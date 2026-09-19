# Aeonis AI: Custom Tooling & Agent Skills Architecture
**Status:** Preliminary Design Document (Design Phase — Non-Deployed)  
**Target Platform:** Minecraft 26.3 • Fabric & NeoForge (Java 25) • Aeonis Mod  
**Authors:** Gemini 3.8 Flash (Platform Lead) & DeepSeek v4.1 Flash (Algorithms Lead)

---

## 1. Executive Vision & Objectives

The goal of the **Custom Tooling and Agent Skills System** is to provide an extensible, modular, and data-driven layer that empowers both in-game companion entities and external AI agents (Gemini, DeepSeek, local LLM harnesses) to:
1. **Introspect available capabilities** via standardized, typed tool signatures.
2. **Execute atomic operations** (world manipulation, perception, inventory management) with deterministic validation and safety limits.
3. **Assemble composite skills** (e.g., `QuarryMining`, `FortificationBuilder`, `CropHarvesting`, `PerimeterPatrol`) declaratively via JSON datapacks or Kotlin DSL without modifying core entity code.
4. **Coordinate multi-agent workflows** where higher-level cognitive planners dispatch domain-specific tool calls to low-level motor and surrogate executors.

```
┌─────────────────────────────────────────────────────────────┐
│               High-Level Cognitive Agent / Brain            │
│       (LLM Planner / Behavior Tree / External Agent)        │
└───────────────┬─────────────────────────────┬───────────────┘
                │ Dispatches Skill            │ Dispatches Tool Call
                ▼                             ▼
┌─────────────────────────────┐ ┌─────────────────────────────┐
│     Agent Skill Engine      │ │     Agent Tool Registry     │
│  - Preconditions / Goals    │ │  - Schema & Signature       │
│  - Datapack JSON Skills     │ │  - Rate Limits & Safety     │
│  - State Machine Lifecycle  │ │  - ToolResult Serialization │
└───────────────┬─────────────┘ └─────────────┬───────────────┘
                │                             │
                └──────────────┬──────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────┐
│           Execution Trampoline & World Subsystems           │
│  - CompanionPlayerSurrogate (Block / Attack / Interact)     │
│  - VoxelPathPlanner (Kinetic 3D A* Parkour & Bridging)      │
│  - StratumOcclusionFilter (3D Raymarch & Threat Assessment) │
│  - CompanionInventory (33-Slot Container Management)        │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Part I: The Custom Tooling Framework (`agent.tooling`)

### 2.1 Tool Contract (`IAgentTool`)

Every atomic capability exposed to agents implements `IAgentTool`:

```kotlin
package com.qc.aeonis.companion.agent.tooling

import com.google.gson.JsonObject
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import com.qc.aeonis.entity.CompanionBotEntity

enum class ToolStatus {
    SUCCESS,
    FAILURE,
    INVALID_ARGS,
    RATE_LIMITED,
    PERMISSION_DENIED
}

data class ToolContext(
    val bot: CompanionBotEntity,
    val owner: ServerPlayer?,
    val callTick: Long
)

data class ToolResult(
    val status: ToolStatus,
    val data: JsonObject = JsonObject(),
    val feedback: Component = Component.empty()
)

interface IAgentTool {
    val id: String
    val description: String
    val parameterSchemaJson: JsonObject
    val isWorldModifying: Boolean
    val requiredPermissionLevel: Int get() = 0

    fun execute(context: ToolContext, parameters: JsonObject): ToolResult
}
```

### 2.2 Standard Built-in Toolsets

| Category | Tool ID | Parameters | Purpose |
| :--- | :--- | :--- | :--- |
| **Perception** | `query_stratum_threats` | `max_range: double` | Returns occluded vs reachable threats via `StratumOcclusionFilter`. |
| **Perception** | `probe_line_of_sight` | `target_pos: [x,y,z]` | Raymarches optical clearance between bot eye and target. |
| **Locomotion** | `plan_voxel_path` | `goal: [x,y,z], max_nodes: int` | Generates 3D parkour waypoints via `VoxelPathPlanner`. |
| **Locomotion** | `execute_motor_step` | `action: string, speed: double` | Triggers primitive (`SPRINT_JUMP`, `PARKOUR`, `BRIDGE`). |
| **Interaction** | `surrogate_mine_block` | `pos: [x,y,z], tool_type: string` | Proxies player-grade mining applying Fortune/Silk Touch. |
| **Interaction** | `surrogate_place_block` | `pos: [x,y,z], face: string, item: string` | Places block with orientation via headless surrogate. |
| **Logistics** | `sort_companion_backpack`| `strategy: string` | Auto-groups ores, stacks materials, organizes slots. |
| **Logistics** | `vacuum_item_entities` | `radius: double` | Pulls nearby dropped items into backpack. |
| **Logistics** | `deposit_into_chest` | `chest_pos: [x,y,z], filter_tag: string` | Deposits matched items into container. |

### 2.3 Tool Registry & Reflection-Free Introspection

```kotlin
object AgentToolRegistry {
    private val tools = LinkedHashMap<String, IAgentTool>()

