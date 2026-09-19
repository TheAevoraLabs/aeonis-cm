package com.qc.aeonis.transform

import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.Pose
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sin

/**
 * How hard a controlled body tries to look like a wild one.
 *
 * A possessed mob is easy to pick out once you know what to look for, and the client setting of
 * the same name lets a player trade stealth against readability. The server always enforces the
 * factual half (no name tag, no glow, correct persistence); the level only controls how much idle
 * behaviour the possessed body invents.
 */
enum class CamouflageLevel(val id: String, val label: String, val description: String) {
    /**
     * Nothing but the factual clean-up. The body stands perfectly still whenever the player is not
     * giving input, which is the most obvious tell there is, but it obeys the player instantly and
     * is the easiest to aim.
     */
    NONE(
        "none", "Obvious",
        "No idle behaviour. Easiest to aim, most obvious to onlookers."
    ),

    /**
     * The default. Idle bodies drift their head, take the occasional shuffling step and settle
     * their body behind their gaze, which is what a real mob does while wandering.
     */
    NATURAL(
        "natural", "Natural",
        "Idles, drifts and shuffles like a wild mob. Recommended."
    ),

    /**
     * Everything [NATURAL] does, plus the mob holds its gaze on a point for seconds at a time and
     * mumbles on its own ambient interval instead of staying silent. Slowest to notice, slightly
     * softer to steer because the body is never quite still.
     */
    GHOST(
        "ghost", "Hidden",
        "Adds long fixed gazes and idle vocalisations. Hardest to pick out, slightly floatier to steer."
    );

    companion object {
        fun byId(id: String?): CamouflageLevel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NATURAL
    }
}

/**
 * Makes a possessed mob hard to tell apart from the real thing.
 *
 * A player-driven mob is normally obvious from a distance for a short list of mechanical reasons:
 * it carries a name tag, it glows, it is completely silent, it never idles, its head is welded to
 * wherever the camera points, and its body yaw snaps instantly. This object removes every one of
 * those, and then puts back the idle behaviour that makes a vanilla mob read as "just a mob".
 *
 * It deliberately does not hide the mob, make it invulnerable or suppress its damage feedback:
 * the point is that another player has to *notice* it, not that they cannot find it at all. A
 * stealthier-than-vanilla mob would be unfair; an indistinguishable one is fair and survives
 * contact with a suspicious player.
 *
 * All idle state is deterministic from the mob's id and tick count, and is stored per mob so two
 * controlled mobs standing together do not idle in lockstep.
 */
object MobCamouflage {

    /** Per-mob idle phase, so a controlled mob keeps its "personality" across ticks. */
    private class IdleState {
        /** Where the mob is currently letting its gaze drift, in degrees relative to its yaw. */
        var gazeYaw = 0.0
        /** Where the gaze is drifting *to*; retargeted every few seconds. */
        var gazeTargetYaw = 0.0
        /** Fixed gaze holds: the mob stares at one point instead of drifting. */
        var gazeHoldTicks = 0
        /** Ticks until the next shuffling micro-step. */
        var stepCooldown = 0
    }

    private val idleStates = ConcurrentHashMap<Int, IdleState>()

    /** Forgets a mob's idle state; called when its session ends so ids are not leaked. */
    fun forget(mobId: Int) {
        idleStates.remove(mobId)
    }

    /** Forgets every mob's idle state, used on server shutdown and `/aeonis reload`. */
    fun clear() {
        idleStates.clear()
    }

    // ------------------------------------------------------------------ factual clean-up

