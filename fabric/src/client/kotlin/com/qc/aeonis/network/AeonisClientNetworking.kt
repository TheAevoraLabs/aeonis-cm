package com.qc.aeonis.network

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import com.qc.aeonis.client.AeonisKeyBindings
import com.qc.aeonis.transform.AbilitySlot

object AeonisClientNetworking {
    
    private var isControlling = false
    private var controlledMobId: Int = -1
    private val controllingPlayerUUIDs = mutableSetOf<java.util.UUID>()
    private var wasAttacking = false  // Track previous attack state to detect new clicks
    /** Ability slot requested this tick, consumed by the next movement packet. */
    private var pendingAbility: Int = 0

    /**
     * Optimistic cooldowns for the ability HUD, keyed by ability id.
     *
     * The authoritative cooldowns live on the server, which refuses an ability that is still
     * recharging and says so. Mirroring the duration locally just keeps the HUD honest between
     * presses without a packet per ability per tick.
     */
    private val abilityCooldowns = mutableMapOf<String, Int>()
    
    // Attack timing: distinguish short click (melee) vs long click (block breaking)
    private var attackStartTime: Long = 0L
    private var attackTriggered = false  // Whether we've already triggered an attack this press
    private const val MELEE_CLICK_THRESHOLD_MS = 150L  // Clicks shorter than this = melee attack
    
    // Track if we're currently breaking a block (long press mode)
    private var isBreakingBlock = false

    // Tracked body position (client-side mirror for release fallback)
    private var trackedBodyX = 0.0
    private var trackedBodyY = 0.0
    private var trackedBodyZ = 0.0

    // Hotbar tracking
    private var prevSelectedSlot: Int = -1

    // Controlled entity last-known status (updated by server)
    private var controlledEntityId: Int = -1
    private var controlledEntityHealth: Float = 0f
    private var controlledEntityMaxHealth: Float = 20f
    private var controlledEntityName: String = ""
    
    // Soul mode (spectator mode with ability to possess existing mobs)
    private var isInSoulMode = false

    private val POSSESS_KEY get() = AeonisKeyBindings.POSSESS_KEY
    private val RELEASE_KEY get() = AeonisKeyBindings.RELEASE_KEY
    private val ABILITY_PRIMARY_KEY get() = AeonisKeyBindings.ABILITY_PRIMARY_KEY
    private val ABILITY_SECONDARY_KEY get() = AeonisKeyBindings.ABILITY_SECONDARY_KEY
    private val ABILITY_TERTIARY_KEY get() = AeonisKeyBindings.ABILITY_TERTIARY_KEY
    private val SOUL_POSSESS_KEY get() = AeonisKeyBindings.SOUL_POSSESS_KEY
    
