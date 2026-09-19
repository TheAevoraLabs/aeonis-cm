package com.qc.aeonis.companion

import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CompanionBotManager {
    private data class BotRecord(
        var entityId: Int? = null,
        var entityUuid: UUID? = null,
        var respawnCooldown: Int = 0,
        var lastKnownDimension: ResourceKey<Level>? = null,
        var snapshot: CompanionBotEntity.Companion.CompanionSnapshot? = null,
        var savedInventory: List<ItemStack>? = null,
        var hasSavedOfflineBot: Boolean = false
    )

    private val ownerBots = ConcurrentHashMap<UUID, BotRecord>()

    fun register() {}

    private fun saveBotState(record: BotRecord, bot: CompanionBotEntity) {
        record.snapshot = bot.createSnapshot()
        record.savedInventory = (0 until CompanionInventory.CONTAINER_SIZE).map {
            bot.inventory.getItem(it).copy()
        }
        record.lastKnownDimension = bot.level().dimension()
    }

    private fun restoreBotState(record: BotRecord, bot: CompanionBotEntity) {
        record.snapshot?.let { bot.applySnapshot(it) }
        record.savedInventory?.let { stacks ->
            stacks.forEachIndexed { index, stack ->
                if (index < CompanionInventory.CONTAINER_SIZE) {
                    bot.inventory.setItem(index, stack.copy())
                }
            }
        }
    }

    fun spawnOrReplace(owner: ServerPlayer): CompanionBotEntity? {
        val record = ownerBots.computeIfAbsent(owner.uuid) { BotRecord() }
        val level = owner.level()
        val server = level.server

        findTrackedBot(server, record)?.let { existing ->
            if (existing.isAlive) {
                saveBotState(record, existing)
            }
            existing.discard()
        }

        val spawnPos = owner.blockPosition()
        val bot = CompanionBotEntity.spawn(level, spawnPos, owner) ?: return null
        restoreBotState(record, bot)

        record.entityId = bot.id
        record.entityUuid = bot.uuid
        record.respawnCooldown = 0
        record.lastKnownDimension = level.dimension()
        record.hasSavedOfflineBot = false
        return bot
    }

    fun getBot(owner: ServerPlayer): CompanionBotEntity? {
        val record = ownerBots[owner.uuid] ?: return null
        val level = owner.level()
        return findTrackedBot(level.server, record)
    }

    fun recall(owner: ServerPlayer): Boolean {
        val bot = getBot(owner) ?: return false
        bot.recallNow()
        return true
    }

    fun dismiss(owner: ServerPlayer): Boolean {
        val record = ownerBots.remove(owner.uuid) ?: return false
        val level = owner.level()
        val bot = findTrackedBot(level.server, record)
        bot?.discard()
        return true
    }

    /**
     * Called when a player logs out or disconnects from the server.
     * Safely preserves runtime state and full inventory, then discards the world entity
     * to prevent corruption, rogue AI behavior, or headless surrogate desyncs while offline.
     */
    fun onPlayerDisconnect(player: ServerPlayer) {
        val record = ownerBots[player.uuid] ?: return
        val server = player.level().server
        val bot = findTrackedBot(server, record)
        if (bot != null && bot.isAlive) {
            saveBotState(record, bot)
            record.hasSavedOfflineBot = true
            bot.discard()
        }
    }

    /**
     * Called when a player logs into the server.
     * Automatically restores and respawns the companion with full inventory and stats.
     */
    fun onPlayerJoin(player: ServerPlayer) {
        val record = ownerBots[player.uuid] ?: return
        if (record.hasSavedOfflineBot) {
            val level = player.level()
            val spawnPos = player.blockPosition()
            val bot = CompanionBotEntity.spawn(level, spawnPos, player)
            if (bot != null) {
                restoreBotState(record, bot)
                record.entityId = bot.id
                record.entityUuid = bot.uuid
                record.hasSavedOfflineBot = false
                record.lastKnownDimension = level.dimension()
                player.sendSystemMessage(
                    Component.literal("§b[Companion] §7Companion restored and rejoined your side.")
                )
            }
        }
    }

    fun tick(server: MinecraftServer) {
        ownerBots.forEach { (ownerUuid, record) ->
            val owner = server.playerList.getPlayer(ownerUuid)
            if (owner == null) {
                // Owner is offline: ensure any active bot in the world is safely saved & discarded
                val orphanBot = findTrackedBot(server, record)
                if (orphanBot != null && orphanBot.isAlive) {
                    saveBotState(record, orphanBot)
                    record.hasSavedOfflineBot = true
                    orphanBot.discard()
                }
                return@forEach
            }

            // If player recently reconnected and companion is queued to restore
            if (record.hasSavedOfflineBot) {
                onPlayerJoin(owner)
                return@forEach
            }

            val level = owner.level()

            if (record.respawnCooldown > 0) {
                record.respawnCooldown--
                return@forEach
            }

            val bot = findTrackedBot(server, record)
            if (bot != null && bot.isAlive) {
                // Dimension transfer: smoothly migrate bot and preserve full inventory
                if (bot.level() != level) {
                    saveBotState(record, bot)
                    bot.discard()

                    val transferPos = owner.blockPosition()
                    val transferred = CompanionBotEntity.spawn(level, transferPos, owner) ?: return@forEach
                    restoreBotState(record, transferred)
                    record.entityId = transferred.id
                    record.entityUuid = transferred.uuid
                    record.lastKnownDimension = level.dimension()
                    record.respawnCooldown = 20
                    return@forEach
                }

                if (bot.distanceTo(owner) > 64.0) {
                    bot.recallNow()
                }
            } else if (record.entityUuid != null && !record.hasSavedOfflineBot) {
                record.respawnCooldown = 100
            }
        }
    }

    private fun findTrackedBot(server: MinecraftServer, record: BotRecord): CompanionBotEntity? {
        val uuid = record.entityUuid ?: return null
        server.allLevels.forEach { level ->
            val entity = level.getEntity(uuid)
            if (entity is CompanionBotEntity) return entity
        }
        return null
    }
}
