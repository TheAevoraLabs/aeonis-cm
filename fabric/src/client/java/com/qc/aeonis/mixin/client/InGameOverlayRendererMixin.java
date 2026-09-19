package com.qc.aeonis.mixin.client;

import com.qc.aeonis.network.AeonisClientNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents the "in wall" overlay when controlling small mobs.
 * Without this, the screen shows block textures when the player's hitbox
 * clips into blocks while the mob is smaller.
 *
 * <p>In 26.3 this logic moved out of {@code ScreenEffectRenderer} into
 * {@code LevelExtractor#getViewBlockingState(LocalPlayer, Frustum)}.
 */
@Mixin(LevelExtractor.class)
public class InGameOverlayRendererMixin {

    @Inject(method = "getViewBlockingState", at = @At("HEAD"), cancellable = true)
    private static void aeonis$preventWallOverlay(LocalPlayer player, Frustum frustum, CallbackInfoReturnable<BlockState> cir) {
        Minecraft client = Minecraft.getInstance();

        if (client.player != null && player == client.player &&
            AeonisClientNetworking.INSTANCE.isControlling() &&
            AeonisClientNetworking.INSTANCE.getControlledMobId() > 0 &&
            client.level != null) {

            int mobId = AeonisClientNetworking.INSTANCE.getControlledMobId();
            Entity entity = client.level.getEntity(mobId);

            if (entity instanceof Mob mob) {
                // If the mob is smaller than 1 block, prevent wall overlay
                if (mob.getBbHeight() <= 1.0f) {
                    cir.setReturnValue(null);
                }
            }
        }
    }
}
