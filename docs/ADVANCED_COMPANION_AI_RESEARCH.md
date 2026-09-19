# Aeonis Companion Bot: Advanced Data-Driven AI & Architecture Research

**Document Version:** 1.0.0  
**Target Platform:** Minecraft 26.3 (Java 25)  
**Loaders:** Fabric (Loom 1.17+) & NeoForge (26.3.0.6-beta+)  
**Author:** Antigravity AI & Aevora Labs  
**Date:** September 2026  

---

## 1. Executive Summary & Vision

The **Aeonis Companion Bot** is designed to evolve from a rudimentary helper mob into an autonomous, intelligent, data-driven companion capable of human-like collaboration with the player. Rather than being restricted by Minecraft's primitive mob AI and rigid entity hierarchies, this architecture integrates:

1. **The Dual-Entity Trampoline Pattern**: Bridges the fundamental engine barrier between `Mob` and `ServerPlayer`, providing full player capabilities (true 36+ slot inventory, equipment, container GUIs, tool enchantments, block placement/breaking mechanics, and claim-mod compatibility) without sacrificing custom rendering or mob lifecycle stability.
2. **Hierarchical Neuro-Symbolic AI**: Combines high-level data-driven **Behavior Trees / Utility AI** (macro decisions) with mid-level **3D Voxel Graph Pathfinding** (A* movement primitives) and low-level **Learned Movement Policies** (lightweight Neural Networks running via **ONNX Runtime Java**).
3. **Advanced 3D Spatial Perception & Occlusion Engine**: Solves the notorious "staring at caves" flaw. Separates optical vision, line-of-sight raymarching, acoustic vibration listening, and topological reachability.
4. **Continuous Player Intent Monitoring**: Actively tracks the player's tools, gaze, health, actions, and environment to dynamically assist in mining, building, combat, and exploration without requiring explicit micromanagement.
5. **Comprehensive Interaction Suite**: Custom Container Screen & Status Panel, tactile Whistle/Ping system, Brigadier command grammar, and customizable progression.

---

## 2. Analysis of Current Companion Implementation (`aeonis-cm`)

### 2.1 Existing Codebase Overview
The existing companion implementation is situated in:
- `common/src/main/kotlin/com/qc/aeonis/entity/CompanionBotEntity.kt`
- `common/src/main/kotlin/com/qc/aeonis/companion/CompanionBotManager.kt`
- `common/src/main/kotlin/com/qc/aeonis/companion/CompanionCommands.kt`
- `common/src/main/kotlin/com/qc/aeonis/item/RhistelItem.kt`

### 2.2 Critical Limitations & Flaws Identified

| Feature Area | Current Implementation in `CompanionBotEntity.kt` | Critical Architectural Flaw |
| :--- | :--- | :--- |
| **Inventory & Loot** | Teleports items directly to the owner (`item.teleportTo(owner.x, owner.y, owner.z)`) when within 1.5 blocks. Blocks placed/mined are stored in a dummy `Map<Block, Int>`. | No true inventory container; companion cannot store items, wear armor, use tools, or open a player-accessible GUI screen. |
| **World Interaction** | Calls `level.destroyBlock(pos, true, this)` and `setBlock(...)` with hardcoded cooldown counters (`blockBreakCooldown`, `blockPlaceCooldown`). | Bypasses player tool durability, Fortune, Silk Touch, tool efficiency attributes, block placement directional properties, and grief-prevention/claims. |
| **Pathfinding** | Uses vanilla `PathfinderMob.navigation.moveTo(...)`. | Gets stuck on 1-block steps, fences, corners, and slabs; cannot perform parkour, bridge across chasms, dig through obstacles, or vault. |
| **Perception** | `Enemy` detection uses simple AABB bounding boxes (`boundingBox.inflate(24.0)`). | **Zero occlusion awareness.** Stares at hostile mobs located 10 blocks beneath the floor in closed, unreachable subterranean caves. |
| **Decision Architecture** | Hardcoded Java `GoalSelector` (`Goal` classes with `EnumSet.of(Flag.MOVE)`). | Rigid, non-extensible, difficult to add complex sequential behaviors or support datapack configurations. |
| **Commands & Modes** | Single enum `ForcedMode` (`NONE, ATTACK, FOLLOW, RUN_AWAY`) cycled through the `Rhistel` item. | Primitive state switching; lacks nuanced tactical orders (e.g., "mine this vein", "hold ground", "guard this door", "deposit loot"). |

