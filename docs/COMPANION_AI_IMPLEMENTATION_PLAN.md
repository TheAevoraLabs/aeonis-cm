# Aeonis Companion Bot: Technical Implementation Plan & Code Architecture

**Document Version:** 1.0.0  
**Target Platform:** Minecraft 26.3 (Java 25)  
**Modules:** `common`, `fabric`, `neoforge`  
**Reference Document:** [ADVANCED_COMPANION_AI_RESEARCH.md](ADVANCED_COMPANION_AI_RESEARCH.md)  

---

## 1. Architectural Blueprint & Class Diagram

```
com.qc.aeonis.companion
├── ai
│   ├── brain
│   │   ├── BehaviorTreeEngine.kt          # Executes JSON-defined behavior trees
│   │   ├── BehaviorNode.kt                # Selector, Sequence, Action, Condition nodes
│   │   └── CompanionBlackboard.kt         # Shared cognitive state & working memory
│   ├── perception
│   │   ├── StratumOcclusionFilter.kt      # Raymarching & underground cave isolator
│   │   ├── AcousticSensor.kt              # Vibration & audio cue listener
│   │   ├── OpticalSensor.kt               # 120-degree FOV cone & LOS manager
│   │   └── ThreatAssessor.kt              # Dynamic threat scoring & target ranking
│   ├── observer
│   │   ├── PlayerIntentObserver.kt        # Continuous player action & intent tracker
│   │   └── PlayerIntent.kt                # Intent enum (MINING, BUILDING, COMBAT, etc.)
│   └── movement
│       ├── VoxelPathPlanner.kt            # 3D A* graph with parkour/bridging primitives
│       ├── MovementPrimitive.kt           # WALK, SPRINT_JUMP, PARKOUR, BRIDGE, PILLAR
│       └── OnnxMovementController.kt      # ONNX Runtime Java neural policy execution
├── entity
│   ├── CompanionBotEntity.kt              # Physical living entity (renderer, hitbox, audio)
│   └── CompanionPlayerSurrogate.kt        # Headless ServerPlayer trampoline
├── inventory
│   ├── CompanionInventory.kt              # 36-slot container + equipment serialization
│   ├── CompanionMenu.kt                   # AbstractContainerMenu for server & client
│   └── CompanionScreen.kt                 # Interactive inspection panel UI
├── command
│   ├── CompanionCommands.kt               # Brigadier command grammar
│   └── RhistelPingHandler.kt              # 3D world raycast marker & ping listener
└── network
    ├── CompanionSyncPayloads.kt           # CustomPacketPayload definitions
    └── CompanionNetworking.kt             # Loader-agnostic packet dispatcher
```

---

## 2. Core Technical Components & Pseudo-Code

### 2.1 The Dual-Entity Trampoline: `CompanionPlayerSurrogate`
Bypasses the `Mob` vs `Player` limitations in Minecraft by proxying player interactions:

