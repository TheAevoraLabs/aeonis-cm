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
 */
interface CompanionSkill {
    val id: String
    val name: String
    val category: SkillCategory
    val priority: Int

    fun canActivate(bot: CompanionBotEntity): Boolean
    fun tick(bot: CompanionBotEntity): SkillResult
    fun stop(bot: CompanionBotEntity) {}
}
