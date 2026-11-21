package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.ceil

/**
 * Real block breaking for the companion: one block at a time, at vanilla's own speed, with the
 * right tool chosen, held visibly, and the drop collected.
 *
 * ### The bug this replaces
 * The previous implementation did `breakProgress += 0.25f` — **four ticks for every block in the
 * game**, regardless of hardness, tool or enchantments. Obsidian, stone and a sapling all came
 * apart in 0.2 seconds, which is what "it breaks blocks soo fast" was. It also kept whatever
 * happened to be in the main hand, so the companion was visibly mining with a torch or a sword.
 *
 * ### How progress is computed
 * Progress comes from the game's own `BlockState.getDestroyProgress(player, level, pos)`, evaluated
 * with the chosen tool in the surrogate's hand. That method is
 * `destroySpeed / hardness / (correctToolForDrops ? 30 : 100)` with `destroySpeed` taken from the
 * held stack *after* the `MINING_EFFICIENCY` attribute and the dig-speed effect are applied — so
 * tool tier, Efficiency enchantments, Haste and instant-break blocks are all honoured without this
 * class re-deriving any of it. The previous implementation did `progress += 0.25f`; everything
 * here exists because that four-ticks-per-block constant is what "it breaks blocks soo fast" was.
 *
 * The resulting timings are the vanilla published ones: stone takes 8 ticks with an iron pickaxe,
 * 6 with diamond, 12 with stone, 23 with wood, and **150 by hand** (7.5s). Oak logs are 8 ticks
 * with a diamond axe. Obsidian is about 188 ticks with a diamond pickaxe and is refused outright
 * without one, because it would take 5 000 ticks by hand.
 *
 * ### One session per entity
 * A mob digs one block at a time, exactly like a player. The session holds the current target, the
 * tool it locked in, and the accumulated progress; switching target, tool or job resets it. That
 * reset rule is what stops the "started mining, changed its mind, came back" thrash that a
 * stateless per-call break accumulates.
 */
class MiningSession(private val bot: CompanionBotEntity) {

    /** Where the session is in its lifecycle, and why. */
    enum class Phase {
        /** No target. */
        IDLE,

        /** Target acquired and the tool is locked in; progress has begun. */
        STARTED,

        /** Digging, progress accumulating. */
        MINING,

        /** The block is gone and the drop was collected. */
        BROKEN,

        /** Declined before starting: wrong tool, no progress possible, or too slow. */
        REFUSED,

        /** The block cannot be broken at all (bedrock, barrier, world border). */
        UNBREAKABLE
    }

    data class MiningResult(
        val phase: Phase,
        val message: String,
        val progressPercent: Int = 0,
        val ticksRemaining: Int = 0
    ) {
        val isBroken: Boolean get() = phase == Phase.BROKEN
        val isRefused: Boolean get() = phase == Phase.REFUSED || phase == Phase.UNBREAKABLE
        val isWorking: Boolean get() = phase == Phase.STARTED || phase == Phase.MINING
    }

    /**
     * The tool the session locked in for the current target.
     *
     * @param slot inventory slot it came from; -1 means bare hands.
     * @param correctTool whether vanilla would drop anything with it.
     */
    data class ToolChoice(
        val slot: Int,
        val stack: ItemStack,
        val correctTool: Boolean,
        val destroySpeed: Float,
        val score: Double
    )

    companion object {
        /** Vanilla's divisors in `getDestroyProgress`: 30 with the right tool, 100 without. */
        const val CORRECT_TOOL_DIVISOR = 30.0f
        const val WRONG_TOOL_DIVISOR = 100.0f

        /** Bonus that makes any correct tool beat any incorrect one, whatever its speed. */
        const val CORRECT_TOOL_BONUS = 1000.0

        /** Speed is worth this much per unit when comparing two otherwise equal tools. */
        const val SPEED_WEIGHT = 10.0

        /** Remaining-durability fraction below which a tool is treated as fragile. */
        const val FRAGILE_DURABILITY = 0.15

        /** Score penalty for a fragile tool, so a fresh stone pick beats a 1-use diamond one. */
        const val FRAGILE_PENALTY = 400.0

        /**
         * Refuse a target whose break time with the best available tool exceeds this.
         *
         * This is the check that stops the companion from spending three minutes on obsidian and
         * looking broken. At 200 ticks it still allows stone by hand (150) and every realistic
         * mining job, while refusing obsidian without a diamond pickaxe.
         */
        const val MAX_TICKS_PER_BLOCK = 200

        /** Radius for vacuuming the drop after a break. */
        const val DROP_PICKUP_RADIUS = 2.5

        /**
         * Pause between consecutive blocks.
         *
         * The break itself is now vanilla-timed, so this only stops the companion chaining blocks
         * faster than a player could aim. Five ticks keeps clearing smooth without looking
         * mechanical; ten, which it used to be, visibly stuttered on stone.
         */
        const val COOLDOWN_AFTER_BREAK = 5

        /** How many times a rejected break is retried before the target is abandoned. */
        const val MAX_REJECTION_RETRIES = 20
    }

