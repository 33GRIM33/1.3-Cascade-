package org.ghostnet.mesh;

import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import org.ghostnet.mesh.core.NearbyPermissionPolicy;
import java.util.ArrayList;
import java.util.List;

public final class NearbyPrerequisites {
    private NearbyPrerequisites() { }
    /** No radio operation begins until this entire list is empty. */
    public static List<String> problems(Context context) {
        List<String> issues = new ArrayList<>();
        for (String p : NearbyPermissionPolicy.missing(Build.VERSION.SDK_INT,
                name -> context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED)) {
            issues.add("Allow " + p.substring(p.lastIndexOf('.') + 1));
        }
        for (String p : new String[]{"android.permission.ACCESS_WIFI_STATE",
                "android.permission.CHANGE_WIFI_STATE", "android.permission.ACCESS_NETWORK_STATE"}) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
                issues.add("APK is missing " + p + "; install build 0.2");
        }
        int play = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        if (play != ConnectionResult.SUCCESS) issues.add("Update or enable Google Play services (code " + play + ")");
        if (!issues.isEmpty()) return issues;
        try {
            BluetoothManager bluetooth = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetooth == null || bluetooth.getAdapter() == null) issues.add("This device has no Bluetooth adapter");
            else if (!bluetooth.getAdapter().isEnabled()) issues.add("Turn Bluetooth ON");
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null || !wifi.isWifiEnabled()) issues.add("Turn Wi-Fi ON; no internet connection is needed");
            LocationManager location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            boolean enabled = location != null && (Build.VERSION.SDK_INT >= 28 ? location.isLocationEnabled() :
                    location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if (!enabled) issues.add("Turn Location ON for nearby device discovery");
        } catch (SecurityException ex) { issues.add("Permissions changed; grant Nearby devices and precise Location, then retry"); }
        return issues;
    }
}
