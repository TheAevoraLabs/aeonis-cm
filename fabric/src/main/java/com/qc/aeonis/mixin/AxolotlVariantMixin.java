package com.qc.aeonis.mixin;

import com.qc.aeonis.transform.AxolotlVariantBridge;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Gives the transform variant system access to {@code Axolotl.DATA_VARIANT}.
 *
 * {@code Axolotl#setVariant} is private, which is why axolotl colours were previously commented
 * out with an "NBT issues" note. Writing the synced value directly is exactly what vanilla's own
 * private setter does.
 */
@Mixin(Axolotl.class)
public abstract class AxolotlVariantMixin implements AxolotlVariantBridge {

    @Shadow
    @Final
    private static EntityDataAccessor<Integer> DATA_VARIANT;

    @Override
    public void aeonisApplyVariantId(int id) {
        Axolotl self = (Axolotl) (Object) this;
        self.getEntityData().set(DATA_VARIANT, id);
    }
}
