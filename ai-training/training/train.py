"""
Main Training Entry Point for Aeonis Neural Movement Policy.

Orchestrates PPO training, vectorized rollouts, periodic checkpointing,
locomotion benchmarking, and ONNX export.
"""

import sys
import os
import time
import argparse
import numpy as np
import torch

# Ensure ai-training modules are importable
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(current_dir)
if parent_dir not in sys.path:
    sys.path.insert(0, parent_dir)

from environment.voxel_sim_env import MinecraftVoxelEnv
from models.policy_network import MovementActorCritic
from training.ppo_trainer import PPOTrainer
from evaluation.benchmarks import LocomotionBenchmark
from export.export_onnx import export_to_onnx


def main():
    parser = argparse.ArgumentParser(description="Train Aeonis Neural Movement Policy with PPO")
    parser.add_argument("--steps", type=int, default=100000, help="Total environment interaction steps")
    parser.add_argument("--envs", type=int, default=16, help="Number of parallel environments")
    parser.add_argument("--lr", type=float, default=3e-4, help="Learning rate")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    parser.add_argument("--checkpoint-dir", type=str, default=os.path.join(parent_dir, "checkpoints"), help="Checkpoint directory")
    parser.add_argument("--export", action="store_true", default=True, help="Auto-export to ONNX upon completion")
    parser.add_argument("--benchmark", action="store_true", default=True, help="Run benchmarks upon completion")
    parser.add_argument("--device", type=str, default="cpu", help="Compute device ('cpu' or 'cuda')")
    args = parser.parse_args()

    # Reproducibility
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    os.makedirs(args.checkpoint_dir, exist_ok=True)

    print("===================================================================")
    print("           AEONIS NEURAL MOVEMENT REINFORCEMENT LEARNER            ")
    print("===================================================================")
    print(f"Total Steps:     {args.steps:,}")
    print(f"Parallel Envs:   {args.envs}")
    print(f"Learning Rate:   {args.lr}")
    print(f"Device:          {args.device}")
    print(f"Checkpoint Dir:  {args.checkpoint_dir}")
    print("===================================================================\n")

    # Initialize Environment & Network
    env = MinecraftVoxelEnv(num_envs=args.envs, curriculum=True)
    policy = MovementActorCritic()

    trainer = PPOTrainer(
        env=env,
        policy=policy,
        lr=args.lr,
        device=args.device,
    )

    num_updates = args.steps // trainer.batch_size
    if num_updates < 1:
        num_updates = 1

    total_steps = 0
    start_time = time.time()
    best_success_rate = -1.0

    for update in range(1, num_updates + 1):
        t0 = time.time()
        rollout_metrics, advantages, returns = trainer.collect_rollout()
        train_metrics = trainer.train_step(advantages, returns)
        dt = time.time() - t0

        total_steps += trainer.batch_size
        fps = trainer.batch_size / dt if dt > 0 else 0

        mean_reward = rollout_metrics["mean_reward"]
        success_rate = rollout_metrics["success_rate"] * 100.0

        if update % 5 == 0 or update == num_updates:
            elapsed = time.time() - start_time
            print(
                f"Update {update:3d}/{num_updates:3d} | "
                f"Steps: {total_steps:7,d} | "
                f"Reward: {mean_reward:6.2f} | "
                f"Success: {success_rate:5.1f}% | "
                f"PolLoss: {train_metrics['policy_loss']:6.4f} | "
                f"ValLoss: {train_metrics['value_loss']:6.4f} | "
                f"FPS: {fps:5.0f} | "
                f"Elapsed: {elapsed:4.0f}s"
            )

        if success_rate > best_success_rate:
            best_success_rate = success_rate
            best_path = os.path.join(args.checkpoint_dir, "best_policy.pt")
            torch.save(policy.state_dict(), best_path)

    # Save final model
    final_path = os.path.join(args.checkpoint_dir, "final_policy.pt")
    torch.save(policy.state_dict(), final_path)
    print(f"\nTraining complete! Checkpoint saved to: {final_path}")

    # Benchmarking
    if args.benchmark:
        print("\nRunning Evaluation Benchmark Suite...")
        bench = LocomotionBenchmark(policy, device=args.device)
        results = bench.run_all_benchmarks(episodes=30)
        bench.print_summary(results)

    # ONNX Export & Resource Deployment
    if args.export:
        onnx_path = os.path.join(args.checkpoint_dir, "movement_policy.onnx")
        export_to_onnx(policy, output_path=onnx_path, deploy_to_assets=True)


if __name__ == "__main__":
    main()
