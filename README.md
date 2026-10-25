# CASCADE26 — Decentralised, AI-Assisted Emergency Response Network

> **A smartphone-first emergency coordination network that keeps working when internet access or central infrastructure is completely unavailable.**

CASCADE combines offline peer-to-peer mesh communications, lightweight on-device AI intelligence, local resource coordination, and resilient store-and-forward routing to help victims, volunteers, and first responders coordinate during disasters.

---

## Architecture Overview

```
                               ┌─────────────────────────────────────────┐
                               │       Freeform Emergency Input          │
                               │ "Trapped on roof, water rising, 3 ppl"  │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    ▼
                       ┌─────────────────────────────────────────────────────────┐
                       │               CASCADE Local AI Pipeline                 │
                       │           (17 MB INT8 MiniLM • <1ms on CPU)             │
                       ├─────────────────────────────────────────────────────────┤
                       │  • Tier 1: Domain Intent Filter (Emergency vs Chat)     │
                       │  • Tier 2: Ridge Head on 384d Embeddings (6-class)      │
                       │  • Tier 3: Clause Polarity & Negation Gating            │
                       │  • Tier 4: Structured Slot & Casualty Extraction        │
                       └────────────────────┬────────────────────────────────────┘
                                            │
                                            ▼
                       ┌─────────────────────────────────────────────────────────┐
                       │          cascade.emergency.v1 JSON Payload              │
                       │    { intent: RESCUE_REQUEST, urgency: critical,         │
                       │      needs: [rescue], hazards: [flood], count: 3 }      │
                       └────────────────────┬────────────────────────────────────┘
                                            │
                                            ▼
                       ┌─────────────────────────────────────────────────────────┐
                       │          GNET v1 Binary Frame (Max 2048 B)              │
                       │     Magic: 0x474E4554 • Version: 1 • Priority: 10       │
                       └────────────────────┬────────────────────────────────────┘
                                            │
                                            ▼
                       ┌─────────────────────────────────────────────────────────┐
                       │           GHOSTNET Offline Mesh Transport               │
                       │        Google Nearby Connections (Wi-Fi / BLE)          │
                       │         Phone A ──(Hop 1)──► Phone B ──(Hop 2)──► C     │
                       └─────────────────────────────────────────────────────────┘
```

The system combines three tightly integrated subsystems:

1. **Android Mesh Transport (`:mesh` and `:app`)**:
   - `:mesh` is a pure Java core library (`MeshRouter`, `MessageCodec`, `MessageStore`) with an Android adapter (`NearbyConnectionsTransport`) running Google Nearby Connections over Wi-Fi Direct and BLE without cellular/internet.
   - Versioned, bounded binary framing (`GNET`, version 1, max 2048 bytes).
   - Store-and-forward relaying with duplicate suppression, TTL expiry, and reverse-path ACKs.
   - `:app` is a device test harness with live connection codes, direct peer tracking, and emergency packet inspector.

2. **On-Device AI Engine (`Models/` & `app/src/main/assets/cascade/`)**:
   - An ultra-compact **17.07 MB INT8 MiniLM** model paired with trained linear heads and domain polarity gates.
   - Runs 100% locally on smartphone CPUs using ONNX Runtime Mobile or the built-in offline Java fallback extractor.
   - Converts informal, messy crisis messages (e.g. *"Need insulin for dad, ground floor flooded, 12% battery"*) into validated structured emergency records (`cascade.emergency.v1`) with zero cloud latency and zero hallucination risk.

3. **Multi-Agent Dispatch & Logistics Engine (`DispatchEngine`, `WarehouseManager`)**:
   - **Role Differentiation**: Distinguishes between **Normal Users** (citizens needing rescue) and **First Responders** (medics, boat operators, firefighters).
   - **Hands-Free Audio Support**: Real-time voice SOS dictation (`🎙️ Voice`) feeding directly into local AI, and audible emergency acoustic siren tones on responder devices.
   - **Marked Supply Warehouses**: Offline directory of disaster supply depots (Warehouse Alpha, Beta, Gamma) tracking stock for insulin, inflatable boats, generators, and rations.
   - **Multi-Depot Supply Routing**: If the nearest warehouse is missing items, automatically routes responders via the next nearest depot.
   - **Probabilistic Evaluation Chain**:
     $$\text{Claim Verification} \longrightarrow \text{Hazard Avoidance} \longrightarrow \text{Trust Scoring} \longrightarrow \text{Battery/Routing Cost} \longrightarrow \text{Safe Dispatch}$$
     Generates one-line plain language justifications for human responders.

