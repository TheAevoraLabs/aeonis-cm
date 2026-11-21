package com.qc.aeonis.companion.skill

import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Items
import net.minecraft.world.level.LightLayer

/**
 * Traversal and lighting assistance: the companion keeps its own route passable and lights
 * the way, without being asked.
 *
 * All *engineering* — pillaring, bridging, tunnelling, scavenging material — is delegated to
 * [ScaffoldingEngine]; this class owns only the decision of **when** to do it and the torch
 * placement that is not traversal. Keeping the boundary sharp matters because the progression
 * loop needs the same prefix/suffix logic, and two copies of "am I standing over a chasm"
 * would drift.
 *
 * The triggers are deliberately narrow. An earlier version fired on "the block ahead is not
 * air", which is true for most of a forest, so the bot spent its life headbutting trees
 * instead of following its path.
 *
 * ### Pathfinding first, building second
 * The rule the user gave is *"try advanced pathfinding first, and only build the reclaimable
 * stair if the target is unreachable"*, and that is now literally the gate: every engineering
 * action asks [ScaffoldingEngine.engineeringRequiredFor] first, which runs an A* probe with
 * digging and building disabled. If ordinary movement can reach the waypoint, this skill declines
 * and lets the path do its job — so a one-block gully the companion can sprint-jump is jumped
 * rather than bridged, and a two-block ledge it can step up is stepped up rather than pillared.
 * Blocks only appear when the world itself is in the way.
 *
 * Anything this skill does build is reclaimable: see [ScaffoldReclaimSkill], which packs the
 * stair back up once the companion is where it was going.
 */
class BuildingSupportSkill : CompanionSkill {

    override val id: String = "building_support"
    override val name: String = "Building & Traversal Support"
    override val category: SkillCategory = SkillCategory.BUILDING
    override val priority: Int = 75

    /** Ticks between torch placements; torches are cheap but not free. */
    private val torchCooldownTicks = 60

    private var torchCooldown: Int = 0

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        if (torchCooldown > 0) {
            torchCooldown--
            return false
        }
        val level = bot.level() as? ServerLevel ?: return false
        val engine = bot.scaffoldingEngine

        // 1. A dark room the companion is standing in.
        if (level.getBrightness(LightLayer.BLOCK, bot.blockPosition()) < 7 && hasTorches(bot)) return true

        val waypoint = currentWaypoint(bot) ?: return false

        // 2. Pathfinding first. Anything reachable on foot is *not* this skill's business, which
        //    is the fix for stairs appearing where a player would simply have walked around.
        if (!engine.engineeringRequiredFor(waypoint)) return false

        // 3. The world is genuinely in the way: a chasm to bridge or a cliff to climb.
        val facing = facingOf(bot)
        if (bot.deltaMovement.horizontalDistanceSqr() > 0.01 && engine.chasmAhead(facing) > 0) return true
        if (engine.requiresAscent(waypoint) && engine.hasScaffoldBlock()) return true

        // 4. Reachable only by tunnelling through something.
        val blocker = blockingAhead(bot, level, waypoint)
        return blocker != null && engine.hasCorrectToolFor(blocker)
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val level = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        val engine = bot.scaffoldingEngine
        val facing = facingOf(bot)

        // 1. Lighting first: it is free in material terms and helps every later step.
        if (level.getBrightness(LightLayer.BLOCK, bot.blockPosition()) < 7 && hasTorches(bot)) {
            val placed = placeTorch(bot, level)
            if (placed) {
                torchCooldown = torchCooldownTicks
                return SkillResult(SkillStatus.SUCCESS, "Placed a torch to light the area", cooldownTicks = torchCooldownTicks)
            }
        }

        // 2. Re-assert the rule inside tick(), not just canActivate(): between the two calls the
        //    planner may have found a route, and building on a stale decision is exactly the
        //    wasted-material behaviour this skill is supposed to prevent.
        val waypoint = currentWaypoint(bot)
        if (waypoint == null || !engine.engineeringRequiredFor(waypoint)) {
            return SkillResult(SkillStatus.IDLE, "No engineering needed — the route is walkable", cooldownTicks = 10)
        }

        // 3. Chasm ahead: bridge it.
        val chasmWidth = engine.chasmAhead(facing)
        if (chasmWidth > 0) {
            val bridge = engine.bridgeForward(facing)
            if (bridge.isFailure) {
                // Out of material: dig some up rather than standing at the edge doing nothing.
                val scavenge = engine.scavengeScaffoldBlocks()
                return scavenge.toSkillResult("Bridging needs material")
            }
            return bridge.toSkillResult("Bridging a ${chasmWidth}-block gap")
        }