    fun register(tool: IAgentTool) {
        tools[tool.id] = tool
    }

    fun get(id: String): IAgentTool? = tools[id]

    fun exportSchemaJson(): JsonObject {
        val root = JsonObject()
        tools.values.forEach { tool ->
            val toolDef = JsonObject()
            toolDef.addProperty("description", tool.description)
            toolDef.add("parameters", tool.parameterSchemaJson)
            root.add(tool.id, toolDef)
        }
        return root
    }
}
```

---

## 3. Part II: The Custom Agent Skill Engine (`agent.skill`)

### 3.1 What Constitutes a "Skill"?

A **Skill** is a goal-oriented, multi-step behavior policy. Unlike an atomic tool, a skill:
1. Has **Preconditions** (e.g., owner intent is `MINING`, player holds Rhistel, or companion holds a pickaxe).
2. Has **Required Tools** (declares dependencies on atomic tools).
3. Evaluates an **Execution Subtree** (Behavior Tree composite or Hierarchical Task Network).
4. Emits **Progress Telemetry** and handles **Graceful Interruption** (e.g., aborting a mining loop if a Creeper approaches).

### 3.2 Datapack-Driven Skill Specification (`data/aeonis/skills/*.json`)

Skills are hot-reloadable via Minecraft datapacks:

```json
{
  "skill_id": "aeonis:skill/quarry_excavation",
  "name": "Quarry Excavation Support",
  "priority": 75,
  "preconditions": [
    {
      "type": "companion_has_tool",
      "tag": "c:pickaxes"
    },
    {
      "type": "inventory_space_available",
      "minimum_empty_slots": 3
    }
  ],
  "required_tools": [
    "surrogate_mine_block",
    "vacuum_item_entities",
    "plan_voxel_path"
  ],
  "routine": {
    "type": "sequence",
    "steps": [
      {
        "type": "action_find_nearest_ore",
        "radius": 12,
        "store_as": "target_ore"
      },
      {
        "type": "action_navigate_adjacent",
        "target": "target_ore",
        "tolerance": 2.5
      },
      {
        "type": "action_mine_vein",
        "target": "target_ore",
        "max_vein_size": 16
      },
      {
        "type": "action_vacuum_drops",
        "radius": 5.0
      }
    ]
  },
  "interrupt_conditions": [
    {
      "type": "creeper_swelling_nearby",
      "distance": 8.0,
      "fallback_action": "flee_to_owner"
    }
  ]
}
```

### 3.3 Skill Lifecycle Interface

```kotlin
interface IAgentSkill {
    val id: String
    val priority: Int

    fun canActivate(ctx: ToolContext): Boolean
    fun onActivate(ctx: ToolContext)
    fun tick(ctx: ToolContext): SkillTickResult
    fun onComplete(ctx: ToolContext)
    fun onInterrupt(ctx: ToolContext, reason: String)
}

enum class SkillTickResult {
    CONTINUE,
    SUCCESS,
    FAILURE
}
```

---

## 4. Part III: Safety, Sandboxing & Rate Limiting

Because custom tooling allows programmatic block modification and surrogate actions, the runtime incorporates a triple-barrier security model:

1. **Spatial Bounding**: All operations are restricted to `CompanionBotEntity.boundingBox.inflate(24.0)`. No out-of-bounds remote quarrying or griefing.
2. **Rate Limiting (`SafetyLimiter`)**: Capped at `maxBlocksPerMinute` (default: 300 blocks/min) to prevent tick lag or server stall.
3. **Player Claim Interop**: Surrogate actions invoke `ServerPlayerGameMode.destroyBlock()` and `item.useOn(UseOnContext)`, naturally inheriting standard server protection plugins (FTB Chunks, WorldGuard, Flans).

---

## 5. Integration Roadmap

1. **Milestone 1 (Specification — Completed):** This design document (`docs/CUSTOM_TOOLING_AND_AGENT_SKILLS_DESIGN.md`).
2. **Milestone 2 (Foundation):** Implement `IAgentTool` interface, `AgentToolRegistry`, and 9 atomic built-in tools.
3. **Milestone 3 (Skill Runtime):** Implement Datapack JSON deserializer and connect `IAgentSkill` executor into `BehaviorTreeEngine.kt`.
4. **Milestone 4 (Telemetry & UI):** Expose active skills and telemetry readouts on `CompanionScreen.kt` and Rhistel whistle link.
