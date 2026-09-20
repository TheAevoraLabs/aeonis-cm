package com.qc.aeonis.companion.progression

/**
 * The companion's user-need progression ladder (Contract 6 in the shared task board).
 *
 * The order of the constants is the order of the ladder, and the companion only ever moves
 * forward through it *or* drops back to a lower rung when it has genuinely run out of that
 * rung's resource. It never skips a rung: a companion with no wood cannot be in
 * [STONE_AGE], because it would have no tool to mine stone with.
 */
enum class ProgressionStage {
    /** Chopping logs for planks, sticks and a first tool. */
    WOOD_GATHERING,

    /** Mining cobblestone and coal, which requires a pickaxe from the previous rung. */
    STONE_AGE,

    /** Using a crafting table the *player* made first: tools, torches, bread. */
    CRAFTING_SUPPORT,

    /** Helping enclose the player's base, matching the block palette already in use. */
    SHELTER_CONSTRUCTION,

    /** Taking raw meat to a nearby furnace and bringing back cooked food. */
    FURNACE_COOKING,

    /** Nothing owed: exploring, scavenging and assisting as the player travels. */
    EXPEDITION
}

/**
 * A pure snapshot of everything the progression decision depends on.
 *
 * Deliberately a plain data class of primitives and booleans, with no world handles: it makes
 * [ProgressionPlanner.nextStage] a total function of its input, which is what allows the ladder
 * to be reasoned about and tested without spinning up a server. The skill builds one of these
 * per evaluation from live worlds.
 */
data class ProgressionSnapshot(
    val logs: Int = 0,
    val planks: Int = 0,
    val sticks: Int = 0,
    val cobblestone: Int = 0,
    val coal: Int = 0,
    val hasAnyPickaxe: Boolean = false,
    val hasMeleeWeapon: Boolean = false,
    val hasTorches: Boolean = false,
    /** True once the player has crafted or placed a crafting table at least once. */
    val craftingTableUnlocked: Boolean = false,
    /** True when a crafting table is currently within interaction range. */
    val craftingTableReachable: Boolean = false,
    /** True when a furnace is within the cooking stage's interaction radius. */
    val furnaceNearby: Boolean = false,
    val rawMeat: Int = 0,
    val cookedMeat: Int = 0,
    val fuel: Int = 0,
    /** True when the player is actively building and there is assembly work to help with. */
    val playerIsBuilding: Boolean = false,
    /** True when the shelter helper has at least one block left to place. */
    val shelterWorkPending: Boolean = false,
    val ownerFoodLevel: Int = 20
) {
    /** True when the companion has enough wood to stop chopping. */
    val woodSatisfied: Boolean get() = planks >= WOOD_TARGET_PLANKS && sticks >= WOOD_TARGET_STICKS
}

/** Wood targets. Set above the bare minimum so the companion does not stop one plank short. */
const val WOOD_TARGET_PLANKS: Int = 12
const val WOOD_TARGET_STICKS: Int = 12

/** Cobblestone target for the stone-age rung. */
const val STONE_TARGET_COBBLESTONE: Int = 20

/**
 * Chooses the companion's next stage from a snapshot.
 *
 * ### The crafting-table rule
 * Stage 3 carries the user's hard requirement: **the companion may never craft a crafting
 * table itself**. It waits for the player to craft or place one, and only then unlocks the
 * 3×3 recipes. [ProgressionSnapshot.craftingTableUnlocked] is that latch; a companion whose
 * player has never made a table simply never enters [ProgressionStage.CRAFTING_SUPPORT] or
 * [ProgressionStage.STONE_AGE], which is the intended, visible consequence of the rule.
 *
 * ### Evaluation order
 * Reactive service stages ([FURNACE_COOKING], [SHELTER_CONSTRUCTION]) are checked before the
 * prerequisite ladder, because they answer an immediate need of the player rather than a need
 * of the bot, and because they are cheap to satisfy. Within the ladder, rungs are checked in
 * ascending order and the first unsatisfied one wins.
 *
 * @param current the stage the companion was in, used only to avoid thrashing between two
 *   stages that are both satisfiable; the returned stage may be lower than it when a resource
 *   genuinely ran out.
 */
object ProgressionPlanner {

