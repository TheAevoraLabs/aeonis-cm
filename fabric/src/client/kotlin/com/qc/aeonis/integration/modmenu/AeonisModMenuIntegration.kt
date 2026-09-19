package com.qc.aeonis.integration.modmenu

import com.qc.aeonis.screen.AeonisSettingsScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/**
 * Mod Menu entry point.
 *
 * This used to return the parent screen unchanged, so the Aeonis config button in Mod Menu
 * silently did nothing. It now opens the hub, which is also reachable from Options -> Aeonis.
 */
class AeonisModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> AeonisSettingsScreen(parent) }
}