        // 4. Cliff between the companion and its next waypoint: build the stair up.
        if (engine.requiresAscent(waypoint)) {
            val ascent = engine.ascendTowards(waypoint)
            if (ascent.isFailure) {
                val scavenge = engine.scavengeScaffoldBlocks()
                return scavenge.toSkillResult("Climbing needs material")
            }
            if (ascent.isSuccess) {
                bot.navigation.recomputePath()
                return SkillResult(SkillStatus.SUCCESS, "Reached the higher level", cooldownTicks = 5)
            }
            return ascent.toSkillResult("Building a stair toward the waypoint")
        }

        // 5. The route exists but only through a block: mine it, guarded, and let the planner
        //    replan across the new opening.
        val blocker = blockingAhead(bot, level, waypoint)
        if (blocker != null) {
            val cleared = engine.clearObstacle(blocker)
            if (cleared.isSuccess) {
                bot.navigation.recomputePath()
                return SkillResult(SkillStatus.SUCCESS, "Opened the route through ${blocker.toShortString()}", cooldownTicks = 5)
            }
            return cleared.toSkillResult("Clearing the route")
        }

        return SkillResult.IDLE
    }

    /** The waypoint the companion is currently travelling toward, or null. */
    private fun currentWaypoint(bot: CompanionBotEntity): BlockPos? {
        val board = bot.brainTree?.blackboard ?: return null
        val path = board.currentPath ?: return null
        return path.getOrNull(board.currentPathIndex)?.pos
    }

    /**
     * First non-air block in the straight line toward [waypoint], within a short reach.
     *
     * Only a handful of cells are sampled: this is a *target* for the mining step, not a
     * pathfinding pass. The planner is what decides whether the route is possible; this only
     * answers "what is immediately in the way, if anything".
     */
    private fun blockingAhead(bot: CompanionBotEntity, level: ServerLevel, waypoint: BlockPos): BlockPos? {
        val step = Direction.getApproximateNearest(
            (waypoint.x - bot.blockPosition().x).toDouble(),
            0.0,
            (waypoint.z - bot.blockPosition().z).toDouble()
        )
        if (step.axis.isVertical) return null
        val ahead = bot.blockPosition().relative(step)
        for (dy in intArrayOf(0, 1)) {
            val cell = ahead.above(dy)
            val state = level.getBlockState(cell)
            if (!state.isAir && !state.canBeReplaced() && state.getDestroySpeed(level, cell) >= 0f) return cell
        }
        return null
    }

    override fun stop(bot: CompanionBotEntity) {
        bot.scaffoldingEngine.cancelAscent()
    }

    /** Heading the companion is travelling; falls back to body rotation when standing still. */
    private fun facingOf(bot: CompanionBotEntity): Direction {
        val velocity = bot.deltaMovement
        if (velocity.horizontalDistanceSqr() > 1.0e-4) {
            return Direction.getApproximateNearest(velocity.x, 0.0, velocity.z)
        }
        return Direction.fromYRot(bot.yRot.toDouble())
    }

    fun hasTorches(bot: CompanionBotEntity): Boolean =
        bot.firstSlotMatching { it.`is`(Items.TORCH) } >= 0

    /**
     * Places a torch at the companion's feet, chosen so it lands on solid floor.
     */
    private fun placeTorch(bot: CompanionBotEntity, level: ServerLevel): Boolean {
        val slot = bot.firstSlotMatching { it.`is`(Items.TORCH) }
        if (slot < 0) return false

        val feet = bot.blockPosition()
        if (!level.getBlockState(feet).isAir) {
            // Standing in the block we wanted to use; try the cell in front instead so a
            // torch still goes down rather than the attempt being silently dropped.
            val ahead = feet.relative(facingOf(bot))
            if (!level.getBlockState(ahead).isAir) return false
            return bot.placeFromSlot(ahead, slot, Direction.UP, torchCooldownTicks)
        }
        return bot.placeFromSlot(feet, slot, Direction.UP, torchCooldownTicks)
    }

    /**
     * Adapts an engine result to the skill protocol, prefixing the reason so the log reads
     * as a sentence about the traversal problem rather than a bare engine message.
     */
    private fun ScaffoldingEngine.ScaffoldResult.toSkillResult(prefix: String): SkillResult = when (status) {
        SkillStatus.SUCCESS -> SkillResult(SkillStatus.SUCCESS, "$prefix: $message", cooldownTicks = 5)
        SkillStatus.FAILURE -> SkillResult(SkillStatus.FAILURE, "$prefix: $message", cooldownTicks = 40)
        SkillStatus.RUNNING -> SkillResult(SkillStatus.RUNNING, "$prefix: $message", cooldownTicks = 0)
        SkillStatus.IDLE -> SkillResult.IDLE
    }
}
