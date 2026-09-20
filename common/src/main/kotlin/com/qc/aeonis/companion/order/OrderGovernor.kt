package com.qc.aeonis.companion.order

import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Enemy

/**
 * The single authority that decides, once per tick, which *mode* the companion is in.
 *
 * Before this existed, four independent systems each decided priority for themselves —
 * the goal selector, the behaviour tree's root selector, the skill manager's static
 * priority list, and the ping handler's direct blackboard writes. They disagreed, which
 * is exactly how the bot ended up hunting through a `HOLD` order. Every consumer now
 * asks this object first, so there is one answer instead of four.
 */
object OrderGovernor {

    /** Owner health ratio at or below which the companion treats the owner as critical. */
    const val OWNER_CRITICAL_HEALTH_RATIO = 0.35f

    /** How long after taking a hit the owner still counts as "just attacked". */
    const val OWNER_ATTACK_MEMORY_TICKS = 100L

    /** How long bodyguard mode persists once triggered, so a fight cannot cause flip-flop. */
    const val BODYGUARD_LATCH_TICKS = 120L

    /** Horizontal radius searched for hostiles to engage while bodyguarding, around the owner. */
    const val BODYGUARD_ENGAGE_RADIUS = 24.0

    /**
     * What the companion should be doing this tick.
     *
     * [AUTONOMY] is the only mode in which autonomous skills are allowed to run; every
     * other mode is a hard preemption of them.
     */
    enum class AgentMode {
        /** Owner is hurt or critical: abort everything, arm up, engage. */
        BODYGUARD,

        /** A Rhistel ping is outstanding and outranks the standing order. */
        PING_RESPONSE,

        /** A standing order (HOLD/FOLLOW/RETREAT/…) is being enforced. */
        ORDER_ENFORCED,

        /** No override active: skills, combat support and following run normally. */
        AUTONOMY
    }

    /**
     * Why the governor reached its verdict this tick. Kept as data rather than a log line
     * so the behaviour tree, the skill manager and the debugging command all read the
     * same explanation.
     */
    data class OrderVerdict(
        val mode: AgentMode,
        val order: CompanionOrder,
        val ownerInDanger: Boolean,
        val attacker: LivingEntity?,
        val reason: String
    ) {
        /** True when autonomous skills must terminate rather than merely not start. */
        val preemptSkills: Boolean get() = mode != AgentMode.AUTONOMY

        /** True when the companion should be sprinting to the owner with a weapon out. */
        val isBodyguard: Boolean get() = mode == AgentMode.BODYGUARD

        /** True when the companion must not pick its own targets at all. */
        val suppressesCombat: Boolean get() = mode == AgentMode.ORDER_ENFORCED || mode == AgentMode.BODYGUARD
    }

    /**
     * The owner's danger picture.
     *
     * @param attacker the living entity that last hurt the owner, if it is still alive.
     * @param critical true when owner health is at or below [OWNER_CRITICAL_HEALTH_RATIO].
     * @param attackedRecently true when the owner was hurt within [OWNER_ATTACK_MEMORY_TICKS].
     */
    data class OwnerThreat(
        val attacker: LivingEntity?,
        val critical: Boolean,
        val attackedRecently: Boolean
    )

    /**
     * Computes the owner's danger picture, or null when there is no online owner.
     *
     * Note this reads `lastHurtByMob` *without* clearing it: a bot must never consume the
     * player's own combat bookkeeping, since vanilla mob targeting reads the same fields.
     */
    fun ownerThreat(owner: ServerPlayer): OwnerThreat {
        val healthRatio = if (owner.maxHealth > 0f) owner.health / owner.maxHealth else 1f
        val lastAttacker = owner.lastHurtByMob
        val attacker = lastAttacker?.takeIf { it.isAlive && it != owner }
        val recent = owner.tickCount - owner.lastHurtByMobTimestamp <= OWNER_ATTACK_MEMORY_TICKS
        return OwnerThreat(
            attacker = attacker,
            critical = healthRatio <= OWNER_CRITICAL_HEALTH_RATIO,
            attackedRecently = recent && (attacker != null || owner.hurtTime > 0)
        )
    }

