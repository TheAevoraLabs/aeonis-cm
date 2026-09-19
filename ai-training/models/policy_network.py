"""
PyTorch Actor-Critic Policy Network for Aeonis Neural Movement.

Architecture:
- Kinematic & Target Trunk: 12-dim -> 32-dim embedding
- Voxel Spatial Trunk: 125-dim -> 32-dim spatial embedding
- Shared Fusion Trunk: (32 + 32) -> 128 -> 64
- Actor Locomotion Head: 64 -> 2 continuous actions (Forward, Strafe) via Tanh
- Actor Gate Head: 64 -> 3 discrete gates (Jump, Sprint, Sneak) via Sigmoid
- Critic Head: 64 -> 1 state value V(s)
"""

from typing import Tuple, Dict
import torch
import torch.nn as nn
from torch.distributions import Normal, Bernoulli


class MovementActorCritic(nn.Module):
    """
    Combined Actor-Critic network for 3D Minecraft voxel navigation.
    Input: state_input of shape [batch_size, 137] (float32).
    Output: actions of shape [batch_size, 5] (float32).
    """

    def __init__(self, state_dim: int = 137, action_dim: int = 5):
        super().__init__()
        self.state_dim = state_dim
        self.action_dim = action_dim

        # 1. Kinematic & Target Feature Extractor (features 0..11)
        self.kinematic_net = nn.Sequential(
            nn.Linear(12, 32),
            nn.GELU(),
            nn.LayerNorm(32),
            nn.Linear(32, 32),
            nn.GELU(),
        )

        # 2. Voxel Spatial Feature Extractor (features 12..136)
        self.voxel_net = nn.Sequential(
            nn.Linear(125, 64),
            nn.GELU(),
            nn.LayerNorm(64),
            nn.Linear(64, 32),
            nn.GELU(),
        )

        # 3. Fusion Backbone
        self.backbone = nn.Sequential(
            nn.Linear(32 + 32, 128),
            nn.GELU(),
            nn.LayerNorm(128),
            nn.Linear(128, 64),
            nn.GELU(),
        )

        # 4. Actor Heads
        # Continuous movement: [forward, strafe] in [-1.0, 1.0]
        self.actor_continuous_mean = nn.Sequential(
            nn.Linear(64, 32),
            nn.GELU(),
            nn.Linear(32, 2),
            nn.Tanh(),
        )
        # Learnable log standard deviation for continuous exploration
        self.actor_continuous_log_std = nn.Parameter(torch.full((2,), -0.5))

        # Discrete gates: [jump, sprint, sneak] in [0.0, 1.0]
        self.actor_discrete_logits = nn.Sequential(
            nn.Linear(64, 32),
            nn.GELU(),
            nn.Linear(32, 3),
        )

        # 5. Critic Head
        self.critic = nn.Sequential(
            nn.Linear(64, 32),
            nn.GELU(),
            nn.Linear(32, 1),
        )

        # Orthogonal weight initialization
        self._init_weights()

    def _init_weights(self):
        for m in self.modules():
            if isinstance(m, nn.Linear):
                nn.init.orthogonal_(m.weight, gain=1.414)
                if m.bias is not None:
                    nn.init.constant_(m.bias, 0.0)
        # Small gain for policy outputs
        nn.init.orthogonal_(self.actor_continuous_mean[-2].weight, gain=0.01)
        nn.init.orthogonal_(self.actor_discrete_logits[-1].weight, gain=0.01)
        nn.init.orthogonal_(self.critic[-1].weight, gain=1.0)

    def extract_features(self, state: torch.Tensor) -> torch.Tensor:
        """
        Extracts fused features from 137-dim state tensor.
        """
        kinematic_in = state[:, 0:12]
        voxel_in = state[:, 12:137]

        k_feat = self.kinematic_net(kinematic_in)
        v_feat = self.voxel_net(voxel_in)

        fused = torch.cat([k_feat, v_feat], dim=-1)
        return self.backbone(fused)

    def get_value(self, state: torch.Tensor) -> torch.Tensor:
        """
        Evaluates state value V(s).
        """
        features = self.extract_features(state)
        return self.critic(features).squeeze(-1)

    def get_action_and_value(
        self,
        state: torch.Tensor,
        action: torch.Tensor = None,
        deterministic: bool = False,
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        """
        Returns sampled action, log probability, entropy, and state value.
        Used during PPO training rollouts and policy updates.
        """
        features = self.extract_features(state)
        value = self.critic(features).squeeze(-1)

        # Continuous actions (forward, strafe)
        cont_mean = self.actor_continuous_mean(features)
        cont_std = torch.exp(self.actor_continuous_log_std.clamp(-2.0, 0.5))
        cont_dist = Normal(cont_mean, cont_std)

        # Discrete gates (jump, sprint, sneak)
        disc_logits = self.actor_discrete_logits(features)
        disc_probs = torch.sigmoid(disc_logits)
        disc_dist = Bernoulli(probs=disc_probs)

        if action is None:
            if deterministic:
                cont_action = cont_mean
                disc_action = (disc_probs > 0.5).float()
            else:
                cont_action = cont_dist.sample()
                disc_action = disc_dist.sample()
            action = torch.cat([cont_action, disc_action], dim=-1)

        cont_taken = action[:, 0:2]
        disc_taken = action[:, 2:5]

        # Log probabilities
        cont_log_prob = cont_dist.log_prob(cont_taken).sum(dim=-1)
        disc_log_prob = disc_dist.log_prob(disc_taken).sum(dim=-1)
        total_log_prob = cont_log_prob + disc_log_prob

        # Entropy
        cont_entropy = cont_dist.entropy().sum(dim=-1)
        disc_entropy = disc_dist.entropy().sum(dim=-1)
        total_entropy = cont_entropy + disc_entropy

        return action, total_log_prob, total_entropy, value

    def forward(self, state_input: torch.Tensor) -> torch.Tensor:
        """
        Deterministic forward pass used directly during ONNX inference in Java:
        Input: [batch_size, 137]
        Output: [batch_size, 5] (forward, strafe, jump_gate, sprint_gate, sneak_gate)
        """
        features = self.extract_features(state_input)
        cont_mean = self.actor_continuous_mean(features)
        disc_logits = self.actor_discrete_logits(features)
        disc_probs = torch.sigmoid(disc_logits)
        return torch.cat([cont_mean, disc_probs], dim=-1)
