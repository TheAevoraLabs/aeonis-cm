package com.qc.aeonis.transform

import com.qc.aeonis.network.MobControlPayload
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.spider.Spider
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a mob is supposed to move.
 *
 * A possessed mob used to be driven by one shared code path that set `zza`/`xxa` and hoped
 * vanilla `travel` handled the rest. That is fine for a zombie and wrong for almost everything
 * else: fish sank, bees fell out of the sky, slimes walked, spiders could not climb, and the
 * Ender Dragon needed a special case in three separate files.
 *
 * A profile describes the locomotion *style* of a mob so the controller can reproduce it, and a
 * single registry answers "how does this mob move" for every call site.
 */
enum class MovementKind {
    /** Walks on the ground using vanilla gravity, jumping and step-up. */
    GROUND,

    /** Free 3D flight: bees, parrots, phantoms, the Wither, the Ender Dragon. */
    FLYING,

    /** Holds altitude and drifts: ghasts, happy ghasts. */
    HOVERING,

    /** Swims in three dimensions and follows pitch while moving: fish, dolphins, guards. */
    AQUATIC,

    /** Walks, and climbs vertical surfaces while pressed against them: spiders. */
    CLIMBING,

    /** Moves by repeatedly launching itself: slimes, frogs, rabbits, goats. */
    LEAPING
}

/**
 * The locomotion parameters of one mob family.
 *
 * Defaults describe an ordinary ground walker, so a profile only has to state what makes its
 * family different.
 */
data class MovementProfile(
    val kind: MovementKind = MovementKind.GROUND,
    /** Short human-readable description, shown by `/transform info` and the manual. */
    val label: String = "Walks",
    /** Multiplier applied to the mob's own movement-speed attribute. */
    val speedScale: Float = 1.0f,
    /** How quickly velocity approaches the requested velocity for the driven kinds. */
    val accel: Float = 0.18f,
    /** Upward velocity produced while ascending. */
    val riseRate: Double = 0.30,
    /** Downward velocity produced while descending. */
    val sinkRate: Double = 0.24,
    /** Hard clamp so no form can tunnel through the world. */
    val maxVerticalSpeed: Double = 0.55,
    /** Velocity retained per tick while airborne; 1.0 would be frictionless. */
    val airDrag: Double = 0.91,
    /** Extra upward acceleration while a climbing mob is pressed against a wall. */
    val climbRate: Double = 0.20,
    /** Vertical impulse of a single leap. */
    val leapImpulse: Double = 0.42,
    /** Vertical velocity a gliding mob is clamped to while descending. */
    val glideFallSpeed: Double = -0.08,
    /** Strength of the gentle bob given to hovering mobs. */
    val hoverBob: Double = 0.012,
    val canSprint: Boolean = false,
    val climbs: Boolean = false,
    val glides: Boolean = false,
    /** True when the mob's gravity should be disabled while it is under player control. */
    val defiesGravity: Boolean = false,
    /** True when forward movement is steered by the look pitch (dolphins, guardians). */
    val usesPitch: Boolean = false
) {
    fun describe(): String = when (kind) {
        MovementKind.GROUND -> if (glides) "$label, slow-fall" else label
        MovementKind.FLYING -> "$label, vertical thrust"
        MovementKind.HOVERING -> "$label, altitude hold"
        MovementKind.AQUATIC -> "$label, 3D swim"
        MovementKind.CLIMBING -> "$label, wall climb"
        MovementKind.LEAPING -> "$label, hop"
    }
}

/**
 * Registry of locomotion profiles.
 *
 * Lookup is a single `when` over the entity type, with an instanceof fallback for the few
 * families that cannot be identified by type alone. It is deliberately total: every mob gets a
 * profile, so a newly added mob behaves like a normal ground walker instead of not moving.
 */
object MobMovementProfiles {

    fun profileFor(mob: Mob): MovementProfile = profileFor(mob.type)

