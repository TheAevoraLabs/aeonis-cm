package com.qc.aeonis.neoforge

import com.qc.aeonis.platform.IPlatformHelper
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import java.util.function.BiFunction

class NeoForgePlatformHelper : IPlatformHelper {
    override fun <T : BlockEntity> createBlockEntityType(factory: BiFunction<BlockPos, BlockState, T>, vararg blocks: Block): BlockEntityType<T> {
        // Fallback to reflection if Builder is not available in this snapshot/environment
        try {
            val constructor = BlockEntityType::class.java.declaredConstructors[0]
            constructor.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return constructor.newInstance(factory, blocks.toSet()) as BlockEntityType<T>
        } catch (e: Exception) {
            throw RuntimeException("Failed to create BlockEntityType via reflection", e)
        }
    }
}
