package com.qc.aeonis.companion.progression

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.ui.CompanionDialogueManager
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack

/**
 * Handing food from the companion to the player.
 *
 * Extracted from the hunting skill because the furnace stage delivers cooked meat through
 * exactly the same path, and two copies of "what if their inventory is full" would eventually
 * disagree — one of them losing the player's dinner.
 */
object FoodDelivery {

    /** How many items of one stack are handed over at a time. */
    const val HANDOVER_SIZE = 2

    /**
     * Moves up to [HANDOVER_SIZE] items from the best matching stack to [owner].
     *
     * @param preferCooked when true, cooked meat is offered before anything else.
     * @return true when something was handed over.
     */
    fun deliverFood(
        bot: CompanionBotEntity,
        owner: ServerPlayer,
        level: ServerLevel,
        preferCooked: Boolean = true,
        announcement: String? = null
    ): Boolean {
        val slot = selectFoodSlot(bot, preferCooked) ?: return false
        val stack = bot.inventory.getItem(slot)
        if (stack.isEmpty) return false

        val toGive = stack.split(minOf(HANDOVER_SIZE, stack.count))
        if (stack.isEmpty) bot.inventory.setItem(slot, ItemStack.EMPTY)

        if (!owner.inventory.add(toGive)) {
            // Inventory full: drop it at their feet rather than keeping it, so the food is
            // never silently retained and the "I fed you" message stays truthful.
            val drop = ItemEntity(level, owner.x, owner.y + 0.5, owner.z, toGive)
            drop.setPickUpDelay(0)
            level.addFreshEntity(drop)
        }

        level.playSound(null, owner.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.7f, 1.2f)
        com.qc.aeonis.companion.ui.CompanionDialogueManager.speak(
            bot,
            owner,
            announcement ?: "Seems you are tired or hungry, I have got some ${toGive.hoverName.string}!",
            "FOOD"
        )
        bot.inventory.setChanged()
        return true
    }

    /**
     * Slot of the stack to hand over: cooked meat first, then any other edible item, so a
     * hungry player is never handed raw chicken while cooked beef sits two slots away.
     */
    fun selectFoodSlot(bot: CompanionBotEntity, preferCooked: Boolean): Int? {
        val inventory = bot.inventory
        if (preferCooked) {
            for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
                if (FoodLedger.isCookedMeat(inventory.getItem(slot))) return slot
            }
        }
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && stack.has(DataComponents.FOOD)) return slot
        }
        return null
    }
}
