package com.qc.aeonis.companion.ai.brain

import com.qc.aeonis.companion.ai.movement.*
import com.qc.aeonis.companion.ai.observer.PlayerIntent
import com.qc.aeonis.companion.ai.perception.StratumOcclusionFilter
import com.qc.aeonis.companion.command.PingAction
import com.qc.aeonis.companion.inventory.CompanionInventory
import com.qc.aeonis.companion.order.CompanionOrder
import com.qc.aeonis.companion.order.OrderGovernor
import com.qc.aeonis.companion.order.WeaponSelector
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

    /**
     * Block position the current path was planned toward.
     *
     * Stored so a replan can be skipped when the destination has not meaningfully moved: an
     * owner walking in a straight line used to trigger a fresh A* search every second, and the
     * resulting alternation between plans is a large part of what the zigzag complaint was
     * actually about.
     */
    var lastPathTarget: BlockPos? = null

    /** Governor verdict for this tick, filled by [CompanionBehaviorTree.tick]. */
    var verdict: OrderGovernor.OrderVerdict? = null

    /** Latched position for a `HOLD` order, so hold means "here", not "wherever I drifted to". */
    var holdAnchor: BlockPos? = null

    val memory = HashMap<String, Any?>()

    fun clearPing() {
        targetPingPos = null
        clearPath()
    }

    fun clearPath() {
        currentPath = null
        currentPathIndex = 0
        lastPathTarget = null
    }

    fun setPath(waypoints: List<PathWaypoint>) {
        currentPath = waypoints
        currentPathIndex = 0
        lastPathRecalcTick = bot.level().gameTime
    }
}

// ------------------------------------------------------------------ Behavior Tree Engine

class CompanionBehaviorTree(val bot: CompanionBotEntity) : AutoCloseable {

    companion object {
        /** Navigation speed used when answering a bodyguard trigger. */
        const val BODYGUARD_SPRINT_SPEED = 1.5

        /** Squared follow distance at which the companion stops closing on the owner. */
        const val FOLLOW_STOP_DISTANCE_SQR = 12.25

        /**
         * Minimum ticks between A* replans toward the same destination.
         *
         * Thirty ticks (1.5s) is long enough that a straight, string-pulled path survives the
         * whole traverse, and short enough that a player who turns a corner is followed within a
         * blink. Replanning every tick was a second, quieter source of visible zigzag.
         */
        const val PATH_RECALC_INTERVAL_TICKS = 30L

        /** Distance a destination may drift before the existing path is considered invalid. */
        const val PATH_TARGET_TOLERANCE = 2.0
    }

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

    /**
     * Root selector. Order is the whole design.
     *
     * | # | subtree | why here |
     * | :--- | :--- | :--- |
     * | 1 | bodyguard | the owner is in danger; nothing else matters |
     * | 2 | emergency | the companion itself is about to die |
     * | 3 | Rhistel ping | an explicit, momentary instruction outranks a standing order |
     * | 4 | order enforcement | `HOLD`/`FOLLOW`/`RETREAT` beat autonomy |
     * | 5 | skills | autonomy, already gated by the same governor |
     * | 6 | combat | target selection with stratum occlusion |
     * | 7 | intent synergy | opportunistic torches and drop vacuuming |
     * | 8 | follow owner | default movement |
     * | 9 | idle | look at the owner and stand by |
     *
     * Bodyguard above emergency is intentional: if the owner is being attacked, a companion that
     * flees because its own health is low has failed at the only job it has.
     */
    private fun buildBrainTree(): BTNode {
        return SelectorNode(
            listOf(
                buildBodyguardSubtree(),
                buildEmergencySubtree(),
                buildRhistelPingSubtree(),
                buildOrderEnforcementSubtree(),
                buildSkillsSubtree(),
                buildCombatSubtree(),
                buildIntentSynergySubtree(),
                buildFollowOwnerSubtree(),
                buildIdleSubtree()
            )
        )
    }

