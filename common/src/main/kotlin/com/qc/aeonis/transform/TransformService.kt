package com.qc.aeonis.transform

import com.qc.aeonis.entity.AeonisEntities
import com.qc.aeonis.network.AeonisNetwork
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase
import net.minecraft.world.level.GameType
import java.util.UUID

/** Outcome of a transform request. */
sealed interface TransformResult {
    data class Success(
        val mob: Mob,
        val report: VariantReport,
        val loadout: String?
    ) : TransformResult

    /** The request was understood but refused; [reason] is safe to show the player. */
    data class Rejected(val reason: String, val herobrineScare: Boolean = false) : TransformResult

    /** The request was valid but could not be carried out. */
    data class Failed(val reason: String) : TransformResult

    val isSuccess: Boolean get() = this is Success
}

/**
 * The one place that owns a player's transform lifecycle.
 *
 * Previously this logic lived inside the command object and was reached three different ways:
 * directly, by re-parsing a synthesised command string through the dispatcher, and by the
 * possession path. Each entry point had slightly different cleanup, which is how players ended
 * up stuck invisible or with the wrong gamemode. Every path now funnels through here, and
 * every exit (untransform, death, disconnect, shutdown) restores from the same snapshot.
 */
object TransformService {

    fun initialise() {
        AeonisMobVariants.registerDefaults()
    }

    fun isTransformed(player: ServerPlayer): Boolean =
        TransformSessions.isActive(player.uuid) || AeonisNetwork.isPlayerTransformed(player.uuid)

    // ------------------------------------------------------------------ transform

    fun transform(
        player: ServerPlayer,
        type: EntityType<*>,
        variantTokens: List<String> = emptyList()
    ): TransformResult {
        initialise()
        val level = player.level() as? ServerLevel
            ?: return TransformResult.Failed("You must be in a loaded world to transform.")

        if (isTransformed(player)) {
            return TransformResult.Rejected("You are already transformed. Use /untransform first.")
        }
        if (type == EntityTypes.PLAYER) {
            return TransformResult.Rejected("You cannot become another player.")
        }
        if (type == AeonisEntities.BODY.get()) {
            return TransformResult.Rejected("The Body entity is internal only.")
        }
        if (type == AeonisEntities.HEROBRINE.get()) {
            return TransformResult.Rejected("Some doors are not meant to be opened.", herobrineScare = true)
        }

        // Create the entity before trusting the request. The previous implementation accepted
        // any summonable type, so transforming into a non-mob left the player permanently
        // invisible and tethered to an entity the control loop silently skipped.
        val spawned = type.create(level, EntitySpawnReason.COMMAND)
            ?: return TransformResult.Failed("${type.description.string} could not be created here.")
        if (spawned !is Mob) {
            spawned.discard()
            return TransformResult.Rejected(
                "${type.description.string} is not a mob, so it cannot be possessed."
            )
        }

        val restore = PlayerRestoreState.capture(player)
        spawned.snapTo(player.x, player.y, player.z, player.yRot, player.xRot)
        spawned.yBodyRot = player.yRot
        spawned.yHeadRot = player.yRot
        level.addFreshEntity(spawned)

        // Variants run after the mob is in the world so vanilla conversions behave normally.
        // A conversion replaces the entity, so follow it as we go.
        var current: Mob = spawned
        val applier = VariantApplier(level, spawned) { previous, replacement ->
            current = replacement
            if (previous.isAlive) previous.discard()
        }
        val report = applier.apply(variantTokens)
        current = applier.currentMob

        // AI is suppressed for controlled mobs by the loader mixins, not by NoAI (which would
        // also freeze movement in 26.2+).
        current.setNoAi(false)
        // `current` is reassigned by the variant callback, so it cannot be smart cast; the
        // dragon needs its own local for the phase lookup.
        (current as? EnderDragon)?.phaseManager?.setPhase(EnderDragonPhase.HOVERING)

        TransformSessions.start(
            TransformSession(
                ownerId = player.uuid,
                entityId = current.id,
                restore = restore,
                variants = report.applied,
                startedTick = level.gameTime
            )
        )
        AeonisNetwork.setControlledEntity(player, current.id, player.inventory.selectedSlot)
        broadcastControl(player)

        val loadout = TransformLoadout.apply(current, player)

        if (restore.gameMode == GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR)
        } else {
            player.setGameMode(GameType.SURVIVAL)
            player.isInvisible = true
        }
        val mobMaxHealth = current.maxHealth.toDouble()
        player.getAttribute(Attributes.MAX_HEALTH)?.baseValue = mobMaxHealth
        player.health = current.health.coerceAtMost(mobMaxHealth.toFloat())
        player.teleportTo(current.x, current.y, current.z)

