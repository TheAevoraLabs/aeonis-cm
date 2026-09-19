package com.qc.aeonis.neoforge

import com.qc.aeonis.Aeonis
import com.qc.aeonis.registration.RegistrationHelper
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

class NeoForgeRegistrationHelper : RegistrationHelper {
    private val BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, Aeonis.MOD_ID)
    private val ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, Aeonis.MOD_ID)
    private val ENTITY_TYPES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, Aeonis.MOD_ID)
    private val BLOCK_ENTITY_TYPES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Aeonis.MOD_ID)

    fun register(bus: IEventBus) {
        BLOCKS.register(bus)
        ITEMS.register(bus)
        ENTITY_TYPES.register(bus)
        BLOCK_ENTITY_TYPES.register(bus)
    }

    override fun <T : Block> registerBlock(name: String, supplier: Supplier<T>): Supplier<T> {
        return BLOCKS.register(name, supplier)
    }

    override fun <T : Item> registerItem(name: String, supplier: Supplier<T>): Supplier<T> {
        return ITEMS.register(name, supplier)
    }

    override fun <T : Entity> registerEntityType(name: String, supplier: Supplier<EntityType<T>>): Supplier<EntityType<T>> {
        return ENTITY_TYPES.register(name, supplier)
    }

    override fun <T : BlockEntity> registerBlockEntityType(name: String, supplier: Supplier<BlockEntityType<T>>): Supplier<BlockEntityType<T>> {
        return BLOCK_ENTITY_TYPES.register(name, supplier)
    }
}
