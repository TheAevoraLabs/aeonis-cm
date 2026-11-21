package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.order.OrderGovernor
import com.qc.aeonis.companion.order.WeaponSelector
import com.qc.aeonis.companion.progression.CookingSupport
import com.qc.aeonis.companion.progression.CraftingTableUnlock
import com.qc.aeonis.companion.progression.FoodLedger
import com.qc.aeonis.companion.progression.ItemLedger
import com.qc.aeonis.companion.progression.ProgressionSnapshot
import com.qc.aeonis.companion.progression.ProgressionStage
import com.qc.aeonis.companion.progression.ProgressionStateMachine
import com.qc.aeonis.companion.progression.ShelterAssistant
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.Items

/**
 * The companion's user-need progression loop: gather wood, then stone, then craft, then help
 * build, then cook — in the order the player's own game actually demands.
 *
 * ### What this skill does and does not own
 * It owns the *ladder* and the gathering/service stages. It deliberately does **not** own
 * 3×3 crafting, which stays with [AutonomousCraftingSkill] so there is one implementation of
 * each recipe; on [ProgressionStage.CRAFTING_SUPPORT] this skill declines to activate and lets
 * the crafting skill take the tick.
 *
 * The ladder itself is [ProgressionStateMachine] over a pure
 * [com.qc.aeonis.companion.progression.ProgressionPlanner] decision, so the "what should the
 * companion want" logic is testable without a world.
 *
 * ### Why the expensive reads are cached
 * A snapshot needs a furnace search, a shelter query and a crafting-table scan. Run every tick
 * they would dominate the server's tick budget for one mob, and `canActivate` is called for
 * every registered skill on every tick. Both the snapshot and the gather-target search are
 * therefore cached with short TTLs — see [SNAPSHOT_INTERVAL_TICKS] and [TARGET_INTERVAL_TICKS] —
 * which costs at most one second of staleness on a decision that changes on the scale of
 * minutes.
 */
