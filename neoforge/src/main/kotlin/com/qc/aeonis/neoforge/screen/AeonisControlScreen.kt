package com.qc.aeonis.neoforge.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.ResourceKey
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem
import java.util.*

class AeonisControlScreen(private val parentScreen: Screen?) : Screen(Component.literal("Aeonis Control Panel")) {

    private val modName = "Aeonis"
    private val modId = "aeonis"
    private val modVersion = "26.1.2"
    private val logoTexture = Identifier.fromNamespaceAndPath("aeonis", "icon.png")

    private lateinit var entityList: AeonisEntityListWidget
    private lateinit var searchField: EditBox
    private lateinit var customCommandField: EditBox
    private var selectedEntityType: EntityType<*>? = null
    private var transformButton: Button? = null

    private val entityListTop = 80
    private val rightPaneX get() = width / 2 + 10
    private val rightPaneWidth get() = width / 2 - 20
    private val quickCommandsLabelY get() = entityListTop + 84

    private val quickCommands = listOf(
        QuickCommand("aeonis transform herobrine", Component.literal("Herobrine")),
        QuickCommand("aeonis transform copper_stalker", Component.literal("Copper Stalker")),
        QuickCommand("aeonis transform body", Component.literal("Body")),
        QuickCommand("aeonis soul mode toggle", Component.literal("Toggle Soul Mode")),
        QuickCommand("aeonis possession release", Component.literal("Release Possession"))
    )

    override fun init() {
        super.init()

        searchField = EditBox(font, 12, entityListTop - 44, width / 2 - 24, 16, Component.literal("Search..."))
        searchField.setResponder { updateEntityList() }
        addRenderableWidget(searchField)

        entityList = AeonisEntityListWidget(minecraft, width / 2 - 16, height - entityListTop - 20, entityListTop, 24)
        addRenderableWidget(entityList)

        updateEntityList()

        transformButton = Button.builder(Component.literal("Transform")) {
            selectedEntityType?.let { type ->
                BuiltInRegistries.ENTITY_TYPE.getResourceKey(type).ifPresent { key ->
                    runCommand("aeonis transform ${key.identifier().getPath()}")
                }
            }
        }.bounds(rightPaneX, entityListTop, 100, 20).build()
        transformButton?.active = false
        addRenderableWidget(transformButton!!)

        var currentY = quickCommandsLabelY + 14
        quickCommands.forEach { cmd ->
            addRenderableWidget(
                Button.builder(cmd.label) { runCommand(cmd.command) }
                    .bounds(rightPaneX, currentY, rightPaneWidth, 18).build()
            )
            currentY += 22
        }

        customCommandField = EditBox(font, rightPaneX, height - 38, rightPaneWidth - 44, 16, Component.literal("Custom command..."))
        customCommandField.setMaxLength(128)
        addRenderableWidget(customCommandField)

        addRenderableWidget(
            Button.builder(Component.literal("Run")) { runCustomCommand() }
                .bounds(rightPaneX + rightPaneWidth - 40, height - 39, 40, 18).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Back")) { onClose() }
                .bounds(width - 70, 15, 60, 20).build()
        )
    }

    private fun updateEntityList() {
        val filter = searchField.value.lowercase(Locale.ROOT)
        val entries = BuiltInRegistries.ENTITY_TYPE.asIterable()
            .map { EntityEntry(it) }
            .filter { it.matches(filter) }
            .sortedBy { it.displayName.string }
        entityList.refreshEntries(entries)
    }

    private fun runCustomCommand() {
        val cmd = customCommandField.value
        if (cmd.isNotEmpty()) {
            runCommand(cmd)
            customCommandField.setValue("")
        }
    }

    private fun runCommand(command: String) {
        val player = minecraft.player ?: return
        if (command.isBlank()) return
        player.connection.sendCommand(command)
    }

