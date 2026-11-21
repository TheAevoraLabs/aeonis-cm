package com.qc.aeonis.minigame.manhunt

import com.qc.aeonis.entity.HunterEntity
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.level.Level
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                      SPEEDRUN HUD & TELEMETRY STREAMER                     ║
 * ║                                                                            ║
 * ║  • Live split-timer overlay & segment progress.                            ║
 * ║  • Real-time 3D distance and relative height telemetry radar.              ║
 * ║  • Zero-latency action-bar status stream to speedrunners & hunters.        ║
 * ║  • Integrated game BossBar tracker with threat color indicators.           ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 */
class SpeedrunHud(private val game: ManhuntGame) {

    private val bossBar: ServerBossEvent = ServerBossEvent(
        java.util.UUID.randomUUID(),
        Component.literal("§6⚡ Manhunt Speedrun"),
        BossEvent.BossBarColor.YELLOW,
        BossEvent.BossBarOverlay.PROGRESS
    )

    init {
        bossBar.isVisible = true
    }

    fun attachPlayer(player: ServerPlayer) {
        bossBar.addPlayer(player)
    }

    fun detachPlayer(player: ServerPlayer) {
        bossBar.removePlayer(player)
    }

    fun removeAll() {
        bossBar.removeAllPlayers()
    }

    /**
     * Ticks the HUD, updating action bar telemetry and boss bar for all players.
     */
    fun tick(elapsedTicks: Long) {
        val server = game.level.server
        val speedrunnerUuids = game.getAliveSpeedrunners()
        val speedrunners = speedrunnerUuids.mapNotNull { server.playerList.getPlayer(it) }

        // Find hunter entities or player hunters
        val hunterPlayers = game.players.values
            .filter { it.role != ManhuntRole.SPEEDRUNNER && it.isAlive }
            .mapNotNull { server.playerList.getPlayer(it.uuid) }

        val aiHunter = game.hunterEntityId?.let { id ->
            game.level.getEntity(id) as? HunterEntity
        }

        val formattedTime = SpeedrunSplitTracker.formatTicks(elapsedTicks)
        val activeSplitName = getNextSplitName(game.splits)

        // Update BossBar title and progress
        val splitProgress = (game.splits.getAllSplits().size - 1).coerceAtLeast(0) / 4.0f
        bossBar.progress = splitProgress.coerceIn(0.0f, 1.0f)
        bossBar.name = Component.literal(
            "§6⚡ Speedrun: §f$formattedTime §7[§e$activeSplitName§7] §7· §cDifficulty: ${game.settings.hunterDifficulty.colorCode}${game.settings.hunterDifficulty.displayName}"
        )

        // Telemetry update every 5 ticks
        if (elapsedTicks % 5 == 0L && speedrunners.isNotEmpty()) {
            for (runner in speedrunners) {
                // Find nearest hunter (player or AI bot)
                val runnerPos = runner.blockPosition()
                val runnerDim = (runner.level() as net.minecraft.server.level.ServerLevel).dimension()

                var nearestDist = Double.MAX_VALUE
                var deltaY = 0
                var hunterLabel = "Hunter"
                var isCrossDim = false

                if (aiHunter != null && aiHunter.isAlive) {
                    val aiDim = aiHunter.level().dimension()
                    if (aiDim == runnerDim) {
                        val dx = aiHunter.x - runner.x
                        val dy = aiHunter.y - runner.y
                        val dz = aiHunter.z - runner.z
                        nearestDist = sqrt(dx * dx + dy * dy + dz * dz)
                        deltaY = (aiHunter.y - runner.y).toInt()
                        hunterLabel = "Hunter AI (${game.settings.hunterDifficulty.displayName})"
                    } else {
                        isCrossDim = true
                        hunterLabel = "Hunter AI [${getDimShort(aiDim)}]"
                    }
                }

                for (hp in hunterPlayers) {
                    val hpDim = (hp.level() as net.minecraft.server.level.ServerLevel).dimension()
                    if (hpDim == runnerDim) {
                        val d = runner.distanceTo(hp).toDouble()
                        if (d < nearestDist) {
                            nearestDist = d
                            deltaY = (hp.y - runner.y).toInt()
                            hunterLabel = hp.name.string
                            isCrossDim = false
                        }
                    } else if (nearestDist == Double.MAX_VALUE) {
                        isCrossDim = true
                        hunterLabel = "${hp.name.string} [${getDimShort(hpDim)}]"
                    }
                }

                val threat = if (isCrossDim) SpeedrunHudState.ThreatLevel.SAFE else SpeedrunHudState.ThreatLevel.fromDistance(nearestDist)
                val (arrow, arrowColor) = when {
                    deltaY > 3 -> "▲" to "§a"
                    deltaY < -3 -> "▼" to "§c"
                    else -> "■" to "§b"
                }

                val distStr = if (isCrossDim) "CROSS-DIM" else String.format("%.1fm", nearestDist)
                val elevationStr = if (isCrossDim) "" else " $arrowColor$arrow §f${abs(deltaY)}m"

                // Stream Action Bar to Speedrunner
                val runnerActionBar = Component.literal(
                    "§e⏱ $formattedTime §7| §c§lHUNTER: §f$distStr$elevationStr ${threat.colorCode}[${threat.label}] §7| §6§lSPLIT: §f$activeSplitName"
                )
                runner.sendSystemMessage(runnerActionBar, true)

                // Mirror into local SpeedrunHudState
                updateSharedClientState(formattedTime, hunterLabel, nearestDist, deltaY, arrow, threat, runnerDim, isCrossDim)
            }

            // Stream Action Bar to Hunters
            for (hunter in hunterPlayers) {
                val target = speedrunners.minByOrNull { hunter.distanceToSqr(it) } ?: speedrunners[0]
                val telemetry = SmartTrackingCompass.computeTelemetry(hunter.blockPosition(), hunter.level() as net.minecraft.server.level.ServerLevel, target, game)
                val distStr = String.format("%.1fm", telemetry.distanceBlocks)

                val hunterActionBar = Component.literal(
                    "§b§lTARGET: §e${telemetry.targetName} §7| §f$distStr ${telemetry.arrowColor}${telemetry.arrowSymbol} §f${abs(telemetry.elevationDeltaY)}m §7| §d${telemetry.targetDimension} §7| §e⏱ $formattedTime"
                )
                hunter.sendSystemMessage(hunterActionBar, true)
            }
        }
    }