    fun profileFor(type: EntityType<*>): MovementProfile = when (type) {

        // ---------------------------------------------------------------- free flight
        EntityTypes.BEE -> MovementProfile(
            kind = MovementKind.FLYING, label = "Hovers and darts", riseRate = 0.30,
            defiesGravity = true, speedScale = 1.05f
        )
        EntityTypes.PARROT -> MovementProfile(
            kind = MovementKind.FLYING, label = "Hops and flutters", riseRate = 0.26,
            defiesGravity = true
        )
        EntityTypes.BAT -> MovementProfile(
            kind = MovementKind.FLYING, label = "Erratic flutter", riseRate = 0.24,
            defiesGravity = true, accel = 0.24f
        )
        EntityTypes.ALLAY -> MovementProfile(
            kind = MovementKind.FLYING, label = "Drifts and darts", riseRate = 0.26,
            defiesGravity = true
        )
        EntityTypes.VEX -> MovementProfile(
            kind = MovementKind.FLYING, label = "Phases through walls", riseRate = 0.30,
            defiesGravity = true, speedScale = 1.15f
        )
        EntityTypes.PHANTOM -> MovementProfile(
            kind = MovementKind.FLYING, label = "Swooping flight", riseRate = 0.34,
            sinkRate = 0.30, defiesGravity = true, speedScale = 0.85f, accel = 0.12f
        )
        EntityTypes.ENDER_DRAGON -> MovementProfile(
            kind = MovementKind.FLYING, label = "Massive flight", riseRate = 0.28,
            sinkRate = 0.22, maxVerticalSpeed = 0.7, defiesGravity = true, speedScale = 0.65f,
            accel = 0.10f
        )
        EntityTypes.WITHER -> MovementProfile(
            kind = MovementKind.FLYING, label = "Heavy flight", riseRate = 0.26,
            defiesGravity = true, speedScale = 0.8f
        )
        EntityTypes.BREEZE -> MovementProfile(
            kind = MovementKind.FLYING, label = "Wind-assisted hover", riseRate = 0.30,
            defiesGravity = true, speedScale = 0.9f
        )
        EntityTypes.GHAST, EntityTypes.HAPPY_GHAST -> MovementProfile(
            kind = MovementKind.HOVERING, label = "Holds altitude", riseRate = 0.18,
            sinkRate = 0.16, maxVerticalSpeed = 0.30, defiesGravity = true, speedScale = 0.75f,
            accel = 0.07f
        )

        // -------------------------------------------------------------------- aquatic
        EntityTypes.COD, EntityTypes.SALMON, EntityTypes.TROPICAL_FISH, EntityTypes.TADPOLE ->
            MovementProfile(
                kind = MovementKind.AQUATIC, label = "Swims", usesPitch = true,
                speedScale = 0.85f, accel = 0.22f
            )
        EntityTypes.PUFFERFISH -> MovementProfile(
            kind = MovementKind.AQUATIC, label = "Puffs and swims slowly", usesPitch = true,
            speedScale = 0.5f, accel = 0.14f
        )
        EntityTypes.SQUID, EntityTypes.GLOW_SQUID -> MovementProfile(
            kind = MovementKind.AQUATIC, label = "Jets in bursts", usesPitch = true,
            speedScale = 1.0f, accel = 0.30f
        )
        EntityTypes.DOLPHIN -> MovementProfile(
            kind = MovementKind.AQUATIC, label = "Fast swimmer with leaps", usesPitch = true,
            speedScale = 1.35f, accel = 0.26f
        )
        EntityTypes.TURTLE -> MovementProfile(
            kind = MovementKind.AQUATIC, label = "Slow but powerful swimmer", usesPitch = true,
            speedScale = 0.8f, accel = 0.16f
        )
        EntityTypes.AXOLOTL -> MovementProfile(
            kind = MovementKind.AQUATIC, label = "Wriggles through water", usesPitch = true,
            speedScale = 1.0f, accel = 0.24f
        )
        EntityTypes.GUARDIAN, EntityTypes.ELDER_GUARDIAN, EntityTypes.NAUTILUS,
        EntityTypes.ZOMBIE_NAUTILUS -> MovementProfile(
            kind = MovementKind.AQUATIC, label = "Thrusts through water", usesPitch = true,
            speedScale = 0.9f, accel = 0.18f, defiesGravity = true
        )
        EntityTypes.CREAKING -> MovementProfile(
            kind = MovementKind.GROUND, label = "Tree-bound lurch", speedScale = 0.75f,
            canSprint = true
        )

        // ------------------------------------------------------------------ livestock
        // Farm animals are not fast, but they are heavy, and a cow that moves at the same pace
        // as a player is the first thing that gives a possessed body away.
        EntityTypes.COW, EntityTypes.MOOSHROOM, EntityTypes.SHEEP, EntityTypes.PIG ->
            MovementProfile(
                kind = MovementKind.GROUND, label = "Plods along at its own pace",
                speedScale = 0.82f, accel = 0.20f
            )

        // -------------------------------------------------------------------- equines
        EntityTypes.HORSE, EntityTypes.DONKEY, EntityTypes.MULE,
        EntityTypes.SKELETON_HORSE, EntityTypes.ZOMBIE_HORSE ->
            MovementProfile(
                kind = MovementKind.GROUND, label = "Strides, and gallops when pushed",
                speedScale = 1.15f, canSprint = true
            )

        // --------------------------------------------------------------------- giants
        EntityTypes.GIANT -> MovementProfile(
            kind = MovementKind.GROUND, label = "Earth-shaking stride", speedScale = 0.9f,
            canSprint = true
        )


        // ------------------------------------------------------------------- climbing
        EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER -> MovementProfile(
            kind = MovementKind.CLIMBING, label = "Walks and climbs walls",
            climbs = true, canSprint = true, speedScale = 1.0f
        )

        // -------------------------------------------------------------------- leaping
        EntityTypes.SLIME, EntityTypes.MAGMA_CUBE, EntityTypes.SULFUR_CUBE -> MovementProfile(
            kind = MovementKind.LEAPING, label = "Bounces", leapImpulse = 0.50,
            speedScale = 1.0f
        )
        EntityTypes.RABBIT -> MovementProfile(
            kind = MovementKind.LEAPING, label = "Hops in long bounds", leapImpulse = 0.52,
            speedScale = 1.1f
        )
        EntityTypes.FROG -> MovementProfile(
            kind = MovementKind.LEAPING, label = "Long leaps", leapImpulse = 0.60,
            speedScale = 1.0f
        )
        EntityTypes.GOAT -> MovementProfile(
            kind = MovementKind.LEAPING, label = "Charges and bounds", leapImpulse = 0.58,
            speedScale = 1.05f
        )
        EntityTypes.CAMEL, EntityTypes.CAMEL_HUSK -> MovementProfile(
            kind = MovementKind.LEAPING, label = "Dash and stride", leapImpulse = 0.40,
            speedScale = 0.9f
        )

        // -------------------------------------------------------------------- gliding
        EntityTypes.CHICKEN -> MovementProfile(
            kind = MovementKind.GROUND, label = "Walks and slow-falls", glides = true,
            speedScale = 0.9f
        )

        // -------------------------------------------------------------------- default
        else -> MovementProfile(kind = MovementKind.GROUND, label = "Walks")
    }

