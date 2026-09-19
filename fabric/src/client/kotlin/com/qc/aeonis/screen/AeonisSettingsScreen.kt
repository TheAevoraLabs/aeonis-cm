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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The Aeonis hub: documentation, client preferences and one-click actions.
 *
 * This replaces the old placeholder config screen, which had two text boxes wired to a file
 * nothing read, and the old control screen, which offered a wall of raw commands with no
 * explanation. Everything here either opens the manual, opens the form catalogue, flips a client
 * display preference, or runs a named action, so every control states what it does.
 *
 * Client preferences are saved to config/aeonis-client.json. World-affecting settings stay on the
 * server and are explained in the manual rather than being exposed here as buttons that would do
 * nothing on a server the player does not administer.
 */
@Environment(EnvType.CLIENT)
class AeonisSettingsScreen(private val parent: Screen?) : Screen(Component.translatable("options.aeonis.title")) {

    private val toggles = mutableListOf<Pair<Button, () -> String>>()
    private lateinit var camouflageButton: Button

    private val modVersion: String = FabricLoader.getInstance()
        .getModContainer("aeonis-manager")
        .map { it.metadata.version.friendlyString }
        .orElse("5.0.0")

    override fun init() {
        super.init()
        AeonisClientSettings.load()

        val columnWidth = 150
        val gap = 6
        val leftX = width / 2 - columnWidth - gap / 2
        val rightX = width / 2 + gap / 2
        val fullWidth = columnWidth * 2 + gap
        var y = 44

        // ------------------------------------------------------- documentation and tooling
        addRenderableWidget(
            Button.builder(Component.literal("Read the manual")) {
                minecraft?.gui?.setScreen(AeonisManualScreen(this, 0))
            }.bounds(leftX, y, columnWidth, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Browse every form")) {
                minecraft?.gui?.setScreen(AeonisFormBrowserScreen(this))
            }.bounds(rightX, y, columnWidth, 20).build()
        )
        y += 26

        AeonisManual.sections.forEachIndexed { index, section ->
            val x = if (index % 2 == 0) leftX else rightX
            val row = y + (index / 2) * 22
            addRenderableWidget(
                Button.builder(Component.literal(trimToWidth(section.title, columnWidth - 12))) {
                    minecraft?.gui?.setScreen(AeonisManualScreen(this, index))
                }.bounds(x, row, columnWidth, 20).build()
            )
        }
        y += ((AeonisManual.sections.size + 1) / 2) * 22 + 12

        // ------------------------------------------------------------- client preferences
        var leftY = y
        leftY = addToggle(
            leftX, leftY, columnWidth, "Ability HUD",
            read = { AeonisClientSettings.showAbilityHud },
            write = { AeonisClientSettings.showAbilityHud = it }
        )
        leftY = addToggle(
            leftX, leftY, columnWidth, "Cooldowns",
            read = { AeonisClientSettings.showCooldownSeconds },
            write = { AeonisClientSettings.showCooldownSeconds = it }
        )
        leftY = addToggle(
            leftX, leftY, columnWidth, "Form details",
            read = { AeonisClientSettings.showFormDetails },
            write = { AeonisClientSettings.showFormDetails = it }
        )

        var rightY = y
        rightY = addToggle(
            rightX, rightY, columnWidth, "Form hints",
            read = { AeonisClientSettings.showFormHints },
            write = { AeonisClientSettings.showFormHints = it }
        )
        rightY = addToggle(
            rightX, rightY, columnWidth, "Large form camera",
            read = { AeonisClientSettings.largeFormCamera },
            write = { AeonisClientSettings.largeFormCamera = it }
        )

        // The camouflage level cycles through three named levels instead of being a slider,
        // because the interesting question is which of the three behaviours you want, not a
        // number that means nothing to the player.
        camouflageButton = Button.builder(Component.literal(camouflageLabel())) {
            val next = CamouflageLevel.entries[(AeonisClientSettings.camouflageLevel.ordinal + 1) % CamouflageLevel.entries.size]
            AeonisClientSettings.camouflageLevel = next
            AeonisClientSettings.save()
        }.bounds(rightX, rightY, columnWidth, 20).build()
        addRenderableWidget(camouflageButton)
        rightY += 22

        // ------------------------------------------------------------- one-click actions
        val actionY = maxOf(leftY, rightY) + 10
        addRenderableWidget(
            Button.builder(Component.literal("Become a random mob")) { runCommand("transform random") }
                .bounds(leftX, actionY, columnWidth, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("List my abilities")) { runCommand("transform abilities") }
                .bounds(rightX, actionY, columnWidth, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Inspect my form")) { runCommand("transform info") }
                .bounds(leftX, actionY + 22, columnWidth, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Return to my body")) { runCommand("untransform") }
                .bounds(rightX, actionY + 22, columnWidth, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Run diagnostics")) { runCommand("aeonis diagnostics") }
                .bounds(leftX, actionY + 44, fullWidth, 20).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 50, height - 30, 100, 20).build()
        )
    }

    private fun rowHeight() = 22

    /** Adds a labelled on/off button and keeps its message in sync with the stored value. */
    private fun addToggle(
        x: Int,
        y: Int,
        width: Int,
        label: String,
        read: () -> Boolean,
        write: (Boolean) -> Unit
    ): Int {
        val button = Button.builder(Component.literal(labelFor(label, read()))) {
            write(!read())
            AeonisClientSettings.save()
        }.bounds(x, y, width, 20).build()
        addRenderableWidget(button)
        toggles += button to { labelFor(label, read()) }
        return y + rowHeight()
    }

    private fun labelFor(label: String, enabled: Boolean) = "$label: ${if (enabled) "ON" else "OFF"}"

    private fun camouflageLabel() = "Camouflage: ${AeonisClientSettings.camouflageLevel.label}"

    private fun trimToWidth(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.isNotEmpty() && font.width("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed..."
    }

    private fun runCommand(command: String) {
        minecraft?.player?.connection?.sendCommand(command)
    }

    override fun extractRenderState(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractBackground(graphics, mouseX, mouseY, partialTick)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        // Toggle labels are redrawn from the stored value so the button text can never disagree
        // with what the client will actually do.
        toggles.forEach { (button, label) -> button.message = Component.literal(label()) }
        camouflageButton.message = Component.literal(camouflageLabel())

        val title = Component.translatable("options.aeonis.title").string
        graphics.text(font, title, (width - font.width(title)) / 2, 14, 0xFFFFFF)
        val subtitle = "Aeonis $modVersion  -  manual, form catalogue and settings"
        graphics.text(font, subtitle, (width - font.width(subtitle)) / 2, 26, 0x9AA8C2)

        val headerY = yHeaderForPreferences()
        graphics.text(font, "Client preferences", width / 2 - 150 - 3, headerY - 12, 0xE2C86A)

        // The camouflage level is the one setting whose effect is invisible from the menu, so its
        // description is spelled out right below the button that changes it.
        val description = AeonisClientSettings.camouflageLevel.description
        graphics.text(
            font,
            trimToWidth(description, width - 40),
            width / 2 + 3,
            headerY + 68,
            0x8FA0BE
        )

        drawCurrentForm(graphics)

        val fileLine = "Saved to ${AeonisClientSettings.filePath()}"
        graphics.text(font, trimToWidth(fileLine, width - 40), 20, height - 42, 0x6E7E9C)
    }

    /**
     * A live readout of the current form.
     *
     * Reading it from the client's own view of the world (rather than from a cached value pushed
     * by the server) means it is always what the player is actually looking at, and it costs
     * nothing when the player is not transformed.
     */
    private fun drawCurrentForm(graphics: GuiGraphics) {
        val y = height - 78
        if (!AeonisClientNetworking.isControlling()) {
            val hint = "Not currently possessing anything. Press G while looking at a mob, or browse the catalogue."
            graphics.text(font, trimToWidth(hint, width - 40), 20, y, 0x7F93B5)
            return
        }
        val mob = minecraft?.level?.getEntity(AeonisClientNetworking.getControlledMobId())
            as? net.minecraft.world.entity.Mob
        if (mob == null) {
            graphics.text(font, "Possessing an entity that is not a mob.", 20, y, 0xB08968)
            return
        }
        val formName = mob.type.description.string
        val movement = MobMovementProfiles.profileFor(mob).describe()
        val abilities = MobCatalogue.summary(mob.type).abilities.size
        val line = "Currently: $formName  -  $movement  -  $abilities abilities"
        graphics.text(font, trimToWidth(line, width - 40), 20, y, 0x86D0A0)
    }

    /**
     * Y position of the preferences header, derived the same way the widgets were laid out so the
     * heading stays attached to its buttons when the window is resized.
     */
    private fun yHeaderForPreferences(): Int {
        val sectionRows = (AeonisManual.sections.size + 1) / 2
        return 44 + 26 + sectionRows * 22 + 12
    }

    override fun onClose() {
        AeonisClientSettings.save()
        minecraft?.gui?.setScreen(parent)
    }
}
