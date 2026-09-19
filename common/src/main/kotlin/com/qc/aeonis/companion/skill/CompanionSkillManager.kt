package com.qc.aeonis.companion.skill

import com.qc.aeonis.entity.CompanionBotEntity

/**
 * Orchestrates autonomous companion skills, evaluating environmental preconditions,
 * player states, and priority hierarchies every server tick.
 */
class CompanionSkillManager(val bot: CompanionBotEntity) {

    val huntingFeeding = HuntingFeedingSkill()
    val autoScavenger = AutoScavengerSkill()
    val buildingSupport = BuildingSupportSkill()
    val autonomousCrafting = AutonomousCraftingSkill()
    val adaptiveLearning = AdaptiveLearningSkill()

    val registeredSkills: List<CompanionSkill> = listOf(
        huntingFeeding,
        autoScavenger,
        buildingSupport,
        autonomousCrafting,
        adaptiveLearning
    ).sortedByDescending { it.priority }

    var activeSkill: CompanionSkill? = null

    fun tick(): SkillResult {
        // Cognitive learning ticks in background
        if (adaptiveLearning.canActivate(bot)) {
            adaptiveLearning.tick(bot)
        }

        // Evaluate priority skills
        for (skill in registeredSkills) {
            if (skill === adaptiveLearning) continue // Handled above

            if (skill.canActivate(bot)) {
                if (activeSkill != null && activeSkill !== skill) {
                    activeSkill?.stop(bot)
                }
                activeSkill = skill
                val result = skill.tick(bot)
                if (result.status == SkillStatus.SUCCESS) {
                    when (skill) {
                        is HuntingFeedingSkill -> adaptiveLearning.recordFoodGiven()
                        is AutoScavengerSkill -> adaptiveLearning.recordDropCollected()
                        is BuildingSupportSkill -> adaptiveLearning.recordBlockAssisted()
                    }
                }
                if (result.status != SkillStatus.RUNNING) {
                    activeSkill = null
                }
                return result
            }
        }

        activeSkill?.stop(bot)
        activeSkill = null
        return SkillResult.IDLE
    }
}
