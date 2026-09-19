package com.qc.aeonis.mixin;

import com.qc.aeonis.transform.ParrotVariantBridge;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.parrot.Parrot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Gives the transform variant system access to {@code Parrot.DATA_VARIANT_ID}.
 *
 * {@code Parrot#setVariant} is private; this mirrors vanilla's own setter so parrot colours work
 * as a transform variant instead of being skipped.
 */
@Mixin(Parrot.class)
public abstract class ParrotVariantMixin implements ParrotVariantBridge {

    @Shadow
    @Final
    private static EntityDataAccessor<Integer> DATA_VARIANT_ID;

    @Override
    public void aeonisApplyVariantId(int id) {
        Parrot self = (Parrot) (Object) this;
        self.getEntityData().set(DATA_VARIANT_ID, id);
    }
}
