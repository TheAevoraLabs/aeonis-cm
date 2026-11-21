package com.qc.aeonis.minigame.prophunt

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class PropHuntPlayerSnapshot(
    val gameMode: GameType,
    val position: Vec3,
    val yRot: Float,
    val xRot: Float,
    val items: Array<ItemStack>,
    val selectedSlot: Int,
    val xpLevel: Int,
    val xpProgress: Float,
    val health: Float,
    val foodLevel: Int,
    val effects: List<MobEffectInstance>
) {
    fun restore(player: ServerPlayer) {
        player.setGameMode(gameMode)
        player.teleportTo(position.x, position.y, position.z)
        player.setYRot(yRot)
        player.setXRot(xRot)

        player.inventory.clearContent()
        for (i in items.indices) {
            player.inventory.setItem(i, items[i].copy())
        }
        player.inventory.selectedSlot = selectedSlot.coerceIn(0, 8)

        player.experienceLevel = xpLevel
        player.experienceProgress = xpProgress
        player.health = health.coerceAtLeast(1.0f)
        player.foodData.foodLevel = foodLevel

        player.removeAllEffects()
        for (effect in effects) {
            player.addEffect(MobEffectInstance(effect))
        }

        player.isInvisible = false
        player.setGlowingTag(false)
        player.abilities.mayfly = false
        player.abilities.flying = false
        player.onUpdateAbilities()
    }
}

data class PropHuntPlayerData(
    val uuid: UUID,
    val snapshot: PropHuntPlayerSnapshot
) {
    var team: PropHuntTeam = PropHuntTeam.NONE
    var isAlive: Boolean = true
    var disguiseType: String? = null
    var rotationLocked: Boolean = false
    var movementFrozen: Boolean = false
    var lockedYaw: Float = 0f
    var lockedPitch: Float = 0f
    var tauntCooldown: Int = 0
    var survivalTicks: Int = 0
    var decoyHits: Int = 0
    var propsFound: Int = 0
    var falseHits: Int = 0
    var scannerCooldown: Int = 0
    var trackerCooldown: Int = 0
    var stunGrenades: Int = 0
    var isFrozen: Boolean = false
    var roundsWon: Int = 0
    var damageDealt: Float = 0f
    var damageTaken: Float = 0f

    fun resetRoundState() {
        isAlive = true
        disguiseType = null
        rotationLocked = false
        movementFrozen = false
        tauntCooldown = 0
        survivalTicks = 0
        decoyHits = 0
        propsFound = 0
        falseHits = 0
        scannerCooldown = 0
        trackerCooldown = 0
        isFrozen = false
    }

    fun restoreState(player: ServerPlayer) {
        PropDisguiseManager.undisguise(player)
        HunterAbilityManager.resetHunter(player)
        snapshot.restore(player)
    }
}

