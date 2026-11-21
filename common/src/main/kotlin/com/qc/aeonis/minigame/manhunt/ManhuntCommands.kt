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
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

object ManhuntCommands {
    private val logger = LoggerFactory.getLogger("aeonis-manhunt-commands")
    private val pendingSettings = mutableMapOf<java.util.UUID, ManhuntSettings>()

    private val CAN_MANAGE = java.util.function.Predicate<CommandSourceStack> { source ->
        source.server.isSingleplayer || source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        registerCommands(dispatcher)
        logger.info("§a[Manhunt] §7Commands registered!")
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("manhunt")
                .executes { openSetupGui(it) }
                .then(Commands.literal("help").executes { showHelp(it) })
                // Solo single-player against Autonomous AI Hunter Bot
                .then(Commands.literal("solo")
                    .executes { startSoloDefault(it) }
                    .then(Commands.argument("difficulty", StringArgumentType.word())
                        .suggests { _, b -> b.suggest("casual"); b.suggest("tactical"); b.suggest("terminator"); b.buildFuture() }
                        .executes { startSoloWithDifficulty(it) }
                        .then(Commands.argument("preset", StringArgumentType.word())
                            .suggests { _, b -> b.suggest("classic"); b.suggest("hitman"); b.suggest("assassin"); b.suggest("nether_start"); b.buildFuture() }
                            .executes { startSoloWithDiffAndPreset(it) })))
                // Create game
                .then(Commands.literal("create")
                    .executes { createGame(it) }
                    .then(Commands.argument("difficulty", StringArgumentType.word())
                        .suggests { _, b -> b.suggest("casual"); b.suggest("tactical"); b.suggest("terminator"); b.buildFuture() }
                        .executes { createGameWithDifficulty(it) }
                        .then(Commands.argument("preset", StringArgumentType.word())
                            .suggests { _, b -> b.suggest("classic"); b.suggest("hitman"); b.suggest("assassin"); b.suggest("nether_start"); b.buildFuture() }
                            .executes { createGameWithDiffAndPreset(it) })))
                // Preset selector
                .then(Commands.literal("preset")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests { _, b -> b.suggest("classic"); b.suggest("hitman"); b.suggest("assassin"); b.suggest("nether_start"); b.buildFuture() }
                        .executes { setPreset(it) }))
                // Difficulty selector
                .then(Commands.literal("difficulty")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests { _, b -> b.suggest("casual"); b.suggest("tactical"); b.suggest("terminator"); b.buildFuture() }
                        .executes { setDifficultyByName(it) }))
                // Compass command
                .then(Commands.literal("compass").executes { giveCompass(it) })
                // Splits overview
                .then(Commands.literal("splits").executes { showSplits(it) })
                // Join / Leave / Start / Stop
                .then(Commands.literal("join")
                    .then(Commands.argument("gameId", StringArgumentType.word())
                        .suggests { _, b ->
                            ManhuntManager.getActiveGameIds().forEach { b.suggest(it) }
                            b.buildFuture()
                        }
                        .executes { joinGame(it, ManhuntRole.SPEEDRUNNER) }
                        .then(Commands.literal("hunter").executes { joinGame(it, ManhuntRole.HUNTER) })
                        .then(Commands.literal("runner").executes { joinGame(it, ManhuntRole.SPEEDRUNNER) })))
                .then(Commands.literal("leave").executes { leaveGame(it) })
                .then(Commands.literal("start").executes { startGame(it) })
                .then(Commands.literal("stop")
                    .requires(CAN_MANAGE)
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
                    .requires(CAN_MANAGE)
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
                    .requires(CAN_MANAGE)
                    .executes { teleportHunter(it) })
                .then(Commands.literal("giveblocks")
                    .requires(CAN_MANAGE)
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                        .executes { giveHunterBlocks(it) }))
                .then(Commands.literal("info").executes { showHunterInfo(it) })
        )

        dispatcher.register(
            Commands.literal("mh").redirect(dispatcher.root.getChild("manhunt"))
        )
    }

    private fun openSetupGui(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player ?: return 0
        Services.load(INetworkHelper::class.java).sendOpenGui(player)
        return 1
    }

    private fun showHelp(ctx: CommandContext<CommandSourceStack>): Int {
        val helpText = """
            §6§l══════════════ MANHUNT HELP ══════════════
            §e/manhunt solo [difficulty] [preset]§7 - Start solo game vs AI Hunter Bot
            §e/manhunt create [difficulty] [preset]§7 - Create multiplayer game
            §e/manhunt preset <classic|hitman|assassin|nether_start>§7 - Choose preset
            §e/manhunt difficulty <casual|tactical|terminator>§7 - Choose AI difficulty
            §e/manhunt join <id> [hunter|runner]§7 - Join active game
            §e/manhunt compass§7 - Get Smart Tracking Compass
            §e/manhunt splits§7 - View speedrun split timestamps
            §e/manhunt start§7 / §e/manhunt stop§7 - Control session
        """.trimIndent()
        ctx.source.sendSuccess({ Component.literal(helpText) }, false)
        return 1
    }

    private fun startSoloDefault(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        ManhuntManager.createSoloGame(player.level() as net.minecraft.server.level.ServerLevel, player, HunterDifficulty.TACTICAL, ManhuntPreset.CLASSIC)
        return 1
    }

    private fun startSoloWithDifficulty(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val diffName = StringArgumentType.getString(ctx, "difficulty")
        val diff = HunterDifficulty.fromId(diffName)
        ManhuntManager.createSoloGame(player.level() as net.minecraft.server.level.ServerLevel, player, diff, ManhuntPreset.CLASSIC)
        return 1
    }

    private fun startSoloWithDiffAndPreset(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val diff = HunterDifficulty.fromId(StringArgumentType.getString(ctx, "difficulty"))
        val preset = ManhuntPreset.fromId(StringArgumentType.getString(ctx, "preset"))
        ManhuntManager.createSoloGame(player.level() as net.minecraft.server.level.ServerLevel, player, diff, preset)
        return 1
    }

    private fun createGame(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val settings = pendingSettings.getOrPut(player.uuid) { ManhuntSettings() }
        val game = ManhuntManager.createGame(player.level() as net.minecraft.server.level.ServerLevel, player, settings) ?: return 0
        ManhuntManager.joinGame(player, game.id)
        player.sendSystemMessage(Component.literal("§a[Manhunt] §7Game created! ID: §e${game.id}"))
        pendingSettings.remove(player.uuid)
        return 1
    }

    private fun createGameWithDifficulty(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val diff = HunterDifficulty.fromId(StringArgumentType.getString(ctx, "difficulty"))
        val settings = pendingSettings.getOrPut(player.uuid) { ManhuntSettings() }
        settings.hunterDifficulty = diff
        settings.difficulty = diff.damageMultiplier.toFloat()
        settings.headStartSeconds = diff.headStartSeconds
        return createGame(ctx)
    }

    private fun createGameWithDiffAndPreset(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val diff = HunterDifficulty.fromId(StringArgumentType.getString(ctx, "difficulty"))
        val preset = ManhuntPreset.fromId(StringArgumentType.getString(ctx, "preset"))
        val settings = pendingSettings.getOrPut(player.uuid) { ManhuntSettings() }
        preset.applyTo(settings, diff)
        return createGame(ctx)
    }

    private fun setPreset(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val preset = ManhuntPreset.fromId(StringArgumentType.getString(ctx, "name"))
        val settings = pendingSettings.getOrPut(player.uuid) { ManhuntSettings() }
        preset.applyTo(settings, settings.hunterDifficulty)
        player.sendSystemMessage(Component.literal("§a[Manhunt] §7Preset set to: ${preset.badge} §f${preset.displayName}"))
        return 1
    }

    private fun setDifficultyByName(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val diff = HunterDifficulty.fromId(StringArgumentType.getString(ctx, "name"))
        val settings = pendingSettings.getOrPut(player.uuid) { ManhuntSettings() }
        settings.hunterDifficulty = diff
        settings.difficulty = diff.damageMultiplier.toFloat()
        settings.headStartSeconds = diff.headStartSeconds
        player.sendSystemMessage(Component.literal("§a[Manhunt] §7Hunter difficulty set to: ${diff.colorCode}${diff.displayName}"))
        return 1
    }

    private fun giveCompass(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = ManhuntManager.getPlayerGame(player.uuid)
        val target = game?.getSpeedrunnerPlayer() ?: player
        val compass = SmartTrackingCompass.createCompass(target, game)
        player.inventory.add(compass)
        player.sendSystemMessage(Component.literal("§b[Manhunt] §7Smart Tracking Compass added to your inventory."))
        return 1
    }

    private fun showSplits(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = ManhuntManager.getPlayerGame(player.uuid)
        if (game == null) {
            player.sendSystemMessage(Component.literal("§c[Manhunt] §7You are not in an active game."))
            return 0
        }

        player.sendSystemMessage(Component.literal("§6§l══════════ SPEEDRUN SPLITS ══════════"))
        for (split in game.splits.getAllSplits()) {
            player.sendSystemMessage(Component.literal(
                "${split.type.colorCode}${split.type.icon} ${split.type.displayName}§f: §a${split.formattedSplitTime} §7(+§e${split.segmentDurationFormatted}§7)"
            ))
        }
        return 1
    }

    private fun joinGame(ctx: CommandContext<CommandSourceStack>, role: ManhuntRole): Int {
        val player = ctx.source.playerOrException
        val gameId = StringArgumentType.getString(ctx, "gameId")
        return if (ManhuntManager.joinGame(player, gameId, role)) 1 else 0
    }

    private fun leaveGame(ctx: CommandContext<CommandSourceStack>): Int {
        return if (ManhuntManager.leaveGame(ctx.source.playerOrException)) 1 else 0
    }

    private fun startGame(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = ManhuntManager.getPlayerGame(player.uuid) ?: return 0
        return if (ManhuntManager.startGame(game.id)) 1 else 0
    }

    private fun stopGame(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
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
        if (ids.isEmpty()) {
            ctx.source.sendSuccess({ Component.literal("§e[Manhunt] §7No active games.") }, false)
            return 1
        }
        ctx.source.sendSuccess({ Component.literal("§6§l═══════ ACTIVE MANHUNT GAMES ═══════") }, false)
        ids.forEach { id ->
            val game = ManhuntManager.getGame(id) ?: return@forEach
            ctx.source.sendSuccess({ Component.literal("§7$id - §a${game.players.size} players §7[${game.settings.preset.badge}§7]") }, false)
        }
        return 1
    }

    private fun showGameInfo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = ManhuntManager.getPlayerGame(player.uuid) ?: return 0
        val info = """
            §6§l═══════ GAME INFO ═══════
            §7Game ID: §e${game.id}
            §7Status: §a${game.state}
            §7Preset: ${game.settings.preset.badge} §f${game.settings.preset.displayName}
            §7Difficulty: ${game.getDifficultyName()}
            §7Elapsed: §e${game.getElapsedTimeFormatted()}
        """.trimIndent()
        player.sendSystemMessage(Component.literal(info))
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
        ctx.source.sendSuccess({ Component.literal("§6§l═══════ PENDING SETTINGS ═══════\n§7Difficulty: ${settings.hunterDifficulty.displayName}\n§7Preset: ${settings.preset.displayName}") }, false)
        return 1
    }

    private fun quickStart(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val game = ManhuntManager.createSoloGame(player.level() as net.minecraft.server.level.ServerLevel, player, HunterDifficulty.TACTICAL, ManhuntPreset.CLASSIC)
        return if (game != null) 1 else 0
    }

    private fun quickStartWithPlayers(ctx: CommandContext<CommandSourceStack>): Int {
        val creator = ctx.source.playerOrException
        val game = ManhuntManager.createGame(creator.level() as net.minecraft.server.level.ServerLevel, creator, ManhuntSettings.normal()) ?: return 0
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

    private fun teleportHunter(ctx: CommandContext<CommandSourceStack>): Int = 0

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
