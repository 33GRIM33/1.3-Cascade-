"""Download a pinned INT8 encoder and fit only its small classification head."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import time
import urllib.request

os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
os.environ.setdefault("OMP_NUM_THREADS", "2")
import numpy as np

from tiny_encoder import Encoder, DEFAULT_MODEL, HERE

REPOSITORY = "sentence-transformers/paraphrase-MiniLM-L3-v2"
REVISION = "e51865bda319ed60379f4393eb95e01027ae5721"
MODEL_SHA256 = "44a2d6852c28e7a4cff4d77a8bc8d5ca2a8b38884c1fa746e6652d830109e0c2"


def atomic_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
        handle.flush()
        os.fsync(handle.fileno())
    json.loads(temporary.read_text(encoding="utf-8"))
    os.replace(temporary, path)


def download(remote, destination, expected_hash=None):
    if destination.exists():
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        if expected_hash is None or digest == expected_hash:
            print(f"  cached {destination.name}", flush=True)
            return digest
    url = f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{remote}"
    temporary = destination.with_name(destination.name + ".part")
    print(f"  download {remote}", flush=True)
    for attempt in range(3):
        try:
            digest = hashlib.sha256()
            size = 0
            with urllib.request.urlopen(url, timeout=60) as source, temporary.open("wb") as target:
                while chunk := source.read(1024 * 1024):
                    size += len(chunk)
                    if size > 80 * 1024 * 1024:
                        raise ValueError("Unexpected download size")
                    target.write(chunk)
                    digest.update(chunk)
                target.flush()
                os.fsync(target.fileno())
            if expected_hash and digest.hexdigest() != expected_hash:
                raise ValueError("Downloaded encoder failed SHA256 verification")
            if not size:
                raise ValueError("Downloaded file is empty")
            os.replace(temporary, destination)
            return digest.hexdigest()
        except (OSError, ValueError):
            if attempt == 2:
                raise
            time.sleep(1 + attempt)


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def train_head(encoder, train, validation):
    labels = sorted({label for row in train for label in row["labels"]})
    x = encoder.encode([row["text"] for row in train]).astype(np.float64)
    v = encoder.encode([row["text"] for row in validation]).astype(np.float64)
    y = np.asarray([[label in row["labels"] for label in labels] for row in train], dtype=np.float64)
    expected = np.asarray([[label in row["labels"] for label in labels] for row in validation])
    xmean, ymean = x.mean(axis=0), y.mean(axis=0)
    centered = x - xmean
    gram = centered @ centered.T
    best = None
    # Choose regularization and a shared threshold using validation only.
    # Gold, smoke, and challenge files are never read by this trainer.
    for regularization in (0.01, 0.1, 1.0, 10.0):
        weights = centered.T @ np.linalg.solve(gram + regularization * np.eye(len(x)), y - ymean)
        bias = ymean - xmean @ weights
        scores = v @ weights + bias
        for threshold in (0.2, 0.3, 0.4, 0.5, 0.6, 0.7):
            predicted = scores >= threshold
            tp = int((predicted & expected).sum())
            fp = int((predicted & ~expected).sum())
            fn = int((~predicted & expected).sum())
            f05 = 1.25 * tp / max(1, 1.25 * tp + fp + 0.25 * fn)
            if best is None or f05 > best[0]:
                best = (f05, weights, bias, threshold, regularization,
                        {"examples": len(validation), "tp": tp, "fp": fp, "fn": fn,
                         "exact": int(np.all(predicted == expected, axis=1).sum())})
    _, weights, bias, threshold, regularization, metrics = best
    return {"format": "cascade.encoder-head.v1", "labels": labels,
            "weights": weights.round(8).tolist(), "bias": bias.round(8).tolist(),
            "threshold": threshold, "regularization": regularization,
            "validation": metrics, "training_examples": len(train),
            "status": "experimental; scores are not calibrated confidence"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL)
    args = parser.parse_args()
    directory = args.model_dir
    directory.mkdir(parents=True, exist_ok=True)
    print("CPU encoder setup: no GPU training", flush=True)
    manifest = {"repository": REPOSITORY, "revision": REVISION, "files": {}}
    for remote, local, sha in (
        ("onnx/model_qint8_arm64.onnx", "encoder.onnx", MODEL_SHA256),
        ("tokenizer.json", "tokenizer.json", None),
        ("config.json", "config.json", None),
        ("README.md", "README.md", None),
    ):
        manifest["files"][local] = download(remote, directory / local, sha)
    manifest["license"] = "Apache-2.0"
    manifest["source"] = f"https://huggingface.co/{REPOSITORY}"
    atomic_json(directory / "manifest.json", manifest)
    encoder = Encoder(directory)
    probe = encoder.encode(["Please help."])
    print(f"  encoder smoke check passed: {probe.shape}", flush=True)
    train = read_rows(HERE / "datasets" / "tiny" / "train.jsonl")
    validation = read_rows(HERE / "datasets" / "tiny" / "validation.jsonl")
    train_texts = {row["text"].strip().casefold() for row in train}
    if any(row["text"].strip().casefold() in train_texts for row in validation):
        raise ValueError("Training and validation texts overlap")
    print(f"  fit small head: {len(train)} training / {len(validation)} validation examples", flush=True)
    head = train_head(encoder, train, validation)
    head["data_sha256"] = {name: hashlib.sha256((HERE / "datasets" / "tiny" / f"{name}.jsonl").read_bytes()).hexdigest()
                           for name in ("train", "validation")}
    # Save before evaluation, and read back to verify the artifact.
    atomic_json(directory / "head.json", head)
    from tiny_encoder import TinyExtractor
    extractor = TinyExtractor(directory)
    extractor.predict("We need drinking water.")
    print(f"  SAVED and reloaded: {directory / 'head.json'}", flush=True)
    print(f"  model bundle: {sum(p.stat().st_size for p in directory.iterdir() if p.is_file()) / 1048576:.2f} MiB", flush=True)
    print("  validation:", json.dumps(head["validation"]), flush=True)


if __name__ == "__main__":
    main()
