package com.qc.aeonis.companion.order

import com.qc.aeonis.entity.CompanionBotEntity

/**
 * A player-issued instruction to the companion.
 *
 * Orders are the highest authority a *player* holds over the bot, short of an active
 * [com.qc.aeonis.companion.command.RhistelPingHandler] ping or an emergency bodyguard
 * response (both of which are emergent and live in [OrderGovernor], not here).
 *
 * ### Why [preemptsAutonomy] exists
 * The field is the literal encoding of one field-testing complaint: the companion kept
 * hunting and scavenging while the owner was telling it to hold position or follow.
 * Every order whose entire meaning is "do this instead of whatever you were doing"
 * sets it, which makes the skill layer yield *before* it ticks rather than after it has
 * already picked a target for the tick.
 *
 * [GUARD], [HUNT] and [FREE] deliberately do **not** preempt: they are the orders that
 * hand autonomy back, so the skill layer stays live under them.
 *
 * @param label human-readable name shown by commands and the companion GUI.
 * @param priority used to order the enforcement subtrees in the behaviour tree; higher wins.
 * @param preemptsAutonomy when true, autonomous skills must terminate and yield immediately.
 * @param forcesLegacyMode the [CompanionBotEntity.ForcedMode] this order maps onto, so the
 *   legacy goal-selector paths (teleport chase, retreat wander) stay consistent with orders.
 */
enum class CompanionOrder(
    val label: String,
    val priority: Int,
    val preemptsAutonomy: Boolean,
    val forcesLegacyMode: CompanionBotEntity.ForcedMode
) {
    /** Stop everything and defend the owner. Emergent only — see [OrderGovernor]. */
    BODYGUARD("Bodyguard", 100, true, CompanionBotEntity.ForcedMode.ATTACK),

    /** Break off and run. */
    RETREAT("Retreat", 90, true, CompanionBotEntity.ForcedMode.RUN_AWAY),

    /** Stand at the current spot and stay there. */
    HOLD("Hold Position", 80, true, CompanionBotEntity.ForcedMode.NONE),

    /** Stay in the owner's slipstream. */
    FOLLOW("Follow", 70, true, CompanionBotEntity.ForcedMode.FOLLOW),

    /** Pick the best visible threat without autonomy. */
    ATTACK("Attack", 65, false, CompanionBotEntity.ForcedMode.ATTACK),

    /** Stay near the current spot but keep working the nearby area. */
    GUARD("Guard Area", 60, false, CompanionBotEntity.ForcedMode.NONE),

    /** Full autonomy with hunting explicitly permitted. */
    HUNT("Hunt", 55, false, CompanionBotEntity.ForcedMode.NONE),

    /** Full autonomy, the default. */
    FREE("Free", 0, false, CompanionBotEntity.ForcedMode.NONE);

    /** True for every order except [BODYGUARD], which only the governor may produce. */
    val isPlayerIssuable: Boolean get() = this != BODYGUARD

    /**
     * Orders a player may hand out, in the order the command help should list them.
     */
    companion object {
        val PLAYER_ORDERS: List<CompanionOrder> =
            listOf(FREE, FOLLOW, HOLD, GUARD, HUNT, ATTACK, RETREAT)

        /** Parses a command argument, tolerating case and `-`/`_` separators. */
        fun parse(raw: String): CompanionOrder? {
            val normalised = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
            return PLAYER_ORDERS.firstOrNull { it.name == normalised }
        }
    }
}

/**
 * Per-bot order runtime: the current order plus the short-lived latches that let the
 * governor override it without losing the player's standing instruction.
 *
 * Deliberately plain mutable state rather than more synched entity data: nothing here
 * needs to reach the client, and the entity's `forcedMode` already carries the one piece
 * the legacy goals care about.
 */
class CompanionOrderState {

    /**
     * The standing order. There is intentionally no expiry — a HOLD means hold until the
     * player says otherwise, which is what field testing asked for. [bodyguardUntilTick]
     * is the one temporary override.
     */
    var order: CompanionOrder = CompanionOrder.FREE
        private set

    /** Game time the current order was issued, for diagnostics and the GUI. */
    var issuedAtTick: Long = -1L
        private set

    /**
     * Governor-owned latch. While `now < bodyguardUntilTick` the companion stays in
     * bodyguard mode even after the attacker dies or the owner heals, which prevents the
     * flip-flop a single-tick danger test would produce during a real fight.
     */
    var bodyguardUntilTick: Long = Long.MIN_VALUE

    /** Applies an order. Safe to call every tick; a no-op when nothing changed. */
    fun set(next: CompanionOrder, nowTick: Long) {
        if (next == order) return
        order = next
        issuedAtTick = nowTick
    }

    /** Extends the bodyguard latch to at least [untilTick]. */
    fun latchBodyguard(untilTick: Long) {
        if (untilTick > bodyguardUntilTick) bodyguardUntilTick = untilTick
    }

    /** True while the bodyguard latch is live. */
    fun isBodyguarding(nowTick: Long): Boolean = nowTick < bodyguardUntilTick

    fun describe(): String = buildString {
        append(order.label)
        if (issuedAtTick >= 0) append(" (issued @").append(issuedAtTick).append("t)")
    }
}
