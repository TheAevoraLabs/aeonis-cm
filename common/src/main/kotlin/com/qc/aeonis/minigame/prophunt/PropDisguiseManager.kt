package com.qc.aeonis.minigame.prophunt

import com.qc.aeonis.network.AeonisNetwork
import com.qc.aeonis.util.playNotifySound
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.*
import net.minecraft.world.entity.Display.BlockDisplay
import net.minecraft.world.entity.Display.ItemDisplay
import net.minecraft.world.entity.animal.allay.Allay
import net.minecraft.world.entity.animal.armadillo.Armadillo
import net.minecraft.world.entity.animal.frog.Frog
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.entity.vehicle.minecart.Minecart
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.joml.Vector3fc
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Prop Disguise options. Supports:
 * - Block Display disguises (modern Minecraft 26.3 BlockDisplay)
 * - Item Display disguises (modern Minecraft 26.3 ItemDisplay)
 * - Mob disguises (via Aeonis transform / possession networking)
 */
sealed class PropDisguiseOption {
    abstract val id: String
    abstract val displayName: String

    data class BlockProp(
        override val id: String,
        override val displayName: String,
        val blockState: BlockState
    ) : PropDisguiseOption()

    data class ItemProp(
        override val id: String,
        override val displayName: String,
        val itemStack: ItemStack
    ) : PropDisguiseOption()

    data class MobProp(
        override val id: String,
        override val displayName: String,
        val entityType: EntityType<*>
    ) : PropDisguiseOption()
}

data class DecoyBlockEntry(
    val displayEntityId: Int,
    val collisionEntityId: Int,
    val pos: BlockPos
)

class PropPlayerRuntime(
    val playerUuid: UUID,
    var disguise: PropDisguiseOption,
    var displayEntityId: Int? = null,
    var mobEntityId: Int? = null,
    var collisionEntityId: Int? = null,
    var isLocked: Boolean = false,
    var isFrozen: Boolean = false,
    var lockedBlockPos: BlockPos? = null,
    var lockedYaw: Float = 0f,
    var lockedPitch: Float = 0f,
    var sneakTicks: Int = 0,
    var ticksSinceLastTaunt: Int = 0,
    val activeDecoys: MutableList<DecoyBlockEntry> = mutableListOf()
)

object PropDisguiseManager {
    private val logger = LoggerFactory.getLogger("aeonis-prophunt-props")

    // Reflection accessors for Display entity internals
    private val BLOCK_STATE_ACCESSOR: EntityDataAccessor<BlockState> by lazy {
        val field = BlockDisplay::class.java.getDeclaredField("DATA_BLOCK_STATE_ID")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(null) as EntityDataAccessor<BlockState>
    }

    private val TRANSLATION_ACCESSOR: EntityDataAccessor<Vector3fc> by lazy {
        val field = Display::class.java.getDeclaredField("DATA_TRANSLATION_ID")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(null) as EntityDataAccessor<Vector3fc>
    }

    private val POS_ROT_INTERPOLATION_ACCESSOR: EntityDataAccessor<Int> by lazy {
        val field = Display::class.java.getDeclaredField("DATA_POS_ROT_INTERPOLATION_DURATION_ID")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(null) as EntityDataAccessor<Int>
    }

    private val GLOW_COLOR_ACCESSOR: EntityDataAccessor<Int> by lazy {
        val field = Display::class.java.getDeclaredField("DATA_GLOW_COLOR_OVERRIDE_ID")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(null) as EntityDataAccessor<Int>
    }

    /** Active prop runtime data per player UUID */
    private val propRuntimes = ConcurrentHashMap<UUID, PropPlayerRuntime>()

    // ════════════════════════════════════════════════════════════════════════
    // DISGUISE CATALOGUE
    // ════════════════════════════════════════════════════════════════════════

