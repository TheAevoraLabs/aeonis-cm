package com.qc.aeonis.minigame.prophunt

import com.qc.aeonis.util.playNotifySound
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

object HunterAbilityManager {
    private val logger = LoggerFactory.getLogger("aeonis-prophunt-hunters")

    /** Tracked props for tracker darts */
    private val trackedProps = ConcurrentHashMap<UUID, UUID>()

    /** Track previous swing state to catch start of left click */
    private val hunterSwinging = ConcurrentHashMap<UUID, Boolean>()

    // ════════════════════════════════════════════════════════════════════════
    // HUNTER SETUP & RESET
    // ════════════════════════════════════════════════════════════════════════

    fun setupHunter(player: ServerPlayer, game: PropHuntGame) {
        val playerData = game.players[player.uuid] ?: return

        player.setGameMode(GameType.ADVENTURE)
        player.inventory.clearContent()
        giveHunterItems(player, game.settings)

        playerData.scannerCooldown = 0
        playerData.trackerCooldown = 0
        playerData.stunGrenades = game.settings.stunGrenadesPerRound

        player.sendSystemMessage(Component.literal("§c[PropHunt] §7You are a §c§lHUNTER§7! Find and eliminate all props!"))
    }

    private fun giveHunterItems(player: ServerPlayer, settings: PropHuntSettings) {
        val sword = ItemStack(Items.IRON_SWORD)
        sword.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§c§lHunter's Blade"))
        player.inventory.setItem(0, sword)

        val sonar = ItemStack(Items.ECHO_SHARD)
        sonar.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§b§lSonar Ping Radar §7(Right-Click)"))
        player.inventory.setItem(1, sonar)

        val tracker = ItemStack(Items.SPECTRAL_ARROW)
        tracker.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§e§lTracker Dart §7(Right-Click)"))
        player.inventory.setItem(2, tracker)

        val stunGrenades = ItemStack(Items.SNOWBALL)
        stunGrenades.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§d§lStun Grenade §7(Throw)"))
        stunGrenades.count = settings.stunGrenadesPerRound
        player.inventory.setItem(3, stunGrenades)

        val compass = ItemStack(Items.COMPASS)
        compass.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§6§lProp Compass"))
        player.inventory.setItem(4, compass)

        val beef = ItemStack(Items.COOKED_BEEF)
        beef.count = 16
        player.inventory.setItem(8, beef)
    }

    fun resetHunter(player: ServerPlayer) {
        trackedProps.remove(player.uuid)
        hunterSwinging.remove(player.uuid)
        player.removeEffect(MobEffects.STRENGTH)
        player.removeEffect(MobEffects.BLINDNESS)
        player.removeEffect(MobEffects.SLOWNESS)
        player.removeEffect(MobEffects.JUMP_BOOST)
        player.removeEffect(MobEffects.MINING_FATIGUE)
        player.removeEffect(MobEffects.WEAKNESS)
    }

    // ════════════════════════════════════════════════════════════════════════
    // HUNTER CAGE & BLINDNESS (Hiding Grace Period)
    // ════════════════════════════════════════════════════════════════════════

