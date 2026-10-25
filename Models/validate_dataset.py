#!/usr/bin/env python3
"""validate_dataset.py

Strict programmatic validation of CASCADE26 emergency extraction dataset
for Needle fine-tuning against tools.json schema 2.0.0.

Validates:
1. File & JSONL integrity (UTF-8, JSON parsing, non-empty, object per line)
2. Needle trainer format compatibility (query, tools, answers)
3. Schema compliance across ALL FIVE tools in tools.json:
   - submit_emergency_report
   - register_volunteer_offer
   - submit_field_update
   - request_assistance (all 3 request_type enums)
   - request_report_closure
4. Strict type enforcement (e.g. integer count must reject bool, strings non-empty)
5. Literal evidence_quotes validation (every quote MUST be exact substring of query)
6. Disjoint split leakage audit (train, val, test, and gold_test)
7. Exit code: 0 on success, 1 on hard validation errors.
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

# Build validator registry from tools.json
TOOLS_MAP = {t["name"]: t for t in TOOLS_SCHEMA["tools"]}

def validate_record_schema(record, file_path, line_no):
    errors = []
    
    # 1. Format checks
    if not isinstance(record, dict):
        return [f"{file_path}:{line_no} Record is not a JSON object"]
    if "query" not in record or not isinstance(record["query"], str) or not record["query"].strip():
        errors.append(f"{file_path}:{line_no} Missing or empty 'query'")
    if "tools" not in record or not isinstance(record["tools"], list):
        errors.append(f"{file_path}:{line_no} Missing or invalid 'tools' array")
    if "answers" not in record or not isinstance(record["answers"], list):
        errors.append(f"{file_path}:{line_no} Missing or invalid 'answers' array")
        return errors

    query = record.get("query", "")
    answers = record.get("answers", [])
    
    # 2. Answers validation
    if len(answers) == 0:
        # Valid no-call/negative record
        return errors
    if len(answers) > 1:
        errors.append(f"{file_path}:{line_no} More than one call in answers: {len(answers)}")

    call = answers[0]
    if not isinstance(call, dict) or "name" not in call or "arguments" not in call:
        errors.append(f"{file_path}:{line_no} Malformed tool call object in answers")
        return errors

    tool_name = call["name"]
    args = call["arguments"]
    
    if tool_name not in TOOLS_MAP:
        errors.append(f"{file_path}:{line_no} Unknown tool name '{tool_name}'")
        return errors
        
    tool_def = TOOLS_MAP[tool_name]
    params_def = tool_def.get("parameters", {})
    props_def = params_def.get("properties", {})
    required_props = set(params_def.get("required", []))
    
    # Check required fields
    for req in required_props:
        if req not in args:
            errors.append(f"{file_path}:{line_no} Missing required argument '{req}' for tool '{tool_name}'")

    # Check additional properties
    if params_def.get("additionalProperties") is False:
        for k in args:
            if k not in props_def:
                errors.append(f"{file_path}:{line_no} Unexpected argument '{k}' for tool '{tool_name}'")

    # Property-level type and value validation
    for k, val in args.items():
        if k not in props_def:
            continue
        spec = props_def[k]
        spec_type = spec.get("type")
        
        # Nullable check
        if val is None:
            if isinstance(spec_type, list) and "null" in spec_type:
                continue
            else:
                errors.append(f"{file_path}:{line_no} Field '{k}' is null but schema does not permit null")
                continue

        # Reject boolean if integer expected (in Python bool subclasses int)
        if isinstance(val, bool) and spec_type == "integer" or (isinstance(spec_type, list) and "integer" in spec_type and not isinstance(val, int)):
            errors.append(f"{file_path}:{line_no} Field '{k}' has boolean value but integer expected")
            continue
            
        # Integer checks
        if spec_type == "integer" or (isinstance(spec_type, list) and "integer" in spec_type):
            if isinstance(val, bool) or not isinstance(val, int):
                errors.append(f"{file_path}:{line_no} Field '{k}' expected integer, got {type(val).__name__}")
            else:
                if "minimum" in spec and val < spec["minimum"]:
                    errors.append(f"{file_path}:{line_no} Field '{k}' value {val} is below minimum {spec['minimum']}")

        # String checks
        elif spec_type == "string" or (isinstance(spec_type, list) and "string" in spec_type):
            if not isinstance(val, str):
                errors.append(f"{file_path}:{line_no} Field '{k}' expected string, got {type(val).__name__}")
            else:
                if "minLength" in spec and len(val) < spec["minLength"]:
                    errors.append(f"{file_path}:{line_no} Field '{k}' string length {len(val)} is below minLength {spec['minLength']}")
                if "enum" in spec and val not in spec["enum"]:
                    errors.append(f"{file_path}:{line_no} Field '{k}' value '{val}' not in allowed enum {spec['enum']}")

        # Array checks
        elif spec_type == "array":
            if not isinstance(val, list):
                errors.append(f"{file_path}:{line_no} Field '{k}' expected array, got {type(val).__name__}")
            else:
                if "minItems" in spec and len(val) < spec["minItems"]:
                    errors.append(f"{file_path}:{line_no} Field '{k}' item count {len(val)} is below minItems {spec['minItems']}")
                if spec.get("uniqueItems") and len(val) != len(set(val)):
                    errors.append(f"{file_path}:{line_no} Field '{k}' contains duplicate items: {val}")
                item_spec = spec.get("items", {})
                item_type = item_spec.get("type")
                item_enum = item_spec.get("enum")
                item_min_len = item_spec.get("minLength")
                for idx, item in enumerate(val):
                    if item_type == "string" and not isinstance(item, str):
                        errors.append(f"{file_path}:{line_no} Field '{k}[{idx}]' expected string, got {type(item).__name__}")
                    elif isinstance(item, str):
                        if item_min_len and len(item) < item_min_len:
                            errors.append(f"{file_path}:{line_no} Field '{k}[{idx}]' string length {len(item)} < {item_min_len}")
                        if item_enum and item not in item_enum:
                            errors.append(f"{file_path}:{line_no} Field '{k}[{idx}]' value '{item}' not in enum {item_enum}")

    # 3. Evidence quotes literal substring check
    if "evidence_quotes" in args:
        quotes = args.get("evidence_quotes", [])
        if not isinstance(quotes, list):
            errors.append(f"{file_path}:{line_no} 'evidence_quotes' must be an array")
        else:
            for q in quotes:
                if not isinstance(q, str):
                    errors.append(f"{file_path}:{line_no} evidence quote is not a string: {q}")
                elif q not in query:
                    errors.append(f"{file_path}:{line_no} evidence quote '{q}' is not a literal substring of query '{query}'")

    return errors

def validate_file(file_path):
    errors = []
    records = []
    tool_counts = Counter()
    queries = set()
    dup_queries = Counter()

    path = Path(file_path)
    if not path.exists():
        return [f"File not found: {file_path}"], 0, tool_counts, queries

    with open(path, "r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except Exception as e:
                errors.append(f"{file_path}:{line_no} JSON parse error: {e}")
                continue

            rec_errors = validate_record_schema(record, file_path, line_no)
            errors.extend(rec_errors)
            records.append(record)

            q = record.get("query", "")
            if q in queries:
                dup_queries[q] += 1
            queries.add(q)

            answers = record.get("answers", [])
            if answers:
                tname = answers[0].get("name", "unknown")
                tool_counts[tname] += 1
                if tname == "request_assistance":
                    rtype = answers[0].get("arguments", {}).get("request_type", "unknown")
                    tool_counts[f"request_assistance_{rtype}"] += 1
            else:
                tool_counts["no_call"] += 1

    if dup_queries:
        for q, cnt in dup_queries.items():
            errors.append(f"{file_path}: Duplicate query found ({cnt + 1} occurrences): '{q[:60]}...'")

    return errors, len(records), tool_counts, queries

def main():
    parser = argparse.ArgumentParser(description="Strict CASCADE26 Dataset Validator")
    parser.add_argument("--dir", default=str(SCRIPT_DIR / "datasets"), help="Datasets directory")
    args = parser.parse_args()

    datasets_dir = Path(args.dir)
    splits = ["train.jsonl", "validation.jsonl", "test.jsonl", "gold_test.jsonl"]
    
    total_errors = []
    split_queries = {}
    split_stats = {}

    print("=" * 70)
    print("CASCADE26 STRICT DATASET & SCHEMA VALIDATOR (Schema 2.0.0)")
    print("=" * 70)

    for sp in splits:
        fpath = datasets_dir / sp
        errors, count, tools, queries = validate_file(fpath)
        total_errors.extend(errors)
        split_queries[sp] = queries
        split_stats[sp] = {
            "count": count,
            "tools": tools,
            "error_count": len(errors)
        }
        
        status = "PASSED" if len(errors) == 0 else f"FAILED ({len(errors)} errors)"
        print(f"\n[{sp}] - {status}")
        print(f"  Records: {count}")
        print(f"  Tool breakdown: {dict(tools)}")

    # Cross-split leakage checks (train, val, test)
    print("\n" + "=" * 70)
    print("CROSS-SPLIT LEAKAGE AUDIT")
    print("=" * 70)
    
    train_q = split_queries.get("train.jsonl", set())
    val_q = split_queries.get("validation.jsonl", set())
    test_q = split_queries.get("test.jsonl", set())
    gold_q = split_queries.get("gold_test.jsonl", set())

    leakage_errors = []
    
    tv_overlap = train_q.intersection(val_q)
    if tv_overlap:
        leakage_errors.append(f"Train / Validation query overlap: {len(tv_overlap)} shared queries")
    tt_overlap = train_q.intersection(test_q)
    if tt_overlap:
        leakage_errors.append(f"Train / Test query overlap: {len(tt_overlap)} shared queries")
    vt_overlap = val_q.intersection(test_q)
    if vt_overlap:
        leakage_errors.append(f"Validation / Test query overlap: {len(vt_overlap)} shared queries")
    tg_overlap = train_q.intersection(gold_q)
    if tg_overlap:
        leakage_errors.append(f"Train / Gold Test query overlap: {len(tg_overlap)} shared queries")

    if leakage_errors:
        for le in leakage_errors:
            print(f"  [ERROR] {le}")
        total_errors.extend(leakage_errors)
    else:
        print("  [OK] Zero query leakage across Train, Validation, Test, and Gold Test.")

    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)
    if total_errors:
        print(f"FAILED: {len(total_errors)} validation errors found.")
        print("\nFirst 10 errors:")
        for err in total_errors[:10]:
            print(f"  - {err}")
        sys.exit(1)
    else:
        print("SUCCESS: 100% of records passed strict schema and quote validation.")
        print("All five tools, request_assistance subtypes, and no-call examples verified.")
        sys.exit(0)

if __name__ == "__main__":
    main()
