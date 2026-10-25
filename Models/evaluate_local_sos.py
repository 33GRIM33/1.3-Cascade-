#!/usr/bin/env python3
"""Measure the offline SOS parser on the reviewed emergency benchmark."""

import argparse
import json
import re
from collections import Counter
from pathlib import Path

from local_sos import build_envelope, wire_bytes


HERE = Path(__file__).resolve().parent
FIELDS = ("needs", "hazards", "location_text", "affected_people_count")


def normalized(value):
    if isinstance(value, list):
        return sorted(normalized(item) for item in value)
    if isinstance(value, str):
        return " ".join(re.sub(r"[^\w\s]", " ", value.casefold()).split())
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", type=Path, default=HERE / "datasets" / "gold_test.jsonl")
    parser.add_argument("--show-errors", type=int, default=20)
    args = parser.parse_args()

    counts = Counter()
    errors = []
    for line in args.gold.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        gold = json.loads(line)
        answers = gold.get("answers") or []
        if not answers or answers[0].get("name") != "submit_emergency_report":
            continue
        counts["cases"] += 1
        expected = answers[0]["arguments"]
        envelope = build_envelope(gold["query"], "evaluation-device", now=1000)
        predicted = envelope["payload"]
        if json.loads(wire_bytes(envelope))["payload"]["raw_text"] == gold["query"]:
            counts["queued_with_original_text"] += 1
        mismatches = []
        for field in FIELDS:
            if normalized(predicted[field]) == normalized(expected[field]):
                counts[field + "_exact"] += 1
            else:
                mismatches.append(field)
        if not mismatches:
            counts["four_fields_exact"] += 1
        if normalized(predicted["requested_items"]) == normalized(expected["need_details"]):
            counts["requested_items_exact"] += 1
        counts["gold_needs"] += len(expected["needs"])
        counts["matched_needs"] += len(set(predicted["needs"]) & set(expected["needs"]))
        counts["false_needs"] += len(set(predicted["needs"]) - set(expected["needs"]))
        counts["gold_hazards"] += len(expected["hazards"])
        counts["matched_hazards"] += len(set(predicted["hazards"]) & set(expected["hazards"]))
        counts["false_hazards"] += len(set(predicted["hazards"]) - set(expected["hazards"]))
        quotes = predicted["evidence_quotes"]
        counts["evidence_quotes"] += len(quotes)
        counts["grounded_quotes"] += sum(quote in gold["query"] for quote in quotes)
        if mismatches:
            errors.append((gold["query"], mismatches, expected, predicted))

    total = counts["cases"]
    print(f"Reviewed gold emergency cases: {total}")
    print(f"SOS envelopes preserving raw text: {counts['queued_with_original_text']}/{total}")
    for field in FIELDS:
        print(f"{field} exact: {counts[field + '_exact']}/{total}")
    print(f"Requested items exact: {counts['requested_items_exact']}/{total}")
    print(f"All four fields exact: {counts['four_fields_exact']}/{total}")
    print(f"Gold need recall: {counts['matched_needs']}/{counts['gold_needs']}")
    print(f"False need labels: {counts['false_needs']}")
    print(f"Gold hazard recall: {counts['matched_hazards']}/{counts['gold_hazards']}")
    print(f"False hazard labels: {counts['false_hazards']}")
    print(f"Literal evidence quotes: {counts['grounded_quotes']}/{counts['evidence_quotes']}")
    for query, fields, expected, predicted in errors[:args.show_errors]:
        print("\nQUERY:", query)
        print("MISMATCH:", ", ".join(fields))
        print("EXPECTED:", json.dumps({key: expected[key] for key in fields}, ensure_ascii=False))
        print("PREDICTED:", json.dumps({key: predicted[key] for key in fields}, ensure_ascii=False))


if __name__ == "__main__":
    main()
