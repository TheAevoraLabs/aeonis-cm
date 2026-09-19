package com.qc.aeonis.neoforge.mixin;

import com.qc.aeonis.transform.CreeperPowerBridge;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * NeoForge counterpart of the Fabric {@code CreeperPowerMixin}.
 *
 * The variant system reaches mob state through the loader-agnostic bridges in
 * {@code com.qc.aeonis.transform}, so every bridge must exist on every loader or the variants it
 * backs silently report as unavailable. This was the reason {@code powered} creepers worked on
 * Fabric and not here.
 */
@Mixin(Creeper.class)
public abstract class CreeperPowerMixin implements CreeperPowerBridge {

    @Shadow
    @Final
    private static EntityDataAccessor<Boolean> DATA_IS_POWERED;

    @Override
    public void aeonisApplyPowered(boolean powered) {
        Creeper self = (Creeper) (Object) this;
        self.getEntityData().set(DATA_IS_POWERED, powered);
    }
}
