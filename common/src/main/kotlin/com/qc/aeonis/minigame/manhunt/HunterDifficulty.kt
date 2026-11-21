package com.qc.aeonis.minigame.manhunt

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments

/**
 * Selectable difficulty levels for Autonomous AI Hunter Bots in Manhunt.
 * Controls neural responsiveness, movement speed, equipment, combat tactics,
 * and pursuit aggressiveness.
 */
enum class HunterDifficulty(
    val id: String,
    val displayName: String,
    val colorCode: String,
    val speedMultiplier: Double,
    val damageMultiplier: Double,
    val headStartSeconds: Int,
    val neuralSamplingHz: Int,
    val canSprintJump: Boolean,
    val canBridgeAndWall: Boolean,
    val canCrossDimensions: Boolean,
    val description: String
) {
    CASUAL(
        id = "casual",
        displayName = "Casual",
        colorCode = "§a",
        speedMultiplier = 0.85,
        damageMultiplier = 0.75,
        headStartSeconds = 45,
        neuralSamplingHz = 5,
        canSprintJump = false,
        canBridgeAndWall = false,
        canCrossDimensions = false,
        description = "Relaxed pace with generous head start and forgiving AI. Hunter uses basic equipment."
    ),
    TACTICAL(
        id = "tactical",
        displayName = "Tactical",
        colorCode = "§e",
        speedMultiplier = 1.15,
        damageMultiplier = 1.0,
        headStartSeconds = 30,
        neuralSamplingHz = 10,
        canSprintJump = true,
        canBridgeAndWall = true,
        canCrossDimensions = true,
        description = "Balanced challenge. Hunter uses predictive intercepting, block placement, and portal tracking."
    ),
    TERMINATOR(
        id = "terminator",
        displayName = "Terminator",
        colorCode = "§4§l",
        speedMultiplier = 1.45,
        damageMultiplier = 1.35,
        headStartSeconds = 15,
        neuralSamplingHz = 20,
        canSprintJump = true,
        canBridgeAndWall = true,
        canCrossDimensions = true,
        description = "Ruthless machine intelligence. Relentless 20Hz neural movement, parkour, diamond gear, and zero mercy."
    );

    fun equipLoadout(entity: com.qc.aeonis.entity.HunterEntity) {
        when (this) {
            CASUAL -> {
                entity.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.STONE_SWORD))
                entity.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.LEATHER_HELMET))
                entity.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.LEATHER_CHESTPLATE))
                entity.setItemSlot(EquipmentSlot.LEGS, ItemStack(Items.LEATHER_LEGGINGS))
                entity.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.LEATHER_BOOTS))
            }
            TACTICAL -> {
                entity.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
                entity.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET))
                entity.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.IRON_CHESTPLATE))
                entity.setItemSlot(EquipmentSlot.LEGS, ItemStack(Items.IRON_LEGGINGS))
                entity.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.IRON_BOOTS))
                entity.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.SHIELD))
            }
            TERMINATOR -> {
                val sword = ItemStack(Items.DIAMOND_SWORD)
                val helmet = ItemStack(Items.DIAMOND_HELMET)
                val chestplate = ItemStack(Items.DIAMOND_CHESTPLATE)
                val leggings = ItemStack(Items.DIAMOND_LEGGINGS)
                val boots = ItemStack(Items.DIAMOND_BOOTS)

                entity.setItemSlot(EquipmentSlot.MAINHAND, sword)
                entity.setItemSlot(EquipmentSlot.HEAD, helmet)
                entity.setItemSlot(EquipmentSlot.CHEST, chestplate)
                entity.setItemSlot(EquipmentSlot.LEGS, leggings)
                entity.setItemSlot(EquipmentSlot.FEET, boots)
                entity.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.TOTEM_OF_UNDYING))
            }
        }
    }

    companion object {
        fun fromId(id: String): HunterDifficulty =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: entries.firstOrNull { it.displayName.equals(id, ignoreCase = true) }
                ?: TACTICAL
    }
}
