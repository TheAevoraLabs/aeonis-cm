package com.qc.aeonis.companion.progression

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.skill.ScaffoldingEngine
import com.qc.aeonis.companion.skill.SkillResult
import com.qc.aeonis.companion.skill.SkillStatus
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import kotlin.math.abs

/**
 * Helps enclose the player's shelter, building in the material the player already chose.
 *
 * ### Palette matching, and why it is the whole point
 * A companion that walls a player in with dirt next to their oak-plank house is a nuisance, so
 * before placing anything this class reads the **palette of blocks the player has already
 * placed** around the build site and builds in that material. The palette is sampled, not
 * invented: whatever the player used most within [PALETTE_RADIUS] wins, and the companion looks
 * in its own backpack for a matching block before falling back to anything solid.
 *
 * ### The plan is derived, not stored per block
 * A shelter plan is a pure function of the world — bed position (or the player's position when
 * only walls exist), a 5×5 footprint, two wall courses and one roof course. Recomputing it each
 * tick instead of caching it means the plan automatically follows the player as they move, and
 * cannot become a stale list of positions that no longer exist.
 */
class ShelterAssistant(private val bot: CompanionBotEntity) {

    /** Result of one assistance step. */
    data class Outcome(
        val status: SkillStatus,
        val message: String,
        val remainingBlocks: Int = 0
    )

    companion object {
        /** Radius around the build anchor sampled for the player's block palette. */
        const val PALETTE_RADIUS = 8

        /** Half-width of the assisted footprint, giving a 5×5 interior. */
        const val FOOTPRINT_RADIUS = 2

        /** Wall courses placed above the anchor level. */
        const val WALL_HEIGHT = 2

        /** Bed search radius around the player. */
        const val BED_RADIUS = 12

        /**
         * Blocks that count as construction material for palette purposes. Anything outside
         * this set is either not a wall (torches, doors, glass panes) or not player-chosen
         * decoration, so letting it into the palette would mislead the matching step.
         */
        val PALETTE_BLOCKS: Set<Block> = setOf(
            Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS,
            Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS,
            Blocks.COBBLESTONE, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE,
            Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.BRICKS, Blocks.SANDSTONE,
            Blocks.RED_SANDSTONE, Blocks.PRISMARINE, Blocks.NETHER_BRICKS, Blocks.TERRACOTTA,
            Blocks.DIRT, Blocks.PACKED_MUD
        )
    }

    /**
     * Builds the plan for this tick, or null when the player has not started anything the
     * companion should help with.
     */
    fun detectAnchor(owner: ServerPlayer): BlockPos? {
        val level = bot.level() as? ServerLevel ?: return null
        val bed = findNearestBed(level, owner.blockPosition())
        if (bed != null) return bed
        // No bed: only assist when the player is visibly building, otherwise the companion
        // starts walling in someone who is merely walking across a field.
        return if (playerIsBuilding(owner)) owner.blockPosition() else null
    }

    /** True when the player is holding a block, the strongest non-verbal building signal. */
    fun playerIsBuilding(owner: ServerPlayer): Boolean =
        owner.mainHandItem.item is BlockItem

