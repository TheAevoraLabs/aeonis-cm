package com.qc.aeonis.neoforge

import com.qc.aeonis.network.AeonisNetworking
import com.qc.aeonis.network.ControlModePayload
import com.qc.aeonis.platform.INetworkHelper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class NeoForgeNetworkHelper : INetworkHelper {
    override fun sendOpenGui(player: ServerPlayer) {
        // Implementation
    }

    override fun sendControlMode(player: ServerPlayer, enabled: Boolean, mobId: Int, selectedSlot: Int) {
        player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(ControlModePayload(enabled, mobId, selectedSlot)))
    }

    override fun sendSoulModeStatus(player: ServerPlayer, enabled: Boolean) {
        player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(AeonisNetworking.SoulModePayload(enabled)))
    }

    override fun broadcastControllingPlayers(server: MinecraftServer, playerUUIDs: MutableList<UUID>) {
        val payload = com.qc.aeonis.network.AeonisNetworking.SyncControllingPlayersPayload(playerUUIDs)
        server.playerList.players.forEach { player ->
            player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload))
        }
    }

    override fun sendLlmStatus(player: ServerPlayer, message: String, success: Boolean, statusType: String) {
        val type = com.qc.aeonis.llm.network.payloads.LlmStatusUpdatePayload.StatusType.valueOf(statusType)
        player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(com.qc.aeonis.llm.network.payloads.LlmStatusUpdatePayload(type, success, message)))
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
        player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(com.qc.aeonis.llm.network.payloads.LlmOpenGuiPayload(
            provider, model, hasApiKey, temperature, maxTokens, maxBlocksPerMinute, maxEditRadius, griefProtection
        )))
    }
}
