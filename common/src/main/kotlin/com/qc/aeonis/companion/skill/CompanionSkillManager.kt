package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.order.OrderGovernor
import com.qc.aeonis.companion.progression.CraftingTableUnlock
import com.qc.aeonis.entity.CompanionBotEntity

/**
 * Orchestrates the companion's autonomous skills, evaluating every skill's preconditions each
 * server tick and ticking the highest-priority one whose conditions hold.
 *
 * ### Where preemption lives
 * This class is the single gate that makes "orders beat autonomy" true for *every* skill at
 * once. The check happens before any skill is considered, and it *stops* the previously active
 * skill rather than merely declining to start a new one — a hunting skill that is mid-pursuit
 * has a live target and a live navigation path, and those have to be released on the tick the
 * order arrives. Doing it here means no individual skill can forget to.
 *
 * The verdict is also published as [lastVerdict] so the behaviour tree, `/comp stats` and the
 * debug log can all report the same reason the companion changed its mind.
 *
 * ### Priority ordering
 * Skills keep a static priority for a stable, explainable order, and each `canActivate` is
 * written to be conservative — it returns true only when there is a concrete thing to do — so a
 * high-priority skill with nothing to do does not starve the ones below it.
 */
class CompanionSkillManager(val bot: CompanionBotEntity) {

    /**
     * The one crafting-table latch for this bot.
     *
     * Shared between [ProgressionLoopSkill] (which polls it and uses it to pick a stage) and
     * [AutonomousCraftingSkill] (which is hard-gated on it), so the player-first rule cannot be
     * satisfied in one place and violated in another.
     */
    val craftingTableUnlock = CraftingTableUnlock()

    val huntingFeeding = HuntingFeedingSkill()
    val autoScavenger = AutoScavengerSkill()
    val progressionLoop = ProgressionLoopSkill(bot, craftingTableUnlock)
    val buildingSupport = BuildingSupportSkill()
    val autonomousCrafting = AutonomousCraftingSkill(craftingTableUnlock)
    val adaptiveLearning = AdaptiveLearningSkill()

    val registeredSkills: List<CompanionSkill> = listOf(
        huntingFeeding,
        autoScavenger,
        progressionLoop,
        buildingSupport,
        autonomousCrafting,
        adaptiveLearning
    ).sortedByDescending { it.priority }

    var activeSkill: CompanionSkill? = null
        private set

    /** Most recent governor verdict, for diagnostics and the stats command. */
    var lastVerdict: OrderGovernor.OrderVerdict? = null
        private set

    /** Message from the most recent skill tick, for diagnostics. */
    var lastSkillMessage: String = ""
        private set

    fun tick(): SkillResult {
        val verdict = OrderGovernor.evaluate(bot)
        lastVerdict = verdict

        // Cognitive learning is a passive observer of what happened; it runs regardless of
        // preemption so the bot's memory does not develop holes during long orders.
        if (adaptiveLearning.canActivate(bot)) {
            adaptiveLearning.tick(bot)
        }

        if (verdict.preemptSkills) {
            stopActiveSkill("preempted by ${verdict.mode.name.lowercase()}: ${verdict.reason}")
            return SkillResult(
                SkillStatus.IDLE,
                "Autonomy suspended — ${verdict.reason}",
                cooldownTicks = 0
            )
        }

        for (skill in registeredSkills) {
            if (skill === adaptiveLearning) continue // Already handled above.

            if (!skill.canActivate(bot)) continue

            if (activeSkill != null && activeSkill !== skill) {
                activeSkill?.stop(bot)
            }
            activeSkill = skill

            val result = skill.tick(bot)
            lastSkillMessage = "${skill.id}: ${result.message}"

            if (result.status == SkillStatus.SUCCESS) {
                recordSuccess(skill)
            }

            if (result.status != SkillStatus.RUNNING) {
                activeSkill = null
            }
            return result
        }

        stopActiveSkill("no skill had anything to do")
        return SkillResult.IDLE
    }

    /**
     * Releases the active skill, giving it a chance to drop targets and navigation state.
     * Safe to call when nothing is active.
     */
    fun stopActiveSkill(reason: String) {
        activeSkill?.let { skill ->
            skill.stop(bot)
            lastSkillMessage = "${skill.id}: stopped ($reason)"
        }
        activeSkill = null
    }

    /** True when a skill is currently mid-execution. */
    fun isSkillActive(): Boolean = activeSkill != null

    /** Id of the active skill, or null. */
    fun activeSkillId(): String? = activeSkill?.id

    private fun recordSuccess(skill: CompanionSkill) {
        when (skill) {
            is HuntingFeedingSkill -> adaptiveLearning.recordFoodGiven()
            is AutoScavengerSkill -> adaptiveLearning.recordDropCollected()
            is BuildingSupportSkill -> adaptiveLearning.recordBlockAssisted()
            is ProgressionLoopSkill -> adaptiveLearning.recordBlockAssisted()
        }
    }

    /** One-line summary for `/comp stats`: stage, order, active work. */
    fun describe(): String {
        val stage = progressionLoop.describeProgression()
        val active = activeSkill?.id ?: "idle"
        val verdict = lastVerdict?.let { "${it.mode.name}<${it.reason}>" } ?: "unevaluated"
        return "stage=$stage | active=$active | governor=$verdict"
    }
}
