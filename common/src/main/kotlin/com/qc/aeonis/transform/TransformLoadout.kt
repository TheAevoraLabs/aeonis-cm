package com.qc.aeonis.transform

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Starting equipment handed to the player when they morph into a mob that is useless bare-handed.
 *
 * Previously this was a `when` chain inside the command object with three byte-identical
 * crossbow branches. Rules are now ordered and declarative: first match wins, so a specific
 * mob can override a broader family rule (wither skeleton gets a sword, other skeletons a bow).
 */
object TransformLoadout {

    private class Rule(
        val label: String,
        val matches: (Mob) -> Boolean,
        val mainHand: Item,
        val extras: List<ItemStack> = emptyList()
    )

    private val rules = mutableListOf<Rule>()

    init {
        // Ordered most specific first.
        rule("Stone Sword", { it.type == EntityTypes.WITHER_SKELETON }, Items.STONE_SWORD)
        rule("Iron Axe", { it.type == EntityTypes.VINDICATOR }, Items.IRON_AXE)
        rule("Trident", { it.type == EntityTypes.DROWNED }, Items.TRIDENT)
        rule("Crossbow", { it.type == EntityTypes.PILLAGER || it.type == EntityTypes.PIGLIN }, Items.CROSSBOW, listOf(stack(Items.ARROW, 64)))
        rule("Bow", { it is AbstractSkeleton }, Items.BOW, listOf(stack(Items.ARROW, 64)))
    }

    private fun stack(item: Item, count: Int) = ItemStack(item, count)

    private fun rule(label: String, matches: (Mob) -> Boolean, mainHand: Item, extras: List<ItemStack> = emptyList()) {
        rules += Rule(label, matches, mainHand, extras)
    }

    /**
     * Equips [mob] and stocks [player]'s inventory. Returns a player-facing summary, or null
     * when the mob needs no kit.
     */
    fun apply(mob: Mob, player: ServerPlayer): String? {
        val rule = rules.firstOrNull { it.matches(mob) } ?: return null

        player.inventory.add(ItemStack(rule.mainHand))
        rule.extras.forEach { player.inventory.add(it.copy()) }

        if (mob is LivingEntity) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(rule.mainHand))
        }
        return rule.label
    }

    /** Every mob type that receives a kit, for help text. */
    fun describe(): String = rules.joinToString(", ") { it.label }
}