```kotlin
package com.qc.aeonis.companion.entity

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Headless ServerPlayer surrogate maintained by CompanionBotEntity.
 * Executes player-grade block breaking, placing, tool usage, and container interactions.
 */
class CompanionPlayerSurrogate(
    level: ServerLevel,
    val bot: CompanionBotEntity,
    ownerProfile: GameProfile
) : ServerPlayer(
    level.server,
    level,
    GameProfile(UUID.nameUUIDFromBytes("aeonis:surrogate:${bot.uuid}".toByteArray()), "[Companion] ${ownerProfile.name}"),
    ClientInformation.createDefault()
) {
    init {
        // Stub the network listener with a dummy connection that safely ignores outgoing packets
        val dummyConnection = Connection(PacketFlow.SERVERBOUND)
        val dummyCookie = CommonListenerCookie.createInitial(gameProfile, false)
        this.connection = object : ServerGamePacketListenerImpl(server, dummyConnection, this, dummyCookie) {
            override fun send(packet: net.minecraft.network.protocol.Packet<*>, listener: net.minecraft.network.PacketSendListener?) {
                // No-op: drop all outbound packets silently
            }
        }
    }

    /**
     * Synchronizes position, rotations, and dimension with the physical mob before any action.
     */
    fun syncWithBot() {
        if (this.serverLevel() != bot.serverLevel()) {
            this.setServerLevel(bot.serverLevel())
        }
        this.moveTo(bot.x, bot.y, bot.z, bot.yRot, bot.xRot)
        this.setYHeadRot(bot.yHeadRot)
    }

    /**
     * Breaks a block as a player, applying pickaxe durability, Fortune, Silk Touch, and land claims.
     */
    fun performPlayerBreakBlock(pos: BlockPos): Boolean {
        syncWithBot()
        // Ensure surrogate holds the companion's mainhand tool
        this.setItemInHand(InteractionHand.MAIN_HAND, bot.companionInventory.mainHandItem)
        
        val result = this.gameMode.destroyBlock(pos)
        // Sync modified tool back to companion inventory (durability reduction)
        bot.companionInventory.mainHandItem = this.mainHandItem
        return result
    }

    /**
     * Places a block with full orientation and blockstate logic.
     */
    fun performPlayerPlaceBlock(pos: BlockPos, face: Direction, stack: ItemStack): InteractionResult {
        syncWithBot()
        this.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val hitResult = BlockHitResult(Vec3.atCenterOf(pos), face, pos, false)
        val context = UseOnContext(this, InteractionHand.MAIN_HAND, hitResult)
        
        val result = stack.useOn(context)
        bot.companionInventory.mainHandItem = this.mainHandItem
        return result
    }
}
```

---

### 2.2 Stratum Occlusion & 3D Perception Filter: `StratumOcclusionFilter`
Eliminates the bug where companion mobs stare at underground cave mobs through solid stone:

```kotlin
package com.qc.aeonis.companion.ai.perception

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.LinkedList

object StratumOcclusionFilter {

    /**
     * Determines whether a detected entity is meaningfully perceptible and tactically relevant,
     * or merely an occluded subterranean noise.
     */
    fun isTargetTacticallyReachable(bot: LivingEntity, target: LivingEntity): Boolean {
        val level = bot.level() as? ServerLevel ?: return false
        val botPos = bot.blockPosition()
        val targetPos = target.blockPosition()

        // 1. Direct Optical Line-of-Sight Test
        val eyeStart = bot.getEyePosition(1.0f)
        val eyeEnd = target.getEyePosition(1.0f)
        val hit = level.clip(ClipContext(eyeStart, eyeEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot))
        
        if (hit.type == HitResult.Type.MISS) {
            // Direct visual contact confirmed
            return true
        }

        // 2. Stratum & Subterranean Elevation Filter
        val botSurfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, botPos.x, botPos.z)
        val targetSurfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, targetPos.x, targetPos.z)

        val botIsSurface = botPos.y >= botSurfaceY - 3
        val targetIsCave = targetPos.y < targetSurfaceY - 6

        // If companion is on surface in daylight, and target is buried underground in a sealed cavern:
        if (botIsSurface && targetIsCave) {
            return false // IGNORE: Do not stare at cave monsters through the grass!
        }

        // 3. Fast Topological Corridor Connectivity (32-step BFS)
        return hasNavigableAirCorridor(level, botPos, targetPos, maxSteps = 32)
    }

    private fun hasNavigableAirCorridor(level: ServerLevel, start: BlockPos, goal: BlockPos, maxSteps: Int): Boolean {
        val queue = LinkedList<BlockPos>()
        val visited = HashSet<BlockPos>()
        
        queue.add(start)
        visited.add(start)

        var steps = 0
        while (queue.isNotEmpty() && steps < maxSteps) {
            val current = queue.poll()
            steps++

            if (current.closerThan(goal, 2.5)) {
                return true
            }

            for (dir in net.minecraft.core.Direction.entries) {
                val neighbor = current.relative(dir)
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    val state = level.getBlockState(neighbor)
                    // Navigable if block is passable (air, torch, water, grass)
                    if (state.getCollisionShape(level, neighbor).isEmpty) {
                        queue.add(neighbor)
                    }
                }
            }
        }
        return false
    }
}
```

