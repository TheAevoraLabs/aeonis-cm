package com.qc.aeonis.mixin.client;
import com.qc.aeonis.screen.AeonisSettingsScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "Aeonis" button to the vanilla options screen.
 *
 * It used to run the `/aeonis` chat command, which required permission and dumped a wall of text
 * into chat. It now opens the client-side hub directly, which works in single player, on a
 * server without operator rights, and offline.
 */
@Environment(EnvType.CLIENT)
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    protected OptionsScreenMixin(Component component) {
        super(component);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void aeonis$addAeonisButton(CallbackInfo ci) {
        OptionsScreen self = (OptionsScreen) (Object) this;
        Button aeonisButton = Button.builder(Component.translatable("options.aeonis"), button ->
            this.minecraft.gui.setScreen(new AeonisSettingsScreen(self))
        ).bounds(this.width / 2 - 100, this.height - 54, 200, 20).build();
        this.addRenderableWidget(aeonisButton);
    }
}
