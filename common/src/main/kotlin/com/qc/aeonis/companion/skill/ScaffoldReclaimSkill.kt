package com.qc.aeonis.companion.skill

import com.qc.aeonis.entity.CompanionBotEntity

/**
 * Packs the companion's own scaffolding back up once it is done with it.
 *
 * ### Why this exists as its own skill
 * The traversal skill builds a stair to get somewhere; nothing in it is in a position to decide
 * that the stair is now finished, because at the moment of building the only correct answer to
 * "should I keep this?" is yes. Reclaiming is a *different* decision made at a different time,
 * and it belongs to a different owner.
 *
 * Splitting it out also solves a scheduling problem for free. Reclaiming is filler work: it must
 * never interrupt an errand, because walking backwards to pick up a block you no longer need is
 * exactly the "it goes and comes back for no reason" behaviour the user complained about. Marking
 * this skill [opportunistic] means the skill manager only offers it the tick after every real job
 * has declined — so the stair is collected when the companion genuinely has nothing else to do,
 * and never while it is on its way somewhere.
 *
 * ### Only ever its own blocks
 * Candidates come from [com.qc.aeonis.companion.safety.BuildLedger]'s scaffold records, and the
 * guard ranks our own scaffold above every protective rule, so this can neither dig a player's
 * build nor take back a wall the player asked for (that is recorded as `PERMANENT`).
 */
class ScaffoldReclaimSkill : CompanionSkill {

    override val id: String = "scaffold_reclaim"
    override val name: String = "Scaffold Reclaim"
    override val category: SkillCategory = SkillCategory.BUILDING

    /**
     * Below every real job, and below the other filler, so collecting a stray pillar block is
     * genuinely the last thing the companion would rather do.
     */
    override val priority: Int = 20

    override val opportunistic: Boolean = true

    companion object {
        /** Ticks between reclaim attempts, so the companion does not churn on one block. */
        const val RECLAIM_COOLDOWN_TICKS = 30

        /** How far the companion will walk to collect its own block. */
        const val RECLAIM_RADIUS = 16
    }

    private var cooldown: Int = 0

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        if (cooldown > 0) {
            cooldown--
            return false
        }
        return bot.scaffoldingEngine.hasScaffoldToReclaim(RECLAIM_RADIUS)
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val engine = bot.scaffoldingEngine
        val result = engine.reclaimScaffold(RECLAIM_RADIUS)

        return when (result.status) {
            SkillStatus.SUCCESS -> {
                cooldown = RECLAIM_COOLDOWN_TICKS
                SkillResult(
                    if (result.deltaOps > 0) SkillStatus.SUCCESS else SkillStatus.IDLE,
                    result.message,
                    cooldownTicks = RECLAIM_COOLDOWN_TICKS
                )
            }
            SkillStatus.RUNNING -> SkillResult(SkillStatus.RUNNING, result.message, cooldownTicks = 0)
            else -> {
                cooldown = RECLAIM_COOLDOWN_TICKS
                SkillResult(SkillStatus.FAILURE, result.message, cooldownTicks = RECLAIM_COOLDOWN_TICKS)
            }
        }
    }

    /**
     * Does nothing on purpose: the engine holds no cross-tick intent for reclaiming, and
     * interrupting a reclaim mid-walk must not cancel the traversal skill's own climb state.
     */
    override fun stop(bot: CompanionBotEntity) {}
}
