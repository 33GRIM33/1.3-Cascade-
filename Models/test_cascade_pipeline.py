"""Unit tests for the unified CASCADE extraction pipeline."""
import json
import unittest
from pathlib import Path

from cascade_pipeline import CascadePipeline
from intent_classifier import INTENT_LABELS
from tiny_encoder import DEFAULT_MODEL


class CascadePipelineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pipeline = CascadePipeline(model_dir=DEFAULT_MODEL)

    def test_pipeline_initialization(self):
        self.assertIsNotNone(self.pipeline.encoder)
        self.assertIsNotNone(self.pipeline.intent_classifier)
        self.assertIsNotNone(self.pipeline.label_extractor)
        # Verify encoder is shared
        self.assertIs(self.pipeline.encoder, self.pipeline.label_extractor.encoder)

    def test_intent_emergency_report(self):
        text = "Water is rising fast at our home, 3 people trapped on the roof!"
        intent, conf, scores = self.pipeline.classify_only(text)
        self.assertEqual(intent, "submit_emergency_report")
        self.assertGreater(conf, 0.2)

    def test_intent_volunteer_offer(self):
        text = "I am a certified paramedic with a first aid kit available all afternoon."
        intent, conf, scores = self.pipeline.classify_only(text)
        self.assertEqual(intent, "register_volunteer_offer")

    def test_intent_field_update(self):
        text = "Debris clearance along Old Mill Road finished by public works crews. Both lanes are now open."
        intent, conf, scores = self.pipeline.classify_only(text)
        self.assertEqual(intent, "submit_field_update")

    def test_intent_request_assistance(self):
        text = "Which bridge is open right now to cross from South Bank to Downtown?"
        intent, conf, scores = self.pipeline.classify_only(text)
        self.assertEqual(intent, "request_assistance")

    def test_intent_request_closure(self):
        text = "Please cancel incident report #E-1082 immediately. Duplicate report called in by mistake."
        intent, conf, scores = self.pipeline.classify_only(text)
        self.assertEqual(intent, "request_report_closure")

    def test_intent_no_call(self):
        text = "Can someone recommend a quiet coffee shop with fast wi-fi near Beacon Hill?"
        intent, conf, scores = self.pipeline.classify_only(text)
        self.assertEqual(intent, "no_call")

    def test_process_emergency_builds_envelope(self):
        text = "Emergency! Water rising rapidly on Greenfield Community Center. 2 people are trapped and need a boat."
        result = self.pipeline.process(text, origin_id="device-test-01")
        self.assertEqual(result["intent"], "submit_emergency_report")
        self.assertIsNotNone(result["envelope"])
        self.assertIsNotNone(result["extraction"])

        envelope = result["envelope"]
        self.assertEqual(envelope["type"], "EMERGENCY_REQUEST")
        self.assertEqual(envelope["origin_id"], "device-test-01")
        self.assertEqual(envelope["payload"]["raw_text"], text)
        self.assertIn("rescue", envelope["payload"]["needs"])
        self.assertIn("flood", envelope["payload"]["hazards"])
        self.assertLess(result["timing_ms"], 50.0)  # Sub-50ms CPU execution

    def test_process_non_emergency_provides_structured_result(self):
        text = "Volunteer with 4x4 high-clearance truck available to transport supplies in Sector 2."
        result = self.pipeline.process(text)
        self.assertEqual(result["intent"], "register_volunteer_offer")
        self.assertIsNone(result["envelope"])
        self.assertIsNotNone(result["extraction"])
        self.assertEqual(result["extraction"]["intent"], "register_volunteer_offer")
        self.assertEqual(result["extraction"]["verification_status"], "unverified")

    def test_polarity_gate_honors_explicit_negation(self):
        text = "The family has blankets and a full tank of fuel; no supplies are required."
        result = self.pipeline.extract_emergency(text)
        self.assertNotIn("supplies", result["needs"])

    def test_to_mesh_event_and_gnet_frame(self):
        text = "Emergency! Water rising rapidly on Greenfield Community Center. 2 people are trapped and need a boat."
        result = self.pipeline.process(text, origin_id="device-test-01")
        frame = self.pipeline.to_gnet_frame(result, node_id="phone-alpha-001")
        self.assertEqual(frame[:4], b"GNET")
        self.assertLessEqual(len(frame), 2048)

        from mesh_codec import MessageCodec, MeshEventType
        decoded = MessageCodec().decode(frame)
        self.assertEqual(decoded.type, MeshEventType.DISTRESS)
        self.assertEqual(decoded.origin_id, "phone-alpha-001")
        self.assertEqual(decoded.recipient_id, "*")
        payload = json.loads(decoded.payload.decode("utf-8"))
        self.assertIn("rescue", payload["payload"]["needs"])


if __name__ == "__main__":
    unittest.main()
