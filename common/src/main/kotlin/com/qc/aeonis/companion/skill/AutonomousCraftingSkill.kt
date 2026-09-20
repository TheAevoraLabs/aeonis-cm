package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.order.WeaponSelector
import com.qc.aeonis.companion.progression.CraftingTableUnlock
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/**
 * Autonomous crafting skill enabling 2x2 hand-crafting (planks, sticks, torches, table)
 * and 3x3 table-crafting (bread, tools, gear) when a crafting table is nearby or in inventory.
 */
class AutonomousCraftingSkill(private val tableUnlock: CraftingTableUnlock) : CompanionSkill {
    override val id: String = "autonomous_crafting"
    override val name: String = "Autonomous Crafting Engine"
    override val category: SkillCategory = SkillCategory.CRAFTING
    override val priority: Int = 60

    private var craftingCooldown: Int = 0

    /**
     * True once the player has crafted or placed a crafting table at least once.
     *
     * This is the hard gate on the crafting-table recipe; see [CraftingTableUnlock] for why it
     * is a one-way latch rather than a live proximity test.
     */
    private fun tableKnowledgeGranted(): Boolean = tableUnlock.isUnlocked

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        if (craftingCooldown > 0) return false
        val inv = bot.inventory

        // 1. Check 2x2 Hand Crafting options
        val hasLogs = countItemTag(inv, ItemTags.LOGS) > 0
        val plankCount = countItemTag(inv, ItemTags.PLANKS)
        val stickCount = countItem(inv, Items.STICK)
        val torchCount = countItem(inv, Items.TORCH)
        val hasCoal = countItem(inv, Items.COAL) > 0 || countItem(inv, Items.CHARCOAL) > 0
        val hasTable = countItem(inv, Items.CRAFTING_TABLE) > 0 || isCraftingTableNearby(bot)

        if (hasLogs) return true
        if (plankCount >= 2 && stickCount < 8) return true
        if (stickCount >= 1 && hasCoal && torchCount < 16) return true
        // Crafting a table of our own requires the player to have shown us one first. Without
        // that, the companion waits rather than bootstrapping its own tool chain.
        if (tableKnowledgeGranted() && plankCount >= 4 && !hasTable) return true

        // 2. Check 3x3 Table Crafting options
        val wheatCount = countItem(inv, Items.WHEAT)
        if (wheatCount >= 3 && hasTable) return true

        val hasPickaxe = hasTool(inv, ItemTags.PICKAXES)
        if (!hasPickaxe && stickCount >= 2 && (plankCount >= 3 || countItem(inv, Items.COBBLESTONE) >= 3 || countItem(inv, Items.IRON_INGOT) >= 3)) {
            return true
        }

        // A sword is the companion's hunting licence; it must never hunt bare-handed.
        if (!WeaponSelector.hasMeleeWeapon(bot) && hasTable && stickCount >= 1 && plankCount >= 2) return true

        return false
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        if (craftingCooldown > 0) {
            craftingCooldown--
            return SkillResult.IDLE
        }

        val inv = bot.inventory
        val level = bot.level()

        // 1. Craft Logs into Planks (1 Log -> 4 Planks)
        val logSlot = findSlotWithTag(inv, ItemTags.LOGS)
        if (logSlot != -1) {
            val logStack = inv.getItem(logSlot)
            val plankItem = getPlankForLog(logStack.item)
            logStack.shrink(1)
            if (logStack.isEmpty) inv.setItem(logSlot, ItemStack.EMPTY)
            inv.collectDrop(ItemStack(plankItem, 4))
            inv.setChanged()
            playCraftSound(bot)
            craftingCooldown = 20
            return SkillResult(SkillStatus.SUCCESS, "Crafted 4 ${ItemStack(plankItem).hoverName.string}", cooldownTicks = 20)
        }

