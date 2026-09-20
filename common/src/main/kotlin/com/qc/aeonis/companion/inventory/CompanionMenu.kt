package com.qc.aeonis.companion.inventory

import com.qc.aeonis.inventory.AeonisMenus
import net.minecraft.tags.ItemTags
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ArmorSlot
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.*

/**
 * Container menu for the Companion Bot, exposing 33 companion slots
 * (4 armor, mainhand weapon, offhand shield, 27-slot backpack)
 * and the player's 36-slot inventory.
 */
class CompanionMenu(
    syncId: Int,
    val playerInventory: Inventory,
    val companionContainer: Container
) : AbstractContainerMenu(AeonisMenus.COMPANION_MENU.get(), syncId) {

    companion object {
        fun createClientMenu(syncId: Int, playerInventory: Inventory): CompanionMenu {
            return CompanionMenu(syncId, playerInventory, SimpleContainer(CompanionInventory.CONTAINER_SIZE))
        }

        private val ARMOR_SLOTS = arrayOf(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
        )

        private val ARMOR_ICONS = arrayOf(
            InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
        )
    }

    init {
        checkContainerSize(companionContainer, CompanionInventory.CONTAINER_SIZE)
        companionContainer.startOpen(playerInventory.player)

        // 1. Companion Armor slots (Head = 3, Chest = 2, Legs = 1, Feet = 0)
        // Laid out vertically on the left at x=8, y=18, 36, 54, 72
        addSlot(ArmorSlot(companionContainer, playerInventory.player, EquipmentSlot.HEAD, CompanionInventory.HELMET_SLOT, 8, 18, ARMOR_ICONS[0]))
        addSlot(ArmorSlot(companionContainer, playerInventory.player, EquipmentSlot.CHEST, CompanionInventory.CHESTPLATE_SLOT, 8, 36, ARMOR_ICONS[1]))
        addSlot(ArmorSlot(companionContainer, playerInventory.player, EquipmentSlot.LEGS, CompanionInventory.LEGGINGS_SLOT, 8, 54, ARMOR_ICONS[2]))
        addSlot(ArmorSlot(companionContainer, playerInventory.player, EquipmentSlot.FEET, CompanionInventory.BOOTS_SLOT, 8, 72, ARMOR_ICONS[3]))

        // 2. Companion Hands (4: Mainhand Weapon, 5: Offhand Shield)
        // Positioned next to 3D preview dock
        addSlot(object : Slot(companionContainer, CompanionInventory.MAINHAND_SLOT, 28, 36) {
            override fun getMaxStackSize(): Int = 1
        })
        addSlot(object : Slot(companionContainer, CompanionInventory.OFFHAND_SLOT, 28, 54) {
            override fun getNoItemIcon() = InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD
        })

        // 3. Companion Backpack (slots 6..32, 3x9 grid at x=98, y=18)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                val index = CompanionInventory.BACKPACK_START + col + row * 9
                addSlot(Slot(companionContainer, index, 98 + col * 18, 18 + row * 18))
            }
        }

        // 4. Player Inventory (3x9 grid at x=98, y=84)
        val playerInvY = 84
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 98 + col * 18, playerInvY + row * 18))
            }
        }

        // 5. Player Hotbar (1x9 at x=98, y=142)
        val hotbarY = 142
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 98 + col * 18, hotbarY))
        }
    }

    override fun stillValid(player: Player): Boolean = companionContainer.stillValid(player)

    override fun removed(player: Player) {
        super.removed(player)
        companionContainer.stopOpen(player)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        var itemStack = ItemStack.EMPTY
        val slot = slots[index]

        if (slot.hasItem()) {
            val stackInSlot = slot.item
            itemStack = stackInSlot.copy()

            // Case A: Shift-click from Companion Container (slots 0..32)
            if (index < CompanionInventory.CONTAINER_SIZE) {
                // Move from companion to player inventory (slots 33..68)
                if (!moveItemStackTo(stackInSlot, CompanionInventory.CONTAINER_SIZE, slots.size, true)) {
                    return ItemStack.EMPTY
                }
            }
            // Case B: Shift-click from Player Inventory (slots 33..68)
            else {
                val equipSlot = player.getEquipmentSlotForItem(stackInSlot)
                var moved = false

                // 1. Try to auto-equip armor
                when (equipSlot) {
                    EquipmentSlot.HEAD -> moved = moveItemStackTo(stackInSlot, 0, 1, false)
                    EquipmentSlot.CHEST -> moved = moveItemStackTo(stackInSlot, 1, 2, false)
                    EquipmentSlot.LEGS -> moved = moveItemStackTo(stackInSlot, 2, 3, false)
                    EquipmentSlot.FEET -> moved = moveItemStackTo(stackInSlot, 3, 4, false)
                    EquipmentSlot.OFFHAND -> moved = moveItemStackTo(stackInSlot, 5, 6, false)
                    else -> {}
                }

                // 2. Try to auto-equip melee weapon / tool to mainhand if empty
                if (!moved && isWeaponOrTool(stackInSlot) && !slots[4].hasItem()) {
                    moved = moveItemStackTo(stackInSlot, 4, 5, false)
                }

                // 3. Otherwise move to companion backpack (slots 6..32)
                if (!moved) {
                    if (!moveItemStackTo(stackInSlot, CompanionInventory.BACKPACK_START, CompanionInventory.CONTAINER_SIZE, false)) {
                        return ItemStack.EMPTY
                    }
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

    private fun isWeaponOrTool(stack: ItemStack): Boolean {
        return stack.`is`(ItemTags.SWORDS) || stack.`is`(ItemTags.AXES) || stack.`is`(ItemTags.PICKAXES) ||
                stack.`is`(ItemTags.SHOVELS) || stack.`is`(ItemTags.HOES) ||
                stack.`is`(Items.BOW) || stack.`is`(Items.CROSSBOW) || stack.`is`(Items.TRIDENT) || stack.`is`(Items.MACE)
    }
}
