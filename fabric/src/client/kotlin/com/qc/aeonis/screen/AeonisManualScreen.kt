package com.qc.aeonis.screen

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Paged reader for one manual section.
 *
 * Text is wrapped at render time against the current window width rather than being hard-wrapped
 * into the content, so the same manual reads correctly at every GUI scale and resolution. Pages
 * are stepped with buttons instead of a scroll view, which keeps the screen usable with a
 * controller and with keyboard navigation alone.
 */
@Environment(EnvType.CLIENT)
class AeonisManualScreen(
    private val parent: Screen?,
    private var index: Int
) : Screen(Component.literal("Aeonis Manual")) {

    private var page = 0
    private var lines: List<String> = emptyList()
    private var pages: List<List<String>> = emptyList()

    private val margin = 24
    private val lineHeight = 11

    override fun init() {
        super.init()
        index = index.coerceIn(0, AeonisManual.sections.size - 1)
        rebuild()

        val bottom = height - 32
        addRenderableWidget(
            Button.builder(Component.literal("< Previous")) { turn(-1) }
                .bounds(width / 2 - 154, bottom, 100, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Contents")) {
                minecraft?.gui?.setScreen(AeonisSettingsScreen(parent ?: this))
            }.bounds(width / 2 - 50, bottom, 100, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Next >")) { turn(1) }
                .bounds(width / 2 + 54, bottom, 100, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 50, bottom + 24, 100, 20).build()
        )
    }

    /** Re-wraps the current section for the current window size. */
    private fun rebuild() {
        val section = AeonisManual.sections.getOrNull(index) ?: return
        val maxWidth = width - margin * 2
        val wrapped = mutableListOf<String>()

        // Blank lines between paragraphs keep long sections readable without indentation tricks.
        section.body.forEach { paragraph ->
            wrapped += wrap(paragraph, maxWidth)
            wrapped += ""
        }
        if (wrapped.isNotEmpty() && wrapped.last().isEmpty()) wrapped.removeAt(wrapped.lastIndex)

        lines = wrapped
        val perPage = ((height - 96) / lineHeight).coerceAtLeast(4)
        pages = if (lines.isEmpty()) listOf(emptyList()) else lines.chunked(perPage)
        page = page.coerceIn(0, pages.size - 1)
    }

    private fun turn(step: Int) {
        val next = page + step
        if (next in pages.indices) {
            page = next
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

    override fun extractRenderState(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractBackground(graphics, mouseX, mouseY, partialTick)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        val section = AeonisManual.sections.getOrNull(index) ?: return
        val titleText = section.title
        graphics.text(font, titleText, (width - font.width(titleText)) / 2, 16, 0xFFFFFF)

        var y = 40
        for (line in pages.getOrNull(page).orEmpty()) {
            if (line.isNotEmpty()) {
                graphics.text(font, line, margin, y, 0xC9D4E6)
            }
            y += lineHeight
        }

        val footer = "Page ${page + 1} / ${pages.size}    ${index + 1} / ${AeonisManual.sections.size}"
        graphics.text(font, footer, (width - font.width(footer)) / 2, height - 48, 0x7F93B5)
    }

    override fun onClose() {
        // Returning to the hub is more useful than dropping the player back into the world.
        minecraft?.gui?.setScreen(parent ?: AeonisSettingsScreen(null))
    }
}
