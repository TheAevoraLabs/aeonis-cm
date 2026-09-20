package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.ai.observer.PlayerIntent
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

data class LearningMemory(
    val dangerZones: MutableMap<BlockPos, Long> = mutableMapOf(),
    val animalHotspots: MutableList<BlockPos> = mutableListOf(),
    var totalFoodDelivered: Int = 0,
    var totalDropsCollected: Int = 0,
    var totalBlocksAssisted: Int = 0,
    var bondExp: Int = 0,
    var bondLevel: Int = 1
)

/**
 * Cognitive skill enabling the companion to learn from environmental hazards,
 * player behavior patterns, and animal grazing zones over time.
 */
class AdaptiveLearningSkill : CompanionSkill {
    override val id: String = "adaptive_learning"
    override val name: String = "Adaptive Cognitive Learning"
    override val category: SkillCategory = SkillCategory.COGNITIVE
    override val priority: Int = 50

    /**
     * Book-keeping only: it observes and remembers, so it must never take a tick away from work
     * the player asked for. See [CompanionSkill.opportunistic].
     */
    override val opportunistic: Boolean = true

    val memory = LearningMemory()
    private var lastLearnTick: Long = 0L

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        val now = bot.level().gameTime
        return (now - lastLearnTick) >= 100 // Evaluates every 5 seconds
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val now = bot.level().gameTime
        lastLearnTick = now

        // 1. Clean expired danger zones (> 6000 ticks / 5 minutes old)
        val it = memory.dangerZones.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value > 6000) {
                it.remove()
            }
        }

        // 2. Record danger zone if bot or owner took severe damage recently
        if (bot.lastHurtByMob != null && bot.hurtTime > 0) {
            memory.dangerZones[bot.blockPosition()] = now
        }

        // 3. Level up bond based on assistance activity
        val totalActivity = memory.totalFoodDelivered * 5 + memory.totalDropsCollected + memory.totalBlocksAssisted * 2
        val newLevel = 1 + (totalActivity / 50).coerceAtMost(10)
        if (newLevel > memory.bondLevel) {
            memory.bondLevel = newLevel
            val owner = bot.getOwnerPlayer()
            if (owner != null) {
                com.qc.aeonis.companion.ui.CompanionDialogueManager.speak(
                    bot,
                    owner,
                    "Learning matrix upgraded to Tier $newLevel! (Efficiency & range enhanced)",
                    "TASK"
                )
            }
        }

        return SkillResult(SkillStatus.SUCCESS, "Learning cycle updated (Bond Tier ${memory.bondLevel})", cooldownTicks = 100)
    }

    fun isDangerZone(pos: BlockPos): Boolean {
        return memory.dangerZones.keys.any { it.closerThan(pos, 8.0) }
    }

    fun recordFoodGiven() {
        memory.totalFoodDelivered++
        memory.bondExp += 10
    }

    fun recordDropCollected() {
        memory.totalDropsCollected++
        memory.bondExp += 1
    }

    fun recordBlockAssisted() {
        memory.totalBlocksAssisted++
        memory.bondExp += 2
    }
}
