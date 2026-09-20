# Shared Multi-Agent Task Board: Aeonis Companion AI

**Agents:** Gemini 3.8 Flash (Platform & Systems) | DeepSeek v4.1 Flash (Algorithms & AI)  
**Status Key:** `TODO` ➔ `IN_PROGRESS` ➔ `REVIEW` ➔ `DONE`

---

## 1. Phase 1: Core Foundation Matrix (Completed)

| Task ID | Component | Lead Agent | Dependencies | Status | Output Target |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **TASK-001** | `com.microsoft.onnxruntime` Gradle Integration | Gemini | None | `DONE` | `gradle.properties`, `common/build.gradle` |
| **TASK-002** | Voxel A* Pathplanner & Movement Primitives | DeepSeek / Gemini | None | `DONE` | `companion/ai/movement/VoxelPathPlanner.kt` |
| **TASK-003** | ONNX Runtime Java Neural Motor Controller | DeepSeek / Gemini | TASK-001 | `DONE` | `companion/ai/movement/OnnxMovementController.kt` |
| **TASK-004** | Dual-Entity Player Surrogate Trampoline | Gemini | None | `DONE` | `companion/entity/CompanionPlayerSurrogate.kt` |
| **TASK-005** | Stratum Occlusion & 3D Raymarch Filter | DeepSeek | None | `DONE` | `companion/ai/perception/StratumOcclusionFilter.kt` |
| **TASK-006** | Continuous Player Intent Observer | Gemini | None | `DONE` | `companion/ai/observer/PlayerIntentObserver.kt` |
| **TASK-007** | Data-Driven Behavior Tree Runtime | DeepSeek / Gemini | None | `DONE` | `companion/ai/brain/BehaviorTreeEngine.kt` |
| **TASK-008** | True 33-Slot Companion Container & Menu | Gemini | TASK-004 | `DONE` | `companion/inventory/CompanionMenu.kt` |
| **TASK-009** | Client Inspection Panel UI & 3D Preview | Gemini | TASK-008 | `DONE` | `fabric/.../client/CompanionScreen.kt` |
| **TASK-010** | Rhistel Whistle 3D Raycast Ping System | Gemini | TASK-002 | `DONE` | `companion/command/RhistelPingHandler.kt` |
| **TASK-011** | End-to-End System Integration & Brain Hook | Shared | All | `DONE` | `entity/CompanionBotEntity.kt` |
| **TASK-012** | Multi-Loader Verification & Smoke Test | Gemini | TASK-011 | `DONE` | `./gradlew build` & `mc-smoke-test.py` |

---

## 2. Phase 2: Real-World Working Stage Matrix (Active Workstreams)

| Task ID | Component | Lead Agent | Dependencies | Status | Output Target |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **TASK-013** | Minecraft-Grade Companion GUI Redesign (Tabs, 3D Entity, Vanilla Slots) | **Gemini** | None | `DONE` | `fabric/.../client/CompanionScreen.kt` |
| **TASK-014** | Enhanced CompanionMenu Slot Validation & Shift-Click Routing | **Gemini** | TASK-013 | `DONE` | `companion/inventory/CompanionMenu.kt` |
| **TASK-015** | Persistent Companion Profile Database & First-Spawn Starter Kit | **Gemini** | None | `DONE` | `companion/profile/CompanionProfileManager.kt` |
| **TASK-016** | Non-Chat Animated Companion Speech HUD Overlay & Packet | **Gemini** | None | `DONE` | `client/hud/CompanionSpeechOverlay.kt` |
| **TASK-017** | Hunting Behavioral Constraints & Order Preemption Override | **DeepSeek** | None | `DONE` | `companion/skill/HuntingFeedingSkill.kt`, `companion/order/` |
| **TASK-018** | Anti-Zigzag Locomotion Smoothing & Swimming Propulsion | **DeepSeek** | None | `DONE` | `companion/ai/movement/WaypointStringPuller.kt`, `AquaticLocomotion.kt` |
| **TASK-019** | Dynamic 3D Scaffolding, Pillaring & Obstacle Clearance | **DeepSeek** | None | `DONE` | `companion/skill/ScaffoldingEngine.kt` |
| **TASK-020** | User Need Progressive Game Loop (Wood ➔ Stone ➔ Crafting ➔ Shelter ➔ Cooking) | **DeepSeek** | None | `DONE` | `companion/skill/ProgressionLoopSkill.kt`, `companion/progression/` |
| **TASK-021** | Sub-Agent (Mini-Bot) Architecture Specification | **DeepSeek** | None | `DONE` | `docs/COMPANION_SUBAGENT_DESIGN.md` |

