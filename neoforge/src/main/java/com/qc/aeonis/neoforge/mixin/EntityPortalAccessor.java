package com.qc.aeonis.neoforge.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityPortalAccessor {
    @Accessor("portalCooldown")
    int getPortalCooldown();

    @Accessor("portalCooldown")
    void setPortalCooldown(int cd);
}
