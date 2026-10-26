package org.ghostnet.mesh.core;

import java.util.*;

/** Dijkstra over the walking graph. No straight-line fallback through a blocked area. */
public final class HazardRouter {
    private final StreetMap graph;
    public HazardRouter(StreetMap graph) { this.graph = graph; }
    public static final class Route {
        public final boolean reachable;
        public final List<StreetMap.Edge> edges;
        public final double meters;
        public final String reason;
        Route(boolean reachable, List<StreetMap.Edge> edges, double meters, String reason) {
            this.reachable = reachable; this.edges = Collections.unmodifiableList(edges); this.meters = meters; this.reason = reason;
        }
    }
    public Set<String> blockedEdges(Collection<HazardReport> hazards, long now) {
        Set<String> blocked = new HashSet<>();
        for (StreetMap.Edge edge : graph.edges.values())
            for (HazardReport h : hazards) if (h.blocks(edge, graph.id, now)) { blocked.add(edge.id); break; }
        return blocked;
    }
    private static final class Visit {
        final StreetMap.Node node; final double meters;
        Visit(StreetMap.Node node, double meters) { this.node = node; this.meters = meters; }
    }
    public Route route(double lat, double lon, double toLat, double toLon, Collection<HazardReport> hazards, long now) {
        StreetMap.Node from = graph.nearestNode(lat, lon), to = graph.nearestNode(toLat, toLon);
        if (from == null || to == null || StreetMap.distance(lat, lon, from.lat, from.lon) > 120
                || StreetMap.distance(toLat, toLon, to.lat, to.lon) > 120) return unavailable("Outside downloaded street coverage");
        // Do not snap a destination out of a reported danger zone onto a nearby clear road.
        for (HazardReport h : hazards) if (h.mapId.equals(graph.id) && h.active(now)) {
            if (StreetMap.distance(lat, lon, h.lat, h.lon) <= h.radiusMeters
                    || StreetMap.distance(toLat, toLon, h.lat, h.lon) <= h.radiusMeters)
                return unavailable("Start or destination is inside a reported " + h.type.name().toLowerCase(Locale.US) + " area");
            StreetMap.Node rawStart = new StreetMap.Node("", lat, lon), rawEnd = new StreetMap.Node("", toLat, toLon);
            if (StreetMap.segmentDistance(h.lat, h.lon, rawStart, from) <= h.radiusMeters
                    || StreetMap.segmentDistance(h.lat, h.lon, rawEnd, to) <= h.radiusMeters)
                return unavailable("Access to the street network intersects a reported hazard");
        }
        Set<String> blocked = blockedEdges(hazards, now);
        Map<String, Double> distances = new HashMap<>();
        Map<String, StreetMap.Edge> previous = new HashMap<>();
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingDouble(v -> v.meters));
        distances.put(from.id, 0.0); queue.add(new Visit(from, 0));
        while (!queue.isEmpty()) {
            Visit current = queue.remove();
            if (current.meters > distances.getOrDefault(current.node.id, Double.POSITIVE_INFINITY)) continue;
            if (current.node == to) break;
            for (StreetMap.Edge edge : current.node.links) {
                if (blocked.contains(edge.id)) continue;
                StreetMap.Node next = edge.other(current.node);
                double candidate = current.meters + edge.meters;
                if (candidate < distances.getOrDefault(next.id, Double.POSITIVE_INFINITY)) {
                    distances.put(next.id, candidate); previous.put(next.id, edge); queue.add(new Visit(next, candidate));
                }
            }
        }
        if (!distances.containsKey(to.id)) return unavailable("No route avoids all active reports");
        List<StreetMap.Edge> path = new ArrayList<>();
        for (StreetMap.Node n = to; n != from; ) { StreetMap.Edge e = previous.get(n.id); path.add(e); n = e.other(n); }
        Collections.reverse(path);
        return new Route(true, path, distances.get(to.id), "Walking route; all known reported closures excluded");
    }
    private Route unavailable(String reason) { return new Route(false, new ArrayList<>(), Double.POSITIVE_INFINITY, reason); }
}
