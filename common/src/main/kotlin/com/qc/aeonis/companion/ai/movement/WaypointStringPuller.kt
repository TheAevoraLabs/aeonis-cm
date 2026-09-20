package com.qc.aeonis.companion.ai.movement

import net.minecraft.core.BlockPos

/**
 * Answers the one question string pulling needs: "can an entity at A walk to B in a
 * straight line?".
 *
 * A functional interface rather than a concrete class so the puller stays free of any
 * Minecraft world dependency and can be reasoned about (and tested) as pure geometry
 * over a boolean oracle. [VoxelPathPlanner] supplies the world-backed implementation.
 */
fun interface TraversalProbe {
    fun canTraverse(from: BlockPos, to: BlockPos): Boolean
}

/**
 * Greedy line-of-sight waypoint pruning ("string pulling") for A* output.
 *
 * ### The problem
 * A grid A* search emits a staircase. Asked to go two blocks north and two east it
 * returns (1,0) then (0,1) rather than one diagonal step, because those are the only two
 * edges its successor generator has. Driving an entity through that staircase produces
 * the visible zigzag the field report complained about — the bot wiggles along the
 * diagonal instead of walking it.
 *
 * ### The fix
 * After the search, repeatedly try to replace the run of waypoints between two anchors
 * with a single straight move. If the entity can walk the straight line, the
 * intermediate waypoints carried no information and are dropped.
 *
 * ### What may never be pruned
 * Waypoints whose action is anything other than [MovementAction.WALK] encode a *physical
 * interaction* the plain walk test cannot express: a sprint jump, a parkour gap, a safe
 * fall, a pillar, a bridge, a dig. Those are hard anchors — they can neither be removed
 * nor skipped over, because the action must be executed at that exact cell. A pruned link
 * may also never jump *across* a hard anchor, since arriving beyond it would silently drop
 * the action.
 *
 * The algorithm is O(n · lookahead) with each candidate link costing one probe call, and
 * it emits a strictly increasing subsequence of the input, so termination is immediate.
 */
object WaypointStringPuller {

    /**
     * Furthest link the puller will try, in Chebyshev blocks. Eight matches the planner's
     * own reach assumptions; larger values make each probe cost more without producing
     * noticeably straighter paths, because the probe's step tolerance already rejects
     * long links over uneven ground.
     */
    const val MAX_LOOKAHEAD = 8

    /**
     * Prunes [waypoints] in place-equivalent form, returning a shorter list that reaches
     * the same cells in the same order.
     *
     * Returns the input list unchanged when it is already minimal (two or fewer nodes, or
     * nothing prunable), so callers can cheaply detect "no change" by identity.
     */
    fun pull(
        waypoints: List<PathWaypoint>,
        probe: TraversalProbe,
        maxLookahead: Int = MAX_LOOKAHEAD
    ): List<PathWaypoint> {
        if (waypoints.size <= 2) return waypoints

        val result = ArrayList<PathWaypoint>(waypoints.size)
        result.add(waypoints[0])

        var anchor = 0
        val lastIndex = waypoints.size - 1

        while (anchor < lastIndex) {
            val limit = minOf(anchor + maxLookahead, lastIndex)
            var next = anchor + 1

            // Search the lookahead window from far to near so the first success is the
            // longest legal shortcut, which is what makes the path straight rather than
            // merely shorter.
            var candidate = limit
            while (candidate > anchor + 1) {
                if (!spansHardAnchor(waypoints, anchor, candidate) &&
                    probe.canTraverse(waypoints[anchor].pos, waypoints[candidate].pos)
                ) {
                    next = candidate
                    break
                }
                candidate--
            }

            result.add(waypoints[next])
            anchor = next
        }

        return if (result.size == waypoints.size) waypoints else result
    }

    /**
     * True when the open interval `(from, to)` contains a waypoint whose action cannot be
     * skipped, i.e. any non-WALK action. The endpoints themselves are excluded: the action
     * at `from` already ran when the bot arrived there, and the action at `to` runs on
     * arrival.
     */
    private fun spansHardAnchor(waypoints: List<PathWaypoint>, from: Int, to: Int): Boolean {
        for (index in (from + 1) until to) {
            if (waypoints[index].action != MovementAction.WALK) return true
        }
        return false
    }

    /** True when [action] forces the companion to stop at that cell and interact with it. */
    fun isHardAnchor(action: MovementAction): Boolean = action != MovementAction.WALK

    /**
     * Number of waypoints a path would have after pulling, without materialising it.
     * Used for diagnostics in `/comp stats`.
     */
    fun approximatePruningRatio(before: Int, after: Int): Double =
        if (before <= 0) 0.0 else 1.0 - (after.toDouble() / before.toDouble())
}
