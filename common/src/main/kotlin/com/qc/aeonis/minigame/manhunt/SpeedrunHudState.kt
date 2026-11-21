package com.qc.aeonis.minigame.manhunt

/**
 * Shared client/server model for the Speedrun split-timer HUD and distance telemetry radar.
 */
object SpeedrunHudState {

    enum class ThreatLevel(val label: String, val colorCode: String, val hexColor: Int) {
        SAFE("SAFE", "§a", 0xFF22C55E.toInt()),
        CAUTION("CAUTION", "§e", 0xFFEAB308.toInt()),
        DANGER("DANGER", "§6", 0xFFF97316.toInt()),
        CRITICAL("CRITICAL", "§c§l", 0xFFEF4444.toInt());

        companion object {
            fun fromDistance(distance: Double): ThreatLevel = when {
                distance > 100.0 -> SAFE
                distance > 50.0 -> CAUTION
                distance > 20.0 -> DANGER
                else -> CRITICAL
            }
        }
    }

    data class SplitEntry(
        val name: String,
        val icon: String,
        val timestamp: String,
        val isCompleted: Boolean,
        val isCurrent: Boolean
    )

    // Client-side mirror fields
    var isEnabled: Boolean = true
    var isActiveGame: Boolean = false
    var elapsedTimeFormatted: String = "00:00.0"
    var hunterName: String = "Hunter Bot"
    var hunterDistance: Double = 0.0
    var hunterElevationDelta: Int = 0
    var elevationArrow: String = "■"
    var threatLevel: ThreatLevel = ThreatLevel.SAFE
    var currentDimension: String = "Overworld"
    var isCrossDimension: Boolean = false
    var splitEntries: List<SplitEntry> = emptyList()

    fun updateClientState(
        active: Boolean,
        elapsedTime: String,
        hunter: String,
        dist: Double,
        elevation: Int,
        arrow: String,
        threat: ThreatLevel,
        dim: String,
        crossDim: Boolean,
        splits: List<SplitEntry>
    ) {
        isActiveGame = active
        elapsedTimeFormatted = elapsedTime
        hunterName = hunter
        hunterDistance = dist
        hunterElevationDelta = elevation
        elevationArrow = arrow
        threatLevel = threat
        currentDimension = dim
        isCrossDimension = crossDim
        splitEntries = splits
    }
}
