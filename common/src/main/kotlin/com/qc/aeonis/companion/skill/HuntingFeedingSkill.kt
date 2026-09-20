package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.order.OrderGovernor
import com.qc.aeonis.companion.order.WeaponSelector
import com.qc.aeonis.companion.progression.FoodLedger
import com.qc.aeonis.companion.ui.CompanionDialogueManager
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.chicken.Chicken
import net.minecraft.world.entity.animal.cow.Cow
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.SwingAnimation

/**
 * Feeds the owner from backpack reserves, and hunts animals only when it is genuinely short
 * of food and properly equipped.
 *
 * ### The three field failures this class exists to fix
 * 1. **Runaway hunting.** The old version re-selected an animal every time its target died,
 *    forever, because "the owner is hungry" stayed true while the bot was hunting. There is
 *    now a [HuntLedger] quota: two animals per session by default, after which the skill
 *    stops and the session's food is delivered or cooked before any new hunt can start.
 * 2. **Bare-handed punching.** [WeaponSelector] gates the whole skill: with no sword or axe
 *    there is no hunt at all. The old code "equipped the best weapon" by scanning for any
 *    sword or axe and, finding none, simply swung anyway.
 * 3. **Ignoring orders.** [OrderGovernor] is consulted before anything else, so `HOLD`,
 *    `FOLLOW`, `RETREAT`, a live Rhistel ping or an owner in danger all end the hunt on the
 *    tick they happen rather than after the current animal dies.
 *
 * ### Why the quota resets on session boundaries, not on a timer
 * A time-based reset is what produced the original loop: hunt, wait, hunt. The ledger instead
 * clears only once the bot has *delivered* the meat it went out for, so "go hunt" is always
 * tied to a concrete outstanding need.
 */
class HuntingFeedingSkill : CompanionSkill {

    override val id: String = "hunting_feeding"
    override val name: String = "Hunting & Feeding"
    override val category: SkillCategory = SkillCategory.SURVIVAL
    override val priority: Int = 85

    /**
     * How many animals one hunt session may take.
     *
     * Three is the field-tested ceiling: enough to feed a hungry owner through a night, few
     * enough that the companion stops before stripping the local herd.
     */
    var animalsPerSession: Int = 3
        private set

    /** Counts kills within the current session and gates the skill once it is spent. */
    inner class HuntLedger {
        var animalsTaken: Int = 0
            private set
        var meatSecured: Int = 0
            private set

        val quotaSpent: Boolean get() = animalsTaken >= animalsPerSession

        fun recordKill(): Unit { animalsTaken++ }

        fun recordMeat(count: Int): Unit { meatSecured += count }

        /** Clears the session once its produce has been handed over. */
        fun reset() {
            animalsTaken = 0
            meatSecured = 0
        }
    }

    val ledger = HuntLedger()

    private var targetAnimal: Animal? = null
    private var attackCooldown: Int = 0

    /** Owner hunger level at or below which feeding is worth interrupting for. */
    private val hungerFeedingThreshold = 14

    /** Ticks between melee swings while hunting. */
    private val attackIntervalTicks = 15

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        val owner = bot.getOwnerPlayer() ?: return false

        // Orders, pings and owner danger always win; a hunter that keeps hunting through a
        // RETREAT order is precisely the reported bug.
        if (OrderGovernor.evaluate(bot).preemptSkills) return false

        val hungry = owner.foodData.foodLevel < hungerFeedingThreshold
        val hasFood = FoodLedger.hasAnyFood(bot.inventory)

        // Feeding an already-hungry owner only needs food in the backpack, never a hunt.
        if (hungry && hasFood) return true

        // Hunting requires an actual need, a spent-free quota and a real weapon.
        if (!hungry && hasFood) return false
        if (ledger.quotaSpent) return false
        if (!WeaponSelector.hasMeleeWeapon(bot)) return false
        if (FoodLedger.hasAnyFood(bot.inventory) && !hungry) return false

        return findNearbyHuntableAnimal(bot) != null
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val serverLevel = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        val owner = bot.getOwnerPlayer() ?: return SkillResult.FAILURE

        if (attackCooldown > 0) attackCooldown--

        // Orders can arrive mid-hunt; abort rather than finish the swing.
        if (OrderGovernor.evaluate(bot).preemptSkills) {
            stop(bot)
            return SkillResult(SkillStatus.IDLE, "Hunt aborted: order override", cooldownTicks = 0)
        }

        // Step 1: deliver food to a hungry owner (any food, cooked or not).
        if (owner.foodData.foodLevel < 18 && FoodLedger.hasAnyFood(bot.inventory)) {
            if (bot.distanceToSqr(owner) <= 9.0) {
                val delivered = deliverFoodToPlayer(bot, owner, serverLevel)
                if (delivered) {
                    ledger.reset()
                    return SkillResult(SkillStatus.SUCCESS, "Delivered food to the owner", cooldownTicks = 40)
                }
            } else {
                bot.navigation.moveTo(owner, 1.25)
                return SkillResult(SkillStatus.RUNNING, "Approaching the hungry owner", cooldownTicks = 0)
            }
        }

