package com.qc.aeonis.companion.inventory

import com.qc.aeonis.inventory.AeonisMenus
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Container menu for the Companion Bot, exposing 33 companion slots
 * (armor, mainhand, offhand, 27-slot backpack) and the player's 36-slot inventory.
 */
class CompanionMenu(
    syncId: Int,
    playerInventory: Inventory,
    val companionContainer: Container
) : AbstractContainerMenu(AeonisMenus.COMPANION_MENU.get(), syncId) {

    companion object {
        fun createClientMenu(syncId: Int, playerInventory: Inventory): CompanionMenu {
            return CompanionMenu(syncId, playerInventory, SimpleContainer(CompanionInventory.CONTAINER_SIZE))
        }
    }

    init {
        checkContainerSize(companionContainer, CompanionInventory.CONTAINER_SIZE)

        // 1. Companion Armor slots (0: Boots, 1: Legs, 2: Chest, 3: Helmet)
        for (i in 0 until 4) {
            val slotIndex = i
            addSlot(object : Slot(companionContainer, slotIndex, 8, 18 + (3 - i) * 18) {
                override fun getMaxStackSize(): Int = 1
            })
        }

        // 2. Companion Hands (4: Mainhand, 5: Offhand)
        addSlot(Slot(companionContainer, CompanionInventory.MAINHAND_SLOT, 30, 27))
        addSlot(Slot(companionContainer, CompanionInventory.OFFHAND_SLOT, 30, 54))

        // 3. Companion Backpack (slots 6..32, 3x9 grid)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                val index = CompanionInventory.BACKPACK_START + col + row * 9
                addSlot(Slot(companionContainer, index, 62 + col * 18, 18 + row * 18))
            }
        }

        // 4. Player Inventory (3x9 grid)
        val playerInvY = 84
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 62 + col * 18, playerInvY + row * 18))
            }
        }

        // 5. Player Hotbar (1x9)
        val hotbarY = 142
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 62 + col * 18, hotbarY))
        }
    }

    override fun stillValid(player: Player): Boolean = companionContainer.stillValid(player)

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        var itemStack = ItemStack.EMPTY
        val slot = slots[index]

        if (slot.hasItem()) {
            val stackInSlot = slot.item
            itemStack = stackInSlot.copy()

            // If item is in Companion container (slots 0..32)
            if (index < CompanionInventory.CONTAINER_SIZE) {
                // Move from companion to player inventory (slots 33..68)
                if (!moveItemStackTo(stackInSlot, CompanionInventory.CONTAINER_SIZE, slots.size, true)) {
                    return ItemStack.EMPTY
                }
            } else {
                // Move from player to companion backpack (slots 6..32)
                if (!moveItemStackTo(stackInSlot, CompanionInventory.BACKPACK_START, CompanionInventory.CONTAINER_SIZE, false)) {
                    return ItemStack.EMPTY
                }
            }

            if (stackInSlot.isEmpty) {
                slot.set(ItemStack.EMPTY)
            } else {
                slot.setChanged()
            }
        }

        return itemStack
    }
}
