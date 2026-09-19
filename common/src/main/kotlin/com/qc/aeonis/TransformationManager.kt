package com.qc.aeonis

import com.qc.aeonis.command.AeonisCommands
import com.qc.aeonis.network.AeonisNetwork
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes

object TransformationManager {

    fun tickStart(server: MinecraftServer) {
        for (player in server.playerList.players) {
            if (!AeonisNetwork.isPlayerTransformed(player.uuid)) continue
            val entityId = AeonisNetwork.getControlledEntityId(player.uuid) ?: continue
            val level = player.level()
            val mob = level.getEntity(entityId) as? Mob ?: continue
            val input = AeonisNetwork.getLatestControlInput(player.uuid)

            mob.navigation.stop()
            val liveMobSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED).toFloat()
            mob.setSpeed(liveMobSpeed)

            if (input != null) {
                val mag = kotlin.math.sqrt(input.forward * input.forward + input.strafe * input.strafe)
                if (mag > 0.001f) {
                    mob.setZza(input.forward / mag)
                    mob.setXxa(input.strafe / mag)
                } else {
                    mob.setZza(0.0f)
                    mob.setXxa(0.0f)
                }

                (mob as? LivingEntity)?.setJumping(input.jump)
                
                if (AeonisNetwork.canMobFly(mob)) {
                    mob.setYya(
                        when {
                            input.jump -> 1.0f
                            input.sneak -> -1.0f
                            else -> 0.0f
                        }
                    )
                    // Drive vertical velocity directly for flying mobs
                    val currY = mob.deltaMovement.y
                    val targetY = when {
                        input.jump -> 0.34
                        input.sneak -> -0.26
                        else -> 0.0
                    }
                    val smoothedY = currY + (targetY - currY) * 0.32
                    mob.setDeltaMovement(
                        mob.deltaMovement.x,
                        smoothedY.coerceIn(-0.6, 0.6),
                        mob.deltaMovement.z
                    )
                } else if (mob.isInWater && mob.canBreatheUnderwater()) {
                    mob.setYya(
                        when {
                            input.jump -> 1.0f
                            input.sneak -> -1.0f
                            else -> 0.0f
                        }
                    )
                } else {
                    mob.setYya(0.0f)
                }
            } else {
                mob.setZza(0.0f)
                mob.setXxa(0.0f)
                mob.setYya(0.0f)
                (mob as? LivingEntity)?.setJumping(false)
            }
        }
    }

    fun tickEnd(server: MinecraftServer) {
        for (player in server.playerList.players) {
            if (!AeonisNetwork.isPlayerTransformed(player.uuid)) continue
            val entityId = AeonisNetwork.getControlledEntityId(player.uuid) ?: continue
            val level = player.level()
            val mob = level.getEntity(entityId)
            
            if (mob == null || !mob.isAlive) {
                AeonisCommands.autoUntransform(player)
                continue
            }

            AeonisCommands.cacheOriginalMoveSpeed(player)
            if (mob is LivingEntity) {
                val liveMobSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED)
                val targetSpeed = if (liveMobSpeed > 0.0) liveMobSpeed else 0.1
                player.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = targetSpeed
            }
            
            // Smooth sync player to mob
            val dx = mob.x - player.x
            val dy = mob.y - player.y
            val dz = mob.z - player.z
            val distSqr = dx * dx + dy * dy + dz * dz
            val posBlend = when {
                distSqr > 36.0 -> 1.0f
                distSqr > 9.0 -> 0.75f
                else -> 0.42f
            }
            player.setPos(
                Mth.lerp(posBlend, player.x.toFloat(), mob.x.toFloat()).toDouble(),
                Mth.lerp(posBlend, player.y.toFloat(), mob.y.toFloat()).toDouble(),
                Mth.lerp(posBlend, player.z.toFloat(), mob.z.toFloat()).toDouble()
            )

            val prevYaw = mob.yRot
            val desiredYaw = player.yRot
            val smoothedYaw = Mth.rotLerp(0.45f, prevYaw, desiredYaw)
            mob.setYRot(smoothedYaw)
            mob.setYHeadRot(smoothedYaw)
            if (mob is LivingEntity) {
                mob.yBodyRot = Mth.rotLerp(0.35f, mob.yBodyRot, smoothedYaw)
                
                // Sync mob equipment with player's held items
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, player.mainHandItem.copy())
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, player.offhandItem.copy())
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).copy())
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).copy())
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).copy())
                mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).copy())
                
                // Sync health
                val mobMaxHealth = mob.maxHealth.toDouble()
                player.getAttribute(Attributes.MAX_HEALTH)?.baseValue = mobMaxHealth
                if (mob.health < player.health) {
                    player.health = mob.health
                }

                // Animation speed sync
                val mobHorizontalSpeed = kotlin.math.sqrt(
                    (mob.x - mob.xo) * (mob.x - mob.xo) +
                    (mob.z - mob.zo) * (mob.z - mob.zo)
                ).toFloat()
                val animSpeed = (mobHorizontalSpeed * 7.2f).coerceIn(0.0f, 2.6f)
                mob.walkAnimation.setSpeed(animSpeed)
            }
            
            mob.setXRot(player.xRot)
            mob.yRotO = prevYaw
            player.setDeltaMovement(mob.deltaMovement.multiply(0.85, 1.0, 0.85))

            // Handle flight
            if (mob is Mob && AeonisNetwork.canMobFly(mob)) {
                player.abilities.mayfly = true
                player.abilities.flying = true
                player.onUpdateAbilities()
            } else {
                val originalMode = AeonisCommands.getOriginalGameMode(player.uuid)
                if (originalMode != net.minecraft.world.level.GameType.CREATIVE && originalMode != net.minecraft.world.level.GameType.SPECTATOR) {
                    player.abilities.mayfly = false
                    player.abilities.flying = false
                    player.onUpdateAbilities()
                }
            }
            
            if (!player.isInvisible) {
                player.isInvisible = true
            }
            player.noPhysics = false
        }
    }
}
