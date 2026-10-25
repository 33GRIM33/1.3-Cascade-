"""Dry-run local text-to-JSON with the compact encoder. Does not transmit."""
import argparse
import json
from pathlib import Path

from local_sos import build_envelope, wire_bytes
from tiny_encoder import TinyExtractor, DEFAULT_MODEL


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--query", required=True)
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--mode", choices=("encoder", "hybrid"), default="hybrid")
    args = parser.parse_args()
    extractor = TinyExtractor(args.model_dir)
    envelope = build_envelope(args.query, "local-test-device")
    original = envelope["payload"]
    envelope["payload"] = extractor.enrich(original, args.mode)
    try:
        wire_bytes(envelope)
    except ValueError:
        # Optional evidence must not make an otherwise valid SOS too large.
        envelope["payload"].pop("model_evidence", None)
        try:
            wire_bytes(envelope)
        except ValueError:
            envelope["payload"] = original
    print(json.dumps(envelope, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
