"""
Reward function formulation for the Aeonis Neural Movement Controller.

Engineered to incentivize fast goal navigation, sprint-jumping across chasms,
fluid obstacle avoidance, and hazard avoidance.
"""

from typing import Dict, Any, Tuple
import numpy as np


class MovementRewardFunction:
    """
    Computes dense and sparse rewards for 3D voxel locomotion.
    
    Reward terms:
    - Distance delta: r_dist = 2.5 * (prev_dist - curr_dist)
    - Direction alignment: r_align = 0.25 * max(0, cos(v, target_dir))
    - Step penalty: r_step = -0.01 (incentivizes quick arrival)
    - Collision penalty: r_collision = -0.05 if horizontal collision
    - Gap clearance bonus: r_gap = +5.0 on crossing void gap
    - Goal arrival bonus: r_goal = +15.0 when dist < arrival_radius
    - Hazard/Void death penalty: r_death = -10.0
    """

    def __init__(
        self,
        progress_weight: float = 2.5,
        alignment_weight: float = 0.25,
        step_penalty: float = 0.01,
        collision_penalty: float = 0.05,
        gap_clearance_bonus: float = 5.0,
        goal_bonus: float = 15.0,
        death_penalty: float = 10.0,
        arrival_radius: float = 0.8,
    ):
        self.progress_weight = progress_weight
        self.alignment_weight = alignment_weight
        self.step_penalty = step_penalty
        self.collision_penalty = collision_penalty
        self.gap_clearance_bonus = gap_clearance_bonus
        self.goal_bonus = goal_bonus
        self.death_penalty = death_penalty
        self.arrival_radius = arrival_radius

    def compute_reward(
        self,
        prev_dist: float,
        curr_dist: float,
        velocity: np.ndarray,
        rel_target: np.ndarray,
        horizontal_collision: bool,
        on_ground: bool,
        is_clearing_gap: bool,
        reached_goal: bool,
        died: bool,
    ) -> Tuple[float, Dict[str, float]]:
        """
        Computes the scalar reward and diagnostic breakdown for a single step.
        """
        terms = {}

        if died:
            reward = -self.death_penalty
            terms["death"] = -self.death_penalty
            return reward, terms

        if reached_goal:
            reward = self.goal_bonus
            terms["goal"] = self.goal_bonus
            return reward, terms

        # Progress reward
        dist_delta = prev_dist - curr_dist
        r_progress = self.progress_weight * dist_delta
        terms["progress"] = r_progress

        # Alignment reward
        vel_mag = np.linalg.norm(velocity[:2])
        target_mag = np.linalg.norm(rel_target[:2])
        r_align = 0.0
        if vel_mag > 1e-4 and target_mag > 1e-4:
            cos_sim = (velocity[0] * rel_target[0] + velocity[2] * rel_target[2]) / (vel_mag * target_mag)
            r_align = self.alignment_weight * max(0.0, float(cos_sim)) * min(1.0, vel_mag)
        terms["alignment"] = r_align

        # Penalties
        r_step = -self.step_penalty
        terms["step"] = r_step

        r_coll = -self.collision_penalty if horizontal_collision else 0.0
        terms["collision"] = r_coll

        # Gap parkour reward
        r_gap = self.gap_clearance_bonus if (is_clearing_gap and on_ground) else 0.0
        terms["gap_clearance"] = r_gap

        total_reward = r_progress + r_align + r_step + r_coll + r_gap
        terms["total"] = total_reward
        return total_reward, terms

    def compute_batch_rewards(
        self,
        prev_dists: np.ndarray,
        curr_dists: np.ndarray,
        velocities: np.ndarray,
        rel_targets: np.ndarray,
        horizontal_collisions: np.ndarray,
        on_grounds: np.ndarray,
        gap_clearings: np.ndarray,
        reached_goals: np.ndarray,
        dones_death: np.ndarray,
    ) -> np.ndarray:
        """
        Vectorized reward computation for N parallel environments.
        """
        N = prev_dists.shape[0]
        rewards = np.zeros(N, dtype=np.float32)

        # Standard step terms
        dist_deltas = prev_dists - curr_dists
        rewards += self.progress_weight * dist_deltas

        # Alignment
        vel_mags = np.linalg.norm(velocities[:, [0, 2]], axis=1) + 1e-6
        tgt_mags = np.linalg.norm(rel_targets[:, [0, 2]], axis=1) + 1e-6
        dot_products = velocities[:, 0] * rel_targets[:, 0] + velocities[:, 2] * rel_targets[:, 2]
        cos_sims = np.clip(dot_products / (vel_mags * tgt_mags), 0.0, 1.0)
        rewards += self.alignment_weight * cos_sims * np.clip(vel_mags, 0.0, 1.0)

        # Step penalty
        rewards -= self.step_penalty

        # Collision penalty
        rewards -= self.collision_penalty * horizontal_collisions.astype(np.float32)

        # Gap bonus
        rewards += self.gap_clearance_bonus * (gap_clearings & on_grounds).astype(np.float32)

        # Terminal overrides
        rewards = np.where(reached_goals, self.goal_bonus, rewards)
        rewards = np.where(dones_death, -self.death_penalty, rewards)

        return rewards