---

## 3. The Minecraft Architecture Dilemma: Player vs. LivingEntity

### 3.1 The Fundamental Barrier in Minecraft's Engine
In Minecraft (1.21+ / 26.x), Mojang's class hierarchy fundamentally separates living beings into two distinct branches:
```
                      Entity
                        │
                  LivingEntity
                 ┌──────┴──────┐
                 │             │
                Mob          Player
                 │             │
           PathfinderMob   ServerPlayer
```

The engine exclusively reserves critical gameplay mechanisms for `ServerPlayer`:
1. **Interactive Screen Handlers (`AbstractContainerMenu` / `MenuProvider`)**: Opening container screens (chests, custom inventories, crafting tables, anvils) requires a `ServerPlayer` network recipient.
2. **Player Interaction Pipeline (`ServerPlayerGameMode`)**: Block breaking with mining fatigue, tool speed formulas, tool damage, and player-only drop loot tables (`LootContextParams.THIS_ENTITY == player`).
3. **Block Placement Context (`BlockPlaceContext` / `UseOnContext`)**: Computes block orientations (facing, half, axis) and triggers item consumption and block placement sounds/particles.
4. **Third-Party Mod Compatibility**: Land-claim mods (FTB Chunks, WorldGuard, FLAN, ClaimChunk) and server security plugins intercept events by checking `event.getPlayer()`. Standard mobs trigger griefing cancellation or fail permission checks.

### 3.2 Solution: The Dual-Entity "Surrogate Trampoline" Pattern
Rather than attempting to force `CompanionBotEntity` to extend `ServerPlayer` (which breaks custom mob models/animations and causes network packet crashes for headless entities), we deploy the **Dual-Entity Trampoline Pattern**:

```
 ┌─────────────────────────────────────────────────────────────┐
 │                  CompanionBotEntity (Physical Mob)          │
 │  - Extends PathfinderMob                                    │
 │  - Renders custom 3D model, animations, hitboxes            │
 │  - Runs AI Brain, Sensors, and Pathfinding                  │
 └──────────────────────────────┬──────────────────────────────┘
                                │ Controls & Delegates
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │             CompanionPlayerSurrogate (Shadow Trampoline)    │
 │  - Extends ServerPlayer (Headless / FakePlayer)             │
 │  - Holds true 36-slot Inventory + Equipment + EnderChest   │
 │  - Executes ServerPlayerGameMode (Mining, Placing, Using)   │
 │  - Dispatches Player Events to Server Engine & Mods         │
 └─────────────────────────────────────────────────────────────┘
```

#### How the Trampoline Operates:
1. **Lifecycle & Binding**:
   - Each `CompanionBotEntity` instantiates and maintains a headless `CompanionPlayerSurrogate` on the server level.
   - The surrogate possesses a unique `GameProfile` tied to the companion and owner:
     `GameProfile(UUID, "[Aeonis] " + owner.name.string)`
   - The surrogate's network listener is hooked into a no-op `ServerGamePacketListenerImpl` that silently drops outbound packets.
2. **Spatial Synchronization**:
   - Before any player action is executed, the surrogate is snapped to the bot's exact coordinate, rotation, and dimension:
     ```kotlin
     surrogate.setServerLevel(bot.serverLevel())
     surrogate.moveTo(bot.x, bot.y, bot.z, bot.yRot, bot.xRot)
     ```
3. **Action Execution Trampoline**:
   - **Block Breaking**: Executed via `surrogate.gameMode.destroyBlock(targetPos)`. Automatically applies pickaxe efficiency, tool durability loss, Fortune/Silk Touch, and drop generation.
   - **Block Placing**: Uses `surrogate.gameMode.useItemOn(surrogate, level, stack, hand, blockHitResult)`. Places blocks with proper block states (torches on walls, stairs facing correct orientations).
   - **Combat Attack**: Dispatches `surrogate.attack(targetEntity)`, allowing sweeping edge attacks, weapon criticals, and enchantment procs (Sharpness, Fire Aspect, Knockback).
