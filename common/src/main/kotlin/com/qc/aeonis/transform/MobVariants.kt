package com.qc.aeonis.transform

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ConversionParams
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob

/**
 * Outcome of applying a list of variant tokens to a transformed mob.
 *
 * Variants are user input, so every token ends up in exactly one bucket. Nothing is
 * silently dropped: unknown tokens and tokens that do not apply to the chosen mob are
 * reported back so the command can tell the player what went wrong.
 */
data class VariantReport(
    val applied: List<String> = emptyList(),
    val unknown: List<String> = emptyList(),
    val inapplicable: List<String> = emptyList(),
    val failed: List<String> = emptyList()
) {
    val hasProblems: Boolean
        get() = unknown.isNotEmpty() || inapplicable.isNotEmpty() || failed.isNotEmpty()

    fun appliedSummary(): String = applied.joinToString(", ")

    /** Human-readable description of every token that did not take effect. */
    fun problems(): String? {
        if (!hasProblems) return null
        val parts = mutableListOf<String>()
        if (inapplicable.isNotEmpty()) {
            parts += "${inapplicable.joinToString(", ")} not available for this mob"
        }
        if (unknown.isNotEmpty()) {
            parts += "${unknown.joinToString(", ")} unknown"
        }
        if (failed.isNotEmpty()) {
            parts += "${failed.joinToString(", ")} could not be applied"
        }
        return parts.joinToString("; ")
    }

    operator fun plus(other: VariantReport): VariantReport = VariantReport(
        applied + other.applied,
        unknown + other.unknown,
        inapplicable + other.inapplicable,
        failed + other.failed
    )
}

/**
 * Mutable handle passed to a variant so it can act on (and replace) the mob being built.
 *
 * Some variants are entity conversions rather than field tweaks (zombie -> husk). Those
 * create a brand new mob, so [adopt] lets the applier keep operating on the live mob while
 * the service re-binds its session to the replacement.
 */
class VariantContext(val level: ServerLevel, mob: Mob) {
    var mob: Mob = mob
        internal set

    /** Records that the variant replaced the entity we were operating on. */
    fun adopt(replacement: Mob) {
        mob = replacement
    }

    /**
     * Converts the current mob into [type] using the vanilla conversion path, which preserves
     * NBT, equipment, custom name and data components. The previous mob is discarded by
     * vanilla. Returns the replacement, or null when conversion was not possible.
     */
    fun <T : Mob> convertTo(type: EntityType<T>): T? {
        val existing = mob
        if (existing.type == type) {
            @Suppress("UNCHECKED_CAST")
            return existing as T
        }
        val params = ConversionParams.single(existing, true, true)
        val converted = existing.convertTo(type, params, EntitySpawnReason.COMMAND) { }
            ?: return null
        adopt(converted)
        return converted
    }
}

/**
 * A single self-describing mob variant.
 *
 * [appliesTo] is what makes the error messages precise: a token that names a real variant
 * but targets the wrong mob is reported as "inapplicable" rather than "unknown".
 */
data class MobVariant(
    val id: String,
    val summary: String,
    val appliesTo: (Mob) -> Boolean,
    val apply: (VariantContext) -> Boolean,
    val aliases: Set<String> = emptySet()
) {
    fun matches(token: String): Boolean =
        id.equals(token, ignoreCase = true) || aliases.any { it.equals(token, ignoreCase = true) }
}

/**
 * Registry of every variant the transform system understands.
 *
 * Registration is explicit (see [AeonisMobVariants]) so adding support for a new mob never
 * means editing the command layer or duplicating branching logic.
 */
object MobVariants {
    private val variants = mutableListOf<MobVariant>()

    fun register(variant: MobVariant) {
        variants.removeAll { it.id.equals(variant.id, ignoreCase = true) }
        variants += variant
    }

    fun registerAll(vararg newVariants: MobVariant) {
        newVariants.forEach(::register)
    }

    fun all(): List<MobVariant> = variants.toList()

    /** All ids a player could type, sorted, used for help text and tab completion. */
    fun allIds(): List<String> = variants.map { it.id }.sorted()

    /** True when the token names a registered variant, regardless of the target mob. */
    fun exists(token: String): Boolean = variants.any { it.matches(token) }

    fun resolve(mob: Mob, token: String): MobVariant? =
        variants.firstOrNull { it.matches(token) && it.appliesTo(mob) }

    fun availableFor(mob: Mob): List<MobVariant> = variants.filter { it.appliesTo(mob) }
}

/**
 * Applies variant tokens to a mob one at a time, following conversions as they happen.
 *
 * When a variant converts the mob into a different entity the [onReplaced] callback fires so
 * the caller can re-point its session, network control entry and camera at the new entity.
 */
class VariantApplier(
    private val level: ServerLevel,
    initial: Mob,
    private val onReplaced: (Mob, Mob) -> Unit
) {
    /** The mob as it currently stands, which may differ from the one passed in after a conversion. */
    var currentMob: Mob = initial
        private set

    fun apply(tokens: List<String>): VariantReport {
        var report = VariantReport()
        for (token in tokens.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val variant = MobVariants.resolve(currentMob, token)
            if (variant == null) {
                report = if (MobVariants.exists(token)) {
                    report.copy(inapplicable = report.inapplicable + token)
                } else {
                    report.copy(unknown = report.unknown + token)
                }
                continue
            }

            val before = currentMob
            val context = VariantContext(level, before)
            val success = try {
                variant.apply(context)
            } catch (_: Exception) {
                false
            }

            if (!success) {
                report = report.copy(failed = report.failed + token)
                continue
            }

            report = report.copy(applied = report.applied + variant.id)
            if (context.mob !== before) {
                currentMob = context.mob
                onReplaced(before, currentMob)
            }
        }
        return report
    }
}
