package org.ghostnet.mesh;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import org.ghostnet.mesh.core.*;
import java.io.*;
import java.util.*;
import java.util.function.*;

/** Owns persisted road observations, reconnect sync, and the offline map screen. UI thread only. */
public final class MapController {
    public static final double RESPONDER_LAT = 37.7740, RESPONDER_LON = -122.4250;
    public final StreetMap map;
    public final WarehouseManager warehouses;
    private final Activity activity;
    private final String nodeId;
    private final Supplier<MeshModule> module;
    private final Consumer<String> log;
    private final HazardLedger ledger = new HazardLedger();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TacticalMapView mapView;
    private TextView routeText, selectedText, hazardCount, reportList;
    private StreetMap.Edge selected;
    private WarehouseManager.RoutePlan currentPlan;
    private List<String> needs = new ArrayList<>(Arrays.asList("insulin", "boat"));
    private double incidentLat = 37.7791, incidentLon = -122.4136;
    private double destinationLat = incidentLat, destinationLon = incidentLon;
    private String destinationName = "Demo incident";
    private int lastActiveCount = -1;
    private final Runnable expire = new Runnable() {
        @Override public void run() {
            int count = ledger.active(System.currentTimeMillis()).size();
            if (count != lastActiveCount) refresh();
            main.postDelayed(this, 15000);
        }
    };
    public MapController(Activity activity, String nodeId, Supplier<MeshModule> module, Consumer<String> log) throws IOException {
        this.activity = activity; this.nodeId = nodeId; this.module = module; this.log = log;
        try (Reader input = new InputStreamReader(activity.getAssets().open("maps/sf-civic.tsv"), java.nio.charset.StandardCharsets.UTF_8)) {
            map = StreetMap.load(input);
        }
        warehouses = WarehouseManager.demo(map);
        warehouses.configureRouting(map, () -> ledger.active(System.currentTimeMillis()));
        restore(); main.post(expire);
    }
    public double incidentLat() { return incidentLat; }
    public double incidentLon() { return incidentLon; }
    private int dp(float n) { return (int)(n * activity.getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(dp(14), dp(6), dp(14), dp(6)); return view;
    }
    private Button button(String title, Runnable action) {
        Button b = new Button(activity); b.setText(title); b.setTextSize(11); b.setAllCaps(false);
        b.setTextColor(0xFFE0EAF5); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF22384B));
        b.setOnClickListener(v -> action.run()); return b;
    }
    public View createView() {
        LinearLayout page = new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(0xFF101923);
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("TACTICAL MAP", 20, 0xFFE9F1F7); title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        hazardCount = text("0 closures", 12, 0xFF58E1C1); heading.addView(hazardCount); page.addView(heading);
        page.addView(text("Civic Center, San Francisco · offline street data\nDemo responder, incident and warehouses", 11, 0xFF93A9BA));
        LinearLayout shortcuts = new LinearLayout(activity); shortcuts.setPadding(dp(10), 0, dp(10), 0);
        shortcuts.addView(button("Incident route", () -> { destinationLat = incidentLat; destinationLon = incidentLon; destinationName = "Incident"; refresh(); }), new LinearLayout.LayoutParams(0, -2, 1));
        shortcuts.addView(button("Warehouses", this::showWarehouses), new LinearLayout.LayoutParams(0, -2, 1));
        shortcuts.addView(button("Reports", this::showReports), new LinearLayout.LayoutParams(0, -2, 1)); page.addView(shortcuts);
        FrameLayout frame = new FrameLayout(activity);
        mapView = new TacticalMapView(activity, map, warehouses.getAllWarehouses());
        mapView.setListener(new TacticalMapView.Listener() {
            public void onRoad(StreetMap.Edge road) { selected = road; mapView.setSelected(road); selectedText.setText(road.name + " · selected segment\nTap Report hazard to mark this section closed"); }
            public void onWarehouse(Warehouse w) { showWarehouse(w); }
        });
        frame.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout zoom = new LinearLayout(activity); zoom.setOrientation(LinearLayout.VERTICAL);
        zoom.addView(button("+", () -> mapView.zoom(1.4))); zoom.addView(button("−", () -> mapView.zoom(1 / 1.4)));
        zoom.addView(button("Fit", () -> mapView.resetCamera()));
        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(dp(56), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        zoomParams.setMarginEnd(dp(6)); frame.addView(zoom, zoomParams);
        page.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(text("━ Teal: route    ┅ Red: closed    W: warehouse\nTap a street · pinch to zoom · drag to pan", 10, 0xFFAAC0CF));
        LinearLayout reportRow = new LinearLayout(activity); reportRow.setGravity(Gravity.CENTER_VERTICAL);
        selectedText = text("Tap a street to report\na hazard on that segment", 12, 0xFFE2EBF3);
        reportRow.addView(selectedText, new LinearLayout.LayoutParams(0, -2, 1));
        reportRow.addView(button("Report hazard", this::reportDialog)); page.addView(reportRow);
        routeText = text("Calculating street route…", 12, 0xFF58E1C1);
        routeText.setMaxLines(4); routeText.setOnClickListener(v -> showRouteDetails()); page.addView(routeText);
        TextView credit = text("Map data © OpenStreetMap contributors · ODbL", 9, 0xFF93A9BA);
        credit.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle("Offline map data")
                .setMessage("Street names, intersections, parks and buildings are from OpenStreetMap.\nData: Open Database License (ODbL).\nhttps://www.openstreetmap.org/copyright\n\nThis downloaded walking network covers Civic Center, San Francisco. Warehouses, stock and initial positions are demo data. Routes avoid reported hazards; unreported conditions are unknown.")
                .setPositiveButton("OK", null).show()); page.addView(credit);
        refresh(); return page;
    }
    private void reportDialog() {
        if (selected == null) { Toast.makeText(activity, "Tap a street segment first", Toast.LENGTH_SHORT).show(); return; }
        final StreetMap.Edge road = selected;
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(18), dp(8), dp(18), 0);
        form.addView(text(road.name + "\nCloses this segment; nearby roads inside the danger radius are also avoided.", 13, Color.DKGRAY));
        Spinner kind = new Spinner(activity);
        kind.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"Fire · 40 m", "Blocked road · 12 m", "Flood · 55 m", "Debris · 12 m"})); form.addView(kind);
        EditText note = new EditText(activity); note.setHint("Optional details (max 200 characters)");
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)}); form.addView(note);
        new AlertDialog.Builder(activity).setTitle("Report street hazard").setView(form)
                .setPositiveButton("Block & share", (d, w) -> {
                    HazardReport report = HazardReport.create(nodeId, map, road, HazardReport.Type.values()[kind.getSelectedItemPosition()], note.getText().toString(), System.currentTimeMillis());
                    if (!ledger.apply(report, System.currentTimeMillis())) { Toast.makeText(activity, "Report limit reached", Toast.LENGTH_LONG).show(); return; }
                    persist(); refresh(); publish(report, MeshEvent.BROADCAST);
                    Toast.makeText(activity, module.get() == null ? "Street closed locally; report will share when mesh starts" : "Street closed; report queued for nearby phones", Toast.LENGTH_LONG).show();
                }).setNegativeButton("Cancel", null).show();
    }
    public boolean receive(MeshEvent event) {
        if (!HazardReport.isHazard(event.payload)) return false;
        try {
            HazardReport h = HazardReport.decode(event.payload);
            if (event.type != MeshEvent.Type.OBSERVATION || !h.mapId.equals(map.id) || !map.edges.containsKey(h.edgeId)) return true;
            // Relayed snapshots can have a different envelope origin; original reporter is preserved.
            // Identities are unverified in this prototype, so show them as community reports.
            if (ledger.apply(h, System.currentTimeMillis())) {
                persist(); refresh(); log.accept("MAP: " + h.street + (h.cleared ? " cleared" : " closed: " + h.type));
            }
        } catch (IOException | RuntimeException e) { log.accept("Rejected map report: " + e.getMessage()); }
        return true;
    }
    public void sync(String peer) { for (HazardReport h : ledger.snapshots(System.currentTimeMillis())) publish(h, peer); }
    private void publish(HazardReport h, String recipient) {
        MeshModule current = module.get(); if (current == null) return;
        long remaining = h.expiresAt - System.currentTimeMillis();
        if (remaining > 0) current.publish(recipient, MeshEvent.Type.OBSERVATION, h.encode(), Math.min(remaining, 300000), 8, 9);
    }
    public void showIncident(CascadeEmergency emergency) {
        if (emergency.senderLat == null || emergency.senderLon == null) return;
        incidentLat = emergency.senderLat; incidentLon = emergency.senderLon;
        destinationLat = incidentLat; destinationLon = incidentLon; destinationName = "Incident";
        needs = new ArrayList<>(emergency.needs);
        if (emergency.hazards.contains("flood") && !needs.contains("boat")) needs.add("boat"); refresh();
    }
    private void refresh() {
        if (mapView == null) return;
        long now = System.currentTimeMillis(); List<HazardReport> active = ledger.active(now); lastActiveCount = active.size();
        List<String> request = destinationName.contains("Depot") ? Collections.emptyList() : needs;
        currentPlan = warehouses.planRoute(request, RESPONDER_LAT, RESPONDER_LON, destinationLat, destinationLon);
        mapView.update(active, currentPlan, RESPONDER_LAT, RESPONDER_LON, destinationLat, destinationLon);
        int closed = new HazardRouter(map).blockedEdges(active, now).size();
        hazardCount.setText(active.size() + " reports · " + closed + " closed");
        hazardCount.setTextColor(active.isEmpty() ? 0xFF58E1C1 : 0xFFFF8B83);
        routeText.setText((currentPlan.reachable ? "↳ " : "⛔ ") + destinationName + "\n" + currentPlan.summaryText + "\nTap for street-by-street directions");
        routeText.setTextColor(currentPlan.reachable ? 0xFF58E1C1 : 0xFFFF8B83);
        if (reportList != null) reportList.setText(reportSummary());
    }
    private void showRouteDetails() {
        StringBuilder content = new StringBuilder(currentPlan.summaryText).append("\n\n");
        String previous = "";
        for (StreetMap.Edge e : currentPlan.streetEdges) if (!e.name.equals(previous)) { content.append("• ").append(e.name).append('\n'); previous = e.name; }
        content.append("\nWalking network only. Reports are unverified community observations. A route may still have unreported dangers.");
        new AlertDialog.Builder(activity).setTitle("Route to " + destinationName).setMessage(content).setPositiveButton("OK", null).show();
    }
    private void showWarehouses() {
        Warehouse[] list = warehouses.getAllWarehouses().toArray(new Warehouse[0]);
        String[] names = new String[list.length]; for (int i = 0; i < list.length; i++) names[i] = "W" + (i + 1) + " · " + list[i].name;
        new AlertDialog.Builder(activity).setTitle("2 demo supply depots").setItems(names, (d, which) -> showWarehouse(list[which])).setNegativeButton("Close", null).show();
    }
    private void showWarehouse(Warehouse w) {
        StringBuilder content = new StringBuilder("DEMO WAREHOUSE · limited stock\n\n");
        for (Map.Entry<String, Integer> item : new TreeMap<>(w.getInventory()).entrySet()) content.append(item.getKey().replace('_', ' ')).append("   × ").append(item.getValue()).append('\n');
        StreetMap.Node nearest = map.nearestNode(w.latitude, w.longitude);
        for (StreetMap.Edge edge : map.edges.values()) if (edge.a == nearest || edge.b == nearest) { content.append("\nNear ").append(edge.name); break; }
        new AlertDialog.Builder(activity).setTitle(w.name).setMessage(content)
                .setPositiveButton("Route here", (d, which) -> { destinationLat = w.latitude; destinationLon = w.longitude; destinationName = w.name; refresh(); })
                .setNegativeButton("Close", null).show();
    }
    private String reportSummary() {
        List<HazardReport> reports = ledger.active(System.currentTimeMillis());
        if (reports.isEmpty()) return "No active reports. Tap a road and choose Report hazard.";
        StringBuilder out = new StringBuilder();
        for (HazardReport h : reports) {
            long minutes = Math.max(1, (h.expiresAt - System.currentTimeMillis()) / 60000);
            out.append(h.type).append(" · ").append(h.street).append("\n").append(h.note).append("\n")
                    .append("Unverified · expires in ").append(minutes).append(" min · ")
                    .append(h.reporter.equals(nodeId) ? "your report" : "peer " + h.reporter.substring(0, 8)).append("\n\n");
        }
        return out.toString();
    }
    private void showReports() {
        List<HazardReport> own = new ArrayList<>();
        for (HazardReport h : ledger.active(System.currentTimeMillis())) if (h.reporter.equals(nodeId)) own.add(h);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        reportList = text(reportSummary(), 13, Color.DKGRAY); body.addView(reportList);
        if (!own.isEmpty()) {
            Button clear = new Button(activity); clear.setText("Clear one of my reports"); body.addView(clear);
            clear.setOnClickListener(v -> {
                String[] names = new String[own.size()]; for (int i = 0; i < own.size(); i++) names[i] = own.get(i).type + " · " + own.get(i).street;
                new AlertDialog.Builder(activity).setTitle("Which hazard is cleared?").setItems(names, (d, which) -> {
                    HazardReport original = own.get(which);
                    if (!original.active(System.currentTimeMillis())) return;
                    HazardReport cleared = original.clear(System.currentTimeMillis());
                    if (ledger.apply(cleared, System.currentTimeMillis())) { persist(); refresh(); publish(cleared, MeshEvent.BROADCAST); }
                }).setNegativeButton("Cancel", null).show();
            });
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        new AlertDialog.Builder(activity).setTitle("Community road reports").setView(scroll).setPositiveButton("Done", null)
                .setOnDismissListener(d -> reportList = null).show();
    }
    private void persist() {
        Set<String> encoded = new HashSet<>();
        for (HazardReport h : ledger.snapshots(System.currentTimeMillis())) encoded.add(Base64.encodeToString(h.encode(), Base64.NO_WRAP));
        activity.getPreferences(Activity.MODE_PRIVATE).edit().putStringSet("hazard-reports-v1", encoded).apply();
    }
    private void restore() {
        for (String stored : activity.getPreferences(Activity.MODE_PRIVATE).getStringSet("hazard-reports-v1", Collections.emptySet())) {
            try { HazardReport h = HazardReport.decode(Base64.decode(stored, Base64.NO_WRAP));
                if (h.mapId.equals(map.id) && map.edges.containsKey(h.edgeId)) ledger.apply(h, System.currentTimeMillis());
            } catch (RuntimeException | IOException ignored) { }
        }
    }
    public void destroy() { main.removeCallbacksAndMessages(null); }
}