        // 2. Craft Planks into Sticks (2 Planks -> 4 Sticks if < 8 sticks)
        val stickCount = countItem(inv, Items.STICK)
        val plankCount = countItemTag(inv, ItemTags.PLANKS)
        if (stickCount < 8 && plankCount >= 2) {
            if (consumeFromTag(inv, ItemTags.PLANKS, 2)) {
                inv.collectDrop(ItemStack(Items.STICK, 4))
                inv.setChanged()
                playCraftSound(bot)
                craftingCooldown = 20
                return SkillResult(SkillStatus.SUCCESS, "Crafted 4 Sticks", cooldownTicks = 20)
            }
        }

        // 3. Craft Sticks + Coal into Torches (1 Stick + 1 Coal -> 4 Torches if < 16 torches)
        val torchCount = countItem(inv, Items.TORCH)
        if (torchCount < 16 && stickCount >= 1 && (countItem(inv, Items.COAL) > 0 || countItem(inv, Items.CHARCOAL) > 0)) {
            val consumedCoal = consumeItem(inv, Items.COAL, 1) || consumeItem(inv, Items.CHARCOAL, 1)
            if (consumedCoal && consumeItem(inv, Items.STICK, 1)) {
                inv.collectDrop(ItemStack(Items.TORCH, 4))
                inv.setChanged()
                playCraftSound(bot)
                craftingCooldown = 20
                return SkillResult(SkillStatus.SUCCESS, "Crafted 4 Torches", cooldownTicks = 20)
            }
        }

        // 4. Craft Crafting Table (4 Planks -> 1 Crafting Table if none available).
        //    Hard-gated on the player-first rule: see CraftingTableUnlock.
        val hasTable = countItem(inv, Items.CRAFTING_TABLE) > 0 || isCraftingTableNearby(bot)
        if (tableKnowledgeGranted() && !hasTable && plankCount >= 4) {
            if (consumeFromTag(inv, ItemTags.PLANKS, 4)) {
                inv.collectDrop(ItemStack(Items.CRAFTING_TABLE, 1))
                inv.setChanged()
                playCraftSound(bot)
                craftingCooldown = 20
                return SkillResult(SkillStatus.SUCCESS, "Crafted Crafting Table", cooldownTicks = 20)
            }
        }

        // 5. 3x3 Table Crafting: Wheat -> Bread (3 Wheat -> 1 Bread)
        val wheatCount = countItem(inv, Items.WHEAT)
        if (wheatCount >= 3 && hasTable) {
            if (consumeItem(inv, Items.WHEAT, 3)) {
                inv.collectDrop(ItemStack(Items.BREAD, 1))
                inv.setChanged()
                playCraftSound(bot)
                craftingCooldown = 20
                return SkillResult(SkillStatus.SUCCESS, "Crafted Bread", cooldownTicks = 20)
            }
        }

        // 6. 3x3 Table Crafting: a melee weapon when the companion has none. This is what turns
        //    "cannot hunt because it would be punching" into "crafts a sword, then hunts".
        if (!WeaponSelector.hasMeleeWeapon(bot) && hasTable && stickCount >= 1 && plankCount >= 2) {
            if (consumeFromTag(inv, ItemTags.PLANKS, 2) && consumeItem(inv, Items.STICK, 1)) {
                inv.collectDrop(ItemStack(Items.WOODEN_SWORD))
                inv.setChanged()
                playCraftSound(bot)
                return SkillResult(SkillStatus.SUCCESS, "Crafted a Wooden Sword", cooldownTicks = 20)
            }
        }

        // 7. 3x3 Table Crafting: Tools (Pickaxe)
        if (!hasTool(inv, ItemTags.PICKAXES) && hasTable && stickCount >= 2) {
            val ironCount = countItem(inv, Items.IRON_INGOT)
            val stoneCount = countItem(inv, Items.COBBLESTONE)
            if (ironCount >= 3) {
                consumeItem(inv, Items.IRON_INGOT, 3)
                consumeItem(inv, Items.STICK, 2)
                inv.collectDrop(ItemStack(Items.IRON_PICKAXE))
                inv.setChanged()
                playCraftSound(bot)
                return SkillResult(SkillStatus.SUCCESS, "Crafted Iron Pickaxe", cooldownTicks = 20)
            } else if (stoneCount >= 3) {
                consumeItem(inv, Items.COBBLESTONE, 3)
                consumeItem(inv, Items.STICK, 2)
                inv.collectDrop(ItemStack(Items.STONE_PICKAXE))
                inv.setChanged()
                playCraftSound(bot)
                return SkillResult(SkillStatus.SUCCESS, "Crafted Stone Pickaxe", cooldownTicks = 20)
            } else if (plankCount >= 3) {
                consumeFromTag(inv, ItemTags.PLANKS, 3)
                consumeItem(inv, Items.STICK, 2)
                inv.collectDrop(ItemStack(Items.WOODEN_PICKAXE))
                inv.setChanged()
                playCraftSound(bot)
                return SkillResult(SkillStatus.SUCCESS, "Crafted Wooden Pickaxe", cooldownTicks = 20)
            }
        }

