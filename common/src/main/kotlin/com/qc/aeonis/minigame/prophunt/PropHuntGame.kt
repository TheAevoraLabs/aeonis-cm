package com.qc.aeonis.minigame.prophunt

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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
    
    val players = ConcurrentHashMap<UUID, PropHuntPlayerData>()
    
    fun addPlayer(player: ServerPlayer) {
        val data = PropHuntPlayerData(
            uuid = player.uuid,
            originalGameMode = player.gameMode.gameModeForPlayer,
            originalPosition = player.blockPosition(),
            originalInventory = saveInventory(player)
        )
        players[player.uuid] = data
    }
    
    fun removePlayer(player: ServerPlayer) {
        players.remove(player.uuid)
    }
    
    fun getProps(): List<PropHuntPlayerData> = players.values.filter { it.team == PropHuntTeam.PROP }
    fun getHunters(): List<PropHuntPlayerData> = players.values.filter { it.team == PropHuntTeam.HUNTER }
    fun getSpectators(): List<PropHuntPlayerData> = players.values.filter { it.team == PropHuntTeam.NONE || !it.isAlive }
    
    fun broadcast(message: String) {
        val component = Component.literal(message)
        for (playerData in players.values) {
            val player = level.server?.playerList?.getPlayer(playerData.uuid)
            player?.sendSystemMessage(component)
        }
    }
    
    fun broadcastToProps(message: String) {
        val component = Component.literal(message)
        for (propData in getProps()) {
            val player = level.server?.playerList?.getPlayer(propData.uuid)
            player?.sendSystemMessage(component)
        }
    }
    
    fun broadcastToHunters(message: String) {
        val component = Component.literal(message)
        for (hunterData in getHunters()) {
            val player = level.server?.playerList?.getPlayer(hunterData.uuid)
            player?.sendSystemMessage(component)
        }
    }
    
    fun start() {
        state = GameState.STARTING
        countdownTicks = 200
    }

    fun endRound(winner: PropHuntTeam, reason: String) {
        state = GameState.ROUND_END
        roundEndTicks = settings.roundEndDelaySeconds * 20
        
        if (winner == PropHuntTeam.PROP) propWins++
        if (winner == PropHuntTeam.HUNTER) hunterWins++
        
        broadcast("§6§lROUND OVER! §7Winner: §e${winner.name}")
        broadcast("§7Reason: $reason")
        
        // Clean up disguises
        players.values.forEach { data ->
            val player = level.server?.playerList?.getPlayer(data.uuid)
            player?.let { PropDisguiseManager.undisguise(it) }
        }
    }

    fun tick() {
        if (state == GameState.ENDED || state == GameState.WAITING) return
        
        elapsedTicks++
        
        when (state) {
            GameState.STARTING -> {
                if (countdownTicks > 0) {
                    if (countdownTicks % 20 == 0) {
                        broadcast("§aGame starting in ${countdownTicks / 20}...")
                    }
                    countdownTicks--
                } else {
                    startRound()
                }
            }
            GameState.HIDING -> {
                if (hideTimeTicks > 0) {
                    if (hideTimeTicks % 20 == 0) {
                        broadcastToProps("§bHiding time: ${hideTimeTicks / 20}s")
                    }
                    hideTimeTicks--
                } else {
                    state = GameState.SEEKING
                    seekTimeTicks = settings.roundTimeSeconds * 20
                    broadcast("§c§lHUNTERS RELEASED! §7Find the props!")
                    
                    // Release hunters
                    getHunters().forEach { data ->
                        val player = level.server?.playerList?.getPlayer(data.uuid)
                        player?.setGameMode(GameType.SURVIVAL)
                        player?.teleportTo(arena.pos1.x.toDouble(), arena.pos1.y.toDouble(), arena.pos1.z.toDouble())
                    }
                }
            }
            GameState.SEEKING -> {
                if (seekTimeTicks > 0) {
                    if (seekTimeTicks % 20 == 0 && seekTimeTicks <= 200) {
                        broadcast("§cRound ends in ${seekTimeTicks / 20}s")
                    }
                    seekTimeTicks--
                    
                    // Check if all props caught
                    if (getProps().none { it.isAlive }) {
                        endRound(PropHuntTeam.HUNTER, "All props caught!")
                    }
                } else {
                    endRound(PropHuntTeam.PROP, "Time ran out!")
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
        
        // Tick individual players
        players.values.forEach { data ->
            val player = level.server?.playerList?.getPlayer(data.uuid) ?: return@forEach
            if (data.team == PropHuntTeam.PROP && data.isAlive) {
                PropDisguiseManager.tickProp(player, this)
            }
        }
    }

    private fun startRound() {
        currentRound++
        state = GameState.HIDING
        hideTimeTicks = settings.hideTimeSeconds * 20
        
        // Assign teams and teleport
        assignTeams()
        
        broadcast("§6§lROUND $currentRound STARTED! §7Props have ${settings.hideTimeSeconds}s to hide.")
        
        players.values.forEach { data ->
            val player = level.server?.playerList?.getPlayer(data.uuid) ?: return@forEach
            data.resetRoundState()
            
            if (data.team == PropHuntTeam.PROP) {
                player.setGameMode(GameType.SURVIVAL)
                player.teleportTo(arena.pos1.x.toDouble(), arena.pos1.y.toDouble(), arena.pos1.z.toDouble())
                PropDisguiseManager.assignRandomDisguise(player, this)
            } else {
                player.setGameMode(GameType.SPECTATOR)
                player.teleportTo(arena.lobby.x.toDouble(), arena.lobby.y.toDouble(), arena.lobby.z.toDouble())
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
        broadcast("§6§lGAME OVER! §7Overall winner: §e${winner.name}")
        
        players.values.forEach { data ->
            val player = level.server?.playerList?.getPlayer(data.uuid)
            player?.let { data.restoreState(it) }
        }
        
        PropHuntManager.endGame(arenaId, "Game finished naturally")
    }

    private fun saveInventory(player: ServerPlayer): Array<ItemStack> {
        val inv = player.inventory
        return Array(inv.containerSize) { i -> inv.getItem(i).copy() }
    }
    
    fun cleanup() {
        state = GameState.ENDED
        players.clear()
    }
}

enum class GameState {
    WAITING, STARTING, HIDING, SEEKING, ROUND_END, ENDED
}

enum class PropHuntTeam {
    PROP, HUNTER, NONE
}

data class PropHuntPlayerData(
    val uuid: UUID,
    val originalGameMode: GameType,
    val originalPosition: BlockPos,
    val originalInventory: Array<ItemStack>
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
        player.setGameMode(originalGameMode)
        player.teleportTo(originalPosition.x.toDouble() + 0.5, originalPosition.y.toDouble(), originalPosition.z.toDouble() + 0.5)
        player.inventory.clearContent()
        for (i in originalInventory.indices) {
            player.inventory.setItem(i, originalInventory[i].copy())
        }
        player.removeAllEffects()
        player.isInvisible = false
        player.abilities.mayfly = false
        player.abilities.flying = false
        player.onUpdateAbilities()
    }
}

data class PropHuntSettings(
    var minPlayers: Int = 4,
    var maxPlayers: Int = 16,
    var maxRounds: Int = 5,
    var hideTimeSeconds: Int = 30,
    var roundTimeSeconds: Int = 180,
    var startCountdownSeconds: Int = 15,
    var roundEndDelaySeconds: Int = 10,
    var hunterRatio: Float = 0.3f,
    var minHunters: Int = 1,
    var maxHunters: Int = 5,
    var propHealth: Float = 10f,
    var allowPropTaunts: Boolean = true,
    var tauntCooldownSeconds: Int = 20,
    var propSpeedMultiplier: Float = 1.0f,
    var hunterDamage: Float = 4f,
    var falseHitPenaltyHealth: Float = 2f,
    var scannerCooldownSeconds: Int = 30,
    var trackerCooldownSeconds: Int = 45,
    var stunGrenadesPerRound: Int = 2,
    var hunterHintsEnabled: Boolean = true,
    var hintIntervalSeconds: Int = 30,
    var propSurviveRewardXP: Int = 50,
    var hunterFindRewardXP: Int = 30,
    var winningTeamRewardXP: Int = 100
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
