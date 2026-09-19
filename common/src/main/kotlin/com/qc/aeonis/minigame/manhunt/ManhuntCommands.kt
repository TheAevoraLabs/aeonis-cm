package com.qc.aeonis.minigame.manhunt

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.qc.aeonis.platform.INetworkHelper
import com.qc.aeonis.platform.Services
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

object ManhuntCommands {
    private val logger = LoggerFactory.getLogger("aeonis-manhunt-commands")
    private val pendingSettings = mutableMapOf<java.util.UUID, ManhuntSettings>()
    
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        registerCommands(dispatcher)
        logger.info("§a[Manhunt] §7Commands registered!")
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("manhunt")
                .executes { openSetupGui(it) }
                .then(Commands.literal("help").executes { showHelp(it) })
                .then(Commands.literal("create")
                    .executes { createGame(it) }
                    .then(Commands.argument("difficulty", StringArgumentType.word())
                        .suggests { _, builder ->
                            builder.suggest("easy"); builder.suggest("normal"); builder.suggest("hard"); builder.suggest("nightmare"); builder.buildFuture()
                        }
                        .executes { createGameWithDifficulty(it) }))
                .then(Commands.literal("join")
                    .then(Commands.argument("gameId", StringArgumentType.word())
                        .suggests { _, builder ->
                            ManhuntManager.getActiveGameIds().forEach { builder.suggest(it) }
                            builder.buildFuture()
                        }
                        .executes { joinGame(it) }))
                .then(Commands.literal("leave").executes { leaveGame(it) })
                .then(Commands.literal("start").executes { startGame(it) })
                .then(Commands.literal("stop")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { stopGame(it) }
                    .then(Commands.argument("gameId", StringArgumentType.word())
                        .executes { stopSpecificGame(it) }))
                .then(Commands.literal("list").executes { listGames(it) })
                .then(Commands.literal("info").executes { showGameInfo(it) })
                .then(Commands.literal("settings")
                    .then(Commands.literal("difficulty")
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.5f, 2.0f))
                            .executes { setDifficulty(it) }))
                    .then(Commands.literal("headstart")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 120))
                            .executes { setHeadStart(it) }))
                    .then(Commands.literal("respawns")
                        .then(Commands.argument("count", IntegerArgumentType.integer(-1, 10))
                            .executes { setRespawns(it) }))
                    .then(Commands.literal("blocks")
                        .then(Commands.argument("count", IntegerArgumentType.integer(0, 256))
                            .executes { setHunterBlocks(it) }))
                    .then(Commands.literal("show").executes { showSettings(it) }))
                .then(Commands.literal("quickstart")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { quickStart(it) }
                    .then(Commands.argument("players", EntityArgument.players())
                        .executes { quickStartWithPlayers(it) }))
        )
        
        dispatcher.register(
            Commands.literal("hunter")
                .then(Commands.literal("deactivate").executes { deactivateHunter(it) })
                .then(Commands.literal("activate").executes { activateHunter(it) })
                .then(Commands.literal("freeze").executes { deactivateHunter(it) })
                .then(Commands.literal("unfreeze").executes { activateHunter(it) })
                .then(Commands.literal("teleport")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { teleportHunter(it) })
                .then(Commands.literal("giveblocks")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                        .executes { giveHunterBlocks(it) }))
                .then(Commands.literal("info").executes { showHunterInfo(it) })
        )
        
        dispatcher.register(
            Commands.literal("mh").redirect(dispatcher.root.getChild("manhunt"))
        )
    }

    private fun openSetupGui(ctx: CommandContext<CommandSourceStack>): Int {
        Services.load(INetworkHelper::class.java).sendOpenGui(ctx.source.playerOrException as ServerPlayer)
        return 1
    }

    private fun showHelp(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess({ Component.literal("§6§l═══════ MANHUNT HELP ═══════\n§e/manhunt§7 - Open setup GUI\n§e/manhunt create [difficulty]§7 - Create a game\n§e/manhunt join <gameId>§7 - Join a game\n§e/manhunt leave§7 - Leave current game\n§e/manhunt start§7 - Start the game\n§e/manhunt stop§7 - Stop the game\n§e/manhunt list§7 - List active games\n§e/manhunt info§7 - Show current game info") }, false)
        return 1
    }

    private fun createGame(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val settings = pendingSettings.getOrPut(player.uuid) { ManhuntSettings() }
        val game = ManhuntManager.createGame(player.level(), player, settings) ?: return 0
        ManhuntManager.joinGame(player, game.id)
        player.sendSystemMessage(Component.literal("§a[Manhunt] §7Game created! ID: §e${game.id}"))
        pendingSettings.remove(player.uuid)
        return 1
    }

    private fun createGameWithDifficulty(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val difficulty = StringArgumentType.getString(ctx, "difficulty")
        pendingSettings[player.uuid] = when (difficulty.lowercase()) {
            "easy" -> ManhuntSettings.easy()
            "hard" -> ManhuntSettings.hard()
            "nightmare" -> ManhuntSettings.nightmare()
            else -> ManhuntSettings.normal()
        }
        return createGame(ctx)
    }

    private fun joinGame(ctx: CommandContext<CommandSourceStack>): Int {
        return if (ManhuntManager.joinGame(ctx.source.playerOrException as ServerPlayer, StringArgumentType.getString(ctx, "gameId"))) 1 else 0
    }

    private fun leaveGame(ctx: CommandContext<CommandSourceStack>): Int {
        return if (ManhuntManager.leaveGame(ctx.source.playerOrException as ServerPlayer)) 1 else 0
    }

    private fun startGame(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val game = ManhuntManager.getPlayerGame(player.uuid) ?: return 0
        return if (ManhuntManager.startGame(game.id)) 1 else 0
    }

    private fun stopGame(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val game = ManhuntManager.getPlayerGame(player.uuid) ?: return 0
        ManhuntManager.endGame(game.id, "Stopped by ${player.name.string}")
        return 1
    }

    private fun stopSpecificGame(ctx: CommandContext<CommandSourceStack>): Int {
        ManhuntManager.endGame(StringArgumentType.getString(ctx, "gameId"), "Stopped by admin")
        return 1
    }

    private fun listGames(ctx: CommandContext<CommandSourceStack>): Int {
        val ids = ManhuntManager.getActiveGameIds()
        if (ids.isEmpty()) { ctx.source.sendSuccess({ Component.literal("§e[Manhunt] §7No active games.") }, false); return 1 }
        ctx.source.sendSuccess({ Component.literal("§6§l═══════ ACTIVE MANHUNT GAMES ═══════") }, false)
        ids.forEach { id ->
            val game = ManhuntManager.getGame(id) ?: return@forEach
            ctx.source.sendSuccess({ Component.literal("§7$id - §a${game.players.size} players") }, false)
        }
        return 1
    }

    private fun showGameInfo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val game = ManhuntManager.getPlayerGame(player.uuid) ?: return 0
        player.sendSystemMessage(Component.literal("§6§l═══════ GAME INFO ═══════\n§7Game ID: §e${game.id}\n§7Status: ${game.state}\n§7Difficulty: ${game.getDifficultyName()}"))
        return 1
    }

    private fun setDifficulty(ctx: CommandContext<CommandSourceStack>): Int {
        val settings = pendingSettings.getOrPut(ctx.source.playerOrException.uuid) { ManhuntSettings() }
        settings.difficulty = FloatArgumentType.getFloat(ctx, "value")
        return 1
    }

    private fun setHeadStart(ctx: CommandContext<CommandSourceStack>): Int {
        val settings = pendingSettings.getOrPut(ctx.source.playerOrException.uuid) { ManhuntSettings() }
        settings.headStartSeconds = IntegerArgumentType.getInteger(ctx, "seconds")
        return 1
    }

    private fun setRespawns(ctx: CommandContext<CommandSourceStack>): Int {
        val settings = pendingSettings.getOrPut(ctx.source.playerOrException.uuid) { ManhuntSettings() }
        settings.maxRespawns = IntegerArgumentType.getInteger(ctx, "count")
        return 1
    }

    private fun setHunterBlocks(ctx: CommandContext<CommandSourceStack>): Int {
        val settings = pendingSettings.getOrPut(ctx.source.playerOrException.uuid) { ManhuntSettings() }
        settings.hunterStartBlocks = IntegerArgumentType.getInteger(ctx, "count")
        return 1
    }

    private fun showSettings(ctx: CommandContext<CommandSourceStack>): Int {
        val settings = pendingSettings.getOrPut(ctx.source.playerOrException.uuid) { ManhuntSettings() }
        ctx.source.sendSuccess({ Component.literal("§6§l═══════ PENDING SETTINGS ═══════\n§7Difficulty: ${settings.difficulty}") }, false)
        return 1
    }

    private fun quickStart(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val game = ManhuntManager.createGame(player.level(), player, ManhuntSettings.normal()) ?: return 0
        ManhuntManager.joinGame(player, game.id); ManhuntManager.startGame(game.id)
        return 1
    }

    private fun quickStartWithPlayers(ctx: CommandContext<CommandSourceStack>): Int {
        val creator = ctx.source.playerOrException as ServerPlayer
        val game = ManhuntManager.createGame(creator.level(), creator, ManhuntSettings.normal()) ?: return 0
        EntityArgument.getPlayers(ctx, "players").forEach { ManhuntManager.joinGame(it, game.id) }
        ManhuntManager.startGame(game.id)
        return 1
    }

    private fun deactivateHunter(ctx: CommandContext<CommandSourceStack>): Int {
        val game = ManhuntManager.getPlayerGame(ctx.source.playerOrException.uuid) ?: return 0
        return if (ManhuntManager.freezeHunter(game.id)) 1 else 0
    }

    private fun activateHunter(ctx: CommandContext<CommandSourceStack>): Int {
        val game = ManhuntManager.getPlayerGame(ctx.source.playerOrException.uuid) ?: return 0
        return if (ManhuntManager.unfreezeHunter(game.id)) 1 else 0
    }

    private fun teleportHunter(ctx: CommandContext<CommandSourceStack>): Int { return 0 }

    private fun giveHunterBlocks(ctx: CommandContext<CommandSourceStack>): Int {
        val game = ManhuntManager.getPlayerGame(ctx.source.playerOrException.uuid) ?: return 0
        val hunter = game.level.getEntity(game.hunterEntityId ?: return 0) as? com.qc.aeonis.entity.HunterEntity ?: return 0
        hunter.giveBlocks(IntegerArgumentType.getInteger(ctx, "count"))
        return 1
    }

    private fun showHunterInfo(ctx: CommandContext<CommandSourceStack>): Int {
        val game = ManhuntManager.getPlayerGame(ctx.source.playerOrException.uuid) ?: return 0
        val hunter = game.level.getEntity(game.hunterEntityId ?: return 0) as? com.qc.aeonis.entity.HunterEntity ?: return 0
        ctx.source.sendSuccess({ Component.literal("§6§l═══════ HUNTER INFO ═══════\n§7State: ${hunter.currentState}") }, false)
        return 1
    }
}
