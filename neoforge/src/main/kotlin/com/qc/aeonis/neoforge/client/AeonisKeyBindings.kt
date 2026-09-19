package com.qc.aeonis.neoforge.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier

object AeonisKeyBindings {
    private val CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aeonis", "controls"))

    val TELEPORT_KEY: KeyMapping = KeyMapping("key.aeonis.teleport", InputConstants.Type.KEYBOARD, InputConstants.KEY_T, CATEGORY)
    val POSSESS_KEY: KeyMapping = KeyMapping("key.aeonis.possess", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, CATEGORY)
    val RELEASE_KEY: KeyMapping = KeyMapping("key.aeonis.release", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CATEGORY)
    val ABILITY_KEY: KeyMapping = KeyMapping("key.aeonis.ability", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY)
    val SOUL_POSSESS_KEY: KeyMapping = KeyMapping("key.aeonis.soul_possess", InputConstants.Type.KEYBOARD, InputConstants.KEY_P, CATEGORY)
    val ZOOM_KEY: KeyMapping = KeyMapping("key.aeonis.zoom", InputConstants.Type.KEYBOARD, InputConstants.KEY_Z, CATEGORY)
    val FREECAM_TOGGLE_KEY: KeyMapping = KeyMapping("key.aeonis.freecam_toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, CATEGORY)
    val FREECAM_REMOTE_BREAK_KEY: KeyMapping = KeyMapping("key.aeonis.freecam_remote_break", InputConstants.Type.KEYBOARD, InputConstants.KEY_N, CATEGORY)

    fun register() {
        // In NeoForge, registration is usually handled via RegisterKeyMappingsEvent
    }
}