    var targetPos: BlockPos? = null
        private set

    var progress: Float = 0f
        private set

    var ticksSpent: Int = 0
        private set

    /** Tool locked in for [targetPos]; null when idle. */
    var lockedTool: ToolChoice? = null
        private set

    /** Reason the last [advance] refused, for diagnostics. */
    var lastRefusal: String? = null
        private set

    /** Blocks broken by this session since it was created; surfaced by `/comp status`. */
    var blocksBroken: Int = 0
        private set

    /** Consecutive rejected breaks on the current target, reset on success or on a new target. */
    private var rejectionRetries: Int = 0

    /** Cancels any in-progress dig, e.g. when an order arrives or the target changes. */
    fun reset() {
        targetPos = null
        progress = 0f
        ticksSpent = 0
        lockedTool = null
        rejectionRetries = 0
    }

    fun progressPercent(): Int = (progress * 100f).toInt().coerceIn(0, 100)

    /** True when this session is currently committed to [pos]. */
    fun isTargeting(pos: BlockPos): Boolean = targetPos == pos

    /**
     * Advances the dig at [pos] by one tick.
     *
     * Call it every tick for the same position to get vanilla speed; call it with a different
     * position and the session restarts on the new block, discarding the old progress — which is
     * exactly what a player switching targets does.
     */
    fun advance(pos: BlockPos): MiningResult {
        val level = bot.level() as? ServerLevel
            ?: return MiningResult(Phase.REFUSED, "no server level")
        val state = level.getBlockState(pos)

        if (state.isAir) {
            val wasOurs = targetPos == pos
            reset()
            return if (wasOurs) {
                MiningResult(Phase.BROKEN, "block already gone")
            } else {
                MiningResult(Phase.IDLE, "nothing to mine")
            }
        }

        if (targetPos != pos) {
            val start = beginTarget(level, pos, state)
            if (start != null) return start
        }

        val choice = lockedTool ?: return MiningResult(Phase.REFUSED, "no tool locked in")

        // A player can walk into the dig site mid-dig. The gate was evaluated when the target was
        // locked in, so it is re-checked cheaply here: the one failure mode worth a per-tick
        // entity query is pulling the floor out from under somebody who just stepped over it.
        if (com.qc.aeonis.companion.safety.TerritoryGuard.playerNear(level, pos)) {
            reset()
            lastRefusal = "the player stepped into the dig"
            return MiningResult(Phase.REFUSED, "the player stepped into the dig")
        }

        val perTick = measureProgressPerTick(state, pos, choice)
        if (perTick <= 0f) {
            lastRefusal = "no progress possible"
            return MiningResult(Phase.REFUSED, "cannot make progress on this block").also { reset() }
        }
        progress += perTick
        ticksSpent++

        if (progress < 1.0f) {
            val remaining = ceil(((1.0f - progress) / perTick)).toInt().coerceAtLeast(1)
            return MiningResult(
                Phase.MINING,
                "mining with ${describeTool(choice)}",
                progressPercent(),
                remaining
            )
        }

        return finishBreak(level, pos, choice)
    }