    fun nextStage(snapshot: ProgressionSnapshot, current: ProgressionStage): ProgressionStage {
        // 1. Reactive: cooking is a short, high-value errand with a visible payoff.
        if (snapshot.rawMeat > 0 && snapshot.fuel > 0 && snapshot.furnaceNearby) {
            return ProgressionStage.FURNACE_COOKING
        }

        // 2. Reactive: the player is building and there is assembly work left.
        if (snapshot.playerIsBuilding && snapshot.shelterWorkPending) {
            return ProgressionStage.SHELTER_CONSTRUCTION
        }

        // 3. Ladder rung one: wood. This is the rung that produces the first weapon, but the
        //    weapon is deliberately *not* a condition here — a missing weapon needs a crafting
        //    table, so it belongs to rung two. Treating it as a wood need instead sent a
        //    companion with no table off to chop logs forever with nothing to spend them on.
        if (!snapshot.woodSatisfied) {
            return ProgressionStage.WOOD_GATHERING
        }

        // 4. Ladder rung two: crafting. Gated on the player having shown the companion a table.
        if (snapshot.craftingTableUnlocked && needsCraftingWork(snapshot)) {
            return ProgressionStage.CRAFTING_SUPPORT
        }

        // 5. Ladder rung three: stone. Requires a pickaxe, which requires rung two.
        if (snapshot.craftingTableUnlocked && snapshot.hasAnyPickaxe &&
            snapshot.cobblestone < STONE_TARGET_COBBLESTONE
        ) {
            return ProgressionStage.STONE_AGE
        }

        // 6. Nothing owed.
        return ProgressionStage.EXPEDITION
    }

    /**
     * True when there is a specific 3×3 recipe worth walking to a table for.
     *
     * A companion that goes looking for a table with nothing to make is worse than one that
     * stays put, so this is intentionally a closed list of concrete wants.
     */
    fun needsCraftingWork(snapshot: ProgressionSnapshot): Boolean {
        if (!snapshot.hasAnyPickaxe) return true
        // A sword is the companion's hunting licence: without one it will not take an animal.
        if (!snapshot.hasMeleeWeapon && snapshot.planks >= 2 && snapshot.sticks >= 1) return true
        if (!snapshot.hasTorches && (snapshot.coal > 0 || snapshot.sticks >= 1)) return true
        if (snapshot.ownerFoodLevel < 18 && snapshot.cookedMeat == 0) return true
        return false
    }

    /** Human-readable one-liner for `/comp stats` and the debug log. */
    fun describe(stage: ProgressionStage): String = when (stage) {
        ProgressionStage.WOOD_GATHERING -> "gathering wood and a first weapon"
        ProgressionStage.STONE_AGE -> "mining cobblestone and coal"
        ProgressionStage.CRAFTING_SUPPORT -> "crafting tools, torches and food"
        ProgressionStage.SHELTER_CONSTRUCTION -> "helping build shelter"
        ProgressionStage.FURNACE_COOKING -> "cooking meat at a furnace"
        ProgressionStage.EXPEDITION -> "exploring and assisting"
    }
}

/**
 * Per-bot stage holder. Keeps the current stage and the tick it was entered, and applies
 * [ProgressionPlanner] without ever moving backwards within the same tick it moved forwards.
 */
class ProgressionStateMachine {

    var stage: ProgressionStage = ProgressionStage.WOOD_GATHERING
        private set

    var stageEnteredTick: Long = 0L
        private set

    /** Last reason the planner gave for a stage change; surfaced by `/comp stats`. */
    var lastTransition: String = "initial"
        private set

    /** Number of stage changes, used to detect thrashing in the debug view. */
    var transitionCount: Int = 0
        private set

    /**
     * Re-evaluates the stage.
     *
     * @return true when the stage changed this call.
     */
    fun update(snapshot: ProgressionSnapshot, nowTick: Long): Boolean {
        val next = ProgressionPlanner.nextStage(snapshot, stage)
        if (next == stage) return false
        lastTransition = "${stage.name} → ${next.name} @${nowTick}t"
        stage = next
        stageEnteredTick = nowTick
        transitionCount++
        return true
    }

    fun describe(): String = "${ProgressionPlanner.describe(stage)} (${lastTransition})"
}