    /** True when the mob should be allowed to sprint under player input. */
    fun canSprint(mob: Mob): Boolean =
        profileFor(mob).canSprint || mob.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.2
}

/**
 * Applies player input to a possessed mob using its family's locomotion style.
 *
 * This is the only place that writes movement state to a controlled mob, so the loader mixins
 * and the transform service can no longer disagree about how a mob should be driven.
 */
object MobMovementController {

    /**
     * Drives [mob] for one tick.
     *
     * @param sprint true while the controlling player is holding the sprint key and the mob is
     *   physically able to run.
     */
    fun drive(mob: Mob, input: MobControlPayload, sprint: Boolean) {
        val profile = MobMovementProfiles.profileFor(mob)

        // The mob is never allowed to steer itself; the player is the only navigator.
        mob.navigation.stop()

        // Flight is sustained by disabling gravity for airborne families. Without this a bee
        // or a phantom drops out of the sky the moment the player stops pressing jump.
        mob.isNoGravity = profile.defiesGravity

        val magnitude = sqrt(input.forward * input.forward + input.strafe * input.strafe)
        val forward = if (magnitude > 0.001f) input.forward / magnitude else 0f
        val strafe = if (magnitude > 0.001f) input.strafe / magnitude else 0f

        // Base movement speed comes from the mob's own attribute so every form keeps its own
        // pace; sprinting is a multiplier on top of that.
        val baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED).toFloat()
        val speed = baseSpeed * profile.speedScale * if (sprint) 1.35f else 1.0f
        mob.setSpeed(speed)

