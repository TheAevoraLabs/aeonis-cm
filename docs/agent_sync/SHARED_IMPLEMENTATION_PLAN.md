# Master Dual-Agent Implementation Plan: Aeonis Companion AI

**Collaborative Engineering Between:**
- **Gemini 3.8 Flash (High):** Platform & Systems Architect
- **DeepSeek v4.1 Flash (High):** Algorithmic & AI Engine Lead

---

## 1. Model Capability Profiling & Workload Rationale

Based on recent benchmark evaluations and architectural profiling (MoE reasoning vs large-context agentic repo integration):

```
┌──────────────────────────────────────────────┬──────────────────────────────────────────────┐
│       DEEPSEEK v4.1 FLASH (HIGH)             │          GEMINI 3.8 FLASH (HIGH)             │
├──────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ Core Strength: Algorithmic & Formal Logic    │ Core Strength: Large Context & Tooling Flow  │
│ • Complex mathematical heuristics            │ • Broad multi-file codebase comprehension    │
│ • 3D graph search optimization (A*)          │ • Minecraft engine class hierarchy bridging  │
│ • Tensor shapes & native memory safety       │ • Network packet serializers & CustomPayloads│
│ • State machine & tree search structures     │ • Container screen layouts & client rendering│
│ • Pure logic and algorithmic unit density    │ • Build pipelines, Gradle & multi-loader test│
├──────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ Assigned Domain: AI Core & Mathematical Math │ Assigned Domain: Platform & Systems Bridge   │
└──────────────────────────────────────────────┴──────────────────────────────────────────────┘
```

---

## 2. Dual-Track Synchronized Milestone Schedule

```
  TRACK A: DEEPSEEK (AI & Algorithms)         TRACK B: GEMINI (Platform & Systems)
 ─────────────────────────────────────       ───────────────────────────────────────
 [Sprint 1: Algorithms Foundation]           [Sprint 1: Platform Foundation]
  TASK-005: Stratum Occlusion Raymarch        TASK-001: ONNX Gradle Setup
  TASK-002: Voxel A* Graph & Primitives       TASK-004: Player Surrogate Trampoline
                     │                                           │
                     └───────────────────┬───────────────────────┘
                                         ▼
                               [CHECKPOINT ALPHA]
                     Interface contracts verified between
                       Surrogate, Graph & Perception
                                         │
 ────────────────────────────────────────┼──────────────────────────────────────────
 [Sprint 2: Motor & Cognitive Engines]   │   [Sprint 2: UI, Intent & Commands]
  TASK-003: ONNX Runtime Controller      │    TASK-006: Player Intent Observer
  TASK-007: Behavior Tree Engine         │    TASK-008: 33-Slot Companion Menu
                                         │    TASK-009: Client Inspection Screen UI
                                         │    TASK-010: Rhistel 3D Ping System
                                         │
                     ┌───────────────────┴───────────────────────┐
                     ▼                                           ▼
                               [CHECKPOINT BETA]
                     Full Brain hooked into CompanionBotEntity;
                     Screen, Menu, and Surrogate wired up.
                                         │
 ────────────────────────────────────────┼──────────────────────────────────────────
 [Sprint 3: Joint Integration & QA]      │
  TASK-011: Full End-to-End System Brain Hook (Both Agents)
  TASK-012: Build & Multi-Loader Smoke Testing (Gemini Lead)
```

---

## 3. Detailed Workstream Plans

### Track A: DeepSeek v4.1 Flash Workstream

#### Milestone A1: Perception & Spatial Filtering (`TASK-005`)
- **Target File:** `common/src/main/kotlin/com/qc/aeonis/companion/ai/perception/StratumOcclusionFilter.kt`
- **Specification:**
  - Implement Amanatides-Woo fast voxel raycasting via `Level.clip(ClipContext)`.
  - Calculate stratum boundaries using `Heightmap.Types.MOTION_BLOCKING`.
  - Implement 32-step Breadth-First Search (BFS) corridor flood-fill to identify connected walkable air paths.
  - Implement dynamic threat weighting function:
    $$Score = \frac{\text{Damage} \times \text{HostilityFactor}}{\text{PathDistance} + 1.0} \times \text{LOSWeight}$$
- **Verification:** Unit logic confirming surface mobs ignore subterranean mobs at $Y-10$ if separated by solid stone.

#### Milestone A2: 3D Voxel Graph Pathfinding (`TASK-002`)
- **Target File:** `common/src/main/kotlin/com/qc/aeonis/companion/ai/movement/VoxelPathPlanner.kt`
- **Specification:**
  - Implement 3D A* graph search over Minecraft block coordinates.
  - Movement primitive definitions:
    - `WALK`: 1 block horizontal (cost = 1.0).
    - `SPRINT_JUMP`: 2-block horizontal gap with air below (cost = 1.2).
    - `PARKOUR_GAP`: 3-block horizontal gap with 1-block drop (cost = 1.6).
    - `FALL_SAFE`: Drop $\le 3$ blocks or into water/slime (cost = 0.8).
    - `PILLAR_UP`: Jump + place block beneath (cost = 3.0).
    - `BRIDGE_FORWARD`: Place block over void ahead (cost = 2.5).
    - `DIG_THROUGH`: Destroy obstructive block in 1x2 tunnel (cost = 4.0 + hardness).
  - Octile distance heuristic with vertical penalty:
    $$H(n) = D_{\text{horiz}} + 1.4 \times |\Delta Y|$$

