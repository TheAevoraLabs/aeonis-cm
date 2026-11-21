package com.qc.aeonis.screen

import com.qc.aeonis.companion.order.CompanionOrder
import com.qc.aeonis.entity.CompanionBotEntity
import com.qc.aeonis.transform.CamouflageLevel
import com.qc.aeonis.transform.MobCatalogue
import dev.lambdaurora.spruceui.Position
import dev.lambdaurora.spruceui.background.EmptyBackground
import dev.lambdaurora.spruceui.border.SimpleBorder
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics
import dev.lambdaurora.spruceui.screen.SpruceScreen
import dev.lambdaurora.spruceui.widget.AbstractSpruceBooleanButtonWidget
import dev.lambdaurora.spruceui.widget.AbstractSpruceWidget
import dev.lambdaurora.spruceui.widget.SpruceButtonWidget
import dev.lambdaurora.spruceui.widget.SpruceSeparatorWidget
import dev.lambdaurora.spruceui.widget.SpruceToggleSwitch
import dev.lambdaurora.spruceui.widget.container.SpruceContainerWidget
import dev.lambdaurora.spruceui.widget.container.SpruceEntryListWidget
import dev.lambdaurora.spruceui.widget.container.tabbed.SpruceTabbedWidget
import dev.lambdaurora.spruceui.widget.text.SpruceTextFieldWidget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Sodium-grade Aeonis Hub Screen built with modern SpruceUI tabbed widgets.
 *
 * Provides five full-featured command centers:
 * 1. 🧬 Morph & Camouflage Matrix (Searchable grid, 3D entity previews, ability cooldowns & traits)
 * 2. 🤖 Companion AI Command Center (Live telemetry, hunger, health, tactical orders, skills & grief guard)
 * 3. 🎮 Minigames Arcade (PropHunt arena launcher & Speedrun / Manhunt tracker)
 * 4. ⚙ Mod Settings & Controls (Clean toggles, sliders, dynamic camera, typewriter audio & keybinds)
 * 5. 📖 Field Manual (Official in-game guidebook with chapter index & word-wrapped reader)
 *
 * Shared seamlessly across both Fabric and NeoForge.
 */
