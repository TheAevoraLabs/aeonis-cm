package com.qc.aeonis.registration

import net.minecraft.world.level.block.Block
import net.minecraft.world.item.Item
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.function.Supplier

interface RegistrationHelper {
    fun <T : Block> registerBlock(name: String, supplier: Supplier<T>): Supplier<T>
    fun <T : Item> registerItem(name: String, supplier: Supplier<T>): Supplier<T>
    fun <T : Entity> registerEntityType(name: String, supplier: Supplier<EntityType<T>>): Supplier<EntityType<T>>
    fun <T : BlockEntity> registerBlockEntityType(name: String, supplier: Supplier<BlockEntityType<T>>): Supplier<BlockEntityType<T>>
}

object AeonisRegistries {
    private var helper: RegistrationHelper? = null

    fun setHelper(helper: RegistrationHelper) {
        this.helper = helper
    }

    fun <T : Block> registerBlock(name: String, supplier: Supplier<T>): Supplier<T> {
        return helper?.registerBlock(name, supplier) ?: throw IllegalStateException("Registry helper not initialized")
    }

    fun <T : Item> registerItem(name: String, supplier: Supplier<T>): Supplier<T> {
        return helper?.registerItem(name, supplier) ?: throw IllegalStateException("Registry helper not initialized")
    }

    fun <T : Entity> registerEntityType(name: String, supplier: Supplier<EntityType<T>>): Supplier<EntityType<T>> {
        return helper?.registerEntityType(name, supplier) ?: throw IllegalStateException("Registry helper not initialized")
    }

    fun <T : BlockEntity> registerBlockEntityType(name: String, supplier: Supplier<BlockEntityType<T>>): Supplier<BlockEntityType<T>> {
        return helper?.registerBlockEntityType(name, supplier) ?: throw IllegalStateException("Registry helper not initialized")
    }
}
