package com.qc.aeonis.companion.progression

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.skill.SkillResult
import com.qc.aeonis.companion.skill.SkillStatus
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container

/**
 * Uses a furnace the player already owns: loads raw meat, keeps it fuelled, collects the
 * output, and hands cooked food to a hungry owner.
 *
 * ### Why it drives the furnace rather than crafting cooked meat
 * Cooking is a *time-based world process*, not a recipe the companion can shortcut. Inserting
 * real items into a real furnace means the cooking actually takes the vanilla time, stops when
 * the chunk unloads, and produces exactly the items a player would have got. Faking the result
 * (consume raw, spawn cooked) would be indistinguishable in a screenshot and wrong everywhere
 * else — including for anything that later inspects what the companion did.
 *
 * ### Slot numbering
 * Vanilla's `AbstractFurnaceBlockEntity` declares `SLOT_INPUT`, `SLOT_FUEL` and `SLOT_RESULT` as
 * *protected* constants, so they are not reachable from outside the class. Their indices are
 * stable (0, 1, 2) and are asserted here through the [Container] interface instead, which is the
 * only supported way to touch a furnace's contents from another package.
 */
class CookingSupport(private val bot: CompanionBotEntity) {

    /** What the furnace stage is currently doing, for skill messages and `/comp stats`. */
    enum class Phase {
        NO_FURNACE,
        OUT_OF_RANGE,
        NEEDS_MEAT,
        LOADED_MEAT,
        ADDED_FUEL,
        COLLECTED_OUTPUT,
        COOKING
    }

    data class Status(
        val phase: Phase,
        val furnace: BlockPos?,
        val message: String
    )

    companion object {
        /** Radius searched for a furnace, around the owner first then the companion. */
        const val SEARCH_RADIUS = 16

        /** Squared distance within which the companion can reach into a furnace. */
        const val INTERACT_RANGE_SQR = 9.0

        /** Backing indices inside a vanilla furnace. */
        const val SLOT_INPUT = 0
        const val SLOT_FUEL = 1
        const val SLOT_RESULT = 2

        /** Raw items loaded per visit; one visit should keep a furnace busy for a while. */
        const val MAX_BATCH = 8

        /** Fuel items topped up per visit. */
        const val FUEL_BATCH = 3

        /** Ticks a found furnace stays valid before the search is repeated (2 seconds). */
        const val SEARCH_CACHE_TICKS = 40L
    }

    /**
     * Furnace search cache.
     *
     * The search is a 33×33×13 block-entity walk — far too expensive to repeat every time the
     * progression snapshot is built, and the snapshot builder calls [inspect] twice per refresh.
     * The cached position is revalidated cheaply (block entity still present and still a 3-slot
     * container) so a furnace another player breaks is noticed on the next query rather than at
     * the end of the TTL.
     */
    private var cachedFurnace: BlockPos? = null
    private var cachedFurnaceLevel: ServerLevel? = null
    private var cachedFurnaceTick: Long = Long.MIN_VALUE

    /** Nearest furnace within [SEARCH_RADIUS] of the owner, falling back to the companion. */
    fun findFurnace(level: ServerLevel, owner: ServerPlayer): BlockPos? {
        val now = level.gameTime
        val cached = cachedFurnace
        if (cached != null && cachedFurnaceLevel === level && now - cachedFurnaceTick < SEARCH_CACHE_TICKS) {
            if (isStillFurnace(level, cached)) return cached
            invalidateFurnaceCache()
        }

        val found = findFurnaceNear(level, owner.blockPosition())
            ?: findFurnaceNear(level, bot.blockPosition())
        cachedFurnace = found
        cachedFurnaceLevel = level
        cachedFurnaceTick = now
        return found
    }

