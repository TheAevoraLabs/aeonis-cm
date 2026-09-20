package com.qc.aeonis.companion.skill

import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity

/**
 * Sweeps up nearby item drops so the player never has to.
 *
 * ### Two constraints added after field testing
 * 1. **Opportunistic.** This skill used to hold a static priority (80) high enough to interrupt
 *    whatever the companion was doing, and drops appear constantly around a working player. The
 *    result was a bot that kept abandoning its errand to pick up a stray sapling and then resuming
 *    it — the reported "it does something with no reason, then comes back and goes again". It is
 *    now considered only when every real job has declined.
 * 2. **Leashed to the owner.** The sweep radius is measured around the *owner*, not the companion,
 *    so chasing a drop can never drag the companion away from the player it is supposed to be
 *    helping. Drops further out are left alone unless the companion happens to walk over them.
 */
class AutoScavengerSkill : CompanionSkill {

    override val id: String = "auto_scavenger"
    override val name: String = "Auto Vacuum Scavenger"
    override val category: SkillCategory = SkillCategory.LOGISTICS
    override val priority: Int = 80

    /** Never interrupts real work; see the class comment. */
    override val opportunistic: Boolean = true

    companion object {
        /** Radius, around the owner, in which drops are worth walking to. */
        const val SWEEP_RADIUS = 12.0

        /** Distance at which the companion can actually hoover a drop up. */
        const val VACUUM_RANGE_SQR = 5.0
    }

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        if (bot.inventory.isBackpackFull()) return false
        val owner = bot.getOwnerPlayer() ?: return false
        return nearestDrop(bot, owner) != null
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val serverLevel = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        val owner = bot.getOwnerPlayer() ?: return SkillResult.FAILURE
        if (bot.inventory.isBackpackFull()) return SkillResult.FAILURE

        val nearest = nearestDrop(bot, owner)
            ?: return SkillResult(SkillStatus.IDLE, "Nothing to collect", cooldownTicks = 20)

        if (bot.distanceToSqr(nearest) > VACUUM_RANGE_SQR) {
            bot.lookControl.setLookAt(nearest, 20.0f, 20.0f)
            bot.navigation.moveTo(nearest, 1.25)
            return SkillResult(SkillStatus.RUNNING, "Collecting ${nearest.item.hoverName.string}", cooldownTicks = 0)
        }

        val originalStack = nearest.item
        val leftover = bot.inventory.collectDrop(originalStack)
        if (leftover.isEmpty) {
            bot.take(nearest, originalStack.count)
            nearest.discard()
            serverLevel.playSound(
                null, bot.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4f, 1.3f
            )
            bot.inventory.setChanged()
            return SkillResult(SkillStatus.SUCCESS, "Collected ${originalStack.hoverName.string}", cooldownTicks = 2)
        }

        nearest.item = leftover
        bot.inventory.setChanged()
        return SkillResult(SkillStatus.SUCCESS, "Partially collected a drop", cooldownTicks = 5)
    }

    /**
     * Closest pickup-eligible drop inside the owner's sweep radius, preferring drops nearest the
     * owner so collecting never pulls the companion steadily away from the player.
     */
    private fun nearestDrop(bot: CompanionBotEntity, owner: net.minecraft.server.level.ServerPlayer): ItemEntity? {
        val serverLevel = bot.level() as? ServerLevel ?: return null
        val drops = serverLevel.getEntitiesOfClass(
            ItemEntity::class.java, owner.boundingBox.inflate(SWEEP_RADIUS)
        ) { it.isAlive && !it.hasPickUpDelay() }
        return drops.minByOrNull { it.distanceToSqr(owner) }
    }
}
