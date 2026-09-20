package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.order.OrderGovernor
import com.qc.aeonis.companion.progression.CraftingTableUnlock
import com.qc.aeonis.entity.CompanionBotEntity

/**
 * Orchestrates the companion's autonomous skills, evaluating preconditions once per tick and
 * ticking the highest-claim skill.
 *
 * ### Why this class changed: the companion contradicted itself
 * The first version picked "the highest-priority skill whose preconditions hold" from scratch every
 * single tick. With six skills whose preconditions come and go independently — a log is in range,
 * then a dropped item appears, then the log is nearer again — that produced the reported behaviour:
 * start walking somewhere, change its mind, come back, and set off again. Nothing was *wrong* with
 * any individual decision; the coordinator simply had no memory, so it re-decided constantly.
 *
 * Three mechanisms now give it memory:
 *
 * 1. **Commitment bonus.** While a skill is running, its effective priority is raised by
 *    [COMMITMENT_BONUS], so it keeps the tick unless something claims clearly more. A job that has
 *    already been started gets finished.
 * 2. **Dynamic urgency** ([CompanionSkill.urgency]). A skill can escalate itself when its need
 *    becomes pressing — a starving owner. Nothing else can jump the commitment bonus.
 * 3. **Opportunistic filler** ([CompanionSkill.opportunistic]). Scavenging and book-keeping are
 *    considered only after every real job has declined, so they can never interrupt one.
 *
 * ### Bounded commitment
 * A commitment that could not end would be a livelock. The incumbent is released when its
 * preconditions stop holding, or after [MAX_COMMITMENT_TICKS], whichever comes first.
 *
 * ### Preemption still wins everything
 * Orders, pings and the bodyguard latch are evaluated first and stop the active skill outright.
 * Commitment is a tie-breaker among autonomous jobs; it is not a way to ignore the player.
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

    /** Every skill, in declaration order; ordering for selection is computed per tick. */
    val registeredSkills: List<CompanionSkill> = listOf(
        huntingFeeding,
        autoScavenger,
        progressionLoop,
        buildingSupport,
        autonomousCrafting,
        adaptiveLearning
    )

    companion object {
        /**
         * Priority added to the running skill.
         *
         * Sized to sit above the gap between adjacent skill priorities (5-10) and below the gap
         * between an errand and an emergency, so it commits without becoming unstoppable.
         */
        const val COMMITMENT_BONUS = 30

        /** Hard cap on a single commitment, in ticks (30 seconds). */
        const val MAX_COMMITMENT_TICKS = 600L
    }

    var activeSkill: CompanionSkill? = null
        private set

    private var activeSkillSince: Long = 0L

    /** Most recent governor verdict, for diagnostics and the stats command. */
    var lastVerdict: OrderGovernor.OrderVerdict? = null
        private set

    /** Message from the most recent skill tick, for diagnostics. */
    var lastSkillMessage: String = ""
        private set

    /** Why the last hand-off happened; the first thing to look at when behaviour looks erratic. */
    var lastSwitchReason: String = "none yet"
        private set

    /** Consecutive ticks the current skill has held the tick, for `/comp status`. */
    var activeSkillTicks: Long = 0L
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
            return SkillResult(SkillStatus.IDLE, "Autonomy suspended — ${verdict.reason}", cooldownTicks = 0)
        }

        val now = bot.level().gameTime
        refreshCommitment(now)

        // Preconditions are evaluated exactly once per skill per tick. Several skills keep
        // cooldowns or refresh caches inside `canActivate`, so asking twice — once to validate the
        // incumbent and once to select — used to advance those counters twice as fast.
        val admissions = HashMap<CompanionSkill, Boolean>()
        fun admitted(skill: CompanionSkill): Boolean =
            admissions.getOrPut(skill) { skill.canActivate(bot) }

        val incumbent = activeSkill

        val primary = registeredSkills
            .filter { !it.opportunistic && it !== adaptiveLearning }
            .sortedByDescending { effectivePriority(it, incumbent, now) }

        for (skill in primary) {
            if (!admitted(skill)) continue
            return run(skill, now)
        }

        // Nothing real to do: opportunistic filler may take the tick, but only now.
        val filler = registeredSkills
            .filter { it.opportunistic && it !== adaptiveLearning }
            .sortedByDescending { effectivePriority(it, incumbent, now) }

        for (skill in filler) {
            if (!admitted(skill)) continue
            return run(skill, now)
        }

        stopActiveSkill("no skill had anything to do")
        return SkillResult.IDLE
    }

    /**
     * Priority used for ranking this tick: static priority, plus the skill's own dynamic urgency,
     * plus the commitment bonus when it is the incumbent.
     */
    private fun effectivePriority(skill: CompanionSkill, incumbent: CompanionSkill?, now: Long): Int {
        var score = skill.priority + skill.urgency(bot)
        if (skill === incumbent && now - activeSkillSince < MAX_COMMITMENT_TICKS) {
            score += COMMITMENT_BONUS
        }
        return score
    }

    /**
     * Releases the incumbent when its reason for running has gone, or when its commitment has run
     * its course. Without this the commitment bonus would be a livelock rather than a stabiliser.
     */
    private fun refreshCommitment(now: Long) {
        val incumbent = activeSkill ?: return
        activeSkillTicks = now - activeSkillSince

        if (activeSkillTicks >= MAX_COMMITMENT_TICKS) {
            stopActiveSkill("commitment window of ${MAX_COMMITMENT_TICKS}t elapsed for ${incumbent.id}")
            return
        }
        if (!incumbent.canActivate(bot)) {
            stopActiveSkill("${incumbent.id} preconditions no longer hold")
        }
    }

    /** Ticks [skill] for this tick, recording the hand-off when the incumbent changes. */
    private fun run(skill: CompanionSkill, now: Long): SkillResult {
        if (activeSkill !== skill) {
            val previous = activeSkill
            if (previous != null) {
                previous.stop(bot)
                lastSwitchReason = "${previous.id} → ${skill.id} " +
                    "(p=${previous.priority + previous.urgency(bot)} vs p=${skill.priority + skill.urgency(bot)})"
            } else {
                lastSwitchReason = "idle → ${skill.id}"
            }
            activeSkill = skill
            activeSkillSince = now
            activeSkillTicks = 0L
        }

        val result = skill.tick(bot)
        lastSkillMessage = "${skill.id}: ${result.message}"

        if (result.status == SkillStatus.SUCCESS) {
            recordSuccess(skill)
        }

        // A skill that finished or failed releases the tick immediately; a RUNNING one keeps it
        // for the next tick via the commitment bonus.
        if (result.status != SkillStatus.RUNNING) {
            activeSkill = null
            activeSkillTicks = 0L
        }
        return result
    }

    /**
     * Releases the active skill, giving it a chance to drop targets, navigation and dig sessions.
     * Safe to call when nothing is active.
     */
    fun stopActiveSkill(reason: String) {
        activeSkill?.let { skill ->
            skill.stop(bot)
            lastSkillMessage = "${skill.id}: stopped ($reason)"
        }
        lastSwitchReason = reason
        activeSkill = null
        activeSkillTicks = 0L
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

    /** One-line summary for `/comp status`: stage, order, active work, and why it changed. */
    fun describe(): String {
        val stage = progressionLoop.describeProgression()
        val active = activeSkill?.id ?: "idle"
        val verdict = lastVerdict?.let { "${it.mode.name}<${it.reason}>" } ?: "unevaluated"
        return "stage=$stage | active=$active (${activeSkillTicks}t) | governor=$verdict | last switch: $lastSwitchReason"
    }
}
