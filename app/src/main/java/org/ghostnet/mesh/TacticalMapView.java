package org.ghostnet.mesh;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import org.ghostnet.mesh.core.*;
import java.util.*;

/** Offline vector renderer: actual bundled OSM geometry, labels, walking routes and closures. */
public final class TacticalMapView extends View {
    public interface Listener { void onRoad(StreetMap.Edge road); void onWarehouse(Warehouse warehouse); }
    private final StreetMap map;
    private final Collection<Warehouse> warehouses;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;
    private final ScaleGestureDetector scaleDetector;
    private final List<StreetMap.Edge> labelCandidates;
    private final List<RectF> labelBoxes = new ArrayList<>();
    private Collection<HazardReport> hazards = Collections.emptyList();
    private Set<String> blocked = Collections.emptySet();
    private List<StreetMap.Edge> route = Collections.emptyList();
    private StreetMap.Edge selected;
    private Listener listener;
    private double centerLat = 37.7765, centerLon = -122.419, metersPerDp = 2.5;
    private double startLat, startLon, endLat, endLon;
    private float downX, downY, lastX, lastY;
    private boolean moved, pinched;

    public TacticalMapView(Context context, StreetMap map, Collection<Warehouse> warehouses) {
        super(context); this.map = map; this.warehouses = warehouses; dp = getResources().getDisplayMetrics().density;
        labelCandidates = new ArrayList<>(map.edges.values());
        labelCandidates.sort(Comparator.comparingDouble((StreetMap.Edge e) -> e.meters).reversed());
        setContentDescription("Offline street map. Tap a road to report fire, blockage, flood or debris. Pinch to zoom and drag to pan.");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                pinched = true; zoom(detector.getScaleFactor()); return true;
            }
        });
    }
    public void setListener(Listener listener) { this.listener = listener; }
    public void setSelected(StreetMap.Edge selected) { this.selected = selected; invalidate(); }
    public void update(Collection<HazardReport> hazards, WarehouseManager.RoutePlan plan,
                       double startLat, double startLon, double endLat, double endLon) {
        this.hazards = hazards; blocked = new HazardRouter(map).blockedEdges(hazards, System.currentTimeMillis());
        route = plan != null && plan.reachable ? plan.streetEdges : Collections.emptyList();
        this.startLat = startLat; this.startLon = startLon; this.endLat = endLat; this.endLon = endLon; invalidate();
    }
    public void zoom(double factor) { metersPerDp = Math.max(0.25, Math.min(9, metersPerDp / factor)); invalidate(); }
    public void resetCamera() {
        if (getWidth() == 0 || getHeight() == 0) return;
        double minLat = Math.min(startLat, endLat), maxLat = Math.max(startLat, endLat);
        double minLon = Math.min(startLon, endLon), maxLon = Math.max(startLon, endLon);
        for (Warehouse w : warehouses) {
            minLat = Math.min(minLat, w.latitude); maxLat = Math.max(maxLat, w.latitude);
            minLon = Math.min(minLon, w.longitude); maxLon = Math.max(maxLon, w.longitude);
        }
        for (StreetMap.Edge e : route) {
            minLat = Math.min(minLat, Math.min(e.a.lat, e.b.lat)); maxLat = Math.max(maxLat, Math.max(e.a.lat, e.b.lat));
            minLon = Math.min(minLon, Math.min(e.a.lon, e.b.lon)); maxLon = Math.max(maxLon, Math.max(e.a.lon, e.b.lon));
        }
        centerLat = (minLat + maxLat) / 2; centerLon = (minLon + maxLon) / 2;
        double widthMeters = (maxLon - minLon) * 111195 * Math.cos(Math.toRadians(centerLat)) + 160;
        double heightMeters = (maxLat - minLat) * 111195 + 160;
        metersPerDp = Math.max(1, Math.max(widthMeters / (getWidth() / dp), heightMeters / (getHeight() / dp)));
        invalidate();
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); if (oldw == 0 || oldh == 0) resetCamera();
    }
    private float x(double lon) { return getWidth() / 2f + (float)((lon - centerLon) * 111195 * Math.cos(Math.toRadians(centerLat)) / metersPerDp * dp); }
    private float y(double lat) { return getHeight() / 2f - (float)((lat - centerLat) * 111195 / metersPerDp * dp); }
    private boolean visible(float x, float y) { return x > -120 * dp && x < getWidth() + 120 * dp && y > -120 * dp && y < getHeight() + 120 * dp; }
    private void stroke(int color, float width) {
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width * dp);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setPathEffect(null);
    }
    private void fill(int color) { paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setPathEffect(null); }
    private void edge(Canvas canvas, StreetMap.Edge e) { canvas.drawLine(x(e.a.lon), y(e.a.lat), x(e.b.lon), y(e.b.lat), paint); }
    private boolean mainRoad(StreetMap.Edge e) { return e.kind.equals("primary") || e.kind.equals("secondary") || e.kind.equals("tertiary"); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(16, 25, 36));
        for (StreetMap.Area a : map.areas) {
            if (a.outline.isEmpty()) continue;
            StreetMap.Node first = a.outline.get(0);
            if (!visible(x(first.lon), y(first.lat))) continue;
            path.reset(); path.moveTo(x(first.lon), y(first.lat));
            for (int i = 1; i < a.outline.size(); i++) { StreetMap.Node n = a.outline.get(i); path.lineTo(x(n.lon), y(n.lat)); }
            path.close(); fill(a.kind.equals("park") ? Color.rgb(25, 58, 47) : Color.rgb(29, 43, 57)); canvas.drawPath(path, paint);
            if (a.kind.equals("building") && metersPerDp < 2.8) { stroke(Color.rgb(39, 54, 69), 0.6f); canvas.drawPath(path, paint); }
        }
        // Road casings separate the street network from building footprints.
        for (int pass = 0; pass < 2; pass++) for (StreetMap.Edge e : map.edges.values()) {
            if (!visible(x(e.a.lon), y(e.a.lat)) && !visible(x(e.b.lon), y(e.b.lat))) continue;
            boolean foot = e.kind.equals("footway") || e.kind.equals("path") || e.kind.equals("steps");
            float width = foot ? 1.4f : mainRoad(e) ? 6.5f : 4.5f;
            stroke(pass == 0 ? Color.rgb(11, 18, 27) : foot ? Color.rgb(47, 63, 72) : Color.rgb(64, 80, 95), pass == 0 ? width + 2 : width);
            edge(canvas, e);
        }
        for (HazardReport h : hazards) {
            float radius = (float)(h.radiusMeters / metersPerDp * dp);
            fill(h.type == HazardReport.Type.FLOOD ? 0x303C9CE0 : 0x40FF5A53);
            canvas.drawCircle(x(h.lon), y(h.lat), radius, paint);
            stroke(h.type == HazardReport.Type.FLOOD ? 0xFF4CA8E8 : 0xFFFF7867, 1);
            paint.setPathEffect(new DashPathEffect(new float[]{4 * dp, 4 * dp}, 0));
            canvas.drawCircle(x(h.lon), y(h.lat), radius, paint);
        }
        for (StreetMap.Edge e : route) { stroke(0xFF102D33, 9); edge(canvas, e); }
        for (StreetMap.Edge e : route) { stroke(0xFF58E1C1, 4); edge(canvas, e); }
        for (String id : blocked) {
            StreetMap.Edge e = map.edges.get(id); if (e == null) continue;
            stroke(0xFF391C26, 9); edge(canvas, e); stroke(0xFFFF676A, 4.5f);
            paint.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0)); edge(canvas, e);
        }
        if (selected != null) { stroke(0xFFFFD17A, 10); edge(canvas, selected); stroke(0xFF392E1E, 2); edge(canvas, selected); }
        drawLabels(canvas);
        int i = 0;
        for (Warehouse w : warehouses) marker(canvas, w.latitude, w.longitude, "W" + (++i), 0xFF8BB4FF, w.name);
        if (startLat != 0) marker(canvas, startLat, startLon, "R", 0xFF58E1C1, "Responder");
        if (endLat != 0) marker(canvas, endLat, endLon, "SOS", 0xFFFFCC7B, "Incident");
        for (HazardReport h : hazards) {
            float xx = x(h.lon), yy = y(h.lat);
            fill(0xFFFF676A); canvas.drawCircle(xx, yy, 10 * dp, paint);
            stroke(0xFF24141D, 2); canvas.drawLine(xx - 3 * dp, yy - 3 * dp, xx + 3 * dp, yy + 3 * dp, paint);
            canvas.drawLine(xx - 3 * dp, yy + 3 * dp, xx + 3 * dp, yy - 3 * dp, paint);
        }
        // Compass and a scale that updates when zooming.
        fill(0xCC10202E); canvas.drawRoundRect(getWidth() - 42 * dp, 14 * dp, getWidth() - 12 * dp, 55 * dp, 10 * dp, 10 * dp, paint);
        text(canvas, "N", getWidth() - 27 * dp, 30 * dp, 10, 0xFFB8C9D9);
        text(canvas, "↑", getWidth() - 27 * dp, 47 * dp, 15, 0xFF58E1C1);
        float bar = (float)(100 / metersPerDp * dp);
        stroke(0xFFD8E3EE, 2); canvas.drawLine(16 * dp, getHeight() - 20 * dp, 16 * dp + bar, getHeight() - 20 * dp, paint);
        text(canvas, "100 m", 16 * dp + bar / 2, getHeight() - 27 * dp, 10, 0xFFD8E3EE);
    }
    private void text(Canvas c, String text, float x, float y, float size, int color) {
        fill(color); paint.setTextSize(size * dp); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER); c.drawText(text, x, y, paint);
    }
    private void drawLabels(Canvas canvas) {
        labelBoxes.clear(); Set<String> placed = new HashSet<>();
        for (StreetMap.Edge e : labelCandidates) {
            if (e.name.equals("Walkway") || e.name.equals("Steps") || placed.contains(e.name)) continue;
            float ax = x(e.a.lon), ay = y(e.a.lat), bx = x(e.b.lon), by = y(e.b.lat);
            float mx = (ax + bx) / 2, my = (ay + by) / 2;
            if (mx < 24 * dp || mx > getWidth() - 24 * dp || my < 20 * dp || my > getHeight() - 40 * dp) continue;
            paint.setTextSize(10 * dp); paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            float width = paint.measureText(e.name);
            if (Math.hypot(bx - ax, by - ay) < width * 0.65) continue;
            float angle = (float)Math.toDegrees(Math.atan2(by - ay, bx - ax));
            if (angle > 90 || angle < -90) angle += 180;
            float w = Math.abs((float)Math.cos(Math.toRadians(angle))) * width + 18 * dp;
            float h = Math.abs((float)Math.sin(Math.toRadians(angle))) * width + 16 * dp;
            RectF box = new RectF(mx - w / 2, my - h / 2, mx + w / 2, my + h / 2);
            boolean overlap = false; for (RectF occupied : labelBoxes) if (RectF.intersects(box, occupied)) { overlap = true; break; }
            if (overlap) continue;
            labelBoxes.add(box); placed.add(e.name);
            canvas.save(); canvas.rotate(angle, mx, my); paint.setTextAlign(Paint.Align.CENTER);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3 * dp); paint.setColor(0xFF101B27);
            canvas.drawText(e.name, mx, my + 3 * dp, paint);
            fill(0xFFD5DEE8); canvas.drawText(e.name, mx, my + 3 * dp, paint); canvas.restore();
        }
    }
    private void marker(Canvas c, double lat, double lon, String badge, int color, String title) {
        float xx = x(lon), yy = y(lat); if (!visible(xx, yy)) return;
        fill(0xFF101B27); c.drawCircle(xx, yy, 17 * dp, paint);
        stroke(color, 2); c.drawCircle(xx, yy, 14 * dp, paint);
        text(c, badge, xx, yy + 4 * dp, badge.length() > 2 ? 9 : 11, color);
        if (metersPerDp < 2.3) {
            String label = title.replace(" Depot", ""); paint.setTextSize(10 * dp);
            float width = paint.measureText(label) + 14 * dp; fill(0xED101B27);
            c.drawRoundRect(xx - width / 2, yy + 19 * dp, xx + width / 2, yy + 37 * dp, 5 * dp, 5 * dp, paint);
            text(c, label, xx, yy + 31 * dp, 10, color);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            downX = lastX = event.getX(); downY = lastY = event.getY(); moved = false; pinched = false; return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress() && !pinched) {
            float dx = event.getX() - lastX, dy = event.getY() - lastY;
            if (Math.hypot(event.getX() - downX, event.getY() - downY) > 7 * dp) moved = true;
            if (moved) {
                centerLon -= dx / dp * metersPerDp / (111195 * Math.cos(Math.toRadians(centerLat)));
                centerLat += dy / dp * metersPerDp / 111195;
                centerLat = Math.max(map.south, Math.min(map.north, centerLat));
                centerLon = Math.max(map.west, Math.min(map.east, centerLon)); invalidate();
            }
            lastX = event.getX(); lastY = event.getY();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            getParent().requestDisallowInterceptTouchEvent(false);
            if (!moved && !pinched) { performClick(); selectAt(event.getX(), event.getY()); }
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private void selectAt(float px, float py) {
        for (Warehouse w : warehouses) if (Math.hypot(x(w.longitude) - px, y(w.latitude) - py) < 22 * dp) {
            if (listener != null) listener.onWarehouse(w); return;
        }
        double lat = centerLat - (py - getHeight() / 2f) / dp * metersPerDp / 111195;
        double lon = centerLon + (px - getWidth() / 2f) / dp * metersPerDp / (111195 * Math.cos(Math.toRadians(centerLat)));
        StreetMap.Edge best = null; double nearest = 26 * metersPerDp;
        for (StreetMap.Edge e : map.edges.values()) {
            double distance = StreetMap.segmentDistance(lat, lon, e.a, e.b);
            if (distance < nearest) { best = e; nearest = distance; }
        }
        if (best != null && listener != null) listener.onRoad(best);
    }
}
