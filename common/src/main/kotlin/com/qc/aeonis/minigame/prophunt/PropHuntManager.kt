package com.qc.aeonis.minigame.prophunt

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
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
        joinGame(creator, arenaId)
        return game
    }

    fun getGame(arenaId: String): PropHuntGame? = activeGames[arenaId]
    fun getPlayerGame(player: Player): PropHuntGame? = playerGameMap[player.uuid]?.let { activeGames[it] }
    fun listGames(): List<PropHuntGame> = activeGames.values.toList()

    fun joinGame(player: ServerPlayer, arenaId: String): Boolean {
        if (playerGameMap.containsKey(player.uuid)) return false
        val game = activeGames[arenaId] ?: return false
        if (game.state != GameState.WAITING && game.state != GameState.STARTING) return false

        game.addPlayer(player)
        playerGameMap[player.uuid] = arenaId
        game.broadcast("§a[PropHunt] §e${player.name.string} §7joined the game! (${game.players.size}/${game.settings.maxPlayers})")
        return true
    }

    fun leaveGame(player: ServerPlayer): Boolean {
        val arenaId = playerGameMap.remove(player.uuid) ?: return false
        val game = activeGames[arenaId] ?: return true
        game.removePlayer(player)
        game.broadcast("§c[PropHunt] §e${player.name.string} §7left the game.")
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
        game.broadcast("§c[PropHunt] Game ended: $reason")
        game.players.keys.forEach { playerGameMap.remove(it) }
        game.cleanup()
    }

    fun onPlayerDisconnect(player: ServerPlayer) {
        leaveGame(player)
    }

    fun tickAllGames(server: MinecraftServer) {
        activeGames.values.forEach { game ->
            game.tick()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // INTERACTION & ATTACK HOOKS
    // ════════════════════════════════════════════════════════════════════════

    fun onPlayerUseItem(player: ServerPlayer, hand: InteractionHand): Boolean {
        val game = getPlayerGame(player) ?: return false
        val playerData = game.players[player.uuid] ?: return false
        val item = player.getItemInHand(hand)

        if (playerData.team == PropHuntTeam.PROP) {
            when (item.item) {
                Items.COMPASS -> {
                    PropDisguiseManager.toggleRotationLock(player, game)
                    return true
                }
                Items.BLUE_ICE -> {
                    PropDisguiseManager.toggleFreeze(player, game)
                    return true
                }
                Items.ARMOR_STAND -> {
                    return PropDisguiseManager.deployDecoy(player, game)
                }
                Items.GOAT_HORN -> {
                    PropDisguiseManager.performTaunt(player, game)
                    return true
                }
            }
        } else if (playerData.team == PropHuntTeam.HUNTER) {
            when (item.item) {
                Items.ECHO_SHARD -> {
                    return HunterAbilityManager.useSonarPing(player, game)
                }
                Items.SPECTRAL_ARROW -> {
                    return HunterAbilityManager.useTracker(player, game)
                }
            }
        }
        return false
    }

    fun onPlayerAttackBlock(player: ServerPlayer, pos: BlockPos): Boolean {
        val game = getPlayerGame(player) ?: return false
        val playerData = game.players[player.uuid] ?: return false
        if (playerData.team == PropHuntTeam.HUNTER) {
            return HunterAbilityManager.processHunterBlockHit(player, pos, game)
        }
        return false
    }

    fun onPlayerAttackEntity(hunter: ServerPlayer, target: Entity): Boolean {
        val game = getPlayerGame(hunter) ?: return false
        val hunterData = game.players[hunter.uuid] ?: return false
        if (hunterData.team == PropHuntTeam.HUNTER) {
            return HunterAbilityManager.processHunterAttack(hunter, target, game)
        }
        return false
    }
}
