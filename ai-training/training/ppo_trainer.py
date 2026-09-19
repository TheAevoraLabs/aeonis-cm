"""
Proximal Policy Optimization (PPO) Trainer for Minecraft Voxel Movement.

Implements actor-critic PPO with Generalized Advantage Estimation (GAE),
clipped surrogate objectives, entropy bonuses, and vectorized rollout collection.
"""

from typing import Dict, Any, Tuple
import os
import time
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim

from environment.voxel_sim_env import MinecraftVoxelEnv
from models.policy_network import MovementActorCritic


class PPOTrainer:
    def __init__(
        self,
        env: MinecraftVoxelEnv,
        policy: MovementActorCritic,
        lr: float = 3e-4,
        gamma: float = 0.99,
        gae_lambda: float = 0.95,
        clip_coef: float = 0.2,
        ent_coef: float = 0.01,
        vf_coef: float = 0.5,
        max_grad_norm: float = 0.5,
        num_steps: int = 128,          # Steps per rollout before update
        num_minibatches: int = 4,
        update_epochs: int = 4,
        device: str = "cpu",
    ):
        self.env = env
        self.policy = policy.to(device)
        self.device = torch.device(device)

        self.lr = lr
        self.gamma = gamma
        self.gae_lambda = gae_lambda
        self.clip_coef = clip_coef
        self.ent_coef = ent_coef
        self.vf_coef = vf_coef
        self.max_grad_norm = max_grad_norm

        self.num_steps = num_steps
        self.num_envs = env.num_envs
        self.batch_size = self.num_envs * self.num_steps
        self.minibatch_size = self.batch_size // num_minibatches
        self.update_epochs = update_epochs

        self.optimizer = optim.Adam(self.policy.parameters(), lr=lr, eps=1e-5)

        # Rollout storage buffers
        self.obs_buf = torch.zeros((num_steps, self.num_envs, 137), dtype=torch.float32, device=self.device)
        self.actions_buf = torch.zeros((num_steps, self.num_envs, 5), dtype=torch.float32, device=self.device)
        self.logprobs_buf = torch.zeros((num_steps, self.num_envs), dtype=torch.float32, device=self.device)
        self.rewards_buf = torch.zeros((num_steps, self.num_envs), dtype=torch.float32, device=self.device)
        self.dones_buf = torch.zeros((num_steps, self.num_envs), dtype=torch.float32, device=self.device)
        self.values_buf = torch.zeros((num_steps, self.num_envs), dtype=torch.float32, device=self.device)

        # Initial state
        self.next_obs = torch.tensor(env.get_observations(), dtype=torch.float32, device=self.device)
        self.next_done = torch.zeros(self.num_envs, dtype=torch.float32, device=self.device)

    def collect_rollout(self) -> Dict[str, float]:
        """
        Collects N steps of experience across all parallel environments.
        """
        ep_rewards = []
        ep_successes = []

        for step in range(self.num_steps):
            self.obs_buf[step] = self.next_obs
            self.dones_buf[step] = self.next_done

            with torch.no_grad():
                action, logprob, _, value = self.policy.get_action_and_value(self.next_obs)
                self.values_buf[step] = value

            self.actions_buf[step] = action
            self.logprobs_buf[step] = logprob

            # Step simulation
            action_np = action.cpu().numpy()
            next_obs_np, rewards_np, dones_np, infos = self.env.step(action_np)

            self.rewards_buf[step] = torch.tensor(rewards_np, dtype=torch.float32, device=self.device)
            self.next_obs = torch.tensor(next_obs_np, dtype=torch.float32, device=self.device)
            self.next_done = torch.tensor(dones_np, dtype=torch.float32, device=self.device)

            for info in infos:
                if "success" in info:
                    ep_successes.append(1.0)
                elif "timeout" in info:
                    ep_successes.append(0.0)

            ep_rewards.extend(rewards_np.tolist())

        # GAE Advantage Estimation
        with torch.no_grad():
            next_value = self.policy.get_value(self.next_obs)
            advantages = torch.zeros_like(self.rewards_buf)
            lastgaelam = 0.0
            for t in reversed(range(self.num_steps)):
                if t == self.num_steps - 1:
                    nextnonterminal = 1.0 - self.next_done
                    nextvalues = next_value
                else:
                    nextnonterminal = 1.0 - self.dones_buf[t + 1]
                    nextvalues = self.values_buf[t + 1]
                delta = self.rewards_buf[t] + self.gamma * nextvalues * nextnonterminal - self.values_buf[t]
                advantages[t] = lastgaelam = delta + self.gamma * self.gae_lambda * nextnonterminal * lastgaelam
            returns = advantages + self.values_buf

        metrics = {
            "mean_reward": float(np.mean(ep_rewards)) if ep_rewards else 0.0,
            "success_rate": float(np.mean(ep_successes)) if ep_successes else 0.0,
        }
        return metrics, advantages, returns

    def train_step(self, advantages: torch.Tensor, returns: torch.Tensor) -> Dict[str, float]:
        """
        Executes PPO update epochs over the collected rollout buffer.
        """
        # Flatten batches
        b_obs = self.obs_buf.reshape(-1, 137)
        b_logprobs = self.logprobs_buf.reshape(-1)
        b_actions = self.actions_buf.reshape(-1, 5)
        b_advantages = advantages.reshape(-1)
        b_returns = returns.reshape(-1)
        b_values = self.values_buf.reshape(-1)

        # Normalize advantages
        b_advantages = (b_advantages - b_advantages.mean()) / (b_advantages.std() + 1e-8)

        b_inds = np.arange(self.batch_size)
        pg_losses, v_losses, ent_losses = [], [], []

        for epoch in range(self.update_epochs):
            np.random.shuffle(b_inds)
            for start in range(0, self.batch_size, self.minibatch_size):
                end = start + self.minibatch_size
                mb_inds = b_inds[start:end]

                _, newlogprob, entropy, newvalue = self.policy.get_action_and_value(
                    b_obs[mb_inds], b_actions[mb_inds]
                )
                logratio = newlogprob - b_logprobs[mb_inds]
                ratio = logratio.exp()

                # Policy surrogate loss
                mb_advantages = b_advantages[mb_inds]
                pg_loss1 = -mb_advantages * ratio
                pg_loss2 = -mb_advantages * torch.clamp(ratio, 1 - self.clip_coef, 1 + self.clip_coef)
                pg_loss = torch.max(pg_loss1, pg_loss2).mean()

                # Value loss
                v_loss = 0.5 * ((newvalue - b_returns[mb_inds]) ** 2).mean()

                # Entropy loss
                entropy_loss = entropy.mean()

                # Total loss
                loss = pg_loss - self.ent_coef * entropy_loss + self.vf_coef * v_loss

                self.optimizer.zero_grad()
                loss.backward()
                nn.utils.clip_grad_norm_(self.policy.parameters(), self.max_grad_norm)
                self.optimizer.step()

                pg_losses.append(pg_loss.item())
                v_losses.append(v_loss.item())
                ent_losses.append(entropy_loss.item())

        return {
            "policy_loss": float(np.mean(pg_losses)),
            "value_loss": float(np.mean(v_losses)),
            "entropy": float(np.mean(ent_losses)),
        }
