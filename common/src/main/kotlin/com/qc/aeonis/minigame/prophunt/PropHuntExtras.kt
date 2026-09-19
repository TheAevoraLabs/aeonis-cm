package com.qc.aeonis.minigame.prophunt

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import java.util.*

object PropHuntExtras {
    fun handlePlayerDeath(player: ServerPlayer, game: PropHuntGame) {
        val playerData = game.players[player.uuid] ?: return
        if (playerData.team == PropHuntTeam.PROP) {
            playerData.isAlive = false
            game.broadcast("§cProp ${player.name.string} was caught!")
            if (game.getProps().isEmpty()) {
                game.endRound(PropHuntTeam.HUNTER, "All props caught")
            }
        }
    }
}

object PropHuntRewards {
    fun grantReward(player: ServerPlayer, xp: Int, reason: String) {
        player.giveExperiencePoints(xp)
        player.sendSystemMessage(Component.literal("§a+$xp XP §7($reason)"))
    }
    
    fun awardFirstBlood(player: ServerPlayer, game: PropHuntGame) {
        grantReward(player, 50, "First Blood")
    }
    
    fun awardLastKill(player: ServerPlayer, game: PropHuntGame) {
        grantReward(player, 75, "Last Kill")
    }
}

object PropHuntSpectator {
    fun setSpectator(player: ServerPlayer) {
        player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR)
    }
    
    fun enterSpectatorMode(player: ServerPlayer, game: PropHuntGame) {
        setSpectator(player)
        player.sendSystemMessage(Component.literal("§7You are now a spectator."))
    }
}
