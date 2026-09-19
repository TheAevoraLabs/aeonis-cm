package com.qc.aeonis.screen

import com.qc.aeonis.network.AeonisClientNetworking
import com.qc.aeonis.transform.CamouflageLevel
import com.qc.aeonis.transform.MobCatalogue
import com.qc.aeonis.transform.MobMovementProfiles
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * The Aeonis System Hub: futuristic holographic OS interface for client preferences,
 * manual archive reading, form telemetry, and quick tactical actions.
 */
@Environment(EnvType.CLIENT)
class AeonisSettingsScreen(private val parent: Screen?) : Screen(Component.literal("Aeonis System Hub")) {

    enum class HubTab(val title: String) {
        SYSTEM("⚙ SYSTEM"),
        ARCHIVE("📖 ARCHIVE"),
        MORPH("🧬 MORPH"),
        ACTIONS("⚡ ACTIONS")
    }

    private var currentTab: HubTab = HubTab.SYSTEM
    private var selectedManualIndex: Int = 0
    private var manualPage: Int = 0

    private val modVersion: String = FabricLoader.getInstance()
        .getModContainer("aeonis-manager")
        .map { it.metadata.version.friendlyString }
        .orElse("5.0.0")

    override fun init() {
        super.init()
        AeonisClientSettings.load()
    }

