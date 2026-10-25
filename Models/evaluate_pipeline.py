"""Evaluate the full CASCADE pipeline on gold test and challenge sets.

Usage from WSL:
    python3 evaluate_pipeline.py

Reports:
- Intent classification accuracy (6-class)
- Need/hazard label accuracy (for emergency cases)
- Slot extraction accuracy (location, count)
- Comparison: rules-only vs encoder-only vs hybrid
- Per-class confusion details
- Timing statistics
"""
import argparse
import json
import re
import time
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

from cascade_pipeline import CascadePipeline
from intent_classifier import INTENT_LABELS, _extract_intent_data
from local_sos import parse_sos
from prepare_tiny_encoder import atomic_json, read_rows
from tiny_encoder import HERE

FIELDS = ("needs", "hazards", "location_text", "affected_people_count")


def normalized(value):
    if isinstance(value, list):
        return sorted(normalized(item) for item in value)
    if isinstance(value, str):
        return " ".join(re.sub(r"[^\w\s]", " ", value.casefold()).split())
    return value


def score_labels(counts, expected, predicted, fields):
    """Score multi-label and scalar fields."""
    counts["cases"] += 1
    all_correct = True
    for field in fields:
        correct = normalized(expected.get(field)) == normalized(predicted.get(field))
        counts[field + "_exact"] += int(correct)
        all_correct &= correct
    counts["all_fields_exact"] += int(all_correct)
    for field in ("needs", "hazards"):
        actual = set(predicted.get(field, []))
        wanted = set(expected.get(field, []))
        counts[field + "_tp"] += len(actual & wanted)
        counts[field + "_fp"] += len(actual - wanted)
        counts[field + "_fn"] += len(wanted - actual)


