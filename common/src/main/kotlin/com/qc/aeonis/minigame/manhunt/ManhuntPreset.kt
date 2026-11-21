package com.qc.aeonis.minigame.manhunt

/**
 * Game presets for Manhunt / Speedrun.
 * Each preset provides a distinct gameplay style with customized rules,
 * head starts, spawn conditions, and hunter mechanics.
 */
enum class ManhuntPreset(
    val id: String,
    val displayName: String,
    val badge: String,
    val description: String
) {
    /**
     * Classic Dream-style Manhunt:
     * Speedrunners beat the Ender Dragon, Hunters have infinite respawns and tracking compasses.
     * Speedrunners have 1 life.
     */
    CLASSIC(
        id = "classic",
        displayName = "Classic Manhunt",
        badge = "§e[CLASSIC]",
        description = "Standard Dream-style manhunt. Beat the Ender Dragon without dying. Hunters respawn infinitely."
    ),

    /**
     * Hitman:
     * The Hunter is a deadly assassin with stealth cloaking and fatal backstab strikes.
     * Speedrunners have 3 lives to compensate for lethal ambush strikes.
     */
    HITMAN(
        id = "hitman",
        displayName = "Hitman",
        badge = "§8[HITMAN]",
        description = "Hunter has stealth invisibility and deals 3x critical backstab damage. Speedrunners have 3 lives."
    ),

    /**
     * Assassin:
     * Hyper-mobile hunter with extreme parkour (Speed II, Jump Boost, zero fall damage, double leap).
     * Hunter is forbidden from placing or breaking blocks — pure parkour pursuit!
     */
    ASSASSIN(
        id = "assassin",
        displayName = "Assassin",
        badge = "§c[ASSASSIN]",
        description = "Hyper-mobile Hunter (Speed II, Jump Boost, No Fall Damage) that cannot build or break blocks."
    ),

    /**
     * Nether Start:
     * The game begins immediately in the fiery depths of the Nether!
     * Players receive a 60-second Fire Resistance grace period to escape initial lava hazards.
     */
    NETHER_START(
        id = "nether_start",
        displayName = "Nether Start",
        badge = "§4[NETHER START]",
        description = "Game begins inside the Nether with a 60s Fire Resistance grace period. Navigate hell to conquer the world!"
    );

    /**
     * Applies this preset's settings onto a ManhuntSettings instance.
     */
    fun applyTo(settings: ManhuntSettings, difficulty: HunterDifficulty = HunterDifficulty.TACTICAL) {
        settings.preset = this
        settings.hunterDifficulty = difficulty
        settings.difficulty = difficulty.damageMultiplier.toFloat()
        settings.headStartSeconds = difficulty.headStartSeconds

        when (this) {
            CLASSIC -> {
                settings.respawnEnabled = false
                settings.maxRespawns = 0
                settings.hunterStartBlocks = 64
                settings.netherEnabled = true
                settings.endEnabled = true
                settings.winCondition = WinCondition.KILL_DRAGON
            }
            HITMAN -> {
                settings.respawnEnabled = true
                settings.maxRespawns = 3
                settings.headStartSeconds = (difficulty.headStartSeconds - 10).coerceAtLeast(15)
                settings.hunterStartBlocks = 32
                settings.winCondition = WinCondition.KILL_DRAGON
            }
            ASSASSIN -> {
                settings.respawnEnabled = false
                settings.maxRespawns = 0
                settings.hunterStartBlocks = 0 // Assassin cannot build/break blocks
                settings.headStartSeconds = (difficulty.headStartSeconds + 10)
                settings.winCondition = WinCondition.KILL_DRAGON
            }
            NETHER_START -> {
                settings.respawnEnabled = false
                settings.maxRespawns = 0
                settings.hunterStartBlocks = 64
                settings.winCondition = WinCondition.KILL_DRAGON
            }
        }
    }

    companion object {
        fun fromId(id: String): ManhuntPreset =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
                ?: entries.firstOrNull { it.displayName.equals(id, ignoreCase = true) }
                ?: CLASSIC
    }
}
