package com.qc.aeonis

import com.qc.aeonis.client.AeonisKeyBindings
import com.qc.aeonis.client.dimension.AncardLightningEvents
import com.qc.aeonis.client.dimension.AncardPostProcessing
import com.qc.aeonis.client.hud.AeonisAbilityHud
import com.qc.aeonis.network.AeonisClientNetworking
import com.qc.aeonis.screen.AeonisClientSettings
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.resources.Identifier

object AeonisManagerClient : ClientModInitializer {
	override fun onInitializeClient() {
		// Preferences are read from disk before anything renders, so the first frame already
		// respects them instead of flashing the defaults.
		AeonisClientSettings.load()
		AeonisKeyBindings.register()
		AeonisClientNetworking.register()
		AeonisEntityRenderers.register()
		com.qc.aeonis.llm.LlmClientNetworking.register()

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("aeonis", "ability_hud"), AeonisAbilityHud)
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("aeonis", "ancard_post_processing"), AncardPostProcessing)
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("aeonis", "ancard_lightning_events"), AncardLightningEvents)
	}
}