data class PropHuntGame(
    val id: String,
    val arenaId: String,
    val arena: PropHuntArena,
    val level: ServerLevel,
    val settings: PropHuntSettings
) {
    var state: GameState = GameState.WAITING
    var currentRound: Int = 0
    var propWins: Int = 0
    var hunterWins: Int = 0
    var countdownTicks: Int = 0
    var elapsedTicks: Int = 0
    var hideTimeTicks: Int = 0
    var seekTimeTicks: Int = 0
    var roundEndTicks: Int = 0
    var gameEndTicks: Int = 0
    var isSuddenDeath: Boolean = false

    val players = ConcurrentHashMap<UUID, PropHuntPlayerData>()

    fun addPlayer(player: ServerPlayer) {
        val snapshot = captureSnapshot(player)
        val data = PropHuntPlayerData(player.uuid, snapshot)
        players[player.uuid] = data
    }

    fun removePlayer(player: ServerPlayer) {
        val data = players.remove(player.uuid)
        if (data != null) {
            data.restoreState(player)
        }
    }

    fun getProps(): List<PropHuntPlayerData> = players.values.filter { it.team == PropHuntTeam.PROP }
    fun getHunters(): List<PropHuntPlayerData> = players.values.filter { it.team == PropHuntTeam.HUNTER }
    fun getSpectators(): List<PropHuntPlayerData> = players.values.filter { it.team == PropHuntTeam.NONE || !it.isAlive }

    fun broadcast(message: String) {
        val component = Component.literal(message)
        for (playerData in players.values) {
            val player = level.server.playerList.getPlayer(playerData.uuid)
            player?.sendSystemMessage(component)
        }
    }

    fun broadcastToProps(message: String) {
        val component = Component.literal(message)
        for (propData in getProps()) {
            val player = level.server.playerList.getPlayer(propData.uuid)
            player?.sendSystemMessage(component)
        }
    }

    fun broadcastToHunters(message: String) {
        val component = Component.literal(message)
        for (hunterData in getHunters()) {
            val player = level.server.playerList.getPlayer(hunterData.uuid)
            player?.sendSystemMessage(component)
        }
    }

    fun start() {
        state = GameState.STARTING
        countdownTicks = settings.startCountdownSeconds * 20
    }

    fun endRound(winner: PropHuntTeam, reason: String) {
        state = GameState.ROUND_END
        roundEndTicks = settings.roundEndDelaySeconds * 20

        if (winner == PropHuntTeam.PROP) {
            propWins++
            getProps().filter { it.isAlive }.forEach { propData ->
                level.server.playerList.getPlayer(propData.uuid)?.let {
                    PropHuntRewards.grantReward(it, settings.propSurviveRewardXP, "Round Survival")
                }
            }
        }
        if (winner == PropHuntTeam.HUNTER) {
            hunterWins++
            getHunters().forEach { hunterData ->
                level.server.playerList.getPlayer(hunterData.uuid)?.let {
                    PropHuntRewards.grantReward(it, settings.winningTeamRewardXP, "Hunter Victory")
                }
            }
        }

        broadcast("§6§l═════════════════════════════════")
        broadcast("§6§lROUND $currentRound OVER! §7Winner: §e${winner.name}")
        broadcast("§7Reason: §f$reason")
        broadcast("§7Score: §aProps $propWins §8- §cHunters $hunterWins")
        broadcast("§6§l═════════════════════════════════")

        // Undisguise and reset states
        players.values.forEach { data ->
            val player = level.server.playerList.getPlayer(data.uuid)
            if (player != null) {
                PropDisguiseManager.undisguise(player)
                HunterAbilityManager.resetHunter(player)
            }
        }
    }

    fun tick() {
        if (state == GameState.ENDED || state == GameState.WAITING) return

        elapsedTicks++

        when (state) {
            GameState.STARTING -> {
                if (countdownTicks > 0) {
                    if (countdownTicks % 20 == 0) {
                        val s = countdownTicks / 20
                        broadcast("§a[PropHunt] Game starting in ${s}s...")
                        level.playSound(null, arena.lobby.x.toDouble(), arena.lobby.y.toDouble(), arena.lobby.z.toDouble(),
                            SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0f, 1.0f)
                    }
                    countdownTicks--
                } else {
                    startRound()
                }
            }

            GameState.HIDING -> {
                if (hideTimeTicks > 0) {
                    val s = hideTimeTicks / 20
                    if (hideTimeTicks % 20 == 0 && (s <= 5 || s % 10 == 0)) {
                        broadcastToProps("§b[Hiding Phase] §e${s}s §7remaining to hide and lock!")
                        if (s <= 5) {
                            broadcast("§c§l$s...")
                            level.playSound(null, arena.pos1.x.toDouble(), arena.pos1.y.toDouble(), arena.pos1.z.toDouble(),
                                SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0f, 1.0f + (5 - s) * 0.2f)
                        }
                    }
                    hideTimeTicks--
                } else {
                    // Release hunters into seeking phase
                    state = GameState.SEEKING
                    seekTimeTicks = settings.roundTimeSeconds * 20
                    isSuddenDeath = false

                    broadcast("§c§l═════════════════════════════════")
                    broadcast("§c§lHUNTERS RELEASED! §7Find and eliminate all props!")
                    broadcast("§c§l═════════════════════════════════")

                    getHunters().forEach { data ->
                        val player = level.server.playerList.getPlayer(data.uuid)
                        if (player != null) {
                            HunterAbilityManager.freezeHunter(player, false)
                            player.teleportTo(arena.pos1.x + 0.5, arena.pos1.y.toDouble(), arena.pos1.z + 0.5)
                        }
                    }
                }
            }

            GameState.SEEKING -> {
                if (seekTimeTicks > 0) {
                    val alivePropsCount = getProps().count { it.isAlive }

                    // Sudden Death trigger: <= 30 seconds or 1 prop remaining (out of multiple)
                    if (!isSuddenDeath && (seekTimeTicks <= 30 * 20 || (alivePropsCount == 1 && getProps().size > 1))) {
                        triggerSuddenDeath()
                    }

                    if (seekTimeTicks % 20 == 0 && (seekTimeTicks <= 200 || seekTimeTicks % 600 == 0)) {
                        broadcast("§c[Seek Timer] §e${seekTimeTicks / 20}s §7remaining! §8(§a$alivePropsCount props left§8)")
                    }

                    seekTimeTicks--

                    // Victory check
                    if (alivePropsCount == 0) {
                        endRound(PropHuntTeam.HUNTER, "All props were found and caught!")
                    }
                } else {
                    endRound(PropHuntTeam.PROP, "Props survived the round!")
                }
            }

            GameState.ROUND_END -> {
                if (roundEndTicks > 0) {
                    roundEndTicks--
                } else {
                    if (currentRound < settings.maxRounds) {
                        startRound()
                    } else {
                        endGame()
                    }
                }
            }

            else -> {}
        }

        // Tick individual player mechanics
        players.values.forEach { data ->
            val player = level.server.playerList.getPlayer(data.uuid) ?: return@forEach
            if (data.team == PropHuntTeam.PROP && data.isAlive) {
                PropDisguiseManager.tickProp(player, this)
            } else if (data.team == PropHuntTeam.HUNTER && data.isAlive) {
                HunterAbilityManager.tickHunter(player, this)
            }
        }
    }

    private fun triggerSuddenDeath() {
        isSuddenDeath = true
        broadcast("§4§l═════════════════════════════════")
        broadcast("§4§l⚠ SUDDEN DEATH! ⚠")
        broadcast("§cAll props are forced to whistle! Hunters are enraged!")
        broadcast("§4§l═════════════════════════════════")

        level.playSound(null, arena.pos1.x.toDouble(), arena.pos1.y.toDouble(), arena.pos1.z.toDouble(),
            SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.5f, 0.8f)

        // Enrage hunters with Speed II
        getHunters().forEach { hunterData ->
            val hunter = level.server.playerList.getPlayer(hunterData.uuid)
            hunter?.addEffect(MobEffectInstance(MobEffects.SPEED, Int.MAX_VALUE, 1, false, false, false))
        }

        // Force whistle on all remaining props immediately
        getProps().filter { it.isAlive }.forEach { propData ->
            val propPlayer = level.server.playerList.getPlayer(propData.uuid)
            if (propPlayer != null) {
                PropDisguiseManager.executeWhistle(propPlayer, this, isInvoluntary = true)
            }
        }
    }

    private fun startRound() {
        currentRound++
        state = GameState.HIDING
        hideTimeTicks = settings.hideTimeSeconds * 20
        isSuddenDeath = false

        assignTeams()

        broadcast("§6§l═════════════════════════════════")
        broadcast("§6§lROUND $currentRound / ${settings.maxRounds} STARTED!")
        broadcast("§7Props have §b${settings.hideTimeSeconds}s §7to hide!")
        broadcast("§6§l═════════════════════════════════")

        players.values.forEach { data ->
            val player = level.server.playerList.getPlayer(data.uuid) ?: return@forEach
            data.resetRoundState()

            if (data.team == PropHuntTeam.PROP) {
                player.setGameMode(GameType.SURVIVAL)
                player.teleportTo(arena.pos1.x + 0.5, arena.pos1.y.toDouble(), arena.pos1.z + 0.5)
                // Speed boost during hiding grace period
                player.addEffect(MobEffectInstance(MobEffects.SPEED, settings.hideTimeSeconds * 20, 1, false, false, false))
                PropDisguiseManager.assignRandomDisguise(player, this)
            } else {
                player.setGameMode(GameType.ADVENTURE)
                player.teleportTo(arena.lobby.x + 0.5, arena.lobby.y.toDouble(), arena.lobby.z + 0.5)
                HunterAbilityManager.setupHunter(player, this)
                HunterAbilityManager.freezeHunter(player, true)
            }
        }
    }

    private fun assignTeams() {
        val allPlayers = players.values.shuffled()
        val hunterCount = (allPlayers.size * settings.hunterRatio).toInt().coerceIn(settings.minHunters, settings.maxHunters)

        for (i in allPlayers.indices) {
            allPlayers[i].team = if (i < hunterCount) PropHuntTeam.HUNTER else PropHuntTeam.PROP
        }
    }

    private fun endGame() {
        val winner = if (propWins > hunterWins) PropHuntTeam.PROP else if (hunterWins > propWins) PropHuntTeam.HUNTER else PropHuntTeam.NONE

        broadcast("§6§l═════════════════════════════════")
        broadcast("§6§lPROP HUNT GAME OVER!")
        broadcast("§7Overall Winner: §e${winner.name}")
        broadcast("§7Final Score: §aProps $propWins §8- §cHunters $hunterWins")
        broadcast("§6§l═════════════════════════════════")

        players.values.forEach { data ->
            val player = level.server.playerList.getPlayer(data.uuid)
            player?.let { data.restoreState(it) }
        }

        PropHuntManager.endGame(arenaId, "Game finished naturally")
    }

    private fun captureSnapshot(player: ServerPlayer): PropHuntPlayerSnapshot {
        val inv = player.inventory
        val items = Array(inv.containerSize) { i -> inv.getItem(i).copy() }
        val effects = player.activeEffects.map { MobEffectInstance(it) }

        return PropHuntPlayerSnapshot(
            gameMode = player.gameMode.gameModeForPlayer,
            position = player.position(),
            yRot = player.yRot,
            xRot = player.xRot,
            items = items,
            selectedSlot = inv.selectedSlot,
            xpLevel = player.experienceLevel,
            xpProgress = player.experienceProgress,
            health = player.health,
            foodLevel = player.foodData.foodLevel,
            effects = effects
        )
    }

    fun cleanup() {
        state = GameState.ENDED
        players.values.forEach { data ->
            val player = level.server.playerList.getPlayer(data.uuid)
            player?.let { data.restoreState(it) }
        }
        players.clear()
    }
}