    val BLOCK_PROPS = listOf(
        PropDisguiseOption.BlockProp("crafting_table", "Crafting Table", Blocks.CRAFTING_TABLE.defaultBlockState()),
        PropDisguiseOption.BlockProp("chest", "Chest", Blocks.CHEST.defaultBlockState()),
        PropDisguiseOption.BlockProp("barrel", "Barrel", Blocks.BARREL.defaultBlockState()),
        PropDisguiseOption.BlockProp("furnace", "Furnace", Blocks.FURNACE.defaultBlockState()),
        PropDisguiseOption.BlockProp("bookshelf", "Bookshelf", Blocks.BOOKSHELF.defaultBlockState()),
        PropDisguiseOption.BlockProp("hay_block", "Hay Bale", Blocks.HAY_BLOCK.defaultBlockState()),
        PropDisguiseOption.BlockProp("melon", "Melon", Blocks.MELON.defaultBlockState()),
        PropDisguiseOption.BlockProp("pumpkin", "Carved Pumpkin", Blocks.CARVED_PUMPKIN.defaultBlockState()),
        PropDisguiseOption.BlockProp("tnt", "TNT", Blocks.TNT.defaultBlockState()),
        PropDisguiseOption.BlockProp("anvil", "Anvil", Blocks.ANVIL.defaultBlockState()),
        PropDisguiseOption.BlockProp("cauldron", "Cauldron", Blocks.CAULDRON.defaultBlockState()),
        PropDisguiseOption.BlockProp("composter", "Composter", Blocks.COMPOSTER.defaultBlockState()),
        PropDisguiseOption.BlockProp("oak_log", "Oak Log", Blocks.OAK_LOG.defaultBlockState()),
        PropDisguiseOption.BlockProp("gold_block", "Gold Block", Blocks.GOLD_BLOCK.defaultBlockState()),
        PropDisguiseOption.BlockProp("diamond_block", "Diamond Block", Blocks.DIAMOND_BLOCK.defaultBlockState()),
        PropDisguiseOption.BlockProp("emerald_block", "Emerald Block", Blocks.EMERALD_BLOCK.defaultBlockState()),
        PropDisguiseOption.BlockProp("beacon", "Beacon", Blocks.BEACON.defaultBlockState()),
        PropDisguiseOption.BlockProp("sponge", "Sponge", Blocks.SPONGE.defaultBlockState()),
        PropDisguiseOption.BlockProp("red_mushroom_block", "Mushroom Block", Blocks.RED_MUSHROOM_BLOCK.defaultBlockState()),
        PropDisguiseOption.BlockProp("target", "Target Block", Blocks.TARGET.defaultBlockState())
    )

    val ITEM_PROPS = listOf(
        PropDisguiseOption.ItemProp("diamond_sword", "Diamond Sword", ItemStack(Items.DIAMOND_SWORD)),
        PropDisguiseOption.ItemProp("golden_apple", "Golden Apple", ItemStack(Items.GOLDEN_APPLE)),
        PropDisguiseOption.ItemProp("totem_of_undying", "Totem of Undying", ItemStack(Items.TOTEM_OF_UNDYING)),
        PropDisguiseOption.ItemProp("enchanted_book", "Enchanted Book", ItemStack(Items.ENCHANTED_BOOK)),
        PropDisguiseOption.ItemProp("shield", "Shield", ItemStack(Items.SHIELD)),
        PropDisguiseOption.ItemProp("clock", "Clock", ItemStack(Items.CLOCK))
    )

    val MOB_PROPS = listOf(
        PropDisguiseOption.MobProp("chicken", "Chicken", EntityTypes.CHICKEN),
        PropDisguiseOption.MobProp("pig", "Pig", EntityTypes.PIG),
        PropDisguiseOption.MobProp("sheep", "Sheep", EntityTypes.SHEEP),
        PropDisguiseOption.MobProp("cow", "Cow", EntityTypes.COW),
        PropDisguiseOption.MobProp("cat", "Cat", EntityTypes.CAT),
        PropDisguiseOption.MobProp("wolf", "Wolf", EntityTypes.WOLF),
        PropDisguiseOption.MobProp("fox", "Fox", EntityTypes.FOX),
        PropDisguiseOption.MobProp("frog", "Frog", EntityTypes.FROG),
        PropDisguiseOption.MobProp("rabbit", "Rabbit", EntityTypes.RABBIT),
        PropDisguiseOption.MobProp("armor_stand", "Armor Stand", EntityTypes.ARMOR_STAND)
    )

    fun getAllDisguises(): List<PropDisguiseOption> {
        return BLOCK_PROPS + ITEM_PROPS + MOB_PROPS
    }