4. **Cross-Loader Realization**:
   - **NeoForge**: Direct subclass of `net.neoforged.neoforge.common.util.FakePlayer`.
   - **Fabric**: Custom headless subclass of `ServerPlayer` using Fabric API's `net.fabricmc.fabric.api.entity.FakePlayer` or a custom dummy connection wrapper (`Connection(PacketFlow.SERVERBOUND)`).

---

## 4. Advanced 3D Spatial Perception & Occlusion Engine

### 4.1 The "Staring at Caves" Problem Solved
Vanilla Minecraft mob targeting relies on simple Euclidean distance:
$$\text{Distance} = \sqrt{\Delta x^2 + \Delta y^2 + \Delta z^2} \le R$$

When a player is on the surface ($Y=70$) and hostile mobs spawn in a cave at $Y=60$, the Euclidean distance is only 10 blocks. A standard mob AI will target the cave monster, stare blankly at the solid floor, and freeze.

### 4.2 Multi-Stage Spatial Filter Pipeline

```
  [Detected Entity within 24m]
               │
               ▼
   [Stage 1: Elevation & Stratum Check] 
         Are we separated by solid bedrock/stone layers?
         Target below Heightmap.Types.MOTION_BLOCKING?
               │  No (Same stratum or vertical shaft open)
               ▼
   [Stage 2: Line-of-Sight (LOS) Raymarching]
         Fast Voxel Clip via Level.clip(ClipContext)
               │
      ┌────────┴────────┐
   Unobstructed     Obstructed
      │                 │
      ▼                 ▼
 [Target Valid]   [Stage 3: Topological Reachability (BFS)]
                        Flood-fill air corridors (max 32 steps).
                        Can the bot walk to this entity without
                        mining through >2 solid stone blocks?
                              │
                      ┌───────┴───────┐
                   Path Open       Blocked / Subterranean
                      │                 │
                      ▼                 ▼
              [Tactical Engage]    [Mark Occluded]
                                   (Demote priority; do not stare)
```

### 4.3 Multi-Modal Sensor Architecture
To simulate genuine biological and artificial intelligence, the companion separates perception into distinct sensory channels:

```
                  ┌─────────────────────────────────────────┐
                  │          PERCEPTION SENSORS             │
                  └────┬───────────────┬───────────────┬────┘
                       │               │               │
                       ▼               ▼               ▼
                Optical Sensor   Acoustic Sensor  Tactical Sense
                - FOV Cone (120°) - Vibration /   - Owner Proximity
                - LOS Raycast     Sculk listener  - Threat Danger
                - Light Level    - Groans/Steps    Rating
```

1. **Optical Sensor (Sight)**:
   - Evaluates a 120° Field of View (FOV) cone in front of the companion's head.
   - Requires uninterrupted line-of-sight raycast.
   - Light-level sensitivity: In total darkness ($Light < 4$), optical range is reduced unless the bot has Night Vision or equipped headgear with illumination.
2. **Acoustic Sensor (Hearing)**:
   - Uses Minecraft's `VibrationSystem` / sound event detection (similar to the Warden).
   - "Hears" block breaking, footsteps, and mob groans through solid walls.
   - **Crucial Distinction**: Hearing does **not** cause the companion to stare at walls or try to attack. Instead, it updates the companion's internal *Threat Memory* and triggers informational dialogues (e.g., *"I hear skeletons rattling beneath our feet, stay alert"*).
3. **Tactical Sense (Spatial Danger Assessment)**:
   - Threat score formula:
     $$\text{ThreatScore} = \frac{\text{BaseHostility} \times \text{DamageOutput}}{\text{TopologicalDistance} + 1.0} \times \text{LOSMultiplier}$$
   - Creeper priming within 7 blocks gets instant maximum priority (triggering knockback attack or immediate retreat).

---

## 5. Continuous Player Intent Monitoring

Instead of awaiting manual instructions for every action, the companion observes the player's behavior in real time and classifies their current intent.

### 5.1 Player Observer Pipeline
Every 4 ticks (0.2s), the companion queries the owner's state:

