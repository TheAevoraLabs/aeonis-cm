package com.qc.aeonis.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier

/**
 * Every key Aeonis binds.
 *
 * The three ability keys map one-to-one onto the ability slots a mob advertises, so what the HUD
 * shows is exactly what each button does. They are ordinary vanilla key mappings, which means
 * players can rebind them from Options -> Controls like any other key.
 */
object AeonisKeyBindings {
    private val CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aeonis", "controls"))

    // Transform lifecycle
    val POSSESS_KEY: KeyMapping = KeyMapping("key.aeonis.possess", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, CATEGORY)
    val RELEASE_KEY: KeyMapping = KeyMapping("key.aeonis.release", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CATEGORY)
    val SOUL_POSSESS_KEY: KeyMapping = KeyMapping("key.aeonis.soul_possess", InputConstants.Type.KEYBOARD, InputConstants.KEY_P, CATEGORY)

    // Mob abilities: one key per slot
    val ABILITY_PRIMARY_KEY: KeyMapping = KeyMapping("key.aeonis.ability_primary", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY)
    val ABILITY_SECONDARY_KEY: KeyMapping = KeyMapping("key.aeonis.ability_secondary", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, CATEGORY)
    val ABILITY_TERTIARY_KEY: KeyMapping = KeyMapping("key.aeonis.ability_tertiary", InputConstants.Type.KEYBOARD, InputConstants.KEY_C, CATEGORY)

    // Freecam
    val ZOOM_KEY: KeyMapping = KeyMapping("key.aeonis.zoom", InputConstants.Type.KEYBOARD, InputConstants.KEY_Z, CATEGORY)
    val FREECAM_TOGGLE_KEY: KeyMapping = KeyMapping("key.aeonis.freecam_toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, CATEGORY)
    val FREECAM_REMOTE_BREAK_KEY: KeyMapping = KeyMapping("key.aeonis.freecam_remote_break", InputConstants.Type.KEYBOARD, InputConstants.KEY_N, CATEGORY)

    /** Kept for callers that still ask for "the" ability key. */
    val ABILITY_KEY: KeyMapping get() = ABILITY_PRIMARY_KEY

    /**
     * Key mappings are declared as statics and read directly, which is how Fabric resolves them
     * for a client-only initialiser. There is nothing to do at registration time in this port.
     */
    fun register() {
    }
}
