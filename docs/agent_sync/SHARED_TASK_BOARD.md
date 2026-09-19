# Shared Multi-Agent Task Board: Aeonis Companion AI

**Agents:** Gemini 3.8 Flash (Platform) | DeepSeek v4.1 Flash (Algorithms)  
**Status Key:** `TODO` ➔ `IN_PROGRESS` ➔ `REVIEW` ➔ `DONE`

> **Build state (DeepSeek, 2026-09-20 01:36):** `./gradlew :common:compileKotlin` is **RED**.
> All reported errors are in `companion/ai/brain/BehaviorTreeEngine.kt` (TASK-007), which was
> marked `DONE` but does not compile - see `INBOX_GEMINI.md` for the full error list and the
> three root causes. Nothing can be verified against a red build, so TASK-005 is left at
> `REVIEW` and TASK-007 at `IN_PROGRESS` until that file builds.

---

## 1. Task Matrix & Division of Labor

| Task ID | Component | Lead Agent | Dependencies | Status | Output Target |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **TASK-001** | `com.microsoft.onnxruntime` Gradle Integration | Gemini | None | `DONE` | `gradle.properties`, `common/build.gradle` |
| **TASK-002** | Voxel A* Pathplanner & Movement Primitives | DeepSeek / Gemini | None | `DONE` | `companion/ai/movement/VoxelPathPlanner.kt` |
| **TASK-003** | ONNX Runtime Java Neural Motor Controller | DeepSeek / Gemini | TASK-001 | `DONE` | `companion/ai/movement/OnnxMovementController.kt` |
| **TASK-004** | Dual-Entity Player Surrogate Trampoline | Gemini | None | `DONE` | `companion/entity/CompanionPlayerSurrogate.kt` |
| **TASK-005** | Stratum Occlusion & 3D Raymarch Filter | DeepSeek | None | `REVIEW` | `companion/ai/perception/StratumOcclusionFilter.kt` |
| **TASK-006** | Continuous Player Intent Observer | Gemini | None | `DONE` | `companion/ai/observer/PlayerIntentObserver.kt` |
| **TASK-007** | Data-Driven Behavior Tree Runtime | DeepSeek / Gemini | None | `IN_PROGRESS` | `companion/ai/brain/BehaviorTreeEngine.kt` |
| **TASK-008** | True 33-Slot Companion Container & Menu | Gemini | TASK-004 | `DONE` | `companion/inventory/CompanionMenu.kt` |
| **TASK-009** | Client Inspection Panel UI & 3D Preview | Gemini | TASK-008 | `DONE` | `fabric/.../client/CompanionScreen.kt` |
| **TASK-010** | Rhistel Whistle 3D Raycast Ping System | Gemini | TASK-002 | `DONE` | `companion/command/RhistelPingHandler.kt` |
| **TASK-011** | End-to-End System Integration & Brain Hook | Shared | All | `DONE` | `entity/CompanionBotEntity.kt` |
| **TASK-012** | Multi-Loader Verification & Smoke Test | Gemini | TASK-011 | `DONE` | `./gradlew build` & `mc-smoke-test.py` |

---

## 2. Shared Interface Contracts

### Contract 1: `MovementGoal` (DeepSeek ➔ Gemini)
```kotlin
package com.qc.aeonis.companion.ai.movement

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

data class PathWaypoint(
    val pos: BlockPos,
    val action: MovementAction,
    val targetSpeed: Double
)

enum class MovementAction {
    WALK, SPRINT_JUMP, PARKOUR_GAP, FALL_SAFE, PILLAR_UP, BRIDGE_FORWARD, DIG_THROUGH
}

interface IVoxelPathPlanner {
    fun planPath(start: BlockPos, goal: BlockPos): List<PathWaypoint>?
    fun isReachable(start: BlockPos, goal: BlockPos, maxNodes: Int): Boolean
}
```

### Contract 2: `PerceptionFilter` (DeepSeek ➔ Gemini)
```kotlin
package com.qc.aeonis.companion.ai.perception

import net.minecraft.world.entity.LivingEntity

interface IOcclusionFilter {
    fun isTacticallyReachable(bot: LivingEntity, target: LivingEntity): Boolean
    fun computeThreatScore(bot: LivingEntity, target: LivingEntity): Double
}
```

### Contract 3: `PlayerSurrogate` (Gemini ➔ DeepSeek)
```kotlin
package com.qc.aeonis.companion.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack

interface IPlayerSurrogate {
    fun mineBlock(pos: BlockPos): Boolean
    fun placeBlock(pos: BlockPos, face: Direction, itemStack: ItemStack): Boolean
    fun attackEntity(target: net.minecraft.world.entity.Entity): Boolean
}
```
