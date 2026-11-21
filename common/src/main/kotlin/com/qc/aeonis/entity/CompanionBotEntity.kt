package com.qc.aeonis.entity

import com.qc.aeonis.Aeonis
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.*
import net.minecraft.world.entity.EquipmentSlot
import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.inventory.CompanionMenu
import com.qc.aeonis.companion.entity.CompanionPlayerSurrogate
import com.qc.aeonis.companion.ai.observer.PlayerIntentObserver
import com.qc.aeonis.companion.ai.observer.PlayerIntent
import com.qc.aeonis.companion.ai.brain.CompanionBehaviorTree
import com.qc.aeonis.companion.order.CompanionOrder
import com.qc.aeonis.companion.order.CompanionOrderState
import com.qc.aeonis.companion.order.WeaponSelector
import com.qc.aeonis.companion.skill.ScaffoldingEngine
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.*
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.*

/**
 * Aeonis Companion Bot — a highly advanced, utility-focused AI helper.
 */
class CompanionBotEntity(type: EntityType<out CompanionBotEntity>, level: Level) : PathfinderMob(type, level) {

    companion object {
        private val OWNER_REF: EntityDataAccessor<Optional<EntityReference<LivingEntity>>> =
            SynchedEntityData.defineId(CompanionBotEntity::class.java, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE)
        private val FORCED_MODE: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(CompanionBotEntity::class.java, EntityDataSerializers.INT)

        fun createAttributes(): AttributeSupplier.Builder {
            return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.STEP_HEIGHT, 1.25)
        }

        /**
         * Serialisable runtime state carried across recall, dimension change and respawn.
         *
         * [order] is a name rather than an ordinal so reordering [CompanionOrder] cannot
         * silently reinterpret a saved companion's standing instruction. It defaults to
         * FREE so existing two-argument call sites stay valid.
         */
        data class CompanionSnapshot(
            val health: Float,
            val mode: Int,
            val order: String = CompanionOrder.FREE.name
        )
        