    private fun updateSharedClientState(
        time: String,
        hunter: String,
        dist: Double,
        elevation: Int,
        arrow: String,
        threat: SpeedrunHudState.ThreatLevel,
        dim: net.minecraft.resources.ResourceKey<Level>,
        crossDim: Boolean
    ) {
        val splitEntries = SpeedrunSplitType.entries.map { splitType ->
            val completed = game.splits.hasSplit(splitType)
            val splitData = game.splits.getSplit(splitType)
            SpeedrunHudState.SplitEntry(
                name = splitType.displayName,
                icon = splitType.icon,
                timestamp = splitData?.formattedSplitTime ?: "--:--",
                isCompleted = completed,
                isCurrent = !completed && getNextSplitType(game.splits) == splitType
            )
        }

        SpeedrunHudState.updateClientState(
            active = true,
            elapsedTime = time,
            hunter = hunter,
            dist = dist,
            elevation = elevation,
            arrow = arrow,
            threat = threat,
            dim = getDimShort(dim),
            crossDim = crossDim,
            splits = splitEntries
        )
    }

    private fun getNextSplitName(tracker: SpeedrunSplitTracker): String {
        return getNextSplitType(tracker).displayName
    }

    private fun getNextSplitType(tracker: SpeedrunSplitTracker): SpeedrunSplitType {
        for (type in SpeedrunSplitType.entries) {
            if (!tracker.hasSplit(type)) return type
        }
        return SpeedrunSplitType.DRAGON
    }

    private fun getDimShort(dim: net.minecraft.resources.ResourceKey<Level>): String = when (dim) {
        Level.OVERWORLD -> "Overworld"
        Level.NETHER -> "Nether"
        Level.END -> "End"
        else -> dim.identifier().path
    }
}
