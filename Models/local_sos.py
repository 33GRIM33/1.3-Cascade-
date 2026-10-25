#!/usr/bin/env python3
"""Offline-first SOS parsing and transport-envelope reference implementation.

The caller's SOS action, not a model classification, authorizes creation of an
unverified report. The raw message is always retained. Optional model values
may fill only fields that can be checked against the original text.
"""

import argparse
import json
import re
import sqlite3
import time
import uuid
from pathlib import Path


SCHEMA_VERSION = "cascade.emergency.v1"
MAX_MESSAGE_CHARS = 1000
MAX_WIRE_BYTES = 8192
NUMBER_WORDS = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
}
PEOPLE = r"(?:people|persons|residents|individuals|children|kids|adults|patients|workers|babies|teachers|fishermen|passengers)"
COUNT_RE = re.compile(
    rf"\b(?P<number>\d{{1,3}}|{'|'.join(NUMBER_WORDS)})\s+"
    rf"(?:elderly\s+|young\s+|injured\s+|disabled\s+)?{PEOPLE}\b", re.I)
DRIVER_PASSENGERS = re.compile(
    rf"\bdriver\s+and\s+(?P<number>\d{{1,3}}|{'|'.join(NUMBER_WORDS)})\s+passengers?\b", re.I)
SINGULAR_PERSON = re.compile(
    r"\b(?:grandma|grandmother|grandfather|grandpa|veteran)\b"
    r"|\b(?:1|one|a|an)\s+(?:elderly\s+|young\s+|injured\s+|disabled\s+)?(?:man|woman|child|person|patient|resident|worker|passenger|infant|toddler)\b"
    r"|\b(?:man|woman|child)\s+(?:had|has|is|was)\b",
    re.I
)

NEED_PATTERNS = {
    "rescue": [r"\btrapped\b", r"\bstranded\b", r"\bstuck\b",
               r"\bpinned\b", r"\bclinging\s+to\b", r"\bcrying\s+for\s+help\b",
               r"\brescue\b", r"\bneed\s+(?:a\s+|an\s+)?boat\b",
               r"\bon\s+(?:the\s+)?roof\b", r"\bcalling\s+out\s+from\s+inside\b",
               r"\bunder\s+steel\s+girders\b", r"\bscreams\s+heard\b"],
    "medical_help": [r"\bmedical\s+(?:help|assistance)\b",
                     r"\bcannot\s+breathe\b", r"\bcan't\s+breathe\b",
                     r"\bsevere\s+bleeding\b", r"\bbleeding\s+heavily\b",
                     r"\bcompound\s+fracture\b", r"\bcannot\s+walk\b"],
    "medicine": [r"\binsulin\b", r"\bmedication\b", r"\bmedicine\b"],
    "evacuation": [r"\bevacuat(?:e|ion)\b"],
    "food": [r"\bneed\s+food\b", r"\bno\s+food\b", r"\bbaby\s+formula\b",
             r"\bneed\s+formula\b"],
    "water": [r"\b(?:clean|drinking)\s+water\b", r"\bneed\s+water\s+to\s+drink\b"],
    "shelter": [r"\bneed\s+(?:a\s+)?shelter\b"],
    "transport": [r"\bneed\s+(?:immediate\s+)?transport\b",
                  r"\bneed\s+(?:a\s+)?(?:van|vehicle)\b",
                  r"\btake\b.{1,30}\bto\s+(?:the\s+)?(?:clinic|hospital|doctor)\b",
                  r"\bwheelchair[- ]van\b"],
    "supplies": [r"\bneed\s+(?:an?\s+)?(?:emergency\s+)?battery\b",
                  r"\bneed\s+(?:fuel\s+)?supplies\b", r"\bfuel\s+supplies\b"],
    "communication": [r"\bneed\s+(?:a\s+)?(?:radio|phone|communication\s+link)\b",
                      r"\bno\s+communication\b", r"\b(?:working\s+)?radio\b"],
}
HAZARD_PATTERNS = {
    "flood": [r"\bflood(?:ed|ing|waters?)?\b", r"\bwater\s+(?:is\s+)?rising\b",
              r"\brising\s+water\b", r"\bwater\s+coming\s+in\b",
              r"\bstorm\s+surge\b", r"\btide\s+has\s+covered\b",
              r"\bfront\s+room\s+is\s+filling\b"],
    "fire": [r"\bfire\b", r"\bflames?\b", r"\bburning\b", r"\bsmoke\b"],
    "structural_collapse": [r"\bstructural\s+collapse\b",
                            r"\b(?:building|roof|bridge)\s+collaps(?:e|ed|ing)\b",
                            r"\broof\s+gave\s+way\b", r"\bunder\s+rubble\b",
                            r"\bbridge\s+.*\bbuckled\b"],
    "landslide": [r"\b(?:landslide|mudslide|mudflow)\b"],
    "severe_weather": [r"\b(?:tornado|hurricane|storm\s+surge)\b"],
    "chemical_hazard": [r"\bchemical\s+(?:spill|leak)\b", r"\btoxic\s+(?:gas|fumes)\b",
                        r"\bgas\s+main\s+ruptured\b"],
    "power_outage": [r"\bpower\s+(?:is\s+)?out\b", r"\bpower\s+outage\b",
                     r"\bgrid\s+down\b", r"\blost\s+power\b",
                     r"\belectric\s+wire\b", r"\btransformer\s+explosion\b",
                     r"\bdowned\s+(?:live\s+)?(?:high-voltage\s+)?wires?\b"],
    "unsafe_road": [r"\broad\s+(?:is\s+)?(?:blocked|closed|impassable)\b",
                    r"\broad\s+is\s+completely\s+wiped\s+out\b",
                    r"\b(?:bridge|road)\s+.*\bimpassable\b", r"\bsinkhole\b"],
    "violence": [r"\barmed\s+violence\b", r"\bgunshots?\b"],
}
RESOURCE_PATTERNS = [
    r"\binsulin\b", r"\boxygen\s+tank\b", r"\b(?:inflatable\s+)?boat\b",
    r"\bwheelchair[- ]accessible\s+van\b", r"\bwheelchair\s+van\b",
    r"\bbaby\s+formula\b", r"\bclean\s+drinking\s+water\b",
    r"\bblankets?\b", r"\bsatellite\s+phone\b", r"\bfuel\b",
    r"\bemergency\s+battery\b",
]
REQUEST_CUE = re.compile(
    r"\b(?:need|send|request|require|bring|out\s+of)\b[^.!?;]{0,70}$", re.I)
