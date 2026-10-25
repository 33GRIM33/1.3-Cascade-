"""Binary codec for GNET mesh frames, interoperable with Android MessageCodec.java.

Matches org.ghostnet.mesh.core.MessageCodec:
- MAGIC = 0x474e4554 ('GNET')
- Version = 1
- Max frame size = 2048 bytes
- Event types: TEXT=0, DISTRESS=1, OBSERVATION=2, DISPATCH=3, ACK=4
"""
import io
import re
import struct
import time
import uuid
from enum import IntEnum

MAGIC = 0x474E4554  # 'GNET'
PROTOCOL_VERSION = 1
MAX_BYTES = 2048
BROADCAST = "*"


class MeshEventType(IntEnum):
    TEXT = 0
    DISTRESS = 1
    OBSERVATION = 2
    DISPATCH = 3
    ACK = 4


class MeshEvent:
    """Logical event mirroring org.ghostnet.mesh.core.MeshEvent."""

    def __init__(self, version, event_id, origin_id, sender_id, recipient_id,
                 event_type, created_at_ms, expires_at_ms, hop_limit, priority, payload):
        self.version = version
        self.id = str(event_id)
        self.origin_id = str(origin_id)
        self.sender_id = str(sender_id)
        self.recipient_id = str(recipient_id)
        self.type = MeshEventType(event_type) if isinstance(event_type, int) else event_type
        self.created_at_ms = int(created_at_ms)
        self.expires_at_ms = int(expires_at_ms)
        self.hop_limit = int(hop_limit)
        self.priority = int(priority)
        self.payload = bytes(payload) if payload is not None else b""

    @classmethod
    def create(cls, node_id, recipient_id, event_type, payload,
               now_ms=None, ttl_ms=300000, hops=8, priority=10):
        if now_ms is None:
            now_ms = int(time.time() * 1000)
        return cls(
            version=PROTOCOL_VERSION,
            event_id=str(uuid.uuid4()),
            origin_id=node_id,
            sender_id=node_id,
            recipient_id=recipient_id,
            event_type=event_type,
            created_at_ms=now_ms,
            expires_at_ms=now_ms + ttl_ms,
            hop_limit=hops,
            priority=priority,
            payload=payload,
        )

    def forwarded_by(self, node_id):
        return MeshEvent(
            self.version, self.id, self.origin_id, node_id, self.recipient_id,
            self.type, self.created_at_ms, self.expires_at_ms,
            self.hop_limit - 1, self.priority, self.payload
        )


def valid_node(node_id):
    """Validate node ID format: 8–64 alphanumeric or hyphen characters."""
    if not isinstance(node_id, str) or len(node_id) < 8 or len(node_id) > 64:
        return False
    return bool(re.fullmatch(r"[a-zA-Z0-9-]+", node_id))


def _write_string(stream, s):
    raw = s.encode("utf-8")
    if len(raw) > 255:
        raise ValueError(f"String too long: {len(raw)} bytes")
    stream.write(struct.pack(">B", len(raw)))
    stream.write(raw)


def _read_string(stream):
    length_bytes = stream.read(1)
    if not length_bytes:
        raise ValueError("Truncated string length")
    length = struct.unpack(">B", length_bytes)[0]
    data = stream.read(length)
    if len(data) < length:
        raise ValueError("Truncated string data")
    return data.decode("utf-8")


class MessageCodec:
    """Encode and decode GNET binary mesh frames."""

    def __init__(self, max_bytes=MAX_BYTES):
        self.max_bytes = max_bytes

    def encode(self, event):
        self.validate(event)
        buf = io.BytesIO()
        buf.write(struct.pack(">IB", MAGIC, event.version))
        _write_string(buf, event.id)
        _write_string(buf, event.origin_id)
        _write_string(buf, event.sender_id)
        _write_string(buf, event.recipient_id)
        buf.write(struct.pack(
            ">BqqBBH",
            event.type.value,
            event.created_at_ms,
            event.expires_at_ms,
            event.hop_limit,
            event.priority,
            len(event.payload),
        ))
        buf.write(event.payload)
        data = buf.getvalue()
        if len(data) > self.max_bytes:
            raise ValueError(f"Message exceeds {self.max_bytes} bytes (was {len(data)})")
        return data

    def decode(self, data):
        if not data or len(data) > self.max_bytes:
            raise ValueError("Invalid frame size")
        stream = io.BytesIO(data)
        magic, version = struct.unpack(">IB", stream.read(5))
        if magic != MAGIC:
            raise ValueError("Invalid frame magic (expected GNET)")
        if version != PROTOCOL_VERSION:
            raise ValueError(f"Unsupported protocol version {version}")

        event_id = _read_string(stream)
        origin_id = _read_string(stream)
        sender_id = _read_string(stream)
        recipient_id = _read_string(stream)

        type_val, created_at, expires_at, hops, priority, payload_len = struct.unpack(
            ">BqqBBH", stream.read(21)
        )
        if type_val >= len(MeshEventType):
            raise ValueError(f"Invalid event type {type_val}")

        payload = stream.read(payload_len)
        if len(payload) < payload_len:
            raise ValueError("Truncated payload")
        if stream.read(1):
            raise ValueError("Trailing bytes in frame")

        event = MeshEvent(
            version, event_id, origin_id, sender_id, recipient_id,
            MeshEventType(type_val), created_at, expires_at, hops, priority, payload
        )
        self.validate(event)
        return event

    def validate(self, event):
        if not event or event.version != 1 or event.type is None or event.payload is None:
            raise ValueError("Invalid event")
        try:
            val_uuid = uuid.UUID(event.id)
            if str(val_uuid).lower() != event.id.lower():
                raise ValueError("Non-canonical UUID")
        except Exception as exc:
            raise ValueError(f"Invalid event ID: {exc}")

        if not valid_node(event.origin_id) or not valid_node(event.sender_id):
            raise ValueError(f"Invalid origin or sender node ID: {event.origin_id}")
        if event.recipient_id != BROADCAST and not valid_node(event.recipient_id):
            raise ValueError(f"Invalid recipient node ID: {event.recipient_id}")

        if (event.created_at_ms <= 0 or event.expires_at_ms <= event.created_at_ms or
                event.hop_limit < 0 or event.hop_limit > 32 or
                event.priority < 0 or event.priority > 10 or
                len(event.payload) > self.max_bytes):
            raise ValueError("Invalid field bounds")
        if event.type == MeshEventType.ACK and len(event.payload) == 0:
            raise ValueError("Invalid empty ACK payload")
