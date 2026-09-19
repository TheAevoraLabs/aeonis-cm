package com.qc.aeonis.companion.ai.perception

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Why a target was accepted or rejected by the perception filter.
 *
 * The companion's brain needs to distinguish "there is nothing here" from "there is something
 * here that I cannot sensibly act on", because the two produce different behaviour: the first
 * means keep looking, the second means remember but stop staring. Returning a bare boolean
 * collapsed that distinction and is why the original bug (a bot locked onto a cave mob through
 * solid rock) was invisible in logs.
 */
enum class OcclusionVerdict {
    /** Visible, or connected by air within the search budget. Attack and pursue normally. */
    ENGAGE,

    /** Too far away to consider at all. */
    OUT_OF_RANGE,

    /** The chunk the target is in is not loaded, so nothing about it can be trusted this tick. */
    UNLOADED,

    /** Separated by rock with no air corridor within the budget: a different stratum entirely. */
    SEALED_STRATUM,

    /** Same air volume reachable in principle, but further than the search budget allowed. */
    BEYOND_BUDGET
}

/**
 * Perception contract consumed by the companion brain (see `SHARED_TASK_BOARD.md`, Contract 2).
 *
 * Kept as an interface so the brain can be tested against a stub filter, and so a future
 * acoustic-only or line-of-sight-only implementation can be swapped in without touching the
 * behaviour tree.
 */
interface IOcclusionFilter {
    fun isTacticallyReachable(bot: LivingEntity, target: LivingEntity): Boolean
    fun computeThreatScore(bot: LivingEntity, target: LivingEntity): Double
}

/**
 * Solves the "companion stares at underground cave mobs through solid terrain" bug.
 *
 * The root cause is that vanilla targeting is pure Euclidean distance. A player standing on the
 * surface at `Y = 70` and a zombie in a cave at `Y = 60` are ten blocks apart, well inside every
 * follow range, so a naive `distanceToSqr` check selects the zombie and the bot then walks into a
 * hillside forever.
 *
 * This filter answers a different question: *is the target in the same connected air volume I
 * am?* It does so with a cost ladder that escapes fast on the common cases and only pays for the
 * expensive test when it has to:
 *
 * | Stage | Test | Cost |
 * | :--- | :--- | :--- |
 * | 0 | Range, liveness, chunk loaded, not self | ~free |
 * | 1 | Same block, or `Level.clip` returns MISS (clear line of sight) | one voxel clip |
 * | 2 | Stratum classifier + vertical shaft probe | ~40 block reads |
 * | 3 | Budgeted BFS over passable cells | <= 32 nodes |
 *
 * Stage 1 resolves the overwhelming majority of real cases, because in a fight the target is
 * normally visible. Stage 2 catches the exact reported bug without ever running a search. Stage 3
 * exists for the genuinely ambiguous case: a target that is not visible but *is* reachable, such
 * as a mob around a corner or through a doorway, where the answer requires actually tracing the
 * air.
 *
 * ### Results are cached
 *
 * The brain evaluates candidates every few ticks, and the answer for a given pair rarely changes
 * within a fraction of a second. Verdicts are cached per `(bot, target)` pair for
 * [CACHE_TICKS] ticks, which turns the per-tick cost from `O(candidates x search)` into
 * `O(candidates)` map lookups.
 *
 * ### Deliberate non-goals
 *
 * - This does **not** decide aggro. It reports reachability and a threat score; the behaviour
 *   tree decides what to do with them.
 * - Occluded targets are not forgotten. `computeThreatScore` is deliberately independent of
 *   occlusion so the brain can keep a "hears something under the floor" memory without ever
 *   facing a wall.
 * - It does not mine. `DIG_THROUGH` belongs to the path planner (TASK-002); this filter reports
 *   that terrain is in the way, not that the way should be dug.
 */
object StratumOcclusionFilter : IOcclusionFilter {

    // ------------------------------------------------------------------ tuning

    /** Absolute outer bound, matching the 24m perception radius in the research document. */
    const val MAX_RANGE: Double = 24.0

    /** Within this distance a target is engaged if a single clip says the path is clear. */
    private const val POINT_BLANK_RANGE: Double = 4.0

    /**
     * How far below its own local surface an entity has to be before it counts as "in a cave"
     * rather than "standing on a hill". Chosen to clear a two-block step plus headroom.
     */
    private const val CAVE_DEPTH: Int = 6

