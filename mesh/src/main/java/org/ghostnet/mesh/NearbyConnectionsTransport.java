package org.ghostnet.mesh;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;
import org.ghostnet.mesh.core.MeshTransport;
import org.ghostnet.mesh.core.MessageCodec;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** All radio operations and callback bookkeeping run on the main looper. */
public final class NearbyConnectionsTransport implements MeshTransport {
    private static final String SERVICE_ID = "org.ghostnet.mesh";
    private final Context context;
    private final ConnectionsClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> endpointToNode = new HashMap<>();
    private final Map<String, String> nodeToEndpoint = new ConcurrentHashMap<>();
    private final Map<String, String> discovered = new HashMap<>();
    private final Map<String, Long> discoveredAt = new HashMap<>();
    private final Map<String, Long> connecting = new HashMap<>();
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Set<String> rejected = new HashSet<>();
    private Listener listener;
    private String localNodeId, blockedPeerId;
    private boolean active;
    private volatile boolean requested;
    private int generation;

    public NearbyConnectionsTransport(Context context) {
        this.context = context.getApplicationContext();
        this.client = Nearby.getConnectionsClient(this.context);
    }
    public void setBlockedPeerId(String nodeId) {
        if (active) throw new IllegalStateException("Stop before changing the blocked peer");
        blockedPeerId = nodeId;
    }
    @Override public void start(String localNodeId, Listener listener) {
        if (requested) return;
        requested = true;
        main.post(() -> begin(localNodeId, listener));
    }
    private void begin(String node, Listener callbacks) {
        if (active || !requested) return;
        listener = callbacks; localNodeId = node;
        List<String> issues = NearbyPrerequisites.problems(context);
        if (!issues.isEmpty()) { requested = false; listener.onStartFailed(String.join("\n", issues)); return; }
        active = true;
        final int session = ++generation;
        try {
            client.startAdvertising(node, SERVICE_ID, connections,
                    new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener(v -> {
                    if (!current(session)) return;
                    try {
                        client.startDiscovery(SERVICE_ID, discoveryCallback,
                                new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                            .addOnSuccessListener(ignored -> {
                                if (!current(session)) return;
                                listener.onReady(); main.postDelayed(retryConnections, 10000);
                            })
                            .addOnFailureListener(e -> failStart(session, "Discovery: " + e.getMessage()));
                    } catch (RuntimeException e) { failStart(session, "Discovery: " + e.getMessage()); }
                })
                .addOnFailureListener(e -> failStart(session, "Advertising: " + e.getMessage()));
        } catch (RuntimeException e) { failStart(session, "Start: " + e.getMessage()); }
    }
    private boolean current(int session) { return active && session == generation; }
    private void failStart(int session, String reason) {
        if (!current(session)) return;
        stopOnMain(); listener.onStartFailed(reason);
    }
    private void request(String endpoint, String peer) {
        if (!active || !MessageCodec.validNode(peer) || peer.equals(localNodeId) || peer.equals(blockedPeerId)
                || nodeToEndpoint.containsKey(peer) || connecting.containsKey(endpoint) || rejected.contains(endpoint)
                || connectingPeer(peer) || attempts.getOrDefault(endpoint, 0) >= 3) return;
        // Prefer the smaller ID first, but allow the other side to initiate after a delay
        // when discovery only succeeds in one direction.
        if (localNodeId.compareTo(peer) >= 0 && SystemClock.elapsedRealtime() - discoveredAt.getOrDefault(endpoint, 0L) < 3000) return;
        final int session = generation;
        connecting.put(endpoint, SystemClock.elapsedRealtime()); endpointToNode.put(endpoint, peer);
        attempts.put(endpoint, attempts.getOrDefault(endpoint, 0) + 1);
        try {
            client.requestConnection(localNodeId, endpoint, connections).addOnFailureListener(e -> {
                if (!current(session)) return;
                connecting.remove(endpoint); endpointToNode.remove(endpoint);
                listener.onError("Connection request: " + e.getMessage());
            });
        } catch (RuntimeException e) { connecting.remove(endpoint); listener.onError("Connection request: " + e.getMessage()); }
    }
    private boolean connectingPeer(String peer) {
        for (String endpoint : connecting.keySet()) if (peer.equals(endpointToNode.get(endpoint))) return true;
        return false;
    }
    private final Runnable retryConnections = new Runnable() {
        @Override public void run() {
            if (!active) return;
            for (String endpoint : new ArrayList<>(connecting.keySet())) {
                if (SystemClock.elapsedRealtime() - connecting.get(endpoint) > 60000) {
                    connecting.remove(endpoint); client.disconnectFromEndpoint(endpoint);
                }
            }
            for (Map.Entry<String, String> entry : new HashMap<>(discovered).entrySet()) request(entry.getKey(), entry.getValue());
            main.postDelayed(this, 10000);
        }
    };
    private final EndpointDiscoveryCallback discoveryCallback = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(String endpoint, DiscoveredEndpointInfo info) {
            if (!active) return;
            String peer = info.getEndpointName();
            if (!MessageCodec.validNode(peer) || peer.equals(localNodeId) || peer.equals(blockedPeerId)) return;
            discovered.put(endpoint, peer); discoveredAt.put(endpoint, SystemClock.elapsedRealtime());
            listener.onInfo("Found peer " + peer); request(endpoint, peer);
        }
        @Override public void onEndpointLost(String endpoint) {
            discovered.remove(endpoint); discoveredAt.remove(endpoint); attempts.remove(endpoint); rejected.remove(endpoint);
        }
    };
    private final ConnectionLifecycleCallback connections = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String endpoint, ConnectionInfo info) {
            String peer = info.getEndpointName();
            if (!active || !MessageCodec.validNode(peer) || peer.equals(localNodeId) || peer.equals(blockedPeerId)
                    || nodeToEndpoint.containsKey(peer)) { client.rejectConnection(endpoint); return; }
            int session = generation;
            endpointToNode.put(endpoint, peer); connecting.put(endpoint, SystemClock.elapsedRealtime());
            // Crisis mesh mode: valid GHOSTNET nodes are accepted automatically. The router's
            // deduplication, expiry, hop limit and ACK rules operate after this radio link forms.
            // Production deployments should authenticate signed envelopes at the application layer.
            client.acceptConnection(endpoint, payloads).addOnSuccessListener(ignored -> {
                if (current(session)) listener.onInfo("Auto-accepted peer " + peer);
            }).addOnFailureListener(e -> {
                if (!current(session)) return;
                connecting.remove(endpoint); endpointToNode.remove(endpoint);
                listener.onError("Auto-accept: " + e.getMessage());
            });
        }
        @Override public void onConnectionResult(String endpoint, ConnectionResolution result) {
            if (!active) return;
            String peer = endpointToNode.get(endpoint);
            if (peer == null) return;
            connecting.remove(endpoint);
            if (result.getStatus().isSuccess()) {
                nodeToEndpoint.put(peer, endpoint); attempts.remove(endpoint); listener.onPeerConnected(peer);
            } else {
                endpointToNode.remove(endpoint);
                if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) rejected.add(endpoint);
                listener.onError("Connection failed: " + result.getStatus().getStatusCode());
            }
        }
        @Override public void onDisconnected(String endpoint) {
            connecting.remove(endpoint);
            String peer = endpointToNode.remove(endpoint);
            if (peer != null && nodeToEndpoint.remove(peer, endpoint) && active) listener.onPeerDisconnected(peer);
        }
    };
    private final PayloadCallback payloads = new PayloadCallback() {
        @Override public void onPayloadReceived(String endpoint, Payload payload) {
            if (!active || payload.getType() != Payload.Type.BYTES) return;
            byte[] bytes = payload.asBytes(); String peer = endpointToNode.get(endpoint);
            if (bytes != null && bytes.length <= MessageCodec.MAX_BYTES && peer != null
                    && endpoint.equals(nodeToEndpoint.get(peer))) listener.onBytes(peer, bytes);
        }
        @Override public void onPayloadTransferUpdate(String endpoint, PayloadTransferUpdate update) { }
    };
    @Override public Set<String> connectedPeers() { return Collections.unmodifiableSet(new HashSet<>(nodeToEndpoint.keySet())); }
    @Override public CompletableFuture<Boolean> send(String peerId, byte[] bytes) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        main.post(() -> {
            String endpoint = nodeToEndpoint.get(peerId);
            if (!active || endpoint == null || bytes == null || bytes.length > MessageCodec.MAX_BYTES) { result.complete(false); return; }
            try {
                client.sendPayload(endpoint, Payload.fromBytes(bytes)).addOnSuccessListener(v -> result.complete(true))
                        .addOnFailureListener(e -> result.complete(false));
            } catch (RuntimeException e) { result.complete(false); }
        });
        return result;
    }
    @Override public void stop() {
        requested = false;
        if (Looper.myLooper() == Looper.getMainLooper()) stopOnMain(); else main.post(this::stopOnMain);
    }
    private void stopOnMain() {
        active = false; requested = false; ++generation; main.removeCallbacks(retryConnections);
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints();
        endpointToNode.clear(); nodeToEndpoint.clear(); connecting.clear(); discovered.clear(); discoveredAt.clear(); attempts.clear(); rejected.clear();
    }
}
