package com.qc.aeonis.fabric

import com.qc.aeonis.llm.network.LlmNetworking
import com.qc.aeonis.network.AeonisNetworking
import com.qc.aeonis.network.ControlModePayload
import com.qc.aeonis.platform.INetworkHelper
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class FabricNetworkHelper : INetworkHelper {
    override fun sendOpenGui(player: ServerPlayer) {
        LlmNetworking.sendOpenGui(player)
    }

    override fun sendControlMode(player: ServerPlayer, enabled: Boolean, mobId: Int, selectedSlot: Int) {
        ServerPlayNetworking.send(player, ControlModePayload(enabled, mobId, selectedSlot))
    }

    override fun sendSoulModeStatus(player: ServerPlayer, enabled: Boolean) {
        ServerPlayNetworking.send(player, AeonisNetworking.SoulModePayload(enabled))
    }

    override fun broadcastControllingPlayers(server: MinecraftServer, playerUUIDs: MutableList<UUID>) {
        val payload = com.qc.aeonis.network.AeonisNetworking.SyncControllingPlayersPayload(playerUUIDs)
        server.playerList.players.forEach { player ->
            ServerPlayNetworking.send(player, payload)
        }
    }

    override fun sendLlmStatus(player: ServerPlayer, message: String, success: Boolean, statusType: String) {
        val type = com.qc.aeonis.llm.network.payloads.LlmStatusUpdatePayload.StatusType.valueOf(statusType)
        ServerPlayNetworking.send(player, com.qc.aeonis.llm.network.payloads.LlmStatusUpdatePayload(type, success, message))
    }

    override fun sendLlmOpenGui(
        player: ServerPlayer,
        provider: String,
        model: String,
        hasApiKey: Boolean,
        temperature: Double,
        maxTokens: Int,
        maxBlocksPerMinute: Int,
        maxEditRadius: Int,
        griefProtection: Boolean
    ) {
        ServerPlayNetworking.send(player, com.qc.aeonis.llm.network.payloads.LlmOpenGuiPayload(
            provider, model, hasApiKey, temperature, maxTokens, maxBlocksPerMinute, maxEditRadius, griefProtection
        ))
    }
}
