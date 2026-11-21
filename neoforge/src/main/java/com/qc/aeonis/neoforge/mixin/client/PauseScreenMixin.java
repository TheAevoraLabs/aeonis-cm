package com.qc.aeonis.neoforge.mixin.client;

import com.qc.aeonis.screen.AeonisSettingsScreen;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Injects an Aeonis settings button into the Minecraft 26.3 Pause Menu on NeoForge.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @ModifyArg(
        method = "createPauseMenu",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"
        ),
        index = 0
    )
    private LayoutElement aeonis$addIconButtonToRow(LayoutElement child) {
        if (child instanceof LinearLayout linearLayout) {
            PauseScreen self = (PauseScreen) (Object) this;
            SpriteIconButton aeonisButton = SpriteIconButton.builder(
                Component.translatable("options.aeonis"),
                button -> {
                    if (this.minecraft != null && this.minecraft.gui != null) {
                        this.minecraft.gui.setScreen(new AeonisSettingsScreen(self));
                    }
                },
                true
            )
            .width(20)
            .sprite(Identifier.fromNamespaceAndPath("aeonis", "pause_menu/aeonis"), 15, 15)
            .withTootip()
            .build();
            aeonisButton.setTooltip(Tooltip.create(Component.translatable("options.aeonis.tooltip")));
            linearLayout.addChild(aeonisButton);
        }
        return child;
    }
}
