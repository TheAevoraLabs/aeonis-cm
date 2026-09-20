package com.qc.aeonis.neoforge.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Modern, crash-free settings and control screen for NeoForge client.
 */
public class AeonisSettingsScreen extends Screen {
    private static final Identifier AEONIS_ICON = Identifier.fromNamespaceAndPath("aeonis", "textures/gui/aeonis_icon.png");
    private final Screen parent;

    public AeonisSettingsScreen(Screen parent) {
        super(Component.translatable("options.aeonis.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int dialogW = Math.min(480, this.width - 32);
        int dialogH = Math.min(280, this.height - 32);
        int left = (this.width - dialogW) / 2;
        int top = (this.height - dialogH) / 2;

        int btnW = 200;
        int btnX = this.width / 2 - btnW / 2;
        int curY = top + 50;

        // Quick Actions
        this.addRenderableWidget(Button.builder(Component.literal("Return to Player Body"), button -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.connection.sendCommand("untransform");
            }
            this.onClose();
        }).bounds(btnX, curY, btnW, 20).tooltip(Tooltip.create(Component.literal("Release possessed mob and return to your body"))).build());
        curY += 26;

        this.addRenderableWidget(Button.builder(Component.literal("Inspect Active Form"), button -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.connection.sendCommand("transform info");
            }
            this.onClose();
        }).bounds(btnX, curY, btnW, 20).tooltip(Tooltip.create(Component.literal("Display abilities and locomotion profile in chat"))).build());
        curY += 26;

        this.addRenderableWidget(Button.builder(Component.literal("Possess Random Mob"), button -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.connection.sendCommand("transform random");
            }
            this.onClose();
        }).bounds(btnX, curY, btnW, 20).tooltip(Tooltip.create(Component.literal("Transform into a randomly chosen mob form"))).build());
        curY += 26;

        this.addRenderableWidget(Button.builder(Component.literal("Run Diagnostic Audit"), button -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.connection.sendCommand("aeonis diagnostics");
            }
            this.onClose();
        }).bounds(btnX, curY, btnW, 20).tooltip(Tooltip.create(Component.literal("Audit mob registrations, movement systems and AI status"))).build());

        // Footer Done Button
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
            .bounds(this.width / 2 - 50, top + dialogH - 28, 100, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft != null && this.minecraft.level != null) {
            this.extractTransparentBackground(graphics);
        } else {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int dialogW = Math.min(480, this.width - 32);
        int dialogH = Math.min(280, this.height - 32);
        int left = (this.width - dialogW) / 2;
        int top = (this.height - dialogH) / 2;

        // Container background
        graphics.fill(left, top, left + dialogW, top + dialogH, 0xEE14171F);

        // Vanilla style bevel borders
        graphics.fill(left, top, left + dialogW, top + 1, 0xFF4A5260);
        graphics.fill(left, top, left + 1, top + dialogH, 0xFF4A5260);
        graphics.fill(left, top + dialogH - 1, left + dialogW, top + dialogH, 0xFF11141A);
        graphics.fill(left + dialogW - 1, top, left + dialogW, top + dialogH, 0xFF11141A);

        // Header bar
        graphics.fill(left + 1, top + 1, left + dialogW - 1, top + 34, 0xD01E222D);
        graphics.fill(left + 1, top + 34, left + dialogW - 1, top + 35, 0xFF2A2F3D);

        // Title and icon
        graphics.text(this.font, "AEONIS CONTROL & SETTINGS", left + 34, top + 9, 0xFFFFFF);
        graphics.text(this.font, "v5.0.0 (NeoForge)", left + 34, top + 20, 0x8FA0BE);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