class ProgressionLoopSkill(
    private val bot: CompanionBotEntity,
    private val tableUnlock: CraftingTableUnlock
) : CompanionSkill {

    override val id: String = "progression_loop"
    override val name: String = "User Need Progression Loop"
    override val category: SkillCategory = SkillCategory.LOGISTICS
    override val priority: Int = 78

    val state = ProgressionStateMachine()

    private val shelter = ShelterAssistant(bot)
    private val cooking = CookingSupport(bot)

    private var snapshot: ProgressionSnapshot = ProgressionSnapshot()
    private var snapshotTick: Long = Long.MIN_VALUE

    private var gatherTarget: BlockPos? = null
    private var gatherTargetTick: Long = Long.MIN_VALUE

    companion object {
        /** Ticks between full snapshot rebuilds (1 second). */
        const val SNAPSHOT_INTERVAL_TICKS = 20L

        /** Ticks between gather-target searches (2 seconds). */
        const val TARGET_INTERVAL_TICKS = 40L

        /** Radius searched for harvestable logs or ore. */
        const val GATHER_RADIUS = 24

        /** Distance within which the companion will swing at a harvest target. */
        const val GATHER_REACH_SQR = 16.0

        /** Safety valve: never spend more consecutive ticks on one gather target than this. */
        const val MAX_TICKS_PER_TARGET = 200
    }

    private var ticksOnTarget = 0

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        val owner = bot.getOwnerPlayer() ?: return false
        val now = bot.level().gameTime

        // The crafting-table latch is polled *before* the preemption check on purpose: it is
        // passive observation of what the player has built, not companion activity, so a
        // standing FOLLOW order must not stop the companion from noticing the table the player
        // just placed and unlocking its 3×3 recipes.
        tableUnlock.poll(bot, owner)

        if (OrderGovernor.evaluate(bot).preemptSkills) return false

        refreshSnapshotIfStale(owner, now)

        return when (state.stage) {
            // Gathering stages are only active when there is actually something to go and get.
            ProgressionStage.WOOD_GATHERING,
            ProgressionStage.STONE_AGE -> hasGatherTarget(now)

            // Crafting is delegated; see the class comment.
            ProgressionStage.CRAFTING_SUPPORT -> false

            ProgressionStage.SHELTER_CONSTRUCTION -> shelter.isShelterWorkPending(owner)

            ProgressionStage.FURNACE_COOKING -> cooking.inspect(owner).furnace != null

            ProgressionStage.EXPEDITION -> false
        }
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val level = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        val owner = bot.getOwnerPlayer() ?: return SkillResult.FAILURE

        // Orders can land mid-errand; drop it rather than finishing the walk.
        if (OrderGovernor.evaluate(bot).preemptSkills) {
            stop(bot)
            return SkillResult(SkillStatus.IDLE, "Progression errand yielded to an order", cooldownTicks = 0)
        }

        val now = level.gameTime
        refreshSnapshotIfStale(owner, now)

        return when (state.stage) {
            ProgressionStage.WOOD_GATHERING -> harvest(level, BlockTags.LOGS, "wood")
            ProgressionStage.STONE_AGE -> harvest(level, BlockTags.BASE_STONE_OVERWORLD, "stone")
            ProgressionStage.SHELTER_CONSTRUCTION -> shelter.toSkillResult(shelter.tick(owner), "Shelter")
            ProgressionStage.FURNACE_COOKING -> cooking.tick(owner)
            ProgressionStage.CRAFTING_SUPPORT ->
                SkillResult(SkillStatus.IDLE, "Crafting is handled by the crafting skill", cooldownTicks = 10)
            ProgressionStage.EXPEDITION ->
                SkillResult(SkillStatus.IDLE, "Nothing owed; exploring", cooldownTicks = 20)
        }
    }

    override fun stop(bot: CompanionBotEntity) {
        gatherTarget = null
        ticksOnTarget = 0
        bot.scaffoldingEngine.cancelAscent()
    }

    // ------------------------------------------------------------------ stages

    /**
     * Walks to and mines the nearest block in [blockTag].
     *
     * Mining goes through [ScaffoldingEngine.clearObstacle] rather than calling
     * `tryBreakBlock` directly, because the engine already owns the three decisions that make
     * mining look competent: which tool to use, whether the block is worth attempting at all,
     * and collecting the drop afterwards.
     */
    private fun harvest(level: ServerLevel, blockTag: net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>, label: String): SkillResult {
        // Stop as soon as the rung's target is met. Without this the loop had no terminal
        // condition of its own and relied entirely on the stage ladder, so a rung that could not
        // be satisfied (see [ProgressionSnapshot.woodSatisfied]) gathered forever.
        if (gatherTargetMet()) {
            return SkillResult(SkillStatus.SUCCESS, "Enough $label for now", cooldownTicks = 40)
        }

        val target = resolveGatherTarget(level, blockTag)
            ?: return SkillResult(SkillStatus.IDLE, "No $label in range", cooldownTicks = 40)

        ticksOnTarget++
        if (ticksOnTarget > MAX_TICKS_PER_TARGET) {
            gatherTarget = null
            gatherTargetTick = Long.MIN_VALUE
            ticksOnTarget = 0
            return SkillResult(SkillStatus.FAILURE, "Gave up on an unreachable $label target", cooldownTicks = 60)
        }

        if (bot.blockPosition().distSqr(target) > GATHER_REACH_SQR) {
            bot.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.25)
            return SkillResult(SkillStatus.RUNNING, "Walking to $label at ${target.toShortString()}", cooldownTicks = 0)
        }

        // Ascend if the target sits above a ledge the companion cannot walk up.
        if (bot.scaffoldingEngine.requiresAscent(target)) {
            val ascent = bot.scaffoldingEngine.ascendTowards(target)
            return when (ascent.status) {
                SkillStatus.SUCCESS -> {
                    bot.navigation.recomputePath()
                    SkillResult(SkillStatus.RUNNING, "Reached the $label's level", cooldownTicks = 0)
                }
                SkillStatus.FAILURE -> {
                    gatherTarget = null
                    gatherTargetTick = Long.MIN_VALUE
                    SkillResult(SkillStatus.FAILURE, "Cannot climb to the $label", cooldownTicks = 60)
                }
                else -> SkillResult(SkillStatus.RUNNING, "Pillaring to the $label", cooldownTicks = 0)
            }
        }

        val result = bot.scaffoldingEngine.clearObstacle(target)
        return when (result.status) {
            SkillStatus.SUCCESS -> {
                gatherTarget = null
                gatherTargetTick = Long.MIN_VALUE
                ticksOnTarget = 0
                SkillResult(SkillStatus.SUCCESS, "Harvested $label", cooldownTicks = 10)
            }
            SkillStatus.FAILURE -> {
                // Either the wrong tool or a block we should not touch: forget it and let the
                // next search pick a different one instead of retrying forever.
                gatherTarget = null
                gatherTargetTick = Long.MIN_VALUE
                ticksOnTarget = 0
                SkillResult(SkillStatus.FAILURE, "Cannot harvest $label: ${result.message}", cooldownTicks = 60)
            }
            else -> SkillResult(SkillStatus.RUNNING, "Mining $label at ${target.toShortString()}", cooldownTicks = 0)
        }
    }

    // ------------------------------------------------------------------ snapshots and targets

    /** Rebuilds the cached snapshot when it is older than the TTL. */
    private fun refreshSnapshotIfStale(owner: ServerPlayer, now: Long) {
        if (snapshotTick != Long.MIN_VALUE && now - snapshotTick < SNAPSHOT_INTERVAL_TICKS) return
        snapshotTick = now

        val level = bot.level() as? ServerLevel ?: return
        snapshot = buildSnapshot(owner, level)
        state.update(snapshot, now)
    }

    private fun buildSnapshot(owner: ServerPlayer, level: ServerLevel): ProgressionSnapshot {
        val inventory = bot.inventory
        return ProgressionSnapshot(
            logs = ItemLedger.countTag(inventory, ItemTags.LOGS),
            planks = ItemLedger.countTag(inventory, ItemTags.PLANKS),
            sticks = ItemLedger.countItem(inventory, Items.STICK),
            cobblestone = ItemLedger.countItem(inventory, Items.COBBLESTONE),
            coal = ItemLedger.countItem(inventory, Items.COAL) + ItemLedger.countItem(inventory, Items.CHARCOAL),
            hasAnyPickaxe = ItemLedger.hasTool(inventory, ItemTags.PICKAXES),
            hasMeleeWeapon = WeaponSelector.hasMeleeWeapon(bot),
            hasTorches = ItemLedger.has(inventory, Items.TORCH),
            craftingTableUnlocked = tableUnlock.isUnlocked,
            craftingTableReachable = tableUnlock.findReachableTable(level, bot.blockPosition()) != null,
            furnaceNearby = cooking.inspect(owner).furnace != null,
            rawMeat = FoodLedger.countRawMeat(inventory),
            cookedMeat = FoodLedger.countCookedMeat(inventory),
            fuel = FoodLedger.countFuel(inventory),
            playerIsBuilding = shelter.playerIsBuilding(owner),
            shelterWorkPending = shelter.isShelterWorkPending(owner),
            ownerFoodLevel = owner.foodData.foodLevel
        )
    }

    private fun hasGatherTarget(now: Long): Boolean {
        val level = bot.level() as? ServerLevel ?: return false
        val tag = if (state.stage == ProgressionStage.WOOD_GATHERING) BlockTags.LOGS else BlockTags.BASE_STONE_OVERWORLD
        return resolveGatherTarget(level, tag, now) != null
    }

    /**
     * Nearest block in [tag], cached between searches.
     *
     * The cached position is revalidated every tick (a cheap air check) so a target another
     * player mined, or that the companion already took, is dropped immediately instead of being
     * walked to forever.
     */
    private fun resolveGatherTarget(
        level: ServerLevel,
        tag: net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>,
        now: Long = level.gameTime
    ): BlockPos? {
        val cached = gatherTarget
        if (cached != null) {
            if (level.getBlockState(cached).`is`(tag)) {
                return cached
            }
            // The target is gone. Clearing it must also clear the search timestamp, otherwise the
            // TTL below refuses to look for a replacement until it expires and the skill reports
            // "nothing to do" for up to two seconds — which the player sees as the companion
            // giving up mid-errand and starting again. That alternating idle/active cycle was a
            // large part of the reported "it comes back and goes again for no reason".
            gatherTarget = null
            gatherTargetTick = Long.MIN_VALUE
        }

        if (gatherTargetTick != Long.MIN_VALUE && now - gatherTargetTick < TARGET_INTERVAL_TICKS) return null
        gatherTargetTick = now

        val origin = bot.blockPosition()
        var best: BlockPos? = null
        var bestScore = Double.MAX_VALUE
        for (dx in -GATHER_RADIUS..GATHER_RADIUS) {
            for (dz in -GATHER_RADIUS..GATHER_RADIUS) {
                for (dy in -6..6) {
                    val pos = origin.offset(dx, dy, dz)
                    if (!level.hasChunkAt(pos)) continue
                    if (!level.getBlockState(pos).`is`(tag)) continue
                    // Prefer blocks near the bot's own elevation: a vein directly overhead is
                    // usually visible but unreachable, and chasing it wastes the whole window.
                    val score = dx * dx + dz * dz + dy.toDouble() * dy * 6.0
                    if (score < bestScore) {
                        bestScore = score
                        best = pos
                    }
                }
            }
        }
        gatherTarget = best
        ticksOnTarget = 0
        return best
    }

    /**
     * True when the current rung already holds enough of what it is gathering.
     *
     * Reads from the same snapshot the ladder uses, so the "am I done?" answer and the "which rung
     * am I on?" answer can never disagree.
     */
    private fun gatherTargetMet(): Boolean = when (state.stage) {
        ProgressionStage.WOOD_GATHERING ->
            snapshot.logs >= com.qc.aeonis.companion.progression.WOOD_TARGET_LOGS || snapshot.woodSatisfied
        ProgressionStage.STONE_AGE ->
            snapshot.cobblestone >= com.qc.aeonis.companion.progression.STONE_TARGET_COBBLESTONE
        else -> false
    }

    /** Human-readable progression state, surfaced by `/comp stats`. */
    fun describeProgression(): String {
        val tableLine = "crafting table ${tableUnlock.describe()}"
        return "${state.describe()} • $tableLine"
    }

    /** Backpack room, which gates any errand that has to bring material home. */
    fun hasBackpackRoom(): Boolean = ItemLedger.freeSlots(bot.inventory) > 0
}
