package com.qc.aeonis.neoforge.client

import com.qc.aeonis.entity.AeonisEntities
import net.neoforged.neoforge.client.event.EntityRenderersEvent

object AeonisEntityRenderers {
    fun register(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(AeonisEntities.COPPER_STALKER.get()) { ctx ->
            CopperStalkerEntityRenderer(ctx)
        }
        event.registerEntityRenderer(AeonisEntities.BODY.get()) { ctx ->
            BodyEntityRenderer(ctx)
        }
        event.registerEntityRenderer(AeonisEntities.HEROBRINE.get()) { ctx ->
            HerobrineEntityRenderer(ctx)
        }
        event.registerEntityRenderer(AeonisEntities.HUNTER.get()) { ctx ->
            HunterEntityRenderer(ctx)
        }
        event.registerEntityRenderer(AeonisEntities.COMPANION_BOT.get()) { ctx ->
            CompanionBotEntityRenderer(ctx)
        }
    }
}
