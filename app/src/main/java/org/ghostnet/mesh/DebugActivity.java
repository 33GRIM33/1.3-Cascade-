package org.ghostnet.mesh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.view.View;
import android.view.Gravity;
import android.graphics.Typeface;
import android.widget.*;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.BatteryManager;
import android.speech.RecognizerIntent;
import org.ghostnet.mesh.core.MeshEvent;
import org.ghostnet.mesh.core.MeshModule;
import org.ghostnet.mesh.core.MessageCodec;
import org.ghostnet.mesh.core.NearbyPermissionPolicy;
import org.ghostnet.mesh.core.CascadeEmergency;
import org.ghostnet.mesh.core.CascadeExtractor;
import org.ghostnet.mesh.core.DispatchEngine;
import org.ghostnet.mesh.core.WarehouseManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Small device harness. Keeps the prerequisites and actual transport state visible. */
public final class DebugActivity extends Activity {
    private MeshModule module;
    private TextView log, peerText, status, packetFeed;
    private EditText recipient, blocked, message;
    private Button start, publish;
    private String nodeId;
    private String currentRole = "NORMAL_USER";
    private DispatchEngine dispatchEngine;
    private MapController mapController;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, PacketView> packets = new LinkedHashMap<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());


    private static final class PacketView {
        String id, direction, text, origin, via, recipient, status;
        int hops;
        long timeMs;
    }
    private final Runnable refreshPeers = new Runnable() {
        @Override public void run() {
            if (module != null) peerText.setText("Direct peers: " + module.peers());
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        nodeId = getPreferences(MODE_PRIVATE).getString("nodeId", null);
        if (nodeId == null) {
            nodeId = UUID.randomUUID().toString();
            getPreferences(MODE_PRIVATE).edit().putString("nodeId", nodeId).apply();
        }
        try {
            mapController = new MapController(this, nodeId, () -> module, this::append);
            dispatchEngine = new DispatchEngine(mapController.warehouses);
        } catch (Exception ex) {
            TextView error = new TextView(this);
            error.setText("Could not load bundled offline map: " + ex.getMessage());
            setContentView(error); return;
        }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(0xFF101923);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        column.setPadding(0, pad, 0, pad);
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this); brand.setText("GHOSTNET  /  CASCADE\nOFFLINE RESPONSE · BUILD 0.6");
        brand.setTextColor(0xFFE9F1F7); brand.setTextSize(15); brand.setTypeface(null, Typeface.BOLD);
        brand.setPadding(pad, pad / 2, pad, pad / 2);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        start = button("Start mesh"); start.setTextSize(11); top.addView(start); column.addView(top);
        status = new TextView(this); status.setText("Mesh stopped · map works offline");
        status.setTextSize(11); status.setTextColor(0xFF93A9BA); status.setPadding(pad, 0, pad, pad / 2); column.addView(status);
        FrameLayout pages = new FrameLayout(this);
        View mapPage = mapController.createView();
        LinearLayout messageColumn = new LinearLayout(this); messageColumn.setOrientation(LinearLayout.VERTICAL);
        messageColumn.setPadding(pad, pad, pad, pad);
        ScrollView messagePage = new ScrollView(this); messagePage.addView(messageColumn); messagePage.setVisibility(View.GONE);
        LinearLayout networkColumn = new LinearLayout(this); networkColumn.setOrientation(LinearLayout.VERTICAL);
        networkColumn.setPadding(pad, pad, pad, pad);
        ScrollView networkPage = new ScrollView(this); networkPage.addView(networkColumn); networkPage.setVisibility(View.GONE);
        LinearLayout tabs = new LinearLayout(this);
        String[] names = {"Map", "Messages", "Network"}; View[] surfaces = {mapPage, messagePage, networkPage};
        for (int i = 0; i < names.length; i++) {
            final int index = i; Button tab = button(names[i]); tab.setTextSize(12);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
            tab.setOnClickListener(v -> { for (int j = 0; j < surfaces.length; j++) surfaces[j].setVisibility(j == index ? View.VISIBLE : View.GONE); });
        }
        column.addView(tabs); pages.addView(mapPage); pages.addView(messagePage); pages.addView(networkPage);
        column.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView own = new TextView(this);
        own.setText("GHOSTNET • Build 0.6\nNode ID:\n" + nodeId); own.setTextColor(0xFFE9F1F7);
        own.setTextIsSelectable(true); networkColumn.addView(own);
        Button copy = button("Copy my node ID"); networkColumn.addView(copy);
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Node ID", nodeId));
            Toast.makeText(this, "Node ID copied", Toast.LENGTH_SHORT).show();
        });
        TextView help = new TextView(this);
        help.setText("Enable Bluetooth, Wi-Fi and Location. Tap Start once; nearby GHOSTNET phones connect automatically. Internet is not needed.");
        help.setTextColor(0xFFAAC0CF); networkColumn.addView(help);

        currentRole = getPreferences(MODE_PRIVATE).getString("role", "NORMAL_USER");
        TextView roleTitle = new TextView(this);
        roleTitle.setText("Participant Role:");
        roleTitle.setTextColor(0xFFE9F1F7); networkColumn.addView(roleTitle);
        RadioGroup roleGroup = new RadioGroup(this);
        roleGroup.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton rbCitizen = new RadioButton(this);
        rbCitizen.setId(View.generateViewId()); rbCitizen.setTextColor(0xFFE9F1F7);
        rbCitizen.setText("Citizen (Victim) ");
        RadioButton rbResponder = new RadioButton(this);
        rbResponder.setId(View.generateViewId()); rbResponder.setTextColor(0xFFE9F1F7);
        rbResponder.setText("First Responder");
        roleGroup.addView(rbCitizen);
        roleGroup.addView(rbResponder);
        roleGroup.check("FIRST_RESPONDER".equals(currentRole) ? rbResponder.getId() : rbCitizen.getId());
        roleGroup.setOnCheckedChangeListener((group, id) -> {
            currentRole = id == rbResponder.getId() ? "FIRST_RESPONDER" : "NORMAL_USER";
            getPreferences(MODE_PRIVATE).edit().putString("role", currentRole).apply();
            Toast.makeText(this, "Active Mode: " + (currentRole.equals("FIRST_RESPONDER") ? "First Responder" : "Citizen / Victim"), Toast.LENGTH_SHORT).show();
        });
        networkColumn.addView(roleGroup);

        blocked = input("Optional: blocked direct peer ID (A↔C test)");
        blocked.setText(getPreferences(MODE_PRIVATE).getString("blocked", "")); networkColumn.addView(blocked);
        peerText = new TextView(this); peerText.setText("Direct peers: []"); peerText.setTextColor(0xFFAAC0CF); networkColumn.addView(peerText);
        recipient = input("Recipient node ID, or * for broadcast");
        recipient.setText(getPreferences(MODE_PRIVATE).getString("recipient", "*")); messageColumn.addView(recipient);

        LinearLayout msgRow = new LinearLayout(this);
        msgRow.setOrientation(LinearLayout.HORIZONTAL);
        message = input("Distress text / needed supplies");
        msgRow.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
        Button voiceBtn = button("🎙️ Voice");
        voiceBtn.setOnClickListener(v -> startVoiceRecognition());
        msgRow.addView(voiceBtn);
        messageColumn.addView(msgRow);

        publish = button("Publish DISTRESS"); publish.setEnabled(false); messageColumn.addView(publish);

        TextView packetTitle = new TextView(this);
        packetTitle.setText("\nMESSAGE PACKETS");
        packetTitle.setTextSize(18); packetTitle.setTextColor(0xFFE9F1F7); messageColumn.addView(packetTitle);
        packetFeed = new TextView(this);
        packetFeed.setText("No messages yet.\nSent and received packets will appear here.");
        packetFeed.setTextIsSelectable(true); packetFeed.setTextColor(0xFFBFD2E1); messageColumn.addView(packetFeed);

        TextView logTitle = new TextView(this);
        logTitle.setText("\nTECHNICAL LOG");
        logTitle.setTextSize(14); logTitle.setTextColor(0xFFE9F1F7); networkColumn.addView(logTitle);
        log = new TextView(this); log.setTextIsSelectable(true); log.setTextColor(0xFF93A9BA); log.setTextSize(11);
        networkColumn.addView(log);
        setContentView(column);
        start.setOnClickListener(v -> { if (module == null) ensurePermissionsThenStart(); else stopModule("Stopped"); });
        publish.setOnClickListener(v -> publish());
        main.post(refreshPeers);
    }

    private void publish() {
        if (module == null) return;
        String to = recipient.getText().toString().trim();
        if (!(to.equals("*") || MessageCodec.validNode(to))) {
            recipient.setError("Enter the destination's node ID, or * for broadcast"); return;
        }
        if (to.equals(nodeId)) { recipient.setError("Use another phone's ID"); return; }
        String body = message.getText().toString().trim();
        if (body.isEmpty()) { message.setError("Enter an emergency description (1–1400 UTF-8 bytes)"); return; }
        
        // Offline CASCADE AI extraction with role & battery constraint
        int battery = getBatteryPercentage();
        CascadeEmergency emergency = CascadeExtractor.extract(body, currentRole, battery, mapController.incidentLat(), mapController.incidentLon(), false);
        byte[] bytes = emergency.toJsonBytes();
        if (bytes.length > 1800) { message.setError("Message payload exceeds wire limit"); return; }
        getPreferences(MODE_PRIVATE).edit().putString("recipient", to).apply();
        String id = module.publish(to, MeshEvent.Type.DISTRESS, bytes, 300000, 4, 10);
        PacketView packet = new PacketView();
        packet.id = id; packet.direction = "SENT";
        DispatchEngine.EvaluationResult localEval = dispatchEngine.evaluate(emergency, nodeId, 4, MapController.RESPONDER_LAT, MapController.RESPONDER_LON);
        mapController.showIncident(emergency);
        packet.text = formatEmergencySummary(emergency, localEval);
        packet.origin = nodeId; packet.via = nodeId; packet.recipient = to;
        packet.hops = 4; packet.status = "QUEUED"; packet.timeMs = System.currentTimeMillis();
        packets.put(id, packet); trimPackets(); renderPackets();
        append("Submitted id=" + id + " to=" + to + " (" + emergency.intent + "/" + emergency.urgency + ")");
        message.setText("");
    }

    private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private void ensurePermissionsThenStart() {
        String block = blocked.getText().toString().trim();
        if (!block.isEmpty() && (!MessageCodec.validNode(block) || block.equals(nodeId))) {
            blocked.setError("Enter another phone's full node ID or leave empty"); return;
        }
        start.setEnabled(false);
        String[] request = NearbyPermissionPolicy.request(Build.VERSION.SDK_INT, this::granted);
        if (request.length > 0) {
            status.setText("Allow Nearby devices and precise Location in the Android prompts");
            requestPermissions(request, 1);
        } else checkRadiosThenStart();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 1) return;
        List<String> missing = NearbyPermissionPolicy.missing(Build.VERSION.SDK_INT, this::granted);
        if (!missing.isEmpty()) {
            start.setEnabled(true); status.setText("Permissions needed — Nearby devices and precise Location");
            new AlertDialog.Builder(this).setTitle("Complete permission setup")
                    .setMessage("Nearby needs Location (precise, while using the app) and Nearby devices. Open Permissions in app settings, allow them, then return and tap Start.")
                    .setPositiveButton("App settings", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Later", null).show();
            return;
        }
        checkRadiosThenStart();
    }
    private void checkRadiosThenStart() {
        List<String> issues = NearbyPrerequisites.problems(this);
        if (!issues.isEmpty()) {
            start.setEnabled(true); status.setText("Setup needed:\n" + String.join("\n", issues));
            new AlertDialog.Builder(this).setTitle("Before connecting").setMessage(String.join("\n", issues))
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_SETTINGS)))
                    .setNegativeButton("OK", null).show();
            return;
        }
        startModule();
    }
    private void startModule() {
        if (module != null) return;
        try {
            NearbyConnectionsTransport transport = new NearbyConnectionsTransport(this);
            String block = blocked.getText().toString().trim();
            transport.setBlockedPeerId(block.isEmpty() ? null : block);
            getPreferences(MODE_PRIVATE).edit().putString("blocked", block).apply();
            MeshModule current = new MeshModule(nodeId, transport, new File(getFilesDir(), "mesh-state.bin"));
            module = current; blocked.setEnabled(false);
            current.onLog(this::append);
            current.onPeerConnected(peer -> runOnUiThread(() -> {
                if (module == current && !isDestroyed()) mapController.sync(peer);
            }));
            current.onEvent(e -> {
                runOnUiThread(() -> recordEvent(e));
                append("RECEIVED id=" + e.id + " from=" + e.originId + " via=" + e.senderId + " hops=" + e.hopLimit);
            });
            current.onStatus((id, delivery) -> {
                runOnUiThread(() -> recordStatus(id, delivery.name()));
                append("STATUS id=" + id + " " + delivery);
            });
            current.onReady(() -> runOnUiThread(() -> {
                if (module != current || isDestroyed()) return;
                status.setText("Ready — phones connect automatically; no code confirmation needed");
                start.setText("Stop"); start.setEnabled(true); publish.setEnabled(true);
                mapController.sync(MeshEvent.BROADCAST);
            }));
            current.onStartFailure(reason -> runOnUiThread(() -> {
                if (module == current && !isDestroyed()) stopModule("Could not start:\n" + reason);
            }));
            status.setText("Starting nearby advertising and discovery…");
            current.start();
        } catch (Exception e) {
            append("Start failed: " + e.getMessage());
            if (module != null) stopModule("Start failed: " + e.getMessage());
            else { start.setEnabled(true); blocked.setEnabled(true); status.setText("Start failed: " + e.getMessage()); }
        }
    }
    private void stopModule(String reason) {
        MeshModule current = module;
        if (current == null) return;
        start.setEnabled(false); publish.setEnabled(false); status.setText(reason);
        current.stopAsync().thenRun(() -> runOnUiThread(() -> {
            if (isDestroyed() || module != current) return;
            module = null; start.setText("Start"); start.setEnabled(true); blocked.setEnabled(true);
            peerText.setText("Direct peers: []");
        }));
    }
    private void append(String line) {
        Log.i("GhostnetMesh", line);
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (log.length() > 16000) log.setText(log.getText().subSequence(log.length() - 8000, log.length()));
            log.append(line + "\n");
        });
    }

    private void recordEvent(MeshEvent event) {
        if (isDestroyed()) return;
        if (mapController.receive(event)) return;
        PacketView packet = packets.get(event.id);
        if (packet == null) {
            packet = new PacketView();
            packet.id = event.id;
            packet.direction = "RECEIVED";
            CascadeEmergency emergency = CascadeEmergency.fromJsonBytes(event.payload);
            if (emergency != null) {
                DispatchEngine.EvaluationResult eval = dispatchEngine.evaluate(
                        emergency, event.originId, event.hopLimit, MapController.RESPONDER_LAT, MapController.RESPONDER_LON
                );
                mapController.showIncident(emergency);
                packet.text = formatEmergencySummary(emergency, eval);
                if ("FIRST_RESPONDER".equals(currentRole) && event.type == MeshEvent.Type.DISTRESS) {
                    triggerEmergencyAudioAlarm();
                }
            } else {
                packet.text = new String(event.payload, StandardCharsets.UTF_8);
            }
            packet.origin = event.originId;
            packet.recipient = event.recipientId;
            packet.timeMs = System.currentTimeMillis();
            packets.put(event.id, packet);
        } else if (nodeId.equals(event.originId)) {
            packet.direction = "SENT / LOCAL BROADCAST";
        }
        packet.via = event.senderId;
        packet.hops = event.hopLimit;
        if (packet.status == null) packet.status = "DELIVERED TO THIS PHONE";
        trimPackets(); renderPackets();
    }

    private String formatEmergencySummary(CascadeEmergency e, DispatchEngine.EvaluationResult eval) {
        StringBuilder sb = new StringBuilder();
        String roleBadge = "FIRST_RESPONDER".equalsIgnoreCase(e.senderRole) ? "🚑 RESPONDER" : "👤 CITIZEN";
        sb.append("[").append(roleBadge);
        if (e.batteryPct != null) sb.append(" • 🔋").append(e.batteryPct).append("%");
        sb.append("] [").append(e.intent).append(" • ").append(e.urgency.toUpperCase(Locale.US)).append("]");
        if (e.peopleCount != null) sb.append(" [").append(e.peopleCount).append(" people]");
        if (!e.needs.isEmpty()) sb.append("\nNeeds: ").append(String.join(", ", e.needs));
        if (!e.hazards.isEmpty()) sb.append("\nHazards: ").append(String.join(", ", e.hazards));
        sb.append("\n\"").append(e.rawText).append("\"");

        if (eval != null) {
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("ROUTE & SUPPLY REVIEW:\n");
            sb.append("• Trust: ").append(Math.round(eval.trustScore * 100)).append("% | Decision: ")
                    .append(eval.approvedForDispatch ? "SUGGESTED" : "HELD").append("\n");
            sb.append("• Plan: ").append(eval.justification);
            if (eval.routePlan != null && eval.routePlan.stops.size() > 2) {
                sb.append("\n• Nearest Supply Depots:\n");
                for (int i = 1; i < eval.routePlan.stops.size() - 1; i++) {
                    WarehouseManager.RoutePlan.RouteStop s = eval.routePlan.stops.get(i);
                    sb.append("  ").append(i).append(". ").append(s.label);
                    if (!s.collectItems.isEmpty()) sb.append(" -> Collect: ").append(String.join(", ", s.collectItems));
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                String spoken = matches.get(0);
                message.setText(spoken);
                Toast.makeText(this, "Voice recognized: " + spoken, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startVoiceRecognition() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe emergency needs Hands-Free");
            startActivityForResult(intent, 42);
        } catch (Exception e) {
            Toast.makeText(this, "Voice recognition not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private void triggerEmergencyAudioAlarm() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1200);
        } catch (Throwable ignored) {}
    }

    private int getBatteryPercentage() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) return (int) ((level / (float) scale) * 100);
            }
        } catch (Throwable ignored) {}
        return 85;
    }


    private void recordStatus(String id, String delivery) {
        PacketView packet = packets.get(id);
        if (packet == null) return; // ACKs and restored packets have no display payload in this session.
        packet.status = friendlyStatus(delivery);
        renderPackets();
    }

    private String friendlyStatus(String statusName) {
        switch (statusName) {
            case "SENT_TO_PEER": return "SENT TO NEXT PHONE";
            case "DELIVERED": return "DELIVERED + ACKNOWLEDGED";
            case "EXPIRED": return "EXPIRED";
            case "FAILED": return "FAILED";
            default: return "QUEUED";
        }
    }

    private void trimPackets() {
        while (packets.size() > 30) {
            String oldest = packets.keySet().iterator().next();
            packets.remove(oldest);
        }
    }

    private void renderPackets() {
        if (packets.isEmpty()) {
            packetFeed.setText("No messages yet.\nSent and received packets will appear here.");
            return;
        }
        StringBuilder out = new StringBuilder();
        List<PacketView> list = new ArrayList<>(packets.values());
        for (int i = list.size() - 1; i >= 0; i--) {
            PacketView p = list.get(i);
            out.append("\n━━ ").append(p.direction).append("  ")
                    .append(clock.format(new Date(p.timeMs))).append(" ━━\n")
                    .append("Message: ").append(p.text).append('\n')
                    .append("Status: ").append(p.status == null ? "RECEIVED" : p.status).append('\n')
                    .append("Packet ID: ").append(p.id).append('\n')
                    .append("From: ").append(p.origin).append('\n')
                    .append("To: ").append(MeshEvent.BROADCAST.equals(p.recipient) ? "ALL PHONES (*)" : p.recipient).append('\n')
                    .append("Last hop / via: ").append(p.via).append('\n')
                    .append("Hops remaining: ").append(p.hops).append('\n');
        }
        packetFeed.setText(out.toString());
    }
    private EditText input(String hint) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint); e.setTextColor(0xFFE0EAF5);
        e.setHintTextColor(0xFF819BAE); e.setTextSize(14);
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF52738B)); return e;
    }
    private Button button(String title) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextColor(0xFFE0EAF5);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF22384B)); return b;
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (mapController != null) mapController.destroy();
        if (module != null) module.stop();
        super.onDestroy();
    }
}
