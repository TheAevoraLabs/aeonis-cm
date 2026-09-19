package com.qc.aeonis.minigame.prophunt

import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PropHuntManager {
    private val logger = LoggerFactory.getLogger("aeonis-prophunt")
    val config = PropHuntConfig()
    val statistics = PropHuntStatistics()
    private val activeGames = ConcurrentHashMap<String, PropHuntGame>()
    private val playerGameMap = ConcurrentHashMap<UUID, String>()
    
    fun register() {
        logger.info("§a[PropHunt] §7Manager initialized and ready!")
    }
    
    fun createGame(arenaId: String, level: ServerLevel, creator: ServerPlayer): PropHuntGame? {
        if (playerGameMap.containsKey(creator.uuid)) return null
        val arena = ArenaManager.getArena(arenaId) ?: return null
        val gameId = "prophunt_${System.currentTimeMillis()}"
        val game = PropHuntGame(gameId, arenaId, arena, level, config.defaultSettings.copy())
        activeGames[arenaId] = game
        return game
    }
    
    fun getGame(arenaId: String): PropHuntGame? = activeGames[arenaId]
    fun getPlayerGame(player: Player): PropHuntGame? = playerGameMap[player.uuid]?.let { activeGames[it] }
    fun listGames(): List<PropHuntGame> = activeGames.values.toList()
    
    fun joinGame(player: ServerPlayer, arenaId: String): Boolean {
        val game = activeGames[arenaId] ?: return false
        game.addPlayer(player)
        playerGameMap[player.uuid] = arenaId
        return true
    }
    
    fun leaveGame(player: ServerPlayer): Boolean {
        val arenaId = playerGameMap.remove(player.uuid) ?: return false
        val game = activeGames[arenaId] ?: return true
        game.removePlayer(player)
        return true
    }
    
    fun startRound(game: PropHuntGame) {
        game.start()
    }
    
    fun endRound(game: PropHuntGame, winner: PropHuntTeam, reason: String) {
        game.endRound(winner, reason)
    }

    fun endGame(arenaId: String, reason: String) {
        val game = activeGames.remove(arenaId) ?: return
        game.players.keys.forEach { playerGameMap.remove(it) }
    }

    fun onPlayerDisconnect(player: ServerPlayer) {
        leaveGame(player)
    }

    fun updatePlayerTeam(player: ServerPlayer, team: PropHuntTeam) {
        // Implementation for syncing team to client
    }

    fun tickAllGames(server: MinecraftServer) {
        activeGames.values.forEach { game ->
            game.tick()
        }
    }
}
