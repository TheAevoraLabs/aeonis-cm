package com.qc.aeonis.transform

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Mob

/**
 * Small, shared predicates for mobs that do not share a useful vanilla base class.
 *
 * These were duplicated in several places (the command object, the variant chain and a Java
 * mixin) with subtly different implementations, which is how `MagmaCube` ended up being treated
 * as a Slime in one place and not another.
 */
object MobKinds {

    /** True for the sulfur cube mob added in the 26.2 line, matched by registry path. */
    fun isSulfurCube(mob: Mob): Boolean = try {
        BuiltInRegistries.ENTITY_TYPE.getKey(mob.type).path == "sulfur_cube"
    } catch (_: Exception) {
        false
    }

    /**
     * Cube-family mobs keep their own vanilla movement controllers, so controlled-mob handling
     * must not suppress their AI the way it does for ordinary mobs.
     */
    fun isCubeStyle(mob: Mob): Boolean =
        mob is net.minecraft.world.entity.monster.cubemob.AbstractCubeMob || isSulfurCube(mob)
}