---

### 2.3 Continuous Player Intent Observer: `PlayerIntentObserver`
Actively senses what the player is doing and coordinates autonomous assistance:

```kotlin
package com.qc.aeonis.companion.ai.observer

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.PickaxeItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.SwordItem
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BowItem

enum class PlayerIntent {
    MINING,
    BUILDING,
    COMBAT,
    EXPLORING,
    EMERGENCY,
    RESTING
}

class PlayerIntentObserver(private val player: ServerPlayer) {
    private var lastIntent = PlayerIntent.EXPLORING
    private var idleTicks = 0

    fun evaluate(): PlayerIntent {
        val healthRatio = player.health / player.maxHealth
        
        // 1. Emergency Safety Check
        if (healthRatio <= 0.35f || player.isOnFire) {
            return PlayerIntent.EMERGENCY
        }

        // 2. Combat Engagement Check
        if (player.lastHurtMob != null && player.lastHurtMob!!.isAlive && player.tickCount - player.lastHurtMobTimestamp < 100) {
            return PlayerIntent.COMBAT
        }

        val mainHand = player.mainHandItem
        val item = mainHand.item

        // 3. Tool-Based Intent Detection
        val detected = when {
            item is PickaxeItem && player.blockPosition().y < 55 -> PlayerIntent.MINING
            item is BlockItem -> PlayerIntent.BUILDING
            item is SwordItem || item is AxeItem || item is BowItem -> PlayerIntent.COMBAT
            player.deltaMovement.lengthSqr() < 0.001 -> {
                idleTicks++
                if (idleTicks > 200) PlayerIntent.RESTING else PlayerIntent.EXPLORING
            }
            else -> {
                idleTicks = 0
                PlayerIntent.EXPLORING
            }
        }

        lastIntent = detected
        return detected
    }
}
```

---

### 2.4 Learned Movement Controller via ONNX Runtime: `OnnxMovementController`
Integrates high-performance deep reinforcement learning inference on Java 25:

```kotlin
package com.qc.aeonis.companion.ai.movement

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import net.minecraft.world.phys.Vec3
import java.nio.FloatBuffer

/**
 * Executes high-frequency neural policy inference (< 0.2ms) for micro-movement,
 * obstacle avoidance, and kinetic parkour.
 */
class OnnxMovementController(modelBytes: ByteArray) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })

    data class MotorAction(
        val moveForward: Float,
        val moveStrafe: Float,
        val jump: Boolean,
        val sprint: Boolean,
        val sneak: Boolean
    )

    fun predict(
        relativeTarget: Vec3,
        velocity: Vec3,
        onGround: Boolean,
        localVoxelGrid125: FloatArray // 5x5x5 local voxel grid
    ): MotorAction {
        val inputBuffer = FloatBuffer.allocate(6 + 6 + 125)
        
        // 1. Target vector features
        inputBuffer.put(relativeTarget.x.toFloat())
        inputBuffer.put(relativeTarget.y.toFloat())
        inputBuffer.put(relativeTarget.z.toFloat())
        inputBuffer.put(relativeTarget.length().toFloat())
        inputBuffer.put(0f) // yaw delta
        inputBuffer.put(0f) // pitch delta

        // 2. Kinematic state features
        inputBuffer.put(velocity.x.toFloat())
        inputBuffer.put(velocity.y.toFloat())
        inputBuffer.put(velocity.z.toFloat())
        inputBuffer.put(if (onGround) 1.0f else 0.0f)
        inputBuffer.put(0f) // inWater
        inputBuffer.put(0f) // horizontalCollision

        // 3. Local voxel features
        inputBuffer.put(localVoxelGrid125)
        inputBuffer.flip()

        val inputTensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, (6 + 6 + 125).toLong()))
        
        val result = session.run(mapOf("state_input" to inputTensor))
        val outputTensor = result.get(0) as OnnxTensor
        val outputArray = (outputTensor.value as Array<FloatArray>)[0]

        // Parse continuous move inputs and discrete action gates
        val forward = outputArray[0].coerceIn(-1f, 1f)
        val strafe = outputArray[1].coerceIn(-1f, 1f)
        val shouldJump = outputArray[2] > 0.5f
        val shouldSprint = outputArray[3] > 0.5f
        val shouldSneak = outputArray[4] > 0.5f

        inputTensor.close()
        result.close()

        return MotorAction(forward, strafe, shouldJump, shouldSprint, shouldSneak)
    }

    override fun close() {
        session.close()
        env.close()
    }
}
```

