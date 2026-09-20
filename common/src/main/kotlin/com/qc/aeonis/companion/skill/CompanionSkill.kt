package com.qc.aeonis.companion.skill

import com.qc.aeonis.entity.CompanionBotEntity

enum class SkillCategory {
    SURVIVAL,
    LOGISTICS,
    CRAFTING,
    BUILDING,
    LOCOMOTION,
    COGNITIVE
}

enum class SkillStatus {
    SUCCESS,
    RUNNING,
    FAILURE,
    IDLE
}

data class SkillResult(
    val status: SkillStatus,
    val message: String = "",
    val cooldownTicks: Int = 10
) {
    companion object {
        val SUCCESS = SkillResult(SkillStatus.SUCCESS, cooldownTicks = 10)
        val RUNNING = SkillResult(SkillStatus.RUNNING, cooldownTicks = 0)
        val FAILURE = SkillResult(SkillStatus.FAILURE, cooldownTicks = 20)
        val IDLE = SkillResult(SkillStatus.IDLE, cooldownTicks = 10)
    }
}

/**
 * Autonomous skill interface executable by the Companion Bot.
 *
 * ### Static priority is not the whole story
 * [priority] orders skills that are merely *available*. Two dynamic adjustments sit on top of it,
 * because a static list alone produced visibly contradictory behaviour — the companion would
 * start walking somewhere, get distracted, come back, and set off again:
 *
 * - [urgency] lets a skill claim more than its static priority *this tick* when its need has
 *   become genuinely pressing (a starving owner, a body about to fail).
 * - [opportunistic] marks filler work that must never displace real work, which is how
 *   scavenging and book-keeping stop pre-empting the task the player actually asked for.
 *
 * On top of both, the manager grants a *commitment bonus* to whichever skill is already running,
 * so a started job is finished unless something with a clearly higher claim turns up.
 */
interface CompanionSkill {
    val id: String
    val name: String
    val category: SkillCategory
    val priority: Int

    /**
     * Extra priority for this tick, on top of [priority].
     *
     * Intended for needs that escalate: returning 0 by default means a skill is never able to
     * jump the queue on its own.
     */
    fun urgency(bot: CompanionBotEntity): Int = 0

    /**
     * True when this skill is filler that may only run when nothing else can.
     *
     * Opportunistic skills are considered only after every non-opportunistic skill has declined,
     * so they can never interrupt an errand in progress.
     */
    val opportunistic: Boolean get() = false

    fun canActivate(bot: CompanionBotEntity): Boolean
    fun tick(bot: CompanionBotEntity): SkillResult
    fun stop(bot: CompanionBotEntity) {}
}