        mob.setZza(forward)
        mob.setXxa(strafe)
        mob.setAggressive(input.attack || sprint)
        if (profile.glides && mob.deltaMovement.y < profile.glideFallSpeed) {
            mob.setDeltaMovement(mob.deltaMovement.x, profile.glideFallSpeed, mob.deltaMovement.z)
        }

        when (profile.kind) {
            MovementKind.GROUND -> driveGround(mob, input)
            MovementKind.CLIMBING -> driveClimbing(mob, input, profile)
            MovementKind.LEAPING -> driveLeaping(mob, input, profile)
            MovementKind.FLYING -> driveFlight(mob, input, profile, speed)
            MovementKind.HOVERING -> driveHover(mob, input, profile, speed)
            MovementKind.AQUATIC -> driveAquatic(mob, input, profile, speed)
        }
    }

    /** Stops a mob dead, used when no input has arrived yet. */
    fun idle(mob: Mob) {
        mob.setZza(0.0f)
        mob.setXxa(0.0f)
        mob.setYya(0.0f)
        mob.setJumping(false)
        mob.setAggressive(false)
    }

    /**
     * Hands a mob back to vanilla physics. Called before a possessed mob is released, so a
     * mob that was given gravity-free flight does not keep hovering once the player leaves it.
     */
    fun restore(mob: Mob) {
        idle(mob)
        mob.isNoGravity = false
        mob.setSpeed(0.0f)
    }

    // ------------------------------------------------------------------ ground

    private fun driveGround(mob: Mob, input: MobControlPayload) {
        mob.setYya(0.0f)
        mob.setJumping(input.jump)
        // Wet mobs swim upward while holding jump, which is what vanilla travel expects.
        if (input.jump && (mob.isInWater || mob.isInLava)) {
            mob.setYya(0.5f)
        }
    }

    private fun driveClimbing(mob: Mob, input: MobControlPayload, profile: MovementProfile) {
        val onWall = mob.horizontalCollision
        if (mob is Spider) {
            mob.setClimbing(onWall)
        }
        mob.setJumping(input.jump)

        if (!onWall && !input.jump) {
            return
        }
        // While pressed against a wall the mob is free to move up or down it.
        val vertical = when {
            input.jump -> profile.climbRate
            input.sneak -> -profile.climbRate
            else -> 0.0
        }
        if (vertical != 0.0) {
            mob.setDeltaMovement(mob.deltaMovement.x, vertical, mob.deltaMovement.z)
            mob.fallDistance = 0.0
        } else if (onWall) {
            // Holding still on a wall cancels the slide that would otherwise pull the mob down.
            mob.setDeltaMovement(mob.deltaMovement.x, 0.0, mob.deltaMovement.z)
        }
    }

    private fun driveLeaping(mob: Mob, input: MobControlPayload, profile: MovementProfile) {
        mob.setYya(0.0f)
        val wantsHop = input.jump || kotlin.math.abs(input.forward) + kotlin.math.abs(input.strafe) > 0.05f
        // A held key must not remove the mob's hop rhythm, so the cadence is derived from the
        // mob's own tick counter rather than from the key state.
        val ready = mob.onGround() && mob.tickCount % 10 == 0
        val leap = profile.leapImpulse * if (mob.isInWater) 0.45 else 1.0
        if (wantsHop && ready) {
            mob.setDeltaMovement(mob.deltaMovement.x, leap, mob.deltaMovement.z)
            mob.setJumping(true)
        } else {
            mob.setJumping(false)
        }
    }

    // ------------------------------------------------------------ driven movement

    private fun driveFlight(
        mob: Mob,
        input: MobControlPayload,
        profile: MovementProfile,
        speed: Float
    ) {
        mob.setJumping(false)
        val look = mob.getLookAngle()
        val yawRad = Math.toRadians(mob.yRot.toDouble())
        val forwardX = -sin(yawRad)
        val forwardZ = cos(yawRad)
        // Strafe is perpendicular to the facing direction.
        val rightX = forwardZ
        val rightZ = -forwardX

        val horizontal = speed.toDouble() * 1.6
        val targetX = (forwardX * input.forward + rightX * input.strafe) * horizontal
        val targetZ = (forwardZ * input.forward + rightZ * input.strafe) * horizontal
        val targetY = when {
            input.jump -> profile.riseRate
            input.sneak -> -profile.sinkRate
            // Flyers hold their altitude when no vertical input is given, but pointing the
            // camera up or down still steers while moving forward.
            else -> look.y * horizontal * 0.6 * kotlin.math.abs(input.forward)
        }

        val current = mob.deltaMovement
        val accel = profile.accel.toDouble()
        mob.setDeltaMovement(
            current.x + (targetX - current.x) * accel,
            (current.y + (targetY - current.y) * accel)
                .coerceIn(-profile.maxVerticalSpeed, profile.maxVerticalSpeed),
            current.z + (targetZ - current.z) * accel
        )
    }

    private fun driveHover(
        mob: Mob,
        input: MobControlPayload,
        profile: MovementProfile,
        speed: Float
    ) {
        mob.setJumping(false)
        val yawRad = Math.toRadians(mob.yRot.toDouble())
        val forwardX = -sin(yawRad)
        val forwardZ = cos(yawRad)
        val rightX = forwardZ
        val rightZ = -forwardX

        // Ghasts move at a crawl compared with their bounding box suggests; the pace is part of
        // what makes them feel like ghasts.
        val horizontal = speed.toDouble() * 1.1
        val targetX = (forwardX * input.forward + rightX * input.strafe) * horizontal
        val targetZ = (forwardZ * input.forward + rightZ * input.strafe) * horizontal
        val bob = sin((mob.tickCount + mob.id) * 0.12) * profile.hoverBob
        val targetY = when {
            input.jump -> profile.riseRate
            input.sneak -> -profile.sinkRate
            // Altitude is held, with a slow idle bob so the hover does not look frozen.
            else -> bob
        }

        val current = mob.deltaMovement
        val accel = profile.accel.toDouble()
        mob.setDeltaMovement(
            current.x + (targetX - current.x) * accel,
            (current.y + (targetY - current.y) * accel)
                .coerceIn(-profile.maxVerticalSpeed, profile.maxVerticalSpeed),
            current.z + (targetZ - current.z) * accel
        )
    }

    private fun driveAquatic(
        mob: Mob,
        input: MobControlPayload,
        profile: MovementProfile,
        speed: Float
    ) {
        mob.setJumping(false)
        val inWater = mob.isInWater || mob.isInLava

        if (!inWater) {
            // Out of water a fish flops: no thrust, ordinary gravity, and it can still be nudged.
            mob.isNoGravity = false
            mob.setYya(0.0f)
            return
        }

        // Guardians and nautilus mobs hover in place; fish are still governed by buoyancy.
        mob.isNoGravity = profile.defiesGravity
        val look = mob.getLookAngle()
        val yawRad = Math.toRadians(mob.yRot.toDouble())
        val forwardX = -sin(yawRad)
        val forwardZ = cos(yawRad)
        val rightX = forwardZ
        val rightZ = -forwardX

        val horizontal = speed.toDouble() * 1.7
        // Aquatic mobs of this family steer with the camera pitch, which is what makes diving
        // and surfacing feel like swimming rather than like walking underwater.
        val pitchY = if (profile.usesPitch) look.y * horizontal else 0.0
        val targetX = (forwardX * input.forward + rightX * input.strafe) * horizontal
        val targetZ = (forwardZ * input.forward + rightZ * input.strafe) * horizontal
        val targetY = when {
            input.jump -> profile.riseRate
            input.sneak -> -profile.sinkRate
            else -> pitchY
        }

        val current = mob.deltaMovement
        val accel = profile.accel.toDouble()
        mob.setDeltaMovement(
            current.x + (targetX - current.x) * accel,
            (current.y + (targetY - current.y) * accel)
                .coerceIn(-profile.maxVerticalSpeed, profile.maxVerticalSpeed),
            current.z + (targetZ - current.z) * accel
        )
    }
}
