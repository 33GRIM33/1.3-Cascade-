package org.ghostnet.mesh.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a marked supply warehouse or relief depot in the disaster zone.
 * Tracks geographic location and real-time inventory counts for emergency supplies.
 */
public final class Warehouse {
    public final String id;
    public final String name;
    public final double latitude;
    public final double longitude;
    private final Map<String, Integer> inventory;

    public Warehouse(String id, String name, double latitude, double longitude, Map<String, Integer> inventory) {
        this.id = id;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.inventory = new HashMap<>(inventory != null ? inventory : Collections.emptyMap());
    }

    public Map<String, Integer> getInventory() {
        return Collections.unmodifiableMap(inventory);
    }

    public int getStock(String item) {
        if (item == null) return 0;
        Integer count = inventory.get(item.toLowerCase(java.util.Locale.US));
        return count != null ? count : 0;
    }

    public boolean hasStock(String item) {
        return getStock(item) > 0;
    }

    public void updateStock(String item, int delta) {
        if (item == null) return;
        String key = item.toLowerCase(java.util.Locale.US);
        int current = getStock(key);
        int updated = Math.max(0, current + delta);
        inventory.put(key, updated);
    }

    /**
     * Calculates offline distance in meters to a target coordinate using the Haversine formula.
     */
    public double distanceTo(double targetLat, double targetLon) {
        double earthRadiusMeters = 6371000.0;
        double dLat = Math.toRadians(targetLat - latitude);
        double dLon = Math.toRadians(targetLon - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(targetLat)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMeters * c;
    }

    @Override
    public String toString() {
        return name + " (" + id + ") @" + String.format(java.util.Locale.US, "%.4f,%.4f", latitude, longitude);
    }
}
