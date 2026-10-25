"""Compare rule and encoder label extraction; save every prediction immediately."""
import argparse
from collections import Counter
import json
from pathlib import Path
import time

import numpy as np

from local_sos import parse_sos
from tiny_encoder import TinyExtractor, DEFAULT_MODEL, HERE
from prepare_tiny_encoder import atomic_json, read_rows


def normalized(value):
    if isinstance(value, list):
        return sorted(normalized(item) for item in value)
    if isinstance(value, str):
        import re
        return " ".join(re.sub(r"[^\w\s]", " ", value.casefold()).split())
    return value


def score(counts, expected, predicted, fields):
    counts["cases"] += 1
    all_correct = True
    for field in fields:
        correct = normalized(expected.get(field)) == normalized(predicted.get(field))
        counts[field + "_exact"] += int(correct)
        all_correct &= correct
    counts["all_fields_exact"] += int(all_correct)
    for field in ("needs", "hazards"):
        actual, wanted = set(predicted[field]), set(expected[field])
        counts[field + "_tp"] += len(actual & wanted)
        counts[field + "_fp"] += len(actual - wanted)
        counts[field + "_fn"] += len(wanted - actual)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--gold", type=Path, default=HERE / "datasets" / "gold_test.jsonl")
    parser.add_argument("--output-dir", type=Path, default=HERE / "checkpoints" / "tiny_encoder_eval")
    parser.add_argument("--show-errors", type=int, default=8)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    extractor = TinyExtractor(args.model_dir)
    gold = []
    for row in read_rows(args.gold):
        if row.get("answers", []) and row["answers"][0]["name"] == "submit_emergency_report":
            gold.append({"text": row["query"], "expected": row["answers"][0]["arguments"]})
    if not gold:
        raise ValueError("Gold file has no emergency cases")
    challenge = read_rows(HERE / "datasets" / "tiny" / "challenge.jsonl")
    challenge = [{"text": row["text"], "expected": {
        "needs": [label[5:] for label in row["labels"] if label.startswith("need:")],
        "hazards": [label[7:] for label in row["labels"] if label.startswith("hazard:")]}}
        for row in challenge]
    summary = {"note": "Gold is a development set. Challenge is authored synthetic wording, not field validation.",
               "latency_note": "This computer's CPU timing, not phone performance.", "sets": {}}
    timings = []
    for name, cases, fields in (
        ("gold_development", gold, ("needs", "hazards", "location_text", "affected_people_count")),
        ("synthetic_challenge", challenge, ("needs", "hazards")),
    ):
        totals = {mode: Counter() for mode in ("rules", "encoder", "hybrid")}
        errors = []
        output = args.output_dir / (name + "_predictions.jsonl")
        with output.open("w", encoding="utf-8") as handle:
            for row in cases:
                baseline = parse_sos(row["text"])
                started = time.perf_counter()
                proposals = extractor.predict(row["text"])
                encoded = extractor.enrich(baseline, "encoder", proposals)
                hybrid = extractor.enrich(baseline, "hybrid", proposals)
                timings.append((time.perf_counter() - started) * 1000)
                outputs = {"rules": baseline, "encoder": encoded, "hybrid": hybrid}
                for mode, prediction in outputs.items():
                    score(totals[mode], row["expected"], prediction, fields)
                handle.write(json.dumps({**row, "predictions": outputs}, ensure_ascii=False) + "\n")
                handle.flush()
                mismatches = [field for field in fields if normalized(row["expected"].get(field)) != normalized(hybrid.get(field))]
                if mismatches:
                    errors.append({"text": row["text"], "fields": mismatches,
                                   "expected": {field: row["expected"].get(field) for field in mismatches},
                                   "hybrid": {field: hybrid.get(field) for field in mismatches}})
        summary["sets"][name] = {mode: dict(count) for mode, count in totals.items()}
        print(f"\n{name}: {len(cases)} cases", flush=True)
        for mode, count in totals.items():
            print(f"  {mode:8s} all fields {count['all_fields_exact']}/{len(cases)}; "
                  f"needs exact {count['needs_exact']}/{len(cases)}, false labels {count['needs_fp']}; "
                  f"hazards exact {count['hazards_exact']}/{len(cases)}, false labels {count['hazards_fp']}", flush=True)
        for error in errors[:args.show_errors]:
            print(json.dumps(error, ensure_ascii=False), flush=True)
        atomic_json(args.output_dir / "summary.json", summary)
    summary["cpu_latency_ms"] = {"median": float(np.median(timings)), "p95": float(np.percentile(timings, 95))}
    summary["model_bytes"] = sum(p.stat().st_size for p in args.model_dir.iterdir() if p.is_file())
    summary["automatic_promotion"] = False
    atomic_json(args.output_dir / "summary.json", summary)
    print("\nSaved predictions and summary to", args.output_dir, flush=True)
    print("CPU timing (ms):", summary["cpu_latency_ms"], flush=True)
    print("Experimental comparison only; review false labels before enabling the encoder in the app.", flush=True)


if __name__ == "__main__":
    main()
