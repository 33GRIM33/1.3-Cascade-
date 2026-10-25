import tempfile
import unittest
from pathlib import Path

from tiny_encoder import TinyExtractor, parts
from prepare_tiny_encoder import atomic_json


class TinyEncoderTests(unittest.TestCase):
    def test_clause_splitting_preserves_address_abbreviation_and_offsets(self):
        self.assertEqual(parts("We are at St. Jude Care Center; send help. No fire."),
                         ["We are at St. Jude Care Center", "send help", "No fire"])

    def test_but_separates_opposite_evidence(self):
        self.assertEqual(parts("There is no fire, but water is rising."),
                         ["There is no fire,", "water is rising"])

    def test_enrichment_keeps_uncertainty_and_source_quote(self):
        extractor = object.__new__(TinyExtractor)
        extractor.predict = lambda _text: [
            {"label": "need:rescue", "score": 0.8, "source_quote": "two people are trapped"},
            {"label": "hazard:flood", "score": 0.9, "source_quote": "water is rising"},
        ]
        report = {"raw_text": "Two people are trapped and water is rising.",
                  "needs": [], "hazards": [], "verification_status": "unverified"}
        result = extractor.enrich(report, "hybrid")
        self.assertEqual(result["needs"], ["rescue"])
        self.assertEqual(result["hazards"], ["flood"])
        self.assertEqual(result["verification_status"], "unverified")
        self.assertEqual(result["model_evidence"][0]["source_quote"], "two people are trapped")

    def test_encoder_mode_can_be_compared_without_rule_labels(self):
        extractor = object.__new__(TinyExtractor)
        extractor.predict = lambda _text: [{"label": "need:water", "score": 0.8,
                                            "source_quote": "we need clean water"}]
        report = {"raw_text": "we need clean water", "needs": ["food"], "hazards": []}
        result = extractor.enrich(report, "encoder")
        self.assertEqual(result["needs"], ["water"])
        self.assertEqual(result["hazards"], [])

    def test_hybrid_honors_clear_negative_request_and_resolved_hazard(self):
        extractor = object.__new__(TinyExtractor)
        extractor.predict = lambda _text: [
            {"label": "need:water", "score": 0.8, "source_quote": "we do not need water"},
            {"label": "hazard:fire", "score": 0.8, "source_quote": "The flames have been put out"},
            {"label": "need:food", "score": 0.8, "source_quote": "we need food"},
        ]
        report = {"raw_text": "We do not need water. Flames put out. We need food.",
                  "needs": [], "hazards": []}
        result = extractor.enrich(report, "hybrid")
        self.assertEqual(result["needs"], ["food"])
        self.assertEqual(result["hazards"], [])

    def test_hybrid_can_remove_rule_labels_explicitly_denied_by_the_message(self):
        extractor = object.__new__(TinyExtractor)
        extractor.predict = lambda _text: [
            {"label": "need:rescue", "score": 0.8, "source_quote": "Nobody is trapped"},
            {"label": "hazard:fire", "score": 0.8, "source_quote": "There is no fire"},
        ]
        report = {"raw_text": "Nobody is trapped. There is no fire.",
                  "needs": ["rescue"], "hazards": ["fire"]}
        result = extractor.enrich(report, "hybrid")
        self.assertEqual(result["needs"], [])
        self.assertEqual(result["hazards"], [])

    def test_json_writer_replaces_atomically_valid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "head.json"
            atomic_json(path, {"value": 2})
            self.assertEqual(path.read_text(encoding="utf-8"), '{"value":2}')
            self.assertFalse(path.with_name("head.json.tmp").exists())


if __name__ == "__main__":
    unittest.main()