---

## 3. Shared Interface Contracts

### Contract 4: `CompanionProfile` (Gemini ➔ DeepSeek)
```kotlin
package com.qc.aeonis.companion.profile

import java.util.UUID
import net.minecraft.world.item.ItemStack

data class CompanionProfile(
    val ownerUuid: UUID,
    var isFirstSpawn: Boolean = true,
    var expLevel: Int = 1,
    var bondLevel: Int = 1,
    var progressionStage: String = "WOOD",
    var customName: String? = null
)
```

### Contract 5: `SpeechNotification` (Gemini ➔ DeepSeek)
```kotlin
package com.qc.aeonis.companion.network

import net.minecraft.server.level.ServerPlayer

object CompanionSpeechNotifier {
    fun sendDialogue(player: ServerPlayer, speakerName: String, message: String, durationTicks: Int = 100)
}
```

### Contract 6: `ProgressionState` (DeepSeek ➔ Gemini)
```kotlin
package com.qc.aeonis.companion.progression

enum class ProgressionStage {
    WOOD_GATHERING,
    STONE_AGE,
    CRAFTING_SUPPORT,
    SHELTER_CONSTRUCTION,
    FURNACE_COOKING,
    EXPEDITION
}
```

### Contract 7: `SubAgentTask` (DeepSeek ➔ Gemini, spec)
```kotlin
package com.qc.aeonis.companion.subagent

sealed interface SubAgentTask {
    val label: String
    val targetCount: Int
}

data class FetchTask(override val targetCount: Int, val itemId: String, val searchRadius: Int = 48) : SubAgentTask
data class HuntTask(override val targetCount: Int, val entityTypeId: String? = null, val requireWeapon: Boolean = true, val searchRadius: Int = 40) : SubAgentTask
data class MineTask(override val targetCount: Int, val blockTagOrId: String, val searchRadius: Int = 40, val maxY: Int = 64) : SubAgentTask
data class GuardTask(val anchor: BlockPos, val radius: Double = 8.0, val holdTicks: Int = 6000) : SubAgentTask
```
Full specification, lifecycle and rationale: [`docs/COMPANION_SUBAGENT_DESIGN.md`](../COMPANION_SUBAGENT_DESIGN.md).

### Contract 8: Mini-Bot lifecycle (DeepSeek ➔ Gemini, spec)
`DEPLOY ➔ ARM ➔ EXECUTE ➔ RETURN ➔ DEPOSIT ➔ FAREWELL ➔ DESPAWN`, with forcing edges from any
state to `RETURN` on TTL expiry, death, owner disconnect, >96 blocks separation, or full
inventory. Items are never destroyed by the sub-agent system.

### Contract 9: `SubAgentScheduler` (DeepSeek ➔ Gemini, spec)
```kotlin
package com.qc.aeonis.companion.subagent

object SubAgentScheduler {
    const val MAX_PER_OWNER = 3
    const val MAX_GLOBAL = 24
    const val MAX_TTL_TICKS = 24_000

    fun deploy(owner: ServerPlayer, task: SubAgentTask, ttlTicks: Int = 6_000): DeploymentResult
    fun recallAll(owner: ServerPlayer): Int
    fun tick(server: MinecraftServer)
}
```
Leases are in-memory only and live in the scheduler, not on the entity — see §5.2 for why.
