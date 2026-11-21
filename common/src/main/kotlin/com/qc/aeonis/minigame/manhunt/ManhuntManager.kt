package com.qc.aeonis.minigame.manhunt

import com.qc.aeonis.entity.HunterEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                         MANHUNT SESSION MANAGER                            ║
 * ║                                                                            ║
 * ║  Global lifecycle and event dispatcher for Aeonis Manhunt / Speedruns:     ║
 * ║  • Game creation, joining, presets, and difficulties.                      ║
 * ║  • Solo single-player setup with Autonomous AI Hunter Bot.                 ║
 * ║  • Portal memory dispatcher for multi-dimensional tracking compasses.      ║
 * ║  • Tick dispatcher for HUD telemetry and neural movement execution.        ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 */
object ManhuntManager {
    private val logger = LoggerFactory.getLogger("aeonis-manhunt")
    private val activeGames = ConcurrentHashMap<String, ManhuntGame>()
    private val playerGameMap = ConcurrentHashMap<UUID, String>()
    private val hunterGameMap = ConcurrentHashMap<Int, String>()

    fun register() {
        logger.info("§a[Manhunt] §7Manager initialized and ready!")
    }

    fun createGame(level: ServerLevel, creator: ServerPlayer, settings: ManhuntSettings): ManhuntGame? {
        if (playerGameMap.containsKey(creator.uuid)) {
            creator.sendSystemMessage(Component.literal("§c[Manhunt] §7You're already in a game! Use §e/manhunt leave§7 first."))
            return null
        }
        val gameId = "manhunt_${System.currentTimeMillis()}"
        val game = ManhuntGame(id = gameId, level = level, settings = settings, creatorUUID = creator.uuid)
        game.spawnPos = creator.blockPosition()
        game.hunterRespawnPos = game.spawnPos
        activeGames[gameId] = game
        logger.info("§a[Manhunt] §7Created new game: $gameId by ${creator.name.string}")
        return game
    }

    /**
     * Creates and starts a solo single-player manhunt game against an Autonomous AI Hunter Bot.
     */
    fun createSoloGame(
        level: ServerLevel,
        player: ServerPlayer,
        difficulty: HunterDifficulty = HunterDifficulty.TACTICAL,
        preset: ManhuntPreset = ManhuntPreset.CLASSIC
    ): ManhuntGame? {
        if (playerGameMap.containsKey(player.uuid)) {
            leaveGame(player)
        }

        val settings = ManhuntSettings()
        preset.applyTo(settings, difficulty)

        val game = createGame(level, player, settings) ?: return null
        joinGame(player, game.id)
        game.start()

        player.sendSystemMessage(Component.literal(
            "§6§l⚡ SOLO MANHUNT STARTED! §7Difficulty: ${difficulty.colorCode}${difficulty.displayName} §7| Preset: ${preset.badge} §f${preset.displayName}"
        ))
        return game
    }

    fun getGame(gameId: String): ManhuntGame? = activeGames[gameId]

    fun getPlayerGame(playerUuid: UUID): ManhuntGame? {
        val gameId = playerGameMap[playerUuid] ?: return null
        return activeGames[gameId]
    }

    fun getActiveGameIds(): List<String> = activeGames.keys().toList()

    fun joinGame(player: ServerPlayer, gameId: String, role: ManhuntRole = ManhuntRole.SPEEDRUNNER): Boolean {
        val game = activeGames[gameId] ?: return false
        if (playerGameMap.containsKey(player.uuid)) return false

        if (role == ManhuntRole.HUNTER) {
            game.addHunter(player)
        } else {
            game.addSpeedrunner(player)
        }
        playerGameMap[player.uuid] = gameId
        return true
    }

    fun leaveGame(player: ServerPlayer): Boolean {
        val gameId = playerGameMap.remove(player.uuid) ?: return false
        val game = activeGames[gameId] ?: return true
        game.removePlayer(player)
        if (game.players.isEmpty()) endGame(gameId, "All players left")
        return true
    }

    fun startGame(gameId: String): Boolean {
        val game = activeGames[gameId] ?: return false
        game.start()
        return true
    }

    fun endGame(gameId: String, reason: String) {
        val game = activeGames.remove(gameId) ?: return
        game.players.keys.forEach { playerGameMap.remove(it) }
        game.hunterEntityId?.let { hunterGameMap.remove(it) }
        game.end(reason)
    }

    fun freezeHunter(gameId: String): Boolean {
        val game = activeGames[gameId] ?: return false
        val entityId = game.hunterEntityId ?: return false
        val hunter = game.level.getEntity(entityId) as? HunterEntity ?: return false
        hunter.freeze()
        return true
    }

    fun unfreezeHunter(gameId: String): Boolean {
        val game = activeGames[gameId] ?: return false
        val entityId = game.hunterEntityId ?: return false
        val hunter = game.level.getEntity(entityId) as? HunterEntity ?: return false
        hunter.unfreeze()
        return true
    }

    fun onPlayerDeath(player: ServerPlayer) {
        val game = getPlayerGame(player.uuid) ?: return
        game.onPlayerDeath(player)
    }

    /**
     * Dispatches dimension/portal changes to maintain portal memory for tracking compasses.
     */
    fun onPlayerDimensionChange(player: ServerPlayer, fromDim: ResourceKey<Level>, toDim: ResourceKey<Level>, pos: BlockPos) {
        val game = getPlayerGame(player.uuid) ?: return
        game.recordPortalUsed(player.uuid, fromDim, pos)
    }

    /**
     * Handles compass right-click interaction.
     */
    fun onCompassUse(player: ServerPlayer, stack: ItemStack): Boolean {
        return SmartTrackingCompass.onCompassUse(player, stack)
    }

    fun tickAllGames(server: MinecraftServer) {
        activeGames.values.forEach { it.tick() }
    }
}
