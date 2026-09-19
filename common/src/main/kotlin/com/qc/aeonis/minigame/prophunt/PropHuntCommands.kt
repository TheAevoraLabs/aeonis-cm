package com.qc.aeonis.minigame.prophunt

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory

object PropHuntCommands {
    private val logger = LoggerFactory.getLogger("aeonis-prophunt-commands")
    
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        registerCommands(dispatcher)
        logger.info("§a[PropHunt] §7Commands registered!")
    }
    
    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("prophunt")
                .then(Commands.literal("help").executes { showHelp(it) })
                .then(Commands.literal("create")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("arena", StringArgumentType.word())
                        .executes { createGame(it) }))
                .then(Commands.literal("join")
                    .then(Commands.argument("arena", StringArgumentType.word())
                        .executes { joinGame(it) }))
                .then(Commands.literal("leave").executes { leaveGame(it) })
                .then(Commands.literal("list").executes { listGames(it) })
                .then(Commands.literal("prop")
                    .then(Commands.literal("lock").executes { toggleLock(it) })
                    .then(Commands.literal("freeze").executes { toggleFreeze(it) })
                    .then(Commands.literal("taunt").executes { performTaunt(it) })
                    .then(Commands.literal("disguise")
                        .then(Commands.argument("type", StringArgumentType.word())
                            .executes { changeDisguise(it) })))
                .then(Commands.literal("hunter")
                    .then(Commands.literal("scan").executes { useScan(it) })
                    .then(Commands.literal("track").executes { useTracker(it) }))
                .then(Commands.literal("start")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { forceStart(it) })
                .then(Commands.literal("stop")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { stopCurrentGame(it) }
                    .then(Commands.argument("arena", StringArgumentType.word())
                        .executes { stopGame(it) }))
                .then(Commands.literal("skip")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes { skipPhase(it) })
                .then(Commands.literal("arena")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes { createArenaHere(it) }
                            .then(Commands.argument("preset", StringArgumentType.word())
                                .executes { createArenaWithPreset(it) })))
                    .then(Commands.literal("set")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                    .then(Commands.argument("lobby", BlockPosArgument.blockPos())
                                        .executes { setCustomArena(it) })))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes { removeArena(it) }))
                    .then(Commands.literal("list").executes { listArenas(it) })
                    .then(Commands.literal("tp")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes { teleportToArena(it) })))
                .then(Commands.literal("settings")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("rounds")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                            .executes { setRounds(it) }))
                    .then(Commands.literal("hidetime")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 120))
                            .executes { setHideTime(it) }))
                    .then(Commands.literal("roundtime")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(60, 600))
                            .executes { setRoundTime(it) }))
                    .then(Commands.literal("hunters")
                        .then(Commands.argument("min", IntegerArgumentType.integer(1, 10))
                            .then(Commands.argument("max", IntegerArgumentType.integer(1, 10))
                                .executes { setHunterCount(it) })))
                    .then(Commands.literal("hints")
                        .then(Commands.literal("on").executes { setHints(it, true) })
                        .then(Commands.literal("off").executes { setHints(it, false) }))
                    .then(Commands.literal("hintinterval")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 120))
                            .executes { setHintInterval(it) }))
                    .then(Commands.literal("show").executes { showSettings(it) }))
                .then(Commands.literal("stats")
                    .executes { showStats(it) }
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes { showPlayerStats(it) }))
        )
        
        dispatcher.register(Commands.literal("ph").redirect(dispatcher.root.getChild("prophunt")))
    }
    
    private fun showHelp(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess({ Component.literal("§6§l═══════ PROP HUNT HELP ═══════\n§e/prophunt create <arena>§7 - Create a new game\n§e/prophunt join <arena>§7 - Join a game\n§e/prophunt leave§7 - Leave your current game\n§e/prophunt list§7 - List active games\n§e/prophunt stats§7 - View your statistics\n\n§b§lProp Commands (in-game):\n§e/prophunt prop lock§7 - Lock rotation\n§e/prophunt prop freeze§7 - Freeze movement\n§e/prophunt prop taunt§7 - Perform a taunt\n\n§c§lHunter Commands (in-game):\n§e/prophunt hunter scan§7 - Use scanner\n§e/prophunt hunter track§7 - Use tracker dart") }, false)
        return 1
    }
    
    private fun createGame(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val arenaId = StringArgumentType.getString(ctx, "arena")
        PropHuntManager.createGame(arenaId, ctx.source.level, player)
        return 1
    }
    
    private fun joinGame(ctx: CommandContext<CommandSourceStack>): Int {
        return if (PropHuntManager.joinGame(ctx.source.playerOrException, StringArgumentType.getString(ctx, "arena"))) 1 else 0
    }
    
    private fun leaveGame(ctx: CommandContext<CommandSourceStack>): Int {
        return if (PropHuntManager.leaveGame(ctx.source.playerOrException)) 1 else 0
    }
    
    private fun listGames(ctx: CommandContext<CommandSourceStack>): Int {
        val games = PropHuntManager.listGames()
        if (games.isEmpty()) { ctx.source.sendSuccess({ Component.literal("§7No active Prop Hunt games.") }, false) }
        else {
            ctx.source.sendSuccess({ Component.literal("§6§l═══════ ACTIVE GAMES ═══════") }, false)
            games.forEach { game -> ctx.source.sendSuccess({ Component.literal("§7• §e${game.arenaId}") }, false) }
        }
        return 1
    }
    
    private fun toggleLock(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        PropDisguiseManager.toggleRotationLock(player, game)
        return 1
    }
    
    private fun toggleFreeze(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        PropDisguiseManager.toggleFreeze(player, game)
        return 1
    }
    
    private fun performTaunt(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        PropDisguiseManager.performTaunt(player, game)
        return 1
    }
    
    private fun changeDisguise(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        return if (PropDisguiseManager.chooseDisguise(player, StringArgumentType.getString(ctx, "type"), game)) 1 else 0
    }
    
    private fun useScan(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        return if (HunterAbilityManager.useScanner(player, game)) 1 else 0
    }
    
    private fun useTracker(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        return if (HunterAbilityManager.useTracker(player, game)) 1 else 0
    }
    
    private fun forceStart(ctx: CommandContext<CommandSourceStack>): Int {
        val game = PropHuntManager.getPlayerGame(ctx.source.playerOrException) ?: return 0
        PropHuntManager.startRound(game)
        return 1
    }
    
    private fun stopCurrentGame(ctx: CommandContext<CommandSourceStack>): Int {
        val game = PropHuntManager.getPlayerGame(ctx.source.playerOrException) ?: return 0
        PropHuntManager.endGame(game.arenaId, "Stopped by admin")
        return 1
    }
    
    private fun stopGame(ctx: CommandContext<CommandSourceStack>): Int {
        PropHuntManager.endGame(StringArgumentType.getString(ctx, "arena"), "Stopped by admin")
        return 1
    }
    
    private fun skipPhase(ctx: CommandContext<CommandSourceStack>): Int {
        val game = PropHuntManager.getPlayerGame(ctx.source.playerOrException) ?: return 0
        when (game.state) {
            GameState.HIDING -> game.hideTimeTicks = 0
            GameState.SEEKING -> PropHuntManager.endRound(game, PropHuntTeam.NONE, "Phase skipped by admin")
            else -> return 0
        }
        return 1
    }
    
    private fun createArenaHere(ctx: CommandContext<CommandSourceStack>): Int {
        ArenaManager.getOrCreateArena(StringArgumentType.getString(ctx, "id"), ctx.source.level, ctx.source.playerOrException.blockPosition())
        return 1
    }
    
    private fun createArenaWithPreset(ctx: CommandContext<CommandSourceStack>): Int {
        ArenaManager.createArenaFromPreset(StringArgumentType.getString(ctx, "id"), ctx.source.level, ctx.source.playerOrException.blockPosition(), StringArgumentType.getString(ctx, "preset"))
        return 1
    }
    
    private fun setCustomArena(ctx: CommandContext<CommandSourceStack>): Int {
        ArenaManager.setCustomArena(StringArgumentType.getString(ctx, "id"), ctx.source.level, BlockPosArgument.getLoadedBlockPos(ctx, "pos1"), BlockPosArgument.getLoadedBlockPos(ctx, "pos2"), BlockPosArgument.getLoadedBlockPos(ctx, "lobby"))
        return 1
    }
    
    private fun removeArena(ctx: CommandContext<CommandSourceStack>): Int {
        return if (ArenaManager.removeArena(StringArgumentType.getString(ctx, "id"))) 1 else 0
    }
    
    private fun listArenas(ctx: CommandContext<CommandSourceStack>): Int {
        ArenaManager.listArenas().forEach { id -> ctx.source.sendSuccess({ Component.literal("§7• §e$id") }, false) }
        return 1
    }
    
    private fun teleportToArena(ctx: CommandContext<CommandSourceStack>): Int {
        val arena = ArenaManager.getArena(StringArgumentType.getString(ctx, "id")) ?: return 0
        ArenaManager.teleportToLobby(ctx.source.playerOrException, arena)
        return 1
    }
    
    private fun setRounds(ctx: CommandContext<CommandSourceStack>): Int {
        PropHuntManager.config.defaultSettings.maxRounds = IntegerArgumentType.getInteger(ctx, "count")
        return 1
    }
    
    private fun setHideTime(ctx: CommandContext<CommandSourceStack>): Int {
        PropHuntManager.config.defaultSettings.hideTimeSeconds = IntegerArgumentType.getInteger(ctx, "seconds")
        return 1
    }
    
    private fun setRoundTime(ctx: CommandContext<CommandSourceStack>): Int {
        PropHuntManager.config.defaultSettings.roundTimeSeconds = IntegerArgumentType.getInteger(ctx, "seconds")
        return 1
    }
    
    private fun setHunterCount(ctx: CommandContext<CommandSourceStack>): Int {
        PropHuntManager.config.defaultSettings.minHunters = IntegerArgumentType.getInteger(ctx, "min")
        PropHuntManager.config.defaultSettings.maxHunters = IntegerArgumentType.getInteger(ctx, "max")
        return 1
    }
    
    private fun setHints(ctx: CommandContext<CommandSourceStack>, enabled: Boolean): Int {
        PropHuntManager.config.defaultSettings.hunterHintsEnabled = enabled
        return 1
    }

    private fun setHintInterval(ctx: CommandContext<CommandSourceStack>): Int {
        PropHuntManager.config.defaultSettings.hintIntervalSeconds = IntegerArgumentType.getInteger(ctx, "seconds")
        return 1
    }
    
    private fun showSettings(ctx: CommandContext<CommandSourceStack>): Int {
        return 1
    }
    
    private fun showStats(ctx: CommandContext<CommandSourceStack>): Int {
        return 1
    }
    
    private fun showPlayerStats(ctx: CommandContext<CommandSourceStack>): Int {
        return 1
    }
}
