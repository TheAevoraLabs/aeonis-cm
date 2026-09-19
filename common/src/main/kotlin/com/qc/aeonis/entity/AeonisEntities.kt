package com.qc.aeonis.entity

import com.qc.aeonis.registration.AeonisRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import java.util.function.Supplier

import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.LivingEntity

object AeonisEntities {
    private fun <T : net.minecraft.world.entity.Entity> register(name: String, builder: EntityType.Builder<T>): Supplier<EntityType<T>> {
        val key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("aeonis", name))
        return AeonisRegistries.registerEntityType(name) {
            builder.build(key)
        }
    }

    val COPPER_STALKER: Supplier<EntityType<CopperStalkerEntity>> = register("copper_stalker", 
        EntityType.Builder.of(::CopperStalkerEntity, MobCategory.MONSTER)
            .sized(0.6f, 1.95f)
            .clientTrackingRange(48)
    )
    
    val HEROBRINE: Supplier<EntityType<HerobrineEntity>> = register("herobrine",
        EntityType.Builder.of(::HerobrineEntity, MobCategory.MONSTER)
            .sized(0.6f, 1.95f)
            .clientTrackingRange(100)
    )
    
    val HUNTER: Supplier<EntityType<HunterEntity>> = register("hunter",
        EntityType.Builder.of(::HunterEntity, MobCategory.MONSTER)
            .sized(0.6f, 1.95f)
            .clientTrackingRange(200)
    )

    val COMPANION_BOT: Supplier<EntityType<CompanionBotEntity>> = register("companion_bot",
        EntityType.Builder.of(::CompanionBotEntity, MobCategory.MONSTER)
            .sized(0.6f, 1.95f)
            .clientTrackingRange(128)
    )
    
    val BODY: Supplier<EntityType<BodyEntity>> = register("body",
        EntityType.Builder.of(::BodyEntity, MobCategory.MISC)
            .sized(0.6f, 1.8f)
            .clientTrackingRange(64)
    )

    fun register() {}

    fun getAttributes(): Map<out EntityType<out LivingEntity>, AttributeSupplier.Builder> {
        return mapOf(
            COPPER_STALKER.get() to CopperStalkerEntity.createAttributes(),
            HEROBRINE.get() to HerobrineEntity.createAttributes(),
            HUNTER.get() to HunterEntity.createAttributes(),
            COMPANION_BOT.get() to CompanionBotEntity.createAttributes(),
            BODY.get() to BodyEntity.createBodyAttributes()
        )
    }
}
