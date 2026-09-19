package com.qc.aeonis.companion.skill

import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity

/**
 * Autonomous scavenger skill that continuously sweeps up all nearby item drops
 * and mined blocks within a 16-block radius, eliminating any manual collection overhead.
 */
class AutoScavengerSkill : CompanionSkill {
    override val id: String = "auto_scavenger"
    override val name: String = "Auto Vacuum Scavenger"
    override val category: SkillCategory = SkillCategory.LOGISTICS
    override val priority: Int = 80

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        if (bot.inventory.isBackpackFull()) return false
        val serverLevel = bot.level() as? ServerLevel ?: return false

        // Scan around bot and owner
        val center = bot.boundingBox.inflate(16.0)
        val items = serverLevel.getEntitiesOfClass(ItemEntity::class.java, center) {
            it.isAlive && !it.hasPickUpDelay()
        }
        return items.isNotEmpty()
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val serverLevel = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        if (bot.inventory.isBackpackFull()) return SkillResult.FAILURE

        val items = serverLevel.getEntitiesOfClass(
            ItemEntity::class.java, bot.boundingBox.inflate(16.0)
        ) { it.isAlive && !it.hasPickUpDelay() }

        if (items.isEmpty()) return SkillResult.IDLE

        // Prioritize closest item
        val nearest = items.minByOrNull { bot.distanceToSqr(it) } ?: return SkillResult.IDLE
        val distSqr = bot.distanceToSqr(nearest)

        if (distSqr <= 5.0) { // Vacuum range <= 2.2 blocks
            val originalStack = nearest.item
            val leftover = bot.inventory.collectDrop(originalStack)

            if (leftover.isEmpty) {
                bot.take(nearest, originalStack.count)
                nearest.discard()
                serverLevel.playSound(null, bot.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4f, 1.3f)
                bot.inventory.setChanged()
                return SkillResult(SkillStatus.SUCCESS, "Vacuumed ${originalStack.hoverName.string}", cooldownTicks = 2)
            } else {
                nearest.item = leftover
                bot.inventory.setChanged()
                return SkillResult(SkillStatus.SUCCESS, "Partially collected drop", cooldownTicks = 5)
            }
        } else {
            bot.lookControl.setLookAt(nearest, 20.0f, 20.0f)
            bot.navigation.moveTo(nearest, 1.25)
            return SkillResult(SkillStatus.RUNNING, "Retrieving ${nearest.item.hoverName.string}", cooldownTicks = 0)
        }
    }
}
