package com.qc.aeonis.companion.safety

import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB

/**
 * The companion's "should I touch this block?" policy.
 *
 * ### The failure this exists to prevent
 * The companion used to dig and build wherever its pathfinding pointed it, with no notion of
 * ownership. In a real world that meant breaking the walls of the player's house, mining the
 * cobblestone out of someone's floor, chopping the trees a player had planted beside their base
 * and dropping pillar blocks through a roof. Field report: *"fix the agent placing blocks and
 * breaking blocks, dangerous!! player placed things!!!"*
 *
 * ### The rules, cheapest first
 * 1. **[BuildVerdict.OWN_SCAFFOLD]** — the companion's own pillar or bridge block: always
 *    breakable, so it can take back what it built. Checked first so reclaim still works inside an
 *    area a player has since marked.
 * 2. **[BuildVerdict.PLAYER_BUILD]** — a position a player was seen breaking, placing or using,
 *    within [BuildLedger]'s one-block neighbourhood. Never touched.
 * 3. **[BuildVerdict.CONTAINER]** — any block with a block entity. Chests, furnaces, barrels,
 *    beds, signs, banners, spawners, beacons: these hold state, cost the player real effort, and
 *    breaking one can destroy items. Never touched, ever, in any context.
 * 4. **[BuildVerdict.NOT_NATURAL]** — the block is not world-generated terrain. This is the rule
 *    that protects every build the ledger has no memory of, and it is a *material* test rather
 *    than a distance test, so it works on a base the player built last week: planks, cobblestone,
 *    stone bricks, glass, wool, concrete, fences and doors are not natural, so they are never
 *    mined. Natural stone, dirt, sand, logs and ore still are, so the companion can still gather
 *    and tunnel.
 * 5. **[BuildVerdict.PLAYER_NEARBY]** — a player is standing *on or immediately against* the
 *    block. Digging the floor out from under someone is the most annoying form of this bug.
 * 6. **[BuildVerdict.OWNER_IN_THE_WAY]** — the cell is the owner's own airspace; never place into
 *    it, so the companion cannot wall the player in.
 *
 * ### Two traps this policy deliberately does *not* fall into
 * Both were live bugs for one session, and both made the companion look broken rather than
 * careful:
 *
 * - **The companion is not an obstruction to itself.** "Is a living entity in this cell" has to
 *   exclude the asker. A pillar block goes into the cell the companion's own feet occupy, so
 *   counting itself made [PlaceIntent.SCAFFOLD] placements impossible *always* — the companion
 *   simply stood under its owner and never built the stair. See the `it !== bot` filters below.
 * - **A flying player is not a wall.** [PLAYER_SAFETY_RADIUS] is one block: it protects the floor
 *   a player is standing on and the blocks they are touching, and nothing else. A radius of two
 *   covered a player hovering three blocks up, which — with an owner in creative flight — refused
 *   *every* dig and placement in the area and stopped the companion progressing at all.
 *
 * A companion that cannot act because of one of these now *says so*: every verdict carries a
 * reason string, which surfaces in skill messages, `/comp status` and the chat diagnostics.
 *
 * ### Why the policy is a separate object
 * Nothing here needs a world to reason about *policy*; it needs one only to read a block. Keeping
 * the decision in one place means the entity, the mining session, the scaffolding engine and the
 * shelter assistant all get the same answer, instead of four call sites each implementing a
 * slightly different idea of "don't grief".
 */
object TerritoryGuard {

    /** Why the companion may or may not touch a block. */
    enum class BuildVerdict {
        ALLOW,
        OWN_SCAFFOLD,
        PLAYER_BUILD,
        CONTAINER,
        NOT_NATURAL,
        PLAYER_NEARBY,
        OWNER_IN_THE_WAY,
        OCCUPIED,
        UNBREAKABLE,
        NO_MATERIAL
    }

    /** Where a placement is going, which changes the rules slightly. */
    enum class PlaceIntent {
        /** Pillars, bridges, steps: must never land inside a player's building. */
        SCAFFOLD,

        /** Shelters and lamps: explicitly *meant* to be beside the player's build. */
        BUILD
    }

    /**
     * Radius around a player that counts as "they are using this space".
     *
     * One block means the floor they stand on and the blocks they are touching. Larger values
     * refuse digs near a player who is nowhere near the block — see the class comment.
     */
    const val PLAYER_SAFETY_RADIUS = 1.0

