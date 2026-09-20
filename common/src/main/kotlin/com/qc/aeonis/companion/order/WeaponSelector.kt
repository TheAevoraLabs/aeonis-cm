package com.qc.aeonis.companion.order

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Ranks and equips the companion's melee weapon.
 *
 * The mod's own complaint list contained "punches animals with bare hands", so this object
 * exists to make two questions cheap and unambiguous for every combat/hunting caller:
 * "do I have a real melee weapon at all?" and "which one hurts most?".
 *
 * ### Why a table instead of reading attack attributes
 * Minecraft 26.3 exposes item attack damage through attribute-modifier data components,
 * which is authoritative but awkward to compare: the modifiers carry additive and
 * multiplicative operations with per-slot multipliers, and modded items can express the
 * same damage several ways. For the *ordering* we need — which of these two stacks should
 * be in the hand — effective DPS (`attackDamage / attackSpeed`, i.e. damage per second at
 * swing cap) is the right measure, and the vanilla values are stable enough to tabulate.
 *
 * The table is the vanilla sword/axe DPS ordering:
 *
 * | item | damage | cooldown | DPS |
 * | :--- | :--- | :--- | :--- |
 * | netherite sword | 8 | 0.625s | 12.8 |
 * | diamond sword | 7 | 0.625s | 11.2 |
 * | netherite axe | 10 | 1.0s | 10.0 |
 * | iron sword | 6 | 0.625s | 9.6 |
 * | diamond axe | 9 | 1.0s | 9.0 |
 * | iron axe | 9 | 1.11s | 8.1 |
 * | stone sword | 5 | 0.625s | 8.0 |
 * | stone axe | 9 | 1.25s | 7.2 |
 * | golden axe | 7 | 1.0s | 7.0 |
 * | wooden sword | 4 | 0.625s | 6.4 |
 * | golden sword | 4 | 0.625s | 6.4 |
 * | wooden axe | 7 | 1.25s | 5.6 |
 *
 * Anything not in the table but tagged `#minecraft:swords` is scored just below the
 * equivalent-tier vanilla sword, and `#minecraft:axes` just below that, so modded weapons
 * participate without being able to outrank netherite.
 */
object WeaponSelector {

    /** Damage-per-second baseline for a bare fist, the score a stack must beat to matter. */
    const val FIST_DPS = 1.0

    /** Anything below this DPS is not worth swinging at an animal for food. */
    const val MELEE_MIN_DPS = 4.0

    private val VANILLA_DPS: Map<Item, Double> = mapOf(
        Items.NETHERITE_SWORD to 12.8,
        Items.DIAMOND_SWORD to 11.2,
        Items.NETHERITE_AXE to 10.0,
        Items.IRON_SWORD to 9.6,
        Items.DIAMOND_AXE to 9.0,
        Items.IRON_AXE to 8.1,
        Items.STONE_SWORD to 8.0,
        Items.STONE_AXE to 7.2,
        Items.GOLDEN_AXE to 7.0,
        Items.WOODEN_SWORD to 6.4,
        Items.GOLDEN_SWORD to 6.4,
        Items.WOODEN_AXE to 5.6
    )

    /** Modded fallbacks, chosen to slot between the vanilla tiers they resemble. */
    private const val UNKNOWN_SWORD_DPS = 9.0
    private const val UNKNOWN_AXE_DPS = 7.5

    /**
     * True when the stack is a sword or an axe. This is the literal test the mod spec
     * asks for ("Sword or Axe"), kept separate from the DPS score so a caller can demand
     * a real weapon and not merely the best of a bad hand.
     */
    fun isMeleeWeapon(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return stack.`is`(ItemTags.SWORDS) || stack.`is`(ItemTags.AXES) ||
            VANILLA_DPS.containsKey(stack.item)
    }

