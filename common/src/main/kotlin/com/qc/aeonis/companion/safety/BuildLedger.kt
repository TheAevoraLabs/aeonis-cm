package com.qc.aeonis.companion.safety

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

/**
 * Remembers which blocks were touched by a **player** and which were placed by the
 * **companion**, so the companion can be kept out of the player's base.
 *
 * ### Why a ledger at all
 * Minecraft does not record who placed a block. A cobblestone wall a player built and a
 * cobblestone wall generated in a dungeon are the same `BlockState`, so there is no query that
 * answers "did a player make this". The only way to know is to *watch* players build.
 *
 * This class is that watch, and nothing more: it is deliberately just a keyed set of positions
 * with no opinions about what should be done with them. All policy lives in [TerritoryGuard],
 * which makes the policy testable without a world and lets the loader-specific event wiring stay
 * a three-line call in each loader's initializer.
 *
 * ### Scope, honestly stated
 * The ledger only knows about activity that happened **while the server was running** — it is
 * not persisted, because a saved copy would go stale the moment a player loaded a different
 * world and would silently protect blocks that no longer exist. Structures a player built in an
 * earlier session are therefore protected by [TerritoryGuard]'s *other* rules (never break
 * crafted blocks, never break containers, never dig beside a player), not by this ledger. The
 * layers are complementary on purpose: the ledger catches "the player just put this here", and
 * the material rules catch "this was built by someone, some time".
 *
 * ### Bounded
 * Entries expire after [TTL_TICKS] and the map is capped at [MAX_ENTRIES], because a busy server
 * would otherwise accumulate one entry per block broken by every player, forever.
 */
object BuildLedger {

    /** What the companion was doing when it placed a block. */
    enum class BotPlacementKind {
        /** Pillar, bridge or step-up stair: fair game to take back when the climb is done. */
        SCAFFOLD,

        /** Shelters, torches and anything else that is meant to stay. */
        PERMANENT
    }

    /**
     * How long a player's mark on a position is remembered.
     *
     * Thirty minutes of game time is long enough that a player who walls in the companion's
     * roaming area and then walks away is still protected, and short enough that the memory of a
     * hole someone dug once does not permanently sterilise a region.
     */
    const val TTL_TICKS: Long = 20L * 60 * 30

    /** Hard cap on entries, to bound memory on a long-running server. */
    const val MAX_ENTRIES: Int = 8192

    private val playerTouched = HashMap<GlobalPos, Long>()
    private val botPlaced = HashMap<GlobalPos, BotPlacementKind>()

    private var lastPruneTick: Long = Long.MIN_VALUE

    /** Ticks between expiry sweeps. */
    private const val PRUNE_INTERVAL_TICKS = 200L

    // ---------------------------------------------------------------- recording

    /**
     * Records that a player broke, placed or interacted with [pos].
     *
     * Right-clicking is recorded as well as breaking and placing, and that is load-bearing: the
     * vanilla placement path clicks the *face* of an existing block, so recording the interaction
     * position marks the neighbour of a freshly placed block. Combined with
     * [TerritoryGuard]'s one-block neighbourhood rule, the block the player just placed is
     * protected even though no loader exposes a "block was placed" callback that runs afterwards.
     */
    fun notePlayerTouched(level: ServerLevel, pos: BlockPos, tick: Long = level.gameTime) {
        pruneIfDue(tick)
        playerTouched[GlobalPos.of(level.dimension(), pos.immutable())] = tick
        if (playerTouched.size > MAX_ENTRIES) evictOldest(playerTouched, MAX_ENTRIES / 4)
    }

    /** Records a block the companion itself placed, tagged with why. */
    fun noteBotPlaced(level: ServerLevel, pos: BlockPos, kind: BotPlacementKind) {
        botPlaced[GlobalPos.of(level.dimension(), pos.immutable())] = kind
        if (botPlaced.size > MAX_ENTRIES) evictOldestPlaced(MAX_ENTRIES / 4)
    }