    /**
     * Validates a new target and locks in the tool.
     *
     * @return non-null when the target must be refused immediately.
     */
    private fun beginTarget(level: ServerLevel, pos: BlockPos, state: BlockState): MiningResult? {
        reset()
        lastRefusal = null

        if (state.getDestroySpeed(level, pos) < 0f) {
            lastRefusal = "unbreakable block"
            return MiningResult(Phase.UNBREAKABLE, "unbreakable block")
        }

        // The grief gate. Every block the companion ever breaks passes through here — obstacle
        // clearing, tree chopping, ore gathering, tunnelling and reclaiming its own stair — so
        // this single check is what makes "never mine the player's build" true mod-wide rather
        // than true in the handful of places someone remembered to ask.
        val verdict = com.qc.aeonis.companion.safety.TerritoryGuard.mayBreak(bot, pos)
        if (!com.qc.aeonis.companion.safety.TerritoryGuard.allows(verdict)) {
            val reason = com.qc.aeonis.companion.safety.TerritoryGuard.describe(verdict)
            lastRefusal = reason
            return MiningResult(Phase.REFUSED, reason)
        }

        val choice = selectTool(state)
        val perTick = measureProgressPerTick(state, pos, choice)
        if (perTick <= 0f) {
            lastRefusal = "no progress possible"
            return MiningResult(Phase.REFUSED, "cannot make progress on this block")
        }

        val estimate = ceil(1.0 / perTick).toInt()
        if (estimate > MAX_TICKS_PER_BLOCK) {
            lastRefusal = "would take ${estimate}t"
            return MiningResult(
                Phase.REFUSED,
                "would take ${estimate} ticks with ${describeTool(choice)}",
                ticksRemaining = estimate
            )
        }

        targetPos = pos
        lockedTool = choice
        progress = 0f
        ticksSpent = 0
        equipTool(choice.stack, choice.slot)
        return null
    }

    /**
     * Breaks the block through the player-grade surrogate and collects the drop.
     *
     * A rejection (out of reach, protected block, the world changing underfoot) does **not** throw
     * the target away: the dig is held at "one tick short" and retried, because resetting would send
     * the caller off to re-path to the same block, which is a walk-fail-walk loop. After
     * [MAX_REJECTION_RETRIES] consecutive rejections the session does give up, so a genuinely
     * unreachable block cannot pin the companion forever.
     */
    private fun finishBreak(level: ServerLevel, pos: BlockPos, choice: ToolChoice): MiningResult {
        val surrogate = bot.getOrCreateSurrogate()
        val tool = lockedTool?.stack ?: ItemStack.EMPTY

        val broke = surrogate?.mineBlock(pos, tool) ?: level.destroyBlock(pos, true, bot)
        if (!broke) {
            rejectionRetries++
            if (rejectionRetries <= MAX_REJECTION_RETRIES) {
                progress = 0.99f
                lastRefusal = "break rejected (attempt $rejectionRetries)"
                return MiningResult(
                    Phase.MINING,
                    "waiting to break ${pos.toShortString()} ($rejectionRetries/$MAX_REJECTION_RETRIES)",
                    progressPercent(),
                    1
                )
            }
            reset()
            rejectionRetries = 0
            lastRefusal = "the world rejected the break $MAX_REJECTION_RETRIES times"
            return MiningResult(Phase.REFUSED, "could not break the block")
        }

        // The cell is empty now: drop its ownership marks so a later dig in the same spot is not
        // judged against a block that no longer exists.
        com.qc.aeonis.companion.safety.BuildLedger.forget(level, pos)
        val collected = bot.scaffoldingEngine.vacuumDrops(DROP_PICKUP_RADIUS)
        val toolName = describeTool(choice)
        reset()
        rejectionRetries = 0
        blocksBroken++
        val suffix = if (collected > 0) " (+$collected collected)" else ""
        return MiningResult(Phase.BROKEN, "broke it with $toolName$suffix")
    }

    /**
     * Per-tick progress, straight from the game: `BlockState.getDestroyProgress` evaluated with
     * [choice]'s stack selected.
     *
     * The tool is pinned into the surrogate's hand for the measurement and cleared immediately
     * afterwards — the hand is scratch space (see [com.qc.aeonis.companion.entity.IPlayerSurrogate]),
     * so this has no side effects on the companion's inventory. Going through the game's method
     * rather than recomputing `speed / hardness / 30` is deliberate: the re-derivation silently
     * omitted the `MINING_EFFICIENCY` attribute and the dig-speed effect, so an Efficiency V
     * pickaxe would have dug at exactly the same speed as a plain one.
     */
    fun measureProgressPerTick(state: BlockState, pos: BlockPos, choice: ToolChoice): Float {
        val level = bot.level() as? ServerLevel ?: return 0f
        val surrogate = bot.getOrCreateSurrogate() ?: return 0f
        val hand = InteractionHand.MAIN_HAND
        val previous = surrogate.getItemInHand(hand).copy()
        return try {
            surrogate.setItemInHand(hand, choice.stack)
            state.getDestroyProgress(surrogate, level, pos)
        } catch (_: Throwable) {
            0f
        } finally {
            surrogate.setItemInHand(hand, previous)
        }
    }

