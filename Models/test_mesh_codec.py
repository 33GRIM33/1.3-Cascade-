"""Unit tests for binary GNET mesh codec interoperability with Android."""
import unittest
import uuid
from mesh_codec import MessageCodec, MeshEvent, MeshEventType, valid_node, BROADCAST


class MeshCodecTests(unittest.TestCase):
    def setUp(self):
        self.codec = MessageCodec()

    def test_valid_node(self):
        self.assertTrue(valid_node("device-node-01"))
        self.assertTrue(valid_node("PHONE-A-12345"))
        self.assertFalse(valid_node("short"))  # < 8 chars
        self.assertFalse(valid_node("invalid node!@#"))  # special chars
        self.assertFalse(valid_node("a" * 65))  # > 64 chars

    def test_encode_decode_distress_event(self):
        payload = b'{"needs":["rescue"],"hazards":["flood"],"count":2}'
        event = MeshEvent.create(
            node_id="phone-alpha-001",
            recipient_id=BROADCAST,
            event_type=MeshEventType.DISTRESS,
            payload=payload,
            now_ms=1700000000000,
            ttl_ms=300000,
            hops=8,
            priority=10,
        )

        frame = self.codec.encode(event)
        self.assertEqual(frame[:4], b"GNET")
        self.assertLessEqual(len(frame), 2048)

        decoded = self.codec.decode(frame)
        self.assertEqual(decoded.id, event.id)
        self.assertEqual(decoded.origin_id, "phone-alpha-001")
        self.assertEqual(decoded.sender_id, "phone-alpha-001")
        self.assertEqual(decoded.recipient_id, BROADCAST)
        self.assertEqual(decoded.type, MeshEventType.DISTRESS)
        self.assertEqual(decoded.created_at_ms, 1700000000000)
        self.assertEqual(decoded.expires_at_ms, 1700000300000)
        self.assertEqual(decoded.hop_limit, 8)
        self.assertEqual(decoded.priority, 10)
        self.assertEqual(decoded.payload, payload)

    def test_forwarding_decrements_hop_limit(self):
        event = MeshEvent.create("node-alpha-1", BROADCAST, MeshEventType.OBSERVATION, b"status", hops=5)
        forwarded = event.forwarded_by("node-relay-2")
        self.assertEqual(forwarded.origin_id, "node-alpha-1")
        self.assertEqual(forwarded.sender_id, "node-relay-2")
        self.assertEqual(forwarded.hop_limit, 4)

    def test_rejects_oversized_frame(self):
        big_payload = b"X" * 2048
        event = MeshEvent.create("phone-node-001", BROADCAST, MeshEventType.TEXT, big_payload)
        with self.assertRaises(ValueError):
            self.codec.encode(event)

    def test_rejects_empty_ack(self):
        event = MeshEvent.create("phone-node-001", "phone-dest-002", MeshEventType.ACK, b"")
        with self.assertRaises(ValueError):
            self.codec.encode(event)


if __name__ == "__main__":
    unittest.main()
