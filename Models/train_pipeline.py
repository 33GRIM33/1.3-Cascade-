"""Train both the intent classifier and label tagger heads on the MiniLM encoder.

Usage from WSL:
    python3 train_pipeline.py

This script:
1. Ensures the MiniLM ONNX encoder is downloaded (via prepare_tiny_encoder)
2. Trains the intent classifier head (384->6) on the full 3,000-example dataset
3. Trains the label tagger head (384->19) on tiny encoder data
4. Saves both heads to checkpoints/tiny_encoder/
5. Runs a quick smoke test on the unified pipeline
"""
import hashlib
import json
import os
import sys
from pathlib import Path

os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
os.environ.setdefault("OMP_NUM_THREADS", "2")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from tiny_encoder import Encoder, DEFAULT_MODEL
from intent_classifier import (
    INTENT_LABELS, _extract_intent_data, train_intent_head,
)
from prepare_tiny_encoder import atomic_json, read_rows, train_head, main as prepare_main


def main():
    # Stage 1: Ensure encoder is ready (downloads if needed, trains label head)
    model_dir = DEFAULT_MODEL
    if not (model_dir / "encoder.onnx").exists():
        print("=== Stage 1: Downloading encoder and training label head ===", flush=True)
        prepare_main()
    else:
        print(f"=== Stage 1: Encoder already at {model_dir} ===", flush=True)
        # Still need to train the label head if it doesn't exist
        if not (model_dir / "head.json").exists():
            print("  Training label head...", flush=True)
            encoder = Encoder(model_dir)
            train_data = read_rows(HERE / "datasets" / "tiny" / "train.jsonl")
            val_data = read_rows(HERE / "datasets" / "tiny" / "validation.jsonl")
            head = train_head(encoder, train_data, val_data)
            head["data_sha256"] = {
                name: hashlib.sha256(
                    (HERE / "datasets" / "tiny" / f"{name}.jsonl").read_bytes()
                ).hexdigest()
                for name in ("train", "validation")
            }
            atomic_json(model_dir / "head.json", head)
            print(f"  Saved label head: {model_dir / 'head.json'}", flush=True)

    encoder = Encoder(model_dir)
    print(f"  Encoder ready: {encoder.encode(['test']).shape}", flush=True)

    # Stage 2: Train intent classifier head
    print("\n=== Stage 2: Training intent classifier ===", flush=True)
    train_path = HERE / "datasets" / "train.jsonl"
    val_path = HERE / "datasets" / "validation.jsonl"

    if not train_path.exists():
        print(f"  ERROR: {train_path} not found. Run generate_dataset.py first.", flush=True)
        sys.exit(1)

    train_texts, train_labels = _extract_intent_data(train_path)
    val_texts, val_labels = _extract_intent_data(val_path)
    print(f"  Training examples: {len(train_texts)}", flush=True)
    print(f"  Validation examples: {len(val_texts)}", flush=True)

    # Check label distribution
    from collections import Counter
    train_dist = Counter(INTENT_LABELS[i] for i in train_labels)
    print(f"  Training distribution:", flush=True)
    for label in INTENT_LABELS:
        print(f"    {label}: {train_dist.get(label, 0)}", flush=True)

    intent_head = train_intent_head(encoder, train_texts, train_labels,
                                     val_texts, val_labels)
    intent_path = model_dir / "intent_head.json"
    atomic_json(intent_path, intent_head)
    print(f"  Saved intent head: {intent_path}", flush=True)
    print(f"  Validation accuracy: {intent_head['validation']['accuracy']:.1%}", flush=True)
    print(f"  Best regularization: {intent_head['regularization']}", flush=True)

    # Stage 3: Smoke test
    print("\n=== Stage 3: Smoke test ===", flush=True)
    from intent_classifier import IntentClassifier
    classifier = IntentClassifier(encoder, intent_path)

    test_cases = [
        ("Water is rising and we are trapped on the roof!", "submit_emergency_report"),
        ("I have a boat and can help with rescue operations.", "register_volunteer_offer"),
        ("The main road is blocked by fallen trees.", "submit_field_update"),
        ("Can you show me a safe route to the hospital?", "request_assistance"),
        ("Report REQ-123 can be closed, everyone was rescued.", "request_report_closure"),
        ("What time does the meeting start?", "no_call"),
    ]
    correct = 0
    for text, expected in test_cases:
        intent, confidence, scores = classifier.classify(text)
        status = "OK" if intent == expected else "MISS"
        if intent == expected:
            correct += 1
        print(f"  [{status}] '{text[:50]}...' -> {intent} ({confidence:.1%})"
              f"{'' if intent == expected else f' (expected {expected})'}", flush=True)
    print(f"  Smoke test: {correct}/{len(test_cases)} correct", flush=True)

    # Stage 4: Evaluate on gold test
    print("\n=== Stage 4: Gold test evaluation ===", flush=True)
    gold_path = HERE / "datasets" / "gold_test.jsonl"
    if gold_path.exists():
        gold_texts, gold_labels = _extract_intent_data(gold_path)
        gold_correct = 0
        intent_errors = []
        for i, (text, expected_idx) in enumerate(zip(gold_texts, gold_labels)):
            intent, confidence, _ = classifier.classify(text)
            predicted_idx = INTENT_LABELS.index(intent)
            if predicted_idx == expected_idx:
                gold_correct += 1
            else:
                intent_errors.append({
                    "text": text[:80],
                    "expected": INTENT_LABELS[expected_idx],
                    "predicted": intent,
                    "confidence": round(confidence, 3),
                })
        print(f"  Gold intent accuracy: {gold_correct}/{len(gold_texts)}"
              f" ({gold_correct/len(gold_texts):.1%})", flush=True)
        for err in intent_errors[:5]:
            print(f"    MISS: '{err['text']}...'", flush=True)
            print(f"          expected={err['expected']}, got={err['predicted']}"
                  f" ({err['confidence']:.1%})", flush=True)

        # Save results
        results = {
            "intent_head": {
                "train_examples": len(train_texts),
                "val_accuracy": intent_head["validation"]["accuracy"],
                "gold_accuracy": round(gold_correct / len(gold_texts), 4),
                "gold_total": len(gold_texts),
                "gold_correct": gold_correct,
                "regularization": intent_head["regularization"],
            },
            "label_head": json.loads((model_dir / "head.json").read_text(encoding="utf-8")).get("validation", {}),
            "errors": intent_errors[:10],
        }
        results_path = HERE / "checkpoints" / "pipeline_eval" / "training_results.json"
        results_path.parent.mkdir(parents=True, exist_ok=True)
        atomic_json(results_path, results)
        print(f"\n  Saved results to {results_path}", flush=True)
    else:
        print(f"  WARNING: {gold_path} not found, skipping gold evaluation", flush=True)

    print("\n=== Pipeline training complete ===", flush=True)
    total_size = sum(p.stat().st_size for p in model_dir.iterdir() if p.is_file())
    print(f"  Total model bundle: {total_size / 1048576:.2f} MiB", flush=True)


if __name__ == "__main__":
    main()
