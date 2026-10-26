package org.ghostnet.mesh.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Behavioral checks for real street routes, remote observations, expiry and fail-closed dispatch. */
public final class HazardRoutingChecks {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        long now = System.currentTimeMillis(); String reporter = "reporter-phone-001";
        StreetMap map = new StreetMap(); map.id = "test-map";
        map.addNode("a", 37, -122); map.addNode("b", 37, -121.998); map.addNode("c", 37, -121.996);
        map.addNode("d", 37.002, -122); map.addNode("e", 37.002, -121.996);
        map.addEdge("ab", "a", "b", "Main Street", "residential");
        map.addEdge("bc", "b", "c", "Main Street", "residential");
        map.addEdge("ad", "a", "d", "Oak Street", "residential");
        map.addEdge("de", "d", "e", "Pine Street", "residential");
        map.addEdge("ec", "e", "c", "Elm Street", "residential");
        HazardRouter router = new HazardRouter(map);
        HazardRouter.Route initial = router.route(37, -122, 37, -121.996, Collections.emptyList(), now);
        check(initial.reachable && initial.edges.size() == 2, "clear route follows Main Street");
        HazardReport blocked = HazardReport.create(reporter, map, map.edges.get("ab"), HazardReport.Type.BLOCKED, "fallen tree", now);
        for (HazardReport.Type type : HazardReport.Type.values()) {
            HazardReport h = HazardReport.create(reporter, map, map.edges.get("ab"), type, "danger", now);
            HazardRouter.Route alternate = router.route(37, -122, 37, -121.996, Arrays.asList(h), now);
            check(alternate.reachable && alternate.meters > initial.meters, type + " causes a longer detour");
            check(alternate.edges.stream().noneMatch(e -> e.id.equals("ab")), type + " never traverses closed segment");
        }
        HazardReport second = HazardReport.create(reporter, map, map.edges.get("ad"), HazardReport.Type.BLOCKED, "closed", now);
        HazardRouter.Route none = router.route(37, -122, 37, -121.996, Arrays.asList(blocked, second), now);
        check(!none.reachable && none.edges.isEmpty(), "all exits blocked produces NO ROUTE, no fallback line");
        HazardRouter.Route danger = router.route(37, -122, blocked.lat, blocked.lon, Arrays.asList(blocked), now);
        check(!danger.reachable, "destination in hazard must not snap outside it");
        check(!router.route(0, 0, 37, -121.996, Collections.emptyList(), now).reachable, "outside map fails closed");
        check(router.route(37, -122, 37, -121.996, Arrays.asList(blocked), blocked.expiresAt + 1).edges.size() == 2, "expiry restores the route");
        HazardLedger ledger = new HazardLedger();
        check(ledger.apply(blocked, now), "new observation accepted");
        check(!ledger.apply(blocked, now), "duplicate observation ignored");
        HazardReport clear = blocked.clear(now + 100);
        check(ledger.apply(clear, now + 100) && ledger.active(now + 100).isEmpty(), "clearance restores access");
        check(!ledger.apply(blocked, now + 101), "old snapshot cannot resurrect cleared hazard");
        check(ledger.snapshots(now + 100).size() == 1, "clearance tombstone kept for reconnect sync");
        HazardReport decoded = HazardReport.decode(blocked.encode());
        check(decoded.edgeId.equals("ab") && decoded.note.equals("fallen tree"), "wire preserves street and details");
        check(HazardReport.decode(clear.encode()).cleared, "wire preserves clearance");
        MessageCodec codec = new MessageCodec();
        MeshEvent frame = MeshEvent.create(reporter, "*", MeshEvent.Type.OBSERVATION, blocked.encode(), now, 300000, 8, 9);
        MeshEvent forwarded = codec.decode(codec.encode(frame.forwardedBy("relay-phone-0001")));
        HazardLedger remote = new HazardLedger(); remote.apply(HazardReport.decode(forwarded.payload), now);
        check(!router.route(37, -122, 37, -121.996, remote.active(now), now).edges.stream().anyMatch(e -> e.id.equals("ab")), "a received relay packet changes the remote route");
        byte[] truncated = Arrays.copyOf(blocked.encode(), blocked.encode().length - 1);
        try { HazardReport.decode(truncated); throw new AssertionError("truncated report accepted"); } catch (IOException expected) { }
        System.out.println("Hazard checks: fire/blockage/flood/debris detours, isolation, expiry, clearance, replay, relay and malformed input passed.");

