package com.qc.aeonis.minigame.prophunt

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap

object ArenaManager {
    private val arenas = ConcurrentHashMap<String, PropHuntArena>()

    fun register() {}

    fun getArena(id: String): PropHuntArena? = arenas[id]

    fun getOrCreateArena(id: String, level: ServerLevel, lobby: BlockPos): PropHuntArena {
        return arenas.getOrPut(id) {
            PropHuntArena(id, lobby, lobby.offset(-24, 0, -24), lobby.offset(24, 16, 24))
        }
    }

    fun createArenaFromPreset(id: String, level: ServerLevel, lobby: BlockPos, preset: String) {}

    fun setCustomArena(id: String, level: ServerLevel, pos1: BlockPos, pos2: BlockPos, lobby: BlockPos) {
        arenas[id] = PropHuntArena(id, lobby, pos1, pos2)
    }

    fun removeArena(id: String): Boolean = arenas.remove(id) != null

    fun listArenas(): List<String> = arenas.keys.toList()

    fun teleportToLobby(player: ServerPlayer, arena: PropHuntArena) {
        player.teleportTo(arena.lobby.x + 0.5, arena.lobby.y.toDouble(), arena.lobby.z + 0.5)
    }
}

data class PropHuntArena(
    val id: String,
    val lobby: BlockPos,
    val pos1: BlockPos,
    val pos2: BlockPos
)