    override fun extractRenderState(graphics: GuiGraphics, mouseX: Int, mouseY: Int, a: Float) {
        drawModernBackground(graphics)
        drawHeader(graphics)
        drawPanels(graphics)

        graphics.text(font, Component.literal("Entity Registry"), 12, entityListTop - 26, 0xE8F0FF)
        graphics.text(font, Component.literal("Select a creature to transform into"), 12, entityListTop - 12, 0x9FAEC9)
        graphics.text(font, Component.literal("Selection"), rightPaneX, entityListTop - 26, 0xE8F0FF)
        val nameComponent = selectedEntityType?.let { Component.translatable(it.descriptionId) } ?: Component.translatable("options.aeonis.selected_entity.none")
        graphics.text(font, nameComponent, rightPaneX, entityListTop - 12, 0xFFFFFF)
        selectedEntityType?.let { type ->
            BuiltInRegistries.ENTITY_TYPE.getResourceKey(type).ifPresent { id ->
                graphics.text(font, Component.literal(id.identifier().toString()), rightPaneX, entityListTop, 0x7E8CAB)
            }
        }
        graphics.text(font, Component.literal("Quick Actions"), rightPaneX, quickCommandsLabelY, 0xD8E2FA)
        graphics.text(font, Component.literal("Custom"), rightPaneX, customCommandField.y - 12, 0xD8E2FA)

        drawSelectionCard(graphics)

        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    private fun drawModernBackground(graphics: GuiGraphics) {
        val pulse = ((Util.getMillis() / 20L) % 40L).toInt()
        val drift = pulse - 20
        val topStart = 0xE5131826.toInt()
        val topEnd = 0xEA09121F.toInt()
        val bottomStart = 0xF0112943.toInt()
        val bottomEnd = 0xF0061A2E.toInt()

        graphics.fillGradient(0, 0, width, height / 2 + drift, topStart, topEnd)
        graphics.fillGradient(0, height / 3, width, height, bottomStart, bottomEnd)
    }

    private fun drawHeader(graphics: GuiGraphics) {
        val headerTop = 8
        val headerLeft = 8
        val headerRight = width - 8
        val headerHeight = 60
        val headerBottomLocal = headerTop + headerHeight

        graphics.fill(headerLeft, headerTop, headerRight, headerBottomLocal, 0xAA101C30.toInt())
        graphics.horizontalLine(headerLeft, headerRight, headerTop, 0xFF4AB8FF.toInt())
        graphics.horizontalLine(headerLeft, headerRight, headerBottomLocal, 0xFF263A57.toInt())
        graphics.verticalLine(headerLeft, headerTop, headerBottomLocal, 0xFF2F4968.toInt())
        graphics.verticalLine(headerRight, headerTop, headerBottomLocal, 0xFF2F4968.toInt())

        val logoSize = 40
        val logoX = headerLeft + 10
        val logoY = headerTop + 10
        graphics.blit(logoTexture, logoX, logoY, logoX + logoSize, logoY + logoSize, 0f, 1f, 0f, 1f)

        val titleX = logoX + logoSize + 10
        graphics.text(font, Component.literal("AEONIS COMMAND CONSOLE"), titleX, headerTop + 14, 0xF4F8FF, false)
        graphics.text(font, Component.literal("Modern runtime control panel"), titleX, headerTop + 28, 0x9EC1EC, false)
        graphics.text(font, Component.literal("Mod Name: $modName"), headerRight - 220, headerTop + 12, 0xC6D9F7, false)
        graphics.text(font, Component.literal("Mod ID: $modId"), headerRight - 220, headerTop + 24, 0x88A7D0, false)
        graphics.text(font, Component.literal("Version: $modVersion"), headerRight - 220, headerTop + 36, 0x88A7D0, false)
    }

    private fun drawPanels(graphics: GuiGraphics) {
        val left = 8
        val rightSplit = width / 2
        val top = entityListTop - 34
        val bottom = height - 14

        graphics.fill(left, top, rightSplit - 8, bottom, 0x8A0E1B2F.toInt())
        graphics.fill(rightSplit + 4, top, width - 8, bottom, 0x8A0C1A2D.toInt())

        graphics.horizontalLine(left, rightSplit - 8, top, 0xFF2B4E73.toInt())
        graphics.horizontalLine(rightSplit + 4, width - 8, top, 0xFF2B4E73.toInt())
        graphics.horizontalLine(left, rightSplit - 8, bottom, 0xFF1E324A.toInt())
        graphics.horizontalLine(rightSplit + 4, width - 8, bottom, 0xFF1E324A.toInt())
    }

    private fun drawSelectionCard(graphics: GuiGraphics) {
        val cardX = rightPaneX
        val cardY = entityListTop + 18
        val cardW = rightPaneWidth
        val cardH = 56
        graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xAE132642.toInt())
        graphics.horizontalLine(cardX, cardX + cardW, cardY, 0xFF4BA8FF.toInt())
        graphics.horizontalLine(cardX, cardX + cardW, cardY + cardH, 0xFF253E5B.toInt())

        val selected = selectedEntityType
        if (selected == null) {
            graphics.text(font, Component.literal("No entity selected"), cardX + 10, cardY + 10, 0xC2D2EC, false)
            graphics.text(font, Component.literal("Pick one from the registry list"), cardX + 10, cardY + 24, 0x7E93B6, false)
            return
        }

        val iconStack = ItemStack(SpawnEggItem.byId(selected).map { it.value() }.orElse(Items.BARRIER))
        graphics.item(iconStack, cardX + 8, cardY + 8)
        graphics.itemDecorations(font, iconStack, cardX + 8, cardY + 8)
        graphics.text(font, Component.translatable(selected.descriptionId), cardX + 30, cardY + 10, 0xFFFFFF, false)
        val idText = BuiltInRegistries.ENTITY_TYPE.getResourceKey(selected)
            .map { it.identifier().toString() }
            .orElse("unknown")
        graphics.text(font, Component.literal(idText), cardX + 30, cardY + 24, 0x88A8D4, false)
        graphics.text(font, Component.literal("One-click transform ready"), cardX + 30, cardY + 38, 0x78C2FF, false)
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        // Gui.setScreen accepts a nullable screen; Minecraft.setScreenAndShow does not.
        minecraft.gui.setScreen(parentScreen)
    }