    /**
     * Removes the persistent tells.
     *
     * This half is unconditional and cheap, and runs every tick, because effects, other mods and
     * even `/effect give` can put a glow or a visible name back at any moment. `isSilent` is
     * explicitly re-enabled: a possessed body should use the mob's own voice.
     */
    fun apply(mob: Mob) {
        // A floating name tag is the loudest possible signal that something is off, and a name
        // left over from a name tag item is worse than a hidden one, so it is removed outright.
        if (mob.customName != null) mob.customName = null
        mob.isCustomNameVisible = false
        // Glowing from a spectral arrow or a command would outline the form through walls.
        mob.setGlowingTag(false)
        // A totally silent mob is unnatural; let it use its own voice.
        mob.isSilent = false
        // Controlled forms must not despawn the moment the player looks away.
        mob.setPersistenceRequired()
        // `/ai chaotic` can leave a mob with no AI at all, which is itself a giveaway: it would
        // stand motionless rather than idling. Restoring normal AI is what a wild mob has.
        if (mob.isNoAi) mob.setNoAi(false)
    }

    // ------------------------------------------------------------------- idle behaviour

    /**
     * The idle body language pass.
     *
     * @param engaged true while the player is giving movement or look input, in which case the
     *   body simply follows the camera and no idle behaviour runs.
     * @param level how much idle behaviour to invent.
     */
    fun idle(mob: Mob, engaged: Boolean, tick: Int, level: CamouflageLevel = CamouflageLevel.NATURAL) {
        if (engaged || level == CamouflageLevel.NONE) {
            // A driven body still leads its head slightly, exactly as a real mob does while it is
            // walking toward something, instead of welding it to the current yaw.
            mob.yHeadRot = mob.yRot + lead(mob, tick)
            mob.yHeadRotO = mob.yHeadRot
            return
        }

        val state = idleStates.getOrPut(mob.id) { IdleState() }

        // Retarget the gaze every few seconds, and occasionally hold it perfectly still, which is
        // what a mob does when it has noticed something. A body that never holds still is as
        // suspicious as one that never moves.
        if (state.gazeHoldTicks > 0) {
            state.gazeHoldTicks--
        } else if (tick % 60 == 0) {
            state.gazeTargetYaw = (pseudoRandom(mob.id, tick, 1) * 2.0 - 1.0) * 34.0
            state.gazeHoldTicks = if (pseudoRandom(mob.id, tick, 2) < 0.3) 40 + (pseudoRandom(mob.id, tick, 3) * 60).toInt() else 0
        }
        // Approach the target slowly so the head never snaps.
        state.gazeYaw += (state.gazeTargetYaw - state.gazeYaw) * 0.06

        val pitchDrift = sin((tick + mob.id * 11) * 0.021) * 6.0
        mob.yHeadRot = mob.yRot + state.gazeYaw.toFloat()
        mob.yHeadRotO = mob.yHeadRot
        mob.xRot = (pitchDrift + if (level == CamouflageLevel.GHOST) 0.0 else 0.0)
            .toFloat()
            .coerceIn(-22.0f, 22.0f)

        shuffleStep(mob, state, level)
    }

    /**
     * A shuffling micro-step while idle.
     *
     * A possessed body that never changes position is a statue; a wild mob wanders in small
     * increments. The step is deliberately tiny (well under a block per second) and only fires
     * while the player is giving no input, so it can never fight the controller for control.
     */
    private fun shuffleStep(mob: Mob, state: IdleState, level: CamouflageLevel) {
        if (level != CamouflageLevel.NATURAL && level != CamouflageLevel.GHOST) return
        if (state.stepCooldown > 0) {
            state.stepCooldown--
            return
        }
        // Only shuffle a body that is actually standing on something and not mid-air, or a flying
        // form would be dragged out of the sky by its own idle animation.
        if (!mob.onGround()) return

        val roll = pseudoRandom(mob.id, mob.tickCount, 4)
        if (roll > 0.04) return

        state.stepCooldown = 20 + (pseudoRandom(mob.id, mob.tickCount, 5) * 60).toInt()
        val angle = pseudoRandom(mob.id, mob.tickCount, 6) * Math.PI * 2
        val speed = 0.014 + pseudoRandom(mob.id, mob.tickCount, 7) * 0.022
        mob.setDeltaMovement(
            mob.deltaMovement.x + kotlin.math.cos(angle) * speed,
            mob.deltaMovement.y,
            mob.deltaMovement.z + kotlin.math.sin(angle) * speed
        )
        // Half the time the mob also turns to face the way it shuffled, which reads as wandering.
        if (pseudoRandom(mob.id, mob.tickCount, 8) < 0.5) {
            mob.yRot += ((angle * 180.0 / Math.PI).toFloat() - mob.yRot) * 0.35f
        }
    }