URGENCY_PATTERNS = [
    r"\bwater\s+(?:is\s+)?rising(?:\s+(?:rapidly|fast))?\b",
    r"\b(?:cannot|can't)\s+breathe\b", r"\btrapped\b",
    r"\bflames?\s+approaching\b", r"\bimmediately\b",
]
LOCATION_START = re.compile(
    r"\b(?:at|near|inside|outside|behind|next\s+to|on|towards|middle\s+of)\s+", re.I)
LOCATION_END = re.compile(r"[.!?;\n]")
IN_LOCATION = re.compile(r"\bin\s+((?:my|our)\s+(?:house|home|apartment|building))\b", re.I)
NAMED_IN_LOCATION = re.compile(r"\bin\s+([A-Z][^.!?;\n]{2,120})")


def _first_match(text, patterns, skip_negated=True):
    found = []
    for pattern in patterns:
        for match in re.finditer(pattern, text, re.I):
            prefix = text[max(0, match.start() - 24):match.start()]
            if skip_negated and re.search(
                    r"\b(?:no|not|without|never)\s+(?:\w+\s+){0,2}$", prefix, re.I):
                continue
            found.append(match)
            break
    return min(found, key=lambda match: match.start()) if found else None


def _find_categories(text, mapping):
    categories, evidence = [], []
    for category, patterns in mapping.items():
        # Absence of food, water, medicine, supplies, or communication is
        # positive evidence of need. Negation still suppresses hazard labels
        # and rescue cues such as "not trapped".
        skip_negated = mapping is HAZARD_PATTERNS or category == "rescue"
        match = _first_match(text, patterns, skip_negated=skip_negated)
        if match:
            categories.append(category)
            evidence.append(match.group(0))
    return categories, evidence


def _literal_span(text, value):
    """Return original-case substring, or None when a model invented a value."""
    if not isinstance(value, str) or not value.strip():
        return None
    match = re.search(re.escape(value.strip()), text, re.I)
    return match.group(0) if match else None


def _number(value):
    value = value.lower()
    return int(value) if value.isdigit() else NUMBER_WORDS[value]


