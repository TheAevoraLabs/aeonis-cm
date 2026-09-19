package com.qc.aeonis.neoforge.mixin;

import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Bee.class)
public interface BeeEntityAccessor {
    @Invoker("setHasStung")
    void invokeSetHasStung(boolean stung);
}
