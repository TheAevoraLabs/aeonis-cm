package com.qc.aeonis.minigame.manhunt

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.LodestoneTracker
import net.minecraft.world.level.Level
import java.util.Optional
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                     SMART MULTI-DIMENSIONAL TRACKING COMPASS               ║
 * ║                                                                            ║
 * ║  • Multi-dimensional tracking: Overworld <-> Nether <-> End.               ║
 * ║  • Nether portal memory & coordinate scale translation (1:8 ratio).       ║
 * ║  • Real-time height delta indicator with directional arrows (▲ / ▼ / ■).   ║
 * ║  • Lodestone-free needle calibration via LodestoneTracker (tracked=false). ║
 * ║  • Target cycling & live radar action-bar telemetry on right-click.        ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 */
object SmartTrackingCompass {

    const val COMPASS_NAME_TAG = "aeonis_smart_compass"

    data class TrackingTelemetry(
        val targetName: String,
        val targetDimension: String,
        val distanceBlocks: Double,
        val elevationDeltaY: Int,
        val arrowSymbol: String,
        val arrowColor: String,
        val elevationStatus: String,
        val isSameDimension: Boolean,
        val destinationPos: BlockPos
    )

    /**
     * Creates a new Smart Tracking Compass item stack tuned to the given target (or idle).
     */
    fun createCompass(target: ServerPlayer? = null, game: ManhuntGame? = null): ItemStack {
        val stack = ItemStack(Items.COMPASS)
        if (target != null && game != null) {
            updateCompassStack(stack, null, target, game)
        } else {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lSmart Tracking Compass"))
            stack.set(
                DataComponents.LORE,
                ItemLore(listOf(
                    Component.literal("§7Tracking: §8None"),
                    Component.literal("§8Right-click to scan for speedrunners")
                ))
            )
        }
        return stack
    }

    /**
     * Determines whether the given item stack is an Aeonis Tracking Compass.
     */
    fun isTrackingCompass(stack: ItemStack): Boolean {
        if (!stack.`is`(Items.COMPASS)) return false
        val customName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: ""
        return customName.contains("Tracking Compass")
    }

    /**
     * Computes multi-dimensional tracking telemetry from the holder to the target.
     */
    fun computeTelemetry(
        holderPos: BlockPos,
        holderLevel: ServerLevel,
        target: ServerPlayer,
        game: ManhuntGame
    ): TrackingTelemetry {
        val targetPos = target.blockPosition()
        val targetLevel = target.level() as ServerLevel
        val isSameDim = holderLevel.dimension() == targetLevel.dimension()

        val destPos: BlockPos
        val destDimName: String

        if (isSameDim) {
            destPos = targetPos
            destDimName = getDimensionName(targetLevel.dimension())
        } else {
            // Multi-dimensional portal / coordinate tracking
            val holderDim = holderLevel.dimension()
            val targetDim = targetLevel.dimension()

            when {
                // Hunter in Overworld, Runner in Nether
                holderDim == Level.OVERWORLD && targetDim == Level.NETHER -> {
                    val portalPos = game.getLastPortalUsed(target.uuid, Level.OVERWORLD)
                        ?: BlockPos(targetPos.x * 8, targetPos.y.coerceIn(60, 120), targetPos.z * 8)
                    destPos = portalPos
                    destDimName = "Nether (via Portal)"
                }
                // Hunter in Nether, Runner in Overworld
                holderDim == Level.NETHER && targetDim == Level.OVERWORLD -> {
                    val portalPos = game.getLastPortalUsed(target.uuid, Level.NETHER)
                        ?: BlockPos(targetPos.x / 8, targetPos.y.coerceIn(32, 110), targetPos.z / 8)
                    destPos = portalPos
                    destDimName = "Overworld (via Portal)"
                }
                // Runner in The End
                targetDim == Level.END -> {
                    if (holderDim == Level.OVERWORLD) {
                        val strongholdPos = game.getLastPortalUsed(target.uuid, Level.OVERWORLD)
                            ?: BlockPos(0, 40, 0)
                        destPos = strongholdPos
                        destDimName = "The End (Stronghold)"
                    } else {
                        // In Nether -> guide back to Overworld portal first
                        destPos = game.getLastPortalUsed(target.uuid, Level.NETHER) ?: BlockPos(0, 64, 0)
                        destDimName = "The End (Exit Nether)"
                    }
                }
                // Fallback: cross dimension
                else -> {
                    destPos = targetPos
                    destDimName = getDimensionName(targetDim)
                }
            }
        }

        val dx = (destPos.x - holderPos.x).toDouble()
        val dy = (destPos.y - holderPos.y).toDouble()
        val dz = (destPos.z - holderPos.z).toDouble()
        val dist = sqrt(dx * dx + dy * dy + dz * dz)

        val deltaY = destPos.y - holderPos.y
        val (arrow, color, status) = when {
            deltaY > 3 -> Triple("▲", "§a", "+${deltaY}m Above")
            deltaY < -3 -> Triple("▼", "§c", "-${abs(deltaY)}m Below")
            else -> Triple("■", "§b", "Level (±3m)")
        }

        return TrackingTelemetry(
            targetName = target.name.string,
            targetDimension = destDimName,
            distanceBlocks = dist,
            elevationDeltaY = deltaY,
            arrowSymbol = arrow,
            arrowColor = color,
            elevationStatus = status,
            isSameDimension = isSameDim,
            destinationPos = destPos
        )
    }