open class AeonisHubScreen(protected val parent: Screen? = null) :
    SpruceScreen(Component.literal("Aeonis Control Hub")) {

    private lateinit var tabbedWidget: SpruceTabbedWidget

    // State for Morph Tab
    private var morphSearchQuery: String = ""
    private var selectedMobType: EntityType<*>? = null
    private var cachedPreviewEntity: LivingEntity? = null
    private val entityCache = ConcurrentHashMap<EntityType<*>, LivingEntity>()

    // State for Manual Tab
    private var manualChapterIndex: Int = 0
    private var manualPageIndex: Int = 0

    override fun init() {
        super.init()
        AeonisClientSettings.load()

        if (selectedMobType == null) {
            selectedMobType = MobCatalogue.possessable().firstOrNull()
        }

        val dialogW = minOf(640, width - 24)
        val dialogH = minOf(360, height - 24)
        val left = (width - dialogW) / 2
        val top = (height - dialogH) / 2

        val sideWidth = 148

        // Create SpruceTabbedWidget
        tabbedWidget = SpruceTabbedWidget(
            Position.of(left, top + 36),
            dialogW,
            dialogH - 64,
            null,
            sideWidth
        )

        // 1. Morph & Camouflage Matrix Tab
        tabbedWidget.addTabEntry(
            Component.literal("🧬 Morph Matrix"),
            Component.literal("Browse forms, 3D entity models & ability matrix"),
            { w, h -> buildMorphContainer(w, h) }
        )

        // 2. Companion Command Center Tab
        tabbedWidget.addTabEntry(
            Component.literal("🤖 Companion AI"),
            Component.literal("Live telemetry, orders, hunger & skills"),
            { w, h -> buildCompanionContainer(w, h) }
        )

        // 3. Minigames Arcade Tab
        tabbedWidget.addTabEntry(
            Component.literal("🎮 Minigames"),
            Component.literal("PropHunt arena launcher & Manhunt AI setup"),
            { w, h -> buildMinigamesContainer(w, h) }
        )

        // 4. Mod Settings & Controls Tab
        tabbedWidget.addTabEntry(
            Component.literal("⚙ Settings"),
            Component.literal("HUD preferences, visual toggles & shortcuts"),
            { w, h -> buildSettingsContainer(w, h) }
        )

        // 5. Field Manual Tab
        tabbedWidget.addTabEntry(
            Component.literal("📖 Field Manual"),
            Component.literal("Full in-game documentation & mechanics guide"),
            { w, h -> buildManualContainer(w, h) }
        )

        addRenderableWidget(tabbedWidget)

        // Footer Done Button
        val footerY = top + dialogH - 24
        addRenderableWidget(
            SpruceButtonWidget(
                Position.of(left + dialogW - 100, footerY),
                98, 20,
                CommonComponents.GUI_DONE
            ) { onClose() }
        )

        // Quick Possess / Untransform Button in Footer
        addRenderableWidget(
            SpruceButtonWidget(
                Position.of(left + 2, footerY),
                130, 20,
                Component.literal("⚡ Quick Untransform")
            ) {
                runCommand("untransform")
                onClose()
            }
        )
    }

    // ---------------------------------------------------------------------------------------------
    // TAB 1: MORPH & CAMOUFLAGE MATRIX
    // ---------------------------------------------------------------------------------------------
    private fun buildMorphContainer(w: Int, h: Int): AbstractSpruceWidget {
        val container = SpruceContainerWidget(Position.of(0, 0), w, h)
        val leftColW = 160
        val rightColX = leftColW + 8
        val rightColW = w - rightColX

        // 1. Search Box
        val searchBox = SpruceTextFieldWidget(
            Position.of(container, 4, 4),
            leftColW - 8, 18,
            Component.literal("Search Forms"),
            Component.literal("Search mobs...")
        )
        searchBox.setText(morphSearchQuery)

        // 2. Mob Forms Entry List
        val mobList = FormListWidget(
            Position.of(container, 4, 26),
            leftColW - 8, h - 30,
            onSelect = { type ->
                selectedMobType = type
                updateCachedPreview(type)
                tabbedWidget.list.setSelected(tabbedWidget.list.currentTab)
            }
        )

        fun refreshList(query: String) {
            mobList.populate(query)
        }

        searchBox.setChangedListener { query ->
            morphSearchQuery = query
            refreshList(query)
        }
        refreshList(morphSearchQuery)

        container.addChild(searchBox)
        container.addChild(mobList)

        // 3. Right Pane Details Widget
        val detailWidget = object : AbstractSpruceWidget(Position.of(container, rightColX, 4)) {
            init {
                this.width = rightColW
                this.height = h - 8
            }

            override fun extractWidgetRenderState(graphics: SpruceGuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
                val type = selectedMobType ?: return
                val summary = MobCatalogue.summary(type)
                val x = getX()
                val y = getY()

                // Header
                graphics.shadowedText(font, summary.formName, x, y + 2, 0xFFFFFF)
                val token = MobCatalogue.token(type)
                graphics.shadowedText(font, "Token: /transform $token", x, y + 14, 0x38BDF8)

                // Locomotion badge
                val movementDesc = "Locomotion: ${summary.movement}"
                graphics.shadowedText(font, movementDesc, x, y + 26, 0xE2C86A)

                // 3D Entity Preview Box
                val previewX = x + width - 100
                val previewY = y + 8
                val previewW = 92
                val previewH = 110

                // Slate frame for 3D model
                graphics.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0x80141C2B.toInt())
                graphics.fill(previewX, previewY, previewX + previewW, previewY + 1, 0xFF38BDF8.toInt())
                graphics.fill(previewX, previewY + previewH - 1, previewX + previewW, previewY + previewH, 0xFF38BDF8.toInt())
                graphics.fill(previewX, previewY, previewX + 1, previewY + previewH, 0xFF38BDF8.toInt())
                graphics.fill(previewX + previewW - 1, previewY, previewX + previewW, previewY + previewH, 0xFF38BDF8.toInt())

                val entity = getOrCreatePreviewEntity(type)
                if (entity != null) {
                    try {
                        val entityH = entity.bbHeight.coerceAtLeast(0.5f)
                        val scale = (38f / entityH).coerceIn(12f, 48f).toInt()
                        val cx = previewX + previewW / 2
                        val cy = previewY + previewH - 12
                        InventoryScreen.extractEntityInInventoryFollowsMouse(
                            graphics.vanilla(),
                            previewX + 2, previewY + 2, previewX + previewW - 2, previewY + previewH - 2,
                            scale,
                            0f,
                            (cx - mouseX).toFloat(),
                            (cy - 20 - mouseY).toFloat(),
                            entity
                        )
                    } catch (_: Exception) {
                        graphics.shadowedText(font, "3D N/A", previewX + 28, previewY + 48, 0x8FA0BE)
                    }
                } else {
                    graphics.shadowedText(font, "3D N/A", previewX + 28, previewY + 48, 0x8FA0BE)
                }

                // Abilities
                var curY = y + 42
                graphics.shadowedText(font, "Special Abilities:", x, curY, 0x38BDF8)
                curY += 12

                if (summary.abilities.isEmpty()) {
                    graphics.shadowedText(font, "• Passive physical traits only", x, curY, 0x8FA0BE)
                } else {
                    summary.abilities.forEach { abilityStr ->
                        val lines = wrapText(abilityStr, width - 110)
                        lines.forEach { line ->
                            graphics.shadowedText(font, line, x, curY, 0xCBD5E1)
                            curY += 10
                        }
                    }
                }
            }
        }
        container.addChild(detailWidget)

        // 4. Action Buttons below preview
        val btnY = h - 50
        val btnW = (rightColW - 12) / 2

        // Transform Button
        container.addChild(
            SpruceButtonWidget(
                Position.of(container, rightColX, btnY),
                btnW, 20,
                Component.literal("⚡ Possess Form")
            ) {
                selectedMobType?.let { type ->
                    runCommand("transform ${MobCatalogue.token(type)}")
                    onClose()
                }
            }
        )

        // Random Transform Button
        container.addChild(
            SpruceButtonWidget(
                Position.of(container, rightColX + btnW + 6, btnY),
                btnW, 20,
                Component.literal("🎲 Random Form")
            ) {
                runCommand("transform random")
                onClose()
            }
        )

        // Camouflage Selector Button
        val camoBtnY = btnY + 24
        val camoButton = SpruceButtonWidget(
            Position.of(container, rightColX, camoBtnY),
            rightColW - 6, 20,
            Component.literal("Camouflage Protocol: ${AeonisClientSettings.camouflageLevel.label}")
        ) { btn ->
            val entries = CamouflageLevel.entries
            val next = (AeonisClientSettings.camouflageLevel.ordinal + 1) % entries.size
            AeonisClientSettings.camouflageLevel = entries[next]
            AeonisClientSettings.save()
            btn.message = Component.literal("Camouflage Protocol: ${AeonisClientSettings.camouflageLevel.label}")
        }
        container.addChild(camoButton)

        return container
    }

    // ---------------------------------------------------------------------------------------------
    // TAB 2: COMPANION COMMAND CENTER
    // ---------------------------------------------------------------------------------------------
    private fun buildCompanionContainer(w: Int, h: Int): AbstractSpruceWidget {
        val container = SpruceContainerWidget(Position.of(0, 0), w, h)

        // Telemetry header card
        val telemetryWidget = object : AbstractSpruceWidget(Position.of(container, 6, 4)) {
            init {
                this.width = w - 12
                this.height = 70
            }

            override fun extractWidgetRenderState(graphics: SpruceGuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
                val x = getX()
                val y = getY()
                // Slate background
                graphics.fill(x, y, x + width, y + height, 0x90141C2B.toInt())
                graphics.fill(x, y, x + width, y + 1, 0xFF38BDF8.toInt())
                graphics.fill(x, y + height - 1, x + width, y + height, 0xFF1E293B.toInt())

                graphics.shadowedText(font, "COMPANION STATUS TELEMETRY", x + 8, y + 6, 0x38BDF8)

                // Companion Bot live query
                val bot = getCompanionBot()
                if (bot != null) {
                    val hp = bot.health.toInt()
                    val maxHp = bot.maxHealth.toInt()
                    val order = bot.orderState.order.label
                    val heldStacks = (0 until bot.inventory.containerSize).count { !bot.inventory.getItem(it).isEmpty }

                    // Health row
                    graphics.shadowedText(font, "Health: $hp / $maxHp HP", x + 8, y + 20, 0xF87171)
                    val barW = 120
                    graphics.fill(x + 130, y + 21, x + 130 + barW, y + 27, 0xFF334155.toInt())
                    val hpFill = (barW * (hp.toFloat() / maxHp.coerceAtLeast(1))).toInt().coerceIn(0, barW)
                    graphics.fill(x + 130, y + 21, x + 130 + hpFill, y + 27, 0xFFEF4444.toInt())

                    // Standing order & state
                    graphics.shadowedText(font, "Standing Order: $order", x + 8, y + 36, 0x34D399)
                    graphics.shadowedText(font, "• Grief Gate: Active  • Autonomous 1-Wide Scaffolding: Ready", x + 8, y + 48, 0x94A3B8)
                    graphics.shadowedText(font, "• Backpack: Active ($heldStacks stacks held)", x + 8, y + 58, 0x38BDF8)
                } else {
                    graphics.shadowedText(font, "• Companion status: Idle or in unloaded chunk", x + 8, y + 22, 0x94A3B8)
                    graphics.shadowedText(font, "• Summon with [/comp follow] or use the Tactical Command buttons below", x + 8, y + 36, 0xCBD5E1)
                    graphics.shadowedText(font, "• Profile Sync: Active (<world>/aeonis/companion_profiles.json)", x + 8, y + 50, 0x38BDF8)
                }
            }
        }
        container.addChild(telemetryWidget)

        // Tactical Order Grid
        val sep = SpruceSeparatorWidget(Position.of(container, 6, 80), w - 12, Component.literal("Tactical Orders"))
        container.addChild(sep)

        val orders = listOf(
            Triple("🚶 Follow", "comp follow", "Command bot to follow closely"),
            Triple("🛑 Hold Position", "comp hold", "Hold current spot firmly"),
            Triple("🛡 Guard Area", "comp guard", "Protect surrounding perimeter"),
            Triple("🏹 Hunt Food", "comp hunt", "Hunt nearby game animals"),
            Triple("⚔ Attack Threat", "comp attack", "Target visible hostile entities"),
            Triple("🌐 Free Autonomy", "comp free", "Restore full autonomous behavior")
        )

        val gridCols = 3
        val btnW = (w - 24) / gridCols
        val btnH = 20
        var curY = 96

        orders.forEachIndexed { idx, (label, cmd, _) ->
            val col = idx % gridCols
            val row = idx / gridCols
            val bx = 6 + col * (btnW + 6)
            val by = curY + row * (btnH + 4)

            container.addChild(
                SpruceButtonWidget(Position.of(container, bx, by), btnW, btnH, Component.literal(label)) {
                    runCommand(cmd)
                }
            )
        }

        // Utility quick buttons
        val utilY = curY + 2 * (btnH + 4) + 8
        val utilW = (w - 24) / 3

        container.addChild(
            SpruceButtonWidget(Position.of(container, 6, utilY), utilW, 20, Component.literal("⚡ Recall Bot")) {
                runCommand("comp recall")
                onClose()
            }
        )

        container.addChild(
            SpruceButtonWidget(Position.of(container, 6 + utilW + 6, utilY), utilW, 20, Component.literal("📦 Open Backpack")) {
                runCommand("comp inventory")
                onClose()
            }
        )

        container.addChild(
            SpruceButtonWidget(Position.of(container, 6 + 2 * (utilW + 6), utilY), utilW, 20, Component.literal("📍 Set Home Anchor")) {
                runCommand("comp home set")
            }
        )

        return container
    }

    // ---------------------------------------------------------------------------------------------
    // TAB 3: MINIGAMES ARCADE
    // ---------------------------------------------------------------------------------------------
    private fun buildMinigamesContainer(w: Int, h: Int): AbstractSpruceWidget {
        val container = SpruceContainerWidget(Position.of(0, 0), w, h)
        val colW = (w - 18) / 2

        // Left Half: PropHunt Launcher
        val propHeader = SpruceSeparatorWidget(Position.of(container, 6, 4), colW, Component.literal("🎭 PropHunt Arcade"))
        container.addChild(propHeader)

        val propBox = object : AbstractSpruceWidget(Position.of(container, 6, 20)) {
            init {
                this.width = colW
                this.height = 68
            }

            override fun extractWidgetRenderState(graphics: SpruceGuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
                val x = getX()
                val y = getY()
                graphics.fill(x, y, x + width, y + height, 0x80141C2B.toInt())
                graphics.fill(x, y, x + width, y + 1, 0xFF38BDF8.toInt())

                graphics.shadowedText(font, "• Camouflage: Real BlockDisplay / ItemDisplay", x + 6, y + 6, 0xCBD5E1)
                graphics.shadowedText(font, "• Grid Lock: Sneak or [/prophunt prop lock]", x + 6, y + 18, 0xCBD5E1)
                graphics.shadowedText(font, "• Sonar Ping: 15-block echolocation radar", x + 6, y + 30, 0xCBD5E1)
                graphics.shadowedText(font, "• Hunter Penalty: Fatigue on missed blocks", x + 6, y + 42, 0xCBD5E1)
                graphics.shadowedText(font, "• Taunt Whistle: Audio whistle on cooldown", x + 6, y + 54, 0xCBD5E1)
            }
        }
        container.addChild(propBox)

        var propBtnY = 94
        container.addChild(
            SpruceButtonWidget(Position.of(container, 6, propBtnY), colW, 20, Component.literal("▶ Start PropHunt Match")) {
                runCommand("prophunt start")
                onClose()
            }
        )
        propBtnY += 24
        container.addChild(
            SpruceButtonWidget(Position.of(container, 6, propBtnY), (colW - 4) / 2, 20, Component.literal("🔒 Lock Prop")) {
                runCommand("prophunt prop lock")
            }
        )
        container.addChild(
            SpruceButtonWidget(Position.of(container, 6 + (colW - 4) / 2 + 4, propBtnY), (colW - 4) / 2, 20, Component.literal("📢 Whistle")) {
                runCommand("prophunt taunt")
            }
        )

        // Right Half: Speedrun / Manhunt Launcher
        val manhuntHeader = SpruceSeparatorWidget(Position.of(container, 6 + colW + 6, 4), colW, Component.literal("⏱ Speedrun & Manhunt"))
        container.addChild(manhuntHeader)

        val manhuntBox = object : AbstractSpruceWidget(Position.of(container, 6 + colW + 6, 20)) {
            init {
                this.width = colW
                this.height = 68
            }

            override fun extractWidgetRenderState(graphics: SpruceGuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
                val x = getX()
                val y = getY()
                graphics.fill(x, y, x + width, y + height, 0x80141C2B.toInt())
                graphics.fill(x, y, x + width, y + 1, 0xFF38BDF8.toInt())

                graphics.shadowedText(font, "• Multi-Dimensional Smart Compass", x + 6, y + 6, 0xCBD5E1)
                graphics.shadowedText(font, "• Autonomous Neural Hunter AI Bot", x + 6, y + 18, 0xCBD5E1)
                graphics.shadowedText(font, "• Nether, Fortress & Stronghold Splits", x + 6, y + 30, 0xCBD5E1)
                graphics.shadowedText(font, "• Height Delta Trackers & Lodestone Sync", x + 6, y + 42, 0xCBD5E1)
                graphics.shadowedText(font, "• AI Difficulties: Casual / Tactical / Terminator", x + 6, y + 54, 0xCBD5E1)
            }
        }
        container.addChild(manhuntBox)

        var manhuntBtnY = 94
        container.addChild(
            SpruceButtonWidget(Position.of(container, 6 + colW + 6, manhuntBtnY), colW, 20, Component.literal("▶ Start Manhunt Game")) {
                runCommand("manhunt start")
                onClose()
            }
        )
        manhuntBtnY += 24
        container.addChild(
            SpruceButtonWidget(Position.of(container, 6 + colW + 6, manhuntBtnY), (colW - 4) / 2, 20, Component.literal("🧭 Get Compass")) {
                runCommand("manhunt compass")
            }
        )
        container.addChild(
            SpruceButtonWidget(Position.of(container, 6 + colW + 6 + (colW - 4) / 2 + 4, manhuntBtnY), (colW - 4) / 2, 20, Component.literal("🔄 Reset Match")) {
                runCommand("manhunt reset")
            }
        )

        return container
    }

    // ---------------------------------------------------------------------------------------------
    // TAB 4: MOD SETTINGS & CONTROLS
    // ---------------------------------------------------------------------------------------------
    private fun buildSettingsContainer(w: Int, h: Int): AbstractSpruceWidget {
        val container = SpruceContainerWidget(Position.of(0, 0), w, h)
        var curY = 6

        fun addToggleRow(title: String, desc: String, initial: Boolean, onToggle: (Boolean) -> Unit) {
            val labelW = w - 70
            val labelWidget = object : AbstractSpruceWidget(Position.of(container, 8, curY)) {
                init {
                    this.width = labelW
                    this.height = 24
                }

                override fun extractWidgetRenderState(graphics: SpruceGuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
                    graphics.shadowedText(font, title, getX(), getY() + 2, 0xFFFFFF)
                    graphics.shadowedText(font, desc, getX(), getY() + 12, 0x8FA0BE)
                }
            }
            container.addChild(labelWidget)

            val toggle = SpruceToggleSwitch(
                Position.of(container, w - 54, curY + 2),
                48, 20,
                Component.empty(),
                AbstractSpruceBooleanButtonWidget.PressAction { _, newValue ->
                    onToggle(newValue)
                    AeonisClientSettings.save()
                },
                initial
            )
            container.addChild(toggle)
            curY += 26
        }

        addToggleRow("Ability HUD", "Show hotkeys and form banner while possessing mobs", AeonisClientSettings.showAbilityHud) {
            AeonisClientSettings.showAbilityHud = it
        }

        addToggleRow("Cooldown Countdown Numbers", "Display numeric seconds on active ability icons", AeonisClientSettings.showCooldownSeconds) {
            AeonisClientSettings.showCooldownSeconds = it
        }

        addToggleRow("Form Keybind Hints", "Display key hints upon transforming into new forms", AeonisClientSettings.showFormHints) {
            AeonisClientSettings.showFormHints = it
        }

        addToggleRow("Dynamic Colossal Camera", "Pull camera back automatically for giant mobs (Dragon/Giant)", AeonisClientSettings.largeFormCamera) {
            AeonisClientSettings.largeFormCamera = it
        }

        addToggleRow("Companion Speech Overlay", "Floating dialogue cards with typewriter revelation", AeonisClientSettings.companionSpeechOverlay) {
            AeonisClientSettings.companionSpeechOverlay = it
        }

        addToggleRow("Typewriter Audio Click", "Mechanical clicks as companion dialogues reveal", AeonisClientSettings.companionSound) {
            AeonisClientSettings.companionSound = it
        }

        return container
    }

    // ---------------------------------------------------------------------------------------------
    // TAB 5: FIELD MANUAL
    // ---------------------------------------------------------------------------------------------
    private fun buildManualContainer(w: Int, h: Int): AbstractSpruceWidget {
        val container = SpruceContainerWidget(Position.of(0, 0), w, h)
        val sections = AeonisManual.sections

        manualChapterIndex = manualChapterIndex.coerceIn(0, (sections.size - 1).coerceAtLeast(0))
        val currentSection = sections.getOrNull(manualChapterIndex)

        // Chapter Selector Dropdown / Buttons
        val chapterBtn = SpruceButtonWidget(
            Position.of(container, 8, 4),
            w - 16, 20,
            Component.literal("Chapter: ${currentSection?.title ?: "None"}")
        ) {
            manualChapterIndex = (manualChapterIndex + 1) % sections.size
            manualPageIndex = 0
            tabbedWidget.list.setSelected(tabbedWidget.list.currentTab)
        }
        container.addChild(chapterBtn)

        // Manual text body reader widget
        val textReader = object : AbstractSpruceWidget(Position.of(container, 8, 28)) {
            init {
                this.width = w - 16
                this.height = h - 56
            }

            override fun extractWidgetRenderState(graphics: SpruceGuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
                val sec = currentSection ?: return
                val x = getX()
                val y = getY()

                val allLines = mutableListOf<String>()
                sec.body.forEach { paragraph ->
                    allLines.addAll(wrapText(paragraph, width - 8))
                    allLines.add("")
                }

                val linesPerPage = 8
                val totalPages = ((allLines.size + linesPerPage - 1) / linesPerPage).coerceAtLeast(1)
                manualPageIndex = manualPageIndex.coerceIn(0, totalPages - 1)

                val startLine = manualPageIndex * linesPerPage
                val pageLines = allLines.drop(startLine).take(linesPerPage)

                var textY = y + 4
                pageLines.forEach { line ->
                    graphics.shadowedText(font, line, x + 4, textY, 0xCBD5E1)
                    textY += 12
                }

                // Page indicator
                val indicator = "Page ${manualPageIndex + 1} of $totalPages"
                graphics.shadowedText(font, indicator, x + width / 2 - font.width(indicator) / 2, y + height - 12, 0x38BDF8)
            }
        }
        container.addChild(textReader)

        // Pagination buttons
        val pagerY = h - 24
        val pagerBtnW = 60
        container.addChild(
            SpruceButtonWidget(Position.of(container, 8, pagerY), pagerBtnW, 20, Component.literal("◀ Prev")) {
                if (manualPageIndex > 0) {
                    manualPageIndex--
                    tabbedWidget.list.setSelected(tabbedWidget.list.currentTab)
                }
            }
        )
        container.addChild(
            SpruceButtonWidget(Position.of(container, w - 8 - pagerBtnW, pagerY), pagerBtnW, 20, Component.literal("Next ▶")) {
                manualPageIndex++
                tabbedWidget.list.setSelected(tabbedWidget.list.currentTab)
            }
        )

        return container
    }

    // ---------------------------------------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------------------------------------
    private fun getCompanionBot(): CompanionBotEntity? {
        val player = minecraft.player ?: return null
        val level = minecraft.level ?: return null
        return level.entitiesForRendering()
            .filterIsInstance<CompanionBotEntity>()
            .firstOrNull { it.ownerUuid == player.uuid }
    }

    private fun updateCachedPreview(type: EntityType<*>) {
        cachedPreviewEntity = getOrCreatePreviewEntity(type)
    }

    private fun getOrCreatePreviewEntity(type: EntityType<*>): LivingEntity? {
        val cached = entityCache[type]
        if (cached != null) return cached
        val level = minecraft.level ?: return null
        return try {
            val created = type.create(level, EntitySpawnReason.LOAD)
            if (created is LivingEntity) {
                entityCache[type] = created
                created
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun runCommand(command: String) {
        minecraft.player?.connection?.sendCommand(command)
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

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (minecraft.level != null) {
            this.extractTransparentBackground(graphics)
        } else {
            super.extractBackground(graphics, mouseX, mouseY, delta)
        }
    }

    override fun extractRenderState(graphics: SpruceGuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val dialogW = minOf(640, width - 24)
        val dialogH = minOf(360, height - 24)
        val left = (width - dialogW) / 2
        val top = (height - dialogH) / 2

        // Procedural Slate Theme Background
        graphics.fill(left, top, left + dialogW, top + dialogH, 0xEE10141D.toInt())

        // Slate bevel outline
        graphics.fill(left, top, left + dialogW, top + 1, 0xFF38BDF8.toInt())
        graphics.fill(left, top, left + 1, top + dialogH, 0xFF38BDF8.toInt())
        graphics.fill(left, top + dialogH - 1, left + dialogW, top + dialogH, 0xFF0F172A.toInt())
        graphics.fill(left + dialogW - 1, top, left + dialogW, top + dialogH, 0xFF0F172A.toInt())

        // Top Header Banner
        graphics.fill(left + 1, top + 1, left + dialogW - 1, top + 32, 0xD01E2536.toInt())
        graphics.fill(left + 1, top + 32, left + dialogW - 1, top + 33, 0xFF38BDF8.toInt())

        // Header Title, Version & Authors
        graphics.shadowedText(font, "AEONIS CONTROL HUB", left + 12, top + 8, 0xFFFFFF)
        graphics.shadowedText(font, "v5.0 · Minecraft 26.3+ · Dual Loader (SpruceUI)", left + 12, top + 19, 0x38BDF8)
        val creditsText = "by QuantumCreeper & MrMK · Aevora Labs"
        val creditsWidth = font.width(creditsText)
        graphics.shadowedText(font, creditsText, left + dialogW - creditsWidth - 12, top + 13, 0x94A3B8)

        super.extractRenderState(graphics, mouseX, mouseY, delta)
    }

    override fun onClose() {
        AeonisClientSettings.save()
        if (minecraft.gui != null) {
            minecraft.gui.setScreen(parent)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FORM LIST WIDGET
    // ---------------------------------------------------------------------------------------------
    private class FormListWidget(
        position: Position,
        width: Int,
        height: Int,
        private val onSelect: (EntityType<*>) -> Unit
    ) : SpruceEntryListWidget<FormListWidget.FormEntry>(position, width, height, 2, FormEntry::class.java) {

        init {
            this.background = EmptyBackground.EMPTY_BACKGROUND
            this.border = SimpleBorder(0, 0, 0, 0, 0)
        }

        fun populate(filter: String) {
            this.clearEntries()
            val query = filter.trim().lowercase()
            val forms = MobCatalogue.possessable().filter {
                query.isEmpty() || it.description.string.lowercase().contains(query) ||
                    MobCatalogue.token(it).lowercase().contains(query)
            }
            forms.forEach { type ->
                this.addEntry(FormEntry(type, onSelect))
            }
        }

        class FormEntry(
            val type: EntityType<*>,
            val onSelect: (EntityType<*>) -> Unit
        ) : SpruceEntryListWidget.Entry() {

            init {
                this.width = 140
                this.height = 18
            }

            override fun extractWidgetRenderState(graphics: SpruceGuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
                val isHovered = mouseX >= getX() && mouseX <= getX() + width && mouseY >= getY() && mouseY <= getY() + height
                val bg = if (isHovered) 0x6038BDF8.toInt() else 0x301E293B.toInt()
                graphics.fill(getX(), getY(), getX() + width, getY() + height, bg)

                val name = type.description.string
                val color = if (isHovered) 0xFFFFFF else 0xCBD5E1
                graphics.shadowedText(Minecraft.getInstance().font, name, getX() + 4, getY() + 5, color)
            }

            override fun onMouseClick(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
                if (event.button() == 0) {
                    onSelect(type)
                    return true
                }
                return super.onMouseClick(event, doubleClick)
            }
        }
    }
}

/**
 * Direct backwards-compatible subclass so all mixins and commands reference AeonisSettingsScreen cleanly.
 */
class AeonisSettingsScreen(parent: Screen? = null) : AeonisHubScreen(parent)
