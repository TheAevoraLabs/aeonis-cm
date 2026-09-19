package com.qc.aeonis.neoforge.network

import com.qc.aeonis.neoforge.client.AeonisKeyBindings
import com.qc.aeonis.network.*
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.UUID

object AeonisClientNetworking {
    private var isControlling = false
    private var controlledMobId: Int = -1
    private val controllingPlayerUUIDs = mutableSetOf<UUID>()
    private var wasAttacking = false
    private var wasTeleporting = false
    private var attackStartTime: Long = 0L
    private var attackTriggered = false
    private const val MELEE_CLICK_THRESHOLD_MS = 150L
    private var isBreakingBlock = false
    private var trackedBodyX = 0.0
    private var trackedBodyY = 0.0
    private var trackedBodyZ = 0.0
    private var prevSelectedSlot: Int = -1
    private var controlledEntityId: Int = -1
    private var controlledEntityHealth: Float = 0f
    private var controlledEntityMaxHealth: Float = 20f
    private var controlledEntityName: String = ""
    private var isInSoulMode = false

    private val TELEPORT_KEY get() = AeonisKeyBindings.TELEPORT_KEY
    private val POSSESS_KEY get() = AeonisKeyBindings.POSSESS_KEY
    private val RELEASE_KEY get() = AeonisKeyBindings.RELEASE_KEY
    private val ABILITY_KEY get() = AeonisKeyBindings.ABILITY_KEY
    private val SOUL_POSSESS_KEY get() = AeonisKeyBindings.SOUL_POSSESS_KEY

    fun isControlling(): Boolean = isControlling
    fun getControlledMobId(): Int = controlledMobId
    fun isBreakingBlock(): Boolean = isBreakingBlock
    fun isPlayerControlling(playerUuid: UUID): Boolean = controllingPlayerUUIDs.contains(playerUuid)

    fun onControlMode(payload: ControlModePayload) {
        val mc = Minecraft.getInstance()
        isControlling = payload.enabled && payload.mobId > 0
        controlledMobId = if (payload.mobId > 0) payload.mobId else -1
        if (!isControlling) {
            resetInputState()
        }
        if (payload.selectedSlot >= 0 && mc.player != null) {
            val slot = payload.selectedSlot
            mc.execute {
                try {
                    mc.player!!.inventory.selectedSlot = slot
                } catch (_: Exception) {}
            }
        }
    }

    fun onSyncControllingPlayers(payload: AeonisNetworking.SyncControllingPlayersPayload) {
        controllingPlayerUUIDs.clear()
        controllingPlayerUUIDs.addAll(payload.playerUUIDs)
    }

    fun onControlledEntityStatus(payload: AeonisNetworking.ControlledEntityStatusPayload) {
        controlledEntityId = payload.entityId
        controlledEntityHealth = payload.health
        controlledEntityMaxHealth = payload.maxHealth
        controlledEntityName = payload.name
    }

    fun onSoulMode(payload: AeonisNetworking.SoulModePayload) {
        isInSoulMode = payload.enabled
    }

    fun onClientTick(mc: Minecraft) {
        if (isControlling && mc.player != null && mc.level != null) {
            sendMovementInput(mc)
        }

        // Key handlers
        if (mc.player != null && !isControlling && POSSESS_KEY.consumeClick()) {
            val selectedSlot = mc.player!!.inventory.selectedSlot
            val hit = mc.hitResult
            if (hit is net.minecraft.world.phys.EntityHitResult && hit.entity is net.minecraft.world.entity.LivingEntity) {
                sendPacket(PossessPayload(hit.entity.id, selectedSlot))
            }
        }

        if (mc.player != null && isControlling && RELEASE_KEY.consumeClick()) {
            val selectedSlot = mc.player!!.inventory.selectedSlot
            sendPacket(ReleasePayload(selectedSlot, mc.player!!.x, mc.player!!.y, mc.player!!.z))
            resetInputState()
        }

        if (mc.player != null && isControlling && ABILITY_KEY.consumeClick()) {
            sendPacket(AbilityPayload())
        }

        if (mc.player != null && isInSoulMode && SOUL_POSSESS_KEY.consumeClick()) {
            val hit = mc.hitResult
            if (hit is net.minecraft.world.phys.EntityHitResult && hit.entity is net.minecraft.world.entity.Mob) {
                sendPacket(AeonisNetworking.SoulPossessPayload(hit.entity.id))
            }
        }

        // Rotation sync
        if (isControlling && mc.level != null && controlledMobId > 0) {
            val mob = mc.level!!.getEntity(controlledMobId)
            if (mob is net.minecraft.world.entity.Mob) {
                mob.yRot = mc.player!!.yRot
                mob.xRot = mc.player!!.xRot
                mob.yBodyRot = mc.player!!.yRot
                mob.yHeadRot = mc.player!!.yRot
            }
        }
    }

    private fun sendMovementInput(mc: Minecraft) {
        val options = mc.options
        var forward = 0f
        var strafe = 0f
        if (options.keyUp.isDown) forward += 1f
        if (options.keyDown.isDown) forward -= 1f
        if (options.keyLeft.isDown) strafe += 1f
        if (options.keyRight.isDown) strafe -= 1f
        
        val currentTime = System.currentTimeMillis()
        val attackKeyDown = options.keyAttack.isDown
        var attack = false
        
        if (attackKeyDown && !wasAttacking) {
            attackStartTime = currentTime
            attackTriggered = false
            isBreakingBlock = false
        } else if (attackKeyDown && !attackTriggered) {
            if (currentTime - attackStartTime >= MELEE_CLICK_THRESHOLD_MS) {
                isBreakingBlock = true
            }
        } else if (!attackKeyDown && wasAttacking) {
            if (currentTime - attackStartTime < MELEE_CLICK_THRESHOLD_MS && !attackTriggered) {
                attack = true
                attackTriggered = true
            }
            isBreakingBlock = false
        }
        wasAttacking = attackKeyDown
        
        val teleport = TELEPORT_KEY.isDown && !wasTeleporting
        wasTeleporting = TELEPORT_KEY.isDown
        
        sendPacket(MobControlPayload(forward, strafe, options.keyJump.isDown, options.keyShift.isDown, mc.player!!.yRot, mc.player!!.xRot, attack, teleport))
    }

    private fun sendPacket(payload: net.minecraft.network.protocol.common.custom.CustomPacketPayload) {
        Minecraft.getInstance().connection?.send(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(payload))
    }

    private fun resetInputState() {
        wasAttacking = false
        wasTeleporting = false
        attackStartTime = 0L
        attackTriggered = false
        isBreakingBlock = false
    }
}