def _people_count(text):
    driver = DRIVER_PASSENGERS.search(text)
    if driver:
        return 1 + _number(driver.group("number")), driver.group(0)
    matches = list(COUNT_RE.finditer(text))
    if not matches:
        singular = SINGULAR_PERSON.search(text)
        if singular:
            return 1, singular.group(0)
        return None, None
    first = matches[0]
    count = _number(first.group("number"))
    evidence_end = first.end()
    for match in matches[1:]:
        between = text[evidence_end:match.start()]
        if not re.fullmatch(r"\s+and\s+", between, re.I):
            break
        count += _number(match.group("number"))
        evidence_end = match.end()
    return count, text[first.start():evidence_end]


def _location(text):
    # Mask periods in common address abbreviations without changing offsets.
    # The source substring still comes from the original message.
    masked = re.sub(r"\b(?:St|Dr|Ave|Rd)\.",
                    lambda match: match.group(0)[:-1] + "·", text, flags=re.I)
    matches = list(LOCATION_START.finditer(masked))
    if matches:
        candidates = []
        for match in matches:
            end = LOCATION_END.search(masked, match.end())
            value = text[match.end():end.start() if end else len(text)][:160].strip(" ,")
            value = re.split(
                r"\s+(?:and\s+(?:we|need)|for\s+\d+\s+people|around\s+\d+|has\s+|is\s+|was\s+)",
                value, maxsplit=1, flags=re.I)[0].strip(" ,")
            if value:
                starts_with_address = bool(re.match(r"\d+\s+\w+", value))
                place_word = bool(re.search(
                    r"\b(?:street|road|route|terrace|school|center|bridge|harbor|park|way|pier)\b",
                    value, re.I))
                candidates.append((3 * starts_with_address + place_word,
                                   len(value), value))
        if candidates:
            return max(candidates)[2]
    match = IN_LOCATION.search(text)
    if match:
        return match.group(1)
    match = NAMED_IN_LOCATION.search(text)
    return match.group(1).strip(" ,") if match else None


def parse_sos(text, model_candidate=None, provided_location=None):
    """Extract supported facts; never infer categories solely from model text."""
    if not isinstance(text, str) or not text.strip():
        raise ValueError("SOS message must contain text")
    text = text.strip()
    if len(text) > MAX_MESSAGE_CHARS:
        raise ValueError(f"SOS message exceeds {MAX_MESSAGE_CHARS} characters")

    needs, need_evidence = _find_categories(text, NEED_PATTERNS)
    hazards, hazard_evidence = _find_categories(text, HAZARD_PATTERNS)
    count, count_quote = _people_count(text)
    location = _location(text)
    location_source = "message" if location else None
    if provided_location:
        if not isinstance(provided_location, str) or len(provided_location.strip()) > 160:
            raise ValueError("provided_location must be at most 160 characters")
        location = provided_location.strip()
        location_source = "user_form"
    urgency_match = _first_match(text, URGENCY_PATTERNS)
    urgency = urgency_match.group(0) if urgency_match else None
    items = []
    for pattern in RESOURCE_PATTERNS:
        for match in re.finditer(pattern, text, re.I):
            if (REQUEST_CUE.search(text[max(0, match.start() - 80):match.start()])
                    and match.group(0).lower() not in {item.lower() for item in items}):
                items.append(match.group(0))
                break

    model_used = False
    candidate = model_candidate if isinstance(model_candidate, dict) else {}
    if location is None:
        candidate_location = _literal_span(text, candidate.get("location_text"))
        source_match = (re.search(re.escape(candidate_location), text, re.I)
                        if candidate_location else None)
        prefix = text[max(0, source_match.start() - 12):source_match.start()] if source_match else ""
        if candidate_location and re.search(r"\b(?:at|near|inside|in)\s+$", prefix, re.I):
            location = candidate_location
            location_source = "model_validated"
            model_used = True
    if not items:
        candidate_item = _literal_span(text, candidate.get("requested_help_text"))
        item_match = (re.search(re.escape(candidate_item), text, re.I)
                      if candidate_item else None)
        prefix = text[max(0, item_match.start() - 80):item_match.start()] if item_match else ""
        if candidate_item and REQUEST_CUE.search(prefix):
            items.append(candidate_item)
            model_used = True
    # Categories and counts stay under deterministic validation. A model can
    # propose arbitrary values, but it cannot turn rising floodwater into a
    # request for drinking water or invent people who were not counted.

    evidence = []
    for quote in [*need_evidence, *hazard_evidence, location,
                  count_quote,
                  urgency, *items]:
        if quote and quote in text and quote not in evidence:
            evidence.append(quote)
    missing = [field for field, value in
               (("location_text", location), ("affected_people_count", count))
               if value is None]
    return {
        "raw_text": text,
        "needs": needs,
        "requested_items": items,
        "hazards": hazards,
        "location_text": location,
        "location_source": location_source,
        "affected_people_count": count,
        "urgency_cue": urgency,
        "evidence_quotes": evidence,
        "missing_fields": missing,
        "extraction_method": "rules+validated_model" if model_used else "rules",
        "verification_status": "unverified",
    }


