package com.qc.aeonis.companion.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack

/**
 * Player-grade capabilities for the companion mob, executed through a headless
 * [net.minecraft.server.level.ServerPlayer] so the game's own mining, placing and
 * attacking code paths run instead of a reimplementation.
 *
 * ### Ownership rule (important)
 * The *entity* owns the inventory; the surrogate's hand is scratch space. Every method
 * here takes the stack it should act with, puts it in the surrogate's hand, lets vanilla
 * mutate it (durability, stack shrink), and clears the hand afterwards. Before this rule
 * the surrogate wrote its hand back into `bot.inventory.mainHandItem`, which duplicated
 * tools and blocks when they came from a backpack slot — the bot could place scaffolding
 * forever. Callers therefore own the stack they pass in and must clear the inventory slot
 * themselves when vanilla has consumed it (`ItemStack.isEmpty` after the call).
 */
interface IPlayerSurrogate {

    /** Teleports the surrogate onto the bot and copies its rotation and velocity. */
    fun syncWithBot()

    /**
     * Breaks [pos] with player-grade block-breaking (tool speed, durability, drops).
     * [tool] is the stack the break is performed with; it may be empty for a bare hand.
     */
    fun mineBlock(pos: BlockPos, tool: ItemStack): Boolean

    /**
     * Places [itemStack] at [pos]. [pos] must be air or otherwise replaceable.
     * The stack is decremented by vanilla on success.
     */
    fun placeBlock(pos: BlockPos, face: Direction, itemStack: ItemStack): Boolean

    /**
     * Attacks [target] exactly as a player would: crit logic, sweep, knockback and the
     * attribute modifiers of [weapon] all apply.
     */
    fun attackEntity(target: Entity, weapon: ItemStack = ItemStack.EMPTY): Boolean
}
