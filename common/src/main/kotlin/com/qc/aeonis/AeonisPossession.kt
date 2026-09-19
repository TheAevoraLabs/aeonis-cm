package com.qc.aeonis

import com.qc.aeonis.item.AeonisItems
import com.qc.aeonis.entity.BodyEntity
import com.qc.aeonis.network.AeonisNetwork
import com.qc.aeonis.transform.PlayerRestoreState
import com.qc.aeonis.entity.AeonisEntities
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.GameType
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntitySpawnReason
import java.util.UUID

object AeonisPossession {
    val playerBodies = mutableMapOf<UUID, BodyEntity>()
    val activePossessions = mutableMapOf<UUID, Boolean>()
    val bodyPositions = mutableMapOf<UUID, DoubleArray>()
    val mobInventories = mutableMapOf<Int, Array<ItemStack?>>()
    private val inventoryBackups = mutableMapOf<UUID, Array<ItemStack?>>()
    private val restoreStates = mutableMapOf<UUID, PlayerRestoreState>()
    private val playerPreviousSelectedSlot = mutableMapOf<UUID, Int>()
    private val mobLastSelectedSlot = mutableMapOf<Int, Int>()

    fun register() {
        // Shared logic registration
    }

    fun onPlayerJoin(player: ServerPlayer, server: MinecraftServer) {}
    fun onPlayerDisconnect(player: ServerPlayer, server: MinecraftServer) {}

    fun isOriginalModeSpectator(uuid: UUID): Boolean {
        return restoreStates[uuid]?.gameMode == GameType.SPECTATOR
    }

    fun isActivelyPossessing(uuid: UUID): Boolean = activePossessions[uuid] == true

    fun handleRelease(player: ServerPlayer, x: Double, y: Double, z: Double) {
        release(player)
    }

    fun possess(player: ServerPlayer, target: LivingEntity): Boolean {
        if (activePossessions[player.uuid] == true) return false
        
        val pos = player.position()
        bodyPositions[player.uuid] = doubleArrayOf(pos.x, pos.y, pos.z)
        restoreStates[player.uuid] = PlayerRestoreState.capture(player)
        
        val body = AeonisEntities.BODY.get().create(player.level(), EntitySpawnReason.COMMAND) ?: return false
        body.setPos(pos.x, pos.y, pos.z)
        body.setOwnerUuid(player.uuid)
        player.level().addFreshEntity(body)
        playerBodies[player.uuid] = body
        
        activePossessions[player.uuid] = true
        AeonisNetwork.setControlledEntity(player, target.id)
        player.setGameMode(GameType.SURVIVAL)
        
        return true
    }

    fun release(player: ServerPlayer) {
        if (activePossessions[player.uuid] != true) return

        AeonisNetwork.removeControlledEntity(player)
        val body = playerBodies.remove(player.uuid)
        body?.discard()

        val pos = bodyPositions.remove(player.uuid)
        if (pos != null) {
            player.teleportTo(pos[0], pos[1], pos[2])
        }

        // Restore the whole snapshot rather than only the gamemode: releasing used to leave the
        // player invisible and still carrying the possessed mob's max health.
        val restore = restoreStates.remove(player.uuid) ?: PlayerRestoreState.safeDefault()
        restore.applyTo(player)
        activePossessions[player.uuid] = false
    }
}
