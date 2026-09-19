package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.Vec3

/**
 * Autonomous building and traversal support skill:
 * - Clears obstructing blocks blocking the path
 * - Bridges gaps and hazards underfoot
 * - Pillars up to scale steep ledges
 * - Automatically lights dark caverns with torches
 */
class BuildingSupportSkill : CompanionSkill {
    override val id: String = "building_support"
    override val name: String = "Building & Traversal Support"
    override val category: SkillCategory = SkillCategory.BUILDING
    override val priority: Int = 75

    private var actionCooldown: Int = 0

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        if (actionCooldown > 0) return false
        val level = bot.level() as? ServerLevel ?: return false
        val pos = bot.blockPosition()

        // Check if dark area needs torch lighting
        if (level.getBrightness(LightLayer.BLOCK, pos) < 7 && hasTorches(bot)) {
            return true
        }

        // Check if gap ahead needs bridging
        val moveVec = bot.deltaMovement
        if (moveVec.horizontalDistanceSqr() > 0.01) {
            val facing = Direction.fromYRot(bot.yRot.toDouble())
            val stepAhead = pos.relative(facing)
            val dropAhead = stepAhead.below()
            if (level.getBlockState(dropAhead).isAir && level.getBlockState(dropAhead.below()).isAir && hasBuildingBlocks(bot)) {
                return true
            }

            // Check if wall ahead needs clearing
            if (!level.getBlockState(stepAhead).isAir && !level.getBlockState(stepAhead).canBeReplaced()) {
                return true
            }
        }

        return false
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        if (actionCooldown > 0) {
            actionCooldown--
            return SkillResult.IDLE
        }

        val level = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        val pos = bot.blockPosition()

        // 1. Auto-Lighting: Place Torch if dark
        if (level.getBrightness(LightLayer.BLOCK, pos) < 7 && hasTorches(bot)) {
            val placed = placeTorch(bot, pos, level)
            if (placed) {
                actionCooldown = 40
                return SkillResult(SkillStatus.SUCCESS, "Placed torch to illuminate dark area", cooldownTicks = 40)
            }
        }

        // 2. Traversal: Bridge across gap or scaffold up
        val facing = Direction.fromYRot(bot.yRot.toDouble())
        val stepAhead = pos.relative(facing)
        val dropAhead = stepAhead.below()

        // Gap ahead -> Place bridge block
        if (level.getBlockState(dropAhead).isAir && level.getBlockState(dropAhead.below()).isAir && hasBuildingBlocks(bot)) {
            val placed = bot.tryPlaceBlock(dropAhead, Direction.UP)
            if (placed) {
                actionCooldown = 15
                return SkillResult(SkillStatus.SUCCESS, "Bridged gap ahead", cooldownTicks = 15)
            }
        }

        // Obstacle ahead -> Clear block
        if (!level.getBlockState(stepAhead).isAir && !level.getBlockState(stepAhead).canBeReplaced()) {
            val broken = bot.tryBreakBlock(stepAhead)
            if (broken) {
                actionCooldown = 10
                return SkillResult(SkillStatus.SUCCESS, "Cleared obstacle block", cooldownTicks = 10)
            }
        }

        return SkillResult.IDLE
    }

    private fun hasTorches(bot: CompanionBotEntity): Boolean {
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            if (bot.inventory.getItem(i).`is`(Items.TORCH)) return true
        }
        return false
    }

    private fun hasBuildingBlocks(bot: CompanionBotEntity): Boolean {
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val item = bot.inventory.getItem(i).item
            if (item is BlockItem && item.block != net.minecraft.world.level.block.Blocks.TORCH) {
                return true
            }
        }
        return false
    }

    private fun placeTorch(bot: CompanionBotEntity, pos: BlockPos, level: ServerLevel): Boolean {
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = bot.inventory.getItem(i)
            if (stack.`is`(Items.TORCH)) {
                val surrogate = bot.getOrCreateSurrogate() ?: return false
                val success = surrogate.placeBlock(pos, Direction.UP, stack)
                if (success) {
                    bot.inventory.setChanged()
                    return true
                }
            }
        }
        return false
    }
}
