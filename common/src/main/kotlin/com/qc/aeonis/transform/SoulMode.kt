package com.qc.aeonis.transform

import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Soul mode: a spectator state where the player can possess an existing mob in the world.
 *
 * Kept separate from transform sessions because the two previously shared one gamemode map.
 * That meant entering soul mode, possessing a mob, and then untransforming could restore the
 * wrong gamemode depending on which path ran first. Each mode now owns its own snapshot.
 */
object SoulMode {
    private val members = ConcurrentHashMap<UUID, PlayerRestoreState>()

    fun isActive(uuid: UUID): Boolean = members.containsKey(uuid)

    fun members(): Set<UUID> = members.keys.toSet()

    fun count(): Int = members.size

    /** Returns false when the player is already in soul mode. */
    fun enter(player: ServerPlayer): Boolean =
        members.putIfAbsent(player.uuid, PlayerRestoreState.capture(player)) == null

    /**
     * Removes and returns the stored snapshot, or null when the player was not in soul mode.
     * Used both to exit and to hand the snapshot over to a transform session on possession.
     */
    fun leave(uuid: UUID): PlayerRestoreState? = members.remove(uuid)

    fun clear() = members.clear()
}