    /**
     * Evaluates the companion's mode for this tick and advances the bodyguard latch.
     *
     * Precedence, highest first:
     * 1. bodyguard (owner attacked recently, or owner health critical)
     * 2. an outstanding Rhistel ping
     * 3. a standing order that preempts autonomy
     * 4. autonomy
     *
     * The ping outranking the standing order is deliberate: a ping is a *momentary*
     * deliberate instruction aimed at a specific target, so it should win over a standing
     * follow order, while a standing `HOLD` still stops the bot from wandering off after a
     * stale ping is cleared.
     */
    fun evaluate(bot: CompanionBotEntity): OrderVerdict {
        val state = bot.orderState
        val order = state.order
        val now = bot.level().gameTime

        val owner = bot.getOwnerPlayer()
        val threat = owner?.let { ownerThreat(it) }
        val ownerInDanger = threat != null && (threat.attackedRecently || threat.critical)

        if (ownerInDanger) {
            state.latchBodyguard(now + BODYGUARD_LATCH_TICKS)
        }
        val bodyguarding = state.isBodyguarding(now)

        val pingActive = bot.brainTree?.blackboard?.targetPingPos != null

        val mode = when {
            bodyguarding -> AgentMode.BODYGUARD
            pingActive -> AgentMode.PING_RESPONSE
            order.preemptsAutonomy -> AgentMode.ORDER_ENFORCED
            else -> AgentMode.AUTONOMY
        }

        val reason = when (mode) {
            AgentMode.BODYGUARD -> when {
                threat?.critical == true -> "owner critical (health < ${(OWNER_CRITICAL_HEALTH_RATIO * 100).toInt()}%)"
                threat?.attacker != null -> "owner attacked by ${threat.attacker.name.string}"
                else -> "owner recently hurt"
            }
            AgentMode.PING_RESPONSE -> "Rhistel ping outstanding"
            AgentMode.ORDER_ENFORCED -> "order ${order.name} preempts autonomy"
            AgentMode.AUTONOMY -> "no override; order ${order.name}"
        }

        return OrderVerdict(
            mode = mode,
            order = order,
            ownerInDanger = ownerInDanger,
            attacker = threat?.attacker,
            reason = reason
        )
    }

    /**
     * Picks the hostile the companion should swing at while bodyguarding.
     *
     * Priority is: the entity that actually hurt the owner (revenge), then the nearest
     * [Enemy] to the owner, then the nearest [Enemy] to the bot. Targeting the attacker
     * first matters because the common case is a single skirmisher that keeps kiting
     * *around* the owner, so the nearest-to-owner heuristic would thrash.
     */
    fun selectBodyguardTarget(bot: CompanionBotEntity, verdict: OrderVerdict): LivingEntity? {
        val level = bot.level() as? ServerLevel ?: return null
        val owner = bot.getOwnerPlayer()

        val attacker = verdict.attacker
        if (attacker != null && attacker.isAlive && attacker != bot) return attacker

        val anchor = owner?.position() ?: bot.position()
        val box = bot.boundingBox.move(anchor.subtract(bot.position())).inflate(BODYGUARD_ENGAGE_RADIUS)

        val hostiles = level.getEntitiesOfClass(LivingEntity::class.java, box) { candidate ->
            candidate.isAlive && candidate != bot && candidate != owner && candidate is Enemy
        }
        return hostiles.minByOrNull { it.distanceToSqr(anchor) }
    }

    /**
     * Nearest visible-ish hostile to the bot itself, used by [selectBodyguardTarget]'s
     * caller when the owner is far from the fight.
     */
    fun nearestHostileToBot(bot: CompanionBotEntity, radius: Double): LivingEntity? {
        val level = bot.level() as? ServerLevel ?: return null
        val owner = bot.getOwnerPlayer()
        return level.getEntitiesOfClass(LivingEntity::class.java, bot.boundingBox.inflate(radius)) { candidate ->
            candidate.isAlive && candidate != bot && candidate != owner && candidate is Enemy
        }.minByOrNull { it.distanceToSqr(bot) }
    }

    /** Block the companion should hold on a `HOLD` order, latched the first tick it sees one. */
    fun resolveHoldAnchor(bot: CompanionBotEntity, previous: BlockPos?): BlockPos =
        previous ?: bot.blockPosition()
}
