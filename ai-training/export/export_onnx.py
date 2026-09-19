"""
Zero-Loss ONNX Exporter for Aeonis Neural Movement Policy.

Exports PyTorch policy network to ONNX format with exact tensor signatures
expected by the Minecraft Java runtime (OnnxMovementController.kt):
- Input Tensor: 'state_input' of shape [batch_size, 137] (Float32)
- Output Tensor: 'actions' of shape [batch_size, 5] (Float32)
"""

import sys
import os
import shutil
import argparse
import numpy as np
import torch
import onnx

current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(current_dir)
if parent_dir not in sys.path:
    sys.path.insert(0, parent_dir)

from models.policy_network import MovementActorCritic

REPO_ROOT = os.path.dirname(parent_dir)
DEFAULT_ASSET_TARGET = os.path.join(
    REPO_ROOT, "common", "src", "main", "resources", "assets", "aeonis", "models", "movement_policy.onnx"
)


def export_to_onnx(
    model: MovementActorCritic,
    output_path: str = os.path.join(parent_dir, "checkpoints", "movement_policy.onnx"),
    deploy_to_assets: bool = True,
    asset_target_path: str = DEFAULT_ASSET_TARGET,
) -> str:
    """
    Exports the policy to ONNX format and verifies graph integrity.
    """
    model.eval()
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)

    dummy_input = torch.randn(1, 137, dtype=torch.float32)

    print(f"Exporting PyTorch model to ONNX: {output_path}...")
    try:
        torch.onnx.export(
            model,
            dummy_input,
            output_path,
            export_params=True,
            opset_version=17,
            do_constant_folding=True,
            input_names=["state_input"],
            output_names=["actions"],
            dynamic_axes={
                "state_input": {0: "batch_size"},
                "actions": {0: "batch_size"},
            },
            dynamo=False,
        )
    except TypeError:
        # If dynamo kwarg is not supported in older torch versions
        torch.onnx.export(
            model,
            dummy_input,
            output_path,
            export_params=True,
            opset_version=17,
            do_constant_folding=True,
            input_names=["state_input"],
            output_names=["actions"],
            dynamic_axes={
                "state_input": {0: "batch_size"},
                "actions": {0: "batch_size"},
            },
        )

    # Validate ONNX model
    onnx_model = onnx.load(output_path)
    onnx.checker.check_model(onnx_model)
    print("✓ ONNX model graph structure verified successfully.")

    # Deploy to Minecraft resource assets
    if deploy_to_assets:
        abs_asset_path = os.path.abspath(asset_target_path)
        os.makedirs(os.path.dirname(abs_asset_path), exist_ok=True)
        shutil.copyfile(output_path, abs_asset_path)
        print(f"✓ Deployed ONNX policy to Minecraft mod assets: {abs_asset_path}")

    # Also export pure JVM weights binary for zero-dependency native execution
    export_weights_bin(model, deploy_to_assets=deploy_to_assets)

    return output_path


def export_weights_bin(
    model: MovementActorCritic,
    output_bin_path: str = os.path.join(parent_dir, "checkpoints", "movement_weights.bin"),
    deploy_to_assets: bool = True,
    asset_target_bin_path: str = os.path.join(
        REPO_ROOT, "common", "src", "main", "resources", "assets", "aeonis", "models", "movement_weights.bin"
    ),
):
    """
    Exports policy weights to a compact flat binary format for pure JVM execution
    without requiring external native ONNX libraries.
    """
    model.eval()
    sd = model.state_dict()
    tensors = [
        sd["kinematic_net.0.weight"].cpu().numpy().flatten(),
        sd["kinematic_net.0.bias"].cpu().numpy().flatten(),
        sd["kinematic_net.2.weight"].cpu().numpy().flatten(),
        sd["kinematic_net.2.bias"].cpu().numpy().flatten(),
        sd["kinematic_net.3.weight"].cpu().numpy().flatten(),
        sd["kinematic_net.3.bias"].cpu().numpy().flatten(),
        sd["voxel_net.0.weight"].cpu().numpy().flatten(),
        sd["voxel_net.0.bias"].cpu().numpy().flatten(),
        sd["voxel_net.2.weight"].cpu().numpy().flatten(),
        sd["voxel_net.2.bias"].cpu().numpy().flatten(),
        sd["voxel_net.3.weight"].cpu().numpy().flatten(),
        sd["voxel_net.3.bias"].cpu().numpy().flatten(),
        sd["backbone.0.weight"].cpu().numpy().flatten(),
        sd["backbone.0.bias"].cpu().numpy().flatten(),
        sd["backbone.2.weight"].cpu().numpy().flatten(),
        sd["backbone.2.bias"].cpu().numpy().flatten(),
        sd["backbone.3.weight"].cpu().numpy().flatten(),
        sd["backbone.3.bias"].cpu().numpy().flatten(),
        sd["actor_continuous_mean.0.weight"].cpu().numpy().flatten(),
        sd["actor_continuous_mean.0.bias"].cpu().numpy().flatten(),
        sd["actor_continuous_mean.2.weight"].cpu().numpy().flatten(),
        sd["actor_continuous_mean.2.bias"].cpu().numpy().flatten(),
        sd["actor_discrete_logits.0.weight"].cpu().numpy().flatten(),
        sd["actor_discrete_logits.0.bias"].cpu().numpy().flatten(),
        sd["actor_discrete_logits.2.weight"].cpu().numpy().flatten(),
        sd["actor_discrete_logits.2.bias"].cpu().numpy().flatten(),
    ]
    all_floats = np.concatenate(tensors).astype(np.float32)
    os.makedirs(os.path.dirname(os.path.abspath(output_bin_path)), exist_ok=True)
    all_floats.tofile(output_bin_path)
    print(f"✓ Exported pure-JVM weights binary ({len(all_floats)} floats): {output_bin_path}")

    if deploy_to_assets:
        abs_bin = os.path.abspath(asset_target_bin_path)
        os.makedirs(os.path.dirname(abs_bin), exist_ok=True)
        shutil.copyfile(output_bin_path, abs_bin)
        print(f"✓ Deployed pure-JVM weights binary to Minecraft mod assets: {abs_bin}")


def main():
    parser = argparse.ArgumentParser(description="Export Aeonis Movement Policy to ONNX")
    parser.add_argument("--checkpoint", type=str, default="", help="Path to PyTorch .pt checkpoint")
    parser.add_argument("--output", type=str, default="checkpoints/movement_policy.onnx", help="Output .onnx path")
    parser.add_argument("--no-deploy", action="store_true", help="Do not copy to Minecraft mod assets")
    args = parser.parse_args()

    model = MovementActorCritic()
    if args.checkpoint and os.path.exists(args.checkpoint):
        state_dict = torch.load(args.checkpoint, map_location="cpu")
        model.load_state_dict(state_dict)
        print(f"Loaded weights from checkpoint: {args.checkpoint}")
    else:
        print("Using initialized model weights (no checkpoint specified).")

    export_to_onnx(model, output_path=args.output, deploy_to_assets=not args.no_deploy)


if __name__ == "__main__":
    main()