#### Milestone A3: ONNX Learned Motor Controller (`TASK-003`)
- **Target File:** `common/src/main/kotlin/com/qc/aeonis/companion/ai/movement/OnnxMovementController.kt`
- **Specification:**
  - Load `data/aeonis/models/movement_policy.onnx` using `ai.onnxruntime.OrtEnvironment`.
  - Encode 137 input features:
    - 6 kinematic floats (velocity, onGround, inWater, collision).
    - 6 target delta floats $(\Delta x, \Delta y, \Delta z, \text{dist}, \text{yaw}, \text{pitch})$.
    - 125 local voxel occupancy floats ($5 \times 5 \times 5$ grid centered on bot eyes).
  - Execute native inference with off-heap FloatBuffer management and auto-cleanup.
  - Output parsed `MotorAction(forward, strafe, jump, sprint, sneak)`.

#### Milestone A4: Data-Driven Behavior Tree Runtime (`TASK-007`)
- **Target File:** `common/src/main/kotlin/com/qc/aeonis/companion/ai/brain/BehaviorTreeEngine.kt`
- **Specification:**
  - Node status enum: `RUNNING`, `SUCCESS`, `FAILURE`.
  - Node types: `Selector`, `Sequence`, `Parallel`, `Inverter`, `Action`, `Condition`.
  - Blackboard: Shared key-value memory (`targetEntity`, `gatherPos`, `currentMiningVein`, `alertState`).

---

### Track B: Gemini 3.8 Flash Workstream

#### Milestone B1: Gradle Setup & Dependencies (`TASK-001`)
- **Target Files:** `gradle.properties`, `common/build.gradle`
- **Specification:**
  - Add `onnxruntime_version=1.20.0`.
  - Add `implementation("com.microsoft.onnxruntime:onnxruntime:${project.onnxruntime_version}")`.
  - Verify Gradle configuration and Loom source mapping resolution.

#### Milestone B2: Dual-Entity Player Surrogate Trampoline (`TASK-004`)
- **Target File:** `common/src/main/kotlin/com/qc/aeonis/companion/entity/CompanionPlayerSurrogate.kt`
- **Specification:**
  - Implement headless `ServerPlayer` subclass with dummy `ServerGamePacketListenerImpl` that drops outgoing packets.
  - Bind surrogate lifecycle to `CompanionBotEntity`.
  - Implement `performPlayerBreakBlock(pos)` honoring tool durability, Fortune, Silk Touch, and land claims.
  - Implement `performPlayerPlaceBlock(pos, face, stack)` with full blockstate and orientation context.

#### Milestone B3: Continuous Player Intent Observer (`TASK-006`)
- **Target File:** `common/src/main/kotlin/com/qc/aeonis/companion/ai/observer/PlayerIntentObserver.kt`
- **Specification:**
  - 4-tick sliding observer tracking player held items, raycast block look vectors, health ratios, and action history.
  - State machine transitioning between `MINING`, `BUILDING`, `COMBAT`, `EMERGENCY`, `RESTING`.

#### Milestone B4: 33-Slot Inventory & Inspection Panel UI (`TASK-008`, `TASK-009`)
- **Target Files:**
  - `common/src/main/kotlin/com/qc/aeonis/companion/inventory/CompanionMenu.kt`
  - `fabric/src/client/kotlin/com/qc/aeonis/client/gui/CompanionScreen.kt`
- **Specification:**
  - 4 Armor slots, 2 Hand slots, 27-slot backpack grid.
  - Client GUI with live 3D entity renderer, health/armor telemetry, follow distance slider, and behavior toggle buttons.
  - Network sync via `CustomPacketPayload`.

#### Milestone B5: Rhistel 3D Ping System (`TASK-010`)
- **Target File:** `common/src/main/kotlin/com/qc/aeonis/companion/command/RhistelPingHandler.kt`
- **Specification:**
  - Shift + right-click raycast listener shooting acoustic beacon beam.
  - Dispatches contextual command based on hit type: Ground (Move & Hold), Ore (Mine Vein), Chest (Deposit), Mob (Focus Fire).

---

## 4. Checkpoint & Integration Protocol

1. **Daily Handoff / Turn-taking:**
   - DeepSeek pushes completed algorithms to `INBOX_GEMINI.md`.
   - Gemini tests compilation against Minecraft 26.3 mappings and provides code review / integration feedback in `INBOX_DEEPSEEK.md`.
2. **Final Verification (`TASK-012`):**
   - Clean build check: `./gradlew build`.
   - Smoke test check: `python3 scripts/mc-smoke-test.py` booting inside PolyMC.