def main():
    parser = argparse.ArgumentParser(
        description="Evaluate the full CASCADE extraction pipeline")
    parser.add_argument("--model-dir", type=Path, default=None)
    parser.add_argument("--gold", type=Path,
                        default=HERE / "datasets" / "gold_test.jsonl")
    parser.add_argument("--output-dir", type=Path,
                        default=HERE / "checkpoints" / "pipeline_eval")
    parser.add_argument("--show-errors", type=int, default=8)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    print("Loading pipeline...", flush=True)
    kwargs = {}
    if args.model_dir:
        kwargs["model_dir"] = args.model_dir
    pipeline = CascadePipeline(**kwargs)

    # =========================================================================
    # Section 1: Intent classification on gold test
    # =========================================================================
    print("\n" + "=" * 60, flush=True)
    print("SECTION 1: Intent Classification", flush=True)
    print("=" * 60, flush=True)

    gold_texts, gold_labels = _extract_intent_data(args.gold)
    intent_correct = 0
    confusion = defaultdict(Counter)
    intent_errors = []
    intent_timings = []

    for text, expected_idx in zip(gold_texts, gold_labels):
        started = time.perf_counter()
        intent, confidence, scores = pipeline.classify_only(text)
        intent_timings.append((time.perf_counter() - started) * 1000)
        predicted_idx = INTENT_LABELS.index(intent)
        confusion[INTENT_LABELS[expected_idx]][intent] += 1
        if predicted_idx == expected_idx:
            intent_correct += 1
        else:
            intent_errors.append({
                "text": text[:100],
                "expected": INTENT_LABELS[expected_idx],
                "predicted": intent,
                "confidence": round(confidence, 3),
            })

    total = len(gold_texts)
    print(f"\nIntent accuracy: {intent_correct}/{total}"
          f" ({intent_correct/total:.1%})", flush=True)
    print(f"Intent latency: median={np.median(intent_timings):.1f}ms,"
          f" p95={np.percentile(intent_timings, 95):.1f}ms", flush=True)

    # Per-class accuracy
    print("\nPer-class breakdown:", flush=True)
    for label in INTENT_LABELS:
        row = confusion[label]
        class_total = sum(row.values())
        class_correct = row.get(label, 0)
        if class_total > 0:
            print(f"  {label:30s}: {class_correct}/{class_total}"
                  f" ({class_correct/class_total:.0%})", flush=True)

    if intent_errors:
        print(f"\nIntent errors ({len(intent_errors)} total,"
              f" showing {min(len(intent_errors), args.show_errors)}):", flush=True)
        for err in intent_errors[:args.show_errors]:
            print(f"  '{err['text']}'", flush=True)
            print(f"    expected={err['expected']}, got={err['predicted']}"
                  f" ({err['confidence']:.1%})", flush=True)

    # =========================================================================
    # Section 2: Emergency extraction comparison (rules vs encoder vs hybrid)
    # =========================================================================
    print("\n" + "=" * 60, flush=True)
    print("SECTION 2: Emergency Label + Slot Extraction", flush=True)
    print("=" * 60, flush=True)

    # Load gold emergency cases
    gold_emergency = []
    for row in read_rows(args.gold):
        answers = row.get("answers", [])
        if answers and answers[0].get("name") == "submit_emergency_report":
            gold_emergency.append({
                "text": row["query"],
                "expected": answers[0]["arguments"],
            })

    # Load challenge cases
    challenge_path = HERE / "datasets" / "tiny" / "challenge.jsonl"
    challenge = []
    if challenge_path.exists():
        for row in read_rows(challenge_path):
            challenge.append({
                "text": row["text"],
                "expected": {
                    "needs": [l[5:] for l in row["labels"] if l.startswith("need:")],
                    "hazards": [l[7:] for l in row["labels"] if l.startswith("hazard:")],
                },
            })

    extraction_timings = []

    for name, cases, fields in (
        ("gold_development", gold_emergency, FIELDS),
        ("synthetic_challenge", challenge, ("needs", "hazards")),
    ):
        totals = {mode: Counter() for mode in ("rules", "encoder", "hybrid")}
        errors = []
        output = args.output_dir / f"{name}_predictions.jsonl"

        with output.open("w", encoding="utf-8") as handle:
            for row in cases:
                text = row["text"]
                baseline = parse_sos(text)
                started = time.perf_counter()
                proposals = pipeline.label_extractor.predict(text)
                encoded = pipeline.label_extractor.enrich(baseline, "encoder", proposals)
                hybrid = pipeline.label_extractor.enrich(baseline, "hybrid", proposals)
                extraction_timings.append(
                    (time.perf_counter() - started) * 1000)

                outputs = {"rules": baseline, "encoder": encoded, "hybrid": hybrid}
                for mode, prediction in outputs.items():
                    score_labels(totals[mode], row["expected"], prediction, fields)

                handle.write(json.dumps(
                    {**row, "predictions": outputs}, ensure_ascii=False) + "\n")
                handle.flush()

                # Track hybrid errors
                mismatches = [
                    f for f in fields
                    if normalized(row["expected"].get(f)) != normalized(hybrid.get(f))
                ]
                if mismatches:
                    errors.append({
                        "text": text[:100],
                        "fields": mismatches,
                        "expected": {f: row["expected"].get(f) for f in mismatches},
                        "hybrid": {f: hybrid.get(f) for f in mismatches},
                    })

        print(f"\n{name}: {len(cases)} cases", flush=True)
        for mode, count in totals.items():
            recall_n = (f"{count['needs_tp']}/{count['needs_tp']+count['needs_fn']}"
                        if count['needs_tp'] + count['needs_fn'] > 0 else "n/a")
            recall_h = (f"{count['hazards_tp']}/{count['hazards_tp']+count['hazards_fn']}"
                        if count['hazards_tp'] + count['hazards_fn'] > 0 else "n/a")
            print(f"  {mode:8s} all_exact={count['all_fields_exact']}/{len(cases)}"
                  f"  needs_exact={count['needs_exact']}/{len(cases)}"
                  f"  need_recall={recall_n} fp={count['needs_fp']}"
                  f"  hazard_recall={recall_h} fp={count['hazards_fp']}",
                  flush=True)

        for error in errors[:args.show_errors]:
            print(f"  HYBRID ERROR: '{error['text']}'", flush=True)
            print(f"    fields={error['fields']}", flush=True)
            print(f"    expected={json.dumps(error['expected'], ensure_ascii=False)}", flush=True)
            print(f"    hybrid  ={json.dumps(error['hybrid'], ensure_ascii=False)}", flush=True)

    # =========================================================================
    # Section 3: Full pipeline end-to-end
    # =========================================================================
    print("\n" + "=" * 60, flush=True)
    print("SECTION 3: Full Pipeline End-to-End", flush=True)
    print("=" * 60, flush=True)

    full_timings = []
    full_correct_intent = 0
    full_correct_emergency_labels = 0
    emergency_count = 0

    for text, expected_idx in zip(gold_texts, gold_labels):
        started = time.perf_counter()
        result = pipeline.process(text, origin_id="eval-device")
        full_timings.append((time.perf_counter() - started) * 1000)

        predicted_idx = INTENT_LABELS.index(result["intent"])
        if predicted_idx == expected_idx:
            full_correct_intent += 1

        if (INTENT_LABELS[expected_idx] == "submit_emergency_report"
                and result["extraction"]):
            emergency_count += 1
            # Check if envelope was built successfully
            if result.get("envelope"):
                full_correct_emergency_labels += 1

    print(f"\nFull pipeline intent accuracy: {full_correct_intent}/{total}"
          f" ({full_correct_intent/total:.1%})", flush=True)
    print(f"Emergency envelopes built: {full_correct_emergency_labels}/{emergency_count}",
          flush=True)
    print(f"Full pipeline latency: median={np.median(full_timings):.1f}ms,"
          f" p95={np.percentile(full_timings, 95):.1f}ms", flush=True)

    # =========================================================================
    # Save summary
    # =========================================================================
    summary = {
        "note": "Gold is a development set. Challenge is authored synthetic wording.",
        "latency_note": "This computer's CPU timing, not phone performance.",
        "intent": {
            "accuracy": round(intent_correct / total, 4),
            "total": total,
            "correct": intent_correct,
            "errors": len(intent_errors),
            "latency_ms": {
                "median": round(float(np.median(intent_timings)), 2),
                "p95": round(float(np.percentile(intent_timings, 95)), 2),
            },
            "confusion": {k: dict(v) for k, v in confusion.items()},
        },
        "extraction_latency_ms": {
            "median": round(float(np.median(extraction_timings)), 2),
            "p95": round(float(np.percentile(extraction_timings, 95)), 2),
        } if extraction_timings else None,
        "full_pipeline_latency_ms": {
            "median": round(float(np.median(full_timings)), 2),
            "p95": round(float(np.percentile(full_timings, 95)), 2),
        },
        "automatic_promotion": False,
    }
    atomic_json(args.output_dir / "pipeline_summary.json", summary)
    print(f"\nSaved summary to {args.output_dir / 'pipeline_summary.json'}", flush=True)
    print("\nReview false labels before enabling any model in the app.", flush=True)


if __name__ == "__main__":
    main()
