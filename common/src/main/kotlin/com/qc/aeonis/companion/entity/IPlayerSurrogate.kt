package com.qc.aeonis.companion.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack

/**
 * Interface contract representing player-grade capabilities for the companion mob.
 * Implemented by CompanionPlayerSurrogate.
 */
interface IPlayerSurrogate {
    fun syncWithBot()
    fun mineBlock(pos: BlockPos): Boolean
    fun placeBlock(pos: BlockPos, face: Direction, itemStack: ItemStack): Boolean
    fun attackEntity(target: Entity): Boolean
}
