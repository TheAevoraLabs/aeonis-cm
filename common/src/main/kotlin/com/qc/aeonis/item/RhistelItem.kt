package com.qc.aeonis.item

import com.qc.aeonis.companion.CompanionBotManager
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/**
 * Rhistel — a companion whistle that cycles the companion bot through
 * forced behaviour modes.
 *
 * Mode cycle:  ATTACK → FOLLOW → RUN_AWAY → AUTO → (repeat)
 *
 * ATTACK / FOLLOW / RUN_AWAY override the default AI for 2 minutes (2 400 ticks).
 * AUTO restores normal decision-making immediately.
 */
class RhistelItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val bot = CompanionBotManager.getBot(serverPlayer)

        if (bot == null) {
            serverPlayer.sendSystemMessage(
                Component.literal("§c[Rhistel] §7No companion found. Spawn one with §e/comp spawn§7.")
            )
            return InteractionResult.FAIL
        }

        // Shift + Right Click triggers 3D Tactical Ping
        if (player.isShiftKeyDown) {
            if (com.qc.aeonis.companion.command.RhistelPingHandler.executePing(serverPlayer, bot)) {
                serverPlayer.cooldowns.addCooldown(player.getItemInHand(hand), 10)
                return InteractionResult.SUCCESS
            }
        }

        // Right-Click: Remotely open Companion GUI
        bot.openCustomInventory(serverPlayer)

        // Communicator chime
        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.NOTE_BLOCK_CHIME.value(),
            SoundSource.PLAYERS,
            1.0f,
            1.6f
        )

        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltipDisplay: net.minecraft.world.item.component.TooltipDisplay,
        consumer: java.util.function.Consumer<Component>,
        flag: TooltipFlag
    ) {
        consumer.accept(Component.literal("§bRight-click: §7Open Companion Communicator GUI"))
        consumer.accept(Component.literal("§6Shift + Right-click: §73D Tactical Ping"))
        consumer.accept(Component.literal("§8Focus Attack • Deposit • Mine Vein • Move & Hold"))
    }
}