        return TransformResult.Success(current, report, loadout)
    }

    /**
     * Programmatic entry point used by `/transform random`, themes, roulette and `/prank morph`.
     * It replaces any existing form instead of failing, matching those commands' intent.
     */
    fun replaceTransform(
        player: ServerPlayer,
        type: EntityType<*>,
        variantTokens: List<String> = emptyList()
    ): TransformResult {
        if (isTransformed(player)) {
            untransform(player, showMessage = false, carryDamage = false)
        }
        return transform(player, type, variantTokens)
    }

    /**
     * Takes control of a mob that already exists in the world (soul-mode possession rather than
     * spawning a new body). The restore snapshot is inherited from soul mode, so exiting restores
     * the gamemode, visibility and flight state the player had before they ever entered it.
     */
    fun possess(player: ServerPlayer, mob: Mob): TransformResult {
        initialise()
        if (isTransformed(player)) {
            return TransformResult.Rejected("You are already controlling a mob. Use /untransform first.")
        }

        val restore = SoulMode.leave(player.uuid) ?: PlayerRestoreState.capture(player)

        mob.setNoAi(false)
        if (mob is EnderDragon) {
            mob.phaseManager.setPhase(EnderDragonPhase.HOVERING)
        }

        TransformSessions.start(
            TransformSession(
                ownerId = player.uuid,
                entityId = mob.id,
                restore = restore,
                variants = emptyList(),
                startedTick = mob.level().gameTime
            )
        )
        AeonisNetwork.setControlledEntity(player, mob.id, player.inventory.selectedSlot)
        broadcastControl(player)
        AeonisNetwork.sendSoulModeStatus(player, false)

        val loadout = TransformLoadout.apply(mob, player)

        player.setGameMode(GameType.SURVIVAL)
        player.isInvisible = true
        val mobMaxHealth = mob.maxHealth.toDouble()
        player.getAttribute(Attributes.MAX_HEALTH)?.baseValue = mobMaxHealth
        player.health = mob.health.coerceAtMost(mobMaxHealth.toFloat())
        player.teleportTo(mob.x, mob.y, mob.z)

        return TransformResult.Success(mob, VariantReport(), loadout)
    }

    // ---------------------------------------------------------------- untransform

    /**
     * Ends the session and restores the player. Idempotent and safe to call when the session
     * record is missing but the network still thinks the player is controlling something.
     *
     * @param teleportToMob move the player to the mob's last position (disabled on respawn, so
     *   a dead player is not dragged back to wherever the abandoned mob wandered).
     * @param carryDamage scale the player's restored health by the mob's remaining health
     *   fraction, so damage taken while morphed is not silently refunded.
     */
    fun untransform(
        player: ServerPlayer,
        showMessage: Boolean = false,
        teleportToMob: Boolean = true,
        carryDamage: Boolean = true
    ): Boolean {
        val session = TransformSessions.end(player.uuid)
        val hadControl = AeonisNetwork.isPlayerTransformed(player.uuid)
        if (session == null && !hadControl) return false

        AeonisNetwork.removeControlledEntity(player)
        broadcastControl(player)

        val level = player.level()
        val mob = session?.let { level.getEntity(it.entityId) } as? LivingEntity
        val restore = session?.restore ?: PlayerRestoreState.safeDefault()

        val healthOverride = if (carryDamage && mob != null && mob.maxHealth > 0.0f) {
            val ratio = (mob.health / mob.maxHealth).coerceIn(0.0f, 1.0f)
            (ratio * restore.maxHealth.toFloat()).coerceAtLeast(1.0f)
        } else {
            null
        }

        if (teleportToMob && mob != null && mob.isAlive) {
            player.teleportTo(mob.x, mob.y, mob.z)
        }

        // Anything layered on top of the transform must be cleared too, or the flag survives
        // into the restored player.
        SoulMode.leave(player.uuid)

        restore.applyTo(player, healthOverride)
        mob?.discard()

        if (showMessage) {
            player.sendSystemMessage(Component.literal("§aYou returned to your normal form."))
        }
        return true
    }

    // ------------------------------------------------------------------- lifecycle

    /** Detects a mob that died or unloaded and unwinds the session. */
    fun tick(server: MinecraftServer) {
        for (session in TransformSessions.all()) {
            val player = server.playerList.getPlayer(session.ownerId) ?: continue
            val mob = player.level().getEntity(session.entityId)
            if (mob == null || !mob.isAlive) {
                untransform(player, showMessage = true)
            }
        }
    }

    /**
     * Disconnect cleanup. The possessed mob is a real entity saved with the world, so leaving
     * it behind persists a playerless mob into the save and strands the returning player in an
     * invisible state with no session to restore from.
     */
    fun handleQuit(player: ServerPlayer) {
        val session = TransformSessions.end(player.uuid)
        if (session == null) {
            AeonisNetwork.clearControlState(player.uuid)
            SoulMode.leave(player.uuid)
            return
        }
        player.level().getEntity(session.entityId)?.discard()
        AeonisNetwork.clearControlState(player.uuid)
        SoulMode.leave(player.uuid)
        session.restore.applyTo(player)
    }

    /**
     * Death and respawn cleanup. Dying while morphed used to leave the session alive, so the
     * position-sync loop immediately dragged the freshly respawned player back to the mob.
     */
    fun handleRespawn(player: ServerPlayer) {
        if (isTransformed(player)) {
            untransform(player, showMessage = false, teleportToMob = false, carryDamage = false)
        }
        SoulMode.leave(player.uuid)
    }

    /**
     * Complete teardown used by server shutdown and `/aeonis reload`.
     *
     * Must never leave a session, a playerless mob, or a player parked in soul mode: all three
     * would otherwise be serialised into the save. A player sitting in soul mode at shutdown was
     * previously saved mid-spectator, which is why they are unwound here as well.
     */
    fun shutdown(server: MinecraftServer) {
        for (session in TransformSessions.all()) {
            val player = server.playerList.getPlayer(session.ownerId)
            if (player != null) {
                untransform(player, showMessage = false, carryDamage = false)
            } else {
                server.allLevels.forEach { it.getEntity(session.entityId)?.discard() }
                TransformSessions.end(session.ownerId)
                AeonisNetwork.clearControlState(session.ownerId)
            }
        }

        for (uuid in SoulMode.members()) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            SoulMode.leave(uuid)?.applyTo(player)
            AeonisNetwork.sendSoulModeStatus(player, false)
        }
        SoulMode.clear()
    }

    // ------------------------------------------------------------------ inspection

    fun sessionDescription(player: ServerPlayer): String? =
        TransformSessions.get(player.uuid)?.describe(player.level().gameTime)

    fun entityNameFor(player: ServerPlayer): String? {
        val session = TransformSessions.get(player.uuid) ?: return null
        val mob = player.level().getEntity(session.entityId) ?: return null
        return mob.type.description.string
    }

    /**
     * Variants a mob type supports, computed from a throwaway probe entity that is discarded
     * immediately so this stays a pure query.
     *
     * Cached per type: the variant registry is fixed after [initialise], and this is called on
     * every keystroke by the tab-completion provider, where creating an entity each time would be
     * wasteful.
     */
    fun variantsFor(level: ServerLevel, type: EntityType<*>): List<MobVariant> {
        initialise()
        variantCache[type]?.let { return it }
        val probe = type.create(level, EntitySpawnReason.COMMAND) as? Mob ?: return emptyList()
        val available = try {
            MobVariants.availableFor(probe)
        } finally {
            probe.discard()
        }
        variantCache[type] = available
        return available
    }

    private val variantCache = java.util.concurrent.ConcurrentHashMap<EntityType<*>, List<MobVariant>>()

    internal fun ownerOf(entityId: Int): UUID? = AeonisNetwork.getControllingPlayerUuid(entityId)

    private fun broadcastControl(player: ServerPlayer) {
        player.level().server?.let { AeonisNetwork.broadcastControllingPlayers(it) }
    }
}