    fun findDisguise(name: String): PropDisguiseOption? {
        val clean = name.lowercase().replace("minecraft:", "").trim()
        return getAllDisguises().find { it.id.equals(clean, ignoreCase = true) }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DISGUISE APPLICATION & REMOVAL
    // ════════════════════════════════════════════════════════════════════════

    fun assignRandomDisguise(player: ServerPlayer, game: PropHuntGame) {
        // Weighted selection: 70% blocks, 15% items, 15% mobs
        val roll = Math.random()
        val chosen = when {
            roll < 0.70 -> BLOCK_PROPS.random()
            roll < 0.85 -> ITEM_PROPS.random()
            else -> MOB_PROPS.random()
        }
        applyDisguise(player, chosen, game)
    }

    fun chooseDisguise(player: ServerPlayer, typeName: String, game: PropHuntGame): Boolean {
        val disguise = findDisguise(typeName)
        if (disguise == null) {
            val available = getAllDisguises().joinToString(", ") { it.id }
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7Unknown disguise: §e$typeName"))
            player.sendSystemMessage(Component.literal("§7Available: §e$available"))
            return false
        }
        applyDisguise(player, disguise, game)
        return true
    }

    fun applyDisguise(player: ServerPlayer, disguise: PropDisguiseOption, game: PropHuntGame) {
        val level = player.level() as? ServerLevel ?: return
        val playerData = game.players[player.uuid] ?: return

        // Undisguise any existing entity first
        removeDisguise(player)

        // Clear and give prop utility items
        player.inventory.clearContent()
        givePropItems(player, game.settings)

        // Set player state
        player.setGameMode(GameType.SURVIVAL)
        player.isInvisible = true

        val runtime = PropPlayerRuntime(player.uuid, disguise)

        when (disguise) {
            is PropDisguiseOption.BlockProp -> {
                val blockDisplay = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND)
                if (blockDisplay != null) {
                    blockDisplay.snapTo(player.x, player.y, player.z, player.yRot, 0f)
                    blockDisplay.entityData.set(BLOCK_STATE_ACCESSOR, disguise.blockState)
                    // Offset by -0.5, 0, -0.5 so it centers perfectly around player position
                    blockDisplay.entityData.set(TRANSLATION_ACCESSOR, Vector3f(-0.5f, 0.0f, -0.5f))
                    blockDisplay.entityData.set(POS_ROT_INTERPOLATION_ACCESSOR, 2)
                    blockDisplay.customName = Component.literal("§8[PH_PROP:${player.uuid}]")
                    blockDisplay.isCustomNameVisible = false
                    level.addFreshEntity(blockDisplay)
                    runtime.displayEntityId = blockDisplay.id
                }
            }

            is PropDisguiseOption.ItemProp -> {
                val itemDisplay = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND)
                if (itemDisplay != null) {
                    itemDisplay.snapTo(player.x, player.y + 0.2, player.z, player.yRot, 0f)
                    itemDisplay.getSlot(0)?.set(disguise.itemStack.copy())
                    itemDisplay.entityData.set(POS_ROT_INTERPOLATION_ACCESSOR, 2)
                    itemDisplay.customName = Component.literal("§8[PH_PROP:${player.uuid}]")
                    itemDisplay.isCustomNameVisible = false
                    level.addFreshEntity(itemDisplay)
                    runtime.displayEntityId = itemDisplay.id
                }
            }

            is PropDisguiseOption.MobProp -> {
                @Suppress("UNCHECKED_CAST")
                val mobEntity = (disguise.entityType as EntityType<Entity>).create(level, EntitySpawnReason.COMMAND)
                if (mobEntity != null) {
                    mobEntity.snapTo(player.x, player.y, player.z, player.yRot, player.xRot)
                    if (mobEntity is Mob) {
                        mobEntity.setNoAi(true)
                        mobEntity.isSilent = true
                    }
                    mobEntity.customName = Component.literal("§8[PH_PROP:${player.uuid}]")
                    mobEntity.isCustomNameVisible = false
                    level.addFreshEntity(mobEntity)
                    runtime.mobEntityId = mobEntity.id
                    AeonisNetwork.setControlledEntity(player, mobEntity.id)
                }
            }
        }

        propRuntimes[player.uuid] = runtime
        playerData.disguiseType = disguise.id

