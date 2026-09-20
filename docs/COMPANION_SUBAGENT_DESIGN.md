# Aeonis Companion Sub-Agent (Mini-Bot) Architecture

**Task:** TASK-021 · **Author:** DeepSeek v4.1 Flash (Algorithms & AI Engine Lead)
**Reviewer:** Gemini 3.8 Flash (Platform & Systems Architect)
**Target:** Aeonis 5.0.0 · Minecraft 26.3 · Java 25 · Kotlin 2.4.20

---

## 1. Goal

Let the player split work off the companion: *"go get me 16 oak logs"*, *"hunt three cows"*,
*"mine me some coal"*, *"watch this spot"*. Each request spawns a small, disposable helper that
does exactly one job, hands the results back to the companion, and disappears.

The design constraints, in priority order:

1. **One task, no chains of thought.** A Mini-Bot never decides what job is most important —
   the player or the companion does. It has no progression ladder, no hunting quota, no orders.
2. **Ephemeral and bounded.** Every Mini-Bot has a hard lifetime and a hard world budget. A
   player must never be able to fill a server with them, deliberately or by accident.
3. **Reuse, not reimplementation.** Locomotion, mining, container interaction and item
   accounting already exist and are already hardened. A Mini-Bot is a *configuration* of those
   systems plus a lifetime, not a second AI.
4. **Results are never lost.** A Mini-Bot that is killed, times out or despawns unexpectedly
   deposits whatever it is carrying; the only acceptable loss case is that it had not yet
   picked anything up.
5. **Cheap when idle.** The whole feature costs ~nothing on a server with no Mini-Bots, and
   scales linearly in ticks with the number deployed, not with the world size.

---

## 2. What already exists and is reused verbatim

| Capability | Existing component | How Mini-Bots use it |
| :--- | :--- | :--- |
| Voxel A* + string pulling | `companion/ai/movement/VoxelPathPlanner.kt`, `WaypointStringPuller.kt` | Pathing to a task target, unchanged |
| Motor execution | `OnnxMovementController` + `MotorBackend` | One shared controller instance per deployment batch, not per bot |
| Water traversal | `AquaticLocomotion` | Swim strokes across rivers |
| World interaction | `ScaffoldingEngine` | `mineBlock(pos)`, `clearObstacle`, `pillarUp`, `bridgeForward`, `vacuumDrops` |
| Inventory accounting | `ItemLedger`, `FoodLedger` | "do I have X yet?" / "how many did I get?" |
| Item collection | `CompanionInventory.collectDrop` | Backpack insertion with remainder handling |
| Behaviour tree nodes | `behavior_tree/BehaviorTreeEngine.kt` (`Selector`, `Sequence`, `Action`, `Condition`, `Cooldown`) | Tasks are small trees built from these nodes |
| Player-grade actions | `CompanionPlayerSurrogate` / `IPlayerSurrogate` | Mining, placing and attacking as a player |
| Perception | `StratumOcclusionFilter` | Threat checks for `GUARD` |

The deliberate consequence: **a Mini-Bot adds no new movement, mining or combat code.** If a
Mini-Bot mines worse than the companion, the fix belongs in `ScaffoldingEngine`, and both improve.

---

## 3. Task model

### 3.1 Contract 7 — `SubAgentTask`

```kotlin
package com.qc.aeonis.companion.subagent

/**
 * A single, parameterised job. Sealed so a task is exhaustive over the type system:
 * adding a task type is a compile error until every executor and codec is updated.
 */
sealed interface SubAgentTask {
    val label: String

    /** Item filter a FETCH/MINE/HUNT task is looking for. */
    val targetCount: Int
}

data class FetchTask(
    override val targetCount: Int,
    val itemId: String,          // registry id, e.g. "minecraft:oak_log"
    val searchRadius: Int = 48
) : SubAgentTask { override val label = "Fetch ${itemId.substringAfter(':')} ×$targetCount" }

data class HuntTask(
    override val targetCount: Int,
    val entityTypeId: String? = null,   // null = any animal
    val requireWeapon: Boolean = true,
    val searchRadius: Int = 40
) : SubAgentTask { override val label = "Hunt ${entityTypeId?.substringAfter(':') ?: "animals"} ×$targetCount" }

data class MineTask(
    override val targetCount: Int,
    val blockTagOrId: String,    // "#minecraft:coal_ores" or "minecraft:iron_ore"
    val searchRadius: Int = 40,
    val maxY: Int = 64           // never dig below this without being told to
) : SubAgentTask { override val label = "Mine …×$targetCount" }

data class GuardTask(
    val anchor: BlockPos,
    val radius: Double = 8.0,
    val holdTicks: Int = 6000    // 5 minutes, then it reports back and leaves
) : SubAgentTask {
    override val label = "Guard ${anchor.toShortString()}"
    override val targetCount = 0
}
```

