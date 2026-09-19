package com.qc.aeonis.dimension

import net.minecraft.server.level.ServerPlayer

object AncardDimensionRules {
    fun register() {}

    fun tick(server: net.minecraft.server.MinecraftServer) {
        for (player in server.playerList.players) {
            if (player.level().dimension() == AeonisDimensions.ANCARD) {
                onAncardTick(player)
            }
        }
    }

    private fun onAncardTick(player: ServerPlayer) {
        // Implementation
    }
}
