package com.qc.aeonis.companion.ai.brain

import com.qc.aeonis.companion.ai.movement.*
import com.qc.aeonis.companion.ai.observer.PlayerIntent
import com.qc.aeonis.companion.ai.perception.StratumOcclusionFilter
import com.qc.aeonis.companion.command.PingAction
import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.entity.CompanionBotEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

enum class BTStatus {
    SUCCESS,
    FAILURE,
    RUNNING
}

interface BTNode {
    fun tick(bb: CompanionBlackboard): BTStatus
    fun reset() {}
}

// ------------------------------------------------------------------ Composites

class SelectorNode(val children: List<BTNode>) : BTNode {
    private var runningIndex = 0

    override fun tick(bb: CompanionBlackboard): BTStatus {
        for (i in runningIndex until children.size) {
            val status = children[i].tick(bb)
            if (status == BTStatus.RUNNING) {
                runningIndex = i
                return BTStatus.RUNNING
            }
            if (status == BTStatus.SUCCESS) {
                runningIndex = 0
                return BTStatus.SUCCESS
            }
        }
        runningIndex = 0
        return BTStatus.FAILURE
    }

    override fun reset() {
        runningIndex = 0
        children.forEach { it.reset() }
    }
}

class SequenceNode(val children: List<BTNode>) : BTNode {
    private var runningIndex = 0

    override fun tick(bb: CompanionBlackboard): BTStatus {
        for (i in runningIndex until children.size) {
            val status = children[i].tick(bb)
            if (status == BTStatus.RUNNING) {
                runningIndex = i
                return BTStatus.RUNNING
            }
            if (status == BTStatus.FAILURE) {
                runningIndex = 0
                return BTStatus.FAILURE
            }
        }
        runningIndex = 0
        return BTStatus.SUCCESS
    }

    override fun reset() {
        runningIndex = 0
        children.forEach { it.reset() }
    }
}

class ParallelNode(val children: List<BTNode>, val requireAllSuccess: Boolean = false) : BTNode {
    override fun tick(bb: CompanionBlackboard): BTStatus {
        var anyRunning = false
        var anySuccess = false
        var allSuccess = true

        for (child in children) {
            val status = child.tick(bb)
            if (status == BTStatus.RUNNING) anyRunning = true
            if (status == BTStatus.SUCCESS) anySuccess = true
            if (status != BTStatus.SUCCESS) allSuccess = false
        }

        return when {
            anyRunning -> BTStatus.RUNNING
            requireAllSuccess -> if (allSuccess) BTStatus.SUCCESS else BTStatus.FAILURE
            anySuccess -> BTStatus.SUCCESS
            else -> BTStatus.FAILURE
        }
    }

    override fun reset() {
        children.forEach { it.reset() }
    }
}

// ------------------------------------------------------------------ Decorators

class InverterNode(val child: BTNode) : BTNode {
    override fun tick(bb: CompanionBlackboard): BTStatus {
        return when (child.tick(bb)) {
            BTStatus.SUCCESS -> BTStatus.FAILURE
            BTStatus.FAILURE -> BTStatus.SUCCESS
            BTStatus.RUNNING -> BTStatus.RUNNING
        }
    }

    override fun reset() = child.reset()
}

class CooldownNode(val cooldownTicks: Int, val child: BTNode) : BTNode {
    private var lastRunTick: Long = -9999L

    override fun tick(bb: CompanionBlackboard): BTStatus {
        val now = bb.bot.level().gameTime
        if (now - lastRunTick < cooldownTicks) {
            return BTStatus.FAILURE
        }
        val status = child.tick(bb)
        if (status != BTStatus.FAILURE) {
            lastRunTick = now
        }
        return status
    }

    override fun reset() {
        lastRunTick = -9999L
        child.reset()
    }
}

// ------------------------------------------------------------------ Leaves

