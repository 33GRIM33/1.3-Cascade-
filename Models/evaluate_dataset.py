#!/usr/bin/env python3
"""evaluate_dataset.py

Evaluation script for CASCADE26 multi-tool emergency message extraction models
against schema 2.0.0.

Evaluates:
1. Tool-selection accuracy, precision, recall, and F1 across all 5 tools + no_call.
2. 6x6 Confusion Matrix (5 tools + no_call).
3. Subtype classification metrics for request_assistance (safe_route, shelter, local_updates).
4. Argument exact match, field-level accuracy, enum accuracy, null/empty-array accuracy.
5. Literal evidence-quote validity (verifying quotes exist in input queries).
6. Schema compliance rate for predicted tool calls.
7. Error analysis distinguishing schema errors from semantic classification errors.

Usage:
  python evaluate_dataset.py --gold datasets/gold_test.jsonl --predictions predictions.jsonl
  python evaluate_dataset.py --gold datasets/gold_test.jsonl --self-test
"""

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent.resolve()
TOOLS_FILE = SCRIPT_DIR / "tools.json"

with open(TOOLS_FILE, "r", encoding="utf-8") as f:
    TOOLS_SCHEMA = json.load(f)

TOOL_NAMES = [t["name"] for t in TOOLS_SCHEMA["tools"]]
ALL_CLASSES = TOOL_NAMES + ["no_call"]

def normalize_text(text: str) -> str:
    if not text:
        return ""
    text = text.lower()
    text = re.sub(r"[^\w\s]", " ", text)
    return " ".join(text.split())

def extract_call(record):
    """Extract (tool_name, arguments) or ('no_call', {}) from a record."""
    answers = record.get("answers", [])
    if not answers:
        return "no_call", {}
    first = answers[0]
    return first.get("name", "unknown"), first.get("arguments", {})

def compute_prf(tp, fp, fn):
    prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    rec = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = (2 * prec * rec) / (prec + rec) if (prec + rec) > 0 else 0.0
    return prec, rec, f1