    private fun isStillFurnace(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.hasChunkAt(pos)) return false
        val entity = level.getBlockEntity(pos) ?: return false
        return entity is Container && entity.containerSize == 3
    }

    /** Drops the cached furnace, e.g. after the player demolishes the kitchen. */
    fun invalidateFurnaceCache() {
        cachedFurnace = null
        cachedFurnaceLevel = null
        cachedFurnaceTick = Long.MIN_VALUE
    }

    private fun findFurnaceNear(level: ServerLevel, origin: BlockPos): BlockPos? {
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dz in -SEARCH_RADIUS..SEARCH_RADIUS) {
                for (dy in -6..6) {
                    val pos = origin.offset(dx, dy, dz)
                    if (!level.hasChunkAt(pos)) continue
                    val entity = level.getBlockEntity(pos) ?: continue
                    if (entity !is Container) continue
                    // A furnace is a 3-slot container: a chest or barrel is not a valid target.
                    if (entity.containerSize != 3) continue
                    val distance = dx * dx + dz * dz + dy * dy.toDouble() * 4
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = pos
                    }
                }
            }
        }
        return best
    }

    /** Read-only description of what the stage would do next; used by the progression skill. */
    fun inspect(owner: ServerPlayer): Status {
        val level = bot.level() as? ServerLevel
            ?: return Status(Phase.NO_FURNACE, null, "no server level")
        val furnace = findFurnace(level, owner)
            ?: return Status(Phase.NO_FURNACE, null, "no furnace within $SEARCH_RADIUS blocks")

        val container = level.getBlockEntity(furnace) as? Container
            ?: return Status(Phase.NO_FURNACE, furnace, "block entity is not a container")

        val inventory = bot.inventory
        val output = container.getItem(SLOT_RESULT)
        if (!output.isEmpty) {
            return Status(Phase.COLLECTED_OUTPUT, furnace, "cooked ${output.hoverName.string} ready")
        }

        val input = container.getItem(SLOT_INPUT)
        if (input.isEmpty && FoodLedger.countRawMeat(inventory) > 0) {
            return Status(Phase.LOADED_MEAT, furnace, "raw meat ready to load")
        }

        val fuel = container.getItem(SLOT_FUEL)
        if (fuel.isEmpty && FoodLedger.countFuel(inventory) > 0) {
            return Status(Phase.ADDED_FUEL, furnace, "fuel ready to load")
        }

        return Status(Phase.COOKING, furnace, "cooking in progress")
    }

    /**
     * One furnace interaction step.
     *
     * Order is collect → load → fuel. Collecting first frees the output slot, and putting fuel
     * in before meat would burn a fuel item on an empty furnace.
     */
    fun tick(owner: ServerPlayer): SkillResult {
        val level = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        val furnace = findFurnace(level, owner)
            ?: return SkillResult(SkillStatus.FAILURE, "No furnace within $SEARCH_RADIUS blocks", cooldownTicks = 80)

        if (bot.blockPosition().distSqr(furnace) > INTERACT_RANGE_SQR) {
            bot.navigation.moveTo(furnace.x + 0.5, (furnace.y + 1).toDouble(), furnace.z + 0.5, 1.25)
            return SkillResult(SkillStatus.RUNNING, "Walking to the furnace at ${furnace.toShortString()}", cooldownTicks = 0)
        }

        val container = level.getBlockEntity(furnace) as? Container
            ?: return SkillResult(SkillStatus.FAILURE, "Furnace block entity unavailable", cooldownTicks = 40)

        // 1. Collect anything already cooked.
        val output = container.getItem(SLOT_RESULT)
        if (!output.isEmpty) {
            val taken = container.removeItem(SLOT_RESULT, output.count)
            val leftover = bot.inventory.collectDrop(taken)
            if (!leftover.isEmpty) {
                container.setItem(SLOT_RESULT, leftover)
                return SkillResult(SkillStatus.FAILURE, "Backpack full; cannot collect cooked food", cooldownTicks = 60)
            }
            container.setChanged()
            bot.inventory.setChanged()
            return SkillResult(
                SkillStatus.SUCCESS,
                "Collected ${taken.count} ${taken.hoverName.string} from the furnace",
                cooldownTicks = 20
            )
        }

        // 2. Load raw meat.
        val input = container.getItem(SLOT_INPUT)
        if (input.isEmpty || input.count < MAX_BATCH) {
            val meatSlot = FoodLedger.firstRawMeatSlot(bot.inventory)
            if (meatSlot >= 0) {
                val meat = bot.inventory.getItem(meatSlot)
                val room = MAX_BATCH - input.count
                val moving = minOf(room, meat.count)
                if (moving > 0) {
                    val toLoad = meat.split(moving)
                    if (input.isEmpty) {
                        container.setItem(SLOT_INPUT, toLoad)
                    } else {
                        input.grow(toLoad.count)
                        container.setItem(SLOT_INPUT, input)
                    }
                    if (meat.isEmpty) bot.inventory.setItem(meatSlot, net.minecraft.world.item.ItemStack.EMPTY)
                    container.setChanged()
                    bot.inventory.setChanged()
                    return SkillResult(
                        SkillStatus.SUCCESS,
                        "Loaded $moving ${toLoad.hoverName.string} into the furnace",
                        cooldownTicks = 20
                    )
                }
            }
        }

        // 3. Top up fuel.
        val fuel = container.getItem(SLOT_FUEL)
        if (fuel.isEmpty || fuel.count < FUEL_BATCH) {
            val fuelSlot = FoodLedger.firstFuelSlot(bot.inventory)
            if (fuelSlot >= 0) {
                val fuelStack = bot.inventory.getItem(fuelSlot)
                val room = FUEL_BATCH - fuel.count
                val moving = minOf(room, fuelStack.count)
                if (moving > 0) {
                    val toLoad = fuelStack.split(moving)
                    if (fuel.isEmpty) {
                        container.setItem(SLOT_FUEL, toLoad)
                    } else {
                        fuel.grow(toLoad.count)
                        container.setItem(SLOT_FUEL, fuel)
                    }
                    if (fuelStack.isEmpty) bot.inventory.setItem(fuelSlot, net.minecraft.world.item.ItemStack.EMPTY)
                    container.setChanged()
                    bot.inventory.setChanged()
                    return SkillResult(
                        SkillStatus.SUCCESS,
                        "Added $moving ${toLoad.hoverName.string} as furnace fuel",
                        cooldownTicks = 20
                    )
                }
            }
        }

        // Nothing to collect, load or feed: let it cook, and hand over cooked food meanwhile.
        if (owner.foodData.foodLevel < 18 && FoodLedger.countCookedMeat(bot.inventory) > 0) {
            if (FoodDelivery.deliverFood(bot, owner, level, preferCooked = true, announcement = null)) {
                return SkillResult(SkillStatus.SUCCESS, "Handed cooked food to the owner", cooldownTicks = 40)
            }
        }

        return SkillResult(SkillStatus.RUNNING, "Furnace is cooking at ${furnace.toShortString()}", cooldownTicks = 0)
    }

    /** Raw meat the stage would consume, used to size the snapshot. */
    fun availableRawMeat(): Int = FoodLedger.countRawMeat(bot.inventory)

    /** Cooked meat currently held, used to decide whether delivery is even possible. */
    fun availableCookedMeat(): Int = FoodLedger.countCookedMeat(bot.inventory)

    /** Backpack free slots, used to decline a collection that would not fit. */
    fun freeBackpackSlots(): Int = ItemLedger.freeSlots(bot.inventory)
}
