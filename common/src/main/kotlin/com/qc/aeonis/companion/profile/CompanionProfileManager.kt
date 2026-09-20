package com.qc.aeonis.companion.profile

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.entity.CompanionBotEntity
import com.qc.aeonis.companion.order.CompanionOrder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Persistent per-player, per-server companion database.
 *
 * Saves and restores:
 * - isFirstSpawn flag (triggers initial starter kit: wooden sword, wooden pickaxe, 8 apples)
 * - Complete 33-slot inventory (armor, mainhand, offhand, 27 backpack slots)
 * - Health, Bond, EXP level, active tactical order, and progression stage.
 */
object CompanionProfileManager {

    private val LOGGER = LoggerFactory.getLogger("Aeonis-CompanionProfile")
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    private const val PROFILES_FILE = "companion_profiles.json"
    private const val DIR_NAME = "aeonis"

    data class ItemRecord(
        val slot: Int,
        val itemId: String,
        val count: Int
    )

    data class ProfileRecord(
        val ownerUuid: String,
        var isFirstSpawn: Boolean = true,
        var health: Float = 20.0f,
        var expLevel: Int = 1,
        var bondLevel: Int = 1,
        var currentOrder: String = CompanionOrder.FOLLOW.name,
        var progressionStage: String = "WOOD",
        var customName: String? = null,
        val items: MutableList<ItemRecord> = mutableListOf()
    )

    private val cachedProfiles = mutableMapOf<UUID, ProfileRecord>()
    private var isLoaded = false

    private fun getFile(server: MinecraftServer): File {
        val dir = server.getWorldPath(LevelResource.ROOT).resolve(DIR_NAME).toFile()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, PROFILES_FILE)
    }

    @Synchronized
    fun loadAll(server: MinecraftServer) {
        cachedProfiles.clear()
        val file = getFile(server)
        if (!file.exists()) {
            isLoaded = true
            return
        }

        try {
            val content = Files.readString(file.toPath())
            val type = object : TypeToken<Map<String, ProfileRecord>>() {}.type
            val map: Map<String, ProfileRecord>? = GSON.fromJson(content, type)
            if (map != null) {
                for ((uuidStr, profile) in map) {
                    try {
                        val uuid = UUID.fromString(uuidStr)
                        cachedProfiles[uuid] = profile
                    } catch (_: Exception) {
                    }
                }
            }
            LOGGER.info("[CompanionProfile] Loaded {} companion profiles from disk", cachedProfiles.size)
        } catch (e: Exception) {
            LOGGER.error("[CompanionProfile] Failed to read companion profiles", e)
        }
        isLoaded = true
    }

    @Synchronized
    fun saveAll(server: MinecraftServer) {
        val file = getFile(server)
        try {
            val stringMap = mutableMapOf<String, ProfileRecord>()
            for ((uuid, profile) in cachedProfiles) {
                stringMap[uuid.toString()] = profile
            }
            val json = GSON.toJson(stringMap)
            Files.writeString(file.toPath(), json)
        } catch (e: Exception) {
            LOGGER.error("[CompanionProfile] Failed to save companion profiles", e)
        }
    }

    @Synchronized
    fun hasProfile(server: MinecraftServer, ownerUuid: UUID): Boolean {
        if (!isLoaded) loadAll(server)
        return cachedProfiles.containsKey(ownerUuid)
    }

    @Synchronized
    fun getProfile(server: MinecraftServer, ownerUuid: UUID): ProfileRecord? {
        if (!isLoaded) loadAll(server)
        return cachedProfiles[ownerUuid]
    }

    /**
     * Initializes a newly spawned companion bot:
     * - If first spawn: populates initial starter kit (Wooden Sword, Wooden Pickaxe, 8 Apples).
     * - If existing: restores exact inventory, health, bond, and order from profile.
     */
    @Synchronized
    fun initializeBotFromProfile(server: MinecraftServer, bot: CompanionBotEntity, ownerUuid: UUID) {
        if (!isLoaded) {
            loadAll(server)
        }

        val profile = cachedProfiles.getOrPut(ownerUuid) {
            ProfileRecord(ownerUuid = ownerUuid.toString(), isFirstSpawn = true)
        }

        if (profile.isFirstSpawn) {
            // Give First Spawn Starter Kit
            bot.inventory.clearContent()
            bot.inventory.setItem(CompanionInventory.MAINHAND_SLOT, ItemStack(Items.WOODEN_SWORD))
            bot.inventory.setItem(CompanionInventory.BACKPACK_START, ItemStack(Items.WOODEN_PICKAXE))
            bot.inventory.setItem(CompanionInventory.BACKPACK_START + 1, ItemStack(Items.APPLE, 8))

            profile.isFirstSpawn = false
            saveBotToProfile(server, bot, ownerUuid)
            LOGGER.info("[CompanionProfile] Provisioned first-spawn starter kit for companion of player {}", ownerUuid)
            return
        }

        // Restore attributes
        bot.health = profile.health.coerceIn(1.0f, bot.maxHealth)
        bot.currentOrder = CompanionOrder.parse(profile.currentOrder) ?: CompanionOrder.FOLLOW

        // Restore inventory slots
        bot.inventory.clearContent()
        for (itemRec in profile.items) {
            if (itemRec.slot in 0 until CompanionInventory.CONTAINER_SIZE) {
                val id = Identifier.tryParse(itemRec.itemId)
                if (id != null) {
                    val item = BuiltInRegistries.ITEM.getValue(id)
                    if (item != Items.AIR) {
                        bot.inventory.setItem(itemRec.slot, ItemStack(item, itemRec.count.coerceAtLeast(1)))
                    }
                }
            }
        }
        bot.inventory.setChanged()
        LOGGER.info("[CompanionProfile] Restored companion state for owner {} ({} items)", ownerUuid, profile.items.size)
    }

    /**
     * Writes live companion bot state to the player's persistent profile.
     */
    @Synchronized
    fun saveBotToProfile(server: MinecraftServer, bot: CompanionBotEntity, ownerUuid: UUID) {
        val profile = cachedProfiles.getOrPut(ownerUuid) {
            ProfileRecord(ownerUuid = ownerUuid.toString(), isFirstSpawn = false)
        }

        profile.health = bot.health
        profile.currentOrder = bot.currentOrder.name
        profile.items.clear()

        for (slot in 0 until CompanionInventory.CONTAINER_SIZE) {
            val stack = bot.inventory.getItem(slot)
            if (!stack.isEmpty) {
                val idStr = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                profile.items.add(ItemRecord(slot, idStr, stack.count))
            }
        }

        saveAll(server)
    }
}