    /**
     * Forgets [pos] entirely — call it when a block is removed, so a later break in the same cell
     * does not inherit the old mark.
     */
    fun forget(level: ServerLevel, pos: BlockPos) {
        val key = GlobalPos.of(level.dimension(), pos)
        playerTouched.remove(key)
        botPlaced.remove(key)
    }

    // ---------------------------------------------------------------- queries

    /** True when a player is known to have broken, placed or used the block at [pos]. */
    fun isPlayerTouched(level: Level, pos: BlockPos): Boolean =
        playerTouched.containsKey(GlobalPos.of(level.dimension(), pos))

    /**
     * True when any position within [radius] (Chebyshev) of [pos] carries a player mark.
     *
     * The neighbourhood is what makes this useful rather than superficial: a player's wall is
     * many blocks, and each one was placed by a click on the block beside it, so the marks are
     * dense around a build and a single query catches the whole area.
     */
    fun isPlayerTerritory(level: Level, pos: BlockPos, radius: Int = 1): Boolean {
        if (playerTouched.isEmpty()) return false
        val dimension = level.dimension()
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    if (playerTouched.containsKey(GlobalPos.of(dimension, pos.offset(dx, dy, dz)))) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /** True when the companion placed the block at [pos], and why it did so. */
    fun botPlacement(level: Level, pos: BlockPos): BotPlacementKind? =
        botPlaced[GlobalPos.of(level.dimension(), pos)]

    /** True when the block at [pos] is the companion's own scaffolding. */
    fun isBotScaffold(level: Level, pos: BlockPos): Boolean =
        botPlacement(level, pos) == BotPlacementKind.SCAFFOLD

    /**
     * Every scaffold position the companion still has standing, nearest [origin] first.
     *
     * Used by the reclaim pass: the companion takes back the stair it built instead of leaving a
     * tower behind, which is the difference between a helper and a litterer.
     */
    fun scaffoldPositionsNear(level: ServerLevel, origin: BlockPos, radius: Int = 24): List<BlockPos> {
        if (botPlaced.isEmpty()) return emptyList()
        val dimension = level.dimension()
        val found = ArrayList<BlockPos>()
        for ((key, kind) in botPlaced) {
            if (kind != BotPlacementKind.SCAFFOLD) continue
            if (key.dimension() != dimension) continue
            val pos = key.pos()
            if (!origin.closerThan(pos, radius.toDouble())) continue
            found.add(pos)
        }
        found.sortBy { it.distSqr(origin) }
        return found
    }

    /** Number of live entries, for `/comp status` and the debug log. */
    fun size(): Int = playerTouched.size + botPlaced.size

    /** Counts of each side, for diagnostics. */
    fun describe(): String = "player marks=${playerTouched.size}, bot placemarks=${botPlaced.size}"

    /** Forgets everything, e.g. on server stop. */
    fun clear() {
        playerTouched.clear()
        botPlaced.clear()
        lastPruneTick = Long.MIN_VALUE
    }

    // ---------------------------------------------------------------- maintenance

    private fun pruneIfDue(now: Long) {
        if (lastPruneTick != Long.MIN_VALUE && now - lastPruneTick < PRUNE_INTERVAL_TICKS) return
        lastPruneTick = now
        if (playerTouched.isEmpty()) return
        val cutoff = now - TTL_TICKS
        playerTouched.entries.removeIf { it.value < cutoff }
    }

    private fun evictOldest(map: MutableMap<GlobalPos, Long>, keep: Int) {
        if (map.size <= keep) return
        val survivors = map.entries.sortedByDescending { it.value }.take(keep)
        map.clear()
        survivors.forEach { map[it.key] = it.value }
    }

    private fun evictOldestPlaced(keep: Int) {
        if (botPlaced.size <= keep) return
        // Insertion order is not preserved by HashMap, so drop an arbitrary slice rather than
        // pretending to know which placements are oldest.
        val survivors = botPlaced.entries.take(keep)
        val kept = HashMap<GlobalPos, BotPlacementKind>(keep * 2)
        survivors.forEach { kept[it.key] = it.value }
        botPlaced.clear()
        botPlaced.putAll(kept)
    }
}
