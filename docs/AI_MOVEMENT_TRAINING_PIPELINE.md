# Aeonis Neural Movement RL Training Pipeline

Comprehensive technical specification and user guide for the offline reinforcement learning (RL) training pipeline located in `ai-training/`. This pipeline trains high-frequency neural movement policies deployed to Minecraft companion entities via ONNX Runtime (`OnnxMovementController.kt`).

---

## 1. System Architecture: Brain Factory to Brain Socket

The movement intelligence of the Aeonis Companion is decoupled into two complementary subsystems:

```
                      [ BRAIN FACTORY ]
               ai-training/ (Python + PyTorch)
  ┌─────────────────────────────────────────────────────────┐
  │  Fast Voxel Sim (Pure NumPy, 30,000+ steps/sec)         │
  │                     │                                   │
  │                     ▼                                   │
  │  PPO Actor-Critic Network (137-dim Input, 5-head Action)│
  │                     │                                   │
  │                     ▼                                   │
  │  Standardized Locomotion Benchmarks (Parkour / Gaps)   │
  │                     │                                   │
  │                     ▼                                   │
  │  Zero-Loss ONNX Exporter (Dynamic Batching, Opset 17)   │
  └─────────────────────┬───────────────────────────────────┘
                        │
                        │ exports movement_policy.onnx
                        ▼
  common/src/main/resources/assets/aeonis/models/movement_policy.onnx
                        │
                        │ auto-loaded at runtime
                        ▼
                      [ BRAIN SOCKET ]
            aeonis-cm (Kotlin + ONNX Runtime Java)
  ┌─────────────────────────────────────────────────────────┐
  │  OnnxMovementController.kt (< 0.15ms Inference Tick)    │
  │  - Zero-allocation off-heap FloatBuffers                │
  │  - Automatic Heuristic Kinematic Steering Fallback      │
  │  - Native Memory Lifecycle Management (AutoCloseable)   │
  └─────────────────────────────────────────────────────────┘
```

---

## 2. Minecraft Kinematics & Physical Formulation

The offline simulation environment (`voxel_sim_env.py`) mirrors Minecraft's exact physical constants and entity collision rules:

| Physical Property | Value | Unit / Behavior |
| :--- | :--- | :--- |
| **Bounding Box** | $0.6 \times 1.8 \times 0.6$ | Width $\times$ Height $\times$ Length (meters / blocks) |
| **Gravity ($g$)** | $0.08$ | $\text{blocks} / \text{tick}^2$ applied downward |
| **Vertical Drag** | $0.98$ | Multiplicative velocity dampening per tick |
| **Ground Friction** | $0.546$ | Horizontal velocity dampening factor on regular blocks ($0.6 \times 0.91$) |
| **Air Drag** | $0.91$ | Horizontal velocity retention factor when airborne |
| **Ground Acceleration** | $0.13$ | Force impulse applied when steering on solid ground |
| **Air Acceleration** | $0.02$ | Force impulse applied when steering while airborne |
| **Jump Impulse ($v_y$)**| $0.42$ | Instantaneous upward velocity when jumping |
| **Sprint-Jump Boost** | $+0.2$ | Forward momentum boost vector added upon jump takeoff while sprinting |
| **Sprint Multiplier** | $1.3\times$ | Velocity / acceleration multiplier |
| **Sneak Multiplier** | $0.3\times$ | Speed dampening factor; prevents slipping over block edges |
| **Vanilla Step Height** | $0.6$ | Maximum elevation auto-stepped without jumping |

### Mathematical Equations of Motion
At each discrete simulation tick $t$:
$$\vec{v}_{xz}^{(t+1)} = \left(\vec{v}_{xz}^{(t)} + \vec{a}_{steer} \cdot k_{sprint}\right) \times \mu_{friction}$$
$$v_y^{(t+1)} = \left(v_y^{(t)} - g\right) \times \mu_{drag\_y}$$
$$\vec{x}^{(t+1)} = \vec{x}^{(t)} + \vec{v}^{(t+1)} + \vec{c}_{collision}$$

---

