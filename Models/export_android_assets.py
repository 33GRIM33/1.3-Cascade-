"""Export model bundle and assets for Android GHOSTNET/CASCADE app integration.

Copies required ONNX model, tokenizer, and JSON heads into an Android assets folder:
- encoder.onnx (17.5 MB INT8 quantized MiniLM)
- tokenizer.json (HuggingFace WordPiece tokenizer)
- config.json (model architecture metadata)
- head.json (multilabel need/hazard weights)
- intent_head.json (tool intent classification weights)

Usage:
    python3 export_android_assets.py --output-dir ../android_assets
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

from tiny_encoder import DEFAULT_MODEL

REQUIRED_FILES = [
    "encoder.onnx",
    "tokenizer.json",
    "config.json",
    "head.json",
    "intent_head.json",
]


def main():
    parser = argparse.ArgumentParser(description="Export CASCADE model assets for Android")
    parser.add_argument(
        "--model-dir", type=Path, default=DEFAULT_MODEL, help="Source model bundle directory"
    )
    parser.add_argument(
        "--output-dir", type=Path, default=Path(__file__).parent.parent / "android_assets",
        help="Target directory for Android assets (e.g. app/src/main/assets/cascade)"
    )
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    print(f"Exporting CASCADE model assets from {args.model_dir} to {args.output_dir}...", flush=True)

    manifest = {"files": {}, "version": "cascade.model-bundle.v1"}
    for filename in REQUIRED_FILES:
        src = args.model_dir / filename
        if not src.exists():
            raise FileNotFoundError(f"Missing required model asset: {src}")
        dst = args.output_dir / filename
        shutil.copy2(src, dst)
        size_kb = dst.stat().st_size / 1024
        sha256 = hashlib.sha256(dst.read_bytes()).hexdigest()
        manifest["files"][filename] = {"size_kb": round(size_kb, 2), "sha256": sha256}
        print(f"  ✓ {filename:20s} ({size_kb:8.1f} KB)", flush=True)

    manifest_path = args.output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\nManifest written to {manifest_path}", flush=True)
    total_mb = sum(f.stat().st_size for f in args.output_dir.iterdir() if f.is_file()) / (1024 * 1024)
    print(f"Total Android asset bundle size: {total_mb:.2f} MiB", flush=True)
    print("Ready to drop into Android app/src/main/assets/ or mesh/src/main/assets/", flush=True)


if __name__ == "__main__":
    main()
