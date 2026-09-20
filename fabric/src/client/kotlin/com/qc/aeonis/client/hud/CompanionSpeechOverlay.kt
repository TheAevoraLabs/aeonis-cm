package com.qc.aeonis.client.hud

import com.qc.aeonis.companion.ui.CompanionSpeechState
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Floating animated dialogue card for the Aeonis Companion Bot.
 *
 * Replaces intrusive chat spam with a clean, authentic Minecraft container-style speech HUD:
 * - Smooth vertical slide-in and slide-out animation.
 * - Emerald-beveled translucent card container.
 * - Pixelated companion face avatar.
 * - Dynamic emotion badge ([FOOD], [COMBAT], [GUARD], [TASK]).
 * - Typewriter text revelation that types out messages smoothly.
 */
import com.qc.aeonis.screen.AeonisClientSettings

object CompanionSpeechOverlay : HudElement {

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (!AeonisClientSettings.companionSpeechOverlay) return
        val msg = CompanionSpeechState.currentMessage ?: return
        val mc = Minecraft.getInstance()
        val font = mc.font
        val windowWidth = mc.window.guiScaledWidth

        val cardWidth = 240
        val cardHeight = 36
        val targetY = 12

        // Slide animation:
        val remaining = CompanionSpeechState.displayTicks
        val slideProgress = when {
            remaining > 130 -> (140 - remaining) / 10.0f
            remaining < 10 -> remaining / 10.0f
            else -> 1.0f
        }

        val cardY = (targetY - (1.0f - slideProgress) * 50).toInt()
        val cardX = (windowWidth - cardWidth) / 2

        // Card dark obsidian background
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xDD121620.toInt())

        // Bevel borders (companion emerald green accent)
        val topBorder = 0xFF5B8C2A.toInt()
        val botBorder = 0xFF2A3D16.toInt()
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + 1, topBorder)
        graphics.fill(cardX, cardY, cardX + 1, cardY + cardHeight, topBorder)
        graphics.fill(cardX, cardY + cardHeight - 1, cardX + cardWidth, cardY + cardHeight, botBorder)
        graphics.fill(cardX + cardWidth - 1, cardY, cardX + cardWidth, cardY + cardHeight, botBorder)

        // Avatar icon frame (24x24)
        val iconX = cardX + 6
        val iconY = cardY + 6
        graphics.fill(iconX, iconY, iconX + 24, iconY + 24, 0xFF1E293B.toInt())
        graphics.fill(iconX + 1, iconY + 1, iconX + 23, iconY + 23, 0xFF365314.toInt())

        // Companion avatar face (pixel art)
        // Eyes
        graphics.fill(iconX + 6, iconY + 7, iconX + 9, iconY + 12, 0xFFE0F2FE.toInt())
        graphics.fill(iconX + 15, iconY + 7, iconX + 18, iconY + 12, 0xFFE0F2FE.toInt())
        graphics.fill(iconX + 7, iconY + 9, iconX + 9, iconY + 12, 0xFF0284C7.toInt())
        graphics.fill(iconX + 16, iconY + 9, iconX + 18, iconY + 12, 0xFF0284C7.toInt())
        // Cheerful mouth expression
        graphics.fill(iconX + 9, iconY + 16, iconX + 15, iconY + 18, 0xFFF1F5F9.toInt())

        // Speaker Header
        val title = "§6${msg.speakerName}"
        val badge = when (msg.emotion.uppercase()) {
            "FOOD" -> "§e[FOOD]"
            "COMBAT" -> "§c[COMBAT]"
            "DEFENSE" -> "§9[GUARD]"
            "TASK" -> "§b[TASK]"
            else -> "§a[COMPANION]"
        }
        graphics.text(font, "$title $badge", cardX + 36, cardY + 5, 0xFFFFFF)

        // Typewriter dialogue text
        val visibleText = msg.fullText.take(CompanionSpeechState.visibleCharCount)
        val lines = wrapText(font, visibleText, 196)
        if (lines.isNotEmpty()) {
            graphics.text(font, "§f${lines[0]}", cardX + 36, cardY + 16, 0xEEEEEE)
            if (lines.size > 1) {
                graphics.text(font, "§f${lines[1]}", cardX + 36, cardY + 25, 0xEEEEEE)
            }
        }
    }

    private fun wrapText(font: Font, text: String, maxWidth: Int): List<String> {
        if (font.width(text) <= maxWidth) return listOf(text)
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (font.width(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