        fun spawn(level: ServerLevel, pos: BlockPos, owner: ServerPlayer): CompanionBotEntity? {
            val bot = AeonisEntities.COMPANION_BOT.get().spawn(level, pos, EntitySpawnReason.COMMAND)
            bot?.ownerUuid = owner.uuid
            return bot
        }
    }

    enum class ForcedMode { NONE, ATTACK, FOLLOW, RUN_AWAY }

    // Internal State
    private var attackCooldown = 0
    private var blockPlaceCooldown = 0
    private var blockBreakCooldown = 0

    /** Ticks between ordinary block placements (torches, scaffolds). */
    private val blockPlaceCooldownTicks = 15
    /**
     * The single block-breaking session for this entity.
     *
     * Breaking state lives here rather than on the entity because progress is per-block and
     * vanilla-timed: see [com.qc.aeonis.companion.skill.MiningSession] for why the old
     * four-ticks-per-block accumulator was replaced.
     */
    val miningSession: com.qc.aeonis.companion.skill.MiningSession by lazy {
        com.qc.aeonis.companion.skill.MiningSession(this)
    }
    
    // True 33-slot Inventory & Player Surrogate Trampoline
    val inventory = CompanionInventory()
    private var surrogate: CompanionPlayerSurrogate? = null
    var brainTree: CompanionBehaviorTree? = null
    val skillManager = com.qc.aeonis.companion.skill.CompanionSkillManager(this)

    /**
     * Runtime order state: the player's standing instruction plus the governor's
     * bodyguard latch. See [com.qc.aeonis.companion.order.OrderGovernor].
     */
    val orderState = CompanionOrderState()

    /**
     * Shared traversal-engineering helper.
     *
     * One instance per bot, created lazily: the engine holds climb intent (the tick its own
     * pillar jump was issued) that must not be duplicated across callers, and building it
     * only when first needed keeps entity construction free of world queries.
     */
    val scaffoldingEngine = ScaffoldingEngine(this)

    /**
     * The player's current instruction to this companion (`HOLD`, `FOLLOW`, `RETREAT`, …).
     *
     * Assigning this is the single supported way to change what the companion has been
     * told to do: the setter also derives [forcedMode], so the legacy goal-selector paths
     * and the order layer can never disagree about the standing instruction.
     */
    var currentOrder: CompanionOrder
        get() = orderState.order
        set(value) {
            orderState.set(value, level().gameTime)
            forcedMode = value.forcesLegacyMode
        }

    fun getOrCreateSurrogate(): CompanionPlayerSurrogate? {
        val serverLevel = level() as? ServerLevel ?: return null
        if (surrogate == null || surrogate?.level() != serverLevel) {
            val ownerProfile = getOwnerPlayer()?.gameProfile
            surrogate = CompanionPlayerSurrogate(serverLevel, this, ownerProfile)
        }
        return surrogate
    }

    override fun getItemBySlot(slot: EquipmentSlot): ItemStack = inventory.getEquipmentBySlot(slot)
    override fun setItemSlot(slot: EquipmentSlot, stack: ItemStack) { inventory.setEquipmentBySlot(slot, stack) }
    override fun getMainHandItem(): ItemStack = inventory.mainHandItem
    override fun getOffhandItem(): ItemStack = inventory.offHandItem
    
    // Threat Memory
    private val threatMemory = mutableMapOf<UUID, Int>() // EntityID -> Tick duration

    init {
        this.setPersistenceRequired()
        // Vanilla mob navigation refuses to path through water unless floating is enabled,
        // which is why the companion used to refuse to cross rivers at all.
        this.navigation.setCanFloat(true)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(OWNER_REF, Optional.empty())
        builder.define(FORCED_MODE, ForcedMode.NONE.ordinal)
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, CompanionEmergencyTeleportGoal(this))
        goalSelector.addGoal(2, CompanionEvadeDangerGoal(this))
        goalSelector.addGoal(3, CompanionForcedModeGoal(this))
        goalSelector.addGoal(6, LookAtPlayerGoal(this, Player::class.java, 8.0f))
        goalSelector.addGoal(7, RandomLookAroundGoal(this))

        targetSelector.addGoal(1, HurtByTargetGoal(this))
        targetSelector.addGoal(2, CompanionOwnerTargetGoal(this))
    }

    var ownerUuid: UUID?
        get() = entityData.get(OWNER_REF).map { it.uuid }.orElse(null)
        set(value) = entityData.set(OWNER_REF, Optional.ofNullable(value).map { EntityReference.of(it) })

    var forcedMode: ForcedMode
        get() = ForcedMode.entries[entityData.get(FORCED_MODE)]
        set(value) = entityData.set(FORCED_MODE, value.ordinal)


    val currentState: ForcedMode get() = forcedMode

    override fun readAdditionalSaveData(input: ValueInput) {
        input.read("Owner", UUIDUtil.CODEC).ifPresent { ownerUuid = it }
        val order = CompanionOrder.parse(input.getStringOr("CompanionOrder", CompanionOrder.FREE.name))
            ?: CompanionOrder.FREE
        orderState.set(order, 0L)
        // forcedMode is derived from the order, never read back independently, so a
        // save/load cycle cannot desynchronise the two.
        forcedMode = order.forcesLegacyMode
        inventory.load(input)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        ownerUuid?.let { output.store("Owner", UUIDUtil.CODEC, it) }
        output.putString("CompanionOrder", orderState.order.name)
        output.putInt("ForcedMode", forcedMode.ordinal)
        inventory.save(output)
    }

    fun createSnapshot() = CompanionSnapshot(health, forcedMode.ordinal, orderState.order.name)
    fun applySnapshot(snapshot: CompanionSnapshot) {
        health = snapshot.health
        val order = CompanionOrder.parse(snapshot.order) ?: CompanionOrder.FREE
        orderState.set(order, 0L)
        forcedMode = order.forcesLegacyMode
    }

    fun getStrengthSummary(): String {
        return when {
            health >= maxHealth * 0.8 -> "§aStrong"
            health >= maxHealth * 0.4 -> "§eHealthy"
            else -> "§cWeakened"
        }
    }

    fun getOwnerPlayer(): ServerPlayer? {
        val uuid = ownerUuid ?: return null
        val serverLevel = level() as? ServerLevel ?: return null
        return serverLevel.server.playerList.getPlayer(uuid)
    }

    fun recallNow() {
        val owner = getOwnerPlayer() ?: return
        teleportTo(owner.x, owner.y, owner.z)
        level().playSound(null, blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1f, 1f)
    }

    private var intentObserver: PlayerIntentObserver? = null

    fun getPlayerIntent(): PlayerIntent {
        val owner = getOwnerPlayer() ?: return PlayerIntent.RESTING
        if (intentObserver == null) {
            intentObserver = PlayerIntentObserver(owner)
        }
        return intentObserver!!.evaluate()
    }

    override fun aiStep() {
        super.aiStep()
        if (level().isClientSide) return
        val serverLevel = level() as? ServerLevel ?: return

        if (brainTree == null) {
            brainTree = CompanionBehaviorTree(this)
        }

        tickCooldowns()
        cleanThreatMemory()
        brainTree?.tick()
        handleEmergencyTeleport()
    }

    override fun remove(reason: Entity.RemovalReason) {
        super.remove(reason)
        val srv = level().server
        val ownerId = ownerUuid
        if (srv != null && ownerId != null && reason != Entity.RemovalReason.KILLED) {
            com.qc.aeonis.companion.profile.CompanionProfileManager.saveBotToProfile(srv, this, ownerId)
        }
        brainTree?.close()
        brainTree = null
    }

    private fun handleIntentSynergy(level: ServerLevel) {
        if (tickCount % 20 != 0) return
        val intent = getPlayerIntent()
        if (intent == PlayerIntent.MINING) {
            tryAutoTorch(level)
        }
    }

    private fun tryAutoTorch(level: ServerLevel): Boolean {
        val pos = blockPosition()
        if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos) < 7) {
            val torchSlot = (0 until CompanionInventory.CONTAINER_SIZE).firstOrNull {
                inventory.getItem(it).`is`(Items.TORCH)
            }
            if (torchSlot != null) {
                return tryPlaceBlock(pos, Direction.UP)
            }
        }
        return false
    }

    private fun tickCooldowns() {
        if (attackCooldown > 0) attackCooldown--
        if (blockPlaceCooldown > 0) blockPlaceCooldown--
        if (blockBreakCooldown > 0) blockBreakCooldown--
    }

    private fun cleanThreatMemory() {
        val it = threatMemory.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value <= 0) it.remove()
            else threatMemory[entry.key] = entry.value - 1
        }
    }

    private fun updateCombatTarget(level: ServerLevel) {
        val owner = getOwnerPlayer() ?: return
        val ownerTarget = owner.lastHurtMob
        if (ownerTarget != null && ownerTarget.isAlive && ownerTarget != this) {
            target = ownerTarget
            return
        }

        val lastHurtBy = lastHurtByMob
        if (lastHurtBy != null && lastHurtBy.isAlive) {
            target = lastHurtBy
            return
        }
    }

    private fun handleEmergencyTeleport() {
        val owner = getOwnerPlayer() ?: return
        if (distanceToSqr(owner) > 2500.0) {
            recallNow()
        }
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (level().isClientSide) return InteractionResult.SUCCESS
        
        val stack = player.getItemInHand(hand)
        
        if (ownerUuid == null) {
            if (stack.`is`(Items.IRON_INGOT)) {
                if (!player.abilities.instabuild) stack.shrink(1)
                ownerUuid = player.uuid
                level().playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1f, 1.2f)
                (player as ServerPlayer).sendSystemMessage(Component.literal("§b[Companion] §7Bot paired successfully!"))
                return InteractionResult.SUCCESS
            }
        }
        
        if (ownerUuid == player.uuid) {
            if (health < maxHealth && (stack.`is`(Items.IRON_INGOT) || stack.`is`(Items.COPPER_INGOT))) {
                heal(10.0f)
                if (!player.abilities.instabuild) stack.shrink(1)
                level().playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1f, 1f)
                return InteractionResult.SUCCESS
            }

            // Shift+Right click or empty-hand opens the Companion Container GUI
            if (player is ServerPlayer && (player.isShiftKeyDown || stack.isEmpty)) {
                openCustomInventory(player)
                return InteractionResult.SUCCESS
            }
        }

        return super.mobInteract(player, hand)
    }

    fun openCustomInventory(player: ServerPlayer) {
        player.openMenu(object : net.minecraft.world.MenuProvider {
            override fun createMenu(syncId: Int, playerInventory: net.minecraft.world.entity.player.Inventory, player: Player): net.minecraft.world.inventory.AbstractContainerMenu {
                return CompanionMenu(syncId, playerInventory, inventory)
            }

            override fun getDisplayName(): Component {
                return customName ?: Component.literal("Aeonis Companion")
            }
        })
    }

    fun getDangerEntity(): LivingEntity? {
        val serverLevel = level() as? ServerLevel ?: return null
        val nearby = serverLevel.getEntitiesOfClass(
            LivingEntity::class.java, boundingBox.inflate(24.0)
        ) { it != this && it.isAlive }

        return nearby.find { it is Enemy && (it.maxHealth > 100.0 || it is Creeper) }
    }

    fun isRetreating(): Boolean = forcedMode == ForcedMode.RUN_AWAY

    /**
     * Places an inventory block at [pos], accepting the first stack matching [filter].
     *
     * @param filter constrains which block is acceptable; the default takes anything.
     */
    fun tryPlaceBlock(
        pos: BlockPos,
        face: Direction = Direction.UP,
        cooldownTicks: Int = blockPlaceCooldownTicks,
        filter: (ItemStack) -> Boolean = { true }
    ): Boolean {
        val slot = firstSlotMatching(filter)
        if (slot < 0) return false
        return placeFromSlot(pos, slot, face, cooldownTicks)
    }

    /** First array-slot (hands included) whose stack satisfies [filter], or -1. */
    fun firstSlotMatching(filter: (ItemStack) -> Boolean): Int {
        for (slot in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && filter(stack)) return slot
        }
        for (slot in 0 until CompanionInventory.BACKPACK_START) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && filter(stack)) return slot
        }
        return -1
    }

    /**
     * Places the block held in [slot] at [pos].
     *
     * The stack is passed straight through to the surrogate, so vanilla's own stack
     * decrement is the only consumption that happens; the slot is cleared only once the
     * stack has actually been emptied. Getting this wrong is what previously let the bot
     * place scaffolding without ever spending it.
     *
     * ### Grief gate
     * Every placement in the mod — pillars, bridges, shelter assistance, torches — funnels through
     * here, so this is where [TerritoryGuard] is consulted: a throwaway scaffold is refused inside
     * a player's build, nothing is ever placed into the owner's own airspace, and nothing is
     * dropped on top of a container. A successful placement is recorded in [BuildLedger], which is
     * what allows the companion to recognise and reclaim its own stair later.
     *
     * @param intent whether this is throwaway traversal material or part of a build the player
     *   asked for, because the two have different rules about the player's territory.
     * @return true only when a block was actually placed.
     */
    fun placeFromSlot(
        pos: BlockPos,
        slot: Int,
        face: Direction = Direction.UP,
        cooldownTicks: Int = blockPlaceCooldownTicks,
        intent: com.qc.aeonis.companion.safety.TerritoryGuard.PlaceIntent =
            com.qc.aeonis.companion.safety.TerritoryGuard.PlaceIntent.SCAFFOLD
    ): Boolean {
        if (blockPlaceCooldown > 0 || level().isClientSide) return false
        val serverLevel = level() as? ServerLevel ?: return false
        if (slot < 0 || slot >= CompanionInventory.CONTAINER_SIZE) return false

        val stack = inventory.getItem(slot)
        if (stack.isEmpty) return false

        val verdict = com.qc.aeonis.companion.safety.TerritoryGuard.mayPlace(this, pos, intent)
        if (!com.qc.aeonis.companion.safety.TerritoryGuard.allows(verdict)) {
            lastPlacementRefusal = com.qc.aeonis.companion.safety.TerritoryGuard.describe(verdict)
            return false
        }

        val playerSurrogate = getOrCreateSurrogate() ?: return false
        val success = playerSurrogate.placeBlock(pos, face, stack)
        if (success) {
            if (stack.isEmpty) inventory.setItem(slot, ItemStack.EMPTY)
            inventory.setChanged()
            blockPlaceCooldown = cooldownTicks
            com.qc.aeonis.companion.safety.BuildLedger.noteBotPlaced(
                serverLevel,
                pos,
                if (intent == com.qc.aeonis.companion.safety.TerritoryGuard.PlaceIntent.SCAFFOLD) {
                    com.qc.aeonis.companion.safety.BuildLedger.BotPlacementKind.SCAFFOLD
                } else {
                    com.qc.aeonis.companion.safety.BuildLedger.BotPlacementKind.PERMANENT
                }
            )
            lastPlacementRefusal = null
            level().playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1f, 1f)
        }
        return success
    }

    /**
     * Why the last placement was refused by the grief gate, or null when it was allowed.
     *
     * Kept on the entity rather than returned, because callers of [placeFromSlot] are making a
     * single decision ("did it work?") and the *reason* is diagnostic — it belongs in
     * `/comp status` and skill messages, not in every call site's signature.
     */
    var lastPlacementRefusal: String? = null
        private set

    /**
     * Advances a real block break at [pos] by one tick, returning true only on the tick the
     * block actually comes apart.
     *
     * Call it every tick for the same position; progress, the tool choice and the timing all come
     * from [miningSession], which reproduces vanilla's hardness/tool maths. Callers that want the
     * reason a break was declined should use [miningSession] directly.
     */
    fun tryBreakBlock(pos: BlockPos): Boolean {
        if (blockBreakCooldown > 0 && !miningSession.isTargeting(pos)) return false
        val result = miningSession.advance(pos)
        if (result.isBroken) {
            blockBreakCooldown = com.qc.aeonis.companion.skill.MiningSession.COOLDOWN_AFTER_BREAK
            return true
        }
        return false
    }

    /** Ticks the current dig would still need; useful for deciding whether a job is worth starting. */
    fun ticksToBreak(pos: BlockPos): Int {
        val serverLevel = level() as? ServerLevel ?: return Int.MAX_VALUE
        val state = serverLevel.getBlockState(pos)
        if (state.isAir) return 0
        return miningSession.estimateTicks(state, pos)
    }

    fun hasIncomingProjectiles(): Boolean {
        val serverLevel = level() as? ServerLevel ?: return false
        val arrows = serverLevel.getEntitiesOfClass(
            AbstractArrow::class.java, boundingBox.inflate(8.0, 4.0, 8.0)
        )
        return arrows.any { arrow ->
            val traj = arrow.deltaMovement.normalize()
            val toBot = position().subtract(arrow.position()).normalize()
            traj.dot(toBot) > 0.95
        }
    }

    fun isFacingTarget(target: Entity): Boolean {
        val lookVec = lookAngle
        val toTarget = target.position().subtract(position()).normalize()
        return lookVec.dot(toTarget) > 0.7
    }

    override fun doHurtTarget(serverLevel: ServerLevel, target: Entity): Boolean {
        val result = super.doHurtTarget(serverLevel, target)
        if (result) {
            level().playSound(null, blockPosition(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.NEUTRAL, 1f, 1.4f)
        }
        return result
    }

    class CompanionFollowOwnerGoal(private val bot: CompanionBotEntity, speed: Double, private val startDist: Float, private val stopDist: Float) 
        : Goal() {
        private var owner: ServerPlayer? = null
        private var timeToRecalcPath = 0

        init {
            this.flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            val owner = bot.getOwnerPlayer() ?: return false
            if (bot.distanceToSqr(owner) < (startDist * startDist).toDouble()) return false
            this.owner = owner
            return true
        }

        override fun canContinueToUse(): Boolean {
            return owner != null && bot.distanceToSqr(owner!!) > (stopDist * stopDist).toDouble()
        }

        override fun start() {
            timeToRecalcPath = 0
        }

        override fun stop() {
            owner = null
            bot.navigation.stop()
        }

        override fun tick() {
            bot.lookControl.setLookAt(owner!!, 10.0f, bot.maxHeadXRot.toFloat())
            if (--timeToRecalcPath <= 0) {
                timeToRecalcPath = 10
                bot.navigation.moveTo(owner!!.x, owner!!.y, owner!!.z, 1.2)
            }
        }
    }

    class CompanionSmartCombatGoal(private val bot: CompanionBotEntity) : Goal() {
        private var attackCooldown = 0

        init {
            this.flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            return bot.target != null && bot.target!!.isAlive && bot.forcedMode != ForcedMode.RUN_AWAY
        }

        override fun tick() {
            val target = bot.target ?: return
            bot.lookControl.setLookAt(target, 30.0f, 30.0f)
            val dist = bot.distanceTo(target)

            if (attackCooldown > 0) attackCooldown--

            if (dist > 3.5) {
                bot.navigation.moveTo(target.x, target.y, target.z, 1.3)
            } else {
                if (bot.health < bot.maxHealth * 0.4) {
                    val backPos = bot.position().add(bot.position().subtract(target.position()).normalize().scale(4.0))
                    bot.navigation.moveTo(backPos.x, backPos.y, backPos.z, 1.1)
                }
            }

            if (attackCooldown <= 0 && dist < 2.8) {
                if (bot.onGround() && bot.random.nextFloat() < 0.45f) {
                    bot.jumpFromGround()
                }
                val serverLevel = bot.level() as? ServerLevel
                if (serverLevel != null) {
                    bot.doHurtTarget(serverLevel, target)
                    attackCooldown = 12
                }
            }
        }
    }

    class CompanionEmergencyTeleportGoal(private val bot: CompanionBotEntity) : Goal() {
        override fun canUse(): Boolean {
            val owner = bot.getOwnerPlayer() ?: return false
            return bot.distanceToSqr(owner) > 2500.0
        }

        override fun start() {
            bot.recallNow()
        }
    }

    class CompanionEvadeDangerGoal(private val bot: CompanionBotEntity) : Goal() {
        private var danger: LivingEntity? = null

        init {
            this.flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            danger = bot.getDangerEntity()
            return danger != null
        }

        override fun tick() {
            danger?.let {
                val escapePos = bot.position().add(bot.position().subtract(it.position()).normalize().scale(8.0))
                bot.navigation.moveTo(escapePos.x, escapePos.y, escapePos.z, 1.5)
            }
        }
    }

    class CompanionForcedModeGoal(private val bot: CompanionBotEntity) : Goal() {
        override fun canUse(): Boolean = bot.forcedMode != ForcedMode.NONE

        override fun tick() {
            when (bot.forcedMode) {
                ForcedMode.ATTACK -> {}
                ForcedMode.FOLLOW -> {}
                ForcedMode.RUN_AWAY -> {
                    val owner = bot.getOwnerPlayer()
                    if (owner != null) {
                        val escapePos = bot.position().add(bot.position().subtract(owner.position()).normalize().scale(10.0))
                        bot.navigation.moveTo(escapePos.x, escapePos.y, escapePos.z, 1.4)
                    }
                }
                else -> {}
            }
        }
    }

    class CompanionOwnerTargetGoal(private val bot: CompanionBotEntity) : Goal() {
        override fun canUse(): Boolean {
            val owner = bot.getOwnerPlayer() ?: return false
            val ownerTarget = owner.lastHurtMob
            return ownerTarget != null && ownerTarget.isAlive && ownerTarget != bot
        }

        override fun start() {
            bot.target = bot.getOwnerPlayer()?.lastHurtMob
        }
    }

    class CompanionGatherDropsGoal(private val bot: CompanionBotEntity) : Goal() {
        private var gatherPos: Vec3? = null
        private var retargetCooldown = 0

        init {
            this.flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            if (bot.target != null || bot.isRetreating() || bot.getDangerEntity() != null) return false
            val owner = bot.getOwnerPlayer() ?: return false
            if (bot.distanceTo(owner) > 10f) return false
            
            val sl = bot.level() as? ServerLevel ?: return false
            val nearbyMobs = sl.getEntitiesOfClass(Monster::class.java, owner.boundingBox.inflate(16.0)) { it.isAlive }
            return nearbyMobs.isEmpty()
        }

        override fun canContinueToUse(): Boolean {
            val owner = bot.getOwnerPlayer() ?: return false
            return bot.distanceTo(owner) < 14f
        }

        override fun tick() {
            val sl = bot.level() as? ServerLevel ?: return
            bot.doGatherTick(sl)

            if (--retargetCooldown <= 0 || gatherPos == null) {
                gatherPos = bot.chooseGatherTarget(sl)
                retargetCooldown = 40
            }

            gatherPos?.let {
                bot.navigation.moveTo(it.x, it.y, it.z, 1.1)
            }
        }
    }

    private fun doGatherTick(level: ServerLevel) {
        val items = level.getEntitiesOfClass(ItemEntity::class.java, boundingBox.inflate(1.5))
        for (item in items) {
            if (item.isAlive) {
                val leftover = inventory.collectDrop(item.item)
                if (leftover.isEmpty) {
                    take(item, item.item.count)
                    item.discard()
                    level.playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5f, 1.2f)
                } else {
                    item.item = leftover
                }
            }
        }
    }

    private fun chooseGatherTarget(level: ServerLevel): Vec3? {
        val items = level.getEntitiesOfClass(ItemEntity::class.java, boundingBox.inflate(12.0))
        return items.firstOrNull { it.isAlive }?.position()
    }
}
