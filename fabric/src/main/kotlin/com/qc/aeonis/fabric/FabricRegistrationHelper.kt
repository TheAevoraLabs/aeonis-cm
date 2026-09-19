package com.qc.aeonis.fabric

import com.qc.aeonis.registration.RegistrationHelper
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import java.util.function.Supplier

class FabricRegistrationHelper : RegistrationHelper {
    override fun <T : Block> registerBlock(name: String, supplier: Supplier<T>): Supplier<T> {
        val block = Registry.register(BuiltInRegistries.BLOCK, Identifier.fromNamespaceAndPath("aeonis", name), supplier.get())
        return Supplier { block }
    }

    override fun <T : Item> registerItem(name: String, supplier: Supplier<T>): Supplier<T> {
        val item = Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath("aeonis", name), supplier.get())
        return Supplier { item }
    }

    override fun <T : Entity> registerEntityType(name: String, supplier: Supplier<EntityType<T>>): Supplier<EntityType<T>> {
        val type = Registry.register(BuiltInRegistries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("aeonis", name), supplier.get())
        return Supplier { type }
    }

    override fun <T : BlockEntity> registerBlockEntityType(name: String, supplier: Supplier<BlockEntityType<T>>): Supplier<BlockEntityType<T>> {
        val type = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.fromNamespaceAndPath("aeonis", name), supplier.get())
        return Supplier { type }
    }

    override fun <T : net.minecraft.world.inventory.AbstractContainerMenu> registerMenuType(name: String, supplier: Supplier<net.minecraft.world.inventory.MenuType<T>>): Supplier<net.minecraft.world.inventory.MenuType<T>> {
        val type = Registry.register(BuiltInRegistries.MENU, Identifier.fromNamespaceAndPath("aeonis", name), supplier.get())
        return Supplier { type }
    }
}
