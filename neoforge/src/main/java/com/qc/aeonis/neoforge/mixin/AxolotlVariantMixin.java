package com.qc.aeonis.neoforge.mixin;

import com.qc.aeonis.transform.AxolotlVariantBridge;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * NeoForge counterpart of the Fabric {@code AxolotlVariantMixin}; see that class for why the
 * bridge exists rather than a direct call to the private vanilla setter.
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