        // Notification and sound
        player.sendSystemMessage(Component.literal("§a[PropHunt] §7Camouflaged as: §e§l${disguise.displayName}"))
        player.sendSystemMessage(Component.literal("§7• §eSneak stationary§7 or use §e/prophunt prop lock§7 to snap to grid!"))
        player.sendSystemMessage(Component.literal("§7• §eRight-click Goat Horn§7 to Whistle (warns hunters)!"))
        player.sendSystemMessage(Component.literal("§7• §eRight-click Armor Stand§7 to place Decoy blocks!"))
        player.playNotifySound(SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.0f, 1.0f)
        spawnTransformParticles(player)
    }

    fun removeDisguise(player: ServerPlayer) {
        val runtime = propRuntimes.remove(player.uuid) ?: return
        val level = player.level() as? ServerLevel ?: return

        // Stop mob control if active
        if (runtime.mobEntityId != null) {
            AeonisNetwork.removeControlledEntity(player)
            level.getEntity(runtime.mobEntityId!!)?.discard()
        }

        // Discard display entity
        runtime.displayEntityId?.let { id ->
            level.getEntity(id)?.discard()
        }

        // Discard solid collision shulker
        runtime.collisionEntityId?.let { id ->
            level.getEntity(id)?.discard()
        }

        // Discard all active decoys
        for (decoy in runtime.activeDecoys) {
            level.getEntity(decoy.displayEntityId)?.discard()
            level.getEntity(decoy.collisionEntityId)?.discard()
        }
        runtime.activeDecoys.clear()
    }

    fun undisguise(player: ServerPlayer) {
        removeDisguise(player)
        player.isInvisible = false
        player.setGlowingTag(false)
        player.removeEffect(MobEffects.SLOWNESS)
        player.removeEffect(MobEffects.JUMP_BOOST)
    }

    // ════════════════════════════════════════════════════════════════════════
    // PROP INVENTORY & HOTBAR
    // ════════════════════════════════════════════════════════════════════════

    private fun givePropItems(player: ServerPlayer, settings: PropHuntSettings) {
        val infoItem = ItemStack(Items.NETHER_STAR)
        infoItem.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§b§lProp Controls"))
        player.inventory.setItem(0, infoItem)

        val lockItem = ItemStack(Items.COMPASS)
        lockItem.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§e§lGrid Lock §7(Right-Click / Sneak)"))
        player.inventory.setItem(1, lockItem)

        val decoyItem = ItemStack(Items.ARMOR_STAND)
        decoyItem.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§6§lDeploy Decoy §7(Right-Click)"))
        decoyItem.count = 3
        player.inventory.setItem(2, decoyItem)

        if (settings.allowPropTaunts) {
            val whistleItem = ItemStack(Items.GOAT_HORN)
            whistleItem.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§d§lWhistle Taunt §7(Right-Click)"))
            player.inventory.setItem(3, whistleItem)
        }

        val freezeItem = ItemStack(Items.BLUE_ICE)
        freezeItem.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§b§lFreeze Position §7(Right-Click)"))
        player.inventory.setItem(4, freezeItem)
    }

    // ════════════════════════════════════════════════════════════════════════
    // SOLID COLLISION LOCK & SNEAK-LOCK
    // ════════════════════════════════════════════════════════════════════════

    fun toggleRotationLock(player: ServerPlayer, game: PropHuntGame) {
        val runtime = propRuntimes[player.uuid] ?: return
        val playerData = game.players[player.uuid] ?: return
        if (!canUsePropAbility(player, game, playerData, "lock")) return

        toggleGridLock(player, game, !runtime.isLocked)
    }

    fun toggleFreeze(player: ServerPlayer, game: PropHuntGame) {
        val runtime = propRuntimes[player.uuid] ?: return
        val playerData = game.players[player.uuid] ?: return
        if (!canUsePropAbility(player, game, playerData, "freeze")) return

        runtime.isFrozen = !runtime.isFrozen
        playerData.movementFrozen = runtime.isFrozen

        if (runtime.isFrozen) {
            player.addEffect(MobEffectInstance(MobEffects.SLOWNESS, Int.MAX_VALUE, 255, false, false, false))
            player.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, Int.MAX_VALUE, 128, false, false, false))
            player.sendSystemMessage(Component.literal("§b[PropHunt] §7Movement §cFROZEN§7."))
            player.playNotifySound(SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 0.5f, 1.0f)
            spawnFreezeParticles(player)
        } else {
            if (!runtime.isLocked) {
                player.removeEffect(MobEffects.SLOWNESS)
                player.removeEffect(MobEffects.JUMP_BOOST)
            }
            player.sendSystemMessage(Component.literal("§b[PropHunt] §7Movement §aUNFROZEN§7."))
            player.playNotifySound(SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.5f, 1.2f)
        }
    }

    /**
     * Toggles solid collision grid lock.
     * Snaps player & disguise to exact voxel grid and spawns an invisible Shulker
     * so hunters physically collide with it and swords strike it like a solid block!
     */
    fun toggleGridLock(player: ServerPlayer, game: PropHuntGame, lock: Boolean) {
        val runtime = propRuntimes[player.uuid] ?: return
        val playerData = game.players[player.uuid] ?: return
        val level = player.level() as? ServerLevel ?: return

        if (lock) {
            runtime.isLocked = true
            playerData.rotationLocked = true

            // Snap position to exact block grid center
            val gridPos = player.blockPosition()
            runtime.lockedBlockPos = gridPos
            val snapX = gridPos.x + 0.5
            val snapY = gridPos.y.toDouble()
            val snapZ = gridPos.z + 0.5
            val snapYaw = (Math.round(player.yRot / 90f) * 90f) % 360f

            runtime.lockedYaw = snapYaw
            runtime.lockedPitch = 0f
            playerData.lockedYaw = snapYaw
            playerData.lockedPitch = 0f

            // Teleport player and freeze position
            player.teleportTo(snapX, snapY, snapZ)
            player.setYRot(snapYaw)
            player.setXRot(0f)
            player.addEffect(MobEffectInstance(MobEffects.SLOWNESS, Int.MAX_VALUE, 255, false, false, false))
            player.addEffect(MobEffectInstance(MobEffects.JUMP_BOOST, Int.MAX_VALUE, 128, false, false, false))

            // Align Display Entity
            runtime.displayEntityId?.let { id ->
                val display = level.getEntity(id)
                display?.teleportTo(snapX, snapY, snapZ)
                display?.setYRot(snapYaw)
                display?.setXRot(0f)
            }

            // Align Mob Entity if any
            runtime.mobEntityId?.let { id ->
                val mob = level.getEntity(id)
                mob?.teleportTo(snapX, snapY, snapZ)
                mob?.setYRot(snapYaw)
                mob?.setXRot(0f)
            }

            // Spawn invisible solid collision Shulker if not already present
            if (runtime.collisionEntityId == null) {
                val shulker = EntityTypes.SHULKER.create(level, EntitySpawnReason.COMMAND)
                if (shulker != null) {
                    shulker.snapTo(snapX, snapY, snapZ, 0f, 0f)
                    shulker.isInvisible = true
                    shulker.isNoAi = true
                    shulker.isSilent = true
                    shulker.setPermanentlyInvulnerable(false) // Hunter attacks hit this!
                    shulker.customName = Component.literal("§8[PH_PROP:${player.uuid}]")
                    shulker.isCustomNameVisible = false
                    level.addFreshEntity(shulker)
                    runtime.collisionEntityId = shulker.id
                }
            }

            player.sendSystemMessage(Component.literal("§a[PropHunt] §eLOCKED TO GRID! §7Solid collision active."))
            player.playNotifySound(SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.PLAYERS, 0.7f, 1.4f)
            level.sendParticles(ParticleTypes.POOF, snapX, snapY + 0.1, snapZ, 8, 0.2, 0.05, 0.2, 0.02)
        } else {
            runtime.isLocked = false
            playerData.rotationLocked = false
            runtime.lockedBlockPos = null

            // Discard collision shulker
            runtime.collisionEntityId?.let { id ->
                level.getEntity(id)?.discard()
                runtime.collisionEntityId = null
            }

            // Restore movement if not manually frozen
            if (!runtime.isFrozen) {
                player.removeEffect(MobEffects.SLOWNESS)
                player.removeEffect(MobEffects.JUMP_BOOST)
            }

            player.sendSystemMessage(Component.literal("§a[PropHunt] §7UNLOCKED from grid. Free to move."))
            player.playNotifySound(SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.PLAYERS, 0.7f, 1.4f)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DIRECTIONAL TAUNT WHISTLE SYSTEM
    // ════════════════════════════════════════════════════════════════════════

    fun performTaunt(player: ServerPlayer, game: PropHuntGame) {
        val playerData = game.players[player.uuid] ?: return
        if (!canUsePropAbility(player, game, playerData, "taunt")) return

        if (playerData.tauntCooldown > 0) {
            val seconds = playerData.tauntCooldown / 20
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7Taunt cooldown: §e${seconds}s§7 remaining."))
            return
        }

        executeWhistle(player, game, isInvoluntary = false)
    }

    fun executeWhistle(player: ServerPlayer, game: PropHuntGame, isInvoluntary: Boolean) {
        val runtime = propRuntimes[player.uuid] ?: return
        val playerData = game.players[player.uuid] ?: return
        val level = player.level() as? ServerLevel ?: return
        val settings = game.settings

        playerData.tauntCooldown = settings.tauntCooldownSeconds * 20
        runtime.ticksSinceLastTaunt = 0

        // Spatial flute whistle audio
        val randomPitch = 1.3f + (Math.random() * 0.4f).toFloat()
        level.playSound(null, player.x, player.y, player.z, SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, 2.5f, randomPitch)
        level.playSound(null, player.x, player.y, player.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.8f, 1.6f)

        // Musical note particles
        level.sendParticles(ParticleTypes.NOTE, player.x, player.y + 1.2, player.z, 7, 0.4, 0.4, 0.4, 0.5)

        if (isInvoluntary) {
            player.sendSystemMessage(Component.literal("§c§l♫ INVOLUNTARY WHISTLE! §7You were silent for too long!"))
        } else {
            player.sendSystemMessage(Component.literal("§d§l♫ WHISTLE! §7Hunters heard you nearby!"))
            player.giveExperiencePoints(15) // Taunt reward
        }

        // Directional telemetry broadcast to each hunter
        for (hunterData in game.getHunters()) {
            val hunter = level.server.playerList.getPlayer(hunterData.uuid) ?: continue
            val dist = hunter.position().distanceTo(player.position())
            val dir = getDirectionString(hunter, player)
            val distColor = when {
                dist < 10 -> "§c"
                dist < 25 -> "§e"
                else -> "§7"
            }
            hunter.sendSystemMessage(Component.literal("§d♫ Whistle heard §e$dir §7($distColor~${dist.toInt()}m§7)!"))
            hunter.playNotifySound(SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, 0.7f, randomPitch)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DECOY BLOCK SPAWNING
    // ════════════════════════════════════════════════════════════════════════

    fun deployDecoy(player: ServerPlayer, game: PropHuntGame): Boolean {
        val runtime = propRuntimes[player.uuid] ?: return false
        val playerData = game.players[player.uuid] ?: return false
        val level = player.level() as? ServerLevel ?: return false
        if (!canUsePropAbility(player, game, playerData, "deploy decoy")) return false

        val maxDecoys = 3
        if (runtime.activeDecoys.size >= maxDecoys) {
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7Maximum decoys deployed ($maxDecoys/$maxDecoys)!"))
            return false
        }

        // Target placement block
        val targetPos = if (player.isShiftKeyDown) {
            player.blockPosition()
        } else {
            player.blockPosition().relative(player.direction)
        }

        val snapX = targetPos.x + 0.5
        val snapY = targetPos.y.toDouble()
        val snapZ = targetPos.z + 0.5

        // Spawn Decoy BlockDisplay matching prop's block disguise
        val blockState = when (val d = runtime.disguise) {
            is PropDisguiseOption.BlockProp -> d.blockState
            else -> Blocks.OAK_LOG.defaultBlockState()
        }

        val decoyDisplay = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND)
        if (decoyDisplay == null) {
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7Failed to deploy decoy."))
            return false
        }

        decoyDisplay.snapTo(snapX, snapY, snapZ, player.yRot, 0f)
        decoyDisplay.entityData.set(BLOCK_STATE_ACCESSOR, blockState)
        decoyDisplay.entityData.set(TRANSLATION_ACCESSOR, Vector3f(-0.5f, 0.0f, -0.5f))
        decoyDisplay.customName = Component.literal("§8[PH_DECOY:${player.uuid}]")
        decoyDisplay.isCustomNameVisible = false
        level.addFreshEntity(decoyDisplay)

        // Spawn Decoy Shulker for physical collision and hit detection
        val decoyShulker = EntityTypes.SHULKER.create(level, EntitySpawnReason.COMMAND)
        if (decoyShulker != null) {
            decoyShulker.snapTo(snapX, snapY, snapZ, 0f, 0f)
            decoyShulker.isInvisible = true
            decoyShulker.isNoAi = true
            decoyShulker.isSilent = true
            decoyShulker.setPermanentlyInvulnerable(false)
            decoyShulker.customName = Component.literal("§8[PH_DECOY:${player.uuid}]")
            decoyShulker.isCustomNameVisible = false
            level.addFreshEntity(decoyShulker)
            runtime.activeDecoys.add(DecoyBlockEntry(decoyDisplay.id, decoyShulker.id, targetPos))
        }

        level.playSound(null, snapX, snapY, snapZ, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f)
        level.sendParticles(ParticleTypes.POOF, snapX, snapY + 0.5, snapZ, 12, 0.3, 0.3, 0.3, 0.05)

        val remaining = maxDecoys - runtime.activeDecoys.size
        player.sendSystemMessage(Component.literal("§6[PropHunt] §7Decoy placed! §e$remaining §7remaining."))
        return true
    }

    fun detonateDecoy(decoyEntry: DecoyBlockEntry, level: ServerLevel, hunter: ServerPlayer, game: PropHuntGame) {
        val pos = decoyEntry.pos
        level.getEntity(decoyEntry.displayEntityId)?.discard()
        level.getEntity(decoyEntry.collisionEntityId)?.discard()

        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 25, 0.3, 0.3, 0.3, 0.05)
        level.sendParticles(ParticleTypes.EXPLOSION, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 2, 0.0, 0.0, 0.0, 0.0)
        level.playSound(null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 1.5f, 0.8f)

        // Apply miss penalty to hunter
        HunterAbilityManager.onHunterMiss(hunter, game, isDecoy = true)

        // Find prop owner and credit decoy
        for ((uuid, runtime) in propRuntimes) {
            if (runtime.activeDecoys.remove(decoyEntry)) {
                val propPlayer = level.server.playerList.getPlayer(uuid)
                val propData = game.players[uuid]
                propData?.decoyHits = (propData?.decoyHits ?: 0) + 1
                propPlayer?.sendSystemMessage(Component.literal("§a[PropHunt] §eA hunter attacked your decoy! §a+25 XP"))
                propPlayer?.giveExperiencePoints(25)
                break
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TICK LOOP
    // ════════════════════════════════════════════════════════════════════════

    fun tickProp(player: ServerPlayer, game: PropHuntGame) {
        val runtime = propRuntimes[player.uuid] ?: return
        val playerData = game.players[player.uuid] ?: return
        val level = player.level() as? ServerLevel ?: return

        if (playerData.tauntCooldown > 0) playerData.tauntCooldown--
        if (playerData.isAlive) playerData.survivalTicks++

        // Handle sneak-lock: if player sneaks stationary for ~12 ticks, auto-lock to grid
        if (!runtime.isLocked && game.state == GameState.SEEKING) {
            if (player.isShiftKeyDown && player.deltaMovement.horizontalDistanceSqr() < 0.001) {
                runtime.sneakTicks++
                if (runtime.sneakTicks >= 12) {
                    toggleGridLock(player, game, true)
                }
            } else {
                runtime.sneakTicks = 0
            }
        } else if (runtime.isLocked) {
            // If locked and player moves or unsneaks, unlock
            if (!player.isShiftKeyDown || player.deltaMovement.horizontalDistanceSqr() > 0.002) {
                toggleGridLock(player, game, false)
            }
        }

        // Follow player smoothly if not grid-locked
        if (!runtime.isLocked) {
            runtime.displayEntityId?.let { id ->
                val display = level.getEntity(id)
                display?.teleportTo(player.x, player.y, player.z)
                display?.setYRot(player.yRot)
            }
        }

        // Involuntary Taunt check
        runtime.ticksSinceLastTaunt++
        val maxSilentTicks = if (game.isSuddenDeath) 10 * 20 else 45 * 20
        if (game.state == GameState.SEEKING && runtime.ticksSinceLastTaunt >= maxSilentTicks) {
            executeWhistle(player, game, isInvoluntary = true)
        }

        showPropActionBar(player, playerData, runtime, game)
    }

    private fun showPropActionBar(player: ServerPlayer, data: PropHuntPlayerData, runtime: PropPlayerRuntime, game: PropHuntGame) {
        val parts = mutableListOf<String>()
        parts.add("§7Disguise: §e${runtime.disguise.displayName}")

        if (runtime.isLocked) {
            parts.add("§6🔒 Grid-Locked")
        } else if (runtime.isFrozen) {
            parts.add("§b❄ Frozen")
        } else {
            parts.add("§a✦ Mobile")
        }

        if (data.tauntCooldown > 0) {
            parts.add("§d♫ ${data.tauntCooldown / 20}s")
        } else {
            parts.add("§a♫ Ready")
        }

        val decoysLeft = 3 - runtime.activeDecoys.size
        parts.add("§6Decoys: $decoysLeft")

        player.sendSystemMessage(Component.literal(parts.joinToString(" §8| ")))
    }

    // ════════════════════════════════════════════════════════════════════════
    // HIT DETECTION & REVEAL
    // ════════════════════════════════════════════════════════════════════════

    fun onPropCaught(prop: ServerPlayer, hunter: ServerPlayer, game: PropHuntGame) {
        val propData = game.players[prop.uuid] ?: return
        val hunterData = game.players[hunter.uuid] ?: return
        if (!propData.isAlive) return

        val totalFindsBefore = game.getHunters().sumOf { it.propsFound }

        propData.isAlive = false
        hunterData.propsFound++

        PropHuntManager.statistics.recordPropFound(hunter.uuid)
        PropHuntManager.statistics.recordSurvivalTime(prop.uuid, propData.survivalTicks.toLong())

        undisguise(prop)
        prop.setGameMode(GameType.SPECTATOR)

        hunter.giveExperiencePoints(game.settings.hunterFindRewardXP)

        val level = prop.level() as? ServerLevel
        level?.sendParticles(ParticleTypes.EXPLOSION, prop.x, prop.y + 1, prop.z, 8, 0.5, 0.5, 0.5, 0.0)
        level?.playSound(null, prop.x, prop.y, prop.z, SoundEvents.PLAYER_DEATH, SoundSource.PLAYERS, 1.0f, 1.0f)

        game.broadcast("§c§l💀 §e${prop.name.string} §7(§a${propData.disguiseType}§7) was caught by §c${hunter.name.string}§7!")

        val remainingProps = game.getProps().count { it.isAlive }
        game.broadcast("§7Remaining props: §e$remainingProps")

        if (totalFindsBefore == 0) PropHuntRewards.awardFirstBlood(hunter, game)
        if (remainingProps == 0) PropHuntRewards.awardLastKill(hunter, game)

        PropHuntSpectator.enterSpectatorMode(prop, game)
    }

    fun isPropDisguise(entity: Entity): UUID? {
        val name = entity.customName?.string ?: return null
        if (name.startsWith("§8[PH_PROP:") && name.endsWith("]")) {
            val uuidStr = name.removePrefix("§8[PH_PROP:").removeSuffix("]")
            return try { UUID.fromString(uuidStr) } catch (e: Exception) { null }
        }
        return null
    }

    fun isDecoyEntity(entity: Entity): DecoyBlockEntry? {
        val name = entity.customName?.string ?: return null
        if (name.startsWith("§8[PH_DECOY:") && name.endsWith("]")) {
            for (runtime in propRuntimes.values) {
                val match = runtime.activeDecoys.find { it.displayEntityId == entity.id || it.collisionEntityId == entity.id }
                if (match != null) return match
            }
        }
        return null
    }

    fun getRuntime(playerUuid: UUID): PropPlayerRuntime? = propRuntimes[playerUuid]

    private fun getDirectionString(from: ServerPlayer, to: ServerPlayer): String {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val angle = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
        val relative = ((angle - from.yRot + 360 + 180) % 360) - 180

        return when {
            relative > 135 || relative < -135 -> "Behind"
            relative > 45 -> "Right"
            relative < -45 -> "Left"
            else -> "Ahead"
        }
    }

    private fun spawnTransformParticles(player: ServerPlayer) {
        val level = player.level() as? ServerLevel ?: return
        level.sendParticles(ParticleTypes.WITCH, player.x, player.y + 1, player.z, 25, 0.5, 0.8, 0.5, 0.1)
    }

    private fun spawnFreezeParticles(player: ServerPlayer) {
        val level = player.level() as? ServerLevel ?: return
        level.sendParticles(ParticleTypes.SNOWFLAKE, player.x, player.y + 0.5, player.z, 20, 0.3, 0.5, 0.3, 0.02)
    }

    private fun canUsePropAbility(player: ServerPlayer, game: PropHuntGame, data: PropHuntPlayerData, abilityName: String): Boolean {
        if (data.team != PropHuntTeam.PROP || !data.isAlive) {
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7Only living props can use $abilityName."))
            return false
        }
        if (game.state != GameState.HIDING && game.state != GameState.SEEKING) {
            player.sendSystemMessage(Component.literal("§c[PropHunt] §7You can only use $abilityName during active rounds."))
            return false
        }
        return true
    }
}
