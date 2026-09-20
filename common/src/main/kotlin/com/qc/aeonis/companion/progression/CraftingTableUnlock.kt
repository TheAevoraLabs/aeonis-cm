package com.qc.aeonis.companion.progression

import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/**
 * Enforces the user's crafting-table rule: **the companion cannot make a crafting table
 * until the player has made or placed one first.**
 *
 * ### Why a latch rather than a live check
 * The rule is about provenance, not presence. A live "is there a table nearby" check would
 * unlock as soon as the companion wandered past a village or another player's base, and would
 * relock the moment it walked away, which is neither the rule nor stable behaviour. This is a
 * one-way latch: once the player has demonstrably produced a crafting table, the companion
 * knows the recipe for good.
 *
 * ### How the player's production is detected
 * Two independent signals, both of which mean "the player made one":
 * 1. a crafting table **in the player's own inventory** (they just crafted it), or
 * 2. a crafting table **block** standing within [SCAN_RADIUS] of the player (they placed it).
 *
 * Either is sufficient, and neither can be triggered by the companion's own actions, because
 * the companion's inventory is its own and it never places a table before the latch is set.
 */
class CraftingTableUnlock {

    var isUnlocked: Boolean = false
        private set

    var unlockedAtTick: Long = -1L
        private set

    /** How the latch was satisfied, for `/comp stats`. */
    var evidence: String = "not yet observed"
        private set

    private var lastScanTick: Long = -1L

    companion object {
        /**
         * Radius, in blocks, around the player searched for a placed crafting table.
         *
         * Twenty-four blocks covers a normal base without the search dominating tick time:
         * the scan is a bounded cube walk and only runs every [SCAN_INTERVAL_TICKS].
         */
        const val SCAN_RADIUS = 24

        /** Ticks between scans while the latch is still unset. */
        const val SCAN_INTERVAL_TICKS = 40L

        /** Radius within which the companion can actually stand at the table and craft. */
        const val REACHABLE_RADIUS = 4
    }

    /**
     * Polls for evidence, latching permanently the first time any is found.
     *
     * @return the current latch value.
     */
    fun poll(bot: CompanionBotEntity, owner: ServerPlayer): Boolean {
        if (isUnlocked) return true

        val now = bot.level().gameTime
        if (lastScanTick >= 0 && now - lastScanTick < SCAN_INTERVAL_TICKS) return false
        lastScanTick = now

        if (ownerInventoryHasTable(owner)) {
            latch(now, "player is carrying a crafting table")
            return true
        }

        val level = bot.level() as? ServerLevel ?: return false
        val tablePos = findNearbyTable(level, owner.blockPosition(), SCAN_RADIUS)
        if (tablePos != null) {
            latch(now, "player placed a crafting table at ${tablePos.toShortString()}")
            return true
        }

        return false
    }

    private fun latch(now: Long, why: String) {
        isUnlocked = true
        unlockedAtTick = now
        evidence = why
    }

    /** True when the player's own inventory holds a crafting table item. */
    private fun ownerInventoryHasTable(owner: ServerPlayer): Boolean {
        for (slot in 0 until owner.inventory.containerSize) {
            if (owner.inventory.getItem(slot).`is`(Items.CRAFTING_TABLE)) return true
        }
        return false
    }

    /**
     * Nearest crafting-table block within [radius] of [origin], or null.
     *
     * Searches a vertical band well below and above the player so a table in a basement or on
     * a first floor still counts, and only reads loaded chunks.
     */
    fun findNearbyTable(level: ServerLevel, origin: BlockPos, radius: Int): BlockPos? {
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                for (dy in -12..12) {
                    val pos = origin.offset(dx, dy, dz)
                    if (!level.hasChunkAt(pos)) continue
                    if (level.getBlockState(pos).block !== Blocks.CRAFTING_TABLE) continue
                    val distance = (dx * dx + dy * dy * 4 + dz * dz).toDouble()
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = pos
                    }
                }
            }
        }
        return best
    }

    /**
     * True when a crafting table is currently close enough to craft at.
     *
     * Distinct from [isUnlocked]: the latch is permanent knowledge, this is the present
     * opportunity. The crafting stage needs both — it may walk to a table it knows exists.
     */
    fun findReachableTable(level: ServerLevel, origin: BlockPos): BlockPos? =
        findNearbyTable(level, origin, REACHABLE_RADIUS)

    fun describe(): String = if (isUnlocked) "unlocked ($evidence)" else "locked ($evidence)"
}