    /** Nearest bed within [BED_RADIUS] of [origin], or null. */
    fun findNearestBed(level: ServerLevel, origin: BlockPos): BlockPos? {
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        for (dx in -BED_RADIUS..BED_RADIUS) {
            for (dz in -BED_RADIUS..BED_RADIUS) {
                for (dy in -6..6) {
                    val pos = origin.offset(dx, dy, dz)
                    if (!level.hasChunkAt(pos)) continue
                    if (level.getBlockState(pos).block !== Blocks.BED) continue
                    val distance = (dx * dx + dz * dz).toDouble() + abs(dy) * 4.0
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
     * The player's block palette near [anchor], most-used first.
     *
     * Ties are broken by distance from the anchor so the house the player is currently standing
     * in outranks a stray block from an older base.
     */
    fun detectPalette(level: ServerLevel, anchor: BlockPos): List<Item> {
        val counts = HashMap<Block, Int>()
        for (dx in -PALETTE_RADIUS..PALETTE_RADIUS) {
            for (dy in -4..6) {
                for (dz in -PALETTE_RADIUS..PALETTE_RADIUS) {
                    val pos = anchor.offset(dx, dy, dz)
                    if (!level.hasChunkAt(pos)) continue
                    val block = level.getBlockState(pos).block
                    if (!PALETTE_BLOCKS.contains(block)) continue
                    counts[block] = (counts[block] ?: 0) + 1
                }
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<Block, Int>> { it.value }.thenBy { it.key.name.string })
            .mapNotNull { it.key.asItem().takeIf { item -> item !== Items.AIR } }
    }

    /**
     * Positions the plan still needs filled, nearest-first, capped so one call cannot scan an
     * unbounded volume.
     */
    fun pendingPositions(level: ServerLevel, anchor: BlockPos, owner: ServerPlayer, limit: Int = 24): List<BlockPos> {
        val pending = ArrayList<BlockPos>(limit)
        val ownerFeet = owner.blockPosition()

        // Walls at anchor level and the course above, then the roof.
        for (course in 0 until WALL_HEIGHT) {
            val y = anchor.y + course
            for (dx in -FOOTPRINT_RADIUS..FOOTPRINT_RADIUS) {
                for (dz in -FOOTPRINT_RADIUS..FOOTPRINT_RADIUS) {
                    val onPerimeter = abs(dx) == FOOTPRINT_RADIUS || abs(dz) == FOOTPRINT_RADIUS
                    if (!onPerimeter) continue
                    val pos = BlockPos(anchor.x + dx, y, anchor.z + dz)
                    if (!shouldFill(level, pos, ownerFeet)) continue
                    pending.add(pos)
                    if (pending.size >= limit) return pending
                }
            }
        }

        val roofY = anchor.y + WALL_HEIGHT
        for (dx in -FOOTPRINT_RADIUS..FOOTPRINT_RADIUS) {
            for (dz in -FOOTPRINT_RADIUS..FOOTPRINT_RADIUS) {
                val pos = BlockPos(anchor.x + dx, roofY, anchor.z + dz)
                if (!shouldFill(level, pos, ownerFeet)) continue
                pending.add(pos)
                if (pending.size >= limit) return pending
            }
        }

        return pending
    }

    /**
     * True when [pos] needs a block: it is air, has something to stand on, and is not the cell
     * the player is occupying. Walling in the player is the one failure mode that would make
     * this feature worse than nothing.
     */
    private fun shouldFill(level: ServerLevel, pos: BlockPos, ownerFeet: BlockPos): Boolean {
        if (ownerFeet == pos || ownerFeet.above() == pos) return false
        if (!level.getBlockState(pos).isAir) return false
        if (level.hasChunkAt(pos.below()) && level.getBlockState(pos.below()).isAir) return false
        val occupied = level.getEntitiesOfClass(Player::class.java, AABB(pos)).isNotEmpty()
        return !occupied
    }

    /**
     * Performs one building action toward the shelter.
     *
     * Returns SUCCESS with no remaining work when the shell is complete, RUNNING while there is
     * more to place, and FAILURE when the companion has no material in the right palette and
     * cannot find any.
     */
    fun tick(owner: ServerPlayer): Outcome {
        val level = bot.level() as? ServerLevel ?: return Outcome(SkillStatus.FAILURE, "no server level")
        val anchor = detectAnchor(owner) ?: return Outcome(SkillStatus.IDLE, "player is not building")

        // Stand near the build site before placing anything, so placements are in reach.
        if (bot.blockPosition().distSqr(anchor) > 36.0) {
            bot.navigation.moveTo(anchor.x + 0.5, anchor.y.toDouble(), anchor.z + 0.5, 1.2)
            return Outcome(SkillStatus.RUNNING, "walking to the build site")
        }

        val pending = pendingPositions(level, anchor, owner)
        if (pending.isEmpty()) return Outcome(SkillStatus.SUCCESS, "shelter shell complete")

        val palette = detectPalette(level, anchor)
        val slot = selectPaletteSlot(palette)
        if (slot < 0) {
            val scavenge = bot.scaffoldingEngine.scavengeScaffoldBlocks()
            return Outcome(
                SkillStatus.FAILURE,
                if (scavenge.isFailure) "no matching building material available" else "gathering matching material"
            )
        }

        val target = pending.first()
        val placed = bot.placeFromSlot(target, slot, Direction.UP, ScaffoldingEngine.BRIDGE_PLACE_COOLDOWN)
        return if (placed) {
            Outcome(SkillStatus.RUNNING, "placed matching block at ${target.toShortString()}", pending.size - 1)
        } else {
            Outcome(SkillStatus.RUNNING, "waiting to place at ${target.toShortString()}", pending.size)
        }
    }

    /**
     * Backpack slot holding a block in the player's palette, falling back to any scaffold block
     * so the shell still gets finished when the matching material has run out.
     */
    fun selectPaletteSlot(palette: List<Item>): Int {
        val inventory = bot.inventory

        // Exact palette match, most-used material first.
        for (paletteItem in palette) {
            for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
                val stack = inventory.getItem(slot)
                if (!stack.isEmpty && stack.item === paletteItem) return slot
            }
        }

        // Close-enough match: same block family, e.g. a different plank species.
        if (palette.isNotEmpty()) {
            val tagMatch = bot.firstSlotMatching { stack ->
                bot.scaffoldingEngine.isScaffoldBlock(stack) && palette.any { preferred ->
                    val preferredBlock = (preferred as? BlockItem)?.block ?: return@any false
                    val candidateBlock = (stack.item as? BlockItem)?.block ?: return@any false
                    sameFamily(preferredBlock, candidateBlock)
                }
            }
            if (tagMatch >= 0) return tagMatch
        }

        return bot.scaffoldingEngine.scaffoldSlot()
    }

    /** True when two blocks share a material family (any planks, or any stone family). */
    private fun sameFamily(a: Block, b: Block): Boolean {
        if (a === b) return true
        if (PALETTE_PLANKS.contains(a) && PALETTE_PLANKS.contains(b)) return true
        return PALETTE_STONE.contains(a) && PALETTE_STONE.contains(b)
    }

    private val PALETTE_PLANKS: Set<Block> = setOf(
        Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
        Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS,
        Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS
    )

    private val PALETTE_STONE: Set<Block> = setOf(
        Blocks.COBBLESTONE, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE,
        Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.DEEPSLATE,
        Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.BRICKS
    )

    /**
     * Adapts an [Outcome] to the skill protocol.
     *
     * A normal method taking the outcome rather than an extension on it, so the single
     * implementation is reachable from the skill that owns the stage transition.
     */
    fun toSkillResult(outcome: Outcome, prefix: String): SkillResult = when (outcome.status) {
        SkillStatus.SUCCESS -> SkillResult(SkillStatus.SUCCESS, "$prefix: ${outcome.message}", cooldownTicks = 10)
        SkillStatus.FAILURE -> SkillResult(SkillStatus.FAILURE, "$prefix: ${outcome.message}", cooldownTicks = 60)
        SkillStatus.RUNNING -> SkillResult(SkillStatus.RUNNING, "$prefix: ${outcome.message}", cooldownTicks = 0)
        SkillStatus.IDLE -> SkillResult.IDLE
    }

    /** Convenience for callers that only need the leftover work count. */
    fun isShelterWorkPending(owner: ServerPlayer): Boolean {
        val level = bot.level() as? ServerLevel ?: return false
        val anchor = detectAnchor(owner) ?: return false
        return pendingPositions(level, anchor, owner, limit = 1).isNotEmpty()
    }

    /** True when a usable stack for [palette] exists. */
    fun hasMatchingMaterial(palette: List<Item>): Boolean = selectPaletteSlot(palette) >= 0

    /** Exposed for diagnostics: the item the companion would build with right now. */
    fun describePaletteCandidate(owner: ServerPlayer): String {
        val level = bot.level() as? ServerLevel ?: return "no level"
        val anchor = detectAnchor(owner) ?: return "player is not building"
        val palette = detectPalette(level, anchor)
        if (palette.isEmpty()) return "no player palette detected"
        val slot = selectPaletteSlot(palette)
        val itemName = if (slot < 0) "nothing in stock" else bot.inventory.getItem(slot).hoverName.string
        val paletteNames = palette.take(3).joinToString(" > ") { ItemStack(it).hoverName.string }
        return "$paletteNames → building with $itemName"
    }

    /** Leftover helper kept for callers that need a raw stack view of the palette match. */
    fun paletteStack(palette: List<Item>): ItemStack {
        val slot = selectPaletteSlot(palette)
        return if (slot < 0) ItemStack.EMPTY else bot.inventory.getItem(slot)
    }
}