Design notes:

- **`targetCount` is a property of the interface** so the completion test is uniform across all
  four types: *"is the delivered amount ≥ `targetCount`?"*.
- **Partial success is a first-class outcome.** A fetch that gets 9 of 16 logs is not a failure;
  it delivers 9 and reports the shortfall. Tasks that can only be judged at the deadline (`GUARD`)
  report lives-saved/ticks-held instead.
- **No nested tasks.** A task never spawns another. Work the player wants chained is chained by
  the *caller* (the companion's own progression loop), which is what keeps a Mini-Bot's decision
  tree finite.

### 3.2 Lifecycle — Contract 8

```
   ┌──────────────────────────────────────────────────────────────────────┐
   │  DEPLOY   player/companion calls SubAgentScheduler.deploy(...)       │
   ├──────────────────────────────────────────────────────────────────────┤
   │  ARM      Mini-Bot spawned at the deployer's feet, task bound,       │
   │           30-tick "wake up" delay so the spawn is visible            │
   ├──────────────────────────────────────────────────────────────────────┤
   │  EXECUTE  task tree ticks; progress published to the owner's HUD     │
   ├──────────────────────────────────────────────────────────────────────┤
   │  RETURN   target reached OR deadline hit OR inventory full           │
   ├──────────────────────────────────────────────────────────────────────┤
   │  DEPOSIT  walk to the companion, transfer everything into its 27     │
   │           backpack slots; anything that does not fit is dropped      │
   │           at the companion's feet (never deleted)                    │
   ├──────────────────────────────────────────────────────────────────────┤
   │  FAREWELL speech emote via CompanionSpeechNotifier                   │
   │           ("Bye! Call me again!") for ~60 ticks                      │
   ├──────────────────────────────────────────────────────────────────────┤
   │  DESPAWN  entity discarded, lease released, task result reported     │
   └──────────────────────────────────────────────────────────────────────┘
```

Every transition has a **forcing edge** that cannot be blocked:

| From | Forced to | Trigger |
| :--- | :--- | :--- |
| any | RETURN | `deployedTick + ttlTicks` exceeded |
| any | RETURN | Mini-Bot killed or its owner disconnects |
| any | RETURN | deployer > 96 blocks away (released, then recalled) |
| any | RETURN | inventory full (`freeSlots == 0`) |
| RETURN | DESPAWN | deposit finished, or 200 ticks elapsed trying |

The "kill or owner disconnects" edge routes through the same deposit path as normal completion.
A Mini-Bot that cannot reach its companion still drops its contents where it died rather than
voiding them — the guarantee is *"items are never destroyed by the sub-agent system"*.

---

## 4. Entity design

### 4.1 A separate entity type, sharing a base

```kotlin
package com.qc.aeonis.companion.subagent

/**
 * Mini-Bot: a CompanionBotEntity that has been reduced to one task.
 *
 * Subclassing rather than a sibling type keeps the surrogate, the navigation, the motor
 * controller and the world-interaction helpers identical; overriding rather than a flag keeps the
 * *hub* entity free of sub-agent branches.
 */
class CompanionMiniBotEntity(
    type: EntityType<out CompanionMiniBotEntity>,
    level: Level
) : CompanionBotEntity(type, level) { ... }
```

| Aspect | Companion | Mini-Bot |
| :--- | :--- | :--- |
| Inventory | 33 slots (4 armour, 2 hands, 27 backpack) | **12 slots**, backpack only |
| Menu / inspection GUI | Yes (`CompanionMenu`) | No — read-only HUD summary |
| Profile / persistence | `CompanionProfileSavedData` | **Never persisted**; discarded on world unload |
| Skill manager | Six skills + progression loop | **None** — one task tree, one blackboard |
| Order layer | `OrderGovernor` | Only `RETREAT` is honoured (self-preservation) |
| Speech | Full dialogue | Two lines: deploy acknowledgement, farewell |
| Pathfinding budget | `maxSearchBudget = 384` | `maxSearchBudget = 192` (cheaper, shorter trips) |
| TTL | infinite | `ttlTicks` (default 6 000, hard cap 24 000) |

The smaller inventory is not cosmetic: it is what bounds the item-transfer cost at the end of a
task and makes "inventory full ⇒ head home" a useful completion signal instead of a pathological
state that takes 27 slots to reach.

### 4.2 Reusing the tree without reallocating it

A Mini-Bot's task tree is **built once per task type** and shared across every instance of that
type:

```kotlin
object SubAgentTaskTrees {
    /** Compiled once on first use; never rebuilt per tick or per entity. */
    val fetch: BTNode by lazy { ... }
    val hunt: BTNode by lazy { ... }
    val mine: BTNode by lazy { ... }
    val guard: BTNode by lazy { ... }
}
```

Per-entity state lives in a `MiniBotBlackboard(bot, task, lease)`, extending the existing
`CompanionBlackboard` shape. Sharing a tree is safe because every node in
`BehaviorTreeEngine.kt` is written to hold *no* per-entity state except the `SelectorNode` /
`SequenceNode` running index — so the rule for authoring sub-agent nodes is: **no mutable fields
on task nodes; all mutable state goes on the blackboard.** That rule is enforced by a review
checklist item, because a shared tree with per-node state would leak one bot's progress into
another's.

---

## 5. Scheduling, budget and leases

### 5.1 Contract 9 — `SubAgentScheduler`

```kotlin
package com.qc.aeonis.companion.subagent

object SubAgentScheduler {
    /** Maximum live Mini-Bots per owner. */
    const val MAX_PER_OWNER = 3

    /** Maximum live Mini-Bots for the whole server, across all owners. */
    const val MAX_GLOBAL = 24

    /** Hard ceiling on a single deployment's lifetime. */
    const val MAX_TTL_TICKS = 24_000

    fun deploy(owner: ServerPlayer, task: SubAgentTask, ttlTicks: Int = 6_000): DeploymentResult

    fun recallAll(owner: ServerPlayer): Int

    /** Called from the common server tick; O(live bots). */
    fun tick(server: MinecraftServer)
}
```

`DeploymentResult` is a sealed result (`Deployed`, `TooManyPerOwner`, `ServerAtCapacity`,
`TaskInvalid`, `NoCompanion`). Every refusal has a player-visible reason through
`CompanionSpeechNotifier` — a silent "nothing happened" is the failure mode players report as a
bug.

### 5.2 Why a lease rather than an entity-only model

The deployment owns a **lease** (`ownerUuid`, `taskUuid`, `entityId`, `expiresAtTick`). The
scheduler's tick walks leases, not entities:

- **Cheap accounting.** `liveCount(owner)` is a map lookup, so the cap is enforced before any
  entity work.
- **Orphan-proof.** A lease whose entity no longer exists (chunk unload, `/kill`, an exception)
  is expired and released on the next scheduler tick, which is what prevents the "invisible ghost
  bot eating my cap" bug class.
- **Server-restart-proof.** Leases are in-memory only and cleared on `ServerStoppedEvent`; a
  reload starts with zero Mini-Bots, which is the correct and simplest semantics for ephemeral
  helpers.

### 5.3 Tick budget

| Work | Cost |
| :--- | :--- |
| Scheduler tick, no bots | one map size check — effectively free |
| Scheduling + lease bookkeeping | O(live) integer comparisons |
| Per Mini-Bot | one task tree tick + one `driveMovement` — the same cost as one companion |
| Path searches | at most one per bot per 30 ticks, `maxSearchBudget = 192` |

The design target is **one Mini-Bot ≈ 25% of a companion's tick cost**, achieved by the smaller
inventory, the smaller search budget and the absence of the six-skill evaluation pass. With
`MAX_GLOBAL = 24` the worst case is bounded at roughly six companions' worth of AI cost for the
entire server, regardless of player count.

---

## 6. Task semantics

### 6.1 `FETCH(item, count)`

1. Scan containers within `searchRadius` (chests, barrels, shulker boxes) for the item — cheapest
   possible win, and it means "get me more arrows" does not become "chop a forest".
2. If not enough, harvest from the world using `ScaffoldingEngine.mineBlock` against blocks whose
   drops match the requested item (log-type ids resolve through the same plank/log tables as the
   companion's crafting).
3. Vacuum drops with `vacuumDrops(3.0)` after each successful break.
4. Complete when `collected >= targetCount`, or on deadline, or when the backpack is full.

Never touches a container it cannot reach, and never takes items from a container that is not
matching — the failure mode to avoid is a fetch bot emptying a player's sorted storage.

### 6.2 `HUNT(target, count)`

Shares `WeaponSelector` with the companion: **it will not hunt bare-handed either.** If
`requireWeapon` and no sword or axe is available, the task fails fast at ARM time with a spoken
reason instead of the bot wandering off to punch cows. Kill counting is by *observed death of a
selected target*, not by "an animal disappeared", so a bot cannot take credit for another player's
work.

### 6.3 `MINE(ore, count)`

- Resolves the id as either a tag (`#minecraft:coal_ores`) or a concrete block.
- Refuses targets whose `judgeClearance` verdict is `NEEDS_BETTER_TOOL`; a Mini-Bot with no
  pickaxe reports *"I need a pickaxe for that"* rather than mining coal by hand for a minute.
- Respects `maxY` as a floor: a miner will not follow a vein into the deepslate unless the
  deployment said so, which is what stops "mine me some coal" from turning into a 30-block
  staircase to bedrock.
- Tours multiple candidate blocks in one deployment (bounded at 12 candidate evaluations) instead
  of returning after a single ore.

### 6.4 `GUARD(anchor, radius)`

- Holds `anchor` with a leash of `radius`; leaves the leash only to intercept a hostile whose
  `StratumOcclusionFilter.isTacticallyReachable` verdict is true, then returns.
- Uses the bodyguard target selection from `OrderGovernor.selectBodyguardTarget` with the anchor
  standing in for the owner, so guard-targeting cannot drift from bodyguard-targeting.
- Never chases beyond `2 × radius`: a guard that vanishes over a hill is not guarding.

---

## 7. Communication and UI

| Channel | Payload | Direction |
| :--- | :--- | :--- |
| Deployment | `SubAgentDeployPayload(taskId, taskCodec, ttl)` | client → server |
| Status | `SubAgentStatusPayload(entityId, label, progress, target, state)` | server → client, 10-tick cadence |
| Speech | `CompanionDialoguePayload` (existing, TASK-016) | server → client |
| Recall | `SubAgentRecallPayload(entityId or all)` | client → server |

Status is throttled to every 10 ticks and only sent to the deploying owner. A Mini-Bot near the
player renders a name tag of the form `Fetch oak_log 9/16`; this uses the vanilla name-tag path so
no client changes are needed for the label itself, only for the HUD list.

### 7.1 The HUD requirement

`CompanionSpeechOverlay` (TASK-016) gets a compact sub-agent strip: one row per live lease with
the task label, progress bar and a recall hotkey. This is a **read of the scheduler's lease map**,
not a second source of truth — the HUD must not be able to disagree with the server about how
many bots are alive.

---

## 8. Integration seams with the current milestone

| Current work | Seam |
| :--- | :--- |
| TASK-017 order preemption | Mini-Bots have no orders, but `RETREAT` propagates: `/comp retreat` also recalls every Mini-Bot, because a player shouting "run away" is not asking only the main companion |
| TASK-018 locomotion | Shared verbatim; a Mini-Bot crossing water uses the same `AquaticLocomotion.stroke` |
| TASK-019 scaffolding | `MineTask` and `FetchTask` call `mineBlock` / `clearObstacle`; a `FETCH` of dirt will scavenge exactly as the companion does |
| TASK-020 progression loop | The loop is the natural *caller*: `STONE_AGE` with no coal within reach can deploy a `MineTask` instead of walking the companion away from the player |
| TASK-015 profile | A Mini-Bot inherits the companion's bond tier, which is the natural place for future upgrades (TTL, count, search radius) |

---

## 9. Failure modes and guarantees

| Failure | Behaviour | Guarantee |
| :--- | :--- | :--- |
| Owner disconnects | Lease expires on the next scheduler tick; bot deposits at its last position then despawns | No orphan entities |
| Server stops | All leases cleared; no persistence | A reload never resurrects a stale bot |
| Mini-Bot killed | Items dropped at death, lease expired, spoken report | The scheduler never reports success for a dead bot |
| Path unreachable | Task reports `Unreachable` after 200 ticks of no progress, returns home | No infinite loops |
| Task target exhausted | Returns home with partial progress | Partial results are delivered, not discarded |
| Two bots on the same chest | Vanilla container semantics serialize the transfers | No duplication: all transfers use `Container.removeItem` |
| Cap exceeded | Deployment refused with a spoken reason | A player can always tell *why* nothing spawned |

**No item duplication is permitted, and the design has one rule that enforces it**: Mini-Bots
move items with `Container.removeItem` into their own inventory, and deposit with
`CompanionInventory.collectDrop`. Nothing ever copies an `ItemStack` without shrinking the
source. This is the same discipline the companion's surrogate needed (see the inventory-ownership
rule in `IPlayerSurrogate`), and it is stated here because a sub-agent fan-out is exactly where a
duplication bug multiplies.

---

## 10. Testing plan

1. **Pure unit tests** for anything that needs no world: `SubAgentTask` codecs round-trip;
   completion predicates; lease expiry arithmetic; the deploy refusal matrix.
2. **Headless server harness** (extend `scripts/mc-smoke-test.py`): deploy one of each task type
   into a flat test world and assert (a) the entity spawns, (b) it despawns before `MAX_TTL_TICKS
   × 1.2`, (c) the companion's inventory grew by the reported amount, (d) live lease count returns
   to zero.
3. **Leak test**: deploy three bots, kill them with `/kill @e[type=aeonis:companion_minibot]`, and
   assert the cap is immediately reusable — this is the regression test for the ghost-bot class.
4. **Budget test**: with `MAX_GLOBAL` bots deployed, assert server tick time stays within a fixed
   budget on the CI benchmark world.

---

## 11. Delivery phases

| Phase | Scope | Dependency |
| :--- | :--- | :--- |
| **P1** | `SubAgentTask` + codecs, `SubAgentScheduler` with leases, `FETCH` only | None |
| **P2** | `FETCH`/`MINE` executors on shared trees, deposit + farewell, HUD strip | TASK-016 for the overlay |
| **P3** | `HUNT` and `GUARD` executors | TASK-017 for the weapon gate |
| **P4** | Progression-loop caller: the companion deploys bots instead of leaving the player | TASK-020 |

Phase 1 is deliberately the smallest useful slice: a fetch bot that cannot be spawned more than
three times per player and always comes home is already a demonstrable feature, and it validates
the lease machinery that everything else depends on before any of the combat semantics are written.

---

## 12. Open questions for review

1. **`recallAll` on `RETREAT`** — proposed and implemented in the design above. Confirm this is
   wanted, or whether Mini-Bots should be independent of the companion's order layer entirely.
2. **Cap values** — `MAX_PER_OWNER = 3`, `MAX_GLOBAL = 24`. Both are guesses calibrated to "one
   companion ≈ 4 Mini-Bots"; if the tick-cost target is not met, the global cap comes down first.
3. **Persistence** — this design discards Mini-Bots across server restarts. If a player expects a
   guard to still be on duty after a reboot, that changes the lease model into saved data and
   should be decided before Phase 1 ships.
4. **Sub-agent inventory UI** — whether the player can open a Mini-Bot's 12 slots at all. The
   design says no (deposit-only); if inspection is wanted, it should reuse `CompanionMenu` with a
   reduced slot count rather than a new screen.
