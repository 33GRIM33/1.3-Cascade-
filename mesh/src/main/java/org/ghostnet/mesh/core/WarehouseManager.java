package org.ghostnet.mesh.core;

import java.util.*;
import java.util.function.Supplier;

/**
 * Manages disaster supply warehouses, stock levels, and multi-depot routing.
 * Helps first responders find where needed resources are available, checking the nearest
 * warehouse first and seamlessly falling back to the next nearest warehouse for missing items.
 */
public final class WarehouseManager {

    private final Map<String, Warehouse> warehouses = new LinkedHashMap<>();
    private StreetMap streetMap;
    private Supplier<Collection<HazardReport>> hazardSource = Collections::emptyList;

    public WarehouseManager() {
        seedDefaultWarehouses();
    }

    private WarehouseManager(boolean empty) { }

    /** Exactly two fictional depots, placed reproducibly so all offline peers agree. */
    public static WarehouseManager demo(StreetMap map) {
        WarehouseManager manager = new WarehouseManager(true);
        List<StreetMap.Node> choices = new ArrayList<>();
        for (StreetMap.Edge e : map.edges.values()) {
            if (!e.name.equals("Walkway") && e.a.lat > 37.773 && e.a.lat < 37.780
                    && e.a.lon > -122.426 && e.a.lon < -122.414) choices.add(e.a);
        }
        if (choices.isEmpty()) choices.addAll(map.nodes.values());
        choices.sort(Comparator.comparing(n -> n.id));
        Random random = new Random(6602);
        StreetMap.Node first = choices.get(random.nextInt(choices.size()));
        StreetMap.Node second = first;
        for (int i = 0; i < choices.size(); i++) {
            second = choices.get(random.nextInt(choices.size()));
            if (StreetMap.distance(first.lat, first.lon, second.lat, second.lon) > 280) break;
        }
        Map<String, Integer> med = new LinkedHashMap<>();
        med.put("insulin", 4); med.put("medical", 6); med.put("water", 12); med.put("shelter", 3);
        Map<String, Integer> rescue = new LinkedHashMap<>();
        rescue.put("boat", 1); rescue.put("life_jacket", 4); rescue.put("water", 8); rescue.put("power", 2);
        manager.registerWarehouse(new Warehouse("DEMO-MEDICAL", "Civic Medical Depot", first.lat, first.lon, med));
        manager.registerWarehouse(new Warehouse("DEMO-RELIEF", "Neighborhood Relief Depot", second.lat, second.lon, rescue));
        return manager;
    }

    public void configureRouting(StreetMap graph, Supplier<Collection<HazardReport>> hazards) {
        streetMap = graph; hazardSource = hazards;
    }

    private void seedDefaultWarehouses() {
        // Warehouse Alpha (Medical & Food Hub - North Sector)
        Map<String, Integer> alphaStock = new HashMap<>();
        alphaStock.put("insulin", 50);
        alphaStock.put("medical", 120);
        alphaStock.put("first_aid", 100);
        alphaStock.put("water", 400);
        alphaStock.put("food_water", 400);
        alphaStock.put("blanket", 150);
        alphaStock.put("shelter", 80);
        warehouses.put("WH-ALPHA", new Warehouse("WH-ALPHA", "Warehouse Alpha (North Sector)", 37.7749, -122.4194, alphaStock));

        // Warehouse Beta (Water Rescue & Heavy Equipment - River/Flood Basin)
        Map<String, Integer> betaStock = new HashMap<>();
        betaStock.put("boat", 4);
        betaStock.put("rescue", 4);
        betaStock.put("life_jacket", 45);
        betaStock.put("rope", 30);
        betaStock.put("water_pump", 8);
        betaStock.put("generator", 6);
        betaStock.put("power", 6);
        warehouses.put("WH-BETA", new Warehouse("WH-BETA", "Warehouse Beta (River Basin)", 37.7833, -122.4167, betaStock));

        // Warehouse Gamma (Central Relief Depot - East Safe Zone)
        Map<String, Integer> gammaStock = new HashMap<>();
        gammaStock.put("food_rations", 600);
        gammaStock.put("food_water", 600);
        gammaStock.put("baby_formula", 80);
        gammaStock.put("cots", 200);
        gammaStock.put("shelter", 200);
        gammaStock.put("battery", 100);
        gammaStock.put("power", 100);
        gammaStock.put("insulin", 25);
        gammaStock.put("medical", 60);
        warehouses.put("WH-GAMMA", new Warehouse("WH-GAMMA", "Warehouse Gamma (East Safe Zone)", 37.7650, -122.4250, gammaStock));
    }

