package com.qc.aeonis.companion.ai.observer

import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items

enum class PlayerIntent {
    MINING,
    BUILDING,
    COMBAT,
    EXPLORING,
    EMERGENCY,
    RESTING
}

/**
 * Continuous observer tracking player activities, held items, health thresholds,
 * and environmental state to deduce high-level cooperative intent.
 */
class PlayerIntentObserver(private val player: ServerPlayer) {
    private var lastIntent: PlayerIntent = PlayerIntent.EXPLORING
    private var idleTicks: Int = 0

    fun evaluate(): PlayerIntent {
        val healthRatio = player.health / player.maxHealth

        // 1. Emergency Safety Check (Health critically low or burning)
        if (healthRatio <= 0.35f || player.isOnFire) {
            lastIntent = PlayerIntent.EMERGENCY
            return PlayerIntent.EMERGENCY
        }

        // 2. Combat Engagement Check (Player recently attacked or was hurt by a living entity)
        val lastHurtMob = player.lastHurtMob
        if (lastHurtMob != null && lastHurtMob.isAlive && player.tickCount - player.lastHurtMobTimestamp < 100) {
            lastIntent = PlayerIntent.COMBAT
            return PlayerIntent.COMBAT
        }

        val lastHurtByMob = player.lastHurtByMob
        if (lastHurtByMob != null && lastHurtByMob.isAlive && player.tickCount - player.lastHurtByMobTimestamp < 100) {
            lastIntent = PlayerIntent.COMBAT
            return PlayerIntent.COMBAT
        }

        val mainHand = player.mainHandItem

        // 3. Activity / Tool Recognition using Data-Driven Tags
        val detected = when {
            mainHand.`is`(ItemTags.PICKAXES) && player.blockPosition().y < 55 -> PlayerIntent.MINING
            mainHand.item is BlockItem -> PlayerIntent.BUILDING
            mainHand.`is`(ItemTags.SWORDS) || mainHand.`is`(ItemTags.AXES) || mainHand.`is`(Items.BOW) || mainHand.`is`(Items.CROSSBOW) -> PlayerIntent.COMBAT
            player.deltaMovement.lengthSqr() < 0.001 -> {
                idleTicks++
                if (idleTicks > 200) PlayerIntent.RESTING else PlayerIntent.EXPLORING
            }
            else -> {
                idleTicks = 0
                PlayerIntent.EXPLORING
            }
        }

        lastIntent = detected
        return detected
    }

    val currentIntent: PlayerIntent get() = lastIntent
}