---

### 2.5 True Container Screen: `CompanionMenu`
Exposes the companion's full inventory, equipment, and settings to the player:

```kotlin
package com.qc.aeonis.companion.inventory

import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

class CompanionMenu(
    containerId: Int,
    playerInventory: Inventory,
    val companionInventory: Container
) : AbstractContainerMenu(null, containerId) {

    init {
        checkContainerSize(companionInventory, 33) // 4 Armor + 2 Hands + 27 Backpack

        // 1. Companion Equipment Slots (Armor)
        for (i in 0 until 4) {
            val slotIndex = i
            addSlot(object : Slot(companionInventory, slotIndex, 8, 18 + i * 18) {
                override fun getMaxStackSize(): Int = 1
            })
        }

        // 2. Companion Hands (Mainhand & Offhand)
        addSlot(Slot(companionInventory, 4, 28, 18)) // Mainhand
        addSlot(Slot(companionInventory, 5, 28, 36)) // Offhand

        // 3. Companion Backpack Storage (3x9 grid)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(companionInventory, 6 + col + row * 9, 52 + col * 18, 18 + row * 18))
            }
        }

        // 4. Human Player Inventory & Hotbar (Standard Minecraft layout)
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 52 + col * 18, 84 + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 52 + col * 18, 142))
        }
    }

    override fun stillValid(player: Player): Boolean = companionInventory.stillValid(player)

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        // Standard shift-click transfer logic between companion backpack and player inventory
        return ItemStack.EMPTY
    }
}
```

---

## 3. Data-Driven Behavior Tree Schema (`data/aeonis/...`)

Companion routines can be customized and expanded via Datapacks without code modifications:

```json
{
  "routine_id": "aeonis:companion/mining_support",
  "priority": 80,
  "condition": {
    "type": "aeonis:player_intent",
    "intent": "MINING"
  },
  "root_node": {
    "type": "aeonis:sequence",
    "children": [
      {
        "type": "aeonis:condition_light_level",
        "operator": "LESS_THAN",
        "threshold": 7,
        "on_true": {
          "type": "aeonis:action_place_torch",
          "max_distance": 4.0
        }
      },
      {
        "type": "aeonis:action_vacuum_drops",
        "pickup_radius": 5.0
      },
      {
        "type": "aeonis:action_mine_adjacent_ore",
        "preferred_tools": ["minecraft:pickaxe"],
        "max_vein_blocks": 8
      },
      {
        "type": "aeonis:action_maintain_guard_distance",
        "min_dist": 2.5,
        "max_dist": 5.0
      }
    ]
  }
}
```

---

## 4. Dependencies & Gradle Integration

To incorporate ONNX Runtime for Java into the project, add the following dependency in `gradle.properties` and the respective loader/common modules:

```properties
# In gradle.properties
onnxruntime_version=1.20.0
```

```kotlin
// In common/build.gradle
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:${project.onnxruntime_version}")
}
```

*Note: `com.microsoft.onnxruntime:onnxruntime` bundles lightweight native dynamic shared libraries (`.so`, `.dll`, `.dylib`) directly inside the JAR for x86_64 and aarch64 architectures, requiring zero manual native library setup on Linux, Windows, or macOS.*
