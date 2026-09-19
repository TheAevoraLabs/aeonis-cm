# Multi-Agent Asynchronous Collaboration Protocol (MA-ACP)

**System:** Aeonis 5.0.0 (Minecraft 26.3 / Java 25)  
**Collaborating Agents:**
- **Agent 1 (Gemini 3.8 Flash - High):** Platform & Systems Architect (Minecraft Internals, Fabric/NeoForge, GUI, Trampoline, Network)
- **Agent 2 (DeepSeek v4.1 Flash - High):** Algorithmic & AI Engine Lead (3D Voxel Graph A*, ONNX Neural Policy, Occlusion Filters, Behavior Trees)

---

## 1. Overview & Operating Mode
Since both agents operate across different interfaces/apps, communication is maintained asynchronously via markdown files in `docs/agent_sync/`.

- **Gemini writes to:** [`docs/agent_sync/INBOX_DEEPSEEK.md`](INBOX_DEEPSEEK.md) (task dispatches, contract requests, code reviews, and replies to DeepSeek).
- **DeepSeek writes to:** [`docs/agent_sync/INBOX_GEMINI.md`](INBOX_GEMINI.md) (algorithmic implementations, questions, review requests, and task completion notices).
- **Shared State:** Both agents update [`docs/agent_sync/SHARED_TASK_BOARD.md`](SHARED_TASK_BOARD.md) as features move from `TODO` to `IN_PROGRESS` and `DONE`.

---

## 2. Message Format
When appending an entry to the other agent's inbox, use this standardized schema:

```markdown
### [TAG] Message Title
- **From:** [Gemini | DeepSeek]
- **Timestamp:** YYYY-MM-DD HH:MM
- **Related Task ID:** TASK-XXX

#### Content
[Your explanation, contract specification, question, or review notes]

#### Code / Artifact
```kotlin
// Exact Kotlin code or interface definition
```

#### Action Required from Recipient
- [ ] What the other agent needs to review, verify, or implement.
```

### Standard Tags:
- `[CONTRACT]` : Defines shared interfaces, data classes, or method signatures.
- `[IMPLEMENTATION]` : Delivers completed algorithm/module code for review and integration.
- `[REVIEW_REQUEST]` : Requests the other agent to audit code for bugs, edge cases, or engine compliance.
- `[QUESTION]` : Requests clarification or design alignment.
- `[APPROVED]` : Sign-off on an interface or implementation.
- `[SYNC]` : Milestone completion and taskboard status update.

---

## 3. Interface Contracts Before Implementation
To ensure zero merge conflicts and seamless integration:
1. Neither agent implements against another agent's module until the `[CONTRACT]` is approved.
2. If an API signature needs to change, post a `[CONTRACT_REVISION]` entry before refactoring.
3. Code should target `common/src/main/kotlin/com/qc/aeonis/companion/` unless loader-specific.

---

## 4. Workload Allocation Strategy

```
┌────────────────────────────────────────────────────────┐
│                   SHARED CONTRACTS                     │
└───────────┬────────────────────────────────┬───────────┘
            │                                │
            ▼                                ▼
┌────────────────────────┐      ┌────────────────────────┐
│      AGENT GEMINI      │      │     AGENT DEEPSEEK     │
│ Platform & Integration │      │ Algorithms & AI Engine │
├────────────────────────┤      ├────────────────────────┤
│ • Player Trampoline    │      │ • 3D Voxel Graph A*    │
│ • Container GUI & Sync │      │ • ONNX Neural Policy   │
│ • Intent Observer      │      │ • Stratum Occlusion    │
│ • Rhistel 3D Ping      │      │ • Behavior Tree Engine │
│ • Multi-loader & Build │      │ • Threat Scoring Math  │
└────────────────────────┘      └────────────────────────┘
```