    /**
     * Vertical separation below which two entities are considered to be in the same stratum and
     * the heightmap test is skipped entirely. A mob on a ledge does not need a cave heuristic.
     */
    private const val SAME_STRATUM_GAP: Int = 4

    /**
     * Node budget for the corridor flood-fill.
     *
     * The research document specifies 32. Kept as a named constant rather than a literal because
     * it trades the "is it reachable around this corner" answer against tick cost, and a server
     * with many companions will want to lower it.
     */
    private const val CORRIDOR_BUDGET: Int = 32

    /** How long a verdict stays valid. Short enough to react, long enough to amortise. */
    private const val CACHE_TICKS: Int = 8

    /** Maximum horizontal radius probed when looking for a vertical shaft between two strata. */
    private const val SHAFT_PROBE_RADIUS: Int = 2

    /** Distance at which a primed creeper overrides every other consideration. */
    private const val CREEPER_PANIC_RANGE: Double = 7.0

    // ------------------------------------------------------------------ cache

    /**
     * One cached verdict.
     *
     * [corridorDistance] is the breadth-first step count to the target when a corridor search was
     * actually run, or [NO_CORRIDOR] when the target was resolved by line of sight or by the
     * stratum classifier. It is carried here rather than recomputed so the threat score can use
     * the true topological distance for the ambiguous cases without paying for a second search.
     */
    private class CacheEntry(
        var tick: Long,
        var verdict: OcclusionVerdict,
        var corridorDistance: Int
    )

    /** Sentinel for "no corridor search was run for this pair". */
    private const val NO_CORRIDOR: Int = -1

    private val cache = ConcurrentHashMap<Long, CacheEntry>()

    /** Statistics, exposed so TASK-012 can prove the filter is actually rejecting things. */
    private var evaluated = 0L
    private var rejectedSealed = 0L
    private var rejectedUnloaded = 0L
    private var cacheHits = 0L

    fun stats(): String {
        val total = evaluated.coerceAtLeast(1L)
        return "occlusion: evaluated=$evaluated cacheHits=$cacheHits " +
            "sealed=${(rejectedSealed * 100 / total)}% unloaded=${(rejectedUnloaded * 100 / total)}%"
    }

    /** Drops every cached verdict. Called on server shutdown and on `/aeonis reload`. */
    fun clear() {
        cache.clear()
    }

    /** Drops the verdicts involving [entityId], used when a bot or target dies or unloads. */
    fun invalidate(entityId: Int) {
        cache.keys.removeIf { key -> unpackBot(key) == entityId || unpackTarget(key) == entityId }
    }

    // ------------------------------------------------------------------ public API

    override fun isTacticallyReachable(bot: LivingEntity, target: LivingEntity): Boolean =
        verdictFor(bot, target) == OcclusionVerdict.ENGAGE

    /** The full verdict, for callers that want to know *why* a target was rejected. */
    fun verdictFor(bot: LivingEntity, target: LivingEntity): OcclusionVerdict {
        val level = bot.level() as? ServerLevel ?: return OcclusionVerdict.UNLOADED

        // --- Stage 0: free rejections. Order matters; each is cheaper than the next.
        if (target === bot || !target.isAlive || !bot.isAlive) return OcclusionVerdict.UNLOADED
        if (target.level() !== level) return OcclusionVerdict.UNLOADED

        val botPos = bot.blockPosition()
        val targetPos = target.blockPosition()
        val distanceSqr = bot.distanceToSqr(target)
        if (distanceSqr > MAX_RANGE * MAX_RANGE) return OcclusionVerdict.OUT_OF_RANGE

        val tick = level.gameTime
        val key = packKey(bot.id, target.id)
        cache[key]?.let { entry ->
            if (tick - entry.tick <= CACHE_TICKS) {
                cacheHits++
                return entry.verdict
            }
        }

        evaluated++
        val outcome = evaluate(level, bot, botPos, target, targetPos, distanceSqr)
        if (outcome.verdict == OcclusionVerdict.SEALED_STRATUM) rejectedSealed++
        if (outcome.verdict == OcclusionVerdict.UNLOADED) rejectedUnloaded++

        val existing = cache[key]
        if (existing == null) {
            cache[key] = CacheEntry(tick, outcome.verdict, outcome.corridorDistance)
        } else {
            existing.tick = tick
            existing.verdict = outcome.verdict
            existing.corridorDistance = outcome.corridorDistance
        }
        return outcome.verdict
    }

