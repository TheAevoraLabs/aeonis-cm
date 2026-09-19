package com.qc.aeonis.companion.ai.movement

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import net.minecraft.world.phys.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2

/**
 * Executes high-frequency neural policy inference using native ONNX Runtime binaries.
 * Isolated in a dedicated class so the main controller does not fail class verification
 * when ONNX native libraries are absent.
 */
class OrtMotorBackend(modelBytes: ByteArray) : MotorBackend {

    private val env: OrtEnvironment
    private val session: OrtSession

    init {
        val environment = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        this.env = environment
        this.session = environment.createSession(modelBytes, sessionOptions)
    }

    override fun predict(
        relativeTarget: Vec3,
        velocity: Vec3,
        onGround: Boolean,
        inWater: Boolean,
        horizontalCollision: Boolean,
        localVoxelGrid125: FloatArray
    ): OnnxMovementController.MotorAction? {
        val totalFloats = 6 + 6 + 125
        val byteBuffer = ByteBuffer.allocateDirect(totalFloats * 4).order(ByteOrder.nativeOrder())
        val buffer = byteBuffer.asFloatBuffer()

        // 1. Target features (6 floats)
        buffer.put(relativeTarget.x.toFloat())
        buffer.put(relativeTarget.y.toFloat())
        buffer.put(relativeTarget.z.toFloat())
        val dist = relativeTarget.length().toFloat()
        buffer.put(dist)
        val yawDelta = (atan2(relativeTarget.z, relativeTarget.x) * (180.0 / Math.PI)).toFloat()
        buffer.put(yawDelta)
        buffer.put(0.0f) // pitch delta

        // 2. Kinematic state features (6 floats)
        buffer.put(velocity.x.toFloat())
        buffer.put(velocity.y.toFloat())
        buffer.put(velocity.z.toFloat())
        buffer.put(if (onGround) 1.0f else 0.0f)
        buffer.put(if (inWater) 1.0f else 0.0f)
        buffer.put(if (horizontalCollision) 1.0f else 0.0f)

        // 3. 125-cell voxel environment
        buffer.put(localVoxelGrid125)
        buffer.flip()

        var inputTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            inputTensor = OnnxTensor.createTensor(env, buffer, longArrayOf(1, totalFloats.toLong()))
            result = session.run(mapOf("state_input" to inputTensor))

            val outputTensor = result.get(0) as? OnnxTensor
            if (outputTensor != null) {
                @Suppress("UNCHECKED_CAST")
                val output = (outputTensor.value as Array<FloatArray>)[0]
                val forward = output[0].coerceIn(-1.0f, 1.0f)
                val strafe = output[1].coerceIn(-1.0f, 1.0f)
                val jump = output[2] > 0.5f
                val sprint = output[3] > 0.5f
                val sneak = output.getOrNull(4)?.let { it > 0.5f } ?: false

                return OnnxMovementController.MotorAction(forward, strafe, jump, sprint, sneak)
            }
        } finally {
            inputTensor?.close()
            result?.close()
        }

        return null
    }

    override fun close() {
        try {
            session.close()
            env.close()
        } catch (_: Exception) {
        }
    }
}