    fun register() {
        // Note: Payload types are already registered by the server/common code
        // We only need to register handlers here
        // Initialize prevSelectedSlot to player's current slot if available
        try {
            val mc = Minecraft.getInstance()
            if (mc.player != null) prevSelectedSlot = mc.player!!.inventory.selectedSlot
        } catch (_: Exception) {}
        
        // Handle control mode packet from server (now includes mobId and selectedSlot)
        ClientPlayNetworking.registerGlobalReceiver(ControlModePayload.ID) { payload, _ ->
            val mc = Minecraft.getInstance()
            isControlling = payload.enabled && payload.mobId > 0
            controlledMobId = if (payload.mobId > 0) payload.mobId else -1
            if (!isControlling) {
                resetInputState()
            }
            // If server told us which slot to select, apply it with a small delay to avoid race conditions
            if (payload.selectedSlot >= 0 && mc.player != null) {
                val slot = payload.selectedSlot
                mc.execute {
                    try {
                        mc.player!!.inventory.selectedSlot = slot
                    } catch (_: Exception) {
                        // ignore if mapping different
                    }
                }
            }
        }

        // Handle sync controlling players payload
        ClientPlayNetworking.registerGlobalReceiver(AeonisNetworking.SyncControllingPlayersPayload.ID) { payload, _ ->
            val players = payload.playerUUIDs
            // update local set
            controllingPlayerUUIDs.clear()
            controllingPlayerUUIDs.addAll(players)
        }

        // Handle controlled-entity status updates from server
        ClientPlayNetworking.registerGlobalReceiver(AeonisNetworking.ControlledEntityStatusPayload.ID) { payload, _ ->
            try {
                controlledEntityId = payload.entityId
                controlledEntityHealth = payload.health
                controlledEntityMaxHealth = payload.maxHealth
                controlledEntityName = payload.name
            } catch (_: Exception) {}
        }
        
        // Handle soul mode status from server
        ClientPlayNetworking.registerGlobalReceiver(AeonisNetworking.SoulModePayload.ID) { payload, _ ->
            isInSoulMode = payload.enabled
        }
        
        // Handle open manhunt GUI packet from server
        ClientPlayNetworking.registerGlobalReceiver(AeonisNetworking.OpenManhuntGuiPayload.ID) { _, _ ->
            val mc = Minecraft.getInstance()
            mc.execute {
                mc.player?.sendSystemMessage(Component.literal("[AEONIS] Manhunt setup UI is currently unavailable in this build."))
            }
        }

        // Register tick event to send movement inputs
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (isControlling && client.player != null && client.level != null) {
                sendMovementInput(client)
            }

            // Possess key: try to resolve the targeted mob under crosshair and send its id
            if (client.player != null && !isControlling && POSSESS_KEY.consumeClick()) {
                val selectedSlot = client.player!!.inventory.selectedSlot
                try {
                    val hit = client.hitResult
                    if (hit is net.minecraft.world.phys.EntityHitResult) {
                        val ent = hit.entity
                        if (ent is net.minecraft.world.entity.LivingEntity) {
                            val payload = PossessPayload(ent.id, selectedSlot)
                            ClientPlayNetworking.send(payload)
                        } else {
                            client.player!!.sendSystemMessage(Component.literal("§c[AEONIS] Can only control living mobs!"))
                        }
                    } else {
                        client.player!!.sendSystemMessage(Component.literal("§c[AEONIS] Look at a mob to control it!"))
                    }
                } catch (_: Exception) {
                    // fallback: send with -1 (server will ignore)
                    val payload = PossessPayload(-1, selectedSlot)
                    ClientPlayNetworking.send(payload)
                }
            }

            if (client.player != null && isControlling && RELEASE_KEY.consumeClick()) {
                val selectedSlot = client.player!!.inventory.selectedSlot
                val payload = ReleasePayload(selectedSlot, client.player!!.x, client.player!!.y, client.player!!.z)
                ClientPlayNetworking.send(payload)
                // clear tracked body after release
                trackedBodyX = 0.0
                trackedBodyY = 0.0
                trackedBodyZ = 0.0
            }

            // One key per ability slot. Pressing a slot sends the index with the next movement
            // packet, and the server turns it into the mob's own action.
            if (client.player != null && isControlling) {
                when {
                    ABILITY_PRIMARY_KEY.consumeClick() -> pendingAbility = AbilitySlot.PRIMARY.index
                    ABILITY_SECONDARY_KEY.consumeClick() -> pendingAbility = AbilitySlot.SECONDARY.index
                    ABILITY_TERTIARY_KEY.consumeClick() -> pendingAbility = AbilitySlot.TERTIARY.index
                }
                // Mirror the pressed ability's cooldown so the HUD reacts immediately.
                if (pendingAbility != 0) {
                    recordCooldown(AbilitySlot.of(pendingAbility))
                }
                tickCooldowns()
            }
            
            // Soul Possess key (P): when in soul mode, possess the mob you're looking at
            if (client.player != null && isInSoulMode && SOUL_POSSESS_KEY.consumeClick()) {
                try {
                    val hit = client.hitResult
                    if (hit is net.minecraft.world.phys.EntityHitResult) {
                        val ent = hit.entity
                        if (ent is net.minecraft.world.entity.Mob) {
                            // Send soul possess packet to server
                            ClientPlayNetworking.send(AeonisNetworking.SoulPossessPayload(ent.id))
                        } else {
                            client.player!!.sendSystemMessage(Component.literal("§c[AEONIS] Can only possess mobs!"))
                        }
                    } else {
                        client.player!!.sendSystemMessage(Component.literal("§c[AEONIS] Look at a mob to possess it!"))
                    }
                } catch (_: Exception) {
                    client.player!!.sendSystemMessage(Component.literal("§c[AEONIS] Error possessing mob!"))
                }
            }

            // Update tracked body coordinates for owner's body entity if present
            if (isControlling && client.player != null && client.level != null) {
                val playerUuid = client.player!!.uuid
                for (entity in client.level!!.entitiesForRendering()) {
                    if (entity is com.qc.aeonis.entity.BodyEntity) {
                        val owner = entity.getOwnerUuid()
                        if (owner != null && owner == playerUuid) {
                            trackedBodyX = entity.x
                            trackedBodyY = entity.y
                            trackedBodyZ = entity.z
                            break
                        }
                    }
                }
                
                // Client-side visual sync: update mob's rotation to match player's camera for smooth rendering
                // Position is controlled entirely by the server to avoid desync
                if (controlledMobId > 0) {
                    val mobEntity = client.level!!.getEntity(controlledMobId)
                    if (mobEntity is net.minecraft.world.entity.Mob) {
                        // Keep only rotation in lockstep; position remains server-authoritative for smoother replication.
                        mobEntity.yRot = client.player!!.yRot
                        mobEntity.xRot = client.player!!.xRot
                        mobEntity.yBodyRot = client.player!!.yRot
                        mobEntity.yHeadRot = client.player!!.yRot
                        
                        // Animate from the mob's own local motion so visuals match replicated movement.
                        if (mobEntity is net.minecraft.world.entity.LivingEntity) {
                            val horizontalSpeed = Math.sqrt(
                                mobEntity.deltaMovement.x * mobEntity.deltaMovement.x +
                                mobEntity.deltaMovement.z * mobEntity.deltaMovement.z
                            )
                            mobEntity.walkAnimation.setSpeed((horizontalSpeed * 6.8f).toFloat())
                        }
                    }
                }

                // Hotbar change detection: notify server when player changes selected slot
                try {
                    val currSlot = client.player!!.inventory.selectedSlot
                    if (currSlot != prevSelectedSlot) {
                        prevSelectedSlot = currSlot
                        ClientPlayNetworking.send(SelectedSlotPayload(currSlot))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun setControlling(controlling: Boolean) {
        isControlling = controlling
    }

    /** Remaining cooldown for an ability id, in ticks; 0 when it is ready. */
    fun remainingCooldown(abilityId: String): Int = abilityCooldowns[abilityId] ?: 0

    private fun tickCooldowns() {
        if (abilityCooldowns.isEmpty()) return
        val keys = abilityCooldowns.keys.toList()
        for (key in keys) {
            val remaining = abilityCooldowns.getValue(key) - 1
            if (remaining <= 0) abilityCooldowns.remove(key) else abilityCooldowns[key] = remaining
        }
    }

    private fun recordCooldown(slot: AbilitySlot?) {
        val slotId = slot ?: return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val mob = level.getEntity(controlledMobId) as? net.minecraft.world.entity.Mob ?: return
        val ability = com.qc.aeonis.transform.MobAbilities.forMob(mob).firstOrNull { it.slot == slotId } ?: return
        abilityCooldowns[ability.id] = ability.cooldownTicks
    }
    
    fun isControlling(): Boolean = isControlling

    fun getControlledEntityHealth(): Float = controlledEntityHealth
    fun getControlledEntityMaxHealth(): Float = controlledEntityMaxHealth
    fun getControlledEntityName(): String = controlledEntityName
    fun getControlledEntityId(): Int = controlledEntityId
    fun getControlledMobId(): Int = controlledMobId
    fun isBreakingBlock(): Boolean = isBreakingBlock
    fun isPlayerControlling(playerUuid: java.util.UUID): Boolean = controllingPlayerUUIDs.contains(playerUuid)
    
    private fun sendMovementInput(client: Minecraft) {
        val player = client.player ?: return
        val options = client.options
        
        // Get movement input
        var forward = 0f
        var strafe = 0f
        
        if (options.keyUp.isDown) forward += 1f
        if (options.keyDown.isDown) forward -= 1f
        // 26.2 vanilla convention: "left impulse" is positive when pressing left.
        if (options.keyLeft.isDown) strafe += 1f
        if (options.keyRight.isDown) strafe -= 1f
        
        val jump = options.keyJump.isDown
        val sneak = options.keyShift.isDown
        
        // Get camera rotation
        val yaw = player.yRot
        val pitch = player.xRot
        
        // Detect left click attack with short vs long click logic
        // Short click = melee attack by mob
        // Long click = block breaking by player
        val attackKeyDown = options.keyAttack.isDown
        var attack = false
        val currentTime = System.currentTimeMillis()
        
        if (attackKeyDown && !wasAttacking) {
            // Just started pressing - record start time
            attackStartTime = currentTime
            attackTriggered = false
            isBreakingBlock = false
        } else if (attackKeyDown && !attackTriggered) {
            // Still holding - check duration
            val holdDuration = currentTime - attackStartTime
            if (holdDuration >= MELEE_CLICK_THRESHOLD_MS) {
                // Long press - switch to block breaking mode
                isBreakingBlock = true
            }
        } else if (!attackKeyDown && wasAttacking) {
            // Just released - check if it was a short click
            val holdDuration = currentTime - attackStartTime
            if (holdDuration < MELEE_CLICK_THRESHOLD_MS && !attackTriggered) {
                // Short click released - trigger melee attack
                attack = true
                attackTriggered = true
            }
            isBreakingBlock = false
        }
        wasAttacking = attackKeyDown
        
        // Sprint is forwarded so forms with the physique for it (and only those) can run.
        val sprint = options.keySprint.isDown && forward > 0f

        // Send packet to server
        // The camouflage level rides every movement packet: it is a client preference that the
        // server has to execute, and one VarInt per tick is cheaper than a separate sync packet
        // with its own lifecycle. Reading it here means a change in the menu takes effect on the
        // very next tick with no reconnect.
        val payload = MobControlPayload(
            forward, strafe, jump, sneak, yaw, pitch, attack,
            teleport = false, ability = pendingAbility, sprint = sprint,
            camouflage = com.qc.aeonis.screen.AeonisClientSettings.camouflageLevel.ordinal
        )
        pendingAbility = 0
        ClientPlayNetworking.send(payload)
    }

    private fun resetInputState() {
        wasAttacking = false
        pendingAbility = 0
        attackStartTime = 0L
        attackTriggered = false
        isBreakingBlock = false
        abilityCooldowns.clear()
    }
}