def build_envelope(text, origin_id, model_candidate=None, provided_location=None, now=None):
    if not isinstance(origin_id, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,80}", origin_id):
        raise ValueError("origin_id must be a 1-80 character device identifier")
    created = int(time.time() if now is None else now)
    envelope = {
        "schema_version": SCHEMA_VERSION,
        "message_id": str(uuid.uuid4()),
        "type": "EMERGENCY_REQUEST",
        "created_at": created,
        "expires_at": created + 3600,
        "origin_id": origin_id,
        "hop_limit": 8,
        "priority": "UNASSESSED",
        "payload": parse_sos(text, model_candidate=model_candidate,
                             provided_location=provided_location),
    }
    wire_bytes(envelope)
    return envelope


def wire_bytes(envelope):
    if envelope.get("schema_version") != SCHEMA_VERSION or envelope.get("type") != "EMERGENCY_REQUEST":
        raise ValueError("Unsupported emergency envelope")
    payload = envelope.get("payload", {})
    if payload.get("verification_status") != "unverified" or not payload.get("raw_text"):
        raise ValueError("Emergency envelope lacks unverified source text")
    encoded = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > MAX_WIRE_BYTES:
        raise ValueError(f"Emergency envelope exceeds {MAX_WIRE_BYTES} bytes")
    return encoded


def enqueue(envelope, database):
    """Persist before any attempted radio transfer; duplicate IDs are ignored."""
    database = Path(database)
    database.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database) as connection:
        connection.execute("""
            CREATE TABLE IF NOT EXISTS outbox (
                message_id TEXT PRIMARY KEY,
                payload BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0
            )
        """)
        connection.execute(
            "INSERT OR IGNORE INTO outbox (message_id, payload, created_at, expires_at) "
            "VALUES (?, ?, ?, ?)",
            (envelope["message_id"], wire_bytes(envelope),
             envelope["created_at"], envelope["expires_at"]),
        )
    return database


def pending_payloads(database, now=None, limit=100):
    """Return unexpired bytes for a transport adapter to send to a peer."""
    cutoff = int(time.time() if now is None else now)
    with sqlite3.connect(database) as connection:
        return connection.execute(
            "SELECT message_id, payload FROM outbox "
            "WHERE state = 'pending' AND expires_at > ? "
            "ORDER BY created_at, message_id LIMIT ?", (cutoff, limit),
        ).fetchall()


def mark_acked(database, message_id):
    """Call only after a peer's application-level acknowledgement arrives."""
    with sqlite3.connect(database) as connection:
        result = connection.execute(
            "UPDATE outbox SET state = 'acknowledged' "
            "WHERE message_id = ? AND state = 'pending'", (message_id,),
        )
        return result.rowcount == 1


def main():
    parser = argparse.ArgumentParser(description="Create and queue an offline SOS JSON message")
    parser.add_argument("message", help="Caller-entered SOS text")
    parser.add_argument("--origin-id", required=True, help="App-assigned device identifier")
    parser.add_argument("--outbox", type=Path, default=Path("outbox.sqlite3"))
    parser.add_argument("--candidate-json", type=Path,
                        help="Optional model arguments; only grounded suggestions are used")
    parser.add_argument("--location", help="Optional location supplied in the app's location form")
    args = parser.parse_args()
    candidate = None
    if args.candidate_json:
        candidate = json.loads(args.candidate_json.read_text(encoding="utf-8"))
    envelope = build_envelope(args.message, args.origin_id, model_candidate=candidate,
                              provided_location=args.location)
    enqueue(envelope, args.outbox)
    print(wire_bytes(envelope).decode("utf-8"))


if __name__ == "__main__":
    main()