    override fun extractRenderState(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        val panelW = minOf(510, width - 16)
        val panelH = minOf(310, height - 16)
        val px = (width - panelW) / 2
        val py = (height - panelH) / 2

        // 1. Futuristic Glassmorphic Canvas Backdrop
        graphics.fill(px, py, px + panelW, py + panelH, 0xF4070B14.toInt())

        // 2. High-Tech Cyber Accent Borders
        graphics.fill(px, py, px + panelW, py + 1, 0xFF00F0FF.toInt())
        graphics.fill(px, py + panelH - 1, px + panelW, py + panelH, 0xFF1E293B.toInt())
        graphics.fill(px, py + 1, px + 1, py + panelH, 0xFF00F0FF.toInt())
        graphics.fill(px + panelW - 1, py + 1, px + panelW, py + panelH, 0xFF1E293B.toInt())

        // Top Accent Corner Marker
        graphics.fill(px, py, px + 12, py + 2, 0xFF00F0FF.toInt())
        graphics.fill(px, py, px + 2, py + 12, 0xFF00F0FF.toInt())

        // 3. Top Header Bar
        graphics.fill(px + 1, py + 1, px + panelW - 1, py + 24, 0x900B132B.toInt())
        graphics.text(font, "AEONIS // SYSTEM HUB", px + 8, py + 8, 0xFF00F0FF.toInt())
        val verText = "v$modVersion"
        graphics.text(font, verText, px + panelW - font.width(verText) - 76, py + 8, 0xFF64748B.toInt())
        graphics.text(font, "● READY", px + panelW - 68, py + 8, 0xFF10B981.toInt())

        graphics.fill(px + 1, py + 24, px + panelW - 1, py + 25, 0xFF1E293B.toInt())

        // 4. Tab Navigation Bar (y: py + 26 .. py + 46)
        val tabW = (panelW - 12) / HubTab.entries.size
        HubTab.entries.forEachIndexed { idx, tab ->
            val tx = px + 6 + idx * tabW
            val ty = py + 26
            val isCurrent = tab == currentTab
            val isHover = mouseX in tx until (tx + tabW - 2) && mouseY in ty until (ty + 20)

            val tabBg = when {
                isCurrent -> 0xFF1E293B.toInt()
                isHover -> 0x80162032.toInt()
                else -> 0x400A0F1D.toInt()
            }
            graphics.fill(tx, ty, tx + tabW - 2, ty + 20, tabBg)

            if (isCurrent) {
                graphics.fill(tx, ty + 18, tx + tabW - 2, ty + 20, 0xFF00F0FF.toInt())
            }

            val textCol = if (isCurrent) 0xFF00F0FF.toInt() else if (isHover) 0xFFE2E8F0.toInt() else 0xFF94A3B8.toInt()
            val tw = font.width(tab.title)
            graphics.text(font, tab.title, tx + (tabW - 2 - tw) / 2, ty + 6, textCol)
        }

        // Separator below tabs
        graphics.fill(px + 1, py + 48, px + panelW - 1, py + 49, 0xFF1E293B.toInt())

        // 5. Dynamic Tab View Content
        val contentY = py + 52
        val contentH = panelH - 78
        when (currentTab) {
            HubTab.SYSTEM -> renderSystemTab(graphics, px + 8, contentY, panelW - 16, contentH, mouseX, mouseY)
            HubTab.ARCHIVE -> renderArchiveTab(graphics, px + 8, contentY, panelW - 16, contentH, mouseX, mouseY)
            HubTab.MORPH -> renderMorphTab(graphics, px + 8, contentY, panelW - 16, contentH, mouseX, mouseY)
            HubTab.ACTIONS -> renderActionsTab(graphics, px + 8, contentY, panelW - 16, contentH, mouseX, mouseY)
        }

        // 6. Bottom Status Bar
        graphics.fill(px + 1, py + panelH - 24, px + panelW - 1, py + panelH - 23, 0xFF1E293B.toInt())
        val configMsg = "Config: config/aeonis-client.json"
        graphics.text(font, configMsg, px + 8, py + panelH - 16, 0xFF475569.toInt())

        // Close Button
        val closeW = 90
        val closeH = 16
        val cx = px + panelW - closeW - 6
        val cy = py + panelH - 20
        val closeHover = mouseX in cx until (cx + closeW) && mouseY in cy until (cy + closeH)
        graphics.fill(cx, cy, cx + closeW, cy + closeH, if (closeHover) 0xFF1E293B.toInt() else 0xFF0F172A.toInt())
        graphics.fill(cx, cy, cx + closeW, cy + 1, if (closeHover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt())
        graphics.fill(cx, cy + closeH - 1, cx + closeW, cy + closeH, if (closeHover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt())
        graphics.fill(cx, cy + 1, cx + 1, cy + closeH, if (closeHover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt())
        graphics.fill(cx + closeW - 1, cy + 1, cx + closeW, cy + closeH, if (closeHover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt())

        val closeText = "CLOSE HUB [ESC]"
        val ctw = font.width(closeText)
        graphics.text(font, closeText, cx + (closeW - ctw) / 2, cy + 4, if (closeHover) 0xFF00F0FF.toInt() else 0xFF94A3B8.toInt())
    }

    // ------------------------------------------------------------- TAB 1: SYSTEM
    private fun renderSystemTab(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val colW = (w - 8) / 2
        val rowH = 32

        // Row 1
        drawToggleCard(graphics, x, y, colW, rowH, "Ability HUD", "Show ability hotkeys & form banner", AeonisClientSettings.showAbilityHud, mouseX, mouseY)
        drawToggleCard(graphics, x + colW + 8, y, colW, rowH, "Cooldown Timers", "Display cooldown countdowns in seconds", AeonisClientSettings.showCooldownSeconds, mouseX, mouseY)

        // Row 2
        drawToggleCard(graphics, x, y + rowH + 6, colW, rowH, "Form Details", "Display movement style & traits", AeonisClientSettings.showFormDetails, mouseX, mouseY)
        drawToggleCard(graphics, x + colW + 8, y + rowH + 6, colW, rowH, "Input Hints", "Show keybind hints when morphing", AeonisClientSettings.showFormHints, mouseX, mouseY)

        // Row 3 (Col 1)
        drawToggleCard(graphics, x, y + (rowH + 6) * 2, colW, rowH, "Dynamic Camera", "Distance offset for colossal mob forms", AeonisClientSettings.largeFormCamera, mouseX, mouseY)

        // Camouflage Section Card (below toggles)
        val camoY = y + (rowH + 6) * 3 + 4
        val camoH = h - (camoY - y)
        graphics.fill(x, camoY, x + w, camoY + camoH, 0x800A0F1D.toInt())
        graphics.fill(x, camoY, x + w, camoY + 1, 0xFF1E293B.toInt())
        graphics.fill(x, camoY + camoH - 1, x + w, camoY + camoH, 0xFF1E293B.toInt())
        graphics.fill(x, camoY, x + 1, camoY + camoH, 0xFF1E293B.toInt())
        graphics.fill(x + w - 1, camoY, x + w, camoY + camoH, 0xFF1E293B.toInt())

        graphics.text(font, "CAMOUFLAGE PROTOCOL", x + 8, camoY + 6, 0xFF38BDF8.toInt())

        // 3 Camouflage Selector Chips
        val chipW = (w - 24) / 3
        CamouflageLevel.entries.forEachIndexed { i, level ->
            val cx = x + 8 + i * (chipW + 4)
            val cy = camoY + 18
            val isSelected = AeonisClientSettings.camouflageLevel == level
            val isHover = mouseX in cx until (cx + chipW) && mouseY in cy until (cy + 18)

            val chipBg = if (isSelected) 0xFF1E293B.toInt() else if (isHover) 0x90162032.toInt() else 0x400F172A.toInt()
            graphics.fill(cx, cy, cx + chipW, cy + 18, chipBg)
            val chipBorder = if (isSelected) 0xFF00F0FF.toInt() else if (isHover) 0xFF38BDF8.toInt() else 0xFF1E293B.toInt()
            graphics.fill(cx, cy, cx + chipW, cy + 1, chipBorder)
            graphics.fill(cx, cy + 17, cx + chipW, cy + 18, chipBorder)
            graphics.fill(cx, cy + 1, cx + 1, cy + 17, chipBorder)
            graphics.fill(cx + chipW - 1, cy + 1, cx + chipW, cy + 17, chipBorder)

            val lText = level.label.uppercase()
            val ltw = font.width(lText)
            val lcol = if (isSelected) 0xFF00F0FF.toInt() else 0xFF94A3B8.toInt()
            graphics.text(font, lText, cx + (chipW - ltw) / 2, cy + 5, lcol)
        }

        // Camouflage Description
        val desc = AeonisClientSettings.camouflageLevel.description
        graphics.text(font, trimText(desc, w - 16), x + 8, camoY + 42, 0xFF94A3B8.toInt())
    }

    private fun drawToggleCard(
        graphics: GuiGraphics,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        title: String,
        subtitle: String,
        value: Boolean,
        mouseX: Int,
        mouseY: Int
    ) {
        val hover = mouseX in cx until (cx + cw) && mouseY in cy until (cy + ch)
        val bg = if (hover) 0xFF162032.toInt() else 0xFF0F172A.toInt()
        val border = if (hover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt()

        graphics.fill(cx, cy, cx + cw, cy + ch, bg)
        graphics.fill(cx, cy, cx + cw, cy + 1, border)
        graphics.fill(cx, cy + ch - 1, cx + cw, cy + ch, border)
        graphics.fill(cx, cy + 1, cx + 1, cy + ch - 1, border)
        graphics.fill(cx + cw - 1, cy + 1, cx + cw, cy + ch - 1, border)

        graphics.text(font, title, cx + 6, cy + 5, 0xFFE2E8F0.toInt())
        graphics.text(font, trimText(subtitle, cw - 46), cx + 6, cy + 18, 0xFF64748B.toInt())

        // Toggle Switch Pill
        val swW = 32
        val swH = 14
        val swX = cx + cw - swW - 6
        val swY = cy + (ch - swH) / 2
        val swBg = if (value) 0xFF10B981.toInt() else 0xFF334155.toInt()
        graphics.fill(swX, swY, swX + swW, swY + swH, swBg)
        val pillText = if (value) "ON" else "OFF"
        val ptw = font.width(pillText)
        graphics.text(font, pillText, swX + (swW - ptw) / 2, swY + 3, 0xFFFFFFFF.toInt())
    }

    // ------------------------------------------------------------- TAB 2: ARCHIVE (MANUAL)
    private fun renderArchiveTab(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val listW = 140

        // Left Sub-Panel: Chapter List
        graphics.fill(x, y, x + listW, y + h, 0x800B1220.toInt())
        graphics.fill(x + listW, y, x + listW + 1, y + h, 0xFF1E293B.toInt())

        val itemH = 16
        AeonisManual.sections.forEachIndexed { i, sec ->
            val iy = y + 2 + i * (itemH + 1)
            if (iy + itemH <= y + h) {
                val isSel = i == selectedManualIndex
                val isHov = mouseX in x until (x + listW) && mouseY in iy until (iy + itemH)

                if (isSel) {
                    graphics.fill(x, iy, x + listW, iy + itemH, 0xFF1E293B.toInt())
                    graphics.fill(x, iy, x + 2, iy + itemH, 0xFF00F0FF.toInt())
                } else if (isHov) {
                    graphics.fill(x, iy, x + listW, iy + itemH, 0x401E293B.toInt())
                }

                val title = trimText(sec.title, listW - 8)
                val tCol = if (isSel) 0xFF00F0FF.toInt() else if (isHov) 0xFFE2E8F0.toInt() else 0xFF94A3B8.toInt()
                graphics.text(font, title, x + 5, iy + 4, tCol)
            }
        }

        // Right Sub-Panel: Document Reader
        val rx = x + listW + 8
        val rw = w - listW - 8
        val section = AeonisManual.sections.getOrNull(selectedManualIndex)
        if (section != null) {
            graphics.text(font, section.title.uppercase(), rx, y + 2, 0xFF38BDF8.toInt())
            graphics.fill(rx, y + 14, rx + rw, y + 15, 0xFF1E293B.toInt())

            // Wrap and paginate paragraphs
            val wrappedLines = mutableListOf<String>()
            section.body.forEach { para ->
                wrappedLines += wrapParagraph(para, rw - 8)
                wrappedLines += ""
            }

            val linesPerPage = (h - 44) / 10
            val totalPages = maxOf(1, (wrappedLines.size + linesPerPage - 1) / linesPerPage)
            manualPage = manualPage.coerceIn(0, totalPages - 1)

            val pageStart = manualPage * linesPerPage
            val pageLines = wrappedLines.drop(pageStart).take(linesPerPage)

            var lineY = y + 20
            pageLines.forEach { line ->
                graphics.text(font, line, rx, lineY, 0xFFCBD5E1.toInt())
                lineY += 10
            }

            // Pagination Controls at bottom
            val btnY = y + h - 18
            val pageText = "Page ${manualPage + 1} of $totalPages"
            graphics.text(font, pageText, rx + rw / 2 - font.width(pageText) / 2, btnY + 4, 0xFF64748B.toInt())

            drawMiniButton(graphics, rx, btnY, 50, 16, "< PREV", mouseX, mouseY, manualPage > 0)
            drawMiniButton(graphics, rx + rw - 50, btnY, 50, 16, "NEXT >", mouseX, mouseY, manualPage < totalPages - 1)
        }
    }

    private fun drawMiniButton(graphics: GuiGraphics, bx: Int, by: Int, bw: Int, bh: Int, label: String, mouseX: Int, mouseY: Int, enabled: Boolean) {
        val hover = enabled && mouseX in bx until (bx + bw) && mouseY in by until (by + bh)
        val bg = if (hover) 0xFF1E293B.toInt() else 0xFF0F172A.toInt()
        val border = if (hover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt()
        graphics.fill(bx, by, bx + bw, by + bh, bg)
        graphics.fill(bx, by, bx + bw, by + 1, border)
        graphics.fill(bx, by + bh - 1, bx + bw, by + bh, border)
        graphics.fill(bx, by + 1, bx + 1, by + bh - 1, border)
        graphics.fill(bx + bw - 1, by + 1, bx + bw, by + bh - 1, border)

        val col = if (!enabled) 0xFF475569.toInt() else if (hover) 0xFF00F0FF.toInt() else 0xFF94A3B8.toInt()
        val tw = font.width(label)
        graphics.text(font, label, bx + (bw - tw) / 2, by + (bh - 8) / 2, col)
    }

    // ------------------------------------------------------------- TAB 3: MORPH TELEMETRY
    private fun renderMorphTab(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        // Active Form Card
        graphics.fill(x, y, x + w, y + 80, 0x800F172A.toInt())
        graphics.fill(x, y, x + w, y + 1, 0xFF1E293B.toInt())
        graphics.fill(x, y + 79, x + w, y + 80, 0xFF1E293B.toInt())
        graphics.fill(x, y, x + 1, y + 80, 0xFF1E293B.toInt())
        graphics.fill(x + w - 1, y, x + w, y + 80, 0xFF1E293B.toInt())

        graphics.text(font, "ACTIVE NEURAL TELEMETRY", x + 8, y + 8, 0xFF38BDF8.toInt())

        if (AeonisClientNetworking.isControlling()) {
            val mob = minecraft?.level?.getEntity(AeonisClientNetworking.getControlledMobId()) as? net.minecraft.world.entity.Mob
            if (mob != null) {
                val formName = mob.type.description.string
                val movement = MobMovementProfiles.profileFor(mob).describe()
                val abilities = MobCatalogue.summary(mob.type).abilities.size

                graphics.text(font, "CURRENT FORM: $formName", x + 8, y + 24, 0xFF10B981.toInt())
                graphics.text(font, "LOCOMOTION: $movement", x + 8, y + 38, 0xFFE2E8F0.toInt())
                graphics.text(font, "REGISTERED ABILITIES: $abilities", x + 8, y + 52, 0xFF00F0FF.toInt())
                graphics.text(font, "CAMOUFLAGE: ${AeonisClientSettings.camouflageLevel.label}", x + 8, y + 66, 0xFFF59E0B.toInt())
            } else {
                graphics.text(font, "Possessing an entity that is not a standard mob.", x + 8, y + 30, 0xFFE2E8F0.toInt())
            }
        } else {
            graphics.text(font, "STATUS: STANDBY (HUMAN FORM)", x + 8, y + 26, 0xFF94A3B8.toInt())
            graphics.text(font, "Crosshair over any living mob and press G to initiate neural link.", x + 8, y + 42, 0xFF64748B.toInt())
            graphics.text(font, "Press H to terminate possession and restore your original body.", x + 8, y + 56, 0xFF64748B.toInt())
        }

        // Form Catalogue Launcher Card
        val launchY = y + 90
        val launchH = h - 90
        graphics.fill(x, launchY, x + w, launchY + launchH, 0x800F172A.toInt())
        graphics.fill(x, launchY, x + w, launchY + 1, 0xFF1E293B.toInt())
        graphics.fill(x, launchY + launchH - 1, x + w, launchY + launchH, 0xFF1E293B.toInt())
        graphics.fill(x, launchY, x + 1, launchY + launchH, 0xFF1E293B.toInt())
        graphics.fill(x + w - 1, launchY, x + w, launchY + launchH, 0xFF1E293B.toInt())

        graphics.text(font, "FORM MATRIX CATALOGUE", x + 8, launchY + 10, 0xFF38BDF8.toInt())
        graphics.text(font, "Explore 90+ registered mob forms, flight mechanics, and special abilities.", x + 8, launchY + 26, 0xFF94A3B8.toInt())

        // Catalogue Button
        val catW = 180
        val catH = 22
        val catX = x + 8
        val catY = launchY + 44
        val catHover = mouseX in catX until (catX + catW) && mouseY in catY until (catY + catH)
        graphics.fill(catX, catY, catX + catW, catY + catH, if (catHover) 0xFF1E293B.toInt() else 0xFF0B132B.toInt())
        val catBorder = if (catHover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt()
        graphics.fill(catX, catY, catX + catW, catY + 1, catBorder)
        graphics.fill(catX, catY + catH - 1, catX + catW, catY + catH, catBorder)
        graphics.fill(catX, catY + 1, catX + 1, catY + catH - 1, catBorder)
        graphics.fill(catX + catW - 1, catY + 1, catX + catW, catY + catH - 1, catBorder)

        val catText = "OPEN FORM CATALOGUE ->"
        val ctw = font.width(catText)
        graphics.text(font, catText, catX + (catW - ctw) / 2, catY + 7, if (catHover) 0xFF00F0FF.toInt() else 0xFF00F0FF.toInt())
    }

    // ------------------------------------------------------------- TAB 4: QUICK ACTIONS
    private fun renderActionsTab(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val actions = listOf(
            Triple("⚡ POSSESS RANDOM", "Morph into a random registered entity", "transform random"),
            Triple("🔍 INSPECT FORM", "Analyze current form attributes & stats", "transform info"),
            Triple("📜 LIST ABILITIES", "Show all hotkeys & abilities in chat", "transform abilities"),
            Triple("↩ RETURN TO BODY", "Terminate link and restore player body", "untransform"),
            Triple("🛠 RUN DIAGNOSTICS", "Execute full system diagnostic audit", "aeonis diagnostics")
        )

        val itemH = 34
        actions.forEachIndexed { i, (title, desc, _) ->
            val iy = y + i * (itemH + 6)
            if (iy + itemH <= y + h) {
                val isHover = mouseX in x until (x + w) && mouseY in iy until (iy + itemH)
                val bg = if (isHover) 0xFF162032.toInt() else 0xFF0F172A.toInt()
                val border = if (isHover) 0xFF00F0FF.toInt() else 0xFF1E293B.toInt()

                graphics.fill(x, iy, x + w, iy + itemH, bg)
                graphics.fill(x, iy, x + w, iy + 1, border)
                graphics.fill(x, iy + itemH - 1, x + w, iy + itemH, border)
                graphics.fill(x, iy + 1, x + 1, iy + itemH - 1, border)
                graphics.fill(x + w - 1, iy + 1, x + w, iy + itemH - 1, border)

                graphics.text(font, title, x + 8, iy + 6, if (isHover) 0xFF00F0FF.toInt() else 0xFFE2E8F0.toInt())
                graphics.text(font, desc, x + 8, iy + 19, 0xFF64748B.toInt())

                val execText = "[ EXECUTE ]"
                val etw = font.width(execText)
                graphics.text(font, execText, x + w - etw - 10, iy + 13, if (isHover) 0xFF00F0FF.toInt() else 0xFF38BDF8.toInt())
            }
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = event.x.toInt()
        val my = event.y.toInt()

        val panelW = minOf(510, width - 16)
        val panelH = minOf(310, height - 16)
        val px = (width - panelW) / 2
        val py = (height - panelH) / 2

        if (event.button() == 0) {
            // Check Close button
            val closeW = 90
            val closeH = 16
            val cx = px + panelW - closeW - 6
            val cy = py + panelH - 20
            if (mx in cx until (cx + closeW) && my in cy until (cy + closeH)) {
                onClose()
                return true
            }

            // Check Tab click
            val tabW = (panelW - 12) / HubTab.entries.size
            HubTab.entries.forEachIndexed { idx, tab ->
                val tx = px + 6 + idx * tabW
                val ty = py + 26
                if (mx in tx until (tx + tabW - 2) && my in ty until (ty + 20)) {
                    currentTab = tab
                    playClick()
                    return true
                }
            }

            // Tab-specific clicks
            val contentY = py + 52
            val contentH = panelH - 78
            when (currentTab) {
                HubTab.SYSTEM -> handleSystemClicks(px + 8, contentY, panelW - 16, contentH, mx, my)
                HubTab.ARCHIVE -> handleArchiveClicks(px + 8, contentY, panelW - 16, contentH, mx, my)
                HubTab.MORPH -> handleMorphClicks(px + 8, contentY, panelW - 16, contentH, mx, my)
                HubTab.ACTIONS -> handleActionsClicks(px + 8, contentY, panelW - 16, contentH, mx, my)
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    private fun handleSystemClicks(x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int): Boolean {
        val colW = (w - 8) / 2
        val rowH = 32

        // Card 1: Ability HUD
        if (mx in x until (x + colW) && my in y until (y + rowH)) {
            AeonisClientSettings.showAbilityHud = !AeonisClientSettings.showAbilityHud
            saveAndChime()
            return true
        }
        // Card 2: Cooldown Timers
        if (mx in (x + colW + 8) until (x + w) && my in y until (y + rowH)) {
            AeonisClientSettings.showCooldownSeconds = !AeonisClientSettings.showCooldownSeconds
            saveAndChime()
            return true
        }
        // Card 3: Form Details
        if (mx in x until (x + colW) && my in (y + rowH + 6) until (y + rowH * 2 + 6)) {
            AeonisClientSettings.showFormDetails = !AeonisClientSettings.showFormDetails
            saveAndChime()
            return true
        }
        // Card 4: Input Hints
        if (mx in (x + colW + 8) until (x + w) && my in (y + rowH + 6) until (y + rowH * 2 + 6)) {
            AeonisClientSettings.showFormHints = !AeonisClientSettings.showFormHints
            saveAndChime()
            return true
        }
        // Card 5: Dynamic Camera
        if (mx in x until (x + colW) && my in (y + (rowH + 6) * 2) until (y + (rowH + 6) * 2 + rowH)) {
            AeonisClientSettings.largeFormCamera = !AeonisClientSettings.largeFormCamera
            saveAndChime()
            return true
        }

        // Camouflage Selector Chips
        val camoY = y + (rowH + 6) * 3 + 4
        val chipW = (w - 24) / 3
        CamouflageLevel.entries.forEachIndexed { i, level ->
            val cx = x + 8 + i * (chipW + 4)
            val cy = camoY + 18
            if (mx in cx until (cx + chipW) && my in cy until (cy + 18)) {
                AeonisClientSettings.camouflageLevel = level
                saveAndChime()
                return true
            }
        }

        return false
    }

    private fun handleArchiveClicks(x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int): Boolean {
        val listW = 140

        // Chapter Selection on left
        val itemH = 16
        AeonisManual.sections.forEachIndexed { i, _ ->
            val iy = y + 2 + i * (itemH + 1)
            if (mx in x until (x + listW) && my in iy until (iy + itemH)) {
                selectedManualIndex = i
                manualPage = 0
                playClick()
                return true
            }
        }

        // Pagination buttons on right
        val rx = x + listW + 8
        val rw = w - listW - 8
        val btnY = y + h - 18
        if (mx in rx until (rx + 50) && my in btnY until (btnY + 16)) {
            if (manualPage > 0) {
                manualPage--
                playClick()
                return true
            }
        }
        if (mx in (rx + rw - 50) until (rx + rw) && my in btnY until (btnY + 16)) {
            manualPage++
            playClick()
            return true
        }

        return false
    }

    private fun handleMorphClicks(x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int): Boolean {
        val launchY = y + 90
        val catW = 180
        val catH = 22
        val catX = x + 8
        val catY = launchY + 44
        if (mx in catX until (catX + catW) && my in catY until (catY + catH)) {
            playClick()
            minecraft?.gui?.setScreen(AeonisFormBrowserScreen(this))
            return true
        }
        return false
    }

    private fun handleActionsClicks(x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int): Boolean {
        val actions = listOf(
            "transform random",
            "transform info",
            "transform abilities",
            "untransform",
            "aeonis diagnostics"
        )
        val itemH = 34
        actions.forEachIndexed { i, cmd ->
            val iy = y + i * (itemH + 6)
            if (mx in x until (x + w) && my in iy until (iy + itemH)) {
                playClick()
                minecraft?.player?.connection?.sendCommand(cmd)
                return true
            }
        }
        return false
    }

    private fun saveAndChime() {
        AeonisClientSettings.save()
        playClick()
    }

    private fun playClick() {
        minecraft?.player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.3f)
    }

    private fun trimText(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.isNotEmpty() && font.width("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed..."
    }

    private fun wrapParagraph(text: String, maxWidth: Int): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        for (w in words) {
            val test = if (current.isEmpty()) w else "$current $w"
            if (font.width(test) <= maxWidth) {
                current = test
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = w
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    override fun onClose() {
        AeonisClientSettings.save()
        minecraft?.gui?.setScreen(parent)
    }
}