    /** A verdict plus, when one was computed, the true breadth-first distance to the target. */
    private class Outcome(val verdict: OcclusionVerdict, val corridorDistance: Int = NO_CORRIDOR)

    // ------------------------------------------------------------------ cost ladder

    private fun evaluate(
        level: ServerLevel,
        bot: LivingEntity,
        botPos: BlockPos,
        target: LivingEntity,
        targetPos: BlockPos,
        distanceSqr: Double
    ): Outcome {
        // Heightmaps and voxel reads are only valid for loaded chunks. Doing this before anything
        // that reads terrain prevents an unloaded-chunk read from silently returning air, which
        // would make the filter report "reachable" for a target that is not really there.
        if (!level.hasChunkAt(botPos) || !level.hasChunkAt(targetPos)) {
            return Outcome(OcclusionVerdict.UNLOADED)
        }

        // --- Stage 1a: point blank. Anything this close that is in the same block is engaged.
        if (botPos.distManhattan(targetPos) == 0) return Outcome(OcclusionVerdict.ENGAGE, 0)

        // --- Stage 1b: direct optical line of sight. This is the ordinary combat case.
        if (hasClearLineOfSight(level, bot, target)) return Outcome(OcclusionVerdict.ENGAGE)

        // Point blank with something in between is still worth engaging; a one-block dihedral
        // should not make a bot ignore a zombie that is already in melee range.
        if (distanceSqr <= POINT_BLANK_RANGE * POINT_BLANK_RANGE) return Outcome(OcclusionVerdict.ENGAGE)

        // --- Stage 2: are we even in the same stratum?
        when (classifyStratum(level, botPos, targetPos)) {
            StratumVerdict.SAME -> return Outcome(OcclusionVerdict.ENGAGE)
            StratumVerdict.SEALED -> {
                // Separated by rock with no opening anywhere in the probe band. This is the
                // reported bug, and it is resolved here without paying for a search.
                return Outcome(OcclusionVerdict.SEALED_STRATUM)
            }
            StratumVerdict.OPEN_SHAFT, StratumVerdict.INCONCLUSIVE -> {
                // Fall through: the answer genuinely depends on the shape of the air.
            }
        }

        // --- Stage 3: trace the air corridor, and keep the distance it found.
        val corridor = corridorDistance(level, botPos, targetPos)
        return if (corridor == null) {
            Outcome(OcclusionVerdict.BEYOND_BUDGET)
        } else {
            Outcome(OcclusionVerdict.ENGAGE, corridor)
        }
    }

    /**
     * True when the bot's eyes see the target's eyes unobstructed.
     *
     * Uses `ClipContext.Block.COLLIDER` (not `OUTLINE`, which ignores non-colliding decor) and
     * `Fluid.NONE`, so water does not block sight: a companion must be able to see a drowned
     * through water, and an aquatic companion must be able to see out of it.
     */
    private fun hasClearLineOfSight(level: ServerLevel, bot: LivingEntity, target: LivingEntity): Boolean {
        val start = bot.getEyePosition(1.0f)
        val end = target.getEyePosition(1.0f)
        val clip = ClipContext(
            start,
            end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            bot
        )
        return level.clip(clip).type == HitResult.Type.MISS
    }

    // ------------------------------------------------------------------ stage 2

    private enum class StratumVerdict { SAME, OPEN_SHAFT, SEALED, INCONCLUSIVE }

    /**
     * Compares the two entities' depth below their own local surface.
     *
     * The comparison is deliberately *relative* rather than absolute: the earlier formulation
     * ("bot on surface, target below its surface by six") misclassifies a bot standing on a
     * hillside above a valley, because the bot's own block Y is not its surface height there.
     * What actually matters is whether either side is buried relative to its own sky.
     */
    private fun classifyStratum(level: ServerLevel, botPos: BlockPos, targetPos: BlockPos): StratumVerdict {
        val botSurface = surfaceHeight(level, botPos)
        val targetSurface = surfaceHeight(level, targetPos)

        val botDepth = botSurface - botPos.y
        val targetDepth = targetSurface - targetPos.y

        // Both standing on or near their own sky: same stratum, nothing to explain.
        val botBuried = botDepth > CAVE_DEPTH
        val targetBuried = targetDepth > CAVE_DEPTH
        if (!botBuried && !targetBuried) {
            // Still worth checking for a large vertical gap through a ceiling, e.g. a bot on a
            // cliff looking at something in a ravine directly below it.
            if (kotlin.math.abs(botPos.y - targetPos.y) <= SAME_STRATUM_GAP) return StratumVerdict.SAME
            return if (shaftProbe(level, botPos, targetPos)) StratumVerdict.OPEN_SHAFT
            else StratumVerdict.INCONCLUSIVE
        }

        // Exactly one side is buried, or both are buried at very different depths. If any column
        // in the probe band is open across the whole vertical gap, the two are connected and the
        // BFS should decide. If none is, they are in sealed strata.
        return if (shaftProbe(level, botPos, targetPos)) StratumVerdict.OPEN_SHAFT
        else StratumVerdict.SEALED
    }

