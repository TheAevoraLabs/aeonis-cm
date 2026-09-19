package com.qc.aeonis.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.qc.aeonis.network.AeonisNetwork;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

@Mixin(Mob.class)
public class ControlledMobReplicationMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void aeonis$markControlledMobForSmoothReplication(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (!(self.level() instanceof ServerLevel)) {
            return;
        }
        if (!AeonisNetwork.INSTANCE.isEntityControlled(self.getId())) {
            return;
        }

        // Keep network updates frequent so other players see smooth movement.
        self.syncVelocity = true;
    }
}
