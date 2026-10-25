# CASCADE26 Dataset Review Report (v2.0.0)

**Generated:** 2026-10-25T16:46:54.129647+00:00  
**Target Schema:** Schema 2.0.0 (`tools.json` / `tools.needle.json`)  
**Total Records:** 3000  
**Seed:** 20261025

---

## 1. Split Distribution

| Split | Count | Share |
|---|---:|---:|
| Train | 2400 | 80.0% |
| Validation | 300 | 10.0% |
| Test | 300 | 10.0% |
| **Total** | **3000** | **100.0%** |

---

## 2. Tool Coverage

| Category / Subtype | Train | Val | Test | Total |
|---|---:|---:|---:|---:|
| `submit_emergency_report` | 600 | 75 | 75 | 750 |
| `register_volunteer_offer` | 360 | 45 | 45 | 450 |
| `submit_field_update` | 360 | 45 | 45 | 450 |
| `request_assistance` (Total) | 600 | 75 | 75 | 750 |
| ├─ `safe_route` | 200 | 25 | 25 | 250 |
| ├─ `shelter` | 200 | 25 | 25 | 250 |
| └─ `local_updates` | 200 | 25 | 25 | 250 |
| `request_report_closure` | 240 | 30 | 30 | 300 |
| No-Call / Negatives | 240 | 30 | 30 | 300 |

---

## 3. Leakage & Split Integrity

- **Template Family Overlap:** 0 (All 600 template families strictly assigned to a single split).
- **Exact Query Overlap:**
  - Train ∩ Validation: 0
  - Train ∩ Test: 0
  - Validation ∩ Test: 0
- **Geographic Disjointness:** Distinct landmark & street name banks across Train, Validation, and Test pools.
- **Evidence Quote Validity:** 100% of extracted quotes verified as verbatim substrings of input messages.
