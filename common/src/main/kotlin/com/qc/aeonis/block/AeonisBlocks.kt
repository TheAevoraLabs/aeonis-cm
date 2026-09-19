package com.qc.aeonis.block

import com.qc.aeonis.registration.AeonisRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import java.util.function.Supplier

object AeonisBlocks {
    private lateinit var _ANCARD_PORTAL: Supplier<out Block>
    val ANCARD_PORTAL: Block get() = _ANCARD_PORTAL.get()

    private lateinit var _PERMANENT_FLAME: Supplier<out Block>
    val PERMANENT_FLAME: Block get() = _PERMANENT_FLAME.get()

    fun register() {
        _ANCARD_PORTAL = Supplier { Blocks.NETHER_PORTAL }
        
        _PERMANENT_FLAME = register("permanent_flame") { props ->
            PermanentFlameBlock(
                props
                    .noCollision()
                    .strength(-1.0f, 3600000.0f)
                    .lightLevel { 15 }
                    .sound(SoundType.WOOL)
                    .noLootTable()
                    .noOcclusion()
                    .ignitedByLava()
            )
        }
    }

    private fun register(name: String, factory: (BlockBehaviour.Properties) -> Block): Supplier<out Block> {
        val blockSupplier = AeonisRegistries.registerBlock(name) {
            val id = Identifier.fromNamespaceAndPath("aeonis", name)
            val key = ResourceKey.create(Registries.BLOCK, id)
            val props = BlockBehaviour.Properties.of().setId(key)
            factory(props)
        }
        
        AeonisRegistries.registerItem(name) {
            val block = blockSupplier.get()
            val id = Identifier.fromNamespaceAndPath("aeonis", name)
            val key = ResourceKey.create(Registries.ITEM, id)
            val itemProps = net.minecraft.world.item.Item.Properties().setId(key)
            net.minecraft.world.item.BlockItem(block, itemProps)
        }
        
        return blockSupplier
    }
}
