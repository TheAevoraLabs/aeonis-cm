package com.qc.aeonis.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.entity.EntityTypes

object AeonisNetwork {
    private val controlledEntities = ConcurrentHashMap<UUID, Int>()
    private val latestControlInputs = ConcurrentHashMap<UUID, MobControlPayload>()

    fun isPlayerTransformed(uuid: UUID): Boolean = controlledEntities.containsKey(uuid)
    fun getControlledEntityId(uuid: UUID): Int? = controlledEntities[uuid]
    fun getLatestControlInput(uuid: UUID): MobControlPayload? = latestControlInputs[uuid]

    fun isEntityControlled(entityId: Int): Boolean {
        return controlledEntities.values.contains(entityId)
    }

    fun getControllingPlayerUuid(entityId: Int): UUID? {
        return controlledEntities.entries.find { it.value == entityId }?.key
    }

    fun getControlledEntitiesMap(): Map<UUID, Int> = controlledEntities

    fun setControlledEntity(player: ServerPlayer, entityId: Int, selectedSlot: Int = -1) {
        controlledEntities[player.uuid] = entityId
        // Platform specific packet sending will be handled by a helper
        com.qc.aeonis.platform.Services.load(com.qc.aeonis.platform.INetworkHelper::class.java)
            .sendControlMode(player, true, entityId, selectedSlot)
    }

    fun removeControlledEntity(player: ServerPlayer) {
        controlledEntities.remove(player.uuid)
        latestControlInputs.remove(player.uuid)
        com.qc.aeonis.platform.Services.load(com.qc.aeonis.platform.INetworkHelper::class.java)
            .sendControlMode(player, false, -1, -1)
    }

    fun clearControlState(uuid: UUID) {
        controlledEntities.remove(uuid)
        latestControlInputs.remove(uuid)
    }

    fun updateControlInput(uuid: UUID, input: MobControlPayload) {
        latestControlInputs[uuid] = input
    }

    fun broadcastControllingPlayers(server: net.minecraft.server.MinecraftServer) {
        val playerUUIDs = controlledEntities.keys().toList()
        com.qc.aeonis.platform.Services.load(com.qc.aeonis.platform.INetworkHelper::class.java)
            .broadcastControllingPlayers(server, playerUUIDs)
    }

    fun sendSoulModeStatus(player: ServerPlayer, enabled: Boolean) {
        com.qc.aeonis.platform.Services.load(com.qc.aeonis.platform.INetworkHelper::class.java)
            .sendSoulModeStatus(player, enabled)
    }

    fun canMobFly(mob: net.minecraft.world.entity.Mob): Boolean {
        val type = mob.type
        return type == net.minecraft.world.entity.EntityTypes.BAT ||
               type == net.minecraft.world.entity.EntityTypes.BEE ||
               type == net.minecraft.world.entity.EntityTypes.GHAST ||
               type == net.minecraft.world.entity.EntityTypes.PARROT ||
               type == net.minecraft.world.entity.EntityTypes.PHANTOM ||
               type == net.minecraft.world.entity.EntityTypes.VEX ||
               type == net.minecraft.world.entity.EntityTypes.WITHER ||
               type == net.minecraft.world.entity.EntityTypes.ALLAY
    }
}
