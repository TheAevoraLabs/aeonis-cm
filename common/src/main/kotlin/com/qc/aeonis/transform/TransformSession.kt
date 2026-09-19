package com.qc.aeonis.transform

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.GameType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything about a player that entering a transform mutates.
 *
 * Previously this information was spread over four unrelated maps (`originalGameModes`,
 * `originalMoveSpeeds` inside the command object and `originalGameModes` again inside
 * `AeonisPossession`), each with different defaults and none of them cleared consistently.
 * A partial restore is exactly how players ended up invisible, clipped into blocks, or in
 * survival after disconnecting mid-morph, so the snapshot and its restore are kept together
 * and restoring is a single all-or-nothing operation.
 */
data class PlayerRestoreState(
    val gameMode: GameType,
    val movementSpeed: Double,
    val maxHealth: Double,
    val health: Float,
    val invisible: Boolean,
    val noPhysics: Boolean,
    val mayFly: Boolean,
    val flying: Boolean
) {
    /**
     * Restores every captured value. [healthOverride] is used by the transform path so damage
     * taken while morphed carries over proportionally instead of being refunded.
     */
    fun applyTo(player: ServerPlayer, healthOverride: Float? = null) {
        val maxHealthValue = maxHealth.coerceAtLeast(1.0)
        player.setGameMode(gameMode)
        player.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = movementSpeed
        player.getAttribute(Attributes.MAX_HEALTH)?.baseValue = maxHealthValue
        player.health = (healthOverride ?: health).coerceIn(1.0f, maxHealthValue.toFloat())
        player.isInvisible = invisible
        player.noPhysics = noPhysics
        player.fallDistance = 0.0
        player.abilities.mayfly = mayFly
        player.abilities.flying = mayFly && flying
        player.onUpdateAbilities()
        // Make sure the camera is never left attached to a discarded mob.
        player.setCamera(player)
    }

    companion object {
        fun capture(player: ServerPlayer): PlayerRestoreState = PlayerRestoreState(
            gameMode = player.gameMode.gameModeForPlayer,
            movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue ?: 0.1,
            maxHealth = player.getAttribute(Attributes.MAX_HEALTH)?.baseValue ?: 20.0,
            health = player.health,
            invisible = player.isInvisible,
            noPhysics = player.noPhysics,
            mayFly = player.abilities.mayfly,
            flying = player.abilities.flying
        )

        /**
         * Fallback for the desynced case where control state exists but the session record was
         * lost. Deliberately conservative: a visible, grounded survival player at full health
         * is always recoverable, which matters more than preserving a gamemode we cannot know.
         */
        fun safeDefault(): PlayerRestoreState = PlayerRestoreState(
            gameMode = GameType.SURVIVAL,
            movementSpeed = 0.1,
            maxHealth = 20.0,
            health = 20.0f,
            invisible = false,
            noPhysics = false,
            mayFly = false,
            flying = false
        )
    }
}

/** A live "this player is currently a mob" record. */
data class TransformSession(
    val ownerId: UUID,
    val entityId: Int,
    val restore: PlayerRestoreState,
    val variants: List<String>,
    val startedTick: Long
) {
    fun describe(elapsedTicks: Long): String {
        val seconds = ((elapsedTicks - startedTick) / 20).coerceAtLeast(0)
        val variantText = if (variants.isEmpty()) "none" else variants.joinToString(", ")
        return "entity #$entityId, variants: $variantText, active for ${seconds}s, " +
            "original mode: ${restore.gameMode.name.lowercase()}"
    }
}

/** Single source of truth for active transform sessions, keyed by the controlling player. */
object TransformSessions {
    private val sessions = ConcurrentHashMap<UUID, TransformSession>()

    fun get(ownerId: UUID): TransformSession? = sessions[ownerId]

    fun isActive(ownerId: UUID): Boolean = sessions.containsKey(ownerId)

    fun count(): Int = sessions.size

    fun start(session: TransformSession) {
        sessions[session.ownerId] = session
    }

    /** Removes and returns the session, or null when the player was not transformed. */
    fun end(ownerId: UUID): TransformSession? = sessions.remove(ownerId)

    /** Re-points a session after a variant converted the mob into a different entity. */
    fun rebind(ownerId: UUID, entityId: Int, variantId: String?): TransformSession? =
        sessions.computeIfPresent(ownerId) { _, session ->
            session.copy(
                entityId = entityId,
                variants = if (variantId == null) session.variants else session.variants + variantId
            )
        }

    fun all(): List<TransformSession> = sessions.values.toList()

    fun clear() = sessions.clear()
}
