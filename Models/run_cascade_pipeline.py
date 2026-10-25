"""CLI runner for the unified CASCADE extraction pipeline.

Usage:
    python3 run_cascade_pipeline.py --query "Water is rising fast, 2 people trapped on the roof!"
"""
import argparse
import json
from pathlib import Path

from cascade_pipeline import CascadePipeline
from tiny_encoder import DEFAULT_MODEL


def main():
    parser = argparse.ArgumentParser(
        description="Run CASCADE unified extraction pipeline on a message"
    )
    parser.add_argument("--query", required=True, help="Input message text")
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL,
                        help="Path to tiny encoder model bundle")
    parser.add_argument("--origin-id", default="cli-local-device",
                        help="Device origin identifier")
    parser.add_argument("--provided-location", default=None,
                        help="Explicit location from user form/GPS")
    parser.add_argument("--mode", choices=("encoder", "hybrid"), default="hybrid",
                        help="Extraction mode (default: hybrid)")
    args = parser.parse_args()

    pipeline = CascadePipeline(model_dir=args.model_dir)
    result = pipeline.process(
        args.query,
        origin_id=args.origin_id,
        provided_location=args.provided_location,
        mode=args.mode,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
