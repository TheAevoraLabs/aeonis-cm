package com.qc.aeonis.companion.skill

import com.qc.aeonis.entity.CompanionBotEntity

/**
 * Closes the vertical gap between the companion and its owner.
 *
 * ### Why this is its own skill
 * The voxel path planner plans a route to a *standable cell*. An owner hovering in creative
 * flight has no standable cell in their column — the block under them is air — so the planner
 * finds no goal, [com.qc.aeonis.companion.ai.brain.CompanionBehaviorTree] falls back to the
 * vanilla navigator, and the vanilla navigator cannot fly. The result, reported verbatim: *"he
 * simply stands below and not at all trying to come near me … i am standing in air using
 * creative."*
 *
 * Reaching a target that has no ground beneath it is not a pathfinding problem, it is a
 * *construction* problem, and it has to be solved by counting blocks:
 *
 * ```
 * blocksNeeded = ownerFeetY - botFeetY
 * ```
 *
 * That count is what this skill acts on — walk under the owner, then build the stair up until
 * the count is zero — and it is also what it reports, so `/comp status` and the chat diagnostics
 * say "7 blocks to climb" rather than leaving the player to guess what the companion is doing.
 *
 * ### Why it outranks ordinary autonomy but is not an emergency
 * [priority] sits above hunting, gathering and building, because a companion that ignores its
 * owner to chase a cow while the owner watches from ten blocks up is exactly the behaviour that
 * prompted this. It stays below the bodyguard and emergency branches, which live in the behaviour
 * tree and are evaluated before skills, so nothing here can delay a defence.
 *
 * ### Bounds
 * The climb is capped at [MAX_CLIMB_BLOCKS] and the whole approach at [MAX_APPROACH_TICKS]. An
 * owner who flies two hundred blocks up gets a companion that tries reasonably hard and then
 * gets on with its life, rather than one that paves a tower to the build limit.
 */
class OwnerApproachSkill : CompanionSkill {

    override val id: String = "owner_approach"
    override val name: String = "Owner Approach (Vertical)"
    override val category: SkillCategory = SkillCategory.LOCOMOTION

    /** Above gathering and hunting; below the tree's bodyguard and emergency branches. */
    override val priority: Int = 88

    companion object {
        /**
         * Vertical gap, in blocks, at which the companion starts considering a climb.
         *
         * Two blocks is the shortest gap that ordinary movement cannot close: a one-block step is
         * handled by the entity's own step height and the planner's step-up transition, so acting
         * at one block would make the companion pillar onto every kerb.
         */
        const val VERTICAL_TRIGGER = 2

        /** The owner must be at least this close horizontally before a stair is worth building. */
        const val APPROACH_RANGE = 28.0

        /** Squared distance considered "already close enough" — the follow comfort radius. */
        const val CLOSE_ENOUGH_SQR = 9.0

        /** Height, in blocks, one approach will climb before giving up gracefully. */
        const val MAX_CLIMB_BLOCKS = 24

        /** Hard cap on how long one approach may run, in ticks (30 seconds). */
        const val MAX_APPROACH_TICKS = 600

        /** Horizontal distance within which the companion stops walking and starts building. */
        const val BUILDUNDER_RANGE = 2.2
    }

    private var approachStartedTick: Long = 0L

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        val owner = bot.getOwnerPlayer() ?: return false
        val gap = owner.blockPosition().y - bot.blockPosition().y
        if (gap < VERTICAL_TRIGGER) return false

        // Out of the follow comfort radius, or there is nothing to fix.
        if (bot.distanceToSqr(owner) <= CLOSE_ENOUGH_SQR) return false

        // Beyond sensible flight height, or absurdly elevated, the companion declines rather than
        // paving a tower: see the class comment on bounds.
        if (gap > MAX_CLIMB_BLOCKS * 2) return false

        val dx = bot.x - owner.x
        val dz = bot.z - owner.z
        return (dx * dx + dz * dz) <= APPROACH_RANGE * APPROACH_RANGE
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val level = bot.level() as? net.minecraft.server.level.ServerLevel ?: return SkillResult.FAILURE
        val owner = bot.getOwnerPlayer() ?: return SkillResult.FAILURE

        val now = level.gameTime
        if (approachStartedTick == 0L) approachStartedTick = now
        if (now - approachStartedTick > MAX_APPROACH_TICKS) {
            reset()
            return SkillResult(SkillStatus.FAILURE, "Gave up climbing to the owner — too long", cooldownTicks = 100)
        }

        val gap = owner.blockPosition().y - bot.blockPosition().y

        // Arrived (or close enough that a step will do).
        if (gap < VERTICAL_TRIGGER) {
            reset()
            return SkillResult(SkillStatus.SUCCESS, "Caught up with the owner", cooldownTicks = 5)
        }

        if (gap > MAX_CLIMB_BLOCKS) {
            reset()
            return SkillResult(
                SkillStatus.FAILURE,
                "The owner is $gap blocks up — more than I will build",
                cooldownTicks = 200
            )
        }

        // 1. Get under them first. Building a stair twenty blocks away from the owner is the
        //    failure this step exists to prevent.
        val dx = bot.x - owner.x
        val dz = bot.z - owner.z
        val horizontal = kotlin.math.sqrt(dx * dx + dz * dz)
        if (horizontal > BUILDUNDER_RANGE) {
            bot.navigation.moveTo(owner.x, bot.y, owner.z, 1.3)
            return SkillResult(
                SkillStatus.RUNNING,
                "Moving under the owner — $gap blocks to climb",
                cooldownTicks = 0
            )
        }

        // 2. Build the stair. The engine provisions its own material when it runs dry, so an
        //    owner who never hands the companion blocks still gets climbed to.
        val ascent = bot.scaffoldingEngine.ascendTowards(owner.blockPosition(), MAX_CLIMB_BLOCKS)
        return when (ascent.status) {
            SkillStatus.SUCCESS -> {
                bot.navigation.recomputePath()
                reset()
                SkillResult(SkillStatus.SUCCESS, "Reached the owner's level", cooldownTicks = 5)
            }
            SkillStatus.RUNNING -> SkillResult(
                SkillStatus.RUNNING,
                "Climbing: $gap blocks to go (${ascent.message})",
                cooldownTicks = 0
            )
            else -> {
                reset()
                SkillResult(
                    SkillStatus.FAILURE,
                    "Cannot climb to the owner: ${ascent.message}",
                    cooldownTicks = 100
                )
            }
        }
    }

    override fun stop(bot: CompanionBotEntity) {
        reset()
        bot.scaffoldingEngine.cancelAscent()
        // The stair is deliberately left standing: the companion is part-way up it, and
        // [ScaffoldReclaimSkill] packs it up once the companion is no longer using it.
    }

    private fun reset() {
        approachStartedTick = 0L
    }
}
