package com.qc.aeonis.neoforge.mixin;

import com.qc.aeonis.AeonisPossession;
import com.qc.aeonis.network.AeonisNetwork;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerEntityAttackMixin {
    @Inject(method = "attack(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void preventEntityAttack(Entity target, CallbackInfo ci) {
        Player player = (Player)(Object)this;
        if (AeonisPossession.INSTANCE.isActivelyPossessing(player.getUUID()) && AeonisNetwork.INSTANCE.getControlledEntityId(player.getUUID()) == null) {
            ci.cancel();
        }
    }
}