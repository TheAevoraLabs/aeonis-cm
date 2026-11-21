package com.qc.aeonis.minigame.manhunt

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                         MANHUNT GAME SESSION                               ║
 * ║                                                                            ║
 * ║  Complete Manhunt Game rewrite featuring:                                  ║
 * ║  • Multi-dimensional Smart Tracking Compass with height delta indicators   ║
 * ║  • Autonomous AI Hunter Bot powered by Aeonis Neural Movement Pipeline     ║
 * ║  • Live Speedrun HUD: split-timer overlay & real-time telemetry radar      ║
 * ║  • Selectable game presets: Classic, Hitman, Assassin, Nether Start        ║
 * ║  • Selectable AI difficulties: Casual, Tactical, Terminator                ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 */
data class ManhuntGame(
    val id: String,
    val level: ServerLevel,
    val settings: ManhuntSettings,
    val creatorUUID: UUID
) {
    // ════════════════════════════════════════════════════════════════════════
    // GAME STATE & SUBSYSTEMS
    // ════════════════════════════════════════════════════════════════════════

    var state: ManhuntGameState = ManhuntGameState.SETUP

    var hunterEntityId: Int? = null

    var startTime: Long = 0

    var hunterReleaseTime: Long = 0

    var countdownTicks: Int = 0

    var headStartTicks: Int = 0

    var elapsedTicks: Long = 0L

    val players = ConcurrentHashMap<UUID, ManhuntPlayerData>()

    var spawnPos: BlockPos? = null

    var hunterRespawnPos: BlockPos? = null

    /** Speedrun split tracker (Start, Nether, Fortress, Stronghold, Dragon) */
    val splits = SpeedrunSplitTracker()

    /** Live Speedrun HUD and Telemetry stream */
    val hud = SpeedrunHud(this)

    /** Autonomous AI Hunter Bot Manager */
    val aiManager = ManhuntAiManager(this)

    /** Multi-dimensional portal registry: UUID -> (Dimension -> Last Portal Pos) */
    private val portalHistory = ConcurrentHashMap<UUID, ConcurrentHashMap<ResourceKey<Level>, BlockPos>>()

    // ════════════════════════════════════════════════════════════════════════
    // PLAYER MANAGEMENT
    // ════════════════════════════════════════════════════════════════════════

    fun addSpeedrunner(player: ServerPlayer) {
        val data = ManhuntPlayerData(
            uuid = player.uuid,
            name = player.name.string,
            role = ManhuntRole.SPEEDRUNNER,
            originalGameMode = player.gameMode.gameModeForPlayer,
            originalPosition = player.blockPosition(),
            originalInventory = saveInventory(player),
            isAlive = true
        )
        players[player.uuid] = data
        hud.attachPlayer(player)
    }

    fun addHunter(player: ServerPlayer) {
        val data = ManhuntPlayerData(
            uuid = player.uuid,
            name = player.name.string,
            role = ManhuntRole.HUNTER,
            originalGameMode = player.gameMode.gameModeForPlayer,
            originalPosition = player.blockPosition(),
            originalInventory = saveInventory(player),
            isAlive = true
        )
        players[player.uuid] = data
        hud.attachPlayer(player)

        // Give tracking compass
        val compass = SmartTrackingCompass.createCompass(getSpeedrunnerPlayer(), this)
        player.inventory.add(compass)
    }

    fun removePlayer(player: ServerPlayer) {
        hud.detachPlayer(player)
        players.remove(player.uuid)
    }

    fun getAliveSpeedrunners(): List<UUID> {
        return players.values
            .filter { it.role == ManhuntRole.SPEEDRUNNER && it.isAlive }
            .map { it.uuid }
    }

    fun getSpeedrunners(): List<ManhuntPlayerData> {
        return players.values.filter { it.role == ManhuntRole.SPEEDRUNNER }
    }

    fun getSpeedrunnerPlayer(): ServerPlayer? {
        val uuid = getAliveSpeedrunners().firstOrNull() ?: return null
        return level.server.playerList.getPlayer(uuid)
    }

    fun getSpectators(): List<ManhuntPlayerData> {
        return players.values.filter { it.role == ManhuntRole.SPECTATOR || !it.isAlive }
    }

    fun eliminatePlayer(uuid: UUID) {
        players[uuid]?.isAlive = false
    }

    fun areAllSpeedrunnersEliminated(): Boolean {
        return getAliveSpeedrunners().isEmpty()
    }

    // ════════════════════════════════════════════════════════════════════════
    // PORTAL & DIMENSION TRACKING
    // ════════════════════════════════════════════════════════════════════════

    fun recordPortalUsed(uuid: UUID, dimension: ResourceKey<Level>, pos: BlockPos) {
        val dimMap = portalHistory.computeIfAbsent(uuid) { ConcurrentHashMap() }
        dimMap[dimension] = pos
    }

    fun getLastPortalUsed(uuid: UUID, dimension: ResourceKey<Level>): BlockPos? {
        return portalHistory[uuid]?.get(dimension)
    }

    // ════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    fun start() {
        state = ManhuntGameState.COUNTDOWN
        countdownTicks = 200 // 10 seconds
        broadcast("§6§l══════════════ MANHUNT SPEEDRUN ══════════════")
        broadcast("§7Preset: ${settings.preset.badge} §f${settings.preset.displayName}")
        broadcast("§7Hunter Difficulty: ${settings.hunterDifficulty.colorCode}${settings.hunterDifficulty.displayName}")
        broadcast("§7Head Start: §e${settings.headStartSeconds} seconds")
        broadcast("§aStarting in 10 seconds...")
    }

    fun end(reason: String) {
        state = ManhuntGameState.ENDED
        broadcast("§c§l[Manhunt] §7Game ended: §f$reason")

        hud.removeAll()
        aiManager.removeHunter()

        players.values.forEach { data ->
            val player = level.server.playerList.getPlayer(data.uuid)
            player?.let { data.restoreState(it) }
        }
    }

    fun onPlayerDeath(player: ServerPlayer) {
        if (state == ManhuntGameState.ENDED) return
        val data = players[player.uuid] ?: return

        if (data.role == ManhuntRole.SPEEDRUNNER) {
            data.deathCount++
            if (!settings.respawnEnabled || (settings.maxRespawns != -1 && data.deathCount > settings.maxRespawns)) {
                eliminatePlayer(player.uuid)
                broadcast("§c§l💀 §e${player.name.string}§7 was eliminated!")
                player.setGameMode(GameType.SPECTATOR)

                if (areAllSpeedrunnersEliminated()) {
                    end("All speedrunners eliminated! Hunters win!")
                }
            } else {
                val remaining = if (settings.maxRespawns == -1) "∞" else (settings.maxRespawns - data.deathCount + 1).toString()
                broadcast("§c⚠ §e${player.name.string}§7 died! Respawns remaining: §b$remaining")
            }
        } else if (data.role == ManhuntRole.HUNTER) {
            // Hunter respawn notification
            broadcast("§a⚔ Hunter §e${player.name.string}§7 was slain! Respawning with compass...")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MAIN TICK
    // ════════════════════════════════════════════════════════════════════════

    fun tick() {
        if (state == ManhuntGameState.ENDED) return

        elapsedTicks++

        when (state) {
            ManhuntGameState.COUNTDOWN -> {
                if (countdownTicks > 0) {
                    if (countdownTicks % 20 == 0) {
                        val seconds = countdownTicks / 20
                        broadcastTitle("§a$seconds", "§7Starting soon...", 0, 25, 5)
                    }
                    countdownTicks--
                } else {
                    state = ManhuntGameState.HEAD_START
                    headStartTicks = settings.headStartSeconds * 20
                    startTime = level.gameTime
                    splits.onGameStart(startTime)

                    broadcastTitle("§6§lGO!", "§eSpeedrunners have a head start!", 10, 40, 10)

                    // Handle Nether Start preset
                    if (settings.preset == ManhuntPreset.NETHER_START) {
                        setupNetherStart()
                    }

                    // Release speedrunners
                    getSpeedrunners().forEach { data ->
                        val player = level.server.playerList.getPlayer(data.uuid)
                        player?.setGameMode(GameType.SURVIVAL)
                    }
                }
            }
            ManhuntGameState.HEAD_START -> {
                if (headStartTicks > 0) {
                    if (headStartTicks % 20 == 0 && headStartTicks <= 100) {
                        val seconds = headStartTicks / 20
                        broadcast("§c⚠ §7Hunter released in §e$seconds§7 seconds!")
                    }
                    headStartTicks--
                } else {
                    state = ManhuntGameState.ACTIVE
                    hunterReleaseTime = level.gameTime
                    broadcastTitle("§c§lHUNTER RELEASED!", "§4Run for your lives!", 10, 60, 10)

                    // Spawn autonomous AI hunter bot if in solo mode or configured
                    spawnHunter()

                    // Give compass to human hunters
                    giveHunterCompasses()
                }
            }
            ManhuntGameState.ACTIVE -> {
                // 1. Tick AI hunter bot (neural movement, dimension pursuit, respawning)
                aiManager.tick(level)

                // 2. Poll and update speedrun splits (Nether, Fortress, Stronghold, Dragon)
                val aliveRunners = getAliveSpeedrunners()
                    .mapNotNull { level.server.playerList.getPlayer(it) }
                splits.checkRunnerSplits(this, aliveRunners, elapsedTicks)

                // 3. Update HUD telemetry stream and BossBar
                hud.tick(elapsedTicks)

                // 4. Update Smart Tracking Compasses across hunters
                if (elapsedTicks % settings.compassUpdateInterval.toLong() == 0L && settings.compassEnabled) {
                    SmartTrackingCompass.refreshGameCompasses(this)
                }

                // 5. Win condition check
                checkWinConditions()
            }
            else -> {}
        }
    }

    private fun setupNetherStart() {
        val netherLevel = level.server.getLevel(Level.NETHER) ?: return
        val netherSpawn = BlockPos(0, 70, 0)

        // Teleport speedrunners to Nether with 60s Fire Resistance
        getSpeedrunners().forEach { data ->
            val player = level.server.playerList.getPlayer(data.uuid) ?: return@forEach
            player.teleportTo(netherLevel, netherSpawn.x + 0.5, netherSpawn.y.toDouble(), netherSpawn.z + 0.5, setOf(), 0f, 0f, true)
            player.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200, 0, false, false))
        }

        spawnPos = netherSpawn
        hunterRespawnPos = netherSpawn
        broadcast("§4§l🔥 NETHER START ACTIVATED! §760 seconds of Fire Resistance granted.")
    }

    private fun spawnHunter() {
        val pos = hunterRespawnPos ?: spawnPos ?: BlockPos(0, 100, 0)
        aiManager.spawnAiHunter(level, pos)
    }

    private fun giveHunterCompasses() {
        val speedrunner = getSpeedrunnerPlayer() ?: return
        for (playerData in players.values) {
            if (playerData.role == ManhuntRole.HUNTER) {
                val player = level.server.playerList.getPlayer(playerData.uuid) ?: continue
                val compass = SmartTrackingCompass.createCompass(speedrunner, this)
                player.inventory.add(compass)
                player.sendSystemMessage(Component.literal("§b[Manhunt] §7You have received a §eSmart Tracking Compass§7!"))
            }
        }
    }

    private fun checkWinConditions() {
        if (splits.isDragonDefeated) {
            end("§a§lSpeedrunners have defeated the Ender Dragon! SPEEDRUNNERS WIN! §fFinal Time: §e${splits.getSplit(SpeedrunSplitType.DRAGON)?.formattedSplitTime}")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MESSAGING & HELPERS
    // ════════════════════════════════════════════════════════════════════════

    fun broadcast(message: String) {
        val component = Component.literal(message)
        for (playerData in players.values) {
            val player = level.server.playerList.getPlayer(playerData.uuid)
            player?.sendSystemMessage(component)
        }
    }

    fun broadcastTitle(title: String, subtitle: String = "", fadeIn: Int = 10, stay: Int = 70, fadeOut: Int = 20) {
        for (playerData in players.values) {
            val player = level.server.playerList.getPlayer(playerData.uuid) ?: continue
            player.connection.send(ClientboundSetTitleTextPacket(Component.literal(title)))
            if (subtitle.isNotEmpty()) {
                player.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(subtitle)))
            }
            player.connection.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
        }
    }

    private fun saveInventory(player: ServerPlayer): List<ItemStack> {
        val items = mutableListOf<ItemStack>()
        for (i in 0 until player.inventory.containerSize) {
            items.add(player.inventory.getItem(i).copy())
        }
        return items
    }

    fun getElapsedTimeFormatted(): String {
        return SpeedrunSplitTracker.formatTicks(elapsedTicks)
    }

    fun getDifficultyName(): String {
        return "${settings.hunterDifficulty.colorCode}${settings.hunterDifficulty.displayName}"
    }
}

