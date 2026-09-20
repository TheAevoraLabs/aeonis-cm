# DeepSeek v4.1 Flash (High) - Kickoff & Execution Package (Phase 2)

**Role:** Algorithmic & AI Engine Lead  
**Collaborating Partner:** Gemini 3.8 Flash (Platform & Systems Architect)  
**Project:** Aeonis 5.0.0 (Minecraft 26.3 / Java 25 / Multi-loader Fabric & NeoForge)  
**Git Branch:** `dev`  
**Communication Bus:** `docs/agent_sync/INBOX_DEEPSEEK.md` & `docs/agent_sync/INBOX_GEMINI.md`

---

## 1. Context & Mission

The baseline companion stack and the offline neural training pipeline are completed and pushed. However, real-world field testing revealed critical behavioral and usability flaws that need our immediate collaboration to bring the companion to a **production-ready, highly focused, professional stage**:

1. **Hunting Runaway & Order Preemption Bug:** The bot currently gets trapped in an infinite hunting loop, punching animals with bare hands, ignoring Rhistel pings, and failing to respect `/comp hold`, `/comp follow`, or `/comp retreat`.
2. **Erratic Locomotion:** Movement displays sharp, jerky zig-zagging, and the bot cannot swim across water or navigate vertical cliffs.
3. **Missing Progressive Game Loop:** The bot lacks a progressive user-need state machine (gathering wood, then stone, crafting only after player crafts a table, building shelters, cooking meat in furnaces).
4. **Sub-Agent Vision:** The user wants a formal architecture for lightweight, ephemeral "Mini-Bots" that can be deployed for single tasks (fetch X, hunt Y, mine Z, guard area) and despawn cleanly.

While **Gemini** rebuilds the GUI to true Minecraft-standard quality, adds the persistent player profile database, and implements the non-chat speech overlay HUD, **DeepSeek** is in charge of the AI, Locomotion, Behavioral Constraints, and Progressive Game Loop.

---

## 2. Your Assigned Tasks

### TASK-017: Hunting Behavioral Constraints & Preemption Overrides
**Target Files:**
- `common/src/main/kotlin/com/qc/aeonis/companion/skill/HuntingFeedingSkill.kt`
- `common/src/main/kotlin/com/qc/aeonis/companion/ai/brain/BehaviorTreeEngine.kt`
- `common/src/main/kotlin/com/qc/aeonis/entity/CompanionBotEntity.kt`

**Requirements:**
1. **Strict Hunt Quota:** Cap hunting to **2–3 animals maximum** per trigger. Once 2–3 animals have been hunted or sufficient raw food is secured, immediately exit hunting mode and deliver or cook the food.
2. **Weapon Requirement:** The companion MUST equip and have a melee weapon (Sword or Axe) to hunt. **Never hunt with bare hands.** If no weapon is in inventory, do not hunt animals.
3. **Instant Order Preemption:** If `bot.currentOrder` is `HOLD`, `FOLLOW`, or `RETREAT`, or if a Rhistel ping is active (`blackboard.targetPingPos != null`), `HuntingFeedingSkill` and gathering skills MUST immediately terminate and yield control!
4. **Emergency Bodyguard Response:** If the owner is attacked or owner health $< 35\%$, abort all hunting/mining/gathering, equip highest DPS weapon, sprint to owner, and engage hostile mobs!

---

### TASK-018: Anti-Zigzag Locomotion Smoothing & Swimming Propulsion
**Target Files:**
- `common/src/main/kotlin/com/qc/aeonis/companion/ai/movement/VoxelPathPlanner.kt`
- `common/src/main/kotlin/com/qc/aeonis/companion/ai/movement/OnnxMovementController.kt`

**Requirements:**
1. **Raycast Shortcut Pruning (String-Pulling):** Given a raw A* waypoint list, perform raymarch line-of-sight checks between non-adjacent nodes. Eliminate redundant zigzag intermediate nodes so the entity walks in smooth, straight diagonals whenever passable.
2. **Active Water Swimming:** In `OnnxMovementController` and entity motor execution, detect `bot.isInWater`. Apply buoyant upward impulse ($v_y = +0.08$) and forward stroke to glide across water bodies without drowning or sinking.

---

### TASK-019: Dynamic 3D Scaffolding, Pillaring & Obstacle Clearance
**Target Files:**
- `common/src/main/kotlin/com/qc/aeonis/companion/skill/BuildingSupportSkill.kt`
- `common/src/main/kotlin/com/qc/aeonis/companion/entity/CompanionPlayerSurrogate.kt`

**Requirements:**
1. **Pillaring Up:** When owner or target waypoint is at a higher elevation ($\Delta y \ge 2$) and cannot be stepped up:
   - Check if bot has solid blocks (dirt, cobblestone, planks) in backpack.
   - Jump and place block directly under feet (`surrogate.placeBlock(feetPos, Direction.UP)`).
2. **Bridging Gaps:** When approaching a chasm/void $> 1$ block wide, place bridging blocks forward.
3. **Obstacle Clearance:** If path is obstructed by breakable blocks (dirt, stone, wood), mine through using the appropriate tool.
4. **Resource Scavenging Fallback:** If out of solid blocks to pillar/bridge, automatically mine 3–4 nearby dirt or cobblestone blocks to acquire scaffolding materials!

---

### TASK-020: User Need Progressive Game Loop
**Target Files:**
- `common/src/main/kotlin/com/qc/aeonis/companion/skill/ProgressionLoopSkill.kt` (New)
- `common/src/main/kotlin/com/qc/aeonis/companion/ai/brain/BehaviorTreeEngine.kt`

**Requirements:**
1. **Stage 1 (Wood Gathering):** Prioritize harvesting nearby logs (Oak, Birch, Spruce) and saplings.
2. **Stage 2 (Stone Age):** Prioritize mining cobblestone and coal veins.
3. **Stage 3 (Crafting Table Constraint):** **The companion CANNOT craft a Crafting Table until the player has crafted or placed one first!** Once unlocked, companion crafts stone tools, torches, and bread.
4. **Stage 4 (Shelter Assist):** If the player places a Bed or starts placing walls:
   - Detect the palette of blocks the player is using (e.g. Oak Planks, Cobblestone).
   - Scavenge matching wood/stone blocks and assist in building walls and roof.
5. **Stage 5 (Furnace Cooking):** If a Furnace is within 16 blocks and companion has raw meat (mutton, beef, pork) + fuel (coal, charcoal, planks):
   - Navigate to furnace, insert meat and fuel to cook food, and deliver cooked food to the hungry owner!

---

### TASK-021: Sub-Agent (Mini-Bot) Architecture Specification
**Target File:** `docs/COMPANION_SUBAGENT_DESIGN.md` (New)

**Requirements:**
Design the architecture for lightweight ephemeral helpers:
- Task types: `FETCH(item, count)`, `HUNT(target, count)`, `MINE(ore, count)`, `GUARD(pos, radius)`.
- Lifecycle: Spawns from companion, accepts single task without complex CoT, executes task, deposits gathered items into companion backpack, displays visual emote ("Bye! Call me again!"), and despawns cleanly.

---

## 3. Communication & Delivery

- Append completed work, questions, and PR notes to [`docs/agent_sync/INBOX_GEMINI.md`](INBOX_GEMINI.md).
- Follow the standardized tag format (`[IMPLEMENTATION]`, `[CONTRACT]`, `[REVIEW_REQUEST]`) described in [`docs/agent_sync/PROTOCOL.md`](PROTOCOL.md).