    private data class QuickCommand(val command: String, val label: Component)

    private inner class AeonisEntityListWidget(
        minecraft: Minecraft,
        width: Int,
        height: Int,
        top: Int,
        entryHeight: Int
    ) : ObjectSelectionList<EntityEntry>(minecraft, width, height, top, entryHeight) {

        fun refreshEntries(entries: Collection<EntityEntry>) {
            super.replaceEntries(entries)
        }

        override fun getRowWidth(): Int = this.width
    }

    private inner class EntityEntry(val entityType: EntityType<*>) : ObjectSelectionList.Entry<EntityEntry>() {
        val displayName: Component = Component.translatable(entityType.descriptionId)
        private val registryKey: ResourceKey<EntityType<*>>? = BuiltInRegistries.ENTITY_TYPE.getResourceKey(entityType).orElse(null)
        private val idText: String = registryKey?.identifier()?.toString() ?: ""
        private val iconStack: ItemStack = ItemStack(SpawnEggItem.byId(entityType).map { it.value() }.orElse(Items.BARRIER))

        fun matches(filter: String): Boolean {
            val display = displayName.string.lowercase(Locale.ROOT)
            return display.contains(filter) || idText.contains(filter)
        }

        override fun extractContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, hovered: Boolean, a: Float) {
            val iconX = this.getContentX()
            val iconY = this.getContentY() + (this.getContentHeight() - 16) / 2
            if (this@AeonisControlScreen.minecraft.player != null) {
                graphics.item(iconStack, iconX, iconY)
                graphics.itemDecorations(font, iconStack, iconX, iconY)
            }

            val textX = iconX + 24
            graphics.text(this@AeonisControlScreen.font, displayName, textX, this.getContentY(), 0xFFFFFF)
            if (idText.isNotEmpty()) {
                graphics.text(this@AeonisControlScreen.font, Component.literal(idText), textX, this.getContentY() + 11, 0x777777)
            }
        }

        override fun getNarration(): Component = displayName
    }
}
