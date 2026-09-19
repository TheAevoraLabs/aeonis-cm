package com.qc.aeonis.companion.command

import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.Container
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Executes tactical 3D raycast ping commands dispatched via the Rhistel whistle.
 * Supports targeting hostile mobs (Focus Fire), containers (Deposit), ores (Mine),
 * or ground voxels (Move & Hold).
 */
enum class PingAction {
    FOCUS_ATTACK,
    DEPOSIT_CONTAINER,
    MINE_BLOCK,
    MOVE_AND_HOLD
}

object RhistelPingHandler {

    fun executePing(player: ServerPlayer, bot: CompanionBotEntity): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val eyePos = player.getEyePosition(1.0f)
        val lookVec = player.getLookAngle()
        val reach = 48.0
        val endPos = eyePos.add(lookVec.scale(reach))

        // 1. Entity Raycast (Search for hostile mobs or entities in crosshairs)
        val entityHit = ProjectileUtil.getEntityHitResult(
            player,
            eyePos,
            endPos,
            AABB(eyePos, endPos).inflate(1.0),
            { entity -> entity is LivingEntity && entity != bot && entity != player && entity.isAlive },
            reach * reach
        )

        if (entityHit != null && entityHit.entity is LivingEntity) {
            val target = entityHit.entity as LivingEntity
            bot.target = target
            bot.brainTree?.blackboard?.apply {
                currentTarget = target
                pingAction = PingAction.FOCUS_ATTACK
            }
            level.playSound(null, player.blockPosition(), SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 1.0f, 1.2f)
            player.sendSystemMessage(Component.literal("§6[Rhistel Ping] §cFocus attack on §e${target.name.string}§c!"))
            spawnPingParticles(level, eyePos, target.position())
            return true
        }

        // 2. Block Raycast
        val blockHit = level.clip(
            ClipContext(eyePos, endPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
        )

        if (blockHit.type == HitResult.Type.BLOCK) {
            val hitPos = blockHit.blockPos
            val state = level.getBlockState(hitPos)

            // Spawn particle beam to the pinged location
            spawnPingParticles(level, eyePos, Vec3.atCenterOf(hitPos))
            level.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.0f, 1.5f)

            // Check if container (Chest, Barrel, etc.)
            if (level.getBlockEntity(hitPos) is Container) {
                bot.brainTree?.blackboard?.apply {
                    targetPingPos = hitPos
                    pingAction = PingAction.DEPOSIT_CONTAINER
                }
                bot.navigation.moveTo(hitPos.x.toDouble(), (hitPos.y + 1).toDouble(), hitPos.z.toDouble(), 1.3)
                player.sendSystemMessage(Component.literal("§6[Rhistel Ping] §bDeposit route set to §e${state.block.name.string}§b at ${hitPos.toShortString()}."))
                return true
            }

            // Check if ore block
            if (state.`is`(BlockTags.ORES)) {
                bot.brainTree?.blackboard?.apply {
                    targetPingPos = hitPos
                    pingAction = PingAction.MINE_BLOCK
                }
                bot.navigation.moveTo(hitPos.x.toDouble(), hitPos.y.toDouble(), hitPos.z.toDouble(), 1.3)
                player.sendSystemMessage(Component.literal("§6[Rhistel Ping] §aMining target set to §e${state.block.name.string}§a at ${hitPos.toShortString()}."))
                return true
            }

            // Standard Move & Hold
            val standPos = hitPos.relative(blockHit.direction)
            bot.brainTree?.blackboard?.apply {
                targetPingPos = standPos
                pingAction = PingAction.MOVE_AND_HOLD
            }
            bot.navigation.moveTo(standPos.x.toDouble(), standPos.y.toDouble(), standPos.z.toDouble(), 1.3)
            player.sendSystemMessage(Component.literal("§6[Rhistel Ping] §eMove & hold target set to §f${standPos.toShortString()}§e."))
            return true
        }

        return false
    }

    private fun spawnPingParticles(level: ServerLevel, start: Vec3, end: Vec3) {
        val distance = start.distanceTo(end)
        val steps = (distance * 2).toInt().coerceAtMost(60)
        if (steps <= 0) return
        val stepVec = end.subtract(start).scale(1.0 / steps)

        var current = start
        for (i in 0 until steps) {
            current = current.add(stepVec)
            level.sendParticles(
                ParticleTypes.ELECTRIC_SPARK,
                current.x, current.y, current.z,
                1,
                0.02, 0.02, 0.02,
                0.01
            )
        }
    }
}