class ActionNode(val name: String, val execute: (CompanionBlackboard) -> BTStatus) : BTNode {
    override fun tick(bb: CompanionBlackboard): BTStatus = execute(bb)
}

class ConditionNode(val name: String, val predicate: (CompanionBlackboard) -> Boolean) : BTNode {
    override fun tick(bb: CompanionBlackboard): BTStatus =
        if (predicate(bb)) BTStatus.SUCCESS else BTStatus.FAILURE
}

// ------------------------------------------------------------------ Blackboard

class CompanionBlackboard(val bot: CompanionBotEntity) {
    var currentTarget: LivingEntity? = null
    var targetPingPos: BlockPos? = null
    var pingAction: PingAction = PingAction.MOVE_AND_HOLD
    var currentPath: List<PathWaypoint>? = null
    var currentPathIndex: Int = 0
    var currentIntent: PlayerIntent = PlayerIntent.EXPLORING

    var miningTargetPos: BlockPos? = null
    var depositContainerPos: BlockPos? = null
    var lastPathRecalcTick: Long = 0L

    val memory = HashMap<String, Any?>()

    fun clearPing() {
        targetPingPos = null
        clearPath()
    }

    fun clearPath() {
        currentPath = null
        currentPathIndex = 0
    }

    fun setPath(waypoints: List<PathWaypoint>) {
        currentPath = waypoints
        currentPathIndex = 0
        lastPathRecalcTick = bot.level().gameTime
    }
}

// ------------------------------------------------------------------ Behavior Tree Engine

class CompanionBehaviorTree(val bot: CompanionBotEntity) : AutoCloseable {

    val blackboard = CompanionBlackboard(bot)
    var pathPlanner: VoxelPathPlanner? = null
    val motorController = OnnxMovementController()

    val root: BTNode = buildBrainTree()

    private fun getLevel(): ServerLevel? = bot.level() as? ServerLevel

    private fun getPlanner(level: ServerLevel): VoxelPathPlanner {
        val current = pathPlanner
        if (current == null || current.level !== level) {
            val fresh = VoxelPathPlanner(level)
            pathPlanner = fresh
            return fresh
        }
        return current
    }

    private fun buildBrainTree(): BTNode {
        return SelectorNode(
            listOf(
                // Priority 1: Emergency & Panic Response (Swelling Creeper / Low Health)
                buildEmergencySubtree(),

                // Priority 2: Rhistel Whistle Ping Override
                buildRhistelPingSubtree(),

                // Priority 3: Autonomous Skills (Hunting & Feeding, Vacuum Scavenger, Crafting, Building)
                buildSkillsSubtree(),

                // Priority 4: Tactical Combat Support with Stratum Occlusion
                buildCombatSubtree(),

                // Priority 5: Player Intent Synergy (Mining Support, Torch Placement)
                buildIntentSynergySubtree(),

                // Priority 6: Follow Owner with Dynamic Parkour
                buildFollowOwnerSubtree(),

                // Priority 7: Idle Standby & Guard
                buildIdleSubtree()
            )
        )
    }

    private fun buildSkillsSubtree(): BTNode {
        return ActionNode("ExecuteAutonomousSkills") { bb ->
            val result = bb.bot.skillManager.tick()
            when (result.status) {
                com.qc.aeonis.companion.skill.SkillStatus.SUCCESS -> BTStatus.SUCCESS
                com.qc.aeonis.companion.skill.SkillStatus.RUNNING -> BTStatus.RUNNING
                else -> BTStatus.FAILURE
            }
        }
    }

