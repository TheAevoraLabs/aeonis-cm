package com.qc.aeonis

import com.qc.aeonis.network.AeonisNetwork
import com.qc.aeonis.network.MobControlPayload
import com.qc.aeonis.transform.AbilitySlot
import com.qc.aeonis.transform.CamouflageLevel
import com.qc.aeonis.transform.MobAbilityRuntime
import com.qc.aeonis.transform.MobCamouflage
import com.qc.aeonis.transform.MobMovementController
import com.qc.aeonis.transform.MobMovementProfiles
import com.qc.aeonis.transform.MovementKind
import com.qc.aeonis.transform.TransformSessions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.GameType
import java.util.UUID

/**
 * Per-tick movement and appearance sync between a controlling player and the mob they occupy.
 *
 * Ownership and restore state are read from [AeonisNetwork] and [TransformSessions] rather than
 * from command-object maps, so this loop can no longer disagree with the transform lifecycle
 * about who is transformed or what their original gamemode was.
 *
 * Movement itself lives in [MobMovementController], which knows the locomotion style of each mob
 * family; this class only decides *when* to drive, mirrors state onto the player, and routes
 * ability presses.
 */
object TransformationManager {

    /**
     * The exact payload object an ability was last dispatched from.
     *
     * Payloads arrive once per client tick and the server keeps the most recent one, so without
     * this guard a dropped packet would re-fire the same ability every tick until the next input
     * arrived. Comparing identity (not equality) is what makes it a one-shot trigger.
     */
    private val lastAbilityPayload = HashMap<UUID, MobControlPayload>()

    private fun controlledMob(player: ServerPlayer): Mob? {
        val entityId = AeonisNetwork.getControlledEntityId(player.uuid) ?: return null
        return player.level().getEntity(entityId) as? Mob
    }

    fun tickStart(server: MinecraftServer) {
        // Cooldowns are per player, so one pass per tick is enough for every session.
        MobAbilityRuntime.tick()

        for (player in server.playerList.players) {
            if (!AeonisNetwork.isPlayerTransformed(player.uuid)) continue
            val mob = controlledMob(player) ?: continue
            val input = AeonisNetwork.getLatestControlInput(player.uuid)

            if (input == null) {
                // No input has arrived yet: hold still rather than running on stale keys.
                MobMovementController.idle(mob)
                continue
            }

            val sprinting = input.sprint && MobMovementProfiles.canSprint(mob)
            MobMovementController.drive(mob, input, sprinting)

            // Identity suppression runs every tick because effects, tags and other mods can
            // re-apply a glow or a visible name at any time.
            MobCamouflage.apply(mob)
            MobCamouflage.ambientChatter(mob, mob.tickCount, camouflageLevel(input))
            MobCamouflage.mirrorPose(mob, input.sneak)

            dispatchAbility(player, mob, input)
        }
    }

    /**
     * The player's requested camouflage level, clamped to a real level.
     *
     * The value arrives over the network, so it is treated as untrusted input: an out-of-range
     * ordinal falls back to the default rather than throwing out of the tick loop.
     */
    private fun camouflageLevel(input: MobControlPayload?): CamouflageLevel =
        CamouflageLevel.entries.getOrElse(input?.camouflage ?: -1) { CamouflageLevel.NATURAL }

    private fun dispatchAbility(player: ServerPlayer, mob: Mob, input: MobControlPayload) {
        if (input.ability == 0) {
            lastAbilityPayload.remove(player.uuid)
            return
        }
        if (lastAbilityPayload[player.uuid] === input) return
        lastAbilityPayload[player.uuid] = input

        val slot = AbilitySlot.of(input.ability) ?: return
        val failure = MobAbilityRuntime.activate(player, mob, slot)
        if (failure != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[AEONIS] $failure"), true)
        }
    }

    fun tickEnd(server: MinecraftServer) {
        for (player in server.playerList.players) {
            if (!AeonisNetwork.isPlayerTransformed(player.uuid)) continue
            val mob = controlledMob(player) ?: continue
            val input = AeonisNetwork.getLatestControlInput(player.uuid)

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
            mob.yRotO = previousYaw
            mob.yBodyRot = Mth.rotLerp(0.35f, mob.yBodyRot, smoothedYaw)
            mob.setXRot(player.xRot)

            // Camera-locked heads are the clearest tell that a mob is player-driven, so an idle
            // mob is allowed to look around on its own.
            val engaged = input != null && (
                kotlin.math.abs(input.forward) > 0.05f ||
                    kotlin.math.abs(input.strafe) > 0.05f ||
                    input.jump
                )
            val camouflage = camouflageLevel(input)
            MobCamouflage.idle(mob, engaged, mob.tickCount, camouflage)
            MobCamouflage.settleBody(mob)

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
            // Ground walkers animate from distance travelled; flyers and swimmers read as
            // motionless if the same formula is applied to them, so they get a milder curve.
            val animationScale = when (MobMovementProfiles.profileFor(mob).kind) {
                MovementKind.GROUND, MovementKind.CLIMBING, MovementKind.LEAPING -> 7.2f
                else -> 4.0f
            }
            mob.walkAnimation.setSpeed((horizontalSpeed * animationScale).coerceIn(0.0f, 2.6f))

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

    /**
     * Forgets per-session state for a player, used when their session ends.
     *
     * The one-shot ability guard and the idle-animation state both key off entity ids, so both
     * have to be dropped together or a recycled id would resume somebody else's idle personality.
     */
    fun forgetAbilities(playerId: UUID, mobId: Int? = null) {
        lastAbilityPayload.remove(playerId)
        mobId?.let { MobCamouflage.forget(it) }
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
