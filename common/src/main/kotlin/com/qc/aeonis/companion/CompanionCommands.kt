package com.qc.aeonis.companion

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import com.qc.aeonis.companion.order.CompanionOrder
import com.qc.aeonis.companion.order.WeaponSelector
import com.qc.aeonis.entity.CompanionBotEntity
import com.qc.aeonis.item.AeonisItems
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * `/companion` (aliased `/comp`) — the player's command surface over the companion.
 *
 * ### Orders are the whole interface
 * Every mode command now routes through [`CompanionBotEntity.currentOrder`], so there is one
 * definition of what the companion has been told to do. The legacy `/comp mode <x>` spellings are
 * kept as aliases because existing players' muscle memory and the mod's own docs use them, but
 * they resolve to the same orders — `mode attack` is `CompanionOrder.ATTACK`, `mode retreat` is
 * `CompanionOrder.RETREAT`, and so on. Two spellings, one state.
 */
object CompanionCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val root = Commands.literal("companion")
            .requires { it.player != null }
            .then(Commands.literal("spawn").executes { spawn(it) })
            .then(Commands.literal("comehere").executes { comeHere(it) })
            .then(Commands.literal("stats").executes { stats(it) })
            .then(Commands.literal("status").executes { stats(it) })
            .then(Commands.literal("dismiss").executes { dismiss(it) })
            .then(Commands.literal("gui").executes { openGui(it) })

            // Direct order commands: the primary interface.
            .then(Commands.literal("free").executes { setOrder(it, CompanionOrder.FREE) })
            .then(Commands.literal("follow").executes { setOrder(it, CompanionOrder.FOLLOW) })
            .then(Commands.literal("hold").executes { setOrder(it, CompanionOrder.HOLD) })
            .then(Commands.literal("stay").executes { setOrder(it, CompanionOrder.HOLD) })
            .then(Commands.literal("guard").executes { setOrder(it, CompanionOrder.GUARD) })
            .then(Commands.literal("hunt").executes { setOrder(it, CompanionOrder.HUNT) })
            .then(Commands.literal("attack").executes { setOrder(it, CompanionOrder.ATTACK) })
            .then(Commands.literal("retreat").executes { setOrder(it, CompanionOrder.RETREAT) })

            // Generic order setter for tab-completion-friendly discovery.
            .then(
                Commands.literal("order")
                    .then(
                        Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests { _, builder ->
                                CompanionOrder.PLAYER_ORDERS.forEach { builder.suggest(it.name.lowercase()) }
                                builder.buildFuture()
                            }
                            .executes { context ->
                                val raw = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name")
                                val order = CompanionOrder.parse(raw)
                                if (order == null) {
                                    context.source.playerOrException.sendSystemMessage(
                                        Component.literal(
                                            "§c[Companion] §7Unknown order '§e$raw§7'. Try one of: " +
                                                CompanionOrder.PLAYER_ORDERS.joinToString(", ") { it.name.lowercase() }
                                        )
                                    )
                                    0
                                } else {
                                    setOrder(context, order)
                                }
                            }
                    )
            )

            // Legacy aliases, mapping onto the same orders.
            .then(
                Commands.literal("mode")
                    .then(Commands.literal("follow").executes { setOrder(it, CompanionOrder.FOLLOW) })
                    .then(Commands.literal("attack").executes { setOrder(it, CompanionOrder.ATTACK) })
                    .then(Commands.literal("retreat").executes { setOrder(it, CompanionOrder.RETREAT) })
                    .then(Commands.literal("idle").executes { setOrder(it, CompanionOrder.FREE) })
            )
            .executes { help(it) }

        val node = dispatcher.register(root)
        dispatcher.register(Commands.literal("comp").redirect(node))
    }

    /** Applies an order and tells the player what the companion just gave up to obey it. */
    private fun setOrder(ctx: CommandContext<CommandSourceStack>, order: CompanionOrder): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val bot = CompanionBotManager.getBot(player)
        if (bot == null) {
            player.sendSystemMessage(Component.literal("§c[Companion] §7No helper bot found. Use §e/comp spawn§7."))
            return 0
        }

        val previous = bot.currentOrder
        bot.currentOrder = order

        // Release anything in flight immediately, so the acknowledgement is true the moment the
        // player reads it rather than after the current errand finishes.
        if (order.preemptsAutonomy) {
            bot.skillManager.stopActiveSkill("player order ${order.name}")
            bot.navigation.stop()
            bot.target = null
            bot.scaffoldingEngine.cancelAscent()
            // Drop any half-dug block too. Leaving progress on the old target is how a companion
            // previously "came back and carried on" after being told to do something else.
            bot.miningSession.reset()
        }

        val suffix = if (order.preemptsAutonomy) " §8(autonomy suspended)" else " §8(autonomy allowed)"
        player.sendSystemMessage(
            Component.literal("§6[Rhistel] §7Companion order: §e${order.label}§7 (§8${previous.name}§7 → §8${order.name}§7)$suffix")
        )
        return 1
    }

    private fun openGui(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val bot = CompanionBotManager.getBot(player)
        if (bot == null) {
            player.sendSystemMessage(Component.literal("§c[Companion] §7No helper bot found. Use §e/comp spawn§7."))
            return 0
        }
        bot.openCustomInventory(player)
        return 1
    }

    private fun spawn(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val bot = CompanionBotManager.spawnOrReplace(player)
        if (bot == null) {
            player.sendSystemMessage(Component.literal("§c[Companion] §7Failed to spawn helper bot."))
            return 0
        }

        player.sendSystemMessage(Component.literal("§a[Companion] §7Helper bot spawned and bound to you."))
        val hasWhistle = (0 until player.inventory.containerSize).any { slot ->
            player.inventory.getItem(slot).`is`(AeonisItems.RHISTEL.get())
        }
        if (!hasWhistle) {
            player.inventory.add(ItemStack(AeonisItems.RHISTEL.get()))
            player.sendSystemMessage(
                Component.literal("§6[Rhistel] §7You received a companion whistle. Right-click the air to open its panel; §eShift+Right-click§7 to ping a target.")
            )
        }
        return 1
    }

    private fun comeHere(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        return if (CompanionBotManager.recall(player)) {
            player.sendSystemMessage(Component.literal("§a[Companion] §7Come here command sent."))
            1
        } else {
            player.sendSystemMessage(Component.literal("§c[Companion] §7No helper bot found. Use §e/comp spawn§7."))
            0
        }
    }

    /**
     * Full status: health, current order, weapon, progression stage and what autonomy is doing.
     * This is the diagnostic a player reaches for when the companion "won't do what I want", so it
     * reports every layer that can override behaviour, in override order.
     */
    private fun stats(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        val bot = CompanionBotManager.getBot(player)
        if (bot == null) {
            player.sendSystemMessage(Component.literal("§c[Companion] §7No helper bot found. Use §e/comp spawn§7."))
            return 0
        }

        val skills = bot.skillManager
        val verdict = skills.lastVerdict

        player.sendSystemMessage(Component.literal("§6§l═══════ COMPANION STATUS ═══════"))
        player.sendSystemMessage(
            Component.literal("§7Health: §c${"%.1f".format(bot.health)}§7/§c${"%.1f".format(bot.maxHealth)}  §7Strength: §a${bot.getStrengthSummary()}")
        )
        player.sendSystemMessage(
            Component.literal("§7Order: §e${bot.currentOrder.label}§7 (${bot.currentOrder.name})${if (bot.currentOrder.preemptsAutonomy) " §8— autonomy suspended" else ""}")
        )
        player.sendSystemMessage(Component.literal("§7Weapon: §f${WeaponSelector.describeLoadout(bot)}"))
        player.sendSystemMessage(
            Component.literal("§7Governor: §b${verdict?.mode?.name ?: "unevaluated"}§7${verdict?.let { " §8(${it.reason})" } ?: ""}")
        )
        player.sendSystemMessage(Component.literal("§7Progression: §a${skills.progressionLoop.describeProgression()}"))
        player.sendSystemMessage(
            Component.literal("§7Active job: §f${skills.activeSkillId() ?: "idle"}§7 (${skills.activeSkillTicks}t)§7${if (skills.lastSkillMessage.isNotEmpty()) " §8— ${skills.lastSkillMessage}" else ""}")
        )
        player.sendSystemMessage(Component.literal("§7Last hand-off: §8${skills.lastSwitchReason}"))

        val mining = bot.miningSession
        val mineTarget = mining.targetPos
        val miningLine = if (mineTarget != null) {
            "digging ${mineTarget.toShortString()} ${mining.progressPercent()}%" +
                " (${mining.ticksSpent}t, broke ${mining.blocksBroken})"
        } else {
            "idle (broke ${mining.blocksBroken} this session)"
        }
        player.sendSystemMessage(Component.literal("§7Mining: §f$miningLine"))
        player.sendSystemMessage(
            Component.literal("§7Crafting table: §f${skills.craftingTableUnlock.describe()}")
        )
        return 1
    }

    private fun dismiss(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        return if (CompanionBotManager.dismiss(player)) {
            player.sendSystemMessage(Component.literal("§e[Companion] §7Helper bot dismissed."))
            1
        } else {
            player.sendSystemMessage(Component.literal("§c[Companion] §7No helper bot to dismiss."))
            0
        }
    }

    private fun help(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as ServerPlayer
        player.sendSystemMessage(Component.literal("§6§lCompanion Commands"))
        player.sendSystemMessage(Component.literal("§e/comp spawn §7- Spawn or replace your helper bot"))
        player.sendSystemMessage(Component.literal("§e/comp comehere §7- Recall the bot to you"))
        player.sendSystemMessage(Component.literal("§e/comp status §7- Health, order, weapon, progression and active work"))
        player.sendSystemMessage(Component.literal("§e/comp gui §7- Open the companion's inventory panel"))
        player.sendSystemMessage(Component.literal("§e/comp dismiss §7- Remove your helper bot"))
        player.sendSystemMessage(Component.literal("§6§lOrders §7(§8higher wins; HOLD/FOLLOW/RETREAT suspend autonomy§7)"))
        player.sendSystemMessage(Component.literal("§e/comp hold §7- Stand your ground where you are"))
        player.sendSystemMessage(Component.literal("§e/comp follow §7- Stay with you"))
        player.sendSystemMessage(Component.literal("§e/comp retreat §7- Break off and run"))
        player.sendSystemMessage(Component.literal("§e/comp guard §7- Work the area around this spot"))
        player.sendSystemMessage(Component.literal("§e/comp hunt §7- Allow autonomous hunting and gathering"))
        player.sendSystemMessage(Component.literal("§e/comp attack §7- Engage visible threats"))
        player.sendSystemMessage(Component.literal("§e/comp free §7- Full autonomy (default)"))
        player.sendSystemMessage(Component.literal("§e/comp order <name> §7- Set any order by name"))
        return 1
    }

    /** Exposed for the client-side manual so the two never drift apart. */
    fun orderSummaries(): List<Pair<String, String>> =
        CompanionOrder.PLAYER_ORDERS.map { it.name.lowercase() to it.label }

    /** Convenience for other systems that need to describe the bot's current instruction. */
    fun describeOrder(bot: CompanionBotEntity): String =
        "${bot.currentOrder.label} (${bot.currentOrder.name})"
}