        return SkillResult.IDLE
    }

    private fun isCraftingTableNearby(bot: CompanionBotEntity): Boolean {
        val level = bot.level()
        val pos = bot.blockPosition()
        return BlockPos.betweenClosedStream(pos.offset(-4, -2, -4), pos.offset(4, 2, 4)).anyMatch {
            level.getBlockState(it).`is`(Blocks.CRAFTING_TABLE)
        }
    }

    private fun countItem(inv: CompanionInventory, item: Item): Int {
        var total = 0
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = inv.getItem(i)
            if (stack.`is`(item)) total += stack.count
        }
        return total
    }

    private fun countItemTag(inv: CompanionInventory, tag: net.minecraft.tags.TagKey<Item>): Int {
        var total = 0
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = inv.getItem(i)
            if (stack.`is`(tag)) total += stack.count
        }
        return total
    }

    private fun hasTool(inv: CompanionInventory, tag: net.minecraft.tags.TagKey<Item>): Boolean {
        if (inv.mainHandItem.`is`(tag) || inv.offHandItem.`is`(tag)) return true
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            if (inv.getItem(i).`is`(tag)) return true
        }
        return false
    }

    private fun findSlotWithTag(inv: CompanionInventory, tag: net.minecraft.tags.TagKey<Item>): Int {
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            if (inv.getItem(i).`is`(tag)) return i
        }
        return -1
    }

    private fun consumeItem(inv: CompanionInventory, item: Item, count: Int): Boolean {
        var remaining = count
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = inv.getItem(i)
            if (stack.`is`(item)) {
                val take = minOf(remaining, stack.count)
                stack.shrink(take)
                remaining -= take
                if (stack.isEmpty) inv.setItem(i, ItemStack.EMPTY)
                if (remaining <= 0) return true
            }
        }
        return remaining <= 0
    }

    private fun consumeFromTag(inv: CompanionInventory, tag: net.minecraft.tags.TagKey<Item>, count: Int): Boolean {
        var remaining = count
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = inv.getItem(i)
            if (stack.`is`(tag)) {
                val take = minOf(remaining, stack.count)
                stack.shrink(take)
                remaining -= take
                if (stack.isEmpty) inv.setItem(i, ItemStack.EMPTY)
                if (remaining <= 0) return true
            }
        }
        return remaining <= 0
    }

    private fun getPlankForLog(log: Item): Item {
        return when (log) {
            Items.BIRCH_LOG, Items.BIRCH_WOOD -> Items.BIRCH_PLANKS
            Items.SPRUCE_LOG, Items.SPRUCE_WOOD -> Items.SPRUCE_PLANKS
            Items.JUNGLE_LOG, Items.JUNGLE_WOOD -> Items.JUNGLE_PLANKS
            Items.ACACIA_LOG, Items.ACACIA_WOOD -> Items.ACACIA_PLANKS
            Items.DARK_OAK_LOG, Items.DARK_OAK_WOOD -> Items.DARK_OAK_PLANKS
            Items.MANGROVE_LOG, Items.MANGROVE_WOOD -> Items.MANGROVE_PLANKS
            Items.CHERRY_LOG, Items.CHERRY_WOOD -> Items.CHERRY_PLANKS
            else -> Items.OAK_PLANKS
        }
    }

    private fun playCraftSound(bot: CompanionBotEntity) {
        bot.level().playSound(null, bot.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6f, 1.5f)
    }
}
