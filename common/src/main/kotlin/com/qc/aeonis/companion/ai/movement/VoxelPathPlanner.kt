package com.qc.aeonis.companion.ai.movement

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

data class PathWaypoint(
    val pos: BlockPos,
    val action: MovementAction,
    val targetSpeed: Double
)

enum class MovementAction {
    WALK, SPRINT_JUMP, PARKOUR_GAP, FALL_SAFE, PILLAR_UP, BRIDGE_FORWARD, DIG_THROUGH
}

interface IVoxelPathPlanner {
    fun planPath(start: BlockPos, goal: BlockPos): List<PathWaypoint>?
    fun isReachable(start: BlockPos, goal: BlockPos, maxNodes: Int): Boolean
}

/**
 * High-performance 3D Voxel A* Pathplanner with dynamic movement primitives.
 *
 * Expands beyond 2D navmesh by evaluating kinetic parkour transitions:
 * - Walking, step-up (+1), step-down (-1)
 * - Safe vertical drops (<= 3 blocks, or deep water cushion)
 * - Sprint jumps across 2-block gaps
 * - Kinetic parkour across 3-block gaps
 * - Tactical bridging across chasms
 * - Tactical pillaring up walls
 * - Controlled tunneling / dig-through when equipped with mining tools
 */
class VoxelPathPlanner(
    val level: ServerLevel,
    val canDig: Boolean = true,
    val canBuild: Boolean = true,
    val maxSearchBudget: Int = 384
) : IVoxelPathPlanner {

    private class Node(
        val pos: BlockPos,
        var gCost: Double,
        val hCost: Double,
        val parent: Node?,
        val action: MovementAction,
        val speed: Double
    ) : Comparable<Node> {
        val fCost: Double get() = gCost + hCost

        override fun compareTo(other: Node): Int {
            val cmp = this.fCost.compareTo(other.fCost)
            return if (cmp != 0) cmp else this.hCost.compareTo(other.hCost)
        }
    }

    override fun isReachable(start: BlockPos, goal: BlockPos, maxNodes: Int): Boolean {
        return search(start, goal, maxNodes) != null
    }

    override fun planPath(start: BlockPos, goal: BlockPos): List<PathWaypoint>? {
        val targetNode = search(start, goal, maxSearchBudget) ?: return null
        return reconstructAndSmoothPath(targetNode)
    }

    private fun search(start: BlockPos, goal: BlockPos, budget: Int): Node? {
        val validStart = findStandableFloor(start) ?: return null
        val validGoal = findStandableFloor(goal) ?: goal

        if (validStart == validGoal) {
            return Node(validStart, 0.0, 0.0, null, MovementAction.WALK, 1.0)
        }

        val openQueue = PriorityQueue<Node>()
        val openSet = HashMap<Long, Node>()
        val closedSet = HashSet<Long>()

        val startNode = Node(validStart, 0.0, heuristic(validStart, validGoal), null, MovementAction.WALK, 1.0)
        openQueue.add(startNode)
        openSet[validStart.asLong()] = startNode

        var expansions = 0
        var closestNode: Node = startNode

        while (openQueue.isNotEmpty() && expansions < budget) {
            val current = openQueue.poll()
            val currentKey = current.pos.asLong()
            openSet.remove(currentKey)
            closedSet.add(currentKey)
            expansions++

            if (current.hCost < closestNode.hCost) {
                closestNode = current
            }

            // Goal arrival test (within 1.5 blocks)
            if (current.pos == validGoal || current.pos.closerThan(validGoal, 1.5)) {
                return current
            }

            // Expand transitions
            val successors = generateTransitions(current, validGoal)
            for (succ in successors) {
                val succKey = succ.pos.asLong()
                if (succKey in closedSet) continue

                val existing = openSet[succKey]
                if (existing == null) {
                    openSet[succKey] = succ
                    openQueue.add(succ)
                } else if (succ.gCost < existing.gCost) {
                    openQueue.remove(existing)
                    openSet[succKey] = succ
                    openQueue.add(succ)
                }
            }
        }

        // Return partial path if reasonably close (within 4 blocks of goal)
        return if (closestNode.hCost <= 4.0 && closestNode !== startNode) closestNode else null
    }

    private fun generateTransitions(current: Node, goal: BlockPos): List<Node> {
        val results = ArrayList<Node>(16)
        val pos = current.pos

        // 1. Cardinal and diagonal standard movements (dy: -1, 0, +1)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue

                val isDiagonal = dx != 0 && dz != 0
                val moveCost = if (isDiagonal) 1.414 else 1.0

                // Check diagonal corner clearance
                if (isDiagonal) {
                    val corner1 = pos.offset(dx, 0, 0)
                    val corner2 = pos.offset(0, 0, dz)
                    if (!isPassable(corner1) || !isPassable(corner2)) {
                        continue
                    }
                }

                // 1a. Flat walk (dy = 0)
                val flatCandidate = pos.offset(dx, 0, dz)
                if (isStandable(flatCandidate)) {
                    results.add(Node(flatCandidate, current.gCost + moveCost, heuristic(flatCandidate, goal), current, MovementAction.WALK, 1.0))
                } else {
                    // 1b. Step-up (dy = +1)
                    val stepUpCandidate = pos.offset(dx, 1, dz)
                    if (isPassable(pos.above(2)) && isStandable(stepUpCandidate)) {
                        results.add(Node(stepUpCandidate, current.gCost + moveCost + 0.5, heuristic(stepUpCandidate, goal), current, MovementAction.WALK, 1.0))
                    }

                    // 1c. Step-down (dy = -1)
                    val stepDownCandidate = pos.offset(dx, -1, dz)
                    if (isPassable(pos.offset(dx, 1, dz)) && isStandable(stepDownCandidate)) {
                        results.add(Node(stepDownCandidate, current.gCost + moveCost + 0.2, heuristic(stepDownCandidate, goal), current, MovementAction.WALK, 1.0))
                    }
                }

                // 2. Safe Drops / Falling (dy = -2 or -3)
                for (drop in 2..3) {
                    val dropCandidate = pos.offset(dx, -drop, dz)
                    if (isDropShaftClear(pos, dx, dz, drop) && isStandable(dropCandidate)) {
                        val fallCost = moveCost + drop * 0.4
                        results.add(Node(dropCandidate, current.gCost + fallCost, heuristic(dropCandidate, goal), current, MovementAction.FALL_SAFE, 1.1))
                        break
                    }
                }

                // Deep water plunge (safe from up to 10 blocks height)
                val waterPlungeCandidate = pos.offset(dx, -4, dz)
                if (isDeepWater(waterPlungeCandidate) && isDropShaftClear(pos, dx, dz, 4)) {
                    results.add(Node(waterPlungeCandidate, current.gCost + 3.0, heuristic(waterPlungeCandidate, goal), current, MovementAction.FALL_SAFE, 1.2))
                }
            }
        }

        // 3. Sprint Jump across 2-block gaps
        for (dir in Direction.entries) {
            if (dir.axis.isVertical) continue

            val gap1 = pos.relative(dir, 1)
            val landing2 = pos.relative(dir, 2)

            // Gap must be air with void/hazard below
            if (isPassable(gap1) && isPassable(gap1.above()) && !isGroundSolid(gap1)) {
                // Landing on same level or 1 block lower
                for (dy in 0 downTo -1) {
                    val target = landing2.above(dy)
                    if (isStandable(target) && isPassable(landing2.above(dy + 1))) {
                        results.add(Node(target, current.gCost + 2.2, heuristic(target, goal), current, MovementAction.SPRINT_JUMP, 1.3))
                        break
                    }
                }
            }

            // 4. Kinetic Parkour across 3-block gaps
            val gap2 = pos.relative(dir, 2)
            val landing3 = pos.relative(dir, 3)
            if (isPassable(gap1) && isPassable(gap2) && isPassable(gap1.above()) && isPassable(gap2.above()) &&
                !isGroundSolid(gap1) && !isGroundSolid(gap2)) {
                for (dy in 0 downTo -1) {
                    val target = landing3.above(dy)
                    if (isStandable(target) && isPassable(landing3.above(dy + 1))) {
                        results.add(Node(target, current.gCost + 3.2, heuristic(target, goal), current, MovementAction.PARKOUR_GAP, 1.4))
                        break
                    }
                }
            }

            // 5. Bridging Forward over Void/Chasm
            if (canBuild && isPassable(gap1) && !isGroundSolid(gap1)) {
                // Can place block under gap1 to step on it
                results.add(Node(gap1, current.gCost + 2.8, heuristic(gap1, goal), current, MovementAction.BRIDGE_FORWARD, 0.7))
            }

            // 6. Digging Through Wall
            if (canDig) {
                val frontFoot = pos.relative(dir)
                val frontHead = frontFoot.above()
                if (isBreakableBlock(frontFoot) || isBreakableBlock(frontHead)) {
                    results.add(Node(frontFoot, current.gCost + 4.5, heuristic(frontFoot, goal), current, MovementAction.DIG_THROUGH, 0.5))
                }
            }
        }

        // 7. Pillaring Up vertically
        if (canBuild) {
            val aboveFoot = pos.above(1)
            val aboveHead = pos.above(2)
            if (isPassable(aboveFoot) && isPassable(aboveHead)) {
                results.add(Node(aboveFoot, current.gCost + 3.0, heuristic(aboveFoot, goal), current, MovementAction.PILLAR_UP, 0.6))
            }
        }

        return results
    }

    private fun isDropShaftClear(start: BlockPos, dx: Int, dz: Int, drop: Int): Boolean {
        for (y in 0 until drop) {
            val p = start.offset(dx, -y, dz)
            if (!isPassable(p)) return false
        }
        return true
    }

    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        // Height differences cost slightly more to prioritize horizontal movement
        return sqrt(dx * dx + (dy * 1.5) * (dy * 1.5) + dz * dz)
    }

    private fun findStandableFloor(pos: BlockPos): BlockPos? {
        if (isStandable(pos)) return pos
        for (dy in 1..2) {
            val up = pos.above(dy)
            if (isStandable(up)) return up
        }
        for (dy in 1..4) {
            val down = pos.below(dy)
            if (isStandable(down)) return down
        }
        return null
    }

    fun isStandable(pos: BlockPos): Boolean {
        if (!level.hasChunkAt(pos)) return false
        // 1. Two blocks of head/body clearance
        if (!isPassable(pos) || !isPassable(pos.above())) return false

        // 2. Solid floor below feet, or water swim surface
        val below = pos.below()
        if (isHazard(pos) || isHazard(below)) return false

        return isGroundSolid(pos) || isWater(pos)
    }

    private fun isPassable(pos: BlockPos): Boolean {
        if (!level.hasChunkAt(pos)) return false
        val state = level.getBlockState(pos)
        if (!state.getCollisionShape(level, pos).isEmpty) return false
        return !isHazard(pos)
    }

    private fun isGroundSolid(pos: BlockPos): Boolean {
        val below = pos.below()
        if (!level.hasChunkAt(below)) return false
        val state = level.getBlockState(below)
        return !state.getCollisionShape(level, below).isEmpty
    }

    private fun isWater(pos: BlockPos): Boolean {
        val fluid = level.getFluidState(pos)
        return fluid.`is`(FluidTags.WATER)
    }

    private fun isDeepWater(pos: BlockPos): Boolean {
        val fluid = level.getFluidState(pos)
        val fluidBelow = level.getFluidState(pos.below())
        return fluid.`is`(FluidTags.WATER) && fluidBelow.`is`(FluidTags.WATER)
    }

    private fun isBreakableBlock(pos: BlockPos): Boolean {
        if (!level.hasChunkAt(pos)) return false
        val state = level.getBlockState(pos)
        val destroySpeed = state.getDestroySpeed(level, pos)
        return destroySpeed >= 0.0f && !state.isAir && !isHazard(pos)
    }

    private fun isHazard(pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        val block = state.block
        if (block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE ||
            block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE || block == Blocks.MAGMA_BLOCK ||
            block == Blocks.SWEET_BERRY_BUSH || block == Blocks.CACTUS || block == Blocks.POWDER_SNOW ||
            block == Blocks.WITHER_ROSE) {
            return true
        }
        val fluid = state.fluidState
        return !fluid.isEmpty && fluid.`is`(FluidTags.LAVA)
    }

    private fun reconstructAndSmoothPath(endNode: Node): List<PathWaypoint> {
        val rawWaypoints = ArrayDeque<PathWaypoint>()
        var curr: Node? = endNode

        while (curr != null) {
            rawWaypoints.addFirst(PathWaypoint(curr.pos, curr.action, curr.speed))
            curr = curr.parent
        }

        if (rawWaypoints.size <= 2) return rawWaypoints.toList()

        // Waypoint smoothing: collapse collinear / line-of-sight waypoints of identical action
        val smoothed = ArrayList<PathWaypoint>()
        val list = rawWaypoints.toList()
        var anchor = 0
        smoothed.add(list[0])

        var i = 1
        while (i < list.size) {
            val p0 = list[anchor].pos
            val p1 = list[i].pos

            val action = list[i].action
            // Special actions (parkour, jumping, building, digging) must not be skipped
            val canSkip = action == MovementAction.WALK && list[anchor].action == MovementAction.WALK

            if (canSkip && canWalkDirectly(p0, p1)) {
                // Can skip intermediate nodes
                i++
            } else {
                val targetIndex = (i - 1).coerceAtLeast(anchor + 1)
                smoothed.add(list[targetIndex])
                anchor = targetIndex
                i = anchor + 1
            }
        }

        if (smoothed.last().pos != list.last().pos) {
            smoothed.add(list.last())
        }

        return smoothed
    }

    private fun canWalkDirectly(from: BlockPos, to: BlockPos): Boolean {
        if (from.distManhattan(to) > 8) return false
        if (from.y != to.y) return false

        val dx = to.x - from.x
        val dz = to.z - from.z
        val steps = maxOf(abs(dx), abs(dz))
        if (steps == 0) return true

        for (step in 1..steps) {
            val t = step.toDouble() / steps
            val bx = (from.x + dx * t + 0.5).toInt()
            val bz = (from.z + dz * t + 0.5).toInt()
            val checkPos = BlockPos(bx, from.y, bz)
            if (!isStandable(checkPos)) return false
        }
        return true
    }
}