## 3. Observation Space (137-Dimensional Tensor)

The observation tensor is packed by `ObservationBuilder.py` into a continuous 1D float vector matching `OnnxMovementController.kt`:

| Index Range | Name | Dimensions | Type | Normalization / Units | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `[0:3]` | `rel_target` | 3 | Float32 | $[-64.0, 64.0]$ | Relative displacement vector $[\Delta x, \Delta y, \Delta z]$ to target |
| `[3]` | `distance` | 1 | Float32 | $[0.0, 64.0]$ | Euclidean distance $\|\vec{t} - \vec{p}\|$ |
| `[4]` | `yaw_delta` | 1 | Float32 | $[-180.0, 180.0]$ | Relative yaw angle to target in degrees |
| `[5]` | `pitch_delta` | 1 | Float32 | $[-90.0, 90.0]$ | Relative pitch angle (default 0.0) |
| `[6:9]` | `velocity` | 3 | Float32 | $[-2.0, 2.0]$ | Current entity velocity $[v_x, v_y, v_z]$ |
| `[9]` | `on_ground` | 1 | Float32 | $\{0.0, 1.0\}$ | $1.0$ if standing on a solid surface |
| `[10]` | `in_water` | 1 | Float32 | $\{0.0, 1.0\}$ | $1.0$ if submerged in water |
| `[11]` | `horizontal_collision` | 1 | Float32 | $\{0.0, 1.0\}$ | $1.0$ if colliding with a wall/hurdle |
| `[12:137]` | `local_voxels` | 125 | Float32 | $\{-1.0, 0.0, 1.0\}$ | $5 \times 5 \times 5$ local occupancy grid around entity center |

### Local Voxel Indexing Order
The 125 voxels are scanned in triple-nested order matching Java:
```python
for dy in range(-1, 4):      # -1 (feet), 0, 1, 2, 3 (above head)
    for dz in range(-2, 3):  # -2 to +2 (Z offset)
        for dx in range(-2, 3): # -2 to +2 (X offset)
            # 0.0 = Air / Passable
            # 1.0 = Solid Obstacle Block
            # -1.0 = Hazard (Lava / Void)
```

---

## 4. Action Space (5 Outputs)

The policy network outputs a 5-dimensional continuous/discrete action vector:

| Index | Action Channel | Activation | Range | Threshold / Semantics |
| :--- | :--- | :--- | :--- | :--- |
| `[0]` | **Forward Locomotion** | $\text{Tanh}$ | $[-1.0, 1.0]$ | $-1.0 = \text{Full Backward}$, $+1.0 = \text{Full Forward}$ |
| `[1]` | **Strafe Locomotion** | $\text{Tanh}$ | $[-1.0, 1.0]$ | $-1.0 = \text{Full Left}$, $+1.0 = \text{Full Right}$ |
| `[2]` | **Jump Gate** | $\text{Sigmoid}$ | $[0.0, 1.0]$ | Trigger jump if value $> 0.5$ |
| `[3]` | **Sprint Gate** | $\text{Sigmoid}$ | $[0.0, 1.0]$ | Enable sprint mode if value $> 0.5$ |
| `[4]` | **Sneak Gate** | $\text{Sigmoid}$ | $[0.0, 1.0]$ | Enable sneak mode if value $> 0.5$ |

---

## 5. Neural Network Architecture (`policy_network.py`)

```
               state_input [batch_size, 137]
                       │
        ┌──────────────┴──────────────┐
        ▼                             ▼
 Kinematics [0:12]             Voxels [12:137]
        │                             │
 Linear(12, 32)                Linear(125, 64)
      GELU                          GELU
 LayerNorm(32)                 LayerNorm(64)
 Linear(32, 32)                Linear(64, 32)
      GELU                          GELU
        │                             │
        └──────────────┬──────────────┘
                       ▼
            Concatenate [batch, 64]
                       │
            Linear(64, 128) -> GELU -> LayerNorm(128)
            Linear(128, 64) -> GELU
                       │
        ┌──────────────┴──────────────┐
        ▼                             ▼
   Actor Heads                   Critic Head
 ┌───────────────────────────┐  ┌───────────────────────────┐
 │ Locomotion:               │  │ Linear(64, 32)            │
 │ Linear(64, 32) -> GELU    │  │ GELU                      │
 │ Linear(32, 2)  -> Tanh    │  │ Linear(32, 1) -> V(s)     │
 │                           │  └───────────────────────────┘
 │ Gates:                    │
 │ Linear(64, 32) -> GELU    │
 │ Linear(32, 3)  -> Sigmoid │
 └─────────────┬─────────────┘
               ▼
        actions [batch_size, 5]
```

