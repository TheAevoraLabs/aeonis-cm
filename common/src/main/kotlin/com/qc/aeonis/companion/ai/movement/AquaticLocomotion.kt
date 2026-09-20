package com.qc.aeonis.companion.ai.movement

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Active water propulsion for the companion.
 *
 * ### Why the bot used to drown
 * Vanilla mob movement is *terrain* movement: `MoveControl.setWantedPosition` picks a
 * direction and gravity plus water drag decide the rest. A mob that never presses jump
 * simply sinks, because nothing in that pipeline knows water is a medium to swim *through*
 * rather than a hazard to fall into. Path navigation was also refusing water outright
 * until `PathNavigation.setCanFloat(true)` was set on the entity, so before this change
 * the bot had no way across a river at any level of the stack.
 *
 * ### The model
 * Two independent impulses, both applied to the entity's own velocity:
 *
 * - **Buoyancy** counters gravity and water drag. It is strongest while the bot's eyes are
 *   below the surface (it is genuinely submerged and needs to climb) and halves at the
 *   surface, where it only has to hold station. Upward velocity is clamped so a long
 *   submerged run cannot turn into a launch out of the water.
 * - **Stroke** is a horizontal acceleration toward the current waypoint, capped at a
 *   swim-speed ceiling. The cap is what keeps this from fighting the path planner: the
 *   stroke can only ever nudge an already-chosen direction, never yank the bot off it.
 *
 * All arithmetic is pure functions of the entity's current state, with no per-instance
 * mutable state, so it cannot get stuck in a mode across entity reuse.
 */
object AquaticLocomotion {

    /** Vertical impulse applied while the companion's eyes are underwater. */
    const val BUOYANT_IMPULSE = 0.08

    /** Fraction of [BUOYANT_IMPULSE] used when floating at the surface. */
    const val SURFACE_BUOYANCY_FACTOR = 0.5

    /** Hard ceiling on upward velocity from buoyancy, to prevent breaching. */
    const val MAX_ASCENT_VELOCITY = 0.16

    /** Horizontal acceleration added per tick by the swim stroke. */
    const val STROKE_ACCELERATION = 0.045

    /** Horizontal speed ceiling while walking a swim, in blocks/tick. */
    const val MAX_SWIM_SPEED = 0.30

    /** Horizontal speed ceiling while sprinting a swim, in blocks/tick. */
    const val MAX_SPRINT_SWIM_SPEED = 0.42

    /** Buoyancy climbs at full strength; below this many ticks of air left we push harder. */
    const val LOW_AIR_TICKS = 60

    /** Extra buoyancy multiplier applied when air is running out. */
    const val LOW_AIR_BUOYANCY_MULTIPLIER = 1.5

    /**
     * Vertical impulse for this tick.
     *
     * Returns 0.0 when the entity is not in water, so callers can apply the result
     * unconditionally without checking the medium first.
     */
    fun buoyantImpulse(bot: LivingEntity): Double {
        if (!bot.isInWater) return 0.0
        val base = if (bot.isUnderWater) BUOYANT_IMPULSE else BUOYANT_IMPULSE * SURFACE_BUOYANCY_FACTOR
        val urgency = if (bot.airSupply <= LOW_AIR_TICKS) LOW_AIR_BUOYANCY_MULTIPLIER else 1.0
        return base * urgency
    }

    /**
     * Applies buoyancy and a forward stroke for this tick.
     *
     * @param horizontalDirection unit-ish horizontal direction of travel; a zero vector
     *   keeps buoyancy but skips the stroke, which is what treading water looks like.
     * @param sprint true to raise the horizontal speed ceiling.
     * @return true when the entity was in water and had impulses applied.
     */
    fun stroke(bot: LivingEntity, horizontalDirection: Vec3, sprint: Boolean): Boolean {
        if (!bot.isInWater) {
            if (bot.isSwimming) bot.isSwimming = false
            return false
        }

        // Vanilla swimming pose: drives the animation and the reduced eye height.
        bot.isSwimming = true

        val velocity = bot.deltaMovement
        val ascent = buoyantImpulse(bot)
        val newY = (velocity.y + ascent).coerceAtMost(MAX_ASCENT_VELOCITY)

        var vx = velocity.x
        var vz = velocity.z

        val horizontalLength = sqrt(horizontalDirection.x * horizontalDirection.x + horizontalDirection.z * horizontalDirection.z)
        if (horizontalLength > 1.0e-4) {
            val nx = horizontalDirection.x / horizontalLength
            val nz = horizontalDirection.z / horizontalLength
            vx += nx * STROKE_ACCELERATION
            vz += nz * STROKE_ACCELERATION

            val ceiling = if (sprint) MAX_SPRINT_SWIM_SPEED else MAX_SWIM_SPEED
            val speed = sqrt(vx * vx + vz * vz)
            if (speed > ceiling) {
                val scale = ceiling / speed
                vx *= scale
                vz *= scale
            }
        }

        bot.setDeltaMovement(vx, newY, vz)
        return true
    }

    /**
     * True when the companion is in water but not making meaningful headway toward a
     * target, which callers use to decide whether to abandon a water route.
     */
    fun isStalled(bot: LivingEntity): Boolean =
        bot.isInWater && bot.deltaMovement.horizontalDistanceSqr() < 0.0025

    /** True when the companion should treat water as a route rather than an obstacle. */
    fun isTraversable(bot: LivingEntity): Boolean = bot.isInWater
}
