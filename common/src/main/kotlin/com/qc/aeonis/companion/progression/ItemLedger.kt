package com.qc.aeonis.companion.progression

import com.qc.aeonis.companion.inventory.CompanionInventory
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * Counting and consuming helpers over [CompanionInventory].
 *
 * These lived as private methods inside the crafting skill, which meant every new skill that
 * needed "how much cobblestone is in the bag" grew its own copy with slightly different slot
 * bounds. One implementation, one set of slot constants, one place to fix.
 *
 * All methods scan the 27 backpack slots plus the two hands; they never touch the four armour
 * slots, so armour is never consumed as crafting material.
 */
object ItemLedger {

    /** Inventory array slots the ledger is allowed to consume from. */
    private val REACHABLE_SLOTS: IntRange =
        CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE

    fun countItem(inventory: CompanionInventory, item: Item): Int {
        var total = 0
        for (slot in REACHABLE_SLOTS) {
            val stack = inventory.getItem(slot)
            if (stack.`is`(item)) total += stack.count
        }
        return total
    }

    fun countTag(inventory: CompanionInventory, tag: TagKey<Item>): Int {
        var total = 0
        for (slot in REACHABLE_SLOTS) {
            val stack = inventory.getItem(slot)
            if (stack.`is`(tag)) total += stack.count
        }
        return total
    }

    fun has(inventory: CompanionInventory, item: Item): Boolean = countItem(inventory, item) > 0

    fun hasTag(inventory: CompanionInventory, tag: TagKey<Item>): Boolean = countTag(inventory, tag) > 0

    /** Slot index of the first stack matching [item], or -1. */
    fun slotOfFirst(inventory: CompanionInventory, item: Item): Int =
        REACHABLE_SLOTS.firstOrNull { inventory.getItem(it).`is`(item) } ?: -1

    /** Slot index of the first stack in [tag], or -1. */
    fun slotOfFirstTag(inventory: CompanionInventory, tag: TagKey<Item>): Int =
        REACHABLE_SLOTS.firstOrNull { inventory.getItem(it).`is`(tag) } ?: -1

    /** Slot index of the first non-empty stack satisfying [predicate], or -1. */
    fun slotOfFirst(inventory: CompanionInventory, predicate: (ItemStack) -> Boolean): Int =
        REACHABLE_SLOTS.firstOrNull {
            val stack = inventory.getItem(it)
            !stack.isEmpty && predicate(stack)
        } ?: -1

    /**
     * Removes [count] of [item], spreading across stacks. Returns true only when the full
     * amount was available, and leaves the inventory untouched when it was not — a partial
     * consume that reports failure would silently eat materials.
     */
    fun consume(inventory: CompanionInventory, item: Item, count: Int): Boolean {
        if (count <= 0) return true
        if (countItem(inventory, item) < count) return false
        var remaining = count
        for (slot in REACHABLE_SLOTS) {
            if (remaining <= 0) break
            val stack = inventory.getItem(slot)
            if (!stack.`is`(item)) continue
            val taken = minOf(remaining, stack.count)
            stack.shrink(taken)
            remaining -= taken
            if (stack.isEmpty) inventory.setItem(slot, ItemStack.EMPTY)
        }
        inventory.setChanged()
        return true
    }

    /** Tag-based counterpart of [consume], with the same all-or-nothing guarantee. */
    fun consumeTag(inventory: CompanionInventory, tag: TagKey<Item>, count: Int): Boolean {
        if (count <= 0) return true
        if (countTag(inventory, tag) < count) return false
        var remaining = count
        for (slot in REACHABLE_SLOTS) {
            if (remaining <= 0) break
            val stack = inventory.getItem(slot)
            if (!stack.`is`(tag)) continue
            val taken = minOf(remaining, stack.count)
            stack.shrink(taken)
            remaining -= taken
            if (stack.isEmpty) inventory.setItem(slot, ItemStack.EMPTY)
        }
        inventory.setChanged()
        return true
    }

    /** True when the tool family in [tag] is present in either hand or the backpack. */
    fun hasTool(inventory: CompanionInventory, tag: TagKey<Item>): Boolean =
        inventory.mainHandItem.`is`(tag) || inventory.offHandItem.`is`(tag) || hasTag(inventory, tag)

    /** Number of backpack slots holding nothing. */
    fun freeSlots(inventory: CompanionInventory): Int =
        REACHABLE_SLOTS.count { inventory.getItem(it).isEmpty }

    /** Deposits a produced stack into the backpack, returning any remainder. */
    fun produce(inventory: CompanionInventory, stack: ItemStack): ItemStack {
        val remainder = inventory.collectDrop(stack)
        inventory.setChanged()
        return remainder
    }
}
