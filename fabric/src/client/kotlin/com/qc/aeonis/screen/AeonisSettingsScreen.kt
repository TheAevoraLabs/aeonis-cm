package com.qc.aeonis.screen

import com.qc.aeonis.network.AeonisClientNetworking
import com.qc.aeonis.transform.CamouflageLevel
import com.qc.aeonis.transform.MobCatalogue
import com.qc.aeonis.transform.MobMovementProfiles
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Modern Sodium-grade options and control hub for Aeonis.
 *
 * Provides a clean Minecraft-authentic tabbed layout:
 * - ⚙ General & HUD preferences
 * - 🧬 Morph & Camouflage settings
 * - 🤖 Companion AI options
 * - ⚡ Tactical quick actions
 * - 📖 Field Manual reader
 *
 * Built on native Minecraft widgets with robust resolution scaling and zero crashes.
 */
@Environment(EnvType.CLIENT)
class AeonisSettingsScreen(private val parent: Screen?) :
    Screen(Component.translatable("options.aeonis.title")) {

    enum class CategoryTab(val title: String, val icon: String) {
        GENERAL("General & HUD", "⚙"),
        MORPH("Morph & Locomotion", "🧬"),
        COMPANION("Companion AI", "🤖"),
        ACTIONS("Tactical Actions", "⚡"),
        MANUAL("Field Manual", "📖")
    }

    private var activeTab: CategoryTab = CategoryTab.GENERAL
    private var selectedChapter: Int = 0
    private var manualPage: Int = 0

    private val modVersion: String = FabricLoader.getInstance()
        .getModContainer("aeonis-manager")
        .map { it.metadata.version.friendlyString }
        .orElse("5.0.0")

    private val aeonisIcon = Identifier.fromNamespaceAndPath("aeonis", "textures/gui/aeonis_icon.png")

    override fun init() {
        super.init()
        AeonisClientSettings.load()
        rebuildScreenWidgets()
    }

    private fun rebuildScreenWidgets() {
        clearWidgets()

        val dialogW = minOf(600, width - 24)
        val dialogH = minOf(330, height - 24)
        val left = (width - dialogW) / 2
        val top = (height - dialogH) / 2

        val sidebarW = 135
        val contentX = left + sidebarW + 8
        val contentW = dialogW - sidebarW - 16
        val contentY = top + 42

        // 1. Sidebar Category Buttons
        CategoryTab.entries.forEachIndexed { idx, tab ->
            val tabY = top + 42 + idx * 24
            val isCurrent = tab == activeTab
            val prefix = if (isCurrent) "▶ " else "  "
            val label = "$prefix${tab.icon} ${tab.title}"

            addRenderableWidget(
                Button.builder(Component.literal(label)) {
                    if (activeTab != tab) {
                        activeTab = tab
                        manualPage = 0
                        rebuildScreenWidgets()
                    }
                }.bounds(left + 6, tabY, sidebarW - 4, 20).build().apply {
                    active = !isCurrent
                }
            )
        }

        // 2. Right Pane Widgets based on Active Tab
        when (activeTab) {
            CategoryTab.GENERAL -> buildGeneralTabWidgets(contentX, contentY, contentW)
            CategoryTab.MORPH -> buildMorphTabWidgets(contentX, contentY, contentW)
            CategoryTab.COMPANION -> buildCompanionTabWidgets(contentX, contentY, contentW)
            CategoryTab.ACTIONS -> buildActionsTabWidgets(contentX, contentY, contentW)
            CategoryTab.MANUAL -> buildManualTabWidgets(contentX, contentY, contentW)
        }

        // 3. Footer Buttons
        val footerY = top + dialogH - 26
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(left + dialogW - 106, footerY, 100, 20)
                .build()
        )
    }

    // ---------------------------------------------------------------- GENERAL TAB
    private fun buildGeneralTabWidgets(x: Int, startY: Int, w: Int) {
        var curY = startY

        // Toggle: Ability HUD
        addOptionToggle(
            x, curY, w,
            title = "Ability HUD",
            desc = "Show active abilities, cooldowns and hotkeys when possessing mobs",
            read = { AeonisClientSettings.showAbilityHud },
            write = { AeonisClientSettings.showAbilityHud = it }
        )
        curY += 28

        // Toggle: Cooldown Timers
        addOptionToggle(
            x, curY, w,
            title = "Cooldown Seconds",
            desc = "Display numeric countdown seconds directly on ability icons",
            read = { AeonisClientSettings.showCooldownSeconds },
            write = { AeonisClientSettings.showCooldownSeconds = it }
        )
        curY += 28

        // Toggle: Form Hints
        addOptionToggle(
            x, curY, w,
            title = "Morph Guidance Hints",
            desc = "Show keybind and locomotion hints when entering a new mob body",
            read = { AeonisClientSettings.showFormHints },
            write = { AeonisClientSettings.showFormHints = it }
        )
        curY += 28

        // Toggle: Dynamic Form Camera
        addOptionToggle(
            x, curY, w,
            title = "Dynamic Form Camera",
            desc = "Automatically offset 3rd person camera distance for giant mob forms",
            read = { AeonisClientSettings.largeFormCamera },
            write = { AeonisClientSettings.largeFormCamera = it }
        )
    }

    // ---------------------------------------------------------------- MORPH TAB
    private fun buildMorphTabWidgets(x: Int, startY: Int, w: Int) {
        var curY = startY

        // Camouflage Selector
        val btnW = 110
        val camoBtn = Button.builder(Component.literal(AeonisClientSettings.camouflageLevel.label)) {
            val levels = CamouflageLevel.entries
            val nextIdx = (AeonisClientSettings.camouflageLevel.ordinal + 1) % levels.size
            AeonisClientSettings.camouflageLevel = levels[nextIdx]
            AeonisClientSettings.save()
            rebuildScreenWidgets()
        }.bounds(x + w - btnW, curY, btnW, 20)
            .tooltip(Tooltip.create(Component.literal(AeonisClientSettings.camouflageLevel.description)))
            .build()
        addRenderableWidget(camoBtn)
        curY += 28

        // Toggle: Locomotion Details
        addOptionToggle(
            x, curY, w,
            title = "Locomotion Details in HUD",
            desc = "Show movement mechanics (flying, swimming, leaping) on the screen banner",
            read = { AeonisClientSettings.showFormDetails },
            write = { AeonisClientSettings.showFormDetails = it }
        )
        curY += 34

        // Action: Open Form Matrix Catalogue
        val catBtn = Button.builder(Component.literal("Browse Form Matrix...")) {
            minecraft?.gui?.setScreen(AeonisFormBrowserScreen(this))
        }.bounds(x, curY, w, 22)
            .tooltip(Tooltip.create(Component.literal("Explore 90+ registered mob forms, abilities, flight mechanics & physics")))
            .build()
        addRenderableWidget(catBtn)
    }

    // ---------------------------------------------------------------- COMPANION TAB
    private fun buildCompanionTabWidgets(x: Int, startY: Int, w: Int) {
        var curY = startY

        // Toggle: Companion Speech Overlay
        addOptionToggle(
            x, curY, w,
            title = "Speech Dialogue Card",
            desc = "Show floating translucent companion dialogue cards with typewriter effect",
            read = { AeonisClientSettings.companionSpeechOverlay },
            write = { AeonisClientSettings.companionSpeechOverlay = it }
        )
        curY += 28

        // Toggle: Typewriter Sound
        addOptionToggle(
            x, curY, w,
            title = "Typewriter Audio",
            desc = "Play crisp click sound feedback as companion messages type out",
            read = { AeonisClientSettings.companionSound },
            write = { AeonisClientSettings.companionSound = it }
        )
    }

    // ---------------------------------------------------------------- ACTIONS TAB
    private fun buildActionsTabWidgets(x: Int, startY: Int, w: Int) {
        var curY = startY
        val actions = listOf(
            Triple("Return to Player Body", "untransform", "Terminate mob possession and return to your player body"),
            Triple("Inspect Current Form", "transform info", "Analyze active form stats, locomotion family and kit"),
            Triple("Possess Random Mob", "transform random", "Instantly transform into a randomly selected registered mob"),
            Triple("Run System Diagnostics", "aeonis diagnostics", "Audit registry status, neural inference and locomotion hooks")
        )

        actions.forEach { (label, cmd, tooltipText) ->
            addRenderableWidget(
                Button.builder(Component.literal(label)) {
                    val conn = minecraft?.player?.connection
                    if (conn != null) {
                        conn.sendCommand(cmd)
                        onClose()
                    }
                }.bounds(x, curY, w, 20)
                    .tooltip(Tooltip.create(Component.literal(tooltipText)))
                    .build()
            )
            curY += 26
        }
    }

    // ---------------------------------------------------------------- MANUAL TAB
    private fun buildManualTabWidgets(x: Int, startY: Int, w: Int) {
        val totalSections = AeonisManual.sections.size
        selectedChapter = selectedChapter.coerceIn(0, maxOf(0, totalSections - 1))
        val section = AeonisManual.sections.getOrNull(selectedChapter) ?: return

        // Chapter selector header
        val headerY = startY
        val prevChBtn = Button.builder(Component.literal("◀")) {
            if (selectedChapter > 0) {
                selectedChapter--
                manualPage = 0
                rebuildScreenWidgets()
            }
        }.bounds(x, headerY, 20, 18).build()
        prevChBtn.active = selectedChapter > 0
        addRenderableWidget(prevChBtn)

        val nextChBtn = Button.builder(Component.literal("▶")) {
            if (selectedChapter < totalSections - 1) {
                selectedChapter++
                manualPage = 0
                rebuildScreenWidgets()
            }
        }.bounds(x + w - 20, headerY, 20, 18).build()
        nextChBtn.active = selectedChapter < totalSections - 1
        addRenderableWidget(nextChBtn)

        // Pagination buttons at bottom of content
        val pageY = headerY + 148
        val prevPgBtn = Button.builder(Component.literal("◀ Prev Page")) {
            if (manualPage > 0) {
                manualPage--
                rebuildScreenWidgets()
            }
        }.bounds(x, pageY, 80, 18).build()
        prevPgBtn.active = manualPage > 0
        addRenderableWidget(prevPgBtn)

        val nextPgBtn = Button.builder(Component.literal("Next Page ▶")) {
            manualPage++
            rebuildScreenWidgets()
        }.bounds(x + w - 80, pageY, 80, 18).build()
        addRenderableWidget(nextPgBtn)
    }

    private fun addOptionToggle(
        x: Int,
        y: Int,
        w: Int,
        title: String,
        desc: String,
        read: () -> Boolean,
        write: (Boolean) -> Unit
    ) {
        val btnW = 75
        val toggleBtn = Button.builder(toggleComponent(read())) {
            val next = !read()
            write(next)
            AeonisClientSettings.save()
            rebuildScreenWidgets()
        }.bounds(x + w - btnW, y, btnW, 20)
            .tooltip(Tooltip.create(Component.literal(desc)))
            .build()

        addRenderableWidget(toggleBtn)
    }

    private fun toggleComponent(enabled: Boolean): Component =
        if (enabled) Component.literal("§a[ ON ]") else Component.literal("§7[ OFF ]")

    // ---------------------------------------------------------------- RENDERING
    override fun extractBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (minecraft?.level != null) {
            extractTransparentBackground(graphics)
        } else {
            super.extractBackground(graphics, mouseX, mouseY, partialTick)
        }
    }

    override fun extractRenderState(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val dialogW = minOf(600, width - 24)
        val dialogH = minOf(330, height - 24)
        val left = (width - dialogW) / 2
        val top = (height - dialogH) / 2

        val sidebarW = 135
        val contentX = left + sidebarW + 8
        val contentW = dialogW - sidebarW - 16
        val contentY = top + 42

        // 1. Dark graphite window container
        graphics.fill(left, top, left + dialogW, top + dialogH, 0xEE12151E.toInt())

        // 2. 3D Bevel borders
        graphics.fill(left, top, left + dialogW, top + 1, 0xFF4A5260.toInt())
        graphics.fill(left, top, left + 1, top + dialogH, 0xFF4A5260.toInt())
        graphics.fill(left, top + dialogH - 1, left + dialogW, top + dialogH, 0xFF0E1017.toInt())
        graphics.fill(left + dialogW - 1, top, left + dialogW, top + dialogH, 0xFF0E1017.toInt())

        // 3. Header bar
        graphics.fill(left + 1, top + 1, left + dialogW - 1, top + 34, 0xD01E222D.toInt())
        graphics.fill(left + 1, top + 34, left + dialogW - 1, top + 35, 0xFF2A2F3D.toInt())

        // Icon (16x16)
        try {
            graphics.blit(RenderPipelines.GUI_TEXTURED, aeonisIcon, left + 8, top + 9, 0f, 0f, 16, 16, 16, 16)
        } catch (_: Throwable) {}

        graphics.text(font, "AEONIS // OPTIONS & CONTROL", left + 28, top + 8, 0xFFFFFF)
        graphics.text(font, "v$modVersion • Minecraft 26.3", left + 28, top + 20, 0x8FA0BE)

        // Live form status badge in header
        drawLiveStatusBadge(graphics, left + dialogW - 12, top + 12)

        // 4. Sidebar / Content divider
        graphics.fill(left + sidebarW + 2, top + 35, left + sidebarW + 3, top + dialogH - 30, 0xFF2A2F3D.toInt())

        // 5. Option Labels in Active Tab
        renderActiveTabLabels(graphics, contentX, contentY, contentW)

        // 6. Bottom status line
        val configLabel = "Config: config/aeonis-client.json"
        graphics.text(font, configLabel, left + 8, top + dialogH - 18, 0x64748B)

        // 7. Render all interactive widgets on top
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawLiveStatusBadge(graphics: GuiGraphics, rightX: Int, y: Int) {
        val (text, color) = if (AeonisClientNetworking.isControlling()) {
            val mob = minecraft?.level?.getEntity(AeonisClientNetworking.getControlledMobId())
                as? net.minecraft.world.entity.Mob
            if (mob != null) {
                "● MORPHED: ${mob.type.description.string.uppercase()}" to 0x10B981
            } else {
                "● MORPHED: ACTIVE" to 0x10B981
            }
        } else {
            "● STANDBY (HUMAN)" to 0x94A3B8
        }
        val tw = font.width(text)
        graphics.text(font, text, rightX - tw, y, color)
    }

    private fun renderActiveTabLabels(graphics: GuiGraphics, x: Int, startY: Int, w: Int) {
        when (activeTab) {
            CategoryTab.GENERAL -> {
                var curY = startY
                val labels = listOf(
                    "Ability HUD" to "Keybinds & form banner while possessing mobs",
                    "Cooldown Timers" to "Numeric countdown seconds on ability icons",
                    "Morph Hints" to "Keybind guidance when entering a new form",
                    "Dynamic Camera" to "Zoom offset for large & colossal mob forms"
                )
                labels.forEach { (title, subtitle) ->
                    graphics.text(font, title, x, curY + 2, 0xFFFFFF)
                    graphics.text(font, subtitle, x, curY + 12, 0x8FA0BE)
                    curY += 28
                }
            }
            CategoryTab.MORPH -> {
                graphics.text(font, "Camouflage Protocol", x, startY + 2, 0xFFFFFF)
                graphics.text(font, "Level: ${AeonisClientSettings.camouflageLevel.description}", x, startY + 12, 0x8FA0BE)

                graphics.text(font, "Locomotion Details in HUD", x, startY + 30, 0xFFFFFF)
                graphics.text(font, "Display flight/swimming/leaping traits on banner", x, startY + 40, 0x8FA0BE)
            }
            CategoryTab.COMPANION -> {
                graphics.text(font, "Speech Dialogue Card", x, startY + 2, 0xFFFFFF)
                graphics.text(font, "Floating HUD card with typewriter revelation", x, startY + 12, 0x8FA0BE)

                graphics.text(font, "Typewriter Audio", x, startY + 30, 0xFFFFFF)
                graphics.text(font, "Mechanical click sound as companion speaks", x, startY + 40, 0x8FA0BE)

                // Telemetry overview
                val infoY = startY + 65
                graphics.fill(x, infoY, x + w, infoY + 60, 0x8010141C.toInt())
                graphics.text(font, "COMPANION STATUS TELEMETRY", x + 6, infoY + 6, 0x38BDF8)
                graphics.text(font, "• Profile Sync: Active (<world>/aeonis/companion_profiles.json)", x + 6, infoY + 18, 0xCBD5E1)
                graphics.text(font, "• Progression Loop: Tree harvest & stone quarry priority", x + 6, infoY + 28, 0xCBD5E1)
                graphics.text(font, "• Hunting Governor: 3-animal limit with weapon check", x + 6, infoY + 38, 0xCBD5E1)
                graphics.text(font, "• Locomotion: Water traversal & 1-wide scaffolding engine", x + 6, infoY + 48, 0xCBD5E1)
            }
            CategoryTab.ACTIONS -> {
                // Actions use full-width buttons with embedded tooltips
            }
            CategoryTab.MANUAL -> {
                val section = AeonisManual.sections.getOrNull(selectedChapter) ?: return
                val titleText = section.title
                val tw = font.width(titleText)
                graphics.text(font, titleText, x + (w - tw) / 2, startY + 4, 0x38BDF8)

                // Word wrap section body
                val wrappedLines = mutableListOf<String>()
                section.body.forEach { para ->
                    wrappedLines += wrapText(para, w)
                    wrappedLines += ""
                }

                val linesPerPage = 11
                val totalPages = maxOf(1, (wrappedLines.size + linesPerPage - 1) / linesPerPage)
                manualPage = manualPage.coerceIn(0, totalPages - 1)

                val pageStart = manualPage * linesPerPage
                val pageLines = wrappedLines.drop(pageStart).take(linesPerPage)

                var textY = startY + 22
                pageLines.forEach { line ->
                    graphics.text(font, line, x, textY, 0xCBD5E1)
                    textY += 11
                }

                val pageInd = "Page ${manualPage + 1} of $totalPages"
                val pw = font.width(pageInd)
                graphics.text(font, pageInd, x + (w - pw) / 2, startY + 152, 0x8FA0BE)
            }
        }
    }

    private fun wrapText(text: String, maxWidth: Int): List<String> {
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
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(parent)
        }
    }
}
