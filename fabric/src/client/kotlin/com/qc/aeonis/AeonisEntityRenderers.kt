package com.qc.aeonis

import com.qc.aeonis.entity.AeonisEntities
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry

object AeonisEntityRenderers {

    fun register() {
        EntityRendererRegistry.register(AeonisEntities.COPPER_STALKER.get()) { ctx ->
            CopperStalkerEntityRenderer(ctx)
        }
        // Register the Body entity renderer (uses player skin when available)
        EntityRendererRegistry.register(AeonisEntities.BODY.get()) { ctx ->
            BodyEntityRenderer(ctx)
        }
        // Register Herobrine entity renderer (uses humanoid player model)
        EntityRendererRegistry.register(AeonisEntities.HEROBRINE.get()) { ctx ->
            HerobrineEntityRenderer(ctx)
        }
        // Register Hunter entity renderer for Manhunt minigame
        EntityRendererRegistry.register(AeonisEntities.HUNTER.get()) { ctx ->
            HunterEntityRenderer(ctx)
        }
        EntityRendererRegistry.register(AeonisEntities.COMPANION_BOT.get()) { ctx ->
            CompanionBotEntityRenderer(ctx)
        }
    }
}
