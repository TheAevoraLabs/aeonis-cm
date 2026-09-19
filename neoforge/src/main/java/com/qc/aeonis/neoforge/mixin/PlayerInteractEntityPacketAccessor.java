package com.qc.aeonis.neoforge.mixin;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerboundInteractPacket.class)
public interface PlayerInteractEntityPacketAccessor {
    @Accessor("entityId")
    int getEntityId();
}
