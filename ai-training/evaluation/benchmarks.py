"""
Standardized Evaluation and Parkour Benchmarking Suite for Aeonis Neural Movement.

Evaluates trained policies across 5 core locomotion challenges:
1. Flat Sprint Speed & Steering
2. 1-Block Elevation Hurdle
3. 2-Block Chasm Jump
4. 3-Block Sprint-Jump Parkour Chasm
5. Lateral Wall Obstacle Avoidance
"""

from typing import Dict, Any, List
import sys
import os
import numpy as np
import torch

from environment.voxel_sim_env import MinecraftVoxelEnv, CourseType
from models.policy_network import MovementActorCritic


class LocomotionBenchmark:
    """
    Evaluates policy performance under rigorous standardized obstacle environments.
    """

    COURSES = {
        "Flat Sprint (20m)": CourseType.FLAT_RUN,
        "1-Block Step Hurdle": CourseType.STEP_UP,
        "2-Block Chasm Jump": CourseType.GAP_2BLOCK,
        "3-Block Sprint Parkour": CourseType.GAP_3BLOCK_SPRINT,
        "Obstacle Wall Avoidance": CourseType.WALL_OBSTACLE,
    }

    def __init__(self, policy: MovementActorCritic, device: str = "cpu"):
        self.policy = policy.to(device)
        self.device = torch.device(device)
        self.policy.eval()

    def run_course_benchmark(self, course_name: str, course_type: int, episodes: int = 50, max_ticks: int = 200) -> Dict[str, Any]:
        """
        Runs a test suite for a specific course type.
        """
        env = MinecraftVoxelEnv(num_envs=episodes, max_ticks=max_ticks, curriculum=False)
        for i in range(episodes):
            env.course_types[i] = course_type
            env._generate_course(i, course_type)
            env.prev_dist[i] = float(np.linalg.norm(env.target[i] - env.pos[i]))

        successes = 0
        ticks_list = []
        collisions = 0

        obs_np = env.get_observations()
        active = np.ones(episodes, dtype=bool)

        for tick in range(max_ticks):
            if not active.any():
                break

            with torch.no_grad():
                obs_t = torch.tensor(obs_np, dtype=torch.float32, device=self.device)
                # Deterministic inference (action heads)
                actions_t = self.policy(obs_t)
                actions_np = actions_t.cpu().numpy()

            obs_np, rewards_np, dones_np, infos = env.step(actions_np)

            for i in range(episodes):
                if active[i]:
                    if env.horizontal_collision[i]:
                        collisions += 1
                    if dones_np[i]:
                        active[i] = False
                        if infos[i].get("success", False):
                            successes += 1
                            ticks_list.append(tick + 1)

        success_rate = (successes / episodes) * 100.0
        avg_ticks = float(np.mean(ticks_list)) if ticks_list else float(max_ticks)

        return {
            "course": course_name,
            "episodes": episodes,
            "successes": successes,
            "success_rate_pct": success_rate,
            "avg_ticks": avg_ticks,
            "total_collisions": collisions,
        }

    def run_all_benchmarks(self, episodes: int = 50) -> Dict[str, Any]:
        """
        Runs the full benchmark suite across all courses.
        """
        results = {}
        for name, ctype in self.COURSES.items():
            res = self.run_course_benchmark(name, ctype, episodes=episodes)
            results[name] = res
        return results

    def print_summary(self, results: Dict[str, Any]):
        """
        Prints formatted benchmark results table.
        """
        print("\n===================================================================")
        print("           AEONIS NEURAL MOVEMENT LOCOMOTION BENCHMARK            ")
        print("===================================================================")
        print(f"{'Course Name':<26} | {'Success %':<10} | {'Avg Ticks':<10} | {'Collisions'}")
        print("---------------------------+------------+------------+-------------")
        total_success = 0
        for name, r in results.items():
            total_success += r["success_rate_pct"]
            print(f"{name:<26} | {r['success_rate_pct']:>8.1f}% | {r['avg_ticks']:>10.1f} | {r['total_collisions']:>10}")
        avg_success = total_success / len(results)
        print("===================================================================")
        print(f"Overall Locomotion Score: {avg_success:.1f}%\n")
