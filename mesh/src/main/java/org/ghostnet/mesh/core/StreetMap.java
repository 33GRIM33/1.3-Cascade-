package org.ghostnet.mesh.core;

import java.io.*;
import java.util.*;

/** Bundled OSM walking graph. Intersections use OSM node IDs, never pixel proximity. */
public final class StreetMap {
    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public final Map<String, Edge> edges = new LinkedHashMap<>();
    public final List<Area> areas = new ArrayList<>();
    public String id = "";
    public double south, west, north, east;

    public static final class Node {
        public final String id;
        public final double lat, lon;
        final List<Edge> links = new ArrayList<>();
        public Node(String id, double lat, double lon) { this.id = id; this.lat = lat; this.lon = lon; }
    }
    public static final class Edge {
        public final String id, name, kind;
        public final Node a, b;
        public final double meters;
        Edge(String id, Node a, Node b, String name, String kind) {
            this.id = id; this.a = a; this.b = b; this.name = name; this.kind = kind;
            meters = distance(a.lat, a.lon, b.lat, b.lon);
        }
        Node other(Node n) { return n == a ? b : a; }
    }
    public static final class Area {
        public final String kind, name;
        public final List<Node> outline;
        Area(String kind, String name, List<Node> outline) { this.kind = kind; this.name = name; this.outline = outline; }
    }

    public void addNode(String id, double lat, double lon) { nodes.put(id, new Node(id, lat, lon)); }
    public void addEdge(String id, String a, String b, String name, String kind) {
        Node from = nodes.get(a), to = nodes.get(b);
        if (from == null || to == null || from == to) return;
        Edge e = new Edge(id, from, to, name, kind);
        edges.put(id, e); from.links.add(e); to.links.add(e);
    }
    public static StreetMap load(Reader source) throws IOException {
        StreetMap map = new StreetMap();
        BufferedReader reader = new BufferedReader(source);
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p[0].equals("MAP")) {
                    map.id = p[1]; map.south = Double.parseDouble(p[2]); map.west = Double.parseDouble(p[3]);
                    map.north = Double.parseDouble(p[4]); map.east = Double.parseDouble(p[5]);
                } else if (p[0].equals("N")) map.addNode(p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3]));
                else if (p[0].equals("E")) map.addEdge(p[1], p[2], p[3], p[4], p[5]);
                else if (p[0].equals("A")) {
                    List<Node> outline = new ArrayList<>();
                    for (String pair : p[3].split(";")) {
                        String[] xy = pair.split(",");
                        outline.add(new Node("", Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
                    }
                    if (outline.size() > 2) map.areas.add(new Area(p[1], p[2], outline));
                }
            }
        } catch (RuntimeException ex) { throw new IOException("Invalid street map", ex); }
        if (map.id.isEmpty() || map.edges.isEmpty()) throw new IOException("Empty street map");
        return map;
    }
    public Node nearestNode(double lat, double lon) {
        Node best = null; double distance = Double.POSITIVE_INFINITY;
        for (Node n : nodes.values()) {
            double d = distance(lat, lon, n.lat, n.lon);
            if (!n.links.isEmpty() && d < distance) { best = n; distance = d; }
        }
        return best;
    }
    public static double distance(double lat, double lon, double toLat, double toLon) {
        double x = Math.toRadians(toLon - lon) * Math.cos(Math.toRadians((lat + toLat) / 2));
        double y = Math.toRadians(toLat - lat);
        return Math.hypot(x, y) * 6371000;
    }
    public static double segmentDistance(double lat, double lon, Node a, Node b) {
        double scale = Math.cos(Math.toRadians(lat));
        double ax = (a.lon - lon) * scale * 111195, ay = (a.lat - lat) * 111195;
        double bx = (b.lon - lon) * scale * 111195, by = (b.lat - lat) * 111195;
        double dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / length));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }
}
