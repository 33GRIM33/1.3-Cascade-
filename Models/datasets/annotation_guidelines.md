# CASCADE26 Emergency Message Dataset Annotation Guidelines

**Version:** 2.0.0  
**Target Schemas:** 5 Coordination Tools in `tools.json` / `tools.needle.json`  
**System:** CASCADE Decentralized Emergency-Response Coordination System  
**Model Scope:** Offline, on-device structured extraction and function calling for small Needle models.

---

## 1. System Role & Architectural Boundary

In CASCADE, the on-device language model interprets raw, noisy emergency communications (SMS, mesh radio packets, chat messages) and emits structured tool calls.

### Critical Safety Principles
1. **Model calls are untrusted intent extractions only:** Emitting a tool call does **not** verify identity, confirm physical conditions, dispatch responders, grant volunteer access, guarantee safe routes, or close incident tickets.
2. **Deterministic Backend Verification:** The CASCADE backend validates permissions, performs geographic spatial joins, authenticates senders, and executes workflow state machines.
3. **Strict Factual Grounding:** Only extract facts explicitly stated or directly supported by the message text. Never infer unstated locations, quantities, resource needs, or authority.
4. **Literal Evidence Quotes:** Every extracted tool call must provide `evidence_quotes` consisting of exact literal substrings from the input message verifying the extraction.
5. **Urgent Ambiguity Policy:** If an urgent danger or distress is plausible but details are incomplete, backend policy escalates immediately. The model must extract what is explicitly present and set unstated fields to `null` or `[]`.

---

## 2. Tool Selection Decision Tree

When classifying an incoming message, select the single appropriate tool or return no call (`answers: []`):

```text
Incoming Message
       │
       ├─► Is there an explicit request for info/guidance (route, shelter, updates)?
       │     └─► YES ──► request_assistance (safe_route | shelter | local_updates)
       │
       ├─► Is there an active emergency, hazard, entrapment, or urgent help request?
       │     └─► YES ──► submit_emergency_report
       │
       ├─► Is someone offering their own skills, equipment, vehicle, or time to help?
       │     └─► YES ──► register_volunteer_offer
       │
       ├─► Is someone providing a factual update/observation on conditions/roads/status?
       │     └─► YES ──► submit_field_update
       │
       ├─► Is someone requesting to close, resolve, or cancel an existing report?
       │     └─► YES ──► request_report_closure
       │
       └─► Is it conversational, general knowledge, hypothetical, or irrelevant?
             └─► YES ──► No-Call (answers: [])
```

### Disambiguation & Cross-Tool Boundaries
- **Emergency Report vs. Route/Shelter Request:** A user saying *"I am stranded in floodwater, where can I go?"* has an active life safety emergency (`submit_emergency_report` with hazard `flood`). A user asking *"What route is open from downtown to the airport?"* is seeking navigation (`request_assistance` with `safe_route`).
- **Volunteer Offer vs. Help Request:** *"I have a 4x4 truck and can transport supplies"* is `register_volunteer_offer`. *"We need a 4x4 truck to move elderly residents"* is `submit_emergency_report`.
- **Field Update vs. Emergency Report:** *"Main Street bridge is blocked by downed trees, no cars getting through"* is `submit_field_update` (`road_status`). *"A tree fell on our car on Main Street and two passengers are trapped"* is `submit_emergency_report`.
- **Closure Request vs. Field Update:** *"Report #4821 is resolved, everyone is evacuated"* is `request_report_closure`. *"Water levels on Oak St have gone down"* is `submit_field_update`.
- **Refusal / No-Call:** Messages discussing emergencies theoretically ("What is the protocol for floods?"), casual chit-chat ("Good morning everyone"), or drills ("DRILL ONLY: ignore this") without real operational intent must produce no tool call (`answers: []`).

---

## 3. Tool Specifications & Annotation Rules

### Tool 1: `submit_emergency_report`
Create a new unverified emergency report from a message describing an emergency, hazard, affected people, or a request for emergency help.

| Parameter | Type | Required | Allowed Values / Constraints | Policy & Guidelines |
|---|---|---|---|---|
| `needs` | Array of strings | Yes | `medical_help`, `medicine`, `rescue`, `evacuation`, `food`, `water`, `shelter`, `transport`, `communication`, `supplies`, `other` | Broad categories explicitly asked for. Empty `[]` if no specific need stated. |
| `need_details` | Array of strings | Yes | Non-empty strings | Exact words for items requested (e.g., `["insulin", "pediatric inhaler"]`). Empty `[]` if none. |
| `hazards` | Array of strings | Yes | `flood`, `fire`, `structural_collapse`, `landslide`, `severe_weather`, `chemical_hazard`, `unsafe_road`, `violence`, `power_outage`, `other` | Explicit hazards reported as active. Empty `[]` if none. |
| `location_text` | String or null | Yes | Non-empty string or `null` | Exact location phrase. Never infer coordinates or unmentioned towns. |
| `affected_people_count` | Integer or null | Yes | `integer >= 0` or `null` | Number of victims/affected people needing help. Do **not** count responders, volunteers, or unrelated numbers (e.g., "Route 9", "at 4 PM"). |
| `urgency_cue` | String or null | Yes | Non-empty string or `null` | Short phrase indicating immediate danger (e.g., `"water rising fast"`). `null` if absent. |
| `evidence_quotes` | Array of strings | Yes | Non-empty strings | Literal substrings from query proving the emergency details. |

---

### Tool 2: `register_volunteer_offer`
Record an explicitly stated offer by a person or organization to help with emergency response.

