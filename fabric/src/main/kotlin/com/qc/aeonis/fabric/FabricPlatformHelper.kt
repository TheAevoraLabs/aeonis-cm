package com.qc.aeonis.fabric

import com.qc.aeonis.platform.IPlatformHelper
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import java.util.function.BiFunction

class FabricPlatformHelper : IPlatformHelper {
    override fun <T : BlockEntity> createBlockEntityType(factory: BiFunction<BlockPos, BlockState, T>, vararg blocks: Block): BlockEntityType<T> {
        return FabricBlockEntityTypeBuilder.create({ pos, state -> factory.apply(pos, state) }, *blocks).build(null)
    }
}
