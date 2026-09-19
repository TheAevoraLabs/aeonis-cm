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

        data class CompanionSnapshot(val health: Float, val mode: Int)
        
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
    private var currentBreakingPos: BlockPos? = null
    private var breakProgress = 0f
    
    // True 33-slot Inventory & Player Surrogate Trampoline
    val inventory = CompanionInventory()
    private var surrogate: CompanionPlayerSurrogate? = null
    var brainTree: CompanionBehaviorTree? = null

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
        goalSelector.addGoal(4, CompanionSmartCombatGoal(this))
        goalSelector.addGoal(5, CompanionFollowOwnerGoal(this, 1.2, 8.0f, 3.0f))
        goalSelector.addGoal(6, CompanionGatherDropsGoal(this))
        goalSelector.addGoal(7, WaterAvoidingRandomStrollGoal(this, 1.0))
        goalSelector.addGoal(8, LookAtPlayerGoal(this, Player::class.java, 8.0f))
        goalSelector.addGoal(9, RandomLookAroundGoal(this))

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
        forcedMode = ForcedMode.entries[input.getIntOr("ForcedMode", 0).coerceIn(0, ForcedMode.entries.size - 1)]
        inventory.load(input)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        ownerUuid?.let { output.store("Owner", UUIDUtil.CODEC, it) }
        output.putInt("ForcedMode", forcedMode.ordinal)
        inventory.save(output)
    }

    fun createSnapshot() = CompanionSnapshot(health, forcedMode.ordinal)
    fun applySnapshot(snapshot: CompanionSnapshot) {
        health = snapshot.health
        forcedMode = ForcedMode.entries[snapshot.mode]
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

    fun tryPlaceBlock(pos: BlockPos, face: Direction = Direction.UP): Boolean {
        if (blockPlaceCooldown > 0 || level().isClientSide) return false
        val serverLevel = level() as? ServerLevel ?: return false
        if (!serverLevel.getBlockState(pos).isAir) return false

        val playerSurrogate = getOrCreateSurrogate()
        val blockStack = (CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE)
            .map { inventory.getItem(it) }
            .firstOrNull { it.item is net.minecraft.world.item.BlockItem }
            ?: return false

        val success = playerSurrogate?.placeBlock(pos, face, blockStack) ?: false
        if (success) {
            blockPlaceCooldown = 15
            level().playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1f, 1f)
        }
        return success
    }

    fun tryBreakBlock(pos: BlockPos): Boolean {
        if (blockBreakCooldown > 0 && currentBreakingPos != pos) return false
        val serverLevel = level() as? ServerLevel ?: return false
        val state = serverLevel.getBlockState(pos)
        if (state.isAir || state.getDestroySpeed(serverLevel, pos) < 0) return false

        if (currentBreakingPos != pos) {
            currentBreakingPos = pos
            breakProgress = 0f
            val bestTool = inventory.findBestTool(state)
            if (bestTool != inventory.mainHandItem) {
                inventory.mainHandItem = bestTool
            }
        }

        breakProgress += 0.25f

        if (breakProgress >= 1.0f) {
            val playerSurrogate = getOrCreateSurrogate()
            val success = playerSurrogate?.mineBlock(pos) ?: serverLevel.destroyBlock(pos, true, this)
            currentBreakingPos = null
            breakProgress = 0f
            blockBreakCooldown = 10
            return success
        }

        return false
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