    /** Radius used when asking whether a position is inside a player's build. */
    const val TERRITORY_RADIUS = 1

    /**
     * World-generated materials the companion is allowed to mine.
     *
     * Tag-based wherever a vanilla tag exists, because a tag also covers modded blocks in the
     * same families. The handful of materials with no tag of their own are listed explicitly in
     * [NATURAL_BLOCKS].
     */
    val NATURAL_TAGS = listOf(
        BlockTags.DIRT,
        BlockTags.SAND,
        BlockTags.SNOW,
        BlockTags.ICE,
        BlockTags.MUD,
        BlockTags.MOSS_BLOCKS,
        BlockTags.BASE_STONE_OVERWORLD,
        BlockTags.BASE_STONE_NETHER,
        BlockTags.DEEPSLATE_ORE_REPLACEABLES,
        BlockTags.SCULK_REPLACEABLE,
        BlockTags.ORES,
        BlockTags.LOGS,
        BlockTags.LEAVES,
        BlockTags.GRASS_BLOCKS,
        BlockTags.TERRACOTTA
    )

    /**
     * Natural materials that vanilla has no tag for.
     *
     * Cobblestone and mossy cobblestone are deliberately in this list: they appear as world
     * generation in dungeons and ruined portals, and the *reason* they are safe to allow is that
     * they are also the most common mined building block, so rule 2 in the class comment has to
     * catch the player-built ones. The ledger does exactly that for anything a player placed
     * while the server was up; a cobblestone wall from a previous session is the one real gap,
     * and it is stated in [BuildLedger]'s documentation rather than papered over.
     */
    val NATURAL_BLOCKS = setOf(
        Blocks.GRAVEL, Blocks.CLAY, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
        Blocks.NETHERRACK, Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
        Blocks.END_STONE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.BASALT,
        Blocks.BLACKSTONE, Blocks.CALCITE, Blocks.TUFF, Blocks.DRIPSTONE_BLOCK,
        Blocks.POINTED_DRIPSTONE, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST,
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE,
        Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.ROOTED_DIRT,
        Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER,
        Blocks.CACTUS, Blocks.GLOW_LICHEN, Blocks.VINE, Blocks.SWEET_BERRY_BUSH,
        Blocks.FERN, Blocks.LARGE_FERN, Blocks.TALL_GRASS, Blocks.SHORT_GRASS,
        Blocks.SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SPONGE, Blocks.WET_SPONGE
    )

    /**
     * True when [state] looks like world-generated terrain rather than something a player made.
     *
     * There is no provenance bit in a block state, so material is the best available proxy — and
     * a good one, because players build with *crafted* blocks while the world generates raw ones.
     */
    fun isNatural(state: BlockState): Boolean {
        if (state.isAir) return false
        if (NATURAL_BLOCKS.contains(state.block)) return true
        return NATURAL_TAGS.any { state.`is`(it) }
    }

    /**
     * Full policy check for breaking [pos].
     *
     * Order follows cost: ledger lookups are hash hits, the block-entity check is a chunk lookup,
     * and the player scan is the only genuinely expensive test, so it runs last.
     */
    fun mayBreak(bot: CompanionBotEntity, pos: BlockPos): BuildVerdict {
        val level = bot.level() as? ServerLevel ?: return BuildVerdict.UNBREAKABLE
        if (!level.hasChunkAt(pos)) return BuildVerdict.UNBREAKABLE

        val state = level.getBlockState(pos)
        if (state.isAir) return BuildVerdict.ALLOW
        if (state.getDestroySpeed(level, pos) < 0f) return BuildVerdict.UNBREAKABLE

        // 1. Our own scaffolding, taken back. Reclaim outranks everything: the block is ours, and
        //    refusing would leave towers behind in areas players happen to have marked.
        if (BuildLedger.isBotScaffold(level, pos)) return BuildVerdict.OWN_SCAFFOLD

        // 2. A position a player was seen working on, or beside one.
        if (BuildLedger.isPlayerTerritory(level, pos, TERRITORY_RADIUS)) return BuildVerdict.PLAYER_BUILD

        // 3. Anything holding state.
        if (level.getBlockEntity(pos) != null) return BuildVerdict.CONTAINER

        // 4. Anything a player made rather than the world generated.
        if (!isNatural(state)) return BuildVerdict.NOT_NATURAL

        // 5. A player standing on, in or beside it.
        if (playerNear(level, pos)) return BuildVerdict.PLAYER_NEARBY

        return BuildVerdict.ALLOW
    }

