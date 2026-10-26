# Build 0.6 — offline streets and shared road closures

The Map tab renders a bundled OpenStreetMap extract of Civic Center, San Francisco:
6,316 nodes, 7,890 street/walking segments and 2,491 building/park polygons. It needs
no tile server, map API key, or internet connection at runtime. Streets retain their
actual names and OSM intersection IDs. The responder, incident and two warehouses
are explicitly labeled demo positions; this build does not acquire real GPS.

## Phone test

1. Install `dist/GHOSTNET-Mesh-0.6.apk` on all phones, launch, and tap **Start mesh**.
2. The Map tab starts with a walking route to the demo incident through both depots.
3. Tap a street segment on the route, then **Report hazard**. Choose fire, blocked
   road, flood or debris; add optional details, then **Block & share**.
4. The reported segment and nearby danger area turn red. Teal routes recompute over
   actual street edges; if every approach is blocked, the map displays **NO ROUTE**.
5. Connected phones apply the same closure and recompute their route. For a relay
   test, block A-C in both directions and leave B unblocked in the Network tab.
6. Open **Reports** to see the street, hazard type, reporter and expiration. The
   original reporter can clear a report. Clearance propagates and routes reopen
   unless another active report still blocks the road.
7. Restart a phone: its reports are restored. Reconnect another phone: active
   observations and clearance tombstones synchronize with the new peer.
8. Tap **Warehouses** or W1/W2 to view stock and route to a depot. **Incident route**
   restores supply collection routing. Tap the route summary for street directions.

## Routing and report behavior

- Dijkstra finds shortest walking paths in the downloaded graph. No route is
  replaced by a straight line when disconnected or outside downloaded coverage.
- A closure always excludes the chosen segment, plus edges intersecting its
  danger circle: fire 40 m, flood 55 m, blockage/debris 12 m.
- Reports last one hour, survive process restarts, and are bounded to 128 records.
  Expiration is checked every 15 seconds while the map is active.
- Hazard records use the bounded GNH1 payload inside GNET `OBSERVATION` frames,
  hop limit 8. Mesh packet deduplication remains in place. Reconnect snapshots use
  new transport packet IDs while retaining report ID and original expiry.
- Clearances retain the report ID and original author; a delayed older snapshot
  cannot resurrect a cleared report. Multiple independent reports remain separate.
- Community reports and responder identity are unverified in this prototype.
- The full segment is shown closed even when it extends past the danger circle.
- Street routes are walking-network suggestions, not driving directions, ETAs,
  or a guarantee about unreported hazards. No road hazard is pre-seeded.

## Demo warehouses

Seeded random placement picks two reproducible street nodes so all phones agree.
These are fictional facilities, not claims about real relief centers. Stock is
display-only demo inventory; it is not reserved or deducted automatically.

| Depot | Supplies |
|---|---|
| Civic Medical Depot | insulin ×4, medical kits ×6, water ×12, shelter supplies ×3 |
| Neighborhood Relief Depot | boat ×1, life jackets ×4, water ×8, power packs ×2 |

## Validation

`scripts/test-core.ps1` checks all four hazard detours, complete isolation, dangerous
endpoints, expiry, clearances, stale replays, malformed reports, A-B-C hazard/clearance
flooding, and real OSM supply routing. The real graph baseline is 2,655 m; a tested
fire closure detours to 2,692 m without traversing any blocked edge. Dispatch is held
for an inaccessible route, unavailable supplies, or an electrical hazard.

`scripts/verify-apk.ps1` checks version 0.6, launcher, signature, required permissions,
and bundled map/licensing assets. Refresh the snapshot with
`scripts/build-offline-map.ps1`; redistribution attribution is in
`app/src/main/assets/maps/LICENSE.txt` and accessible directly on the map.