    fun freezeHunter(player: ServerPlayer, freeze: Boolean) {
        val game = PropHuntManager.getPlayerGame(player) ?: return
        val playerData = game.players[player.uuid] ?: return

        if (freeze) {
            playerData.isFrozen = true
            player.addEffect(MobEffectInstance(MobEffects.BLINDNESS, Int.MAX_VALUE, 0, false, false, false))
            player.addEffect(MobEffectInstance(MobEffects.SLOWNESS, Int.MAX_VALUE, 255, false, false, false))
            player.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, Int.MAX_VALUE, 128, false, false, false))
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7You are §cCAGED§7 while props hide!"))
        } else {
            playerData.isFrozen = false
            player.removeEffect(MobEffects.BLINDNESS)
            player.removeEffect(MobEffects.SLOWNESS)
            player.removeEffect(MobEffects.JUMP_BOOST)
            // Speed boost on release to charge in!
            player.addEffect(MobEffectInstance(MobEffects.SPEED, 100, 0, false, false, false))
            player.sendSystemMessage(Component.literal("§a[PropHunt] §7You are §aRELEASED§7! Hunt them down!"))
            player.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.MASTER, 1.0f, 1.0f)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SONAR PING RADAR (Echolocates props within 15 blocks on a timer)
    // ════════════════════════════════════════════════════════════════════════

    fun useScanner(player: ServerPlayer, game: PropHuntGame): Boolean {
        return useSonarPing(player, game)
    }

    fun useSonarPing(player: ServerPlayer, game: PropHuntGame): Boolean {
        val playerData = game.players[player.uuid] ?: return false
        val settings = game.settings
        if (!canUseAbility(player, game, playerData, "sonar ping radar")) return false

        if (playerData.scannerCooldown > 0) {
            val secondsLeft = playerData.scannerCooldown / 20
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7Sonar on cooldown! §e${secondsLeft}s§7 remaining."))
            return false
        }

        playerData.scannerCooldown = settings.scannerCooldownSeconds * 20
        val level = player.level() as? ServerLevel ?: return false
        val scanRadius = 15.0

        // Expanding sonic wave particles
        spawnSonarPulse(player, level, scanRadius)
        player.playNotifySound(SoundEvents.SCULK_CLICKING, SoundSource.PLAYERS, 1.5f, 1.0f)
        player.playNotifySound(SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8f, 2.0f)

        // Echolocate props within 15 blocks
        var detected = 0
        var closestDist = Double.MAX_VALUE

        for (propData in game.getProps()) {
            if (!propData.isAlive) continue
            val propPlayer = level.server.playerList.getPlayer(propData.uuid) ?: continue
            val distance = player.position().distanceTo(propPlayer.position())

            if (distance <= scanRadius) {
                detected++
                if (distance < closestDist) closestDist = distance

                // Closer prop = higher ping pitch
                val pingPitch = (2.2f - (distance.toFloat() / 15.0f * 1.4f)).coerceIn(0.7f, 2.0f)
                player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.2f, pingPitch)

                // Outline prop disguise with glowing for 2 seconds
                propPlayer.addEffect(MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, false))

                val dir = getDirectionString(player, propPlayer)
                player.sendSystemMessage(Component.literal("§b[Sonar] §aPING! §7Prop detected §e$dir §7(~${String.format("%.1f", distance)}m)"))

                // Alert the echolocated prop with warden heartbeat
                propPlayer.playNotifySound(SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.0f, 1.0f)
                propPlayer.sendSystemMessage(Component.literal("§c⚠ [Sonar Alert] You were detected by hunter echolocation!"))
            }
        }

        if (detected == 0) {
            player.sendSystemMessage(Component.literal("§7[Sonar] No props detected within 15 blocks."))
        } else {
            player.sendSystemMessage(Component.literal("§b[Sonar] §7Detected §e$detected§7 prop(s) in echolocation range!"))
        }

        return true
    }

    private fun spawnSonarPulse(player: ServerPlayer, level: ServerLevel, radius: Double) {
        val center = player.position()
        for (ring in 1..3) {
            val ringRadius = (radius / 3.0) * ring
            val particles = (ringRadius * 6).toInt()
            for (i in 0 until particles) {
                val angle = (2 * Math.PI / particles) * i
                val x = center.x + cos(angle) * ringRadius
                val z = center.z + sin(angle) * ringRadius

                level.sendParticles(ParticleTypes.SONIC_BOOM, x, center.y + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0)
                level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, x, center.y + 0.3, z, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TRACKER DART
    // ════════════════════════════════════════════════════════════════════════

    fun useTracker(player: ServerPlayer, game: PropHuntGame): Boolean {
        val playerData = game.players[player.uuid] ?: return false
        val settings = game.settings
        if (!canUseAbility(player, game, playerData, "tracker dart")) return false

        if (playerData.trackerCooldown > 0) {
            val seconds = playerData.trackerCooldown / 20
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7Tracker cooldown: §e${seconds}s§7 remaining."))
            return false
        }

        val level = player.level() as? ServerLevel ?: return false
        var nearestProp: ServerPlayer? = null
        var nearestDist = Double.MAX_VALUE

        for (propData in game.getProps()) {
            if (!propData.isAlive) continue
            val propPlayer = level.server.playerList.getPlayer(propData.uuid) ?: continue
            val dist = player.position().distanceTo(propPlayer.position())
            if (dist < nearestDist) {
                nearestDist = dist
                nearestProp = propPlayer
            }
        }

        if (nearestProp == null) {
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7No props to track!"))
            return false
        }

        playerData.trackerCooldown = settings.trackerCooldownSeconds * 20
        trackedProps[player.uuid] = nearestProp.uuid

        nearestProp.addEffect(MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false, false))
        player.playNotifySound(SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 1.0f, 0.5f)
        player.sendSystemMessage(Component.literal("§e[Tracker] §7Nearest prop marked with Glowing for 10s!"))

        nearestProp.playNotifySound(SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.0f, 1.0f)
        nearestProp.sendSystemMessage(Component.literal("§c§l⚠ [Tracker Alert] §cYou have been MARKED by a tracker!"))

        return true
    }

    // ════════════════════════════════════════════════════════════════════════
    // STUN GRENADE
    // ════════════════════════════════════════════════════════════════════════

    fun onStunGrenadeLand(thrower: ServerPlayer, hitPos: Vec3, game: PropHuntGame) {
        val level = thrower.level() as? ServerLevel ?: return
        val stunRadius = 5.0

        level.sendParticles(ParticleTypes.EXPLOSION, hitPos.x, hitPos.y + 0.5, hitPos.z, 3, 0.2, 0.2, 0.2, 0.0)
        level.sendParticles(ParticleTypes.END_ROD, hitPos.x, hitPos.y + 0.5, hitPos.z, 40, stunRadius / 2, 0.8, stunRadius / 2, 0.05)
        level.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.0f, 1.2f)

        var stunned = 0
        for (propData in game.getProps()) {
            if (!propData.isAlive) continue
            val propPlayer = level.server.playerList.getPlayer(propData.uuid) ?: continue
            val dist = hitPos.distanceTo(propPlayer.position())

            if (dist <= stunRadius) {
                stunned++
                propPlayer.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 80, 2, false, false, false))
                propPlayer.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false, false))

                // Force unlock
                val runtime = PropDisguiseManager.getRuntime(propPlayer.uuid)
                if (runtime != null && runtime.isLocked) {
                    PropDisguiseManager.toggleGridLock(propPlayer, game, false)
                }

                propPlayer.sendSystemMessage(Component.literal("§d§l⚡ [PropHunt] §dYou were STUNNED and forced out of lock!"))
            }
        }

        if (stunned > 0) {
            thrower.sendSystemMessage(Component.literal("§d[Stun] §7Stunned §e$stunned §7prop(s)!"))
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ANTI-CHEAT MISS PENALTY (Hitting non-prop blocks incurs swing fatigue)
    // ════════════════════════════════════════════════════════════════════════

    fun onHunterMiss(hunter: ServerPlayer, game: PropHuntGame, isDecoy: Boolean) {
        val hunterData = game.players[hunter.uuid] ?: return
        hunterData.falseHits++

        val level = hunter.level() as? ServerLevel ?: return

        // Apply swing fatigue (Mining Fatigue III) & Weakness
        hunter.addEffect(MobEffectInstance(MobEffects.MINING_FATIGUE, 60, 2, false, false, true))
        hunter.addEffect(MobEffectInstance(MobEffects.WEAKNESS, 40, 0, false, false, true))

        // Health penalty
        val penalty = if (isDecoy) game.settings.falseHitPenaltyHealth * 1.5f else game.settings.falseHitPenaltyHealth
        hunter.hurtServer(level, hunter.damageSources().magic(), penalty)

        // Audio & visual feedback
        hunter.playNotifySound(SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0f, 0.8f)
        hunter.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8f, 1.0f)
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, hunter.x, hunter.y + 1.2, hunter.z, 6, 0.3, 0.3, 0.3, 0.0)

        if (isDecoy) {
            hunter.sendSystemMessage(Component.literal("§c§lDECOY HIT! §7You fell for a decoy! Swing fatigue applied. (-${penalty}❤)"))
        } else {
            hunter.sendSystemMessage(Component.literal("§c§lMISS PENALTY! §7Not a prop! Swing fatigue applied. (-${penalty}❤)"))
        }

        game.broadcastToProps("§a[PropHunt] A hunter suffered a miss penalty!")
    }

    // ════════════════════════════════════════════════════════════════════════
    // ATTACK PROCESSING (Entities & Blocks)
    // ════════════════════════════════════════════════════════════════════════

    fun processHunterAttack(hunter: ServerPlayer, target: Entity, game: PropHuntGame): Boolean {
        val hunterData = game.players[hunter.uuid] ?: return false
        if (game.state != GameState.SEEKING || hunterData.team != PropHuntTeam.HUNTER || !hunterData.isAlive || hunterData.isFrozen) {
            return false
        }

        val level = hunter.level() as? ServerLevel ?: return false

        // Check if target is a prop disguise or solid collision shulker
        val propUuid = PropDisguiseManager.isPropDisguise(target)
        if (propUuid != null) {
            val propPlayer = level.server.playerList.getPlayer(propUuid)
            if (propPlayer != null) {
                PropDisguiseManager.onPropCaught(propPlayer, hunter, game)
                return true
            }
        }

        // Check if target is a decoy entity
        val decoyEntry = PropDisguiseManager.isDecoyEntity(target)
        if (decoyEntry != null) {
            PropDisguiseManager.detonateDecoy(decoyEntry, level, hunter, game)
            return true
        }

        // Friendly fire check between hunters
        if (target is ServerPlayer) {
            val targetData = game.players[target.uuid]
            if (targetData != null && targetData.team == PropHuntTeam.HUNTER) {
                return false
            }
        }

        // Target was an ordinary mob or innocent entity - miss penalty!
        if (target is LivingEntity) {
            onHunterMiss(hunter, game, isDecoy = false)
            return false
        }

        return false
    }

    fun processHunterBlockHit(hunter: ServerPlayer, pos: BlockPos, game: PropHuntGame): Boolean {
        val hunterData = game.players[hunter.uuid] ?: return false
        if (game.state != GameState.SEEKING || hunterData.team != PropHuntTeam.HUNTER || !hunterData.isAlive || hunterData.isFrozen) {
            return false
        }

        val level = hunter.level() as? ServerLevel ?: return false

        // Check if any prop is locked at this block
        for (propData in game.getProps()) {
            if (!propData.isAlive) continue
            val runtime = PropDisguiseManager.getRuntime(propData.uuid) ?: continue
            if (runtime.isLocked && runtime.lockedBlockPos == pos) {
                val propPlayer = level.server.playerList.getPlayer(propData.uuid)
                if (propPlayer != null) {
                    PropDisguiseManager.onPropCaught(propPlayer, hunter, game)
                    return true
                }
            }
        }

        // Check if any decoy is at this block
        for (propData in game.getProps()) {
            val runtime = PropDisguiseManager.getRuntime(propData.uuid) ?: continue
            val decoy = runtime.activeDecoys.find { it.pos == pos }
            if (decoy != null) {
                PropDisguiseManager.detonateDecoy(decoy, level, hunter, game)
                return true
            }
        }

        // Regular non-prop block hit -> Anti-cheat miss penalty!
        onHunterMiss(hunter, game, isDecoy = false)
        return false
    }

    // ════════════════════════════════════════════════════════════════════════
    // TICK LOOP
    // ════════════════════════════════════════════════════════════════════════

    fun tickHunter(player: ServerPlayer, game: PropHuntGame) {
        val playerData = game.players[player.uuid] ?: return

        if (playerData.scannerCooldown > 0) playerData.scannerCooldown--
        if (playerData.trackerCooldown > 0) playerData.trackerCooldown--

        // Left-click swing detection and block raycasting
        val isSwingingNow = player.isSwinging
        val wasSwinging = hunterSwinging.getOrDefault(player.uuid, false)
        hunterSwinging[player.uuid] = isSwingingNow

        if (isSwingingNow && !wasSwinging && game.state == GameState.SEEKING && !playerData.isFrozen) {
            // Hunter swung their weapon! Perform raycast against blocks
            val eyePos = player.getEyePosition()
            val lookVec = player.getViewVector(1.0f)
            val reach = 4.0
            val endPos = eyePos.add(lookVec.scale(reach))

            val clipContext = ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
            val hitResult = player.level().clip(clipContext)

            if (hitResult.type == HitResult.Type.BLOCK) {
                processHunterBlockHit(player, hitResult.blockPos, game)
            }
        }

        showHunterActionBar(player, playerData, game)
    }

    private fun showHunterActionBar(player: ServerPlayer, data: PropHuntPlayerData, game: PropHuntGame) {
        val parts = mutableListOf<String>()

        val aliveProps = game.getProps().count { it.isAlive }
        parts.add("§7Props: §c$aliveProps")

        if (data.scannerCooldown > 0) {
            parts.add("§b📡 ${data.scannerCooldown / 20}s")
        } else {
            parts.add("§a📡 Sonar Ready")
        }

        if (data.trackerCooldown > 0) {
            parts.add("§e🎯 ${data.trackerCooldown / 20}s")
        } else {
            parts.add("§a🎯 Dart Ready")
        }

        val nearest = findNearestProp(player, game)
        if (nearest != null) {
            val dist = player.position().distanceTo(nearest.position()).toInt()
            val dir = getDirectionString(player, nearest)
            val color = when {
                dist < 10 -> "§c"
                dist < 25 -> "§e"
                else -> "§7"
            }
            parts.add("$color↗ $dir (~${dist}m)")
        }

        player.sendSystemMessage(Component.literal(parts.joinToString(" §8| ")))
    }

    private fun findNearestProp(hunter: ServerPlayer, game: PropHuntGame): ServerPlayer? {
        val level = hunter.level() as? ServerLevel ?: return null
        var nearest: ServerPlayer? = null
        var minDist = Double.MAX_VALUE

        for (propData in game.getProps()) {
            if (!propData.isAlive) continue
            val prop = level.server.playerList.getPlayer(propData.uuid) ?: continue
            val dist = hunter.position().distanceTo(prop.position())
            if (dist < minDist) {
                minDist = dist
                nearest = prop
            }
        }
        return nearest
    }

    private fun getDirectionString(from: ServerPlayer, to: ServerPlayer): String {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val angle = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
        val relative = ((angle - from.yRot + 360 + 180) % 360) - 180

        return when {
            relative > 135 || relative < -135 -> "Behind"
            relative > 45 -> "Right"
            relative < -45 -> "Left"
            else -> "Ahead"
        }
    }

    private fun canUseAbility(player: ServerPlayer, game: PropHuntGame, data: PropHuntPlayerData, abilityName: String): Boolean {
        if (game.state != GameState.SEEKING) {
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7You can only use $abilityName during seeking phase."))
            return false
        }
        if (data.isFrozen) {
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7You are caged and cannot use abilities."))
            return false
        }
        if (!data.isAlive) {
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7Eliminated hunters cannot use abilities."))
            return false
        }
        return true
    }
}
