package com.qc.aeonis.minigame.manhunt

import com.qc.aeonis.util.playNotifySound
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.structure.BuiltinStructures

/**
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                     SPEEDRUN SPLIT-TIMER ENGINE                            ║
 * ║                                                                            ║
 * ║  Tracks official speedrun splits across the manhunt timeline:              ║
 * ║  1. Nether Entry (Dimension crossover)                                     ║
 * ║  2. Fortress Discovery (Fortress / Bastion structure bounding box check)   ║
 * ║  3. Stronghold / End Entry (Overworld Stronghold or End dimension arrival)  ║
 * ║  4. Dragon Death (EnderDragonFight victory check)                          ║
 * ║                                                                            ║
 * ║  Calculates split timestamps, segment deltas, and broadcasts banner toasts.║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 */
enum class SpeedrunSplitType(
    val id: String,
    val displayName: String,
    val icon: String,
    val colorCode: String
) {
    START("start", "Start", "⏱", "§a"),
    NETHER("nether", "Nether", "🔥", "§c"),
    FORTRESS("fortress", "Fortress", "🏰", "§6"),
    STRONGHOLD("stronghold", "Stronghold", "👁", "§d"),
    DRAGON("dragon", "Dragon Slain", "🐉", "§b§l");

    companion object {
        fun fromId(id: String): SpeedrunSplitType? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

data class SpeedrunSplit(
    val type: SpeedrunSplitType,
    val tickTimestamp: Long,
    val formattedSplitTime: String,
    val segmentTicks: Long,
    val segmentDurationFormatted: String
)

class SpeedrunSplitTracker {

    private val splits = LinkedHashMap<SpeedrunSplitType, SpeedrunSplit>()
    private var lastSplitTick: Long = 0L

    var isDragonDefeated: Boolean = false
        private set

    /**
     * Initializes the tracker on game start.
     */
    fun onGameStart(startTick: Long) {
        splits.clear()
        lastSplitTick = startTick
        val startSplit = SpeedrunSplit(
            type = SpeedrunSplitType.START,
            tickTimestamp = startTick,
            formattedSplitTime = "00:00",
            segmentTicks = 0L,
            segmentDurationFormatted = "00:00"
        )
        splits[SpeedrunSplitType.START] = startSplit
    }

    fun hasSplit(type: SpeedrunSplitType): Boolean = splits.containsKey(type)

    fun getSplit(type: SpeedrunSplitType): SpeedrunSplit? = splits[type]

    fun getAllSplits(): List<SpeedrunSplit> = splits.values.toList()

    /**
     * Attempts to trigger and record a split if not already achieved.
     */
    fun recordSplit(
        type: SpeedrunSplitType,
        currentTick: Long,
        game: ManhuntGame,
        runnerName: String? = null
    ): SpeedrunSplit? {
        if (splits.containsKey(type)) return null

        val elapsedFromStart = (currentTick - game.startTime).coerceAtLeast(0L)
        val segmentTicks = (currentTick - lastSplitTick).coerceAtLeast(0L)
        lastSplitTick = currentTick

        val formattedElapsed = formatTicks(elapsedFromStart)
        val formattedSegment = formatTicks(segmentTicks)

        val split = SpeedrunSplit(
            type = type,
            tickTimestamp = currentTick,
            formattedSplitTime = formattedElapsed,
            segmentTicks = segmentTicks,
            segmentDurationFormatted = formattedSegment
        )
        splits[type] = split

        if (type == SpeedrunSplitType.DRAGON) {
            isDragonDefeated = true
        }

        // Broadcast toast banner
        val runnerTag = if (runnerName != null) " §7by §e$runnerName" else ""
        game.broadcast(
            "§6§l⚡ SPEEDRUN SPLIT §8» ${type.colorCode}${type.icon} ${type.displayName}§f: §a$formattedElapsed §7(+§e$formattedSegment§7)$runnerTag"
        )
        game.broadcastTitle(
            "${type.colorCode}${type.icon} ${type.displayName}",
            "§a$formattedElapsed §7(+§e$formattedSegment§7)",
            5, 45, 10
        )

        // Play celebratory sound across players
        for (playerData in game.players.values) {
            val player = game.level.server.playerList.getPlayer(playerData.uuid) ?: continue
            val sound = if (type == SpeedrunSplitType.DRAGON) SoundEvents.UI_TOAST_CHALLENGE_COMPLETE else SoundEvents.PLAYER_LEVELUP
            player.playNotifySound(sound, SoundSource.MASTER, 0.7f, 1.2f)
        }

        return split
    }

    /**
     * Polls active speedrunners to detect structure or dimension advancement splits.
     */
    fun checkRunnerSplits(game: ManhuntGame, runners: List<ServerPlayer>, currentTick: Long) {
        for (runner in runners) {
            val level = runner.level() as net.minecraft.server.level.ServerLevel
            val dim = level.dimension()
            val pos = runner.blockPosition()

            // 1. Check Nether split
            if (dim == Level.NETHER && !hasSplit(SpeedrunSplitType.NETHER)) {
                recordSplit(SpeedrunSplitType.NETHER, currentTick, game, runner.name.string)
            }

            // 2. Check Fortress split
            if (dim == Level.NETHER && !hasSplit(SpeedrunSplitType.FORTRESS)) {
                if (isInsideFortressOrBastion(level, pos)) {
                    recordSplit(SpeedrunSplitType.FORTRESS, currentTick, game, runner.name.string)
                }
            }

            // 3. Check Stronghold / End split
            if (!hasSplit(SpeedrunSplitType.STRONGHOLD)) {
                if (dim == Level.END) {
                    recordSplit(SpeedrunSplitType.STRONGHOLD, currentTick, game, runner.name.string)
                } else if (dim == Level.OVERWORLD && isInsideStronghold(level, pos)) {
                    recordSplit(SpeedrunSplitType.STRONGHOLD, currentTick, game, runner.name.string)
                }
            }
        }

        // 4. Check Dragon defeat split
        if (!hasSplit(SpeedrunSplitType.DRAGON)) {
            val endLevel = game.level.server.getLevel(Level.END)
            if (endLevel != null) {
                val dragonFight = endLevel.dragonFight
                if (dragonFight != null && dragonFight.hasPreviouslyKilledDragon()) {
                    recordSplit(SpeedrunSplitType.DRAGON, currentTick, game)
                }
            }
        }
    }

    private fun isInsideFortressOrBastion(level: ServerLevel, pos: net.minecraft.core.BlockPos): Boolean {
        try {
            val structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
            val fortressHolder = structureRegistry.get(BuiltinStructures.FORTRESS)
            if (fortressHolder.isPresent && level.structureManager().getStructureAt(pos, fortressHolder.get().value()).isValid) {
                return true
            }
            val bastionHolder = structureRegistry.get(BuiltinStructures.BASTION_REMNANT)
            if (bastionHolder.isPresent && level.structureManager().getStructureAt(pos, bastionHolder.get().value()).isValid) {
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun isInsideStronghold(level: ServerLevel, pos: net.minecraft.core.BlockPos): Boolean {
        try {
            val structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
            val strongholdHolder = structureRegistry.get(BuiltinStructures.STRONGHOLD)
            if (strongholdHolder.isPresent && level.structureManager().getStructureAt(pos, strongholdHolder.get().value()).isValid) {
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    companion object {
        fun formatTicks(ticks: Long): String {
            val totalSeconds = (ticks / 20).coerceAtLeast(0L)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val tenths = (ticks % 20) / 2
            return String.format("%02d:%02d.%d", minutes, seconds, tenths)
        }
    }
}
