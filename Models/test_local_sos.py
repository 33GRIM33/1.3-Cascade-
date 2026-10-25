import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from local_sos import (build_envelope, enqueue, mark_acked, parse_sos,
                       pending_payloads, wire_bytes)


class LocalSosTests(unittest.TestCase):
    def test_rising_water_and_boat_are_rescue_not_drinking_water(self):
        report = parse_sos("Water is rising at my house. Two people are trapped and need a boat.")
        self.assertEqual(report["needs"], ["rescue"])
        self.assertEqual(report["hazards"], ["flood"])
        self.assertEqual(report["requested_items"], ["boat"])
        self.assertEqual(report["location_text"], "my house")
        self.assertEqual(report["affected_people_count"], 2)
        self.assertTrue(all(quote in report["raw_text"] for quote in report["evidence_quotes"]))

    def test_missing_details_do_not_block_an_sos(self):
        envelope = build_envelope("Help, I am trapped!", "device-104", now=1000)
        self.assertEqual(envelope["type"], "EMERGENCY_REQUEST")
        self.assertEqual(envelope["priority"], "UNASSESSED")
        self.assertEqual(envelope["payload"]["raw_text"], "Help, I am trapped!")
        self.assertEqual(envelope["payload"]["verification_status"], "unverified")
        self.assertIn("location_text", envelope["payload"]["missing_fields"])
        self.assertEqual(envelope["expires_at"], 4600)

    def test_model_cannot_invent_location_or_resource(self):
        text = "Two people are trapped and need a boat."
        report = parse_sos(text, {"location_text": "Main Street",
                                  "requested_help_text": "helicopter",
                                  "affected_people_count": 99})
        self.assertIsNone(report["location_text"])
        self.assertEqual(report["requested_items"], ["boat"])
        self.assertEqual(report["affected_people_count"], 2)
        self.assertEqual(report["extraction_method"], "rules")

    def test_location_form_has_explicit_provenance(self):
        report = parse_sos("Help, we are trapped.",
                           provided_location="742 Evergreen Terrace")
        self.assertEqual(report["location_text"], "742 Evergreen Terrace")
        self.assertEqual(report["location_source"], "user_form")
        self.assertNotIn("742 Evergreen Terrace", report["evidence_quotes"])

    def test_drinking_water_is_a_need_without_a_flood(self):
        report = parse_sos("We need clean drinking water at Oak Street shelter.")
        self.assertEqual(report["needs"], ["water"])
        self.assertEqual(report["hazards"], [])

    def test_negated_hazard_is_not_reported(self):
        report = parse_sos("No fire at our building. Two people are trapped by floodwater.")
        self.assertEqual(report["hazards"], ["flood"])
        self.assertEqual(report["affected_people_count"], 2)

    def test_mentioned_but_not_requested_boat_is_not_a_requested_item(self):
        report = parse_sos("Our boat is damaged. We are stranded near the pier.")
        self.assertEqual(report["needs"], ["rescue"])
        self.assertEqual(report["requested_items"], [])

    def test_adds_two_explicit_groups_without_counting_road_numbers(self):
        report = parse_sos(
            "Mudflow at Sunnyvale Daycare on Valley Vista Blvd. "
            "15 children and 3 teachers need transport from Route 9.")
        self.assertEqual(report["affected_people_count"], 18)
        self.assertIn("landslide", report["hazards"])

    def test_route_number_is_not_a_people_count(self):
        report = parse_sos("Landslide on Route 9 near mile marker 14. Road is impassable.")
        self.assertIsNone(report["affected_people_count"])
        self.assertIn("landslide", report["hazards"])

    def test_roadside_address_wins_over_wire_description(self):
        report = parse_sos(
            "Tree caught fire on electric wire outside 112 Elm Street along Route 4 "
            "around 3 PM. Sparks showering down on a porch.")
        self.assertEqual(report["location_text"], "112 Elm Street along Route 4")

    def test_street_abbreviation_and_missing_water_are_preserved(self):
        report = parse_sos(
            "8 elderly patients stranded without drinking water at "
            "St. Jude Care Center. We need water and fuel supplies now.")
        self.assertEqual(report["location_text"], "St. Jude Care Center")
        self.assertEqual(report["affected_people_count"], 8)
        self.assertIn("water", report["needs"])
        self.assertIn("supplies", report["needs"])

    def test_outbox_contains_same_compact_json_and_deduplicates(self):
        envelope = build_envelope("Need insulin at Pine Ridge Senior Living.",
                                  "device-104", now=1000)
        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / "outbox.sqlite3"
            enqueue(envelope, database)
            enqueue(envelope, database)
            pending = pending_payloads(database, now=1001)
            self.assertEqual(len(pending), 1)
            self.assertEqual(pending[0][0], envelope["message_id"])
            self.assertTrue(mark_acked(database, envelope["message_id"]))
            self.assertEqual(pending_payloads(database, now=1001), [])
            with sqlite3.connect(database) as connection:
                rows = connection.execute("SELECT payload, state FROM outbox").fetchall()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][1], "acknowledged")
        self.assertEqual(json.loads(rows[0][0]), json.loads(wire_bytes(envelope)))


if __name__ == "__main__":
    unittest.main()
