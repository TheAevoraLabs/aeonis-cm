"""
Observation builder for the Aeonis Neural Movement Controller.

Encodes entity kinematics, relative targets, and local 3D voxel surroundings
into the exact 137-dimensional Float32 vector expected by OnnxMovementController.kt.
"""

from typing import Tuple, Dict, Any
import numpy as np


class ObservationBuilder:
    """
    Constructs a 137-dimensional observation vector with identical indexing and normalization
    as the Minecraft client/server OnnxMovementController.kt runtime.
    
    Structure:
    - [0:3]   Relative target position (dx, dy, dz) in entity coordinates
    - [3]     Distance to target (Euclidean norm)
    - [4]     Yaw delta angle (degrees to target [-180, 180])
    - [5]     Pitch delta angle (default 0.0)
    - [6:9]   Current velocity vector (vx, vy, vz)
    - [9]     onGround flag (1.0 = True, 0.0 = False)
    - [10]    inWater flag (1.0 = True, 0.0 = False)
    - [11]    horizontalCollision flag (1.0 = True, 0.0 = False)
    - [12:137] Flattened 5x5x5 voxel occupancy grid (125 floats)
              Iterated in order: dy in [-1..3], dz in [-2..2], dx in [-2..2]
              0.0 = Air / Passable, 1.0 = Solid block, -1.0 = Hazard
    """
    
    TOTAL_FLOATS = 137
    VOXEL_DIM = 5
    VOXEL_TOTAL = 125  # 5 * 5 * 5

    @staticmethod
    def build_observation(
        rel_target: np.ndarray,
        velocity: np.ndarray,
        on_ground: bool,
        in_water: bool,
        horizontal_collision: bool,
        local_voxel_grid: np.ndarray,
    ) -> np.ndarray:
        """
        Builds a single 137-float observation array.
        
        Args:
            rel_target: Shape (3,) [dx, dy, dz]
            velocity: Shape (3,) [vx, vy, vz]
            on_ground: Boolean flag
            in_water: Boolean flag
            horizontal_collision: Boolean flag
            local_voxel_grid: Shape (125,) or (5, 5, 5) float array
            
        Returns:
            np.ndarray of shape (137,) dtype float32
        """
        obs = np.zeros(ObservationBuilder.TOTAL_FLOATS, dtype=np.float32)

        # 1. Target features (6 floats)
        dx, dy, dz = rel_target[0], rel_target[1], rel_target[2]
        dist = float(np.sqrt(dx * dx + dy * dy + dz * dz))
        yaw_delta = float(np.arctan2(dz, dx) * (180.0 / np.pi))

        obs[0] = dx
        obs[1] = dy
        obs[2] = dz
        obs[3] = dist
        obs[4] = yaw_delta
        obs[5] = 0.0  # pitch delta

        # 2. Kinematic state features (6 floats)
        obs[6] = velocity[0]
        obs[7] = velocity[1]
        obs[8] = velocity[2]
        obs[9] = 1.0 if on_ground else 0.0
        obs[10] = 1.0 if in_water else 0.0
        obs[11] = 1.0 if horizontal_collision else 0.0

        # 3. 125-cell voxel environment
        flattened_grid = local_voxel_grid.reshape(-1)
        if flattened_grid.size != ObservationBuilder.VOXEL_TOTAL:
            raise ValueError(f"Expected 125 voxel elements, got {flattened_grid.size}")
        obs[12:137] = flattened_grid

        return obs

    @staticmethod
    def build_batch_observations(
        rel_targets: np.ndarray,
        velocities: np.ndarray,
        on_ground_batch: np.ndarray,
        in_water_batch: np.ndarray,
        horizontal_collision_batch: np.ndarray,
        voxel_grids_batch: np.ndarray,
    ) -> np.ndarray:
        """
        Vectorized batch construction for parallel simulation environments.
        
        Args:
            rel_targets: Shape (N, 3)
            velocities: Shape (N, 3)
            on_ground_batch: Shape (N,)
            in_water_batch: Shape (N,)
            horizontal_collision_batch: Shape (N,)
            voxel_grids_batch: Shape (N, 125)
            
        Returns:
            np.ndarray of shape (N, 137), dtype float32
        """
        N = rel_targets.shape[0]
        obs = np.zeros((N, ObservationBuilder.TOTAL_FLOATS), dtype=np.float32)

        # Target features
        obs[:, 0:3] = rel_targets
        dist = np.linalg.norm(rel_targets, axis=1)
        obs[:, 3] = dist
        obs[:, 4] = np.arctan2(rel_targets[:, 2], rel_targets[:, 0]) * (180.0 / np.pi)
        obs[:, 5] = 0.0

        # Kinematics
        obs[:, 6:9] = velocities
        obs[:, 9] = on_ground_batch.astype(np.float32)
        obs[:, 10] = in_water_batch.astype(np.float32)
        obs[:, 11] = horizontal_collision_batch.astype(np.float32)

        # Local Voxels
        obs[:, 12:137] = voxel_grids_batch.reshape(N, 125)

        return obs