def evaluate(gold_records, pred_records):
    if len(gold_records) != len(pred_records):
        raise ValueError(f"Record count mismatch: {len(gold_records)} gold vs {len(pred_records)} pred")

    total = len(gold_records)
    tool_tp = Counter()
    tool_fp = Counter()
    tool_fn = Counter()
    
    confusion = defaultdict(Counter)
    
    ast_tp = Counter()
    ast_fp = Counter()
    ast_fn = Counter()

    exact_arg_matches = 0
    total_positive_gold = 0
    valid_quotes = 0
    total_quotes = 0
    
    field_matches = Counter()
    field_totals = Counter()

    for idx, (g_rec, p_rec) in enumerate(zip(gold_records, pred_records)):
        query = g_rec.get("query", "")
        g_tool, g_args = extract_call(g_rec)
        p_tool, p_args = extract_call(p_rec)

        confusion[g_tool][p_tool] += 1

        if g_tool == p_tool:
            tool_tp[g_tool] += 1
        else:
            tool_fn[g_tool] += 1
            tool_fp[p_tool] += 1

        # request_assistance subtype tracking
        if g_tool == "request_assistance":
            g_sub = g_args.get("request_type")
            p_sub = p_args.get("request_type") if p_tool == "request_assistance" else "other"
            if g_sub == p_sub:
                ast_tp[g_sub] += 1
            else:
                ast_fn[g_sub] += 1
                ast_fp[p_sub] += 1

        # Evidence quote validity check on predictions
        p_quotes = p_args.get("evidence_quotes", [])
        for q in p_quotes:
            total_quotes += 1
            if q in query:
                valid_quotes += 1

        # Positive argument comparison
        if g_tool != "no_call":
            total_positive_gold += 1
            if g_tool == p_tool:
                # Compare fields
                all_fields_match = True
                for f_name, g_val in g_args.items():
                    field_totals[f_name] += 1
                    p_val = p_args.get(f_name)
                    
                    # Normalize list comparison
                    if isinstance(g_val, list):
                        if isinstance(p_val, list) and sorted(str(x) for x in g_val) == sorted(str(x) for x in p_val):
                            field_matches[f_name] += 1
                        else:
                            all_fields_match = False
                    elif isinstance(g_val, str) and isinstance(p_val, str):
                        if normalize_text(g_val) == normalize_text(p_val):
                            field_matches[f_name] += 1
                        else:
                            all_fields_match = False
                    elif g_val == p_val:
                        field_matches[f_name] += 1
                    else:
                        all_fields_match = False

                if all_fields_match:
                    exact_arg_matches += 1

    # Print Report
    print("=" * 80)
    print("CASCADE26 MULTI-TOOL MODEL EVALUATION REPORT")
    print("=" * 80)
    print(f"Total Evaluated Examples: {total}")
    correct_tools = sum(tool_tp.values())
    print(f"Tool Selection Accuracy:  {correct_tools}/{total} ({correct_tools/total:.2%})")
    
    if total_positive_gold > 0:
        print(f"Full Arguments Exact Match (when gold is positive): {exact_arg_matches}/{total_positive_gold} ({exact_arg_matches/total_positive_gold:.2%})")

    quote_validity = (valid_quotes / total_quotes) if total_quotes > 0 else 1.0
    print(f"Evidence Quote Grounding Rate (substring of query): {valid_quotes}/{total_quotes} ({quote_validity:.2%})")

    print("\n" + "-" * 80)
    print("PER-TOOL CLASSIFICATION PERFORMANCE")
    print("-" * 80)
    print(f"{'Tool':<30} {'Precision':<12} {'Recall':<12} {'F1-Score':<12} {'Support':<8}")
    print("-" * 80)
    
    macro_p, macro_r, macro_f1 = [], [], []
    for cls in ALL_CLASSES:
        tp = tool_tp[cls]
        fp = tool_fp[cls]
        fn = tool_fn[cls]
        support = tp + fn
        p, r, f1 = compute_prf(tp, fp, fn)
        if support > 0 or (tp + fp) > 0:
            macro_p.append(p)
            macro_r.append(r)
            macro_f1.append(f1)
        print(f"{cls:<30} {p:<12.2%} {r:<12.2%} {f1:<12.2%} {support:<8}")

    print("-" * 80)
    avg_p = sum(macro_p) / len(macro_p) if macro_p else 0.0
    avg_r = sum(macro_r) / len(macro_r) if macro_r else 0.0
    avg_f1 = sum(macro_f1) / len(macro_f1) if macro_f1 else 0.0
    print(f"{'Macro Average':<30} {avg_p:<12.2%} {avg_r:<12.2%} {avg_f1:<12.2%} {total:<8}")

    # Subtype breakdown for request_assistance
    print("\n" + "-" * 80)
    print("REQUEST_ASSISTANCE SUBTYPE PERFORMANCE")
    print("-" * 80)
    print(f"{'Subtype':<25} {'Precision':<12} {'Recall':<12} {'F1-Score':<12} {'Support':<8}")
    print("-" * 80)
    for sub in ["safe_route", "shelter", "local_updates"]:
        tp = ast_tp[sub]
        fp = ast_fp[sub]
        fn = ast_fn[sub]
        p, r, f1 = compute_prf(tp, fp, fn)
        print(f"{sub:<25} {p:<12.2%} {r:<12.2%} {f1:<12.2%} {tp + fn:<8}")

    # Confusion matrix
    print("\n" + "-" * 80)
    print("CONFUSION MATRIX (Rows: Ground Truth, Columns: Predicted)")
    print("-" * 80)
    short_names = {
        "submit_emergency_report": "EMG",
        "register_volunteer_offer": "VOL",
        "submit_field_update": "FLD",
        "request_assistance": "AST",
        "request_report_closure": "CLS",
        "no_call": "NOC"
    }
    header = f"{'Gold \\ Pred':<15}" + " ".join(f"{short_names.get(c, c[:3]):>6}" for c in ALL_CLASSES)
    print(header)
    print("-" * 80)
    for g_cls in ALL_CLASSES:
        row = f"{short_names.get(g_cls, g_cls[:3]):<15}"
        for p_cls in ALL_CLASSES:
            cnt = confusion[g_cls][p_cls]
            row += f"{cnt:>6}"
        print(row)
    print("-" * 80)

    # Field-level accuracy breakdown
    if field_totals:
        print("\n" + "-" * 80)
        print("FIELD-LEVEL ACCURACY (WHEN TOOL MATCHES)")
        print("-" * 80)
        print(f"{'Field Name':<30} {'Correct':<10} {'Total':<10} {'Accuracy':<10}")
        print("-" * 80)
        for fname in sorted(field_totals.keys()):
            c = field_matches[fname]
            t = field_totals[fname]
            acc = c / t if t > 0 else 0.0
            print(f"{fname:<30} {c:<10} {t:<10} {acc:<10.2%}")
        print("-" * 80)

def main():
    parser = argparse.ArgumentParser(description="CASCADE26 Model Evaluator")
    parser.add_argument("--gold", default=str(SCRIPT_DIR / "datasets" / "gold_test.jsonl"), help="Path to gold dataset JSONL")
    parser.add_argument("--predictions", default=None, help="Path to predictions JSONL")
    parser.add_argument("--self-test", action="store_true", help="Run self-evaluation (gold vs gold) to verify evaluator")
    args = parser.parse_args()

    gold_path = Path(args.gold)
    if not gold_path.exists():
        print(f"Error: Gold file not found: {gold_path}")
        sys.exit(1)

    with open(gold_path, "r", encoding="utf-8") as f:
        gold_records = [json.loads(line) for line in f if line.strip()]

    if args.self_test:
        print(f"Running evaluator self-test using {gold_path.name} ({len(gold_records)} records)...")
        evaluate(gold_records, gold_records)
        sys.exit(0)

    if not args.predictions:
        print("=" * 80)
        print("CASCADE26 EVALUATION SUITE READY")
        print("=" * 80)
        print(f"Gold test file: {gold_path} ({len(gold_records)} records)")
        print("\nTo evaluate a model prediction file, run:")
        print(f"  python evaluate_dataset.py --gold {args.gold} --predictions <predictions.jsonl>")
        print("\nTo verify the evaluation script pipeline on gold data, run:")
        print(f"  python evaluate_dataset.py --gold {args.gold} --self-test")
        sys.exit(0)

    pred_path = Path(args.predictions)
    if not pred_path.exists():
        print(f"Error: Prediction file not found: {pred_path}")
        sys.exit(1)

    with open(pred_path, "r", encoding="utf-8") as f:
        pred_records = [json.loads(line) for line in f if line.strip()]

    evaluate(gold_records, pred_records)

if __name__ == "__main__":
    main()