---

## Directory Structure

```
Cascade/
├── app/                             # Android application module
│   ├── src/main/java/org/ghostnet/mesh/
│   │   └── DebugActivity.java       # Mesh UI with live AI extraction & packet inspector
│   ├── src/main/assets/cascade/     # Exported on-device ONNX model & heads
│   └── src/testCore/java/org/ghostnet/mesh/core/
│       └── RouterChecks.java        # Deterministic routing & AI serialization tests
├── mesh/                            # Android mesh library module
│   └── src/main/java/org/ghostnet/mesh/
│       ├── NearbyConnectionsTransport.java  # Wi-Fi Direct / BLE Nearby adapter
│       └── core/
│           ├── CascadeEmergency.java        # Structured emergency record (v1)
│           ├── CascadeExtractor.java        # Offline zero-dependency rule extractor
│           ├── MessageCodec.java            # GNET v1 binary frame encoder/decoder
│           ├── MeshRouter.java              # Store-and-forward router
│           └── MessageStore.java            # Atomic snapshot persistence
├── Models/                          # Python AI training, evaluation & export
│   ├── cascade_pipeline.py          # Unified 4-tier pipeline orchestrator
│   ├── intent_classifier.py         # 6-class hybrid classifier
│   ├── tiny_encoder.py              # Multi-label need/hazard tagger + polarity gate
│   ├── local_sos.py                 # Slot extraction & emergency envelope builder
│   ├── mesh_codec.py                # Python GNET binary codec implementation
│   ├── checkpoints/tiny_encoder/    # ONNX model, tokenizer & linear heads
│   └── datasets/                    # Gold test, training & validation corpora
├── scripts/
│   ├── test-core.ps1                # Java core test runner with JDK auto-detection
│   └── verify-apk.ps1               # Audits APK permissions and signature
├── INTEGRATION_GUIDE.md             # End-to-end integration manual
├── README_CASCADE26.md              # Complete CASCADE26 system specification
└── README_MESH.md                   # Low-level GHOSTNET mesh documentation
```

---

## Verification & Testing

### 1. Java Core & Wire Frame Tests (Deterministic)
The core library has no Android dependencies and runs with pure `javac`/`java`:
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test-core.ps1
```
*Validates: A→B→C forwarding, duplicate suppression, ACK loop prevention, re-connection, concurrent delivery, and CASCADE structured emergency payload serialization through `MessageCodec`.*

### 2. Python AI Model Pipeline & Benchmarks
Activate the virtual environment and run the test suite:
```bash
cd Models
python3 -m unittest discover -v -p "test_*.py"
```
*Validates: Intent classification, polarity gates, negation rejection, casualty counting, and GNET binary frame encoding.*

To run the full accuracy benchmark across all datasets:
```bash
python3 evaluate_pipeline.py
```
- **Intent Accuracy**: **114/114 (100.0%)** on Gold Test suite
- **Emergency Extraction**: **46/50 (92.0%)** exact match on adversarial synthetic challenges (0 false positives on negated hazards)
- **CPU Inference Latency**: **~0.9 ms** median per message

---

## Wire Format Specifications

- **Protocol Magic**: `0x474E4554` (`GNET`)
- **Protocol Version**: `1`
- **Max Frame Limit**: `2048 bytes`
- **Distress Payload Schema**:
```json
{
  "version": "cascade.emergency.v1",
  "intent": "RESCUE_REQUEST",
  "confidence": 0.98,
  "urgency": "critical",
  "needs": ["rescue", "medical"],
  "hazards": ["flood"],
  "people_count": 3,
  "location": "2nd floor",
  "raw_text": "Water rising rapidly trapped on 2nd floor need boat and medical kit for 3 people"
}
```

---

## Building the Android App

1. Open the project root in **Android Studio**.
2. Ensure **Android SDK 35** and **Google Play Services** are installed.
3. Build the debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. Verify APK permissions and signature:
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify-apk.ps1
   ```
