package com.qc.aeonis

import com.qc.aeonis.network.AeonisNetwork
import com.qc.aeonis.transform.TransformSessions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.GameType

/**
 * Per-tick movement and appearance sync between a controlling player and the mob they occupy.
 *
 * Ownership and restore state are read from [AeonisNetwork] and [TransformSessions] rather than
 * from command-object maps, so this loop can no longer disagree with the transform lifecycle
 * about who is transformed or what their original gamemode was.
 */
object TransformationManager {

    private fun controlledMob(player: ServerPlayer): Mob? {
        val entityId = AeonisNetwork.getControlledEntityId(player.uuid) ?: return null
        return player.level().getEntity(entityId) as? Mob
    }

    fun tickStart(server: MinecraftServer) {
        for (player in server.playerList.players) {
            if (!AeonisNetwork.isPlayerTransformed(player.uuid)) continue
            val mob = controlledMob(player) ?: continue
            val input = AeonisNetwork.getLatestControlInput(player.uuid)

            // The mob is driven by player input, never by its own navigator.
            mob.navigation.stop()
            mob.setSpeed(mob.getAttributeValue(Attributes.MOVEMENT_SPEED).toFloat())

            if (input == null) {
                mob.setZza(0.0f)
                mob.setXxa(0.0f)
                mob.setYya(0.0f)
                mob.setJumping(false)
                continue
            }

            val magnitude = kotlin.math.sqrt(input.forward * input.forward + input.strafe * input.strafe)
            if (magnitude > 0.001f) {
                mob.setZza(input.forward / magnitude)
                mob.setXxa(input.strafe / magnitude)
            } else {
                mob.setZza(0.0f)
                mob.setXxa(0.0f)
            }
            mob.setJumping(input.jump)

            val vertical = when {
                input.jump -> 1.0f
                input.sneak -> -1.0f
                else -> 0.0f
            }
            if (AeonisNetwork.canMobFly(mob)) {
                mob.setYya(vertical)
                // Flyers need their vertical velocity driven directly; setYya alone is ignored.
                val currentY = mob.deltaMovement.y
                val targetY = when {
                    input.jump -> 0.34
                    input.sneak -> -0.26
                    else -> 0.0
                }
                val smoothedY = currentY + (targetY - currentY) * 0.32
                mob.setDeltaMovement(
                    mob.deltaMovement.x,
                    smoothedY.coerceIn(-0.6, 0.6),
                    mob.deltaMovement.z
                )
            } else if (mob.isInWater && mob.canBreatheUnderwater()) {
                mob.setYya(vertical)
            } else {
                mob.setYya(0.0f)
            }
        }
    }

    fun tickEnd(server: MinecraftServer) {
        for (player in server.playerList.players) {
            if (!AeonisNetwork.isPlayerTransformed(player.uuid)) continue
            val mob = controlledMob(player) ?: continue

            // Mirror the mob's movement speed onto the player so their own movement feels right
            // when the session is later restored.
            val mobSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED)
            player.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = if (mobSpeed > 0.0) mobSpeed else 0.1

            // Keep the player glued to the mob, tightening the blend as the gap grows so a
            // fast-moving form does not visibly lag behind its own body.
            val dx = mob.x - player.x
            val dy = mob.y - player.y
            val dz = mob.z - player.z
            val distanceSqr = dx * dx + dy * dy + dz * dz
            val blend = when {
                distanceSqr > 36.0 -> 1.0f
                distanceSqr > 9.0 -> 0.75f
                else -> 0.42f
            }
            player.setPos(
                Mth.lerp(blend, player.x.toFloat(), mob.x.toFloat()).toDouble(),
                Mth.lerp(blend, player.y.toFloat(), mob.y.toFloat()).toDouble(),
                Mth.lerp(blend, player.z.toFloat(), mob.z.toFloat()).toDouble()
            )

            val previousYaw = mob.yRot
            val smoothedYaw = Mth.rotLerp(0.45f, previousYaw, player.yRot)
            mob.setYRot(smoothedYaw)
            mob.setYHeadRot(smoothedYaw)
            mob.yBodyRot = Mth.rotLerp(0.35f, mob.yBodyRot, smoothedYaw)
            mob.setXRot(player.xRot)
            mob.yRotO = previousYaw

            // Mirror the player's equipment onto the mob so the model shows what they hold.
            for (slot in EQUIPMENT_SLOTS) {
                mob.setItemSlot(slot, player.getItemBySlot(slot).copy())
            }

            // Health is shared: the mob owns it, the player's bar reflects it.
            val mobMaxHealth = mob.maxHealth.toDouble()
            player.getAttribute(Attributes.MAX_HEALTH)?.baseValue = mobMaxHealth
            if (mob.health < player.health) {
                player.health = mob.health
            }

            val horizontalSpeed = kotlin.math.sqrt(
                (mob.x - mob.xo) * (mob.x - mob.xo) +
                    (mob.z - mob.zo) * (mob.z - mob.zo)
            ).toFloat()
            mob.walkAnimation.setSpeed((horizontalSpeed * 7.2f).coerceIn(0.0f, 2.6f))

            player.setDeltaMovement(mob.deltaMovement.multiply(0.85, 1.0, 0.85))

            // Flight is granted only for forms that can actually fly, and never taken away from a
            // player who is entitled to it by their original gamemode.
            if (AeonisNetwork.canMobFly(mob)) {
                player.abilities.mayfly = true
                player.abilities.flying = true
                player.onUpdateAbilities()
            } else {
                val originalMode = TransformSessions.get(player.uuid)?.restore?.gameMode
                if (originalMode != GameType.CREATIVE && originalMode != GameType.SPECTATOR) {
                    player.abilities.mayfly = false
                    player.abilities.flying = false
                    player.onUpdateAbilities()
                }
            }

            player.isInvisible = true
            player.noPhysics = false
        }
    }

    private val EQUIPMENT_SLOTS = arrayOf(
        EquipmentSlot.MAINHAND,
        EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    )
}