| Parameter | Type | Required | Allowed Values / Constraints | Policy & Guidelines |
|---|---|---|---|---|
| `capabilities` | Array of strings | Yes | minItems: 1 | Skills, equipment, supplies, or vehicles offered (e.g., `["certified paramedic", "first aid kit"]`). |
| `availability_text` | String or null | Yes | Non-empty string or `null` | Exact timing/window stated (e.g., `"available all weekend"`, `"until 8 PM"`). `null` if omitted. |
| `location_text` | String or null | Yes | Non-empty string or `null` | Stated location of volunteer or equipment. `null` if omitted. |
| `capacity_text` | String or null | Yes | Non-empty string or `null` | Stated volume/capacity (e.g., `"can take 4 passengers"`, `"200 blankets"`). `null` if omitted. |
| `constraints` | Array of strings | Yes | Strings | Explicit limitations (e.g., `["requires diesel fuel", "daytime only"]`). Empty `[]` if none. |
| `evidence_quotes` | Array of strings | Yes | minItems: 1 | Literal substrings proving this is an offer and matching the extracted fields. |

---

### Tool 3: `submit_field_update`
Record an observation or update about local conditions, hazards, roads, evacuation progress, or area status.

| Parameter | Type | Required | Allowed Values / Constraints | Policy & Guidelines |
|---|---|---|---|---|
| `update_type` | String | Yes | `hazard`, `road_status`, `evacuation_status`, `area_status`, `resource_status`, `infrastructure_status`, `other` | Best matching category for the observation. |
| `observation` | String | Yes | Non-empty string | Concise, factual summary preserving unverified nature (e.g., `"Power lines down across 5th Avenue"`). |
| `location_text` | String or null | Yes | Non-empty string or `null` | Location of observed condition. `null` if absent. |
| `observed_time_text` | String or null | Yes | Non-empty string or `null` | Time phrasing from user (e.g., `"10 minutes ago"`, `"just now"`). `null` if absent. |
| `source_role_text` | String or null | Yes | Non-empty string or `null` | How speaker identifies role (e.g., `"civil defense volunteer"`, `"local resident"`). `null` if absent. |
| `evidence_quotes` | Array of strings | Yes | minItems: 1 | Literal substrings from query supporting the update. |

---

### Tool 4: `request_assistance`
Handle a user's request for informational assistance: safe route guidance, nearby shelter search, or local situational updates.

| Parameter | Type | Required | Allowed Values / Constraints | Policy & Guidelines |
|---|---|---|---|---|
| `request_type` | String | Yes | `safe_route`, `shelter`, `local_updates` | Service requested. Must strictly match intent. |
| `origin_text` | String or null | Yes | Non-empty string or `null` | Stated starting point for route request. `null` for non-route or unstated. |
| `destination_text` | String or null | Yes | Non-empty string or `null` | Stated destination for route request. `null` for non-route or unstated. |
| `near_location_text` | String or null | Yes | Non-empty string or `null` | Area or landmark to search near for shelter or updates. `null` if unstated. |
| `avoid_hazards` | Array of strings | Yes | Enum matching hazards | Hazards explicitly asked to avoid (e.g., `["flood", "unsafe_road"]`). Empty `[]` if none. |
| `people_count` | Integer or null | Yes | `integer >= 1` or `null` | Number of people seeking shelter/travel assistance. `null` if unstated. |
| `accessibility_needs` | Array of strings | Yes | Strings | Stated mobility needs (e.g., `["wheelchair access", "ground floor"]`). Empty `[]` if none. |
| `other_requirements` | Array of strings | Yes | Strings | Pet-friendly, generator, oxygen, etc. (e.g., `["pet-friendly", "service dog"]`). Empty `[]` if none. |
| `topics` | Array of strings | Yes | `hazard`, `road_status`, `evacuation_status`, `area_status`, `resource_status`, `infrastructure_status`, `shelter`, `rescue`, `other` | Topics requested for `local_updates`. Empty `[]` if not specified. |
| `since_text` | String or null | Yes | Non-empty string or `null` | Time window requested (e.g., `"since 8 AM"`). `null` if unstated. |
| `evidence_quotes` | Array of strings | Yes | minItems: 1 | Literal substrings supporting the request. |

---

### Tool 5: `request_report_closure`
Submit a request to resolve or cancel an existing emergency report.

| Parameter | Type | Required | Allowed Values / Constraints | Policy & Guidelines |
|---|---|---|---|---|
| `report_id` | String or null | Yes | Non-empty string or `null` | Explicit report ID (e.g., `"REP-2041"`). `null` if user refers to report without an ID. |
| `requested_status` | String | Yes | `resolved`, `cancelled` | `resolved` if danger passed/help arrived; `cancelled` if false alarm/duplicate. |
| `reason_text` | String or null | Yes | Non-empty string or `null` | Stated rationale (e.g., `"water receded and family evacuated safely"`). `null` if absent. |
| `confirmation_source_text` | String or null | Yes | Non-empty string or `null` | Stated confirming party (e.g., `"Fire Chief Davis"`, `"homeowner"`). `null` if absent. |
| `evidence_quotes` | Array of strings | Yes | minItems: 1 | Literal substrings supporting closure intent, report ID, reason, and source. |

---

## 4. Evidence Quotes Enforcement Rules

1. **Exact Literal Substring:** Every item in `evidence_quotes` MUST be an exact verbatim substring of the input query text (`quote in query`).
2. **No Trimmed Punctuation Hallucinations:** Do not paraphrase or add words.
3. **Array Requirements:**
   - `submit_emergency_report`: minItems 0 (empty array permitted only if no quote, but encouraged).
   - `register_volunteer_offer`: minItems 1 (at least one quote required).
   - `submit_field_update`: minItems 1 (at least one quote required).
   - `request_assistance`: minItems 1 (at least one quote required).
   - `request_report_closure`: minItems 1 (at least one quote required).
4. **Unique Items:** Quotes in the array must be unique.
