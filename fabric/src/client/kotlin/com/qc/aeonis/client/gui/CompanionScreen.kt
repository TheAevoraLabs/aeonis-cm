package com.qc.aeonis.client.gui

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.inventory.CompanionMenu
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory

/**
 * High-tech Sci-Fi Communicator terminal for the Aeonis Companion Bot.
 * Provides live telemetry, equipment dock, 27-slot neural backpack,
 * operator inventory sync, and one-click tactical order buttons.
 */
@Environment(EnvType.CLIENT)
class CompanionScreen(
    menu: CompanionMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<CompanionMenu>(menu, playerInventory, title, 236, 196) {

    init {
        this.titleLabelX = -9999
        this.titleLabelY = -9999
        this.inventoryLabelX = -9999
        this.inventoryLabelY = -9999
    }

    override fun extractContents(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // 1. Dark glass futuristic terminal backdrop
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF2090D16.toInt())

        // 2. High-tech glowing cyan & deep-slate accent borders
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, 0xFF00F0FF.toInt())
        graphics.fill(leftPos, topPos + imageHeight - 1, leftPos + imageWidth, topPos + imageHeight, 0xFF1E293B.toInt())
        graphics.fill(leftPos, topPos + 1, leftPos + 1, topPos + imageHeight, 0xFF00F0FF.toInt())
        graphics.fill(leftPos + imageWidth - 1, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF1E293B.toInt())

        // 3. Top Header Bar
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + 14, 0x900F172A.toInt())
        graphics.text(font, "RHISTEL // TACTICAL COMPANION", leftPos + 8, topPos + 5, 0xFF00F0FF.toInt())
        graphics.text(font, "● ONLINE", leftPos + imageWidth - 52, topPos + 5, 0xFF10B981.toInt())

        // 4. Panel Backdrops
        // Left: Equipment bay (y: 16..90)
        graphics.fill(leftPos + 5, topPos + 16, leftPos + 54, topPos + 92, 0x800B1220.toInt())
        // Left: Telemetry & status dock (y: 94..164)
        graphics.fill(leftPos + 5, topPos + 94, leftPos + 54, topPos + 164, 0x80070B14.toInt())
        // Right Top: Companion Neural Storage (27 slots) (y: 16..74)
        graphics.fill(leftPos + 58, topPos + 16, leftPos + 230, topPos + 74, 0x800B1220.toInt())
        // Right Bottom: Operator Inventory (y: 78..164)
        graphics.fill(leftPos + 58, topPos + 78, leftPos + 230, topPos + 164, 0x800B1220.toInt())

        // 5. Panel Labels
        graphics.text(font, "NEURAL STORAGE (27)", leftPos + 62, topPos + 18, 0xFF64748B.toInt())
        graphics.text(font, "OPERATOR INVENTORY", leftPos + 62, topPos + 80, 0xFF64748B.toInt())

        // 6. Custom Sci-Fi Slot Frames for EVERY slot in menu
        for (slot in menu.slots) {
            val sx = leftPos + slot.x - 1
            val sy = topPos + slot.y - 1
            val isHover = isHovering(slot.x, slot.y, 16, 16, mouseX.toDouble(), mouseY.toDouble())

            // Recessed slot background
            val fillBg = if (isHover) 0xFF1E2E48.toInt() else 0xFF060911.toInt()
            graphics.fill(sx, sy, sx + 18, sy + 18, fillBg)

            // 1px Cyber border
            val borderCol = if (isHover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt()
            graphics.fill(sx, sy, sx + 18, sy + 1, borderCol)
            graphics.fill(sx, sy + 17, sx + 18, sy + 18, borderCol)
            graphics.fill(sx, sy + 1, sx + 1, sy + 17, borderCol)
            graphics.fill(sx + 17, sy + 1, sx + 18, sy + 17, borderCol)

            // Equipment Slot Silhouettes when empty
            if (!slot.hasItem()) {
                val label = when (slot.index) {
                    CompanionInventory.HELMET_SLOT -> "HLM"
                    CompanionInventory.CHESTPLATE_SLOT -> "CHS"
                    CompanionInventory.LEGGINGS_SLOT -> "LEG"
                    CompanionInventory.BOOTS_SLOT -> "BOT"
                    CompanionInventory.MAINHAND_SLOT -> "ATK"
                    CompanionInventory.OFFHAND_SLOT -> "DEF"
                    else -> null
                }
                if (label != null) {
                    val labelWidth = font.width(label)
                    graphics.text(font, label, sx + 9 - labelWidth / 2, sy + 5, 0x6038BDF8.toInt())
                }
            }
        }

        // 7. Telemetry & AI status readout
        graphics.text(font, "STATUS", leftPos + 8, topPos + 97, 0xFF38BDF8.toInt())
        graphics.fill(leftPos + 8, topPos + 107, leftPos + 51, topPos + 108, 0xFF1E293B.toInt())

        graphics.text(font, "HP: 100%", leftPos + 8, topPos + 112, 0xFF10B981.toInt())
        // Mini HP Bar
        graphics.fill(leftPos + 8, topPos + 122, leftPos + 51, topPos + 125, 0xFF1E293B.toInt())
        graphics.fill(leftPos + 8, topPos + 122, leftPos + 51, topPos + 125, 0xFF10B981.toInt())

        graphics.text(font, "AI: ONNX", leftPos + 8, topPos + 130, 0xFFE2E8F0.toInt())
        graphics.text(font, "NAV: VXL", leftPos + 8, topPos + 142, 0xFF94A3B8.toInt())
        graphics.text(font, "SYNC: OK", leftPos + 8, topPos + 154, 0xFF00F0FF.toInt())

        // 8. Interactive Quick Command Buttons (y: 168..188)
        drawOrderButton(graphics, leftPos + 6, topPos + 170, 52, 18, "FOLLOW", mouseX, mouseY, 0xFF00F0FF.toInt())
        drawOrderButton(graphics, leftPos + 62, topPos + 170, 52, 18, "ATTACK", mouseX, mouseY, 0xFFF59E0B.toInt())
        drawOrderButton(graphics, leftPos + 118, topPos + 170, 52, 18, "RETREAT", mouseX, mouseY, 0xFFEF4444.toInt())
        drawOrderButton(graphics, leftPos + 174, topPos + 170, 56, 18, "RECALL", mouseX, mouseY, 0xFF10B981.toInt())

        // 9. Call super to render all slot items, stack counts, hover highlight, and labels!
        super.extractContents(graphics, mouseX, mouseY, delta)
    }

    private fun drawOrderButton(
        graphics: GuiGraphics,
        bx: Int,
        by: Int,
        bw: Int,
        bh: Int,
        label: String,
        mouseX: Int,
        mouseY: Int,
        accentColor: Int
    ) {
        val hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh
        val bg = if (hovered) 0xFF1E293B.toInt() else 0xFF0B1120.toInt()
        val border = if (hovered) accentColor else 0xFF1E293B.toInt()
        graphics.fill(bx, by, bx + bw, by + bh, bg)
        graphics.fill(bx, by, bx + bw, by + 1, border)
        graphics.fill(bx, by + bh - 1, bx + bw, by + bh, border)
        graphics.fill(bx, by + 1, bx + 1, by + bh - 1, border)
        graphics.fill(bx + bw - 1, by + 1, bx + bw, by + bh - 1, border)

        val tw = font.width(label)
        val textColor = if (hovered) accentColor else 0xFFE2E8F0.toInt()
        graphics.text(font, label, bx + (bw - tw) / 2, by + (bh - 8) / 2, textColor)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.x.toInt()
        val my = event.y.toInt()

        if (event.button() == 0) {
            val by = topPos + 170
            val bh = 18
            if (my in by until by + bh) {
                when {
                    mx in (leftPos + 6) until (leftPos + 58) -> {
                        sendBotCommand("comp mode follow")
                        return true
                    }
                    mx in (leftPos + 62) until (leftPos + 114) -> {
                        sendBotCommand("comp mode attack")
                        return true
                    }
                    mx in (leftPos + 118) until (leftPos + 170) -> {
                        sendBotCommand("comp mode retreat")
                        return true
                    }
                    mx in (leftPos + 174) until (leftPos + 230) -> {
                        sendBotCommand("comp comehere")
                        return true
                    }
                }
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    private fun sendBotCommand(command: String) {
        minecraft?.player?.connection?.sendCommand(command)
        minecraft?.player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.4f)
    }
}
