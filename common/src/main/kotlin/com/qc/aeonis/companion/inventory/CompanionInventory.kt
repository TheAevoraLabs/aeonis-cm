package com.qc.aeonis.companion.inventory

import net.minecraft.core.NonNullList
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * True 33-slot container for the Aeonis Companion Bot:
 * - Slots 0..3: Armor (Feet, Legs, Chest, Head)
 * - Slot 4: Mainhand Tool / Weapon
 * - Slot 5: Offhand / Shield / Torches
 * - Slots 6..32: 27-slot Backpack Storage (3x9 grid)
 */
class CompanionInventory : SimpleContainer(CONTAINER_SIZE) {

    companion object {
        const val BOOTS_SLOT = 0
        const val LEGGINGS_SLOT = 1
        const val CHESTPLATE_SLOT = 2
        const val HELMET_SLOT = 3
        const val MAINHAND_SLOT = 4
        const val OFFHAND_SLOT = 5
        const val BACKPACK_START = 6
        const val BACKPACK_SIZE = 27
        const val CONTAINER_SIZE = 33
    }

    var helmet: ItemStack
        get() = getItem(HELMET_SLOT)
        set(value) = setItem(HELMET_SLOT, value)

    var chestplate: ItemStack
        get() = getItem(CHESTPLATE_SLOT)
        set(value) = setItem(CHESTPLATE_SLOT, value)

    var leggings: ItemStack
        get() = getItem(LEGGINGS_SLOT)
        set(value) = setItem(LEGGINGS_SLOT, value)

    var boots: ItemStack
        get() = getItem(BOOTS_SLOT)
        set(value) = setItem(BOOTS_SLOT, value)

    var mainHandItem: ItemStack
        get() = getItem(MAINHAND_SLOT)
        set(value) = setItem(MAINHAND_SLOT, value)

    var offHandItem: ItemStack
        get() = getItem(OFFHAND_SLOT)
        set(value) = setItem(OFFHAND_SLOT, value)

    fun getEquipmentBySlot(slot: EquipmentSlot): ItemStack {
        return when (slot) {
            EquipmentSlot.HEAD -> helmet
            EquipmentSlot.CHEST -> chestplate
            EquipmentSlot.LEGS -> leggings
            EquipmentSlot.FEET -> boots
            EquipmentSlot.MAINHAND -> mainHandItem
            EquipmentSlot.OFFHAND -> offHandItem
            else -> ItemStack.EMPTY
        }
    }

    fun setEquipmentBySlot(slot: EquipmentSlot, stack: ItemStack) {
        when (slot) {
            EquipmentSlot.HEAD -> helmet = stack
            EquipmentSlot.CHEST -> chestplate = stack
            EquipmentSlot.LEGS -> leggings = stack
            EquipmentSlot.FEET -> boots = stack
            EquipmentSlot.MAINHAND -> mainHandItem = stack
            EquipmentSlot.OFFHAND -> offHandItem = stack
            else -> {}
        }
    }

    /**
     * Attempts to store collected items into the 27 backpack slots first, then offhand if empty.
     */
    fun collectDrop(stack: ItemStack): ItemStack {
        if (stack.isEmpty) return ItemStack.EMPTY
        var remaining = stack.copy()

        // 1. Try to merge into existing backpack stacks
        for (i in BACKPACK_START until CONTAINER_SIZE) {
            val existing = getItem(i)
            if (ItemStack.isSameItemSameComponents(existing, remaining)) {
                val space = existing.maxStackSize - existing.count
                if (space > 0) {
                    val toAdd = minOf(space, remaining.count)
                    existing.grow(toAdd)
                    remaining.shrink(toAdd)
                    setItem(i, existing)
                    if (remaining.isEmpty) return ItemStack.EMPTY
                }
            }
        }

        // 2. Try to insert into first empty backpack slot
        for (i in BACKPACK_START until CONTAINER_SIZE) {
            if (getItem(i).isEmpty) {
                setItem(i, remaining)
                return ItemStack.EMPTY
            }
        }

        return remaining
    }

    fun isBackpackFull(): Boolean {
        for (i in BACKPACK_START until CONTAINER_SIZE) {
            val item = getItem(i)
            if (item.isEmpty || item.count < item.maxStackSize) {
                return false
            }
        }
        return true
    }

    /**
     * Finds the best equipped or stored tool for breaking a given block state.
     */
    fun findBestTool(state: BlockState): ItemStack {
        var bestTool = mainHandItem
        var bestSpeed = bestTool.getDestroySpeed(state)

        for (i in BACKPACK_START until CONTAINER_SIZE) {
            val item = getItem(i)
            if (!item.isEmpty) {
                val speed = item.getDestroySpeed(state)
                if (speed > bestSpeed) {
                    bestSpeed = speed
                    bestTool = item
                }
            }
        }
        return bestTool
    }

    fun save(output: ValueOutput) {
        val list = output.list("Inventory", ItemStack.CODEC)
        storeAsItemList(list)
    }

    fun load(input: ValueInput) {
        input.list("Inventory", ItemStack.CODEC).ifPresent {
            fromItemList(it)
        }
    }
}