    /** Cached governor verdict for this tick, evaluating on demand if the tick has not filled it. */
    private fun verdictOf(bb: CompanionBlackboard): OrderGovernor.OrderVerdict =
        bb.verdict ?: OrderGovernor.evaluate(bb.bot).also { bb.verdict = it }

    /**
     * Emergency bodyguard response.
     *
     * Fires while the governor's bodyguard latch is live — i.e. the owner was attacked recently
     * or is on low health. It aborts every skill and path in flight, arms the companion with its
     * best melee weapon, and either engages the attacker or sprints to the owner.
     *
     * Aborting in-flight work here rather than relying on the skill manager is deliberate: the
     * owner can be attacked on a tick where the active skill is mid-swing with a live target, and
     * that target has to be released before the companion can answer the real threat.
     */
    private fun buildBodyguardSubtree(): BTNode {
        return SequenceNode(
            listOf(
                ConditionNode("IsBodyguardLatchLive") { bb -> verdictOf(bb).isBodyguard },
                ActionNode("DefendOwner") { bb ->
                    val botEntity = bb.bot
                    val verdict = verdictOf(bb)
                    val serverLevel = getLevel() ?: return@ActionNode BTStatus.FAILURE

                    bb.bot.skillManager.stopActiveSkill("bodyguard: ${verdict.reason}")
                    bb.clearPath()

                    // Highest-DPS melee weapon, never fists. If there is none, the companion
                    // still advances and uses its natural attack damage.
                    WeaponSelector.equipBestMeleeWeapon(botEntity)

                    val hostile = OrderGovernor.selectBodyguardTarget(botEntity, verdict)
                        ?: OrderGovernor.nearestHostileToBot(botEntity, 12.0)

                    if (hostile != null && hostile.isAlive) {
                        botEntity.lookControl.setLookAt(hostile, 30.0f, 30.0f)
                        if (botEntity.distanceToSqr(hostile) <= 9.0) {
                            val weapon = botEntity.inventory.mainHandItem
                            val surrogate = botEntity.getOrCreateSurrogate()
                            val hit = surrogate?.attackEntity(hostile, weapon)
                                ?: botEntity.doHurtTarget(serverLevel, hostile)
                            if (hit) {
                                botEntity.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true)
                            }
                            return@ActionNode BTStatus.RUNNING
                        }
                        pursue(botEntity, hostile.blockPosition(), BODYGUARD_SPRINT_SPEED)
                        return@ActionNode BTStatus.RUNNING
                    }

                    // No threat left standing: get to the owner and stay there.
                    val owner = botEntity.getOwnerPlayer() ?: return@ActionNode BTStatus.FAILURE
                    if (botEntity.distanceToSqr(owner) > 12.25) {
                        pursue(botEntity, owner.blockPosition(), BODYGUARD_SPRINT_SPEED)
                        return@ActionNode BTStatus.RUNNING
                    }
                    BTStatus.SUCCESS
                }
            )
        )
    }

    /**
     * Standing-order enforcement.
     *
     * Runs only when the governor says an order is preempting autonomy, which is exactly the set
     * `HOLD`, `FOLLOW` and `RETREAT`. Each branch stops skills and navigation first, because the
     * point of an order is to *end* what was happening, not to queue behind it.
     */
    private fun buildOrderEnforcementSubtree(): BTNode {
        return SequenceNode(
            listOf(
                ConditionNode("HasPreemptingOrder") { bb ->
                    verdictOf(bb).mode == OrderGovernor.AgentMode.ORDER_ENFORCED
                },
                ActionNode("EnforceStandingOrder") { bb ->
                    val botEntity = bb.bot
                    val verdict = verdictOf(bb)
                    val owner = botEntity.getOwnerPlayer()

                    when (verdict.order) {
                        CompanionOrder.HOLD -> {
                            bb.bot.skillManager.stopActiveSkill("order HOLD")
                            val anchor = bb.holdAnchor ?: botEntity.blockPosition().also { bb.holdAnchor = it }
                            if (botEntity.blockPosition().distSqr(anchor) > 4.0) {
                                bb.clearPath()
                                pursue(botEntity, anchor, 1.2)
                            } else {
                                bb.clearPath()
                                botEntity.navigation.stop()
                                owner?.let { botEntity.lookControl.setLookAt(it, 10.0f, 10.0f) }
                            }
                            BTStatus.RUNNING
                        }

                        CompanionOrder.RETREAT -> {
                            bb.bot.skillManager.stopActiveSkill("order RETREAT")
                            val threat = OrderGovernor.nearestHostileToBot(botEntity, 16.0)
                            val origin = threat?.position() ?: owner?.position() ?: botEntity.position()
                            val away = botEntity.position().subtract(origin)
                            val escape = if (away.lengthSqr() < 1.0e-4) Vec3(1.0, 0.0, 0.0) else away.normalize()
                            val destination = botEntity.blockPosition().offset(
                                (escape.x * 8).toInt(), 0, (escape.z * 8).toInt()
                            )
                            bb.clearPath()
                            pursue(botEntity, destination, 1.45)
                            BTStatus.RUNNING
                        }

                        CompanionOrder.FOLLOW -> {
                            if (owner == null) return@ActionNode BTStatus.FAILURE
                            bb.bot.skillManager.stopActiveSkill("order FOLLOW")
                            if (botEntity.distanceToSqr(owner) <= FOLLOW_STOP_DISTANCE_SQR) {
                                bb.clearPath()
                                BTStatus.SUCCESS
                            } else {
                                navigateTowards(bb, owner.blockPosition())
                                BTStatus.RUNNING
                            }
                        }

                        // ATTACK/GUARD/HUNT/FREE/FREE do not set preemptsAutonomy, so reaching
                        // here means the order changed mid-tick; decline and re-evaluate.
                        else -> BTStatus.FAILURE
                    }
                }
            )
        )
    }

    /**
     * Direct steering for short, urgent moves where a full A* plan would cost more than it
     * saves. Used by bodyguard and order enforcement only.
     */
    private fun pursue(botEntity: CompanionBotEntity, destination: BlockPos, speed: Double) {
        botEntity.navigation.moveTo(
            destination.x + 0.5,
            destination.y.toDouble(),
            destination.z + 0.5,
            speed
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
                        // Fight with the best weapon available, never with fists if a weapon is
                        // in the bag — combat and hunting share the same arming rule.
                        WeaponSelector.equipBestMeleeWeapon(botEntity)
                        val weapon = botEntity.inventory.mainHandItem
                        val surrogate = botEntity.getOrCreateSurrogate()
                        val hit = surrogate?.attackEntity(target, weapon)
                            ?: botEntity.doHurtTarget(serverLevel, target)
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

    /**
     * Ensures the blackboard holds a fresh path toward [targetPos].
     *
     * A replan happens only when the previous path is exhausted, when the destination has moved
     * more than [PATH_TARGET_TOLERANCE] blocks, and at most every [PATH_RECALC_INTERVAL_TICKS]
     * ticks. All three conditions are needed: without the destination check the companion
     * replans on a timer while walking straight, and each new A* search can pick a different
     * equally-optimal staircase, which the entity then visibly follows.
     */
    private fun navigateTowards(bb: CompanionBlackboard, targetPos: BlockPos) {
        val botEntity = bb.bot
        val serverLevel = getLevel() ?: return
        val now = botEntity.level().gameTime
        val planner = getPlanner(serverLevel)

        val path = bb.currentPath
        val pathExhausted = path == null || bb.currentPathIndex >= path.size
        val targetMoved = bb.lastPathTarget?.let { !it.closerThan(targetPos, PATH_TARGET_TOLERANCE) } ?: true
        val recalcDue = now - bb.lastPathRecalcTick >= PATH_RECALC_INTERVAL_TICKS

        if (pathExhausted || (targetMoved && recalcDue)) {
            val planned = planner.planPath(botEntity.blockPosition(), targetPos)
            if (planned != null && planned.isNotEmpty()) {
                bb.setPath(planned)
                bb.lastPathTarget = targetPos
            } else if (pathExhausted) {
                // Genuinely unreachable: clear so callers stop driving toward a dead path, and
                // fall back to the vanilla navigator, which can still round simple corners the
                // voxel planner declined to model.
                bb.clearPath()
                botEntity.navigation.moveTo(
                    targetPos.x + 0.5, targetPos.y.toDouble(), targetPos.z + 0.5, 1.2
                )
            }
        }
    }

    fun tick() {
        val serverLevel = getLevel() ?: return
        getPlanner(serverLevel)

        // 1. Refresh observer blackboard state
        blackboard.currentIntent = bot.getPlayerIntent()

        // 2. Evaluate the governor once and share the verdict with every subtree, so all of them
        //    act on the same decision within a tick rather than each re-deriving it.
        val verdict = OrderGovernor.evaluate(bot)
        blackboard.verdict = verdict

        // 3. Keep the HOLD anchor pinned to the order that latched it: a new order releases the
        //    old anchor, and re-issuing HOLD later latches a fresh one.
        if (verdict.order != CompanionOrder.HOLD) {
            blackboard.holdAnchor = null
        }

        // 4. Execute the behaviour tree.
        root.tick(blackboard)

        // 5. Drive physical micro-locomotion along the current path. Movement is suppressed while
        //    a HOLD is being enforced, so a stale path cannot walk the companion off its post.
        if (verdict.mode == OrderGovernor.AgentMode.ORDER_ENFORCED && verdict.order == CompanionOrder.HOLD) {
            return
        }
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

        // Waypoint action primitives are delegated to the scaffolding engine, which owns tool
        // selection, material consumption and the refusal reasons. The engine returns RUNNING
        // while a multi-tick action is still in progress; the waypoint is only considered reached
        // once the entity is physically at it, so no progress tracking is needed here.
        val engine = bot.scaffoldingEngine
        when (currentWaypoint.action) {
            MovementAction.DIG_THROUGH -> {
                engine.clearObstacle(targetPos)
            }
            MovementAction.BRIDGE_FORWARD -> {
                engine.bridgeForward(directionToward(bot, targetPos))
            }
            MovementAction.PILLAR_UP -> {
                engine.pillarUp()
            }
            MovementAction.SPRINT_JUMP, MovementAction.PARKOUR_GAP -> {
                bot.isSprinting = true
                if (bot.onGround() && distHorizontal < 2.5) {
                    bot.jumpFromGround()
                }
            }
            MovementAction.WALK, MovementAction.FALL_SAFE -> {
                // Standard walking; nothing to prepare.
            }
        }

        // Water is its own motion regime. Buoyancy and the swim stroke are applied here and the
        // method returns, because the walking pipeline below would fight them: it drives the
        // entity downward toward `targetCenter.y` and can only jump on the ground, which is
        // exactly how the companion used to sink.
        if (bot.isInWater) {
            val heading = Vec3(relative.x, 0.0, relative.z)
            AquaticLocomotion.stroke(bot, heading, currentWaypoint.targetSpeed > 1.2)
            bot.moveControl.setWantedPosition(
                targetCenter.x, bot.y, targetCenter.z,
                currentWaypoint.targetSpeed * 0.5
            )
            return
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
            bot.jumpFromGround()
        }
        if (motorAction.sprint) {
            bot.isSprinting = true
        }
    }

    /**
     * Cardinal direction from the companion toward [target], preferring the dominant axis so a
     * diagonal destination still yields a legal (non-vertical) bridging direction.
     */
    private fun directionToward(botEntity: CompanionBotEntity, target: BlockPos): Direction {
        val current = botEntity.blockPosition()
        val dx = target.x - current.x
        val dz = target.z - current.z
        if (dx == 0 && dz == 0) return Direction.fromYRot(botEntity.yRot.toDouble())
        return if (kotlin.math.abs(dx) >= kotlin.math.abs(dz)) {
            if (dx >= 0) Direction.EAST else Direction.WEST
        } else {
            if (dz >= 0) Direction.SOUTH else Direction.NORTH
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