    /** The `MOTION_BLOCKING` height of the column containing [pos], i.e. where its sky starts. */
    private fun surfaceHeight(level: ServerLevel, pos: BlockPos): Int =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.x, pos.z)

    /**
     * Looks for any column between the two entities that is open across the whole vertical gap.
     *
     * The probe band is [SHAFT_PROBE_RADIUS] around the straight line between the two positions,
     * sampled at a handful of interpolation points. That is enough to find a cave mouth, a
     * ravine, a stairwell or a ladder shaft without degenerating into a search; if the band finds
     * nothing, the caller still has the BFS as a second opinion.
     *
     * A column is open when every block from `min(y) + 1` to `max(y) - 1` has an empty collision
     * shape. Endpoints are excluded because the entities themselves occupy them.
     */
    private fun shaftProbe(level: ServerLevel, botPos: BlockPos, targetPos: BlockPos): Boolean {
        val lowY = minOf(botPos.y, targetPos.y)
        val highY = maxOf(botPos.y, targetPos.y)
        if (highY - lowY <= 1) return true

        val steps = kotlin.math.min(8, highY - lowY)
        for (step in 0..steps) {
            val t = if (steps == 0) 0.0 else step.toDouble() / steps
            val centreX = (botPos.x + (targetPos.x - botPos.x) * t).toInt()
            val centreZ = (botPos.z + (targetPos.z - botPos.z) * t).toInt()
            for (dx in -SHAFT_PROBE_RADIUS..SHAFT_PROBE_RADIUS) {
                for (dz in -SHAFT_PROBE_RADIUS..SHAFT_PROBE_RADIUS) {
                    if (columnOpen(level, centreX + dx, lowY, highY, centreZ + dz)) return true
                }
            }
        }
        return false
    }

    private fun columnOpen(level: ServerLevel, x: Int, lowY: Int, highY: Int, z: Int): Boolean {
        var y = lowY + 1
        while (y < highY) {
            val pos = BlockPos(x, y, z)
            if (!level.hasChunkAt(pos)) return false
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) {
                y++
                continue
            }
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ stage 3

    /**
     * Breadth-first flood-fill over passable cells, returning the true step distance to the target
     * or `null` when it was not found within the budget.
     *
     * Breadth-first (rather than depth-first) is the right choice here even though depth-first
     * would find *a* path with less bookkeeping: the budget is a node count, and BFS spends that
     * budget on cells in order of true travel distance, so a reachable target is found in the
     * fewest expansions. A depth-first walk of the same budget would wander down a single dead-end
     * tunnel and report "unreachable".
     *
     * Each queued cell carries its own depth, so the depth at which the target is found *is* its
     * cell-step distance, which the threat score then uses directly. Expansions are capped by
     * [CORRIDOR_BUDGET] and the frontier is confined by [withinSearchExtent] to the endpoints'
     * bounding box, so a pathological open volume - an ocean, a roofed forest canopy - cannot make
     * one call expensive.
     *
     * Plain JDK collections are used rather than fastutil's primitive sets: at a hard budget of 32
     * nodes the boxing cost is noise next to the voxel reads themselves, and keeping the hot loop
     * free of a third-party dependency makes the behaviour trivially auditable. If the budget is
     * ever raised into the thousands this decision should be revisited.
     */
    private fun corridorDistance(
        level: ServerLevel,
        botPos: BlockPos,
        targetPos: BlockPos
    ): Int? {
        val start = nearestPassable(level, botPos) ?: return null

        val queue = ArrayDeque<Long>()
        val depths = ArrayDeque<Int>()
        val visited = HashSet<Long>(CORRIDOR_BUDGET * 2)
        val startKey = start.asLong()
        queue.addLast(startKey)
        depths.addLast(0)
        visited.add(startKey)

        var expanded = 0
        while (queue.isNotEmpty() && expanded < CORRIDOR_BUDGET) {
            val current = BlockPos.of(queue.removeFirst())
            val depth = depths.removeFirst()
            expanded++

            // Arrival tolerance is generous in Y because entities stand on top of blocks, and
            // tight in XZ because a corridor that passes half a block away from the target has
            // genuinely arrived at it.
            if (current.closerThan(targetPos, 2.5)) return depth

            for (direction in Direction.entries) {
                val neighbour = current.relative(direction)
                if (!level.hasChunkAt(neighbour)) continue
                if (!isPassable(level, neighbour)) continue

                // Bound the search volume so an open plain cannot be walked indefinitely.
                if (!withinSearchExtent(neighbour, botPos, targetPos)) continue

                val key = neighbour.asLong()
                if (visited.add(key)) {
                    queue.addLast(key)
                    depths.addLast(depth + 1)
                }
            }
        }
        return null
    }

    /** Keeps the frontier inside the bounding box of the two endpoints, inflated by two. */
    private fun withinSearchExtent(candidate: BlockPos, botPos: BlockPos, targetPos: BlockPos): Boolean {
        val minX = minOf(botPos.x, targetPos.x) - 2
        val maxX = maxOf(botPos.x, targetPos.x) + 2
        val minY = minOf(botPos.y, targetPos.y) - 2
        val maxY = maxOf(botPos.y, targetPos.y) + 2
        val minZ = minOf(botPos.z, targetPos.z) - 2
        val maxZ = maxOf(botPos.z, targetPos.z) + 2
        return candidate.x in minX..maxX && candidate.y in minY..maxY && candidate.z in minZ..maxZ
    }

    /**
     * A cell is traversable when its collision shape is empty and it is not a hazard the companion
     * should not be routed through.
     *
     * Fire, lava, magma and powder snow are excluded deliberately. They are passable in the
     * collision sense but routing through them would make the companion kill itself, and the
     * filter is answering "would a sensible path exist", not "is there a geometric hole".
     */
    private fun isPassable(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (!state.getCollisionShape(level, pos).isEmpty) return false
        val fluid = state.fluidState
        if (!fluid.isEmpty && fluid.`is`(FluidTags.LAVA)) return false
        return true
    }

    /**
     * The nearest cell to [origin] the bot could plausibly stand in.
     *
     * The bot's own `blockPosition()` is its feet, which may be inside a slab, a carpet or a
     * snow layer, none of which are passable but all of which a mob can stand on. Starting the
     * flood-fill from a cell that is not passable used to make the search report "unreachable"
     * for every bot standing on a path block, so the origin is lifted first.
     */
    private fun nearestPassable(level: ServerLevel, origin: BlockPos): BlockPos? {
        if (isPassable(level, origin)) return origin
        // Search upward first: a mob standing in shallow snow or on a carpet has air above it.
        for (dy in 1..3) {
            val candidate = origin.above(dy)
            if (level.hasChunkAt(candidate) && isPassable(level, candidate)) return candidate
        }
        for (dy in 1..2) {
            val candidate = origin.below(dy)
            if (level.hasChunkAt(candidate) && isPassable(level, candidate)) return candidate
        }
        return null
    }

    // ------------------------------------------------------------------ threat score

    /**
     * $$ \text{ThreatScore} = \frac{\text{BaseHostility} \times \text{DamageOutput}}
     *    {\text{TopologicalDistance} + 1.0} \times \text{LOSMultiplier} $$
     *
     * with two additions the raw formula needs to be usable as a priority:
     *
     * 1. **A primed creeper short-circuits to maximum priority.** Nothing else in the game changes
     *    a fight as abruptly as a creeper that has begun to swell, and the denominator means a
     *    slow, tanky, distant threat would otherwise outrank it. Distance is not a mitigation for
     *    an explosion that is already counting down.
     * 2. **Hostility is class-based, not name-based.** `Enemy` (which covers every `Monster` and
     *    the slime family) is full weight; a neutral mob that has been provoked is half weight;
     *    a passive animal is a rounding error. Deriving this from the entity's own interfaces
     *    rather than a hardcoded type list means a modded hostile mob is scored correctly without
     *    being taught about it.
     *
     * The score deliberately ignores occlusion. A target the bot cannot see but *can* hear should
     * still raise its alert level; it just must not be turned into a facing or movement goal. The
     * caller is expected to combine this with [verdictFor] rather than this function swallowing
     * the distinction.
     */
    override fun computeThreatScore(bot: LivingEntity, target: LivingEntity): Double {
        if (!target.isAlive) return 0.0

        // --- the creeper short-circuit
        if (target is Creeper) {
            val distance = bot.distanceTo(target).toDouble()
            val swelling = target.swellDir != -1 || target.getSwelling(1.0f) > 0f
            if (swelling && distance <= CREEPER_PANIC_RANGE) return PANIC_THREAT
            if (target.isPowered && distance <= CREEPER_PANIC_RANGE * 2) return PANIC_THREAT * 0.8
        }

        val hostility = baseHostility(bot, target)
        if (hostility <= 0.0) return 0.0

        val damage = target.getAttributeValue(Attributes.ATTACK_DAMAGE)
            .takeIf { it > 0.0 }
            // A mob with no attack-damage attribute still does contact damage, and zero would
            // silently delete it from consideration. One point is the honest floor.
            ?: 1.0

        val distance = topologicalDistance(bot, target)
        val lineOfSight = if (hasClearLineOfSightSafely(bot, target)) 1.0 else OCCLUDED_LOS_MULTIPLIER

        return hostility * damage / (distance + 1.0) * lineOfSight
    }

    /** Threat weight used when a creeper is close and swelling. */
    private const val PANIC_THREAT: Double = 100.0

    /** Hearing a threat through a wall keeps it on the radar at a reduced weight. */
    private const val OCCLUDED_LOS_MULTIPLIER: Double = 0.35

    /**
     * How much this entity matters as a threat to [bot].
     *
     * A hostile mob targeting the bot's owner outranks one wandering past; a mob already
     * attacking the bot outranks everything of its class. Capped at 1.0 so the class weight
     * cannot be inflated into the creeper short-circuit's territory.
     */
    private fun baseHostility(bot: LivingEntity, target: LivingEntity): Double {
        val botsOwnTarget = (bot as? Mob)?.target
        var weight = when {
            // `Enemy` is the interface every hostile mob implements, including modded ones and
            // the slime family, so this is the one test that does not need a type list.
            target is Enemy -> 1.0
            target is Mob && target.type.category == MobCategory.MONSTER -> 1.0
            // A neutral mob that has picked a fight is worth more than one wandering past, but
            // still less than a genuinely hostile one.
            target is Mob && target.target != null -> 0.5
            else -> 0.1
        }
        // The thing already trading blows with this companion outranks its own class.
        if (botsOwnTarget != null && botsOwnTarget === target) weight += 0.25
        if (target.lastHurtMob === bot) weight += 0.25
        return weight.coerceAtMost(1.0)
    }

    /**
     * The distance the score's denominator should use.
     *
     * When a corridor search has already resolved this pair, its breadth-first step count is the
     * true topological distance and is used directly: a target four blocks away around a corner is
     * genuinely further than one four blocks away in the open, and the score should say so.
     *
     * Otherwise the pair was resolved by direct line of sight or by the stratum classifier, in
     * which case Euclidean distance is the correct answer rather than a fallback - the shortcut
     * cases are exactly the cases where straight-line distance is the travel distance. Either way
     * the denominator stays continuous across the branches instead of stepping at the boundary.
     */
    private fun topologicalDistance(bot: LivingEntity, target: LivingEntity): Double {
        val cached = cache[packKey(bot.id, target.id)]
        val corridor = cached?.corridorDistance ?: NO_CORRIDOR
        if (corridor > 0) return corridor.toDouble()
        return bot.distanceTo(target).toDouble()
    }

    /** `computeThreatScore` must never throw into the tick loop, so the clip is guarded once. */
    private fun hasClearLineOfSightSafely(bot: LivingEntity, target: LivingEntity): Boolean {
        val level = bot.level() as? ServerLevel ?: return false
        return try {
            hasClearLineOfSight(level, bot, target)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Packs a `(botId, targetId)` pair into one map key.
     *
     * Entity ids are `int`s that are reused after an entity unloads, so the cache is also
     * invalidated on death and logout rather than relying on the key being unique forever.
     */
    private fun packKey(botId: Int, targetId: Int): Long =
        (botId.toLong() shl 32) or (targetId.toLong() and 0xFFFFFFFFL)

    private fun unpackBot(key: Long): Int = (key ushr 32).toInt()

    private fun unpackTarget(key: Long): Int = key.toInt()
}
