package com.qc.aeonis.companion.skill

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.chicken.Chicken
import net.minecraft.world.entity.animal.cow.Cow
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.SwingAnimation

/**
 * Autonomous skill that monitors owner hunger, feeds the player from backpack reserves,
 * or actively hunts nearby animals (prioritizing sheep, cows, pigs) to secure food.
 */
class HuntingFeedingSkill : CompanionSkill {
    override val id: String = "hunting_feeding"
    override val name: String = "Hunting & Feeding"
    override val category: SkillCategory = SkillCategory.SURVIVAL
    override val priority: Int = 85 // Higher priority than general follow/idle

    private var targetAnimal: Animal? = null
    private var attackCooldown: Int = 0

    override fun canActivate(bot: CompanionBotEntity): Boolean {
        val owner = bot.getOwnerPlayer() ?: return false
        val isHungry = owner.foodData.foodLevel < 14
        val hasFood = hasEdibleFoodInBackpack(bot)
        if (isHungry) return true

        // Idle proactive hunting if low food reserves and animals nearby
        return !hasFood && findNearbyHuntableAnimal(bot) != null
    }

    override fun tick(bot: CompanionBotEntity): SkillResult {
        val serverLevel = bot.level() as? ServerLevel ?: return SkillResult.FAILURE
        val owner = bot.getOwnerPlayer() ?: return SkillResult.FAILURE

        if (attackCooldown > 0) attackCooldown--

        // Step 1: Feed owner if hungry and bot has food in backpack
        if (owner.foodData.foodLevel < 18 && hasEdibleFoodInBackpack(bot)) {
            val distSqr = bot.distanceToSqr(owner)
            if (distSqr <= 9.0) { // Within 3 blocks
                val delivered = deliverFoodToPlayer(bot, owner, serverLevel)
                if (delivered) {
                    return SkillResult(SkillStatus.SUCCESS, "Delivered food to owner", cooldownTicks = 40)
                }
            } else {
                bot.navigation.moveTo(owner, 1.25)
                return SkillResult(SkillStatus.RUNNING, "Approaching hungry owner", cooldownTicks = 0)
            }
        }

        // Step 2: Proactive hunting for food (prioritizing sheep)
        var animal = targetAnimal
        if (animal == null || !animal.isAlive) {
            animal = findNearbyHuntableAnimal(bot)
            targetAnimal = animal
        }

        if (animal != null && animal.isAlive) {
            val distSqr = bot.distanceToSqr(animal)
            bot.lookControl.setLookAt(animal, 30.0f, 30.0f)

            // Equip best weapon
            equipBestWeapon(bot)

            if (distSqr <= 7.0) { // Melee range
                if (attackCooldown <= 0) {
                    val surrogate = bot.getOrCreateSurrogate()
                    val hit = surrogate?.attackEntity(animal) ?: bot.doHurtTarget(serverLevel, animal)
                    if (hit) {
                        bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true)
                        serverLevel.playSound(null, bot.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 0.8f, 1.0f)
                    }
                    attackCooldown = 15
                }
                return SkillResult(SkillStatus.RUNNING, "Hunting ${animal.type.description.string}", cooldownTicks = 0)
            } else {
                bot.navigation.moveTo(animal, 1.3)
                return SkillResult(SkillStatus.RUNNING, "Tracking ${animal.type.description.string}", cooldownTicks = 0)
            }
        }

        return SkillResult.IDLE
    }

    private fun hasEdibleFoodInBackpack(bot: CompanionBotEntity): Boolean {
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = bot.inventory.getItem(i)
            if (!stack.isEmpty && stack.has(DataComponents.FOOD)) {
                return true
            }
        }
        return false
    }

    private fun deliverFoodToPlayer(bot: CompanionBotEntity, owner: net.minecraft.server.level.ServerPlayer, level: ServerLevel): Boolean {
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = bot.inventory.getItem(i)
            if (!stack.isEmpty && stack.has(DataComponents.FOOD)) {
                val toGive = stack.split(minOf(2, stack.count))
                val added = owner.inventory.add(toGive)
                if (!added) {
                    // Toss at player feet
                    val drop = ItemEntity(level, owner.x, owner.y + 0.5, owner.z, toGive)
                    drop.setPickUpDelay(0)
                    level.addFreshEntity(drop)
                }

                level.playSound(null, owner.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.7f, 1.2f)
                owner.sendSystemMessage(
                    Component.literal("§6[Companion] §7I noticed you were hungry! Here is some §e${toGive.hoverName.string}§7.")
                )
                bot.inventory.setChanged()
                return true
            }
        }
        return false
    }

    private fun findNearbyHuntableAnimal(bot: CompanionBotEntity): Animal? {
        val serverLevel = bot.level() as? ServerLevel ?: return null
        val animals = serverLevel.getEntitiesOfClass(
            Animal::class.java, bot.boundingBox.inflate(20.0)
        ) { it.isAlive }

        // Priority: Sheep ("hunt for sheep") > Cow > Pig > Chicken > Other
        return animals.minByOrNull { animal ->
            val priorityScore = when (animal) {
                is Sheep -> 0
                is Cow -> 1
                is Pig -> 2
                is Chicken -> 3
                else -> 4
            }
            priorityScore * 100.0 + bot.distanceToSqr(animal)
        }
    }

    private fun equipBestWeapon(bot: CompanionBotEntity) {
        var bestSlot = -1
        var bestDamage = 1.0f

        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = bot.inventory.getItem(i)
            if (stack.`is`(ItemTags.SWORDS) || stack.`is`(ItemTags.AXES)) {
                bestSlot = i
                break
            }
        }

        if (bestSlot != -1) {
            val currentHand = bot.inventory.mainHandItem
            val weapon = bot.inventory.getItem(bestSlot)
            bot.inventory.mainHandItem = weapon
            bot.inventory.setItem(bestSlot, currentHand)
            bot.inventory.setChanged()
        }
    }

    override fun stop(bot: CompanionBotEntity) {
        targetAnimal = null
    }
}
