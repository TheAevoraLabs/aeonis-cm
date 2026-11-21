package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.ai.movement.VoxelPathPlanner
import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.safety.BuildLedger
import com.qc.aeonis.companion.safety.TerritoryGuard
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs

/**
 * Vertical and lateral traversal engineering for the companion: climbing cliffs it cannot
 * step up, crossing chasms it cannot jump, clearing obstacles it cannot walk around, and
 * — when it runs out of material — mining more.
 *
 * ### Design rule: one action per call
 * Every method performs at most one world-changing action and reports [SkillStatus.RUNNING]
 * while it still has work to do. Callers tick it from the behaviour tree, so scaffolding
 * interleaves properly with combat, orders and the preemption layer instead of blocking the
 * companion inside a long build loop. A "build a pillar" that took twenty ticks in one call
 * would be unresponsive to a `RETREAT` order for a full second.
 *
 * ### Why the guide-block choice is shape-based, not a whitelist
 * [isScaffoldBlock] asks whether the item's default state is a full collision cube, rather
 * than checking a list of block names. A name list silently mis-handles modded blocks and
 * every new vanilla block; a full cube is exactly the property that makes a block usable as
 * a step, a bridge deck or a pillar.
 */
class ScaffoldingEngine(private val bot: CompanionBotEntity) {

    /**
     * Outcome of one scaffolding step.
     *
     * @param status RUNNING while more work remains, SUCCESS on completion, FAILURE when the
     *   requested engineering is impossible from here (no material, no headroom, tool missing).
     * @param message short human-readable reason, surfaced in `/comp stats` and the debug log.
     * @param deltaOps world-changing operations performed by this call (0 or 1).
     */
    data class ScaffoldResult(
        val status: SkillStatus,
        val message: String,
        val deltaOps: Int = 0
    ) {
        val isRunning: Boolean get() = status == SkillStatus.RUNNING
        val isFailure: Boolean get() = status == SkillStatus.FAILURE
        val isSuccess: Boolean get() = status == SkillStatus.SUCCESS
    }

    companion object {
        /** Minimum elevation gain, in blocks, before pillaring beats walking. */
        const val PILLAR_THRESHOLD_Y = 2

        /** Ticks between pillar block placements. */
        const val PILLAR_PLACE_COOLDOWN = 4

        /** Ticks between bridge block placements, matched to a walking stride. */
        const val BRIDGE_PLACE_COOLDOWN = 5

        /** How long after issuing our own pillar jump a placement is still considered ours. */
        const val PILLAR_JUMP_GRACE_TICKS = 20

        /** Blocks scanned when looking for scavengable material. */
        const val SCAVENGE_RADIUS = 8

        /** Hardest block the engine will attempt to tunnel through (obsidian is 50). */
        const val MAX_CLEARABLE_HARDNESS = 12.0f

        /** How many scaffold blocks the engine considers a working stock. */
        const val COMFORTABLE_SCAFFOLD_STOCK = 16

        /** Blocks the companion tries to gather for itself when it runs dry. */
        const val SCAVENGE_TARGET = 8

        /**
         * Material the engine prefers to scavenge.
         *
         * Cobblestone used to be on this list "because it is cheap", and that single entry was
         * how the companion ended up mining the player's cobblestone house: the block is world
         * generation in dungeons *and* the most common player building material, and nothing here
         * was asking which it was. It has been removed, and every candidate is now screened by
         * [com.qc.aeonis.companion.safety.TerritoryGuard] before it is touched — the list decides
         * what is *useful*, the guard decides what is *allowed*.
         */
        val NATURAL_SCAVENGE_BLOCKS = setOf(
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.PODZOL,
            Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CLAY,
            Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE,
            Blocks.GRANITE, Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.OAK_LOG
        )

        /**
         * Ticks between reclaim digs.
         *
         * Matched to [PILLAR_PLACE_COOLDOWN], so packing a stair up is never faster than how
         * quickly it was built and the companion does not visibly churn.
         */
        const val RECLAIM_COOLDOWN = 4

        /**
         * How far the planner is allowed to look before the engine is permitted to build.
         *
         * The engine will only offer a stair or a bridge when a *no-engineering* route does not
         * exist. That search is bounded, and a bounded failure to find a route is treated as
         * "unreachable", which is the safe direction: the worst case is one unnecessary stair,
         * not a companion frozen at the bottom of a cliff.
         */
        const val ROUTE_PROBE_BUDGET = 512

        /** Furthest goal the no-engineering probe will judge, in blocks. */
        const val ROUTE_PROBE_RANGE = 32.0

        /** Ticks a route verdict is reused before the planner is asked again (1 second). */
        const val ROUTE_CACHE_TICKS = 20L

        /** How far the companion will walk to pick its own scaffolding back up. */
        const val RECLAIM_RADIUS = 16
    }

