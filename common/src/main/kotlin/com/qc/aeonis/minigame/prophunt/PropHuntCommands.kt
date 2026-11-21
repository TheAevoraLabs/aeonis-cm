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
                    .then(Commands.literal("whistle").executes { performTaunt(it) })
                    .then(Commands.literal("decoy").executes { deployDecoy(it) })
                    .then(Commands.literal("disguise")
                        .then(Commands.argument("type", StringArgumentType.word())
                            .executes { changeDisguise(it) })))
                .then(Commands.literal("hunter")
                    .then(Commands.literal("scan").executes { useScan(it) })
                    .then(Commands.literal("sonar").executes { useScan(it) })
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
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 120))
                            .executes { setHideTime(it) }))
                    .then(Commands.literal("roundtime")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(30, 600))
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
        ctx.source.sendSuccess({
            Component.literal(
                "§6§l═══════ PROP HUNT HELP ═══════\n" +
                "§e/prophunt join <arena>§7 - Join a game\n" +
                "§e/prophunt leave§7 - Leave your current game\n" +
                "§e/prophunt list§7 - List active games\n" +
                "§e/prophunt stats§7 - View your statistics\n\n" +
                "§a§lProp Controls:\n" +
                "§e/prophunt prop lock§7 - Toggle solid grid lock\n" +
                "§e/prophunt prop decoy§7 - Deploy a decoy block\n" +
                "§e/prophunt prop whistle§7 - Directional taunt whistle\n" +
                "§e/prophunt prop disguise <type>§7 - Choose camouflage\n\n" +
                "§c§lHunter Controls:\n" +
                "§e/prophunt hunter sonar§7 - Echolocate props within 15m\n" +
                "§e/prophunt hunter track§7 - Tracker dart nearest prop\n" +
                "§c⚠ Miss Penalty: attacking non-prop blocks causes swing fatigue!"
            )
        }, false)
        return 1
    }

    private fun createGame(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val arenaId = StringArgumentType.getString(ctx, "arena")
        val game = PropHuntManager.createGame(arenaId, ctx.source.level, player)
        if (game != null) {
            ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Game created in arena §e$arenaId§a!") }, false)
        } else {
            ctx.source.sendFailure(Component.literal("§c[PropHunt] Could not create game in arena $arenaId."))
        }
        return 1
    }

    private fun joinGame(ctx: CommandContext<CommandSourceStack>): Int {
        val success = PropHuntManager.joinGame(ctx.source.playerOrException, StringArgumentType.getString(ctx, "arena"))
        if (success) {
            ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Joined game!") }, false)
        } else {
            ctx.source.sendFailure(Component.literal("§c[PropHunt] Could not join game."))
        }
        return if (success) 1 else 0
    }

    private fun leaveGame(ctx: CommandContext<CommandSourceStack>): Int {
        val success = PropHuntManager.leaveGame(ctx.source.playerOrException)
        if (success) {
            ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Left game.") }, false)
        } else {
            ctx.source.sendFailure(Component.literal("§c[PropHunt] You are not in a game."))
        }
        return if (success) 1 else 0
    }

    private fun listGames(ctx: CommandContext<CommandSourceStack>): Int {
        val games = PropHuntManager.listGames()
        if (games.isEmpty()) {
            ctx.source.sendSuccess({ Component.literal("§7No active Prop Hunt games.") }, false)
        } else {
            ctx.source.sendSuccess({ Component.literal("§6§l═══════ ACTIVE GAMES ═══════") }, false)
            games.forEach { game ->
                ctx.source.sendSuccess({
                    Component.literal("§7• §e${game.arenaId} §7[${game.state.name}] §8(§f${game.players.size} players§8)")
                }, false)
            }
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

    private fun deployDecoy(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        return if (PropDisguiseManager.deployDecoy(player, game)) 1 else 0
    }

    private fun changeDisguise(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        val type = StringArgumentType.getString(ctx, "type")
        return if (PropDisguiseManager.chooseDisguise(player, type, game)) 1 else 0
    }

    private fun useScan(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        return if (HunterAbilityManager.useSonarPing(player, game)) 1 else 0
    }

    private fun useTracker(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = PropHuntManager.getPlayerGame(player) ?: return 0
        return if (HunterAbilityManager.useTracker(player, game)) 1 else 0
    }

    private fun forceStart(ctx: CommandContext<CommandSourceStack>): Int {
        val game = PropHuntManager.getPlayerGame(ctx.source.playerOrException) ?: return 0
        PropHuntManager.startRound(game)
        ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Round starting!") }, false)
        return 1
    }

    private fun stopCurrentGame(ctx: CommandContext<CommandSourceStack>): Int {
        val game = PropHuntManager.getPlayerGame(ctx.source.playerOrException) ?: return 0
        PropHuntManager.endGame(game.arenaId, "Stopped by admin")
        ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Game stopped.") }, false)
        return 1
    }

    private fun stopGame(ctx: CommandContext<CommandSourceStack>): Int {
        val arena = StringArgumentType.getString(ctx, "arena")
        PropHuntManager.endGame(arena, "Stopped by admin")
        ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Game in arena $arena stopped.") }, false)
        return 1
    }

    private fun skipPhase(ctx: CommandContext<CommandSourceStack>): Int {
        val game = PropHuntManager.getPlayerGame(ctx.source.playerOrException) ?: return 0
        when (game.state) {
            GameState.STARTING -> game.countdownTicks = 0
            GameState.HIDING -> game.hideTimeTicks = 0
            GameState.SEEKING -> PropHuntManager.endRound(game, PropHuntTeam.NONE, "Phase skipped by admin")
            else -> return 0
        }
        ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Phase skipped.") }, false)
        return 1
    }

    private fun createArenaHere(ctx: CommandContext<CommandSourceStack>): Int {
        val arena = ArenaManager.getOrCreateArena(StringArgumentType.getString(ctx, "id"), ctx.source.level, ctx.source.playerOrException.blockPosition())
        ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Arena created with lobby at ${arena.lobby.toShortString()}") }, false)
        return 1
    }

    private fun createArenaWithPreset(ctx: CommandContext<CommandSourceStack>): Int {
        ArenaManager.createArenaFromPreset(StringArgumentType.getString(ctx, "id"), ctx.source.level, ctx.source.playerOrException.blockPosition(), StringArgumentType.getString(ctx, "preset"))
        ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Arena preset created.") }, false)
        return 1
    }

    private fun setCustomArena(ctx: CommandContext<CommandSourceStack>): Int {
        val id = StringArgumentType.getString(ctx, "id")
        val pos1 = BlockPosArgument.getLoadedBlockPos(ctx, "pos1")
        val pos2 = BlockPosArgument.getLoadedBlockPos(ctx, "pos2")
        val lobby = BlockPosArgument.getLoadedBlockPos(ctx, "lobby")
        ArenaManager.setCustomArena(id, ctx.source.level, pos1, pos2, lobby)
        ctx.source.sendSuccess({ Component.literal("§a[PropHunt] Arena $id bounds updated.") }, false)
        return 1
    }

    private fun removeArena(ctx: CommandContext<CommandSourceStack>): Int {
        val success = ArenaManager.removeArena(StringArgumentType.getString(ctx, "id"))
        return if (success) 1 else 0
    }

    private fun listArenas(ctx: CommandContext<CommandSourceStack>): Int {
        val arenas = ArenaManager.listArenas()
        ctx.source.sendSuccess({ Component.literal("§6§l═══════ ARENAS ═══════") }, false)
        arenas.forEach { id -> ctx.source.sendSuccess({ Component.literal("§7• §e$id") }, false) }
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
        val s = PropHuntManager.config.defaultSettings
        ctx.source.sendSuccess({
            Component.literal(
                "§6§l═══════ PROP HUNT SETTINGS ═══════\n" +
                "§7Rounds: §e${s.maxRounds} §8| §7Hide Time: §e${s.hideTimeSeconds}s\n" +
                "§7Round Time: §e${s.roundTimeSeconds}s §8| §7Hunters: §e${s.minHunters}-${s.maxHunters}\n" +
                "§7Sonar Cooldown: §e${s.scannerCooldownSeconds}s §8| §7Taunt Cooldown: §e${s.tauntCooldownSeconds}s\n" +
                "§7False Hit Penalty: §c-${s.falseHitPenaltyHealth}❤"
            )
        }, false)
        return 1
    }

    private fun showStats(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        displayStats(ctx, player.uuid, player.name.string)
        return 1
    }

    private fun showPlayerStats(ctx: CommandContext<CommandSourceStack>): Int {
        val target = EntityArgument.getPlayer(ctx, "player")
        displayStats(ctx, target.uuid, target.name.string)
        return 1
    }

    private fun displayStats(ctx: CommandContext<CommandSourceStack>, uuid: java.util.UUID, name: String) {
        val stats = PropHuntManager.statistics
        ctx.source.sendSuccess({
            Component.literal(
                "§6§l═══════ STATS: $name ═══════\n" +
                "§7Prop Wins: §a${stats.getPropWins(uuid)}\n" +
                "§7Hunter Wins: §c${stats.getHunterWins(uuid)}\n" +
                "§7Props Caught: §e${stats.getPropsFound(uuid)}\n" +
                "§7Best Survival: §b${stats.getBestSurvivalTime(uuid) / 20}s"
            )
        }, false)
    }
}
