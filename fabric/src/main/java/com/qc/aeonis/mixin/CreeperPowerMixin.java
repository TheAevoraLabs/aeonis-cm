package com.qc.aeonis.mixin;

import com.qc.aeonis.transform.CreeperPowerBridge;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Gives the transform variant system access to {@code Creeper.DATA_IS_POWERED}.
 *
 * Creeper exposes {@code isPowered()} but no setter, so the "charged" variant previously could
 * not be implemented and was faked with a permanent GLOWING effect. Implementing the common
 * {@link CreeperPowerBridge} keeps the variant declarative and identical on every loader that
 * supplies this mixin; loaders without it simply report the variant as unavailable.
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
