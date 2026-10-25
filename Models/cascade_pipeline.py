"""Unified CASCADE extraction pipeline: intent -> labels -> slots -> envelope.

This module chains the three extraction stages into one pipeline that can
process any user message and produce a validated output.

Usage:
    from cascade_pipeline import CascadePipeline
    pipeline = CascadePipeline()
    result = pipeline.process("Water is rising, we need help!")
"""
import json
import time
from pathlib import Path

from local_sos import parse_sos, build_envelope
from tiny_encoder import Encoder, TinyExtractor, DEFAULT_MODEL
from intent_classifier import IntentClassifier, INTENT_LABELS

HERE = Path(__file__).resolve().parent


class CascadePipeline:
    """Three-stage extraction pipeline for CASCADE emergency messages.

    Stage 1: Intent classification (encoder -> which tool?)
    Stage 2: Label tagging (encoder -> need/hazard labels for emergencies)
    Stage 3: Slot extraction (rules + validated model spans)
    Stage 4: Envelope building (deterministic validation)
    """

    def __init__(self, model_dir=DEFAULT_MODEL, threads=2):
        self.model_dir = Path(model_dir)
        self.encoder = Encoder(model_dir, threads)
        self.intent_classifier = IntentClassifier(
            self.encoder, self.model_dir / "intent_head.json"
        )
        self.label_extractor = TinyExtractor(model_dir, threads, encoder=self.encoder)

    def process(self, text, origin_id="local-device", provided_location=None,
                mode="hybrid"):
        """Process a message through the full pipeline.

        Returns a dict with:
            intent: predicted tool name
            intent_confidence: softmax probability
            intent_scores: per-class scores
            envelope: full SOS envelope (for emergencies) or None
            extraction: parsed fields
            timing_ms: inference time in milliseconds
            pipeline_version: version string
        """
        started = time.perf_counter()

        # Stage 1: Intent classification
        intent, confidence, scores = self.intent_classifier.classify(text)

        result = {
            "intent": intent,
            "intent_confidence": round(confidence, 4),
            "intent_scores": scores,
            "envelope": None,
            "extraction": None,
            "timing_ms": None,
            "pipeline_version": "cascade.pipeline.v1",
        }

        # Stage 2+3: Only do full extraction for emergency reports
        if intent == "submit_emergency_report":
            # Stage 2: Label tagging (encoder) + Stage 3: Slot extraction (rules)
            baseline = parse_sos(text, provided_location=provided_location)
            proposals = self.label_extractor.predict(text)
            enriched = self.label_extractor.enrich(baseline, mode, proposals)

            result["extraction"] = enriched

            # Stage 4: Build validated envelope
            try:
                envelope = build_envelope(
                    text, origin_id, provided_location=provided_location
                )
                # Merge enriched labels into the envelope payload
                envelope["payload"]["needs"] = enriched["needs"]
                envelope["payload"]["hazards"] = enriched["hazards"]
                if enriched.get("model_evidence"):
                    envelope["payload"]["model_evidence"] = enriched["model_evidence"]
                envelope["payload"]["extraction_method"] = enriched["extraction_method"]
                result["envelope"] = envelope
            except (ValueError, KeyError) as exc:
                result["envelope_error"] = str(exc)
        else:
            # For non-emergency intents, provide basic extraction info
            result["extraction"] = {
                "intent": intent,
                "raw_text": text,
                "verification_status": "unverified",
                "extraction_method": "intent_only",
            }

        elapsed = (time.perf_counter() - started) * 1000
        result["timing_ms"] = round(elapsed, 2)
        return result

    def classify_only(self, text):
        """Quick intent classification without full extraction."""
        return self.intent_classifier.classify(text)

    def extract_emergency(self, text, mode="hybrid"):
        """Full emergency extraction without intent classification step."""
        baseline = parse_sos(text)
        proposals = self.label_extractor.predict(text)
        return self.label_extractor.enrich(baseline, mode, proposals)

    def to_mesh_event(self, result, node_id="phone-node-001", recipient_id="*",
                      ttl_ms=300000, hop_limit=8, priority=10):
        """Convert a pipeline process() result into a MeshEvent matching 1.3-Cascade-."""
        from mesh_codec import MeshEvent, MeshEventType

        intent = result.get("intent", "no_call")
        if intent == "submit_emergency_report":
            event_type = MeshEventType.DISTRESS
            payload_data = result.get("envelope") or result.get("extraction")
        elif intent in ("submit_field_update", "register_volunteer_offer"):
            event_type = MeshEventType.OBSERVATION
            payload_data = result.get("extraction")
        else:
            event_type = MeshEventType.TEXT
            payload_data = result.get("extraction") or {"raw_text": result.get("raw_text", "")}

        payload_bytes = json.dumps(payload_data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return MeshEvent.create(
            node_id=node_id,
            recipient_id=recipient_id,
            event_type=event_type,
            payload=payload_bytes,
            ttl_ms=ttl_ms,
            hops=hop_limit,
            priority=priority,
        )

    def to_gnet_frame(self, result, node_id="phone-node-001", recipient_id="*",
                      ttl_ms=300000, hop_limit=8, priority=10):
        """Serialize pipeline result directly into a valid GNET binary frame for Android mesh."""
        from mesh_codec import MessageCodec
        event = self.to_mesh_event(result, node_id, recipient_id, ttl_ms, hop_limit, priority)
        return MessageCodec().encode(event)
