package com.qc.aeonis.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.entity.EntityTypes

/**
 * Authoritative registry of which player is controlling which entity.
 *
 * Lookups are hit every tick per controlled mob from the loader mixins, so both directions are
 * kept as direct map entries. Reverse lookups used to scan all values linearly, which is fine
 * for one morphed player and quadratic for a server full of them.
 */
object AeonisNetwork {
    private val controlledEntities = ConcurrentHashMap<UUID, Int>()
    private val entityToOwner = ConcurrentHashMap<Int, UUID>()
    private val latestControlInputs = ConcurrentHashMap<UUID, MobControlPayload>()

    fun isPlayerTransformed(uuid: UUID): Boolean = controlledEntities.containsKey(uuid)
    fun getControlledEntityId(uuid: UUID): Int? = controlledEntities[uuid]
    fun getLatestControlInput(uuid: UUID): MobControlPayload? = latestControlInputs[uuid]

    fun isEntityControlled(entityId: Int): Boolean = entityToOwner.containsKey(entityId)

    fun getControllingPlayerUuid(entityId: Int): UUID? = entityToOwner[entityId]

    fun getControlledEntitiesMap(): Map<UUID, Int> = controlledEntities

    fun setControlledEntity(player: ServerPlayer, entityId: Int, selectedSlot: Int = -1) {
        controlledEntities.put(player.uuid, entityId)?.let { previous ->
            if (previous != entityId) entityToOwner.remove(previous)
        }
        entityToOwner[entityId] = player.uuid
        // Platform specific packet sending will be handled by a helper
        com.qc.aeonis.platform.Services.load(com.qc.aeonis.platform.INetworkHelper::class.java)
            .sendControlMode(player, true, entityId, selectedSlot)
    }

    fun removeControlledEntity(player: ServerPlayer) {
        controlledEntities.remove(player.uuid)?.let { entityToOwner.remove(it) }
        latestControlInputs.remove(player.uuid)
        com.qc.aeonis.platform.Services.load(com.qc.aeonis.platform.INetworkHelper::class.java)
            .sendControlMode(player, false, -1, -1)
    }

    fun clearControlState(uuid: UUID) {
        controlledEntities.remove(uuid)?.let { entityToOwner.remove(it) }
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

    /**
     * True when the mob moves through the air under its own power.
     *
     * This used to be a hardcoded list of eight entity types that had drifted out of sync with
     * the movement code (the Ender Dragon and the Breeze were missing). It now reads the answer
     * from the locomotion profile, so there is one definition of "can this mob fly".
     */
    fun canMobFly(mob: net.minecraft.world.entity.Mob): Boolean {
        val kind = com.qc.aeonis.transform.MobMovementProfiles.profileFor(mob).kind
        return kind == com.qc.aeonis.transform.MovementKind.FLYING ||
            kind == com.qc.aeonis.transform.MovementKind.HOVERING
    }
}