        // Step 2: hunting is only legal with a weapon in hand.
        if (!WeaponSelector.equipBestMeleeWeapon(bot)) {
            stop(bot)
            return SkillResult(
                SkillStatus.FAILURE,
                "Cannot hunt without a sword or axe",
                cooldownTicks = 60
            )
        }

        // Step 3: quota check before selecting a new victim, so the cap cannot be overshot.
        if (ledger.quotaSpent) {
            stop(bot)
            return SkillResult(
                SkillStatus.SUCCESS,
                "Hunt quota of $animalsPerSession animals reached; returning with food",
                cooldownTicks = 60
            )
        }

        var animal = targetAnimal
        if (animal == null || !animal.isAlive) {
            animal = findNearbyHuntableAnimal(bot)
            targetAnimal = animal
            if (animal != null) {
                announce(bot, owner, "Hunting a ${animal.type.description.string}.")
            }
        }

        if (animal != null && animal.isAlive) {
            val distanceSqr = bot.distanceToSqr(animal)
            bot.lookControl.setLookAt(animal, 30.0f, 30.0f)

            if (distanceSqr <= 7.0) {
                if (attackCooldown <= 0) {
                    val weapon = bot.inventory.mainHandItem
                    val surrogate = bot.getOrCreateSurrogate()
                    // Player-grade attack: the weapon's own attribute modifiers and crit /
                    // sweep logic apply, which is what makes a sword actually better than a fist.
                    val hit = surrogate?.attackEntity(animal, weapon) ?: bot.doHurtTarget(serverLevel, animal)
                    if (hit) {
                        bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true)
                        serverLevel.playSound(
                            null, bot.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP,
                            SoundSource.NEUTRAL, 0.8f, 1.0f
                        )
                    }
                    attackCooldown = attackIntervalTicks
                    if (!animal.isAlive) {
                        ledger.recordKill()
                        targetAnimal = null
                        ledger.recordMeat(FoodLedger.rawMeatEquivalent(animal))
                    }
                }
                return SkillResult(
                    SkillStatus.RUNNING,
                    "Hunting ${animal.type.description.string} (${ledger.animalsTaken + 1}/$animalsPerSession)",
                    cooldownTicks = 0
                )
            }

            bot.navigation.moveTo(animal, 1.3)
            return SkillResult(SkillStatus.RUNNING, "Tracking ${animal.type.description.string}", cooldownTicks = 0)
        }

        // Nothing in range and the quota is not spent: end the session rather than idling in
        // hunting mode, which is what let the skill hold top priority indefinitely.
        return SkillResult(SkillStatus.IDLE, "No huntable animal in range", cooldownTicks = 20)
    }

    /**
     * Records a kill the companion made outside this skill (e.g. while defending itself), so
     * the quota still counts it rather than letting combat refill the food supply indefinitely.
     */
    fun recordExternalKill(animal: Animal) {
        ledger.recordKill()
        ledger.recordMeat(FoodLedger.rawMeatEquivalent(animal))
    }

    private fun announce(bot: CompanionBotEntity, owner: ServerPlayer, message: String) {
        com.qc.aeonis.companion.ui.CompanionDialogueManager.speak(bot, owner, message, "HUNT")
    }

    /**
     * Delivers one food stack to the owner, dumping it at their feet when their inventory is
     * full so the food is never lost.
     */
    private fun deliverFoodToPlayer(bot: CompanionBotEntity, owner: ServerPlayer, level: ServerLevel): Boolean {
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = bot.inventory.getItem(slot)
            if (stack.isEmpty || !stack.has(DataComponents.FOOD)) continue

            val toGive = stack.split(minOf(2, stack.count))
            if (stack.isEmpty) bot.inventory.setItem(slot, ItemStack.EMPTY)

            val added = owner.inventory.add(toGive)
            if (!added) {
                val drop = ItemEntity(level, owner.x, owner.y + 0.5, owner.z, toGive)
                drop.setPickUpDelay(0)
                level.addFreshEntity(drop)
            }

            level.playSound(null, owner.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.7f, 1.2f)
            com.qc.aeonis.companion.ui.CompanionDialogueManager.speak(
                bot,
                owner,
                "Seems you are tired or hungry, I have got some ${toGive.hoverName.string}!",
                "FOOD"
            )
            bot.inventory.setChanged()
            return true
        }
        return false
    }

    /**
     * Nearest huntable animal, weighted toward the species the companion is best at taking.
     *
     * Babies and animals the companion cannot reach in a straight line are excluded: a hunt
     * that keeps failing to path wastes the whole quota window.
     */
    private fun findNearbyHuntableAnimal(bot: CompanionBotEntity): Animal? {
        val serverLevel = bot.level() as? ServerLevel ?: return null
        val animals = serverLevel.getEntitiesOfClass(Animal::class.java, bot.boundingBox.inflate(20.0)) { animal ->
            animal.isAlive && !animal.isBaby && !animal.isInvulnerable
        }

        return animals.minByOrNull { animal ->
            val speciesPriority = when (animal) {
                is Sheep -> 0
                is Cow -> 1
                is Pig -> 2
                is Chicken -> 3
                else -> 4
            }
            speciesPriority * 100.0 + bot.distanceToSqr(animal)
        }
    }

    override fun stop(bot: CompanionBotEntity) {
        targetAnimal = null
        bot.navigation.stop()
    }
}