```
 ┌─────────────────────────────────────────────────────────────┐
 │                     Player State Query                      │
 ├─────────────────────────────────────────────────────────────┤
 │ - Mainhand item (Pickaxe, Sword, Block, Axe, Food, Bow)     │
 │ - Action state (Mining block, Attacking, Sprinting, Eating) │
 │ - Raycast target (Looking at ore, hostile mob, crop, air)   │
 │ - Health & Effects (Low HP, Poisoned, Withered, Burning)    │
 │ - Environment (Light level, Under water, In lava, Falling)  │
 └──────────────────────────────┬──────────────────────────────┘
                                │
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                Intent Classification Engine                 │
 └──────────────────────────────┬──────────────────────────────┘
                                │
        ┌──────────────┬────────┼──────────────┬─────────────┐
        ▼              ▼        ▼              ▼             ▼
     MINING         COMBAT   BUILDING      EMERGENCY     EXPLORING
```

### 5.2 Dynamic Cooperative Behaviors

| Detected Player Intent | Trigger Conditions | Companion Autonomous Reaction |
| :--- | :--- | :--- |
| **MINING** | Player holding pickaxe and breaking stone/ore at depth ($Y < 50$). | - Stands 3 blocks back (never blocks player's line of swing).<br>- Detects exposed ore veins adjacent to player's shaft and mines them.<br>- Places torches if ambient light $< 7$.<br>- Vacuums cobblestone/ore drops into internal backpack. |
| **BUILDING** | Player holding building blocks and placing them repeatedly. | - Keeps clear of the player's construction bounding box.<br>- Automatically provides additional stacks of the block being placed if player runs out.<br>- Clears wandering mobs from the build site. |
| **COMBAT** | Player attacks or is damaged by a hostile mob. | - Executes tactical pincer movement (attacks from flank/rear).<br>- Prioritizes archers/skeletons shooting the player from a distance.<br>- Blocks creeper blasts with shield if equipped. |
| **EMERGENCY** | Player health falls below 30% ($< 6$ HP) or player catches fire. | - Moves between the threat and player (body-blocking).<br>- If carrying splash healing potions, throws one at the player's feet.<br>- If player is in fire, uses a water bucket to extinguish them. |
| **RESTING / IDLE** | Player stands motionless for $> 10$ seconds. | - Performs idle fidget animations (scans surroundings, sits down).<br>- Organizes internal inventory.<br>- Sharpens weapon (particle effect). |

---

## 6. Learned Movements & Machine Learning for Minecraft

### 6.1 Why Traditional Pathfinding Struggles in Minecraft
Standard Minecraft mob pathfinding (`PathNavigation`) operates on a 2D-biased A* grid that evaluates block traversability. It exhibits critical failure modes:
1. Cannot perform **parkour** (sprint-jumping over 2-3 block gaps).
2. Cannot **bridge** across chasms or **pillar** straight up.
3. Cannot **dynamically dig** through walls when blocked.
4. Lacks momentum conservation (stops moving before each jump).

### 6.2 Evaluation of Java-Compatible AI / ML Frameworks

We surveyed modern Java and JVM-compatible machine learning runtimes for running actual learned movement and tactical policies:

| Framework | JVM Compatibility | Inference Latency | Offline / Embedded Feasibility | Architectural Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **ONNX Runtime Java** (`com.microsoft.onnxruntime:onnxruntime`) | Full Java 8-25+ support. Bundled C++ native binaries for Linux, Win, Mac. | **Ultra-Fast (< 0.2ms)** per inference step on CPU. | **Excellent**. Self-contained inside the JAR; zero external network downloads. | **Recommended Primary Engine**. Industry standard for deploying trained PyTorch/TensorFlow policies to Java. |
| **Deep Java Library (DJL)** (`ai.djl:api`) | Excellent Kotlin/Java API. Supports PyTorch & ONNX backends. | Fast (< 0.5ms), but has higher memory allocation overhead. | Good, but default configuration attempts to download C++ engines dynamically at runtime. | Viable alternative if high-level tensor manipulation in Kotlin is required. |
| **DeepLearning4j (DL4J)** | Pure Java / ND4J backend. | Moderate (1-3ms). Heavy JAR size ($> 80\text{MB}$). | Fair; complex setup with Java 25 vector API changes. | Not recommended due to bloat and maintenance lag. |
| **Tribuo (Oracle Labs)** | Pure Java ML. | Fast for classical ML (Random Forests, SVMs), not deep RL. | Excellent, but lacks deep neural policy networks. | Good for tabular classification, unsuitable for continuous kinetic movement. |

### 6.3 The Hybrid Architecture: Voxel A* + Neural Motor Control
Pure end-to-end Reinforcement Learning (RL) across an infinite voxel world suffers from spatial hallucination. The proven, robust solution is a **Hierarchical Hybrid Model**:

```
 ┌─────────────────────────────────────────────────────────────┐
 │           High-Level: Macro Goal Selection                  │
 │           (Behavior Tree / Utility AI Engine)               │
 └──────────────────────────────┬──────────────────────────────┘
                                │ Emits Destination BlockPos
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │       Mid-Level: Voxel Path Planner (Baritone-Style A*)     │
 │  - Traverses 3D Voxel Graph using Action Primitives:        │
 │    [Walk, Sprint, Jump, Parkour, Bridge, Pillar, Tunnel]   │
 └──────────────────────────────┬──────────────────────────────┘
                                │ Emits Waypoint & Action Type
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │    Low-Level: Learned Neural Motor Policy (ONNX Runtime)    │
 │  - Inputs: Local 5x5x5 Voxel Grid + Relative Waypoint Vec   │
 │  - Outputs: Continuous Move (x, z), Sprint, Jump, Sneak,    │
 │             Air-Strafe, Pitch/Yaw rotations                 │
 └──────────────────────────────┘
```

#### Neural Network Policy Specification:
- **Architecture**: Lightweight Multi-Layer Perceptron (MLP) with Residual connections (3 hidden layers of 128 units, GELU activation). Total size: $< 1.5\text{MB}$.
- **Observation Space (Input Tensor)**:
  - Relative target vector: $(\Delta x, \Delta y, \Delta z, \text{distance}, \theta_{\text{yaw}}, \phi_{\text{pitch}})$
  - Current physical velocity: $(v_x, v_y, v_z, \text{onGround}, \text{inWater}, \text{horizontalCollision})$
  - 3D Local Voxel Occupancy Grid ($5 \times 5 \times 5 = 125$ floats: air=0, solid=1, hazard/lava=-1, climbable=0.5)
- **Action Space (Output Tensor)**:
  - Continuous movement forward/backward $[-1.0, 1.0]$
  - Continuous strafe left/right $[-1.0, 1.0]$
  - Discrete action probabilities: `[JUMP, SPRINT, SNEAK, PLACE_BLOCK, ATTACK]`
- **Training Pipeline**:
  - Trained offline in Python using **Isaac Gym** or a headless Minecraft simulation harness (Gym-Minecraft / MineRL) via **Proximal Policy Optimization (PPO)**.
  - Exported to `aeonis_movement_policy.onnx`.
  - Loaded at mod startup via `OrtSession` in Java. Inference executes on a dedicated asynchronous tick task or directly on entity tick in $< 0.15\text{ms}$.

---

## 7. True Resource Collection & Custom GUI Panel

### 7.1 Real Container & Inventory System
The companion is equipped with a genuine server-backed container:
- **Armor Slots (4)**: Head, Chest, Legs, Feet (Companion visibly renders equipped armor and gains full attribute defense/toughness).
- **Hand Slots (2)**: Mainhand (Weapons/Tools), Offhand (Shield, Torches, Totem of Undying).
- **Backpack Storage (27 slots)**: 3 rows of 9 slots for collected ores, drops, and building materials.
- **Data Persistence**: Serialized seamlessly into NBT / ValueInput via Mojang `ItemStack.CODEC` under the key `"CompanionInventory"`.

### 7.2 Custom Container Screen Architecture
When the player shifts + right-clicks the companion, the server dispatches a custom `MenuProvider`:

```
 ┌─────────────────────────────────────────────────────────────────────────┐
 │                      AEONIS COMPANION INSPECTION PANEL                 │
 ├─────────────────────────┬───────────────────────────────────────────────┤
 │     COMPANION PREVIEW   │               STATUS & TELEMETRY              │
 │                         │                                               │
 │         [ 3D ]          │ Name: Rhistel-Unit Alpha   [Rename]           │
 │       Animated          │ Health:  ████████████████████ 40.0 / 40.0     │
 │        Model            │ Armor:   ████████░░░░░░░░░░░░ 16.0 Points     │
 │       Preview           │ Energy:  ████████████████████ 100%            │
 │                         │ Mode:    [ DEFENSIVE GUARD ▼ ]                │
 │                         │ Current Task: Mining Exposed Diamond Ore     │
 ├─────────────────────────┴───────────────────────────────────────────────┤
 │                             EQUIPMENT SLOTS                             │
 │  [Helmet] [Chestplate] [Leggings] [Boots]    [Mainhand] [Offhand/Shield]│
 ├─────────────────────────────────────────────────────────────────────────┤
 │                         COMPANION BACKPACK (27 SLOTS)                   │
 │  [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ]                │
 │  [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ]                │
 │  [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ]                │
 ├─────────────────────────────────────────────────────────────────────────┤
 │                      BEHAVIOR TOGGLES & CAPABILITIES                    │
 │  [X] Auto-Loot Drops     [X] Illuminate Dark Caves   [X] Protect Owner  │
 │  [X] Mine Adjacent Ores  [ ] Aggressive Stance       [X] Avoid Creepers │
 │  Follow Distance: [────●────────────] 5.0 Blocks                        │
 ├─────────────────────────────────────────────────────────────────────────┤
 │                           PLAYER INVENTORY                              │
 │  [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ]                │
 │  [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ]                │
 │  [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ] [   ]                │
 │  [ 1 ] [ 2 ] [ 3 ] [ 4 ] [ 5 ] [ 6 ] [ 7 ] [ 8 ] [ 9 ]                │
 └─────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Advanced Tactical Command & Communication System

### 8.1 Multi-Tiered Command Inputs
Communication with the companion occurs across three intuitive tiers:
1. **Brigadier Chat Commands** (for scripting, macros, and distant recalls):
   - `/aeonis companion follow [tight | loose | stay]`
   - `/aeonis companion mine <block_tag> [radius]`
   - `/aeonis companion deposit [chest_coords]`
   - `/aeonis companion patrol [waypoint_name]`
   - `/aeonis companion stats`
2. **The Rhistel Whistle (Radial Wheel & Hotkey)**:
   - Holding right-click with the Rhistel displays an in-game radial selection wheel with sound feedback:
     - **Follow Me** (Recalls companion to owner's side).
     - **Hold Position** (Stays stationary, defends immediate perimeter).
     - **Scout Ahead** (Explores 30 blocks in look direction, reports threats).
     - **Assist / Work** (Toggles contextual mining/building help).
3. **Tactical Ping / Waypoint Marker (Raycast Command)**:
   - Shift + Right-clicking with the Rhistel shoots a directional acoustic beam.
   - **Ping on Ground**: Companion moves to and holds the pinged coordinate.
   - **Ping on Ore/Block**: Companion pathfinds to and extracts the pinged block vein.
   - **Ping on Chest/Container**: Companion deposits all gathered materials into that container.
   - **Ping on Hostile Mob**: Designates high-priority focus-fire target.

---

## 9. Implementation Roadmap & Technical Architecture Spec

```
[Phase 1: Engine Foundation]
  ├── CompanionPlayerSurrogate (Cross-loader headless player trampoline)
  ├── True 36-slot Inventory + Equipment + NBT Codecs
  └── Custom Container Screen & Inspection Panel UI

[Phase 2: Perception & Intent Engine]
  ├── StratumOcclusionDetector (Raymarching + Heightmap + BFS connectivity)
  ├── Optical vs Acoustic Sensory Fusion (FOV cone + Vibration listener)
  └── PlayerIntentObserver (Real-time activity classification)

[Phase 3: 3D Voxel Graph & ML Policy]
  ├── Voxel Graph Action Primitives (Walk, Sprint, Jump, Parkour, Bridge)
  ├── ONNX Runtime Java Integration (com.microsoft.onnxruntime:onnxruntime)
  └── Low-Level Neural Motor Policy Inference

[Phase 4: Tactical Commands & Extensibility]
  ├── Rhistel 3D Ping / Raycast Marker System
  ├── Datapack-Driven Behavior Trees (data/aeonis/companion_behaviors/)
  └── Multi-loader CI and Smoke Test Verification
```

### Conclusion
This architecture elevates the Aeonis Companion Bot from a basic follower entity into a state-of-the-art autonomous companion. By decoupling player capabilities via the **Surrogate Trampoline**, filtering sensory noise with **3D Stratum Occlusion**, and marrying **Voxel Path Planning** with **ONNX Learned Movement Policies**, Aeonis will feature one of the most advanced and responsive companion AIs in the Minecraft modding ecosystem.
