package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.ai.perception.StratumOcclusionFilter
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
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.animal.Animal
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

    /**
     * Urgency added while the owner is starving. Sized above the skill manager's commitment
     * bonus, because feeding a starving player outranks finishing an errand.
     */
    private val starvingUrgency = 60

    /** Urgency added when the owner is hungry and the companion is already carrying food. */
    private val hungryUrgency = 35

    /** Food level at or below which the owner counts as starving. */
    private val starvingFoodLevel = 6

    /**
     * Maximum distance the companion may drift from the owner while hunting.
     *
     * A hunter that chases an animal out of sight is indistinguishable from a bug; the leash keeps
     * every hunt inside the player's view and stops the companion arriving back mid-fight.
     */
    private val ownerLeashDistance = 20.0

    /** How far from the owner an animal may be for it to be worth hunting. */
    private val ownerAnchoredHuntRadius = 20.0

    /** Ticks to pause after a kill, so a hunt reads as deliberate rather than frantic. */
    private val betweenKillsPause = 40

    private var huntCooldown: Int = 0

    /**
     * Escalates when the owner actually needs food.
     *
     * See [CompanionSkill.urgency]: this is the only mechanism that lets the feeding skill beat an
     * errand the companion has already committed to, and it only fires when the need is real.
     */
    override fun urgency(bot: CompanionBotEntity): Int {
        val owner = bot.getOwnerPlayer() ?: return 0
        val foodLevel = owner.foodData.foodLevel
        return when {
            foodLevel <= starvingFoodLevel -> starvingUrgency
            foodLevel < hungerFeedingThreshold && FoodLedger.hasAnyFood(bot.inventory) -> hungryUrgency
            else -> 0
        }
    }

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
        if (huntCooldown > 0) return false
        if (!WeaponSelector.hasMeleeWeapon(bot)) return false
        if (FoodLedger.hasAnyFood(bot.inventory) && !hungry) return false

        // Stay leashed to the owner: if it has drifted away, regrouping is the job, not hunting.
        if (bot.distanceToSqr(owner) > ownerLeashDistance * ownerLeashDistance) return true

        return findNearbyHuntableAnimal(bot, owner) != null
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val serverLevel = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        val owner = bot.getOwnerPlayer() ?: return SkillResult.FAILURE

        if (attackCooldown > 0) attackCooldown--
        if (huntCooldown > 0) huntCooldown--

        // Orders can arrive mid-hunt; abort rather than finish the swing.
        if (OrderGovernor.evaluate(bot).preemptSkills) {
            stop(bot)
            return SkillResult(SkillStatus.IDLE, "Hunt aborted: order override", cooldownTicks = 0)
        }

        // Leash first: never let a hunt pull the companion away from the player. Serving a hungry
        // owner takes priority over the walk back, so a companion already carrying food hands it
        // over on arrival rather than walking past them.
        if (bot.distanceToSqr(owner) > ownerLeashDistance * ownerLeashDistance) {
            stop(bot)
            bot.navigation.moveTo(owner, 1.3)
            return SkillResult(SkillStatus.RUNNING, "Regrouping with the owner", cooldownTicks = 0)
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

        // Step 4: a short beat between animals. Without it the companion snaps from one carcass to
        // the next on the same tick, which reads as erratic even though each individual choice is
        // sound.
        if (huntCooldown > 0) {
            return SkillResult(
                SkillStatus.RUNNING,
                "Pacing before the next animal (${huntCooldown}t)",
                cooldownTicks = 0
            )
        }

        // The current target is kept until it is dead or genuinely no longer worth chasing. This
        // commitment is what stops the companion oscillating between two animals mid-pursuit.
        var animal = targetAnimal
        if (animal != null && !isStillHuntable(animal, owner)) {
            targetAnimal = null
            animal = null
        }
        if (animal == null) {
            animal = findNearbyHuntableAnimal(bot, owner)
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
                        ledger.recordMeat(FoodLedger.rawMeatEquivalent(animal))
                        targetAnimal = null
                        huntCooldown = betweenKillsPause
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
     * Best animal to hunt right now, or null.
     *
     * Selection is anchored on the **owner**, not the companion: an animal 30 blocks behind the
     * player is not a target, however close it is to the bot. Candidates must also be
     * tactically reachable through `StratumOcclusionFilter`, which is the same test combat uses,
     * so the companion never picks prey it cannot actually path to through a wall, a ravine or a
     * sealed cave. Babies and invulnerable animals are excluded outright.
     *
     * Scoring is meat-yield per distance rather than species-then-distance: a cow yields two meat
     * and a chicken one, so a cow slightly further away is the better hunt, which is what a player
     * would decide.
     */
    private fun findNearbyHuntableAnimal(bot: CompanionBotEntity, owner: ServerPlayer): Animal? {
        val serverLevel = bot.level() as? ServerLevel ?: return null
        val animals = serverLevel.getEntitiesOfClass(
            Animal::class.java, owner.boundingBox.inflate(ownerAnchoredHuntRadius)
        ) { animal -> isHuntable(animal) }

        return animals
            .filter { StratumOcclusionFilter.isTacticallyReachable(bot, it) }
            .minByOrNull { animal ->
                val yield = FoodLedger.rawMeatEquivalent(animal).coerceAtLeast(1)
                val distanceFromOwner = kotlin.math.sqrt(animal.distanceToSqr(owner))
                distanceFromOwner / yield
            }
    }

    /**
     * True while [animal] is still worth chasing: alive, not a baby, and still on the companion's
     * side of the owner's leash. Deliberately cheap — it runs every tick for the committed target.
     */
    private fun isStillHuntable(animal: Animal, owner: ServerPlayer): Boolean {
        if (!isHuntable(animal)) return false
        return animal.distanceToSqr(owner) <= ownerAnchoredHuntRadius * ownerAnchoredHuntRadius * 1.5
    }

    /**
     * True when the animal is legitimate prey.
     *
     * The tamed and leashed exclusions are the fix for the reported "it hunts random things":
     * `Wolf`, `Cat` and `Horse` are all `Animal`s, so the companion used to happily attack the
     * player's own pets and any animal they were leading home. Pets are off the menu by identity
     * (tamed), not by species, so a player's tamed wolf is safe while a wild one is still fair game.
     */
    private fun isHuntable(animal: Animal): Boolean {
        if (!animal.isAlive || animal.isBaby || animal.isInvulnerable) return false
        if (animal is TamableAnimal && animal.isTame) return false
        if (animal is Leashable && animal.isLeashed) return false
        return true
    }

    override fun stop(bot: CompanionBotEntity) {
        targetAnimal = null
        huntCooldown = 0
        bot.navigation.stop()
    }
}