enum class GameState {
    WAITING, STARTING, HIDING, SEEKING, ROUND_END, ENDED
}

enum class PropHuntTeam {
    PROP, HUNTER, NONE
}

data class PropHuntSettings(
    var minPlayers: Int = 2,
    var maxPlayers: Int = 16,
    var maxRounds: Int = 5,
    var hideTimeSeconds: Int = 30,
    var roundTimeSeconds: Int = 180,
    var startCountdownSeconds: Int = 10,
    var roundEndDelaySeconds: Int = 8,
    var hunterRatio: Float = 0.3f,
    var minHunters: Int = 1,
    var maxHunters: Int = 5,
    var propHealth: Float = 10f,
    var allowPropTaunts: Boolean = true,
    var tauntCooldownSeconds: Int = 20,
    var propSpeedMultiplier: Float = 1.0f,
    var hunterDamage: Float = 4f,
    var falseHitPenaltyHealth: Float = 2f,
    var scannerCooldownSeconds: Int = 15,
    var trackerCooldownSeconds: Int = 35,
    var stunGrenadesPerRound: Int = 2,
    var hunterHintsEnabled: Boolean = true,
    var hintIntervalSeconds: Int = 30,
    var propSurviveRewardXP: Int = 60,
    var hunterFindRewardXP: Int = 40,
    var winningTeamRewardXP: Int = 120
) {
    fun copy(): PropHuntSettings = PropHuntSettings(
        minPlayers, maxPlayers, maxRounds, hideTimeSeconds, roundTimeSeconds,
        startCountdownSeconds, roundEndDelaySeconds, hunterRatio, minHunters,
        maxHunters, propHealth, allowPropTaunts, tauntCooldownSeconds,
        propSpeedMultiplier, hunterDamage, falseHitPenaltyHealth,
        scannerCooldownSeconds, trackerCooldownSeconds, stunGrenadesPerRound,
        hunterHintsEnabled, hintIntervalSeconds, propSurviveRewardXP,
        hunterFindRewardXP, winningTeamRewardXP
    )
}

