package com.qc.aeonis.client.gui

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.inventory.CompanionMenu
import com.qc.aeonis.companion.order.CompanionOrder
import com.qc.aeonis.entity.CompanionBotEntity
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory

/**
 * Authentic Minecraft-grade GUI for the Aeonis Companion Bot.
 *
 * Features:
 * - Classic Minecraft container framing, bevels, and slot textures.
 * - Interactive top tabs: [STORAGE], [TACTICAL ORDERS], [STATS & BOND].
 * - Live 3D Companion Bot entity preview with mouse head-tracking.
 * - Complete equipment bay: Helmet, Chestplate, Leggings, Boots, Weapon, and Shield.
 * - Full mouse event propagation: stack splitting, drag distribution, shift-click routing, and hotbar swapping.
 */
@Environment(EnvType.CLIENT)
class CompanionScreen(
    menu: CompanionMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<CompanionMenu>(menu, playerInventory, title, 272, 172) {

    enum class Tab(val label: String) {
        STORAGE("Storage & Gear"),
        ORDERS("Tactical Orders"),
        STATS("Stats & Bond")
    }

    private var activeTab = Tab.STORAGE

    init {
        this.titleLabelX = 10
        this.titleLabelY = 6
        this.inventoryLabelX = 98
        this.inventoryLabelY = 74
    }

    private fun getCompanionEntity(): CompanionBotEntity? {
        val player = minecraft?.player ?: return null
        val level = minecraft?.level ?: return null
        return level.entitiesForRendering()
            .filterIsInstance<CompanionBotEntity>()
            .firstOrNull { it.ownerUuid == player.uuid }
    }

    override fun extractContents(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // 1. Render Top Tabs (Creative Mode Style)
        renderTabs(graphics, mouseX, mouseY)

        // 2. Main Minecraft Container Window Background
        renderVanillaContainerBox(graphics, leftPos, topPos, imageWidth, imageHeight)

        // 3. Render Active Tab Content
        when (activeTab) {
            Tab.STORAGE -> renderStorageTab(graphics, mouseX, mouseY)
            Tab.ORDERS -> renderOrdersTab(graphics, mouseX, mouseY)
            Tab.STATS -> renderStatsTab(graphics, mouseX, mouseY)
        }

        // 4. Delegate to super for item rendering, slot highlights, tooltips, and carried stack!
        if (activeTab == Tab.STORAGE) {
            super.extractContents(graphics, mouseX, mouseY, delta)
        } else {
            // Render cursor stack even on non-storage tabs
            super.extractContents(graphics, mouseX, mouseY, delta)
        }
    }

    private fun renderTabs(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val tabWidth = 82
        val tabHeight = 22
        val tabs = Tab.entries

        for (i in tabs.indices) {
            val tab = tabs[i]
            val tx = leftPos + 6 + i * (tabWidth + 4)
            val ty = topPos - tabHeight + 2
            val isSelected = tab == activeTab
            val isHovered = mouseX in tx until (tx + tabWidth) && mouseY in ty until topPos

            // Tab background
            val bgCol = if (isSelected) 0xFFC6C6C6.toInt() else if (isHovered) 0xFFA0A0A0.toInt() else 0xFF8B8B8B.toInt()
            graphics.fill(tx, ty, tx + tabWidth, topPos, bgCol)

            // Tab 3D borders (Minecraft style)
            graphics.fill(tx, ty, tx + tabWidth, ty + 1, 0xFFFFFFFF.toInt())       // top highlight
            graphics.fill(tx, ty, tx + 1, topPos, 0xFFFFFFFF.toInt())               // left highlight
            graphics.fill(tx + tabWidth - 1, ty, tx + tabWidth, topPos, 0xFF373737.toInt()) // right shadow

            val textColor = if (isSelected) 0x000000 else if (isHovered) 0x333333 else 0x555555
            val tw = font.width(tab.label)
            graphics.text(font, tab.label, tx + (tabWidth - tw) / 2, ty + 6, textColor)
        }
    }

    private fun renderVanillaContainerBox(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        // Base container fill
        graphics.fill(x, y, x + w, y + h, 0xFFC6C6C6.toInt())

        // 3D Beveled Outer Edges
        graphics.fill(x, y, x + w, y + 2, 0xFFFFFFFF.toInt())             // Top outer highlight
        graphics.fill(x, y, x + 2, y + h, 0xFFFFFFFF.toInt())             // Left outer highlight
        graphics.fill(x + w - 2, y, x + w, y + h, 0xFF555555.toInt())     // Right outer shadow
        graphics.fill(x, y + h - 2, x + w, y + h, 0xFF555555.toInt())     // Bottom outer shadow
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF000000.toInt())     // Rightmost line
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF000000.toInt())     // Bottommost line
    }

    private fun renderStorageTab(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Header titles
        graphics.text(font, "COMPANION GEAR", leftPos + 8, topPos + 6, 0x404040)
        graphics.text(font, "BACKPACK (27)", leftPos + 98, topPos + 6, 0x404040)
        graphics.text(font, "INVENTORY", leftPos + 98, topPos + 74, 0x404040)

        // 1. Draw Minecraft Slot Boxes for every slot in the menu
        for (slot in menu.slots) {
            val sx = leftPos + slot.x - 1
            val sy = topPos + slot.y - 1
            drawVanillaSlotBox(graphics, sx, sy)
        }

        // 2. Equipment Dock Background (x=48..92, y=18..88)
        val ex1 = leftPos + 48
        val ey1 = topPos + 18
        val ex2 = leftPos + 92
        val ey2 = topPos + 88
        drawRecessedBox(graphics, ex1, ey1, ex2 - ex1, ey2 - ey1)

        // 3. 3D Live Entity Preview of Companion Bot!
        val bot = getCompanionEntity()
        val displayEntity = bot ?: minecraft?.player
        if (displayEntity != null) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                graphics,
                ex1, ey1, ex2, ey2,
                26,
                0.0625f,
                mouseX.toFloat(),
                mouseY.toFloat(),
                displayEntity
            )
        }
    }

    companion object {
        private val DISPLAYED_ORDERS = listOf(
            Triple(CompanionOrder.FOLLOW, "FOLLOW OWNER", "Stay within 6 blocks and protect operator"),
            Triple(CompanionOrder.HOLD, "HOLD POSITION", "Guard the current location, do not wander"),
            Triple(CompanionOrder.ATTACK, "AGGRESSIVE", "Target all hostiles within detection radius"),
            Triple(CompanionOrder.RETREAT, "RETREAT", "Avoid combat, sprint immediately back to owner")
        )
    }

    private fun renderOrdersTab(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        graphics.text(font, "TACTICAL ORDERS & BEHAVIOR", leftPos + 10, topPos + 8, 0x202020)

        val orders = DISPLAYED_ORDERS

        val bot = getCompanionEntity()
        val currentOrder = bot?.currentOrder ?: CompanionOrder.FOLLOW

        for (i in orders.indices) {
            val (order, title, desc) = orders[i]
            val bx = leftPos + 14
            val by = topPos + 22 + i * 34
            val bw = 244
            val bh = 30
            val isSelected = order == currentOrder
            val isHovered = mouseX in bx until (bx + bw) && mouseY in by until (by + bh)

            // Card background
            val bgCol = if (isSelected) 0xFFD4E6B5.toInt() else if (isHovered) 0xFFE2E2E2.toInt() else 0xFFD0D0D0.toInt()
            graphics.fill(bx, by, bx + bw, by + bh, bgCol)

            // Bevel border
            val topBorder = if (isSelected) 0xFF5B8C2A.toInt() else 0xFFFFFFFF.toInt()
            val botBorder = if (isSelected) 0xFF3B5C1B.toInt() else 0xFF707070.toInt()
            graphics.fill(bx, by, bx + bw, by + 1, topBorder)
            graphics.fill(bx, by, bx + 1, by + bh, topBorder)
            graphics.fill(bx, by + bh - 1, bx + bw, by + bh, botBorder)
            graphics.fill(bx + bw - 1, by, bx + bw, by + bh, botBorder)

            // Text
            val titleColor = if (isSelected) 0x1E4605 else 0x111111
            val statusBadge = if (isSelected) " [ACTIVE]" else ""
            graphics.text(font, title + statusBadge, bx + 8, by + 5, titleColor)
            graphics.text(font, desc, bx + 8, by + 16, 0x555555)
        }
    }

    private fun renderStatsTab(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        graphics.text(font, "COMPANION TELEMETRY & BOND", leftPos + 10, topPos + 8, 0x202020)

        val bot = getCompanionEntity()
        val hp = bot?.health?.toInt() ?: 20
        val maxHp = bot?.maxHealth?.toInt() ?: 20
        val armor = bot?.armorValue ?: 0

        // Stats card box
        drawRecessedBox(graphics, leftPos + 14, topPos + 24, 244, 134)

        var yOff = topPos + 32
        graphics.text(font, "● Health Pool: §c$hp / $maxHp HP", leftPos + 22, yOff, 0x222222)
        yOff += 16
        graphics.text(font, "● Armor Rating: §9$armor Points", leftPos + 22, yOff, 0x222222)
        yOff += 16
        graphics.text(font, "● Bond Level: §6Level 1 (Devoted)", leftPos + 22, yOff, 0x222222)
        yOff += 16
        graphics.text(font, "● Navigation Engine: §23D Voxel Graph A* (Dynamic Parkour)", leftPos + 22, yOff, 0x222222)
        yOff += 16
        graphics.text(font, "● Motor Brain: §1Pure JVM Neural Engine (0-latency)", leftPos + 22, yOff, 0x222222)
        yOff += 16
        graphics.text(font, "● Current Directive: §5${bot?.currentOrder?.name ?: "FOLLOW"}", leftPos + 22, yOff, 0x222222)
        yOff += 16
        graphics.text(font, "● Memory: §7Safe (0 hazard zones logged)", leftPos + 22, yOff, 0x222222)
    }

    private fun drawVanillaSlotBox(graphics: GuiGraphics, x: Int, y: Int) {
        // 18x18 standard Minecraft recessed slot box
        graphics.fill(x, y, x + 18, y + 18, 0xFF8B8B8B.toInt())
        graphics.fill(x, y, x + 17, y + 1, 0xFF373737.toInt())
        graphics.fill(x, y, x + 1, y + 17, 0xFF373737.toInt())
        graphics.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF.toInt())
        graphics.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFFFFF.toInt())
    }

    private fun drawRecessedBox(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        graphics.fill(x, y, x + w, y + h, 0xFFB0B0B0.toInt())
        graphics.fill(x, y, x + w, y + 1, 0xFF373737.toInt())
        graphics.fill(x, y, x + 1, y + h, 0xFF373737.toInt())
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF.toInt())
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF.toInt())
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.x.toInt()
        val my = event.y.toInt()

        if (event.button() == 0) {
            // Tab clicks
            val tabWidth = 82
            val tabHeight = 22
            val tabs = Tab.entries
            for (i in tabs.indices) {
                val tx = leftPos + 6 + i * (tabWidth + 4)
                val ty = topPos - tabHeight + 2
                if (mx in tx until (tx + tabWidth) && my in ty until topPos) {
                    activeTab = tabs[i]
                    minecraft?.player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f)
                    return true
                }
            }

            // Orders clicks when on Orders tab
            if (activeTab == Tab.ORDERS) {
                for (i in DISPLAYED_ORDERS.indices) {
                    val bx = leftPos + 14
                    val by = topPos + 22 + i * 34
                    val bw = 244
                    val bh = 30
                    if (mx in bx until (bx + bw) && my in by until (by + bh)) {
                        val (order, _, _) = DISPLAYED_ORDERS[i]
                        val cmd = when (order) {
                            CompanionOrder.FOLLOW -> "comp mode follow"
                            CompanionOrder.HOLD -> "comp mode idle"
                            CompanionOrder.ATTACK -> "comp mode attack"
                            CompanionOrder.RETREAT -> "comp mode retreat"
                            else -> "comp mode follow"
                        }
                        minecraft?.player?.connection?.sendCommand(cmd)
                        minecraft?.player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, 1.2f)
                        return true
                    }
                }
            }
        }

        return super.mouseClicked(event, doubleClick)
    }
}