    private fun buildEmergencySubtree(): BTNode {
        return SequenceNode(
            listOf(
                ConditionNode("IsEmergency") { bb ->
                    val botEntity = bb.bot
                    val serverLevel = getLevel() ?: return@ConditionNode false
                    val nearbyCreepers = serverLevel.getEntitiesOfClass(
                        Creeper::class.java, botEntity.boundingBox.inflate(7.0)
                    ) { creeper -> creeper.isAlive && (creeper.swellDir > 0 || creeper.getSwelling(1.0f) > 0f) }

                    val lowHealth = botEntity.health < (botEntity.maxHealth * 0.25f)
                    nearbyCreepers.isNotEmpty() || (lowHealth && botEntity.lastHurtByMob != null)
                },
                ActionNode("EvadeHazard") { bb ->
                    val botEntity = bb.bot
                    val serverLevel = getLevel() ?: return@ActionNode BTStatus.FAILURE
                    val dangerousMob = serverLevel.getEntitiesOfClass(
                        LivingEntity::class.java, botEntity.boundingBox.inflate(8.0)
                    ) { targetEntity -> targetEntity is Creeper && targetEntity.isAlive }.firstOrNull() ?: botEntity.lastHurtByMob

                    if (dangerousMob != null) {
                        val fleeDir = botEntity.position().subtract(dangerousMob.position()).normalize()
                        val targetPos = botEntity.blockPosition().offset(
                            (fleeDir.x * 10).toInt(), 0, (fleeDir.z * 10).toInt()
                        )
                        bb.setPath(listOf(PathWaypoint(targetPos, MovementAction.SPRINT_JUMP, 1.4)))
                        botEntity.moveControl.setWantedPosition(
                            botEntity.x + fleeDir.x * 8, botEntity.y, botEntity.z + fleeDir.z * 8, 1.4
                        )
                    }
                    BTStatus.SUCCESS
                }
            )
        )
    }

    private fun buildRhistelPingSubtree(): BTNode {
        return SequenceNode(
            listOf(
                ConditionNode("HasPing") { bb -> bb.targetPingPos != null },
                SelectorNode(
                    listOf(
                        // Action: Mine Target Block
                        SequenceNode(
                            listOf(
                                ConditionNode("IsPingMine") { bb ->
                                    bb.pingAction == PingAction.MINE_BLOCK
                                },
                                ActionNode("ExecutePingMine") { bb ->
                                    val target = bb.targetPingPos ?: return@ActionNode BTStatus.FAILURE
                                    val botEntity = bb.bot
                                    val serverLevel = getLevel() ?: return@ActionNode BTStatus.FAILURE
                                    val dist = botEntity.blockPosition().distManhattan(target)

                                    if (dist <= 4) {
                                        botEntity.lookControl.setLookAt(target.x + 0.5, target.y + 0.5, target.z + 0.5)
                                        val broken = botEntity.tryBreakBlock(target)
                                        if (broken || serverLevel.getBlockState(target).isAir) {
                                            bb.clearPing()
                                            return@ActionNode BTStatus.SUCCESS
                                        }
                                        BTStatus.RUNNING
                                    } else {
                                        navigateTowards(bb, target)
                                        BTStatus.RUNNING
                                    }
                                }
                            )
                        ),

                        // Action: Deposit into Container
                        SequenceNode(
                            listOf(
                                ConditionNode("IsPingDeposit") { bb ->
                                    bb.pingAction == PingAction.DEPOSIT_CONTAINER
                                },
                                ActionNode("ExecutePingDeposit") { bb ->
                                    val target = bb.targetPingPos ?: return@ActionNode BTStatus.FAILURE
                                    val botEntity = bb.bot
                                    val serverLevel = getLevel() ?: return@ActionNode BTStatus.FAILURE
                                    val dist = botEntity.blockPosition().distManhattan(target)

                                    if (dist <= 4) {
                                        val blockEntity = serverLevel.getBlockEntity(target)
                                        if (blockEntity is Container) {
                                            depositItemsIntoContainer(botEntity.inventory, blockEntity)
                                        }
                                        bb.clearPing()
                                        BTStatus.SUCCESS
                                    } else {
                                        navigateTowards(bb, target)
                                        BTStatus.RUNNING
                                    }
                                }
                            )
                        ),

                        // Action: Move and Hold Position
                        ActionNode("ExecutePingMoveHold") { bb ->
                            val target = bb.targetPingPos ?: return@ActionNode BTStatus.FAILURE
                            val botEntity = bb.bot
                            if (botEntity.blockPosition().closerThan(target, 2.0)) {
                                bb.clearPing()
                                BTStatus.SUCCESS
                            } else {
                                navigateTowards(bb, target)
                                BTStatus.RUNNING
                            }
                        }
                    )
                )
            )
        )
    }

