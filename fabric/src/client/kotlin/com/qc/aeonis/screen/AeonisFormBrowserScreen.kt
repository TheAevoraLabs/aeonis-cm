package com.qc.aeonis.screen

import com.qc.aeonis.transform.MobCatalogue
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType

/**
 * A browsable catalogue of every form the mod can give you.
 *
 * The point of this screen is that nothing about a form has to be discovered by trial and error.
 * For each entry it lists the locomotion the server will actually drive the body with and the
 * three ability slots with their keys, both read from the same registries the gameplay code uses.
 * If an ability is listed here, pressing that key in that form does that thing; there is no
 * separate documentation copy that can drift.
 *
 * Forms are grouped by how they move, because that is the property a player is really choosing
 * between: whether they want something that walks, flies, swims, climbs, hovers or hops.
 */
@Environment(EnvType.CLIENT)
class AeonisFormBrowserScreen(private val parent: Screen?) :
    Screen(Component.literal("Aeonis - Form catalogue")) {

    /** One rendered row: either a group heading or a selectable form. */
    private sealed interface Row {
        data class Heading(val title: String) : Row
        data class Form(val type: EntityType<*>, val token: String, val name: String) : Row
    }

    private val rows: List<Row> = buildList {
        for (group in MobCatalogue.grouped()) {
            add(Row.Heading("${group.title}  (${group.types.size})"))
            for (type in group.types) {
                add(Row.Form(type, MobCatalogue.token(type), type.description.string))
            }
        }
    }

    private var page = 0
    private var selected: EntityType<*>? = rows.filterIsInstance<Row.Form>().firstOrNull()?.type

    private val listX = 16
    private val listWidth = 168
    private val detailX = listX + listWidth + 12
    private val rowHeight = 18
    private val topY = 46

    /** How many rows fit between the header and the pager. */
    private val rowsPerPage = ((height - topY - 56) / rowHeight).coerceAtLeast(6)
    private val pageCount = ((rows.size + rowsPerPage - 1) / rowsPerPage).coerceAtLeast(1)

    override fun init() {
        super.init()
        rebuildPage()
    }

    private fun rebuildPage() {
        clearWidgets()
        page = page.coerceIn(0, pageCount - 1)

        val visible = rows.drop(page * rowsPerPage).take(rowsPerPage)
        var y = topY
        for (row in visible) {
            when (row) {
                is Row.Heading -> y += rowHeight
                is Row.Form -> {
                    val button = Button.builder(Component.literal(row.name)) { selected = row.type }
                        .bounds(listX, y, listWidth, rowHeight - 2).build()
                    // The selected row is disabled rather than marked with a glyph: a button that
                    // cannot be pressed reads as "you are already here" without extra text.
                    button.active = row.type != selected
                    addRenderableWidget(button)
                    y += rowHeight
                }
            }
        }

        val pagerY = height - 52
        addRenderableWidget(
            Button.builder(Component.literal("<")) { page--; rebuildPage() }
                .bounds(listX, pagerY, 30, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal(">")) { page++; rebuildPage() }
                .bounds(listX + 34, pagerY, 30, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Random form")) { runCommand("transform random") }
                .bounds(listX + 68, pagerY, listWidth - 68, 20).build()
        )

        val detailWidth = width - detailX - 16
        val detailBottom = height - 52
        addRenderableWidget(
            Button.builder(Component.literal(becomeLabel())) { selected?.let { become(it) } }
                .bounds(detailX, detailBottom, detailWidth / 2 - 3, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Back")) { onClose() }
                .bounds(detailX + detailWidth / 2 + 3, detailBottom, detailWidth / 2 - 3, 20).build()
        )
    }

    private fun becomeLabel(): String =
        selected?.let { "Become ${it.description.string}" } ?: "Select a form"

    private fun become(type: EntityType<*>) {
        runCommand("transform ${MobCatalogue.token(type)}")
    }

    private fun runCommand(command: String) {
        minecraft?.player?.connection?.sendCommand(command)
    }

    override fun extractRenderState(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        val title = Component.literal("Form catalogue").string
        graphics.text(font, title, (width - font.width(title)) / 2, 16, 0xFFFFFF)
        val subtitle = "Every form, how it moves, and what its three buttons do"
        graphics.text(font, subtitle, (width - font.width(subtitle)) / 2, 28, 0x9AA8C2)

        // Group headings are drawn rather than placed as buttons so they cannot be clicked.
        var y = topY
        val visible = rows.drop(page * rowsPerPage).take(rowsPerPage)
        for (row in visible) {
            if (row is Row.Heading) {
                graphics.text(font, row.title, listX + 2, y + 4, 0xE2C86A)
                y += rowHeight
            } else {
                y += rowHeight
            }
        }

        val pageLabel = "Page ${page + 1} / $pageCount"
        graphics.text(font, pageLabel, listX, height - 64, 0x7F93B5)

        drawDetails(graphics)
    }

    private fun drawDetails(graphics: GuiGraphics) {
        val type = selected ?: return
        val summary = MobCatalogue.summary(type)
        val maxWidth = width - detailX - 20

        var y = topY + 2
        graphics.text(font, summary.formName, detailX, y, 0xFFFFFF)
        y += 14
        graphics.text(font, "Command:  /transform ${MobCatalogue.token(type)}", detailX, y, 0x8FA0BE)
        y += 16

        graphics.text(font, "Movement", detailX, y, 0xE2C86A)
        y += 12
        for (line in wrap(summary.movement, maxWidth)) {
            graphics.text(font, line, detailX, y, 0xC9D4E6)
            y += 11
        }
        y += 8

        graphics.text(font, "Abilities", detailX, y, 0xE2C86A)
        y += 12
        if (summary.abilities.isEmpty()) {
            graphics.text(font, "This form has no special abilities.", detailX, y, 0x8FA0BE)
            return
        }
        for (ability in summary.abilities) {
            for ((index, line) in wrap(ability, maxWidth).withIndex()) {
                graphics.text(font, line, detailX + if (index == 0) 0 else 10, y, if (index == 0) 0xC9D4E6 else 0x9AA8C2)
                y += 11
            }
        }

        val note = "Movement and abilities are read from the same registries the game runs, so this page cannot list a button that does nothing."
        y += 8
        for (line in wrap(note, maxWidth)) {
            graphics.text(font, line, detailX, y, 0x6E7E9C)
            y += 11
        }
    }

    private fun wrap(text: String, maxWidth: Int): List<String> {
        val words = text.split(' ')
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (font.width(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) out += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    override fun onClose() {
        minecraft?.gui?.setScreen(parent ?: AeonisSettingsScreen(null))
    }
}