---

## 6. Reinforcement Learning Objective (PPO)

The agent optimizes the clipped surrogate objective with Generalized Advantage Estimation:
$$L^{CLIP}(\theta) = \hat{\mathbb{E}}_t \left[ \min\left(r_t(\theta)\hat{A}_t, \, \text{clip}(r_t(\theta), 1-\epsilon, 1+\epsilon)\hat{A}_t\right) \right]$$

### Reward Engineering
$$R_t = 2.5 \cdot (d_{t-1} - d_t) + 0.25 \cdot \max(0, \cos \phi) - 0.01 + R_{gap} - 0.05 \cdot \mathbb{I}_{collision} + R_{terminal}$$

- **Progress Delta**: $+2.5 \times (d_{t-1} - d_t)$ directly reinforces moving toward the waypoint.
- **Direction Alignment**: $+0.25 \times \cos(\vec{v}, \vec{t})$ encourages heading alignment.
- **Step Penalty**: $-0.01/\text{tick}$ promotes time-optimal sprint-jumping over slow walking.
- **Wall Collision**: $-0.05/\text{tick}$ discourages bumping into obstacle blocks.
- **Gap Clearance**: $+5.0$ awarded upon successfully clearing a void chasm.
- **Goal Arrival**: $+15.0$ awarded when within $0.8$ blocks of target.
- **Death / Void Fall**: $-10.0$ penalty on falling into hazards or void.

---

## 7. CLI Quickstart & Workflow

### 1. Set Up Environment
```bash
# Automated via uv
uv venv ai-training/.venv --python 3.11
source ai-training/.venv/bin/activate
uv pip install -r ai-training/requirements.txt
```

### 2. Train Movement Policy
```bash
# Standard training run (100k steps, 16 parallel envs, auto-export to ONNX)
python3 ai-training/training/train.py --steps 100000 --envs 16 --export --benchmark

# Fast training run for quick experimentation
python3 ai-training/training/train.py --steps 25000 --envs 16 --lr 0.0003
```

### 3. Evaluate on Standard Benchmark Suite
```bash
python3 -c "
import sys; sys.path.insert(0, 'ai-training')
import torch
from models import MovementActorCritic
from evaluation import LocomotionBenchmark

policy = MovementActorCritic()
policy.load_state_dict(torch.load('ai-training/checkpoints/final_policy.pt', map_location='cpu'))
bench = LocomotionBenchmark(policy)
bench.print_summary(bench.run_all_benchmarks(episodes=50))
"
```

### 4. Export Model to Minecraft Mod Assets
```bash
# Exports checkpoints/final_policy.pt directly to common/src/main/resources/assets/aeonis/models/movement_policy.onnx
python3 ai-training/export/export_onnx.py --checkpoint ai-training/checkpoints/final_policy.pt
```

---

## 8. Runtime Integration & Heuristic Fallback

When Minecraft loads:
1. `OnnxMovementController.kt` attempts to load `/assets/aeonis/models/movement_policy.onnx` from the mod JAR.
2. If ONNX Runtime native libraries (CUDA, CoreML, CPU DirectML) are present, it allocates a native session.
3. Every tick, entity state is converted into direct native off-heap memory buffers and evaluated in $< 0.15\text{ms}$.
4. If native libraries are unavailable (e.g. unsupported platform or headless test server), `OnnxMovementController` seamlessly falls back to its built-in kinematic steering heuristics with zero crashes or exceptions.