    private fun buildCombatSubtree(): BTNode {
        return SequenceNode(
            listOf(
                ActionNode("SelectTacticalTarget") { bb ->
                    val botEntity = bb.bot
                    val serverLevel = getLevel() ?: return@ActionNode BTStatus.FAILURE
                    val candidates = serverLevel.getEntitiesOfClass(
                        LivingEntity::class.java, botEntity.boundingBox.inflate(StratumOcclusionFilter.MAX_RANGE)
                    ) { candidate: LivingEntity ->
                        candidate != botEntity && candidate.isAlive &&
                                (candidate is Enemy || candidate.lastHurtMob === botEntity || candidate.lastHurtMob === botEntity.getOwnerPlayer())
                    }

                    var bestTarget: LivingEntity? = null
                    var bestScore = 0.0

                    for (candidate in candidates) {
                        if (StratumOcclusionFilter.isTacticallyReachable(botEntity, candidate)) {
                            val score = StratumOcclusionFilter.computeThreatScore(botEntity, candidate)
                            if (score > bestScore) {
                                bestScore = score
                                bestTarget = candidate
                            }
                        }
                    }

                    bb.currentTarget = bestTarget
                    botEntity.target = bestTarget
                    if (bestTarget != null) BTStatus.SUCCESS else BTStatus.FAILURE
                },
                ActionNode("EngageCombatTarget") { bb ->
                    val botEntity = bb.bot
                    val target = bb.currentTarget ?: return@ActionNode BTStatus.FAILURE
                    if (!target.isAlive) {
                        bb.currentTarget = null
                        botEntity.target = null
                        return@ActionNode BTStatus.SUCCESS
                    }

                    val serverLevel = getLevel() ?: return@ActionNode BTStatus.FAILURE
                    val distSqr = botEntity.distanceToSqr(target)
                    botEntity.lookControl.setLookAt(target, 30.0f, 30.0f)

                    if (distSqr <= 9.0) { // Melee range <= 3.0 blocks
                        val surrogate = botEntity.getOrCreateSurrogate()
                        val hit = surrogate?.attackEntity(target) ?: botEntity.doHurtTarget(serverLevel, target)
                        if (hit) {
                            botEntity.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true)
                        }
                        BTStatus.RUNNING
                    } else {
                        navigateTowards(bb, target.blockPosition())
                        BTStatus.RUNNING
                    }
                }
            )
        )
    }

    private fun buildIntentSynergySubtree(): BTNode {
        return SelectorNode(
            listOf(
                // 1. Vacuum nearby item drops (within 8 blocks)
                SequenceNode(
                    listOf(
                        ConditionNode("HasNearbyDrops") { bb ->
                            val botEntity = bb.bot
                            val serverLevel = getLevel() ?: return@ConditionNode false
                            val items = serverLevel.getEntitiesOfClass(
                                ItemEntity::class.java, botEntity.boundingBox.inflate(8.0)
                            ) { drop: ItemEntity -> drop.isAlive && !drop.hasPickUpDelay() }
                            items.isNotEmpty()
                        },
                        ActionNode("VacuumItemDrops") { bb ->
                            val botEntity = bb.bot
                            val serverLevel = getLevel() ?: return@ActionNode BTStatus.FAILURE
                            val items = serverLevel.getEntitiesOfClass(
                                ItemEntity::class.java, botEntity.boundingBox.inflate(8.0)
                            ) { drop: ItemEntity -> drop.isAlive && !drop.hasPickUpDelay() }

                            val nearest = items.minByOrNull { drop -> botEntity.distanceToSqr(drop) } ?: return@ActionNode BTStatus.FAILURE
                            if (botEntity.distanceToSqr(nearest) <= 4.0) {
                                val stack = nearest.item
                                val remainder = botEntity.inventory.addItem(stack)
                                if (remainder.isEmpty) {
                                    nearest.discard()
                                } else {
                                    nearest.item = remainder
                                }
                                BTStatus.SUCCESS
                            } else {
                                navigateTowards(bb, nearest.blockPosition())
                                BTStatus.RUNNING
                            }
                        }
                    )
                ),

                // 2. Mining Synergy: Place Torches in Dark Caves
                SequenceNode(
                    listOf(
                        ConditionNode("IsPlayerMining") { bb ->
                            bb.currentIntent == PlayerIntent.MINING
                        },
                        CooldownNode(60, ActionNode("PlaceTorchInDark") { bb ->
                            val botEntity = bb.bot
                            val serverLevel = getLevel() ?: return@ActionNode BTStatus.FAILURE
                            val pos = botEntity.blockPosition()
                            if (serverLevel.getBrightness(LightLayer.BLOCK, pos) < 7) {
                                val hasTorch = (CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE)
                                    .any { idx -> botEntity.inventory.getItem(idx).`is`(Items.TORCH) }
                                if (hasTorch) {
                                    botEntity.tryPlaceBlock(pos, Direction.UP)
                                    return@ActionNode BTStatus.SUCCESS
                                }
                            }
                            BTStatus.FAILURE
                        })
                    )
                )
            )
        )
    }

    private fun buildFollowOwnerSubtree(): BTNode {
        return SequenceNode(
            listOf(
                ConditionNode("HasOwner") { bb -> bb.bot.getOwnerPlayer() != null },
                ActionNode("FollowOwnerPath") { bb ->
                    val botEntity = bb.bot
                    val owner = botEntity.getOwnerPlayer() ?: return@ActionNode BTStatus.FAILURE
                    val distSqr = botEntity.distanceToSqr(owner)

                    // Emergency recall if excessively far (> 48 blocks)
                    if (distSqr > 2304.0) {
                        botEntity.recallNow()
                        return@ActionNode BTStatus.SUCCESS
                    }

                    // Stop if comfortably close (within 3.5 blocks)
                    if (distSqr <= 12.25) {
                        bb.clearPath()
                        return@ActionNode BTStatus.SUCCESS
                    }

                    // Steer towards owner using Voxel PathPlanner
                    navigateTowards(bb, owner.blockPosition())
                    BTStatus.RUNNING
                }
            )
        )
    }

    private fun buildIdleSubtree(): BTNode {
        return ActionNode("IdleGuard") { bb ->
            val botEntity = bb.bot
            val owner = botEntity.getOwnerPlayer()
            if (owner != null) {
                botEntity.lookControl.setLookAt(owner, 10.0f, 10.0f)
            }
            BTStatus.SUCCESS
        }
    }

    // ------------------------------------------------------------------ Movement & Navigation

    private fun navigateTowards(bb: CompanionBlackboard, targetPos: BlockPos) {
        val botEntity = bb.bot
        val serverLevel = getLevel() ?: return
        val now = botEntity.level().gameTime
        val planner = getPlanner(serverLevel)

        // Recalculate path every 20 ticks or when path is missing
        if (bb.currentPath == null || bb.currentPathIndex >= bb.currentPath!!.size || (now - bb.lastPathRecalcTick > 20)) {
            val planned = planner.planPath(botEntity.blockPosition(), targetPos)
            if (planned != null && planned.isNotEmpty()) {
                bb.setPath(planned)
            }
        }
    }

    fun tick() {
        val serverLevel = getLevel() ?: return
        getPlanner(serverLevel)

        // 1. Refresh observer blackboard state
        blackboard.currentIntent = bot.getPlayerIntent()

        // 2. Execute Behavior Tree
        root.tick(blackboard)

        // 3. Drive physical micro-locomotion along current path
        driveMovement()
    }

    private fun driveMovement() {
        val path = blackboard.currentPath ?: return
        val idx = blackboard.currentPathIndex
        if (idx >= path.size) {
            blackboard.clearPath()
            return
        }

        val currentWaypoint = path[idx]
        val targetPos = currentWaypoint.pos
        val targetCenter = Vec3.atBottomCenterOf(targetPos)
        val relative = targetCenter.subtract(bot.position())
        val distHorizontal = sqrt(relative.x * relative.x + relative.z * relative.z)

        // Check if waypoint reached
        if (distHorizontal < 0.65 && kotlin.math.abs(relative.y) < 1.25) {
            blackboard.currentPathIndex++
            return
        }

        // Handle waypoint action primitives
        when (currentWaypoint.action) {
            MovementAction.DIG_THROUGH -> {
                bot.tryBreakBlock(targetPos)
            }
            MovementAction.BRIDGE_FORWARD -> {
                bot.tryPlaceBlock(targetPos.below(), Direction.UP)
            }
            MovementAction.PILLAR_UP -> {
                bot.tryPlaceBlock(bot.blockPosition().below(), Direction.UP)
                bot.jumpControl.jump()
            }
            MovementAction.SPRINT_JUMP, MovementAction.PARKOUR_GAP -> {
                bot.isSprinting = true
                if (bot.onGround() && distHorizontal < 2.5) {
                    bot.jumpControl.jump()
                }
            }
            MovementAction.WALK, MovementAction.FALL_SAFE -> {
                // Standard walking
            }
        }

        // Neural policy or kinematic heuristic inference
        val voxelGrid = motorController.sampleLocalVoxelGrid(bot)
        val motorAction = motorController.predict(
            relativeTarget = relative,
            velocity = bot.deltaMovement,
            onGround = bot.onGround(),
            inWater = bot.isInWater,
            horizontalCollision = bot.horizontalCollision,
            localVoxelGrid125 = voxelGrid
        )

        // Apply motor actions to living entity controls
        bot.moveControl.setWantedPosition(
            targetCenter.x, targetCenter.y, targetCenter.z,
            currentWaypoint.targetSpeed * (if (motorAction.sprint) 1.25 else 1.0)
        )
        if (motorAction.jump && bot.onGround()) {
            bot.jumpControl.jump()
        }
        if (motorAction.sprint) {
            bot.isSprinting = true
        }
    }

    private fun depositItemsIntoContainer(source: CompanionInventory, destination: Container) {
        for (i in CompanionInventory.BACKPACK_START until CompanionInventory.CONTAINER_SIZE) {
            val stack = source.getItem(i)
            if (stack.isEmpty) continue

            // Don't deposit torches or weapons/tools
            if (stack.`is`(Items.TORCH) || stack.`is`(ItemTags.PICKAXES) || stack.`is`(ItemTags.SWORDS) ||
                stack.`is`(ItemTags.AXES) || stack.`is`(ItemTags.SHOVELS) || stack.`is`(ItemTags.HOES)) {
                continue
            }

            var remaining = stack.copy()
            for (destSlot in 0 until destination.containerSize) {
                val destStack = destination.getItem(destSlot)
                if (destStack.isEmpty) {
                    destination.setItem(destSlot, remaining)
                    source.setItem(i, ItemStack.EMPTY)
                    break
                } else if (ItemStack.isSameItemSameComponents(destStack, remaining)) {
                    val transferable = minOf(remaining.count, destStack.maxStackSize - destStack.count)
                    if (transferable > 0) {
                        destStack.grow(transferable)
                        remaining.shrink(transferable)
                        if (remaining.isEmpty) {
                            source.setItem(i, ItemStack.EMPTY)
                            break
                        } else {
                            source.setItem(i, remaining)
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        motorController.close()
    }
}