    public void registerWarehouse(Warehouse warehouse) {
        if (warehouse != null) {
            warehouses.put(warehouse.id, warehouse);
        }
    }

    public Collection<Warehouse> getAllWarehouses() {
        return Collections.unmodifiableCollection(warehouses.values());
    }

    public Warehouse getWarehouse(String id) {
        return warehouses.get(id);
    }

    /**
     * Finds warehouses that have a specific item in stock, ordered by distance from coordinates.
     */
    public List<WarehouseMatch> findWarehousesWithStock(String item, double fromLat, double fromLon) {
        String key = normalizeItem(item);
        List<WarehouseMatch> matches = new ArrayList<>();
        for (Warehouse wh : warehouses.values()) {
            int stock = wh.getStock(key);
            if (stock > 0) {
                double dist = wh.distanceTo(fromLat, fromLon);
                matches.add(new WarehouseMatch(wh, key, stock, dist));
            }
        }
        Collections.sort(matches, Comparator.comparingDouble(m -> m.distanceMeters));
        return matches;
    }

    /**
     * Result of finding a warehouse with item stock.
     */
    public static final class WarehouseMatch {
        public final Warehouse warehouse;
        public final String item;
        public final int stock;
        public final double distanceMeters;

        public WarehouseMatch(Warehouse warehouse, String item, int stock, double distanceMeters) {
            this.warehouse = warehouse;
            this.item = item;
            this.stock = stock;
            this.distanceMeters = distanceMeters;
        }

        public String formattedDistance() {
            if (distanceMeters < 1000) {
                return Math.round(distanceMeters) + " m";
            }
            return String.format(Locale.US, "%.1f km", distanceMeters / 1000.0);
        }
    }

    /**
     * Routing plan for a first responder detailing warehouse stops for needed supplies.
     */
    public static final class RoutePlan {
        public final List<RouteStop> stops = new ArrayList<>();
        public final List<String> fulfilledItems = new ArrayList<>();
        public final List<String> unfulfilledItems = new ArrayList<>();
        public double totalDistanceMeters = 0.0;
        public String summaryText = "";
        public boolean reachable = true;
        public final List<StreetMap.Edge> streetEdges = new ArrayList<>();

        public static final class RouteStop {
            public final String label;
            public final double latitude;
            public final double longitude;
            public final List<String> collectItems = new ArrayList<>();
            public double distanceFromPrevMeters = 0.0;

            public RouteStop(String label, double latitude, double longitude) {
                this.label = label;
                this.latitude = latitude;
                this.longitude = longitude;
            }
        }

