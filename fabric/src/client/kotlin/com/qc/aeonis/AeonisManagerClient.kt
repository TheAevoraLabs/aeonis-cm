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

		try {
			val field = net.minecraft.client.gui.screens.MenuScreens::class.java.getDeclaredField("SCREENS")
			field.isAccessible = true
			@Suppress("UNCHECKED_CAST")
			val screens = field.get(null) as MutableMap<net.minecraft.world.inventory.MenuType<*>, Any>
			screens[com.qc.aeonis.inventory.AeonisMenus.COMPANION_MENU.get()] = net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor { menu: com.qc.aeonis.companion.inventory.CompanionMenu, playerInv, title ->
				com.qc.aeonis.client.gui.CompanionScreen(menu, playerInv, title)
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}
}