        try (Reader input = Files.newBufferedReader(Paths.get("app/src/main/assets/maps/sf-civic.tsv"))) { map = StreetMap.load(input); }
        check(map.nodes.size() > 1000 && map.edges.size() > 1000, "real OSM graph loaded");
        check(map.edges.values().stream().anyMatch(e -> e.name.equals("Market Street")), "real Market Street name bundled");
        WarehouseManager wm = WarehouseManager.demo(map);
        check(wm.getAllWarehouses().size() == 2, "exactly two demo warehouses");
        for (Warehouse w : wm.getAllWarehouses()) for (int stock : w.getInventory().values()) check(stock > 0 && stock <= 12, "limited demo stock");
        List<HazardReport> reports = new ArrayList<>();
        wm.configureRouting(map, () -> reports);
        WarehouseManager.RoutePlan baseline = wm.planRoute(Arrays.asList("insulin", "boat"), 37.7740, -122.4250, 37.7791, -122.4136);
        check(baseline.reachable && baseline.stops.size() == 4 && baseline.unfulfilledItems.isEmpty(), "real map route visits both depots and incident");
        check(baseline.streetEdges.size() > 5, "real route follows road segments");
        StreetMap.Edge target = null;
        for (StreetMap.Edge edge : baseline.streetEdges) {
            double lat = (edge.a.lat + edge.b.lat) / 2, lon = (edge.a.lon + edge.b.lon) / 2;
            boolean away = StreetMap.distance(lat, lon, 37.7740, -122.4250) > 120 && StreetMap.distance(lat, lon, 37.7791, -122.4136) > 120;
            for (Warehouse w : wm.getAllWarehouses()) away &= StreetMap.distance(lat, lon, w.latitude, w.longitude) > 120;
            if (away && edge.meters > 10 && !edge.name.equals("Walkway")) { target = edge; break; }
        }
        check(target != null, "route has a reportable street away from stops");
        reports.add(HazardReport.create(reporter, map, target, HazardReport.Type.FIRE, "fire reported on street", now));
        WarehouseManager.RoutePlan detour = wm.planRoute(Arrays.asList("insulin", "boat"), 37.7740, -122.4250, 37.7791, -122.4136);
        check(detour.reachable, "real OSM map has an alternate route around reported fire");
        Set<String> excluded = new HazardRouter(map).blockedEdges(reports, now);
        check(detour.streetEdges.stream().noneMatch(e -> excluded.contains(e.id)), "multi-depot route excludes every blocked edge");
        CascadeEmergency electrical = CascadeExtractor.extract("Live wire down, 3 people trapped need rescue", "NORMAL_USER", 50, 37.7791, -122.4136, false);
        DispatchEngine.EvaluationResult evaluation = new DispatchEngine(wm).evaluate(electrical, reporter, 4, 37.7740, -122.4250);
        check(!evaluation.hazardSafe && !evaluation.approvedForDispatch, "electrical hazard cannot be approved for dispatch");
        System.out.println("OSM checks: " + map.nodes.size() + " nodes, " + map.edges.size() + " segments; two-depot route "
                + Math.round(baseline.totalDistanceMeters) + " m; fire detour " + Math.round(detour.totalDistanceMeters) + " m; dispatch gate passed.");
        for (Warehouse w : wm.getAllWarehouses()) System.out.println(w);
    }
}