        public String getDetailedPlan() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== RESPONDER DISPATCH & SUPPLY ROUTE ===\n");
            for (int i = 0; i < stops.size(); i++) {
                RouteStop stop = stops.get(i);
                sb.append(i + 1).append(". ").append(stop.label);
                if (stop.distanceFromPrevMeters > 0) {
                    sb.append(String.format(Locale.US, " (+%.1f km)", stop.distanceFromPrevMeters / 1000.0));
                }
                if (!stop.collectItems.isEmpty()) {
                    sb.append("\n   📦 Collect: ").append(String.join(", ", stop.collectItems));
                }
                sb.append("\n");
            }
            sb.append(String.format(Locale.US, "Total Distance: %.2f km\n", totalDistanceMeters / 1000.0));
            if (!unfulfilledItems.isEmpty()) {
                sb.append("⚠️ Missing supplies not in stock: ").append(String.join(", ", unfulfilledItems)).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Plans a multi-stop supply route from responder to victim, stopping at the nearest
     * warehouse(s) that have the items the victim requested.
     */
    public RoutePlan planRoute(List<String> neededItems, double responderLat, double responderLon,
                               double victimLat, double victimLon) {
        if (streetMap != null) return planStreetRoute(neededItems, responderLat, responderLon, victimLat, victimLon);
        RoutePlan plan = new RoutePlan();
        if (neededItems == null || neededItems.isEmpty()) {
            // Direct route: responder to victim
            RoutePlan.RouteStop start = new RoutePlan.RouteStop("Responder Location", responderLat, responderLon);
            RoutePlan.RouteStop end = new RoutePlan.RouteStop("Victim Location", victimLat, victimLon);
            double dist = haversine(responderLat, responderLon, victimLat, victimLon);
            end.distanceFromPrevMeters = dist;
            plan.stops.add(start);
            plan.stops.add(end);
            plan.totalDistanceMeters = dist;
            plan.summaryText = "Direct dispatch to victim (" + formatDist(dist) + ")";
            return plan;
        }

        Set<String> remaining = new LinkedHashSet<>();
        for (String item : neededItems) remaining.add(normalizeItem(item));

        double currLat = responderLat;
        double currLon = responderLon;
        RoutePlan.RouteStop start = new RoutePlan.RouteStop("Responder Location", currLat, currLon);
        plan.stops.add(start);

        // Greedily find closest warehouse that has the most needed items
        while (!remaining.isEmpty()) {
            Warehouse bestWh = null;
            double bestDist = Double.MAX_VALUE;
            List<String> bestItemsCovered = new ArrayList<>();

            for (Warehouse wh : warehouses.values()) {
                List<String> canSupply = new ArrayList<>();
                for (String item : remaining) {
                    if (wh.hasStock(item)) {
                        canSupply.add(item + " (" + wh.getStock(item) + " avail)");
                    }
                }
                if (!canSupply.isEmpty()) {
                    double dist = wh.distanceTo(currLat, currLon);
                    // Heuristic: minimize distance while rewarding supplying more items
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestWh = wh;
                        bestItemsCovered = canSupply;
                    }
                }
            }

            if (bestWh == null) {
                // No remaining warehouses have the rest of the items
                break;
            }

            RoutePlan.RouteStop stop = new RoutePlan.RouteStop(bestWh.name, bestWh.latitude, bestWh.longitude);
            stop.distanceFromPrevMeters = bestDist;
            stop.collectItems.addAll(bestItemsCovered);
            plan.stops.add(stop);
            plan.totalDistanceMeters += bestDist;

            // Remove covered items from remaining
            for (String supplied : bestItemsCovered) {
                String cleanItem = supplied.split(" ")[0];
                remaining.remove(cleanItem);
                plan.fulfilledItems.add(cleanItem);
            }

            currLat = bestWh.latitude;
            currLon = bestWh.longitude;
        }

        plan.unfulfilledItems.addAll(remaining);

        // Final leg: from last warehouse to victim
        double finalLeg = haversine(currLat, currLon, victimLat, victimLon);
        RoutePlan.RouteStop victimStop = new RoutePlan.RouteStop("Victim Distress Location", victimLat, victimLon);
        victimStop.distanceFromPrevMeters = finalLeg;
        plan.stops.add(victimStop);
        plan.totalDistanceMeters += finalLeg;

        // Build one-line justification summary (for human-in-the-loop responder)
        if (plan.stops.size() > 2) {
            List<String> whNames = new ArrayList<>();
            for (int i = 1; i < plan.stops.size() - 1; i++) {
                whNames.add(plan.stops.get(i).label.split(" \\(")[0]);
            }
            plan.summaryText = "Route via " + String.join(" -> ", whNames) + " for " +
                    String.join(", ", plan.fulfilledItems) + " (" + formatDist(plan.totalDistanceMeters) + " total)";
        } else {
            plan.summaryText = "Direct dispatch to victim (" + formatDist(plan.totalDistanceMeters) + ")";
        }

        return plan;
    }