/**
 * Game state phases
 */
enum class ManhuntGameState {
    SETUP,
    COUNTDOWN,
    HEAD_START,
    ACTIVE,
    ENDED
}

/**
 * Player roles
 */
enum class ManhuntRole {
    SPEEDRUNNER,
    HUNTER,
    SPECTATOR
}

/**
 * Per-player data for manhunt game
 */
data class ManhuntPlayerData(
    val uuid: UUID,
    val name: String,
    var role: ManhuntRole,
    val originalGameMode: GameType,
    val originalPosition: BlockPos,
    val originalInventory: List<ItemStack>,
    var isAlive: Boolean = true,
    var deathCount: Int = 0,
    var lastCompassUpdate: Long = 0
) {
    fun restoreState(player: ServerPlayer) {
        player.setGameMode(originalGameMode)
        player.teleportTo(
            originalPosition.x + 0.5,
            originalPosition.y.toDouble(),
            originalPosition.z + 0.5
        )
        player.inventory.clearContent()
        for ((index, item) in originalInventory.withIndex()) {
            if (index < player.inventory.containerSize) {
                player.inventory.setItem(index, item.copy())
            }
        }
    }
}

/**
 * Game settings configured via GUI / Commands / Presets
 */
data class ManhuntSettings(
    var preset: ManhuntPreset = ManhuntPreset.CLASSIC,
    var hunterDifficulty: HunterDifficulty = HunterDifficulty.TACTICAL,
    var difficulty: Float = 1.0f,
    var headStartSeconds: Int = 30,
    var hunterStartBlocks: Int = 64,
    var respawnEnabled: Boolean = false,
    var maxRespawns: Int = 3,
    var compassEnabled: Boolean = true,
    var compassUpdateInterval: Int = 20,
    var netherEnabled: Boolean = true,
    var endEnabled: Boolean = true,
    var winCondition: WinCondition = WinCondition.KILL_DRAGON
) {
    companion object {
        fun easy() = ManhuntSettings(
            preset = ManhuntPreset.CLASSIC,
            hunterDifficulty = HunterDifficulty.CASUAL,
            difficulty = 0.5f,
            headStartSeconds = 45,
            hunterStartBlocks = 32,
            respawnEnabled = true,
            maxRespawns = 5
        )

        fun normal() = ManhuntSettings(
            preset = ManhuntPreset.CLASSIC,
            hunterDifficulty = HunterDifficulty.TACTICAL,
            difficulty = 1.0f,
            headStartSeconds = 30,
            hunterStartBlocks = 64,
            respawnEnabled = false,
            maxRespawns = 0
        )

        fun hard() = ManhuntSettings(
            preset = ManhuntPreset.CLASSIC,
            hunterDifficulty = HunterDifficulty.TERMINATOR,
            difficulty = 1.5f,
            headStartSeconds = 15,
            hunterStartBlocks = 96,
            respawnEnabled = false,
            maxRespawns = 0
        )

        fun nightmare() = ManhuntSettings(
            preset = ManhuntPreset.CLASSIC,
            hunterDifficulty = HunterDifficulty.TERMINATOR,
            difficulty = 2.0f,
            headStartSeconds = 10,
            hunterStartBlocks = 128,
            respawnEnabled = false,
            maxRespawns = 0
        )
    }
}

/**
 * Win conditions for the game
 */
enum class WinCondition(val displayName: String) {
    KILL_DRAGON("Kill the Ender Dragon"),
    SURVIVE_TIME("Survive for set time"),
    REACH_END("Reach The End"),
    KILL_WITHER("Kill the Wither")
}
