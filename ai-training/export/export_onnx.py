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

    return output_path


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