    private RoutePlan planStreetRoute(List<String> neededItems, double lat, double lon, double endLat, double endLon) {
        RoutePlan plan = new RoutePlan();
        plan.stops.add(new RoutePlan.RouteStop("Responder", lat, lon));
        Set<String> remaining = new LinkedHashSet<>();
        if (neededItems != null) for (String item : neededItems) remaining.add(normalizeItem(item));
        Collection<HazardReport> hazards = hazardSource.get();
        long now = System.currentTimeMillis();
        HazardRouter router = new HazardRouter(streetMap);
        Set<String> visited = new HashSet<>();
        while (!remaining.isEmpty()) {
            Warehouse best = null; HazardRouter.Route bestRoute = null;
            for (Warehouse warehouse : warehouses.values()) {
                if (visited.contains(warehouse.id) || remaining.stream().noneMatch(warehouse::hasStock)) continue;
                HazardRouter.Route route = router.route(lat, lon, warehouse.latitude, warehouse.longitude, hazards, now);
                HazardRouter.Route exit = router.route(warehouse.latitude, warehouse.longitude, endLat, endLon, hazards, now);
                if (route.reachable && exit.reachable && (bestRoute == null || route.meters < bestRoute.meters)) {
                    best = warehouse; bestRoute = route;
                }
            }
            if (best == null) break;
            visited.add(best.id);
            RoutePlan.RouteStop stop = new RoutePlan.RouteStop(best.name, best.latitude, best.longitude);
            stop.distanceFromPrevMeters = bestRoute.meters;
            for (String item : new ArrayList<>(remaining)) if (best.hasStock(item)) {
                stop.collectItems.add(item + " (" + best.getStock(item) + " available)");
                remaining.remove(item); plan.fulfilledItems.add(item);
            }
            plan.stops.add(stop); plan.streetEdges.addAll(bestRoute.edges);
            plan.totalDistanceMeters += bestRoute.meters; lat = best.latitude; lon = best.longitude;
        }
        plan.unfulfilledItems.addAll(remaining);
        HazardRouter.Route last = router.route(lat, lon, endLat, endLon, hazards, now);
        if (!last.reachable) {
            plan.reachable = false; plan.streetEdges.clear(); plan.totalDistanceMeters = 0;
            plan.summaryText = "NO ROUTE: " + last.reason; return plan;
        }
        plan.streetEdges.addAll(last.edges); plan.totalDistanceMeters += last.meters;
        RoutePlan.RouteStop end = new RoutePlan.RouteStop("Incident", endLat, endLon);
        end.distanceFromPrevMeters = last.meters; plan.stops.add(end);
        plan.summaryText = (plan.stops.size() > 2 ? "Route via " + (plan.stops.size() - 2) + " supply depot(s)" : "Walking route")
                + " · " + formatDist(plan.totalDistanceMeters) + " · known closures avoided";
        if (!remaining.isEmpty()) plan.summaryText += " · supplies unavailable: " + String.join(", ", remaining);
        return plan;
    }

    private static String normalizeItem(String item) {
        if (item == null) return "";
        String s = item.toLowerCase(Locale.US).trim();
        if (s.contains("insulin")) return "insulin";
        if (s.contains("boat") || s.contains("raft")) return "boat";
        if (s.contains("water") || s.contains("drink")) return "water";
        if (s.equals("rescue")) return "boat";
        if (s.contains("first aid") || s.contains("medic")) return "medical";
        if (s.contains("generator") || s.contains("power")) return "power";
        if (s.contains("blanket") || s.contains("tent") || s.contains("shelter")) return "shelter";
        return s;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String formatDist(double meters) {
        if (meters < 1000) return Math.round(meters) + "m";
        return String.format(Locale.US, "%.1fkm", meters / 1000.0);
    }
}