    /**
     * Updates an ItemStack's LodestoneTracker, CustomName, and Lore to point at the target.
     */
    fun updateCompassStack(
        stack: ItemStack,
        holder: ServerPlayer?,
        target: ServerPlayer,
        game: ManhuntGame
    ) {
        val holderLevel = (holder?.level() as? ServerLevel) ?: (target.level() as ServerLevel)
        val holderPos = holder?.blockPosition() ?: target.blockPosition()
        val telemetry = computeTelemetry(holderPos, holderLevel, target, game)

        // Point vanilla compass needle to destination pos in the holder's current dimension
        val targetGlobalPos = GlobalPos.of(holderLevel.dimension(), telemetry.destinationPos)
        // tracked = false allows compass to point at any coordinate without needing a physical lodestone block!
        stack.set(DataComponents.LODESTONE_TRACKER, LodestoneTracker(Optional.of(targetGlobalPos), false))

        // Custom name with target and height arrow
        val nameText = "§b§lTracking Compass §7(§e${telemetry.targetName}§7) ${telemetry.arrowColor}${telemetry.arrowSymbol} §f${abs(telemetry.elevationDeltaY)}m"
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(nameText))

        // Lore with telemetry details
        val distFormatted = String.format("%.1f", telemetry.distanceBlocks)
        val loreLines = listOf(
            Component.literal("§7Target: §e${telemetry.targetName}"),
            Component.literal("§7Dimension: §b${telemetry.targetDimension}"),
            Component.literal("§7Distance: §f${distFormatted}m"),
            Component.literal("§7Elevation: ${telemetry.arrowColor}${telemetry.arrowSymbol} §f${telemetry.elevationStatus}"),
            Component.literal("§8Right-click: cycle target / radar ping")
        )
        stack.set(DataComponents.LORE, ItemLore(loreLines))
    }

    /**
     * Handles right-clicking the tracking compass.
     * Cycles to the next available speedrunner, plays sound feedback, and pings the action bar.
     */
    fun onCompassUse(player: ServerPlayer, stack: ItemStack): Boolean {
        val game = ManhuntManager.getPlayerGame(player.uuid) ?: return false
        val speedrunnerUuids = game.getAliveSpeedrunners()
        if (speedrunnerUuids.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[Compass] §7No active speedrunners found to track."))
            return false
        }

        val speedrunners = speedrunnerUuids.mapNotNull { game.level.server.playerList.getPlayer(it) }
        if (speedrunners.isEmpty()) return false

        // Determine currently tracked target
        val currentTargetName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: ""
        val currentIndex = speedrunners.indexOfFirst { currentTargetName.contains(it.name.string) }
        val nextTarget = if (currentIndex != -1 && currentIndex + 1 < speedrunners.size) {
            speedrunners[currentIndex + 1]
        } else {
            speedrunners[0]
        }

        // Update compass
        updateCompassStack(stack, player, nextTarget, game)

        // Telemetry calculation for action bar ping
        val telemetry = computeTelemetry(player.blockPosition(), player.level() as ServerLevel, nextTarget, game)
        val distFormatted = String.format("%.1f", telemetry.distanceBlocks)

        val pingMessage = Component.literal(
            "§b[Compass] §7Tracking §e${telemetry.targetName} §7| §f${distFormatted}m §7| ${telemetry.arrowColor}${telemetry.arrowSymbol} §f${telemetry.elevationStatus} §7| §d${telemetry.targetDimension}"
        )
        player.sendSystemMessage(pingMessage, true) // true = action bar
        (player.level() as ServerLevel).playSound(
            null, player.blockPosition(),
            SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS,
            0.6f, 1.4f
        )
        return true
    }

    /**
     * Updates tracking compasses across all hunters in an active Manhunt game.
     */
    fun refreshGameCompasses(game: ManhuntGame) {
        val speedrunners = game.getAliveSpeedrunners()
            .mapNotNull { game.level.server.playerList.getPlayer(it) }
        if (speedrunners.isEmpty()) return

        val server = game.level.server
        for (playerData in game.players.values) {
            val player = server.playerList.getPlayer(playerData.uuid) ?: continue
            if (playerData.role == ManhuntRole.SPEEDRUNNER) continue

            // Update all tracking compasses in hunter's inventory
            for (i in 0 until player.inventory.containerSize) {
                val stack = player.inventory.getItem(i)
                if (isTrackingCompass(stack)) {
                    val target = speedrunners.minByOrNull { player.distanceToSqr(it) } ?: speedrunners[0]
                    updateCompassStack(stack, player, target, game)
                }
            }
        }
    }

    private fun getDimensionName(dim: net.minecraft.resources.ResourceKey<Level>): String {
        return when (dim) {
            Level.OVERWORLD -> "Overworld"
            Level.NETHER -> "Nether"
            Level.END -> "The End"
            else -> dim.identifier().path.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}
