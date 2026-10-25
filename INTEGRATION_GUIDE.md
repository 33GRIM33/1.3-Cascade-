# CASCADE + GHOSTNET Android Mesh Integration Guide

This guide describes how to connect the **CASCADE local AI extraction pipeline** (`Models/`) with the **GHOSTNET Android offline mesh network** ([1.3-Cascade-](https://github.com/33GRIM33/1.3-Cascade-)).

---

## 1. System Integration Overview

```
[ User Input Text on Phone ]
         │
         ▼
┌────────────────────────────────────────────────────────┐
│  CASCADE On-Device AI (17.2 MB INT8 ONNX Runtime)      │
│  1. Intent Classifier: Emergency vs Info vs Volunteer  │
│  2. Label Tagging: Needs (rescue, med) & Hazards       │
│  3. Slot Extraction: Location, People Count            │
│  4. Envelope Builder: Compact JSON Schema              │
└────────────────────────────────────────────────────────┘
         │
         ▼ Output: Structured JSON Payload (< 1400 bytes)
┌────────────────────────────────────────────────────────┐
│  GHOSTNET Mesh Module (MessageCodec.java / GNET Frame) │
│  • Frame Magic: 0x474E4554 ('GNET')                    │
│  • Event Type: DISTRESS (1) or OBSERVATION (2)         │
│  • Total Wire Frame: <= 2048 bytes                     │
│  • Hop Limit: 8, Priority: 10                          │
└────────────────────────────────────────────────────────┘
         │
         ▼
[ Google Nearby Connections P2P Transport (Wi-Fi Direct / BLE) ]
         │
         ▼
[ Relayed to Nearby Responders & Field Teams Without Internet ]
```

---

## 2. Wire Protocol & Event Type Mapping

The Python pipeline and the Java Android library now share the exact same binary wire protocol:

| CASCADE Python Intent | GHOSTNET `MeshEvent.Type` | Wire Priority | Description |
|---|---|---|---|
| `submit_emergency_report` | `DISTRESS (1)` | `10` (Highest) | Life-safety request with extracted needs, hazards, location, and casualty count |
| `submit_field_update` | `OBSERVATION (2)` | `5` | Infrastructure status, road blockage, utility outage report |
| `register_volunteer_offer` | `OBSERVATION (2)` | `4` | Offered skills, equipment, and availability |
| `request_assistance` | `TEXT (0)` | `3` | Informational query for safe route or shelter |
| `request_report_closure` | `OBSERVATION (2)` | `6` | Ticket resolution or false alarm cancellation |

### Wire Constraints:
* **Max Frame Size:** `2048 bytes` (enforced by `MessageCodec.MAX_BYTES`).
* **Node ID Format:** 8–64 alphanumeric and hyphen characters (`[a-zA-Z0-9-]+`).
* **Event ID:** Canonical UUID v4 string.
* **Payload Encoding:** UTF-8 compact JSON (`ensure_ascii=False, separators=(',', ':')`).

---

## 3. Python Interoperability (`Models/mesh_codec.py`)

You can encode and decode GNET binary frames directly in Python to verify compatibility with the Android app:

```python
from cascade_pipeline import CascadePipeline

pipeline = CascadePipeline()
result = pipeline.process("Water rising on 742 Evergreen Terrace, 4 people trapped on roof!")

# Generate valid 2048-byte binary GNET frame ready for mesh broadcast:
gnet_binary = pipeline.to_gnet_frame(result, node_id="PHONE-NODE-001", recipient_id="*")
print(f"Generated {len(gnet_binary)} bytes binary frame starting with: {gnet_binary[:4]}")
```

---

## 4. Android App Integration Steps

### Step A: Export Model Assets
Run the asset exporter from `Models/`:
```bash
python3 export_android_assets.py --output-dir ../android_assets
```
Copy `android_assets/*` into your Android project at:
`app/src/main/assets/cascade/`

### Step B: Add ONNX Runtime Mobile to Android `build.gradle`
In `app/build.gradle` (or `mesh/build.gradle`):
```groovy
dependencies {
    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.19.0'
}
```

### Step C: Wire into `DebugActivity.java` or UI Input Handler
Instead of sending raw unparsed text:
```java
// 1. Run local extraction
String rawText = message.getText().toString();
String jsonPayload = CascadeExtractor.process(rawText);

// 2. Publish structured DISTRESS event over mesh
byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
String id = module.publish("*", MeshEvent.Type.DISTRESS, bytes, 300000, 8, 10);
```

### Step D: Receiving Phone Display
When a peer phone receives a `DISTRESS` event:
Parse `event.payload` as JSON to immediately display extracted fields in the UI:
* Location badge: `"742 Evergreen Terrace"`
* Needs chips: `[Rescue]`
* Hazard badge: `[Flood]`
* People count: `4`
* Verification status: `Unverified (Raw text preserved)`
