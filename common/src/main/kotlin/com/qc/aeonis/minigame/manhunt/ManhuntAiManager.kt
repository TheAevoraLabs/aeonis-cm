package com.qc.aeonis.minigame.manhunt

import com.qc.aeonis.entity.AeonisEntities
import com.qc.aeonis.entity.HunterEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

/**
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                  AUTONOMOUS AI HUNTER BOT MANAGER                          ║
 * ║                                                                            ║
 * ║  Orchestrates AI bot hunters for solo single-player & multiplayer sessions:║
 * ║  • Spawns & configures HunterEntity with selectable HunterDifficulty.      ║
 * ║  • Applies preset rules (Assassin, Hitman, Classic, Nether Start).         ║
 * ║  • Multi-dimensional pursuit: tracks runner portals across Overworld,       ║
 * ║    Nether, and The End.                                                    ║
 * ║  • Automatic respawn cycle on hunter elimination (10s respawn timer).      ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 */
class ManhuntAiManager(val game: ManhuntGame) {

    private val logger = LoggerFactory.getLogger("aeonis-manhunt-ai")

    var hunterEntityId: Int? = null
        private set

    private var respawnCountdownTicks: Int = 0
    private var lastTargetDimension: net.minecraft.resources.ResourceKey<Level>? = null

    /**
     * Spawns an Autonomous AI Hunter Bot for this game session.
     */
    fun spawnAiHunter(serverLevel: ServerLevel, spawnPos: BlockPos): HunterEntity? {
        val difficulty = game.settings.hunterDifficulty
        val preset = game.settings.preset

        val hunter = HunterEntity.spawnHunter(
            serverLevel,
            spawnPos,
            game.id,
            difficulty.damageMultiplier.toFloat()
        ) ?: return null

        hunterEntityId = hunter.id
        game.hunterEntityId = hunter.id

        // Apply difficulty gear loadout
        difficulty.equipLoadout(hunter)

        // Apply preset buffs and constraints
        applyPresetRules(hunter, preset)

        // Assign target to nearest alive speedrunner
        retargetHunter(hunter, serverLevel)

        logger.info("Spawned AI Hunter [${difficulty.displayName}] for game ${game.id} at $spawnPos")
        return hunter
    }

    /**
     * Applies preset-specific abilities, constraints, and potion effects to the Hunter.
     */
    fun applyPresetRules(hunter: HunterEntity, preset: ManhuntPreset) {
        when (preset) {
            ManhuntPreset.ASSASSIN -> {
                // Permanent Speed II and Jump Boost II, zero block placing/breaking
                hunter.addEffect(MobEffectInstance(MobEffects.SPEED, 1000000, 1, false, false))
                hunter.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, 1000000, 1, false, false))
                hunter.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 1000000, 0, false, false))
            }
            ManhuntPreset.HITMAN -> {
                // Stealth effects: periodic invisibility when distant
                hunter.addEffect(MobEffectInstance(MobEffects.SPEED, 1000000, 0, false, false))
            }
            ManhuntPreset.NETHER_START -> {
                // Fire resistance grace
                hunter.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200, 0, false, false))
            }
            ManhuntPreset.CLASSIC -> {
                // Standard configuration
            }
        }
    }

    /**
     * Periodic tick for AI hunter maintenance, dimension tracking, and respawning.
     */
    fun tick(serverLevel: ServerLevel) {
        val currentId = hunterEntityId
        val hunter = if (currentId != null) serverLevel.getEntity(currentId) as? HunterEntity else null

        // Handle hunter respawn countdown if killed
        if (hunter == null || !hunter.isAlive) {
            if (respawnCountdownTicks > 0) {
                respawnCountdownTicks--
                if (respawnCountdownTicks == 0) {
                    val respawnPos = game.hunterRespawnPos ?: game.spawnPos ?: BlockPos(0, 80, 0)
                    val newHunter = spawnAiHunter(serverLevel, respawnPos)
                    if (newHunter != null) {
                        game.broadcast("§c§l⚡ The AI Hunter has respawned at $respawnPos!")
                        serverLevel.playSound(null, respawnPos, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.7f, 1.2f)
                    }
                }
            } else if (hunter != null && !hunter.isAlive) {
                // Hunter just died
                respawnCountdownTicks = 200 // 10 seconds respawn delay
                game.broadcast("§a§l⚔ The Hunter Bot was slain! §7Respawning in §e10 seconds§7...")
            }
            return
        }

        // Cross-dimensional pursuit check
        if (game.settings.hunterDifficulty.canCrossDimensions) {
            checkDimensionPursuit(hunter, serverLevel)
        }

        // Retarget periodically or if target is lost
        if (hunter.target == null || !hunter.target!!.isAlive || hunter.tickCount % 60 == 0) {
            retargetHunter(hunter, serverLevel)
        }
    }

    /**
     * Checks if speedrunners have entered another dimension and teleports/pursues them through portals.
     */
    private fun checkDimensionPursuit(hunter: HunterEntity, currentLevel: ServerLevel) {
        val speedrunners = game.getAliveSpeedrunners()
            .mapNotNull { currentLevel.server.playerList.getPlayer(it) }
        if (speedrunners.isEmpty()) return

        val primaryTarget = speedrunners.firstOrNull() ?: return
        val targetLevel = primaryTarget.level() as? ServerLevel ?: return
        val targetDim = targetLevel.dimension()

        if (hunter.level().dimension() != targetDim) {
            // Find portal position in target dimension
            val targetPortalPos = game.getLastPortalUsed(primaryTarget.uuid, targetDim)
                ?: primaryTarget.blockPosition()

            // Safe position around portal
            val safePos = findSafePositionNear(targetLevel, targetPortalPos)

            // Cross dimension
            hunter.teleport(
                net.minecraft.world.level.portal.TeleportTransition(
                    targetLevel,
                    net.minecraft.world.phys.Vec3(safePos.x + 0.5, safePos.y.toDouble(), safePos.z + 0.5),
                    hunter.deltaMovement,
                    hunter.yRot,
                    hunter.xRot,
                    net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING
                )
            )

            game.broadcast("§c§l⚡ The Hunter has pursued speedrunners into the §e${targetDim.identifier().path.uppercase()}§c§l!")
        }
    }

    private fun findSafePositionNear(level: ServerLevel, origin: BlockPos): BlockPos {
        for (dx in -3..3) for (dz in -3..3) for (dy in -2..4) {
            val p = origin.offset(dx, dy, dz)
            if (level.getBlockState(p).isAir && level.getBlockState(p.above()).isAir && level.getBlockState(p.below()).isSolid) {
                return p
            }
        }
        return origin
    }

    private fun retargetHunter(hunter: HunterEntity, serverLevel: ServerLevel) {
        val speedrunners = game.getAliveSpeedrunners()
            .mapNotNull { serverLevel.server.playerList.getPlayer(it) }
            .filter { it.isAlive && !it.isSpectator }

        if (speedrunners.isEmpty()) return
        val nearest = speedrunners.minByOrNull { hunter.distanceToSqr(it) }
        if (nearest != null) {
            hunter.target = nearest
        }
    }

    fun removeHunter() {
        val currentId = hunterEntityId ?: return
        val hunter = game.level.getEntity(currentId) as? HunterEntity
        hunter?.discard()
        hunterEntityId = null
    }
}
