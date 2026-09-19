package com.qc.aeonis.neoforge.mixin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.qc.aeonis.network.AeonisNetwork;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

@Mixin(Player.class)
public abstract class PlayerAttackMixin {
    @Unique
    private static final Map<UUID, Long> aeonis$attackCooldowns = new HashMap<>();

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onAttack(Entity target, CallbackInfo ci) {
        Player player = (Player)(Object)this;
        
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        
        if (!AeonisNetwork.INSTANCE.isPlayerTransformed(player.getUUID())) {
            return;
        }
        
        Integer controlledId = AeonisNetwork.INSTANCE.getControlledEntityId(player.getUUID());
        if (controlledId == null) {
            ci.cancel();
            return;
        }
        
        if (target.getId() == controlledId) {
            ci.cancel();
            return;
        }
        
        long now = System.currentTimeMillis();
        Long lastAttack = aeonis$attackCooldowns.get(player.getUUID());
        if (lastAttack != null && now - lastAttack < 300L) {
            ci.cancel();
            return;
        }
        aeonis$attackCooldowns.put(player.getUUID(), now);
        
        ci.cancel();
        
        Entity entity = player.level().getEntity(controlledId);
        if (!(entity instanceof Mob controlledMob)) {
            return;
        }
        
        ServerLevel world = (ServerLevel) player.level();
        double mobReach = 3.0 + controlledMob.getBbWidth();
        
        Vec3 mobEyePos = controlledMob.getEyePosition();
        Vec3 lookVec = controlledMob.getViewVector(1.0f);
        
        AABB searchBox = controlledMob.getBoundingBox().inflate(mobReach);
        List<Entity> nearbyEntities = world.getEntities(controlledMob, searchBox, 
            e -> e != controlledMob && e != player && e instanceof LivingEntity && e.isAlive());
            
        for (Entity nearby : nearbyEntities) {
            if (nearby == target || nearby.getBoundingBox().inflate(0.3).contains(mobEyePos.add(lookVec.scale(controlledMob.distanceTo(nearby))))) {
                controlledMob.doHurtTarget(world, nearby);
                break;
            }
        }
    }
}