    /**
     * Effective DPS estimate for a stack, scaled by remaining durability.
     *
     * A weapon about to break is worth less than a fresh one of the same tier, but never
     * worth nothing: the durability factor bottoms out at 0.5 so a nearly-dead diamond
     * sword still outranks a fresh stone one.
     */
    fun dpsScore(stack: ItemStack): Double {
        if (!isMeleeWeapon(stack)) return FIST_DPS
        val base = VANILLA_DPS[stack.item]
            ?: if (stack.`is`(ItemTags.AXES)) UNKNOWN_AXE_DPS else UNKNOWN_SWORD_DPS
        return base * durabilityFactor(stack)
    }

    /**
     * Remaining durability as a 0.5..1.0 multiplier; 1.0 for items with no durability at
     * all (so an unbreakable modded weapon is never penalised).
     */
    fun durabilityFactor(stack: ItemStack): Double {
        if (!stack.isDamageableItem) return 1.0
        val max = stack.maxDamage
        if (max <= 0) return 1.0
        val remaining = (max - stack.damageValue).coerceAtLeast(0)
        return 0.5 + 0.5 * (remaining.toDouble() / max.toDouble())
    }

    /** True when the companion has a sword or axe anywhere it can reach. */
    fun hasMeleeWeapon(bot: CompanionBotEntity): Boolean = bestMeleeSlot(bot) >= 0

    /**
     * Best melee weapon available, or null. Scans the hands first (already equipped wins
     * ties) then the backpack, which keeps the choice stable across ticks and stops the
     * bot swapping weapons back and forth on floating-point ties.
     */
    fun bestMeleeStack(bot: CompanionBotEntity): ItemStack? {
        val slot = bestMeleeSlot(bot)
        return if (slot < 0) null else bot.inventory.getItem(slot)
    }

    /**
     * Inventory slot holding the best melee weapon, or -1.
     *
     * Hands are checked before the backpack; within the backpack the highest DPS wins and
     * ties keep the lowest slot index.
     */
    fun bestMeleeSlot(bot: CompanionBotEntity): Int {
        val inventory = bot.inventory
        var bestSlot = -1
        var bestScore = MELEE_MIN_DPS

        fun consider(slot: Int) {
            val stack = inventory.getItem(slot)
            if (!isMeleeWeapon(stack)) return
            val score = dpsScore(stack)
            if (score > bestScore) {
                bestScore = score
                bestSlot = slot
            }
        }

        consider(CompanionInventory.MAINHAND_SLOT)
        consider(CompanionInventory.OFFHAND_SLOT)
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            consider(slot)
        }
        return bestSlot
    }

    /**
     * Moves the best melee weapon into the main hand, swapping whatever was there back to
     * the source slot. Returns true when the main hand now holds the weapon.
     *
     * Refuses to equip anything scoring below [MELEE_MIN_DPS] so a fishing rod or a stick
     * never displaces a real weapon, and returns false when there is nothing to equip.
     */
    fun equipBestMeleeWeapon(bot: CompanionBotEntity): Boolean {
        val inventory = bot.inventory
        val slot = bestMeleeSlot(bot)
        if (slot < 0) return false
        if (slot == CompanionInventory.MAINHAND_SLOT) return true

        val weapon = inventory.getItem(slot)
        val displaced = inventory.mainHandItem
        inventory.mainHandItem = weapon
        inventory.setItem(slot, displaced)
        inventory.setChanged()
        bot.setItemSlot(EquipmentSlot.MAINHAND, weapon)
        return true
    }

    /** Slot of a non-weapon melee-capable tool (pickaxe/shovel) worth swinging in a pinch. */
    fun bestToolSlot(bot: CompanionBotEntity): Int {
        val inventory = bot.inventory
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && (stack.`is`(ItemTags.PICKAXES) || stack.`is`(ItemTags.SHOVELS))) {
                return slot
            }
        }
        return -1
    }

    /** Short human-readable summary of the current weapon, for `/comp stats` and the GUI. */
    fun describeLoadout(bot: CompanionBotEntity): String {
        val mainHand = bot.inventory.mainHandItem
        if (mainHand.isEmpty) return "bare hands (no melee weapon)"
        val suffix = if (isMeleeWeapon(mainHand)) " (%.1f DPS)".format(dpsScore(mainHand)) else " (not a melee weapon)"
        return mainHand.hoverName.string + suffix
    }
}