    /** Expected ticks to break [state] with the best available tool; [Int.MAX_VALUE] when impossible. */
    fun estimateTicks(state: BlockState, pos: BlockPos): Int {
        val choice = selectTool(state)
        val perTick = measureProgressPerTick(state, pos, choice)
        if (perTick <= 0f) return Int.MAX_VALUE
        return ceil(1.0 / perTick).toInt()
    }

    /**
     * Picks the tool to dig [state] with.
     *
     * Scoring, highest wins:
     * 1. **Correct tool for drops** — a flat [CORRECT_TOOL_BONUS], so any real tool beats any
     *    non-tool no matter how fast the latter is. This is what makes the companion pick the
     *    pickaxe instead of the torch.
     * 2. **Speed** — `destroySpeed × `[SPEED_WEIGHT], so between two correct tools the faster
     *    (better tier) one wins.
     * 3. **Durability** — [-FRAGILE_PENALTY] for a tool below 15% durability, so a fresh stone
     *    pickaxe is preferred to a diamond pickaxe on its last use.
     *
     * Items that are not in one of the four mining tool families are never chosen: they score
     * exactly what a bare hand scores, which is the fix for "it breaks blocks with a random
     * object". A sword is a weapon, not a shovel, and the companion no longer pretends otherwise.
     *
     * Hands are considered first so ties keep the already-held tool, avoiding a per-block swap.
     */
    fun selectTool(state: BlockState): ToolChoice {
        val inventory = bot.inventory
        val handChoice = ToolChoice(-1, ItemStack.EMPTY, false, ItemStack.EMPTY.getDestroySpeed(state), -1.0)

        var best = scoreCandidate(-1, ItemStack.EMPTY, state, handChoice)

        fun consider(slot: Int) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) return
            val candidate = scoreCandidate(slot, stack, state, best)
            if (candidate.score > best.score) best = candidate
        }

        consider(CompanionInventory.MAINHAND_SLOT)
        consider(CompanionInventory.OFFHAND_SLOT)
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            consider(slot)
        }
        return best
    }

    private fun scoreCandidate(slot: Int, stack: ItemStack, state: BlockState, fallback: ToolChoice): ToolChoice {
        if (stack.isEmpty) return fallback

        val speed = stack.getDestroySpeed(state)
        val correct = stack.isCorrectToolForDrops(state)
        val isToolFamily = TOOL_TAGS.any { stack.`is`(it) }

        // Non-tools are scored as a bare hand: they may be held, but they are never *chosen*.
        if (!isToolFamily) {
            val handScore = (if (correct) CORRECT_TOOL_BONUS else 0.0) + speed * SPEED_WEIGHT
            return ToolChoice(slot, stack, correct, speed, handScore - 1.0)
        }

        var score = (if (correct) CORRECT_TOOL_BONUS else 0.0) + speed * SPEED_WEIGHT
        if (stack.isDamageableItem) {
            val max = stack.maxDamage
            if (max > 0) {
                val remaining = (max - stack.damageValue).coerceAtLeast(0).toDouble() / max.toDouble()
                if (remaining < FRAGILE_DURABILITY) score -= FRAGILE_PENALTY
            }
        }
        return ToolChoice(slot, stack, correct, speed, score)
    }

    /**
     * Puts [stack] in the main hand so the player can see which tool the companion is using, and
     * so durability is applied to the tool that did the work.
     *
     * Uses [CompanionInventory.equipFromSlot], which swaps rather than overwrites — writing the
     * main-hand slot directly is what previously duplicated a stack into two slots.
     */
    private fun equipTool(stack: ItemStack, slot: Int) {
        if (stack.isEmpty || slot < 0) return
        if (slot == CompanionInventory.MAINHAND_SLOT) return
        if (bot.inventory.getItem(slot).isEmpty) return

        val displacedTo = bot.inventory.equipFromSlot(slot)
        if (displacedTo < 0) return

        // The tool now lives in the main hand; keep the session's slot in step so a later swap
        // (combat equipping a weapon) does not leave us pointing at whatever moved into `slot`.
        lockedTool = lockedTool?.copy(slot = CompanionInventory.MAINHAND_SLOT)
    }

    private fun describeTool(choice: ToolChoice): String =
        if (choice.stack.isEmpty) "bare hands" else choice.stack.hoverName.string

    private val TOOL_TAGS = listOf(ItemTags.PICKAXES, ItemTags.SHOVELS, ItemTags.AXES, ItemTags.HOES)
}
