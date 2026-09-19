package com.qc.aeonis.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import java.util.UUID;
import java.util.List;

public interface INetworkHelper {
    void sendOpenGui(ServerPlayer player);
    void sendControlMode(ServerPlayer player, boolean enabled, int mobId, int selectedSlot);
    void sendSoulModeStatus(ServerPlayer player, boolean enabled);
    void broadcastControllingPlayers(MinecraftServer server, List<UUID> playerUUIDs);
    
    // LLM related
    void sendLlmStatus(ServerPlayer player, String message, boolean success, String statusType);
    void sendLlmOpenGui(ServerPlayer player, String provider, String model, boolean hasApiKey, 
                        double temperature, int maxTokens, int maxBlocksPerMinute, 
                        int maxEditRadius, boolean griefProtection);
}
