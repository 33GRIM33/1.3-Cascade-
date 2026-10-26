package org.ghostnet.mesh.core;

import java.util.*;

/** One record per report, including clearance tombstones for reconnect reconciliation. */
public final class HazardLedger {
    private final Map<String, HazardReport> records = new LinkedHashMap<>();
    public boolean apply(HazardReport next, long now) {
        if (next.expiresAt <= now || next.updatedAt > now + 300000) return false;
        HazardReport old = records.get(next.id);
        if (old != null && (!old.reporter.equals(next.reporter) || !old.mapId.equals(next.mapId)
                || !old.edgeId.equals(next.edgeId) || old.createdAt != next.createdAt || old.cleared
                || next.updatedAt <= old.updatedAt)) return false;
        if (old == null && records.size() >= 128) {
            records.values().removeIf(h -> h.expiresAt <= now);
            if (records.size() >= 128) return false;
        }
        records.put(next.id, next); return true;
    }
    public List<HazardReport> active(long now) {
        List<HazardReport> result = new ArrayList<>();
        for (HazardReport h : records.values()) if (h.active(now)) result.add(h);
        return result;
    }
    public List<HazardReport> snapshots(long now) {
        List<HazardReport> result = new ArrayList<>();
        for (HazardReport h : records.values()) if (h.expiresAt > now) result.add(h);
        return result;
    }
}
