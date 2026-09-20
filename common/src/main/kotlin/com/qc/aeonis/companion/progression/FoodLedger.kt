package com.qc.aeonis.companion.progression

import com.qc.aeonis.companion.inventory.CompanionInventory
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.chicken.Chicken
import net.minecraft.world.entity.animal.cow.Cow
import net.minecraft.world.entity.animal.goat.Goat
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.rabbit.Rabbit
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Knowledge about food: what is edible, what is raw, what it cooks into, and what burns.
 *
 * ### Why the raw/cooked tables are explicit
 * Minecraft 26.3 has no "raw meat" tag — `#minecraft:meat` covers raw and cooked alike — so
 * the companion cannot ask the game "is this cooked?" and must carry the mapping. The names
 * also dropped their `RAW_` prefix in 26.3 (`Items.BEEF`, not `Items.RAW_BEEF`), so any code
 * that referenced the old constants silently stops compiling; keeping the whole mapping in
 * one table means there is exactly one place to fix when it changes again.
 */
object FoodLedger {

    /**
     * Raw meats, keyed to what they cook into. The mapping is also the smelting table the
     * furnace cooking stage works from.
     */
    val RAW_TO_COOKED: Map<Item, Item> = linkedMapOf(
        Items.BEEF to Items.COOKED_BEEF,
        Items.PORKCHOP to Items.COOKED_PORKCHOP,
        Items.MUTTON to Items.COOKED_MUTTON,
        Items.CHICKEN to Items.COOKED_CHICKEN,
        Items.RABBIT to Items.COOKED_RABBIT,
        Items.COD to Items.COOKED_COD,
        Items.SALMON to Items.COOKED_SALMON
    )

    val RAW_MEATS: Set<Item> = RAW_TO_COOKED.keys
    val COOKED_MEATS: Set<Item> = RAW_TO_COOKED.values.toSet()

    /**
     * Furnace fuels in descending burn-time order. Ordered rather than a set because the
     * cooking stage should spend a plank before it spends a coal lump.
     */
    val FUELS_BY_PREFERENCE: List<Item> = listOf(
        Items.COAL_BLOCK,
        Items.COAL,
        Items.CHARCOAL,
        Items.OAK_PLANKS,
        Items.OAK_LOG,
        Items.STICK
    )

    /**
     * Raw meat a given animal is expected to drop, used to size a hunt before it starts.
     *
     * Values are the vanilla minimums, not averages: underestimating the yield keeps the
     * quota honest, whereas overestimating would send the companion out for meat it already has.
     */
    fun rawMeatEquivalent(animal: Animal): Int = when (animal) {
        is Cow, is Sheep, is Pig, is Goat -> 2
        is Chicken -> 1
        is Rabbit -> 1
        else -> 1
    }

    /** True when the stack carries the food component, i.e. the game considers it edible. */
    fun isEdible(stack: ItemStack): Boolean = !stack.isEmpty && stack.has(DataComponents.FOOD)

    fun isRawMeat(stack: ItemStack): Boolean = !stack.isEmpty && RAW_MEATS.contains(stack.item)

    fun isCookedMeat(stack: ItemStack): Boolean = !stack.isEmpty && COOKED_MEATS.contains(stack.item)

    /** The cooked item a raw stack becomes, or null when it is not cookable. */
    fun cookedVariant(raw: ItemStack): Item? = if (raw.isEmpty) null else RAW_TO_COOKED[raw.item]

    /** True for anything tagged as a furnace fuel. */
    fun isFuel(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (FUELS_BY_PREFERENCE.any { stack.`is`(it) }) return true
        return stack.`is`(ItemTags.PLANKS) || stack.`is`(ItemTags.LOGS)
    }

    fun hasAnyFood(inventory: CompanionInventory): Boolean =
        countEdible(inventory) > 0

    fun countEdible(inventory: CompanionInventory): Int = countWhere(inventory) { isEdible(it) }

    fun countRawMeat(inventory: CompanionInventory): Int = countWhere(inventory) { isRawMeat(it) }

    fun countCookedMeat(inventory: CompanionInventory): Int = countWhere(inventory) { isCookedMeat(it) }

    fun countFuel(inventory: CompanionInventory): Int = countWhere(inventory) { isFuel(it) }

    /** First backpack slot holding raw meat, or -1. */
    fun firstRawMeatSlot(inventory: CompanionInventory): Int =
        ItemLedger.slotOfFirst(inventory) { isRawMeat(it) }

    /** First backpack slot holding a fuel, or -1. */
    fun firstFuelSlot(inventory: CompanionInventory): Int =
        ItemLedger.slotOfFirst(inventory) { isFuel(it) }

    private inline fun countWhere(inventory: CompanionInventory, predicate: (ItemStack) -> Boolean): Int {
        var total = 0
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && predicate(stack)) total += stack.count
        }
        return total
    }
}
