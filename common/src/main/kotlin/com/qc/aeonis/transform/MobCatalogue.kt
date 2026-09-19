package com.qc.aeonis.transform

import com.qc.aeonis.entity.AeonisEntities
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory

/**
 * The list of mobs a player may actually become, and how that list is grouped.
 *
 * This used to exist only as an inline filter inside `/transform random`, which meant the command
 * picker, the manual and (later) the in-game form browser each had to re-derive the same rules.
 * They now share this one definition, so a mob can never be offered by the browser and refused by
 * the command, or vice versa.
 */
object MobCatalogue {

    /**
     * True when [type] is a mob a player can be given.
     *
     * Excluded: the player itself, Aeonis' own body entity (becoming your own dropped body is a
     * recursive mess), anything the world cannot spawn, and `MISC`-category entities such as
     * lightning, markers and item frames, which have no locomotion to reproduce.
     */
    fun isPossessable(type: EntityType<*>): Boolean = try {
        type != net.minecraft.world.entity.EntityTypes.PLAYER &&
            type != AeonisEntities.BODY.get() &&
            type.canSummon() &&
            type.category != MobCategory.MISC &&
            !type.description.string.contains("Body", ignoreCase = true)
    } catch (_: Exception) {
        // A registry that is not ready yet must not take the client down; nothing is offered.
        false
    }

    /**
     * Every possessable mob, sorted by display name.
     *
     * Sorted rather than registry-ordered because the browser shows them to a human, and a stable
     * alphabetical order is the only ordering a player can navigate.
     */
    fun possessable(): List<EntityType<*>> = try {
        BuiltInRegistries.ENTITY_TYPE
            .filter { isPossessable(it) }
            .sortedBy { it.description.string.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * A named group of forms, used by the browser and by the manual.
     *
     * Groups come from the locomotion registry rather than from a hand-written classification, so
     * adding a mob to [MobMovementProfiles] files it correctly everywhere at once.
     */
    data class FormGroup(val kind: MovementKind, val title: String, val types: List<EntityType<*>>)

    /** Every form, grouped by how it moves. */
    fun grouped(): List<FormGroup> = possessable()
        .groupBy { MobMovementProfiles.profileFor(it).kind }
        .map { (kind, types) -> FormGroup(kind, titleFor(kind), types) }
        .sortedBy { it.kind.ordinal }

    private fun titleFor(kind: MovementKind): String = when (kind) {
        MovementKind.GROUND -> "Walks"
        MovementKind.FLYING -> "Flies"
        MovementKind.HOVERING -> "Hovers"
        MovementKind.AQUATIC -> "Swims"
        MovementKind.CLIMBING -> "Climbs"
        MovementKind.LEAPING -> "Hops"
    }

    /**
     * Everything the manual and the browser show about one form.
     *
     * The movement description and the ability list both come from the registries the server
     * drives from, so the documentation cannot promise a button that does not exist.
     */
    fun summary(type: EntityType<*>): MobAbilities.FormSummary = MobAbilities.summary(type)

    /** The form name a player would type, e.g. `cave_spider` for a Cave Spider. */
    fun token(type: EntityType<*>): String = try {
        BuiltInRegistries.ENTITY_TYPE.getKey(type)?.path ?: type.description.string
    } catch (_: Exception) {
        type.description.string
    }
}
