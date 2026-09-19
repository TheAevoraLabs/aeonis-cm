package com.qc.aeonis.client.gui

import com.qc.aeonis.companion.inventory.CompanionMenu
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

@Environment(EnvType.CLIENT)
class CompanionScreen(
    menu: CompanionMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<CompanionMenu>(menu, playerInventory, title, 236, 168) {

    init {
        this.titleLabelX = 8
        this.titleLabelY = 6
        this.inventoryLabelX = 62
        this.inventoryLabelY = 72
    }

    override fun extractContents(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // Dark metallic container backdrop
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xE013181F.toInt())
        // Accent border
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, 0xFF3B82F6.toInt())
        graphics.fill(leftPos, topPos + imageHeight - 1, leftPos + imageWidth, topPos + imageHeight, 0xFF1E293B.toInt())
        graphics.fill(leftPos, topPos + 1, leftPos + 1, topPos + imageHeight, 0xFF1E293B.toInt())
        graphics.fill(leftPos + imageWidth - 1, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF1E293B.toInt())

        // Equipment bay background (left side)
        graphics.fill(leftPos + 6, topPos + 16, leftPos + 52, topPos + 92, 0x800F172A.toInt())

        // Backpack area background
        graphics.fill(leftPos + 60, topPos + 16, leftPos + 226, topPos + 72, 0x800F172A.toInt())

        // Player inventory background
        graphics.fill(leftPos + 60, topPos + 82, leftPos + 226, topPos + 162, 0x800F172A.toInt())

        // Labels
        graphics.text(font, Component.literal("Armor & Tools"), leftPos + 8, topPos + 6, 0xFF94A3B8.toInt())
        graphics.text(font, Component.literal("Backpack (27 Slots)"), leftPos + 60, topPos + 6, 0xFF94A3B8.toInt())
        graphics.text(font, Component.literal("§6[Rhistel] §aONLINE"), leftPos + 158, topPos + 6, 0xFF38D9E8.toInt())
        graphics.text(font, playerInventoryTitle, leftPos + 60, topPos + 72, 0xFF94A3B8.toInt())

        // Communicator Telemetry Panel (Below Equipment bay)
        graphics.fill(leftPos + 6, topPos + 96, leftPos + 52, topPos + 162, 0x900B132B.toInt())
        graphics.text(font, Component.literal("§3Telemetry"), leftPos + 8, topPos + 100, 0xFF64F3FF.toInt())
        graphics.text(font, Component.literal("§7Link: §aOK"), leftPos + 8, topPos + 114, 0xFFE2E8F0.toInt())
        graphics.text(font, Component.literal("§7AI: §eONNX"), leftPos + 8, topPos + 128, 0xFFE2E8F0.toInt())
        graphics.text(font, Component.literal("§7Path: §bVoxel"), leftPos + 8, topPos + 142, 0xFFE2E8F0.toInt())
    }
}
