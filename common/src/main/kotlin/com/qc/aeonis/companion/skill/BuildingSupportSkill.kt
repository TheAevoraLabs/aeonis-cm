package com.qc.aeonis.companion.skill

import com.qc.aeonis.entity.CompanionBotEntity
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
 * instead of following its path. Now it acts only on situations the path planner genuinely
 * cannot resolve on its own: a real chasm, a real cliff, or a genuinely dark room.
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
        val facing = facingOf(bot)

        // 1. A dark room the companion is standing in.
        if (level.getBrightness(LightLayer.BLOCK, bot.blockPosition()) < 7 && hasTorches(bot)) return true

        // 2. A real chasm directly ahead along the current heading.
        if (bot.deltaMovement.horizontalDistanceSqr() > 0.01 && engine.chasmAhead(facing) > 0) return true

        // 3. A cliff the destination sits on top of.
        val waypoint = bot.brainTree?.blackboard?.currentPath
            ?.getOrNull(bot.brainTree?.blackboard?.currentPathIndex ?: 0)
            ?.pos
        if (waypoint != null && engine.requiresAscent(waypoint) && engine.hasScaffoldBlock()) return true

        return false
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

        // 2. Chasm ahead: bridge it.
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

        // 3. Cliff between the companion and its next waypoint: pillar up.
        val waypoint = bot.brainTree?.blackboard?.currentPath
            ?.getOrNull(bot.brainTree?.blackboard?.currentPathIndex ?: 0)
            ?.pos
        if (waypoint != null && engine.requiresAscent(waypoint)) {
            val ascent = engine.ascendTowards(waypoint)
            if (ascent.isFailure) {
                val scavenge = engine.scavengeScaffoldBlocks()
                return scavenge.toSkillResult("Climbing needs material")
            }
            if (ascent.isSuccess) {
                bot.navigation.recomputePath()
                return SkillResult(SkillStatus.SUCCESS, "Reached the higher level", cooldownTicks = 5)
            }
            return ascent.toSkillResult("Pillaring toward the waypoint")
        }

        return SkillResult.IDLE
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
