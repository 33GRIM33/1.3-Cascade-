package org.ghostnet.mesh.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** Compatibility policy for the observed Nearby service requirements; no Android dependency. */
public final class NearbyPermissionPolicy {
    private NearbyPermissionPolicy() { }
    public static final String COARSE = "android.permission.ACCESS_COARSE_LOCATION";
    public static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
    public static List<String> required(int sdk) {
        List<String> permissions = new ArrayList<>(Arrays.asList(COARSE, FINE));
        if (sdk >= 31) permissions.addAll(Arrays.asList("android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"));
        if (sdk >= 33) permissions.add("android.permission.NEARBY_WIFI_DEVICES");
        return permissions;
    }
    public static List<String> missing(int sdk, Predicate<String> granted) {
        List<String> missing = new ArrayList<>();
        for (String permission : required(sdk)) if (!granted.test(permission)) missing.add(permission);
        return missing;
    }
    public static String[] request(int sdk, Predicate<String> granted) {
        List<String> request = missing(sdk, granted);
        // Android 12+ may ignore a fine-only request, including a precision upgrade.
        if (request.contains(FINE) && !request.contains(COARSE)) request.add(0, COARSE);
        return request.toArray(new String[0]);
    }
}
