package com.qc.aeonis.client.hud

import com.qc.aeonis.minigame.manhunt.SpeedrunHudState
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║                      SPEEDRUN SPLIT-TIMER HUD OVERLAY                      ║
 * ║                                                                            ║
 * ║  Authentic Minecraft container-styled split-timer overlay & radar:         ║
 * ║  • Live speedrun timer (minutes:seconds.tenths).                           ║
 * ║  • Split progress checklist (Nether, Fortress, Stronghold, Dragon).        ║
 * ║  • Threat-colored hunter proximity radar and elevation delta arrows.       ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 */
object SpeedrunHudOverlay : HudElement {

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (!SpeedrunHudState.isEnabled || !SpeedrunHudState.isActiveGame) return

        val mc = Minecraft.getInstance()
        val font = mc.font
        val windowWidth = mc.window.guiScaledWidth

        val cardWidth = 148
        val cardX = windowWidth - cardWidth - 8
        var cardY = 8

        val splits = SpeedrunHudState.splitEntries
        val contentHeight = 32 + (splits.size * 11) + 24

        // 1. Semi-translucent container background
        val bgDark = 0xD810141E.toInt()
        val borderOuter = 0xFF2A364F.toInt()
        val borderInner = 0xFF141A26.toInt()

        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + contentHeight, bgDark)
        // Outer beveled frame
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + 1, borderOuter)
        graphics.fill(cardX, cardY, cardX + 1, cardY + contentHeight, borderOuter)
        graphics.fill(cardX, cardY + contentHeight - 1, cardX + cardWidth, cardY + contentHeight, borderInner)
        graphics.fill(cardX + cardWidth - 1, cardY, cardX + cardWidth, cardY + contentHeight, borderInner)

        var curY = cardY + 5

        // 2. Header banner: Live Timer & Title
        val title = "⚡ SPEEDRUN"
        graphics.text(font, title, cardX + 6, curY, 0xFFFCD34D.toInt())

        val timerText = SpeedrunHudState.elapsedTimeFormatted
        val timerWidth = font.width(timerText)
        graphics.text(font, timerText, cardX + cardWidth - timerWidth - 6, curY, 0xFFFFFFFF.toInt())

        curY += 13
        // Divider line
        graphics.fill(cardX + 4, curY, cardX + cardWidth - 4, curY + 1, 0xFF334155.toInt())
        curY += 5

        // 3. Splits Checklist
        for (split in splits) {
            val statusIcon = when {
                split.isCompleted -> "§a✓"
                split.isCurrent -> "§e▶"
                else -> "§8•"
            }
            val nameColor = when {
                split.isCompleted -> 0xFFE2E8F0.toInt()
                split.isCurrent -> 0xFFFDE047.toInt()
                else -> 0xFF64748B.toInt()
            }
            val timeColor = when {
                split.isCompleted -> 0xFF86EFAC.toInt()
                split.isCurrent -> 0xFFFDE047.toInt()
                else -> 0xFF64748B.toInt()
            }

            val splitLabel = "$statusIcon §f${split.name}"
            graphics.text(font, splitLabel, cardX + 6, curY, nameColor)

            val timeWidth = font.width(split.timestamp)
            graphics.text(font, split.timestamp, cardX + cardWidth - timeWidth - 6, curY, timeColor)

            curY += 11
        }

        curY += 2
        // Telemetry Divider
        graphics.fill(cardX + 4, curY, cardX + cardWidth - 4, curY + 1, 0xFF334155.toInt())
        curY += 4

        // 4. Hunter Proximity Radar & Height Delta
        val threat = SpeedrunHudState.threatLevel
        val threatBadge = "[${threat.label}]"
        val distText = if (SpeedrunHudState.isCrossDimension) {
            "DIM: ${SpeedrunHudState.currentDimension}"
        } else {
            String.format("%.1fm %s %+dm", SpeedrunHudState.hunterDistance, SpeedrunHudState.elevationArrow, SpeedrunHudState.hunterElevationDelta)
        }

        graphics.text(font, "HUNTER:", cardX + 6, curY, 0xFFEF4444.toInt())
        val threatWidth = font.width(threatBadge)
        graphics.text(font, threatBadge, cardX + cardWidth - threatWidth - 6, curY, threat.hexColor)

        curY += 10
        graphics.text(font, distText, cardX + 6, curY, 0xFFCBD5E1.toInt())
    }
}