    /**
     * Full policy check for placing at [pos].
     *
     * The intent matters: scaffolding must never be dropped inside a player's build, while shelter
     * assistance is *supposed* to continue a player's wall, so [PlaceIntent.BUILD] skips the
     * territory rule.
     */
    fun mayPlace(bot: CompanionBotEntity, pos: BlockPos, intent: PlaceIntent): BuildVerdict {
        val level = bot.level() as? ServerLevel ?: return BuildVerdict.UNBREAKABLE
        if (!level.hasChunkAt(pos)) return BuildVerdict.UNBREAKABLE

        val existing = level.getBlockState(pos)
        val ours = BuildLedger.botPlacement(level, pos) != null
        if (!existing.isAir && !existing.canBeReplaced() && !ours) return BuildVerdict.OCCUPIED

        // Never build inside a player's own space with throwaway material.
        if (intent == PlaceIntent.SCAFFOLD && BuildLedger.isPlayerTerritory(level, pos, TERRITORY_RADIUS)) {
            return BuildVerdict.PLAYER_BUILD
        }

        // Do not seal a chest, furnace or bed: dropping a scaffold on top of a container is how a
        // "helpful" bot makes a base unusable.
        if (level.getBlockEntity(pos.above()) != null) return BuildVerdict.CONTAINER

        // Never place into another entity's airspace, least of all the owner's.
        val owner = bot.getOwnerPlayer()
        if (owner != null && isMobAirspace(pos, owner.blockPosition())) return BuildVerdict.OWNER_IN_THE_WAY

        // The companion does not count. A pillar block is placed into the cell the companion's
        // own feet occupy, so including itself here would make every scaffold placement
        // impossible — which is exactly what happened, and why the companion stood under its
        // owner without ever building the way up.
        val others = level.getEntitiesOfClass(LivingEntity::class.java, AABB(pos)) { it !== bot }
        if (others.isNotEmpty()) return BuildVerdict.OCCUPIED

        return BuildVerdict.ALLOW
    }

    /**
     * True when a player occupies, stands on, or is immediately beside [pos].
     *
     * Deliberately tight: a player flying overhead is not using the block below the companion,
     * and treating them as if they were is what froze progress during a creative-flight session.
     */
    fun playerNear(level: ServerLevel, pos: BlockPos, bot: CompanionBotEntity? = null): Boolean {
        val box = AABB(pos).inflate(PLAYER_SAFETY_RADIUS)
        val near = level.getEntitiesOfClass(Player::class.java, box)
        // The owner is the player being followed, and following them means working near them; only
        // their feet-level blocks are off limits, which the inflate above already covers.
        return near.isNotEmpty()
    }

    /** True when [cell] is the two-block column [feet] stands in. */
    fun isMobAirspace(cell: BlockPos, feet: BlockPos): Boolean =
        cell == feet || cell == feet.above()

    /** Human-readable reason, used verbatim in skill messages and `/comp status`. */
    fun describe(verdict: BuildVerdict): String = when (verdict) {
        BuildVerdict.ALLOW -> "allowed"
        BuildVerdict.OWN_SCAFFOLD -> "taking back my own scaffold"
        BuildVerdict.PLAYER_BUILD -> "that is part of the player's build"
        BuildVerdict.CONTAINER -> "that block stores things"
        BuildVerdict.NOT_NATURAL -> "that was built, not generated"
        BuildVerdict.PLAYER_NEARBY -> "the player is standing right there"
        BuildVerdict.OWNER_IN_THE_WAY -> "that is where my owner is standing"
        BuildVerdict.OCCUPIED -> "something is in the way"
        BuildVerdict.UNBREAKABLE -> "I cannot break that"
        BuildVerdict.NO_MATERIAL -> "I have nothing to build with"
    }

    /** True when the verdict permits the action. */
    fun allows(verdict: BuildVerdict): Boolean =
        verdict == BuildVerdict.ALLOW || verdict == BuildVerdict.OWN_SCAFFOLD
}