class PropHuntConfig {
    val defaultSettings = PropHuntSettings()
    var enabled: Boolean = true
    var maxConcurrentGames: Int = 5
    var announceGames: Boolean = true
}

class PropHuntStatistics {
    private val propWins = ConcurrentHashMap<UUID, Int>()
    private val hunterWins = ConcurrentHashMap<UUID, Int>()
    private val propsFound = ConcurrentHashMap<UUID, Int>()
    private val survivalTime = ConcurrentHashMap<UUID, Long>()

    fun recordPropWin(uuid: UUID) { propWins.merge(uuid, 1, Int::plus) }
    fun recordHunterWin(uuid: UUID) { hunterWins.merge(uuid, 1, Int::plus) }
    fun recordPropFound(hunterUuid: UUID) { propsFound.merge(hunterUuid, 1, Int::plus) }
    fun recordSurvivalTime(propUuid: UUID, ticks: Long) { survivalTime.merge(propUuid, ticks) { old, new -> maxOf(old, new) } }

    fun getPropWins(uuid: UUID): Int = propWins[uuid] ?: 0
    fun getHunterWins(uuid: UUID): Int = hunterWins[uuid] ?: 0
    fun getPropsFound(uuid: UUID): Int = propsFound[uuid] ?: 0
    fun getBestSurvivalTime(uuid: UUID): Long = survivalTime[uuid] ?: 0
}
