package com.qc.aeonis.network

import com.qc.aeonis.AeonisPossession
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity

object AeonisServerNetworking {
    
    fun register() {
        // Register clientbound payloads
        PayloadTypeRegistry.clientboundPlay().register(ControlModePayload.ID, ControlModePayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(AeonisNetworking.SoulModePayload.ID, AeonisNetworking.SoulModePayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(AeonisNetworking.SyncControllingPlayersPayload.ID, AeonisNetworking.SyncControllingPlayersPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(AeonisNetworking.ControlledEntityStatusPayload.ID, AeonisNetworking.ControlledEntityStatusPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(AeonisNetworking.OpenManhuntGuiPayload.ID, AeonisNetworking.OpenManhuntGuiPayload.CODEC)

        // Register serverbound payloads
        PayloadTypeRegistry.serverboundPlay().register(MobControlPayload.ID, MobControlPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(PossessPayload.ID, PossessPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ReleasePayload.ID, ReleasePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(AbilityPayload.ID, AbilityPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SelectedSlotPayload.ID, SelectedSlotPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(AeonisNetworking.SoulPossessPayload.ID, AeonisNetworking.SoulPossessPayload.CODEC)

        registerHandlers()
    }

    private fun registerHandlers() {
        // Handle MobControlPayload
        ServerPlayNetworking.registerGlobalReceiver(MobControlPayload.ID) { payload, context ->
            val player = context.player()
            context.server().execute {
                AeonisNetwork.updateControlInput(player.uuid, payload)
            }
        }

        // Handle PossessPayload
        ServerPlayNetworking.registerGlobalReceiver(PossessPayload.ID) { payload, context ->
            val player = context.player()
            context.server().execute {
                val entity = player.level().getEntity(payload.entityId)
                if (entity is LivingEntity) {
                    AeonisPossession.possess(player, entity)
                }
            }
        }

        // Handle ReleasePayload
        ServerPlayNetworking.registerGlobalReceiver(ReleasePayload.ID) { payload, context ->
            val player = context.player()
            context.server().execute {
                AeonisPossession.handleRelease(player, payload.x, payload.y, payload.z)
            }
        }

        // Handle AbilityPayload
        ServerPlayNetworking.registerGlobalReceiver(AbilityPayload.ID) { _, context ->
            // Ability logic could go here or in AeonisPossession
        }

        // Handle SelectedSlotPayload
        ServerPlayNetworking.registerGlobalReceiver(SelectedSlotPayload.ID) { payload, context ->
            // Handle slot change if needed
        }

        // Handle SoulPossessPayload
        ServerPlayNetworking.registerGlobalReceiver(AeonisNetworking.SoulPossessPayload.ID) { payload, context ->
            val player = context.player()
            context.server().execute {
                val entity = player.level().getEntity(payload.entityId)
                if (entity is LivingEntity) {
                    AeonisPossession.possess(player, entity)
                }
            }
        }
    }
}