    /**
     * Body rotation damping.
     *
     * Real mobs turn their body to catch up with their head rather than snapping; a snapped body
     * yaw is another visible tell on tall mobs. This is deliberately slower than vanilla's own
     * turn rate, because the controller is already writing the yaw directly.
     */
    fun settleBody(mob: Mob) {
        val difference = mob.yHeadRot - mob.yBodyRot
        if (abs(difference) < 0.5f) return
        mob.yBodyRot += difference * 0.25f
    }

    /**
     * Mirrors a crouch.
     *
     * Real mobs that can crouch do it for a reason (sneaking up on something, hiding under a low
     * ceiling); setting the pose keeps a crouching player's body from standing bolt upright in a
     * gap it should not fit through, which is a very visible tell.
     */
    fun mirrorPose(mob: Mob, sneaking: Boolean) {
        // Only ground-bound bodies crouch. A fish or a flyer told to crouch would be stuck in a
        // pose its own model has no animation for, which is a louder tell than standing up.
        if (sneaking && (!mob.onGround() || mob.isInWater || mob.isInLava)) return
        val wanted = if (sneaking) Pose.CROUCHING else Pose.STANDING
        if (mob.pose != wanted) mob.setPose(wanted)
    }

    /**
     * Occasional idle sound.
     *
     * Vanilla mobs vocalise on their own schedule; `playAmbientSound` already respects the mob's
     * own interval and only fires when it actually has an ambient sound defined, so mobs like
     * zombies keep their groans and silent ones stay silent.
     */
    fun ambientChatter(mob: Mob, tick: Int, level: CamouflageLevel = CamouflageLevel.NATURAL) {
        // `playAmbientSound` is cheap but not free, and it runs for every controlled mob every
        // tick; the interval here is only a throttle on top of the mob's own.
        if (level == CamouflageLevel.NONE) return
        val interval = if (level == CamouflageLevel.GHOST) 40 else 80
        if (tick % interval != 0) return
        try {
            mob.playAmbientSound()
        } catch (_: Exception) {
            // A mob without an ambient sound is fine; nothing to report.
        }
    }

    // ------------------------------------------------------------------------ helpers

    /**
     * A tiny lead between the head and the facing direction while moving.
     *
     * A mob walking toward a target leads its head by a couple of degrees; a head welded to the
     * exact yaw is a tell on every mob with a visible neck.
     */
    private fun lead(mob: Mob, tick: Int): Float {
        val speed = kotlin.math.sqrt(
            (mob.x - mob.xo) * (mob.x - mob.xo) + (mob.z - mob.zo) * (mob.z - mob.zo)
        )
        if (speed < 0.02) return 0.0f
        return (sin((tick + mob.id * 5) * 0.09) * 2.4).toFloat()
    }

    /**
     * A stable pseudo-random number in `[0, 1)` derived from the mob, the tick and a salt.
     *
     * Deterministic on purpose: the idle animation has to be reproducible from the mob's id alone
     * so a client that reconnects sees the same "personality", and so nothing here needs shared
     * state between the server tick and a render pass.
     */
    private fun pseudoRandom(mobId: Int, tick: Int, salt: Int): Double {
        var value = tick * 1_103_515_245 + mobId * 12_345 + salt * 2_654_435_761
        value = value xor (value shr 13)
        value *= 1_274_126_177
        value = value xor (value shr 16)
        return (value and 0x7FFFFFFF).toDouble() / 0x7FFFFFFF.toDouble()
    }
}
