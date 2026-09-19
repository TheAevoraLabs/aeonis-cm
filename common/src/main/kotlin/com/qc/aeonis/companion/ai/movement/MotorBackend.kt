package com.qc.aeonis.companion.ai.movement

import net.minecraft.world.phys.Vec3

/**
 * Common abstraction for neural and kinematic movement policy backends.
 */
interface MotorBackend : AutoCloseable {
    fun predict(
        relativeTarget: Vec3,
        velocity: Vec3,
        onGround: Boolean,
        inWater: Boolean,
        horizontalCollision: Boolean,
        localVoxelGrid125: FloatArray
    ): OnnxMovementController.MotorAction?
}
