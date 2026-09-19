package com.qc.aeonis.transform

/**
 * Optional bridges to mob state that vanilla only exposes through private fields.
 *
 * Each interface is implemented by a loader-specific mixin (Fabric: see the `mixin` package).
 * Common code always reaches them through a safe cast, so a loader that does not provide a
 * bridge degrades to "variant unavailable" instead of failing to compile or crashing at runtime.
 *
 * Method names avoid the usual `mod$name` mixin convention because Kotlin does not allow `$`
 * in identifiers; the `aeonis` prefix keeps them collision-free with vanilla members instead.
 *
 * Adding a new bridge is deliberately cheap: declare the interface here, implement it with a
 * small accessor mixin per loader, and register a [MobVariant] that casts to it.
 */

/** Exposes `Creeper.DATA_IS_POWERED`. */
interface CreeperPowerBridge {
    fun aeonisApplyPowered(powered: Boolean)
}

/** Exposes `Axolotl.DATA_VARIANT`. */
interface AxolotlVariantBridge {
    fun aeonisApplyVariantId(id: Int)
}

/** Exposes `Parrot.DATA_VARIANT_ID`. */
interface ParrotVariantBridge {
    fun aeonisApplyVariantId(id: Int)
}