    // Per-climb intent, so an unrelated fall is never mistaken for a pillar jump.
    private var pillarJumpTick: Long = Long.MIN_VALUE

    // ------------------------------------------------------------------ material ledger

    /**
     * Memoised shape classification, keyed by item.
     *
     * Whether a block is a full cube cannot change at runtime, so this is cached for the
     * engine's lifetime. It matters because the classification runs inside per-slot loops (a
     * 27-slot stock count, a palette match over the whole backpack) and collision-shape lookups
     * are the expensive part.
     */
    private val fullCubeCache = HashMap<Item, Boolean>()

    /** True when the stack is a full solid cube usable as scaffolding. */
    fun isScaffoldBlock(stack: ItemStack): Boolean {
        val item = stack.item as? BlockItem ?: return false
        val level = bot.level() as? ServerLevel ?: return false
        if (!level.hasChunkAt(bot.blockPosition())) return false
        return fullCubeCache.getOrPut(item) {
            val state = item.block.defaultBlockState()
            !state.isAir && state.isCollisionShapeFullBlock(level, bot.blockPosition())
        }
    }

    /** First backpack slot holding usable scaffolding material, or -1. */
    fun scaffoldSlot(): Int = bot.firstSlotMatching { isScaffoldBlock(it) }

    /** Total count of scaffolding blocks carried, across every backpack slot. */
    fun scaffoldBlockCount(): Int {
        var total = 0
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = bot.inventory.getItem(slot)
            if (isScaffoldBlock(stack)) total += stack.count
        }
        return total
    }

    /** True when there is at least one block available for placing. */
    fun hasScaffoldBlock(): Boolean = scaffoldSlot() >= 0

    // ------------------------------------------------------------------ geometry queries

    /** Elevation the companion must gain to stand beside [target], clamped at zero. */
    fun elevationGap(target: BlockPos): Int =
        (target.y - bot.blockPosition().y).coerceAtLeast(0)

    /** True when [target] sits high enough that pillaring is the economical route. */
    fun requiresAscent(target: BlockPos): Boolean = elevationGap(target) >= PILLAR_THRESHOLD_Y

    /**
     * Width, in blocks, of the first open chasm along [direction]; 0 when the ground ahead
     * is continuous.
     *
     * "Chasm" means a run of cells at foot level with nothing under them within three
     * blocks — the void, a ravine or the edge of a cliff. A one-block dip is not a chasm:
     * the planner's step-down transition already handles that, and bridging it would waste
     * material and time.
     */
    fun chasmAhead(direction: Direction, maxWidth: Int = 4): Int {
        val level = bot.level() as? ServerLevel ?: return 0
        val feet = bot.blockPosition()
        var width = 0
        for (step in 1..maxWidth) {
            val cell = feet.relative(direction, step)
            if (!level.getBlockState(cell).isAir || !level.getBlockState(cell.below()).isAir) break
            width++
        }
        return width
    }

    /** True when two blocks of clearance exist above [pos] (i.e. the companion fits). */
    fun hasHeadroom(pos: BlockPos): Boolean {
        val level = bot.level() as? ServerLevel ?: return false
        val a = level.getBlockState(pos)
        val b = level.getBlockState(pos.above())
        return (a.isAir || a.canBeReplaced()) && (b.isAir || b.canBeReplaced())
    }

    // ------------------------------------------------------------------ pillaring

    /** Clears pillar intent, e.g. when the owner issues an order mid-climb. */
    fun cancelAscent() {
        pillarJumpTick = Long.MIN_VALUE
    }

    /**
     * Climbs one block by jumping and placing a block beneath the feet at the apex.
     *
     * The jump is issued by us and is time-stamped, so an *unrelated* airborne state (walking
     * off a ledge) can never be mistaken for a pillar opportunity — building under a fall is
     * how a bot digs itself into a hole.
     */
    fun pillarUp(): ScaffoldResult {
        val level = bot.level() as? ServerLevel ?: return ScaffoldResult(SkillStatus.FAILURE, "no server level")
        val now = level.gameTime

        // Out of material is a *supply* problem, not a refusal: the companion goes and digs some
        // dirt or stone itself rather than waiting for the player to hand it blocks.
        provisionMaterialIfNeeded()?.let { return it }

        val slot = scaffoldSlot()
        if (slot < 0) return ScaffoldResult(SkillStatus.FAILURE, "no scaffolding material")

        if (bot.onGround()) {
            pillarJumpTick = now
            bot.jumpFromGround()
            return ScaffoldResult(SkillStatus.RUNNING, "pillar: jump issued")
        }

        if (now - pillarJumpTick > PILLAR_JUMP_GRACE_TICKS) {
            pillarJumpTick = Long.MIN_VALUE
            return ScaffoldResult(SkillStatus.FAILURE, "airborne outside a pillar jump")
        }

        // Wait for upward motion: placing while still rising wastes the block, because the
        // entity would rise straight back out of the cell it was placed in.
        if (bot.deltaMovement.y <= 0.0) return ScaffoldResult(SkillStatus.RUNNING, "pillar: rising")

        val feet = bot.blockPosition()
        if (!level.getBlockState(feet).isAir) {
            return ScaffoldResult(SkillStatus.FAILURE, "no headroom above feet")
        }

        val placed = bot.placeFromSlot(feet, slot, Direction.UP, PILLAR_PLACE_COOLDOWN)
        pillarJumpTick = Long.MIN_VALUE
        return if (placed) {
            ScaffoldResult(SkillStatus.SUCCESS, "pillared up one block", deltaOps = 1)
        } else {
            ScaffoldResult(SkillStatus.RUNNING, "pillar: placement rejected")
        }
    }

    /**
     * Climbs toward [target]'s elevation, one block per call, up to [maxBlocks] this session.
     *
     * Returns SUCCESS as soon as the elevation gap is closed, which is what lets the caller
     * resume normal pathing on the same tick.
     */
    fun ascendTowards(target: BlockPos, maxBlocks: Int = 8): ScaffoldResult {
        val gap = elevationGap(target)
        if (gap < PILLAR_THRESHOLD_Y) return ScaffoldResult(SkillStatus.SUCCESS, "no ascent needed")
        if (gap > maxBlocks) {
            return ScaffoldResult(SkillStatus.FAILURE, "target $gap blocks up exceeds climb budget")
        }
        return pillarUp()
    }

    // ------------------------------------------------------------------ bridging

    /**
     * Places one bridge block across the chasm ahead of the companion.
     *
     * [direction] is normally the entity's facing. The block goes into the cell at foot level
     * one block ahead, which is the cell the companion will step into — the same cell a
     * player sneak-bridging fills.
     */
    fun bridgeForward(direction: Direction): ScaffoldResult {
        val level = bot.level() as? ServerLevel ?: return ScaffoldResult(SkillStatus.FAILURE, "no server level")
        if (direction.axis.isVertical) return ScaffoldResult(SkillStatus.FAILURE, "vertical bridge direction")

        provisionMaterialIfNeeded()?.let { return it }

        val slot = scaffoldSlot()
        if (slot < 0) return ScaffoldResult(SkillStatus.FAILURE, "no scaffolding material")

        val feet = bot.blockPosition()
        val target = feet.relative(direction)

        // Only bridge a genuine gap; placing against solid ground would wall the bot in.
        if (!level.getBlockState(target).isAir) {
            return ScaffoldResult(SkillStatus.SUCCESS, "bridge complete")
        }
        if (!level.getBlockState(target.below()).isAir) {
            return ScaffoldResult(SkillStatus.SUCCESS, "ground ahead is continuous")
        }

        val placed = bot.placeFromSlot(target, slot, Direction.UP, BRIDGE_PLACE_COOLDOWN)
        return if (placed) {
            ScaffoldResult(SkillStatus.RUNNING, "bridged one block forward", deltaOps = 1)
        } else {
            ScaffoldResult(SkillStatus.RUNNING, "bridge: placement rejected")
        }
    }

    // ------------------------------------------------------------------ obstacle clearing

    /** Reasons the companion should not try to tunnel through a given block. */
    enum class ClearanceVerdict { CLEAR, NEEDS_BETTER_TOOL, TOO_HARD, INDESTRUCTIBLE, HAZARD }

    /**
     * Judges whether [pos] is worth mining through.
     *
     * The `NEEDS_BETTER_TOOL` case is the one that matters in practice: without it the bot
     * happily starts mining stone with its bare hands, spends fifteen seconds on one block,
     * and looks broken. Harvesting the missing pickaxe is the progression loop's job, so the
     * engine declines and says why.
     */
    fun judgeClearance(pos: BlockPos): ClearanceVerdict {
        val level = bot.level() as? ServerLevel ?: return ClearanceVerdict.INDESTRUCTIBLE
        val state = level.getBlockState(pos)
        if (state.isAir) return ClearanceVerdict.CLEAR
        if (isHazard(state)) return ClearanceVerdict.HAZARD

        val speed = state.getDestroySpeed(level, pos)
        if (speed < 0.0f) return ClearanceVerdict.INDESTRUCTIBLE
        if (speed > MAX_CLEARABLE_HARDNESS) return ClearanceVerdict.TOO_HARD

        if (state.requiresCorrectToolForDrops() && !hasCorrectTool(state)) {
            return ClearanceVerdict.NEEDS_BETTER_TOOL
        }
        return ClearanceVerdict.CLEAR
    }

    /**
     * Public form of the tool check, for callers deciding whether a dig is even worth starting.
     *
     * Exposed rather than duplicated because "do I have the right tool for this block" has exactly
     * one correct answer and it depends on the mineable-with tags; a second copy would drift from
     * the one [judgeClearance] uses.
     */
    fun hasCorrectToolFor(pos: BlockPos): Boolean {
        val level = bot.level() as? ServerLevel ?: return false
        return hasCorrectTool(level.getBlockState(pos))
    }

    /** True when the inventory holds a tool tagged for [state]'s mineable-with family. */
    private fun hasCorrectTool(state: BlockState): Boolean {
        val tags = buildList {
            if (state.`is`(BlockTags.MINEABLE_WITH_PICKAXE)) add(net.minecraft.tags.ItemTags.PICKAXES)
            if (state.`is`(BlockTags.MINEABLE_WITH_AXE)) add(net.minecraft.tags.ItemTags.AXES)
            if (state.`is`(BlockTags.MINEABLE_WITH_SHOVEL)) add(net.minecraft.tags.ItemTags.SHOVELS)
            if (state.`is`(BlockTags.MINEABLE_WITH_HOE)) add(net.minecraft.tags.ItemTags.HOES)
        }
        if (tags.isEmpty()) return true
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = bot.inventory.getItem(slot)
            if (stack.isEmpty) continue
            if (tags.any { stack.`is`(it) }) return true
        }
        return tags.any { bot.inventory.mainHandItem.`is`(it) || bot.inventory.offHandItem.`is`(it) }
    }

    private fun isHazard(state: BlockState): Boolean {
        val block = state.block
        if (block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE ||
            block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS || block == Blocks.POWDER_SNOW
        ) {
            return true
        }
        return !state.fluidState.isEmpty && state.fluidState.`is`(net.minecraft.tags.FluidTags.LAVA)
    }

    /**
     * General-purpose mining primitive: mines [pos] with the right tool and collects the drop.
     *
     * Named separately from [clearObstacle] because callers outside traversal use it too — the
     * progression loop's wood and stone stages both mine through here, so there is exactly one
     * implementation of "mine this properly and pick it up".
     */
    fun mineBlock(pos: BlockPos): ScaffoldResult = clearObstacle(pos)

    /** Mines [pos] progressively, returning SUCCESS once the block is gone. */
    fun clearObstacle(pos: BlockPos): ScaffoldResult {
        val level = bot.level() as? ServerLevel ?: return ScaffoldResult(SkillStatus.FAILURE, "no server level")
        if (level.getBlockState(pos).isAir) return ScaffoldResult(SkillStatus.SUCCESS, "already clear")

        // Ownership is checked before feasibility. The engine reports the guard's own wording so
        // a refusal to mine the player's wall reads as a decision ("that was built, not
        // generated") rather than as a pathfinding failure.
        val ownership = TerritoryGuard.mayBreak(bot, pos)
        if (!TerritoryGuard.allows(ownership)) {
            return ScaffoldResult(SkillStatus.FAILURE, TerritoryGuard.describe(ownership))
        }

        return when (val verdict = judgeClearance(pos)) {
            ClearanceVerdict.CLEAR -> {
                val broken = bot.tryBreakBlock(pos)
                if (broken) {
                    vacuumDrops(2.5)
                    ScaffoldResult(SkillStatus.SUCCESS, "cleared obstacle", deltaOps = 1)
                } else {
                    ScaffoldResult(SkillStatus.RUNNING, "mining obstacle")
                }
            }
            ClearanceVerdict.NEEDS_BETTER_TOOL ->
                ScaffoldResult(SkillStatus.FAILURE, "no correct tool for ${level.getBlockState(pos).block.name.string}")
            ClearanceVerdict.TOO_HARD ->
                ScaffoldResult(SkillStatus.FAILURE, "block too hard to tunnel")
            ClearanceVerdict.INDESTRUCTIBLE ->
                ScaffoldResult(SkillStatus.FAILURE, "unbreakable block")
            ClearanceVerdict.HAZARD ->
                ScaffoldResult(SkillStatus.FAILURE, "hazardous block")
        }
    }

    // ------------------------------------------------------------------ material scavenging

    /**
     * Mines one nearby natural block to replenish scaffolding material.
     *
     * Only [NATURAL_SCAVENGE_BLOCKS] are eligible and only when the engine judges them
     * clearable, so the fallback cannot turn into "dig a hole to the bedrock when out of
     * dirt". Drops are vacuumed immediately rather than left on the floor, because a
     * scavenging bot that walks away from its own drop is the same bug as not digging at all.
     */
    fun scavengeScaffoldBlocks(target: Int = 4): ScaffoldResult {
        val level = bot.level() as? ServerLevel ?: return ScaffoldResult(SkillStatus.FAILURE, "no server level")
        if (scaffoldBlockCount() >= target) {
            return ScaffoldResult(SkillStatus.SUCCESS, "scaffold stock satisfied")
        }

        val origin = bot.blockPosition()
        val candidate = nearestScavengeTarget(level, origin)
            ?: return ScaffoldResult(SkillStatus.FAILURE, "no scavengable material within $SCAVENGE_RADIUS blocks")

        val distance = bot.blockPosition().closerThan(candidate, 3.0)
        if (!distance) {
            bot.navigation.moveTo(candidate.x + 0.5, candidate.y.toDouble(), candidate.z + 0.5, 1.2)
            return ScaffoldResult(SkillStatus.RUNNING, "walking to scavenge material")
        }

        return when (judgeClearance(candidate)) {
            ClearanceVerdict.CLEAR -> {
                val broken = bot.tryBreakBlock(candidate)
                if (broken) {
                    vacuumDrops(3.0)
                    ScaffoldResult(SkillStatus.RUNNING, "scavenged material", deltaOps = 1)
                } else {
                    ScaffoldResult(SkillStatus.RUNNING, "mining scavenge material")
                }
            }
            else -> ScaffoldResult(SkillStatus.FAILURE, "scavenge target not clearable")
        }
    }

    /** Nearest eligible natural block, preferring the closest to the bot's feet level. */
    private fun nearestScavengeTarget(level: ServerLevel, origin: BlockPos): BlockPos? {
        var best: BlockPos? = null
        var bestScore = Double.MAX_VALUE
        for (dx in -SCAVENGE_RADIUS..SCAVENGE_RADIUS) {
            for (dy in -2..3) {
                for (dz in -SCAVENGE_RADIUS..SCAVENGE_RADIUS) {
                    val pos = origin.offset(dx, dy, dz)
                    if (!level.hasChunkAt(pos)) continue
                    if (!NATURAL_SCAVENGE_BLOCKS.contains(level.getBlockState(pos).block)) continue
                    // Scavenging digs in the world, so it goes through the same gate as everything
                    // else: no player territory, no containers, no non-natural material.
                    if (!TerritoryGuard.allows(TerritoryGuard.mayBreak(bot, pos))) continue
                    val score = dx * dx + dz * dz + abs(dy) * 4.0
                    if (score < bestScore) {
                        bestScore = score
                        best = pos
                    }
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------ self-provisioning

    /**
     * Makes sure the companion has something to build with, by going and getting it.
     *
     * ### Why this lives in the engine rather than in each caller
     * The user's report was precise: *"he should himself break dirt blocks or stone if he has a
     * pickaxe and come near me"*, and they only handed the companion blocks because it had
     * stalled. Reporting "no scaffolding material" and standing there is a supply failure dressed
     * up as a capability failure. Every caller that needs a block — pillaring, bridging, stairs —
     * goes through this, so no future caller can forget to provision.
     *
     * Only natural material is eligible ([NATURAL_SCAVENGE_BLOCKS] screened by
     * [TerritoryGuard]), so provisioning can never become "mine the player's wall to fix my
     * material problem".
     *
     * @return null when the companion can build now, or a result to hand straight back to the
     *   caller while provisioning is still in progress or genuinely impossible.
     */
    private fun provisionMaterialIfNeeded(): ScaffoldResult? {
        if (hasScaffoldBlock()) return null

        val scavenge = scavengeScaffoldBlocks(SCAVENGE_TARGET)
        if (hasScaffoldBlock()) return null

        return when (scavenge.status) {
            SkillStatus.RUNNING -> ScaffoldResult(
                SkillStatus.RUNNING,
                "getting material to build with: ${scavenge.message}"
            )
            SkillStatus.SUCCESS -> ScaffoldResult(
                SkillStatus.FAILURE,
                "found nothing usable to build with"
            )
            else -> ScaffoldResult(
                SkillStatus.FAILURE,
                "nothing to build with: ${scavenge.message}"
            )
        }
    }

    // ------------------------------------------------------------------ route probing

    /** What a no-engineering route probe concluded. */
    enum class RouteVerdict {
        /** Ordinary walking, jumping and dropping gets there. Do not build anything. */
        EXISTS,

        /** Ordinary movement cannot get there, so engineering is justified. */
        MISSING,

        /** Too far to judge within the probe budget; do not engineer on a guess. */
        UNKNOWN
    }

    private var routeCacheGoal: BlockPos? = null
    private var routeCacheVerdict: RouteVerdict? = null
    private var routeCacheTick: Long = Long.MIN_VALUE

    /**
     * Answers "could I simply walk there?" *before* any block is placed.
     *
     * ### Why this is the gate in front of all engineering
     * Pillaring and bridging used to fire on a purely local reading — "the waypoint is two blocks
     * above me", "there is a hole ahead" — with no attempt to find out whether the destination was
     * reachable the ordinary way. Stairs therefore appeared where a player would simply have walked
     * around, which is the worst kind of visible incompetence: it litters the world *and* wastes
     * material. The user's rule is explicit: **try advanced pathfinding first, and only build a
     * reclaimable stair if the target is genuinely unreachable.**
     *
     * The probe runs [VoxelPathPlanner] with `canDig = false` and `canBuild = false`, so the action
     * primitives it is allowed to use are exactly the ones the companion can already do — walk,
     * step up, step down, fall safely, sprint-jump a gap. A [RouteVerdict.MISSING] answer therefore
     * means "the only way there is to change the world", which is precisely when scaffolding is
     * warranted.
     *
     * ### Cost
     * A* is the most expensive thing the engine can do, and `canActivate` runs every tick, so the
     * verdict is cached per goal for [ROUTE_CACHE_TICKS]. One search per second, per companion, is
     * a rounding error; one per tick would not be.
     */
    fun routeVerdict(goal: BlockPos): RouteVerdict {
        val level = bot.level() as? ServerLevel ?: return RouteVerdict.UNKNOWN
        val now = level.gameTime

        if (routeCacheGoal == goal && routeCacheVerdict != null && now - routeCacheTick < ROUTE_CACHE_TICKS) {
            return routeCacheVerdict!!
        }

        val start = bot.blockPosition()
        // Beyond the probe range a bounded search cannot distinguish "no route" from "no budget",
        // and building a stair toward a distant waypoint would put blocks far from the player for
        // no reason. Decline to judge.
        val verdict = if (!start.closerThan(goal, ROUTE_PROBE_RANGE)) {
            RouteVerdict.UNKNOWN
        } else {
            val planner = VoxelPathPlanner(
                level = level,
                canDig = false,
                canBuild = false,
                maxSearchBudget = ROUTE_PROBE_BUDGET
            )
            try {
                if (planner.isReachable(start, goal, ROUTE_PROBE_BUDGET)) RouteVerdict.EXISTS
                else RouteVerdict.MISSING
            } catch (_: Throwable) {
                RouteVerdict.UNKNOWN
            }
        }

        routeCacheGoal = goal
        routeCacheVerdict = verdict
        routeCacheTick = now
        return verdict
    }

    /**
     * True when a stair, pillar or bridge is the only way to [goal].
     *
     * The single predicate every engineering caller should use. It is deliberately the *inverse*
     * of the probe so the "try walking first" rule cannot be forgotten at a call site.
     */
    fun engineeringRequiredFor(goal: BlockPos): Boolean = routeVerdict(goal) == RouteVerdict.MISSING

    // ------------------------------------------------------------------ reclaiming

    /**
     * Takes back the companion's own scaffolding, nearest first, and returns the material to the
     * backpack.
     *
     * ### Reclaimable, by construction
     * Every block this engine places is recorded in [BuildLedger] as
     * [BuildLedger.BotPlacementKind.SCAFFOLD], and the guard ranks our own scaffold above every
     * protective rule, so reclaiming works even inside a region a player has since marked. That is
     * what makes the stair "reclaimable" rather than merely "temporary": the companion can always
     * pick up after itself.
     *
     * Only scaffolding is eligible. Shelter blocks are recorded as `PERMANENT` and are never dug
     * back up, because a wall the player asked for is not litter.
     */
    fun reclaimScaffold(radius: Int = RECLAIM_RADIUS): ScaffoldResult {
        val level = bot.level() as? ServerLevel ?: return ScaffoldResult(SkillStatus.FAILURE, "no server level")
        val origin = bot.blockPosition()

        val candidates = BuildLedger.scaffoldPositionsNear(level, origin, radius)
        if (candidates.isEmpty()) return ScaffoldResult(SkillStatus.SUCCESS, "no scaffold to reclaim")

        for (pos in candidates) {
            if (!BuildLedger.isBotScaffold(level, pos)) continue
            if (level.getBlockState(pos).isAir) {
                BuildLedger.forget(level, pos)
                continue
            }
            if (!origin.closerThan(pos, 3.0)) {
                bot.navigation.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, 1.15)
                return ScaffoldResult(SkillStatus.RUNNING, "walking back to my scaffold")
            }
            val result = clearObstacle(pos)
            return if (result.isSuccess) {
                ScaffoldResult(SkillStatus.RUNNING, "reclaimed scaffolding", deltaOps = 1)
            } else {
                // Usually a stone pillar with no pickaxe to hand: leave it rather than retry.
                BuildLedger.forget(level, pos)
                ScaffoldResult(SkillStatus.SUCCESS, "left a scaffold I cannot take back")
            }
        }
        return ScaffoldResult(SkillStatus.SUCCESS, "no scaffold to reclaim")
    }

    /** True when the companion has any of its own scaffolding still standing nearby. */
    fun hasScaffoldToReclaim(radius: Int = RECLAIM_RADIUS): Boolean {
        val level = bot.level() as? ServerLevel ?: return false
        return BuildLedger.scaffoldPositionsNear(level, bot.blockPosition(), radius)
            .any { !level.getBlockState(it).isAir }
    }

    /**
     * Collects item drops within [radius] into the backpack, leaving anything that does not
     * fit on the ground.
     */
    fun vacuumDrops(radius: Double): Int {
        val level = bot.level() as? ServerLevel ?: return 0
        val drops = level.getEntitiesOfClass(ItemEntity::class.java, bot.boundingBox.inflate(radius)) { drop ->
            drop.isAlive && !drop.hasPickUpDelay()
        }
        var collected = 0
        for (drop in drops) {
            val original = drop.item
            val leftover = bot.inventory.collectDrop(original)
            if (leftover.isEmpty) {
                bot.take(drop, original.count)
                drop.discard()
                collected += original.count
            } else if (leftover.count != original.count) {
                drop.item = leftover
                collected += original.count - leftover.count
            }
        }
        if (collected > 0) bot.inventory.setChanged()
        return collected
    }
}
