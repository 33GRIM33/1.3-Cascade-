package org.ghostnet.mesh.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.Arrays;

/** Dependency-free deterministic checks: run with scripts/test-core.ps1. */
public final class RouterChecks {
    private static final class FakeTransport implements MeshTransport {
        final String id;
        final Map<String, FakeTransport> links = new HashMap<>();
        Listener listener;
        int sends;
        FakeTransport(String id) { this.id = id; }
        public void start(String node, Listener l) { listener = l; }
        public void stop() { }
        public Set<String> connectedPeers() { return new HashSet<>(links.keySet()); }
        public CompletableFuture<Boolean> send(String peer, byte[] bytes) {
            FakeTransport other = links.get(peer);
            if (other == null) return CompletableFuture.completedFuture(false);
            sends++;
            other.listener.onBytes(id, bytes);
            return CompletableFuture.completedFuture(true);
        }
        void link(FakeTransport other) {
            links.put(other.id, other); other.links.put(id, this);
            listener.onPeerConnected(other.id); other.listener.onPeerConnected(id);
        }
        void unlink(FakeTransport other) {
            links.remove(other.id); other.links.remove(id);
            listener.onPeerDisconnected(other.id); other.listener.onPeerDisconnected(id);
        }
    }
    private static final class Node {
        final String id;
        final FakeTransport transport;
        final MeshRouter router;
        final List<MeshEvent> received = new ArrayList<>();
        Node(String id, long[] now, File file) throws Exception {
            this.id = id; transport = new FakeTransport(id);
            MessageCodec codec = new MessageCodec();
            router = new MeshRouter(id, transport, codec, new MessageStore(file, codec),
                    () -> now[0], received::add, (eventId, state) -> {}, Runnable::run);
            transport.start(id, new MeshTransport.Listener() {
                public void onPeerConnected(String peer) { router.peerConnected(peer); }
                public void onPeerDisconnected(String peer) { }
                public void onBytes(String peer, byte[] bytes) { router.receive(peer, bytes); }
                public void onError(String error) { throw new AssertionError(error); }
            });
        }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static MeshEvent event(Node from, String to, long now, int hops) {
        return MeshEvent.create(from.id, to, MeshEvent.Type.DISTRESS,
                "help".getBytes(StandardCharsets.UTF_8), now, 60000, hops, 10);
    }
    public static void main(String[] args) throws Exception {
        for (int sdk = 26; sdk <= 35; sdk++) {
            List<String> required = NearbyPermissionPolicy.required(sdk);
            check(required.contains(NearbyPermissionPolicy.COARSE) && required.contains(NearbyPermissionPolicy.FINE), "location declared on API " + sdk);
            check(required.contains("android.permission.BLUETOOTH_SCAN") == (sdk >= 31), "Bluetooth runtime boundary " + sdk);
            check(required.contains("android.permission.NEARBY_WIFI_DEVICES") == (sdk >= 33), "Wi-Fi runtime boundary " + sdk);
            check(NearbyPermissionPolicy.request(sdk, p -> true).length == 0, "granted permissions not requested again");
            List<String> upgrade = Arrays.asList(NearbyPermissionPolicy.request(sdk, p -> !p.equals(NearbyPermissionPolicy.FINE)));
            check(upgrade.contains(NearbyPermissionPolicy.COARSE) && upgrade.contains(NearbyPermissionPolicy.FINE), "precision upgrade requests BOTH permissions");
        }
        long[] now = { 1792929600000L };
        String aId = UUID.randomUUID().toString(), bId = UUID.randomUUID().toString(), cId = UUID.randomUUID().toString();
        File directory = Files.createTempDirectory("ghostnet-checks").toFile();
        Node a = new Node(aId, now, new File(directory, "a"));
        Node b = new Node(bId, now, new File(directory, "b"));
        Node c = new Node(cId, now, new File(directory, "c"));
        a.transport.link(b.transport); b.transport.link(c.transport);
        MeshEvent first = event(a, cId, now[0], 3);
        a.router.publish(first);
        check(c.received.size() == 1 && c.received.get(0).id.equals(first.id), "A-B-C delivery");
        check(c.received.get(0).hopLimit == 2, "relay decrements hop limit");
        check(a.router.status(first.id) == DeliveryStatus.DELIVERED, "destination ACK returns to A");
        check(b.router.status(first.id) != DeliveryStatus.DELIVERED, "relay is not destination");
        int sendsBefore = b.transport.sends;
        b.router.receive(aId, new MessageCodec().encode(first));
        check(c.received.size() == 1 && b.transport.sends == sendsBefore, "duplicate suppressed");
        MeshEvent zero = event(a, cId, now[0], 0);
        b.router.receive(aId, new MessageCodec().encode(zero));
        check(c.received.size() == 1, "zero hop prevents forwarding");
        MeshEvent expired = event(a, cId, now[0] - 120000, 4);
        b.router.receive(aId, new MessageCodec().encode(expired));
        check(c.received.size() == 1, "expired frame rejected");
        byte[] invalid = new MessageCodec().encode(event(a, cId, now[0], 3));
        invalid[4] = 99;
        b.router.receive(aId, invalid);
        check(c.received.size() == 1, "unknown version rejected");
        b.transport.unlink(c.transport);
        MeshEvent delayed = event(a, cId, now[0], 3);
        a.router.publish(delayed);
        check(c.received.size() == 1 && b.router.pendingCount() > 0, "disconnect queues at relay");
        now[0] += 6000;
        b.transport.link(c.transport);
        check(c.received.size() == 2 && a.router.status(delayed.id) == DeliveryStatus.DELIVERED,
                "reconnect retries and ACKs");
        // Durable deduplication survives a process restart.
        Node restartedC = new Node(cId, now, new File(directory, "c"));
        restartedC.router.receive(bId, new MessageCodec().encode(delayed.forwardedBy(bId)));
        check(restartedC.received.isEmpty(), "durable processed-ID cache");
        MeshEvent broadcast = event(a, MeshEvent.BROADCAST, now[0], 3);
        a.router.publish(broadcast);
        check(b.received.size() == 1 && c.received.size() == 3, "broadcast reaches each node once");
        int broadcastSends = a.transport.sends + b.transport.sends + c.transport.sends;
        now[0] += 6000;
        a.router.tick(); b.router.tick(); c.router.tick();
        check(a.transport.sends + b.transport.sends + c.transport.sends == broadcastSends,
                "successful broadcast creates no retry storm");
        MeshEvent noPeer = event(a, cId, now[0], 3);
        a.transport.unlink(b.transport);
        a.router.publish(noPeer);
        check(a.router.status(noPeer.id) == DeliveryStatus.QUEUED, "no peer remains queued");
        now[0] += 61000;
        a.router.tick();
        check(a.router.status(noPeer.id) == DeliveryStatus.EXPIRED, "queued expiry");
        Node x = new Node(UUID.randomUUID().toString(), now, null);
        Node y = new Node(UUID.randomUUID().toString(), now, null);
        Node z = new Node(UUID.randomUUID().toString(), now, null);
        x.transport.link(y.transport); y.transport.link(z.transport);
        MeshEvent finalHop = event(x, z.id, now[0], 1);
        x.router.publish(finalHop);
        check(z.received.size() == 1 && z.received.get(0).hopLimit == 0, "last allowed relay sends zero-budget frame to destination");
        check(x.router.status(finalHop.id) == DeliveryStatus.DELIVERED, "last-hop destination ACK");
        check(x.router.pendingCount() == 0 && y.router.pendingCount() == 0 && z.router.pendingCount() == 0,
                "delivered event and ACK frames leave no retry storm");
        int sentBefore = y.transport.sends;
        y.router.receive(x.id, new byte[]{1, 2, 3});
        y.router.receive(x.id, new byte[MessageCodec.MAX_BYTES + 1]);
        check(y.transport.sends == sentBefore, "malformed and oversized frames rejected");
        // A destination ACK is deduplicated and never acknowledged in turn.
        MeshEvent ack = MeshEvent.create(z.id, x.id, MeshEvent.Type.ACK,
                finalHop.id.getBytes(StandardCharsets.US_ASCII), now[0], 60000, 4, 10);
        byte[] ackBytes = new MessageCodec().encode(ack);
        x.router.receive(z.id, ackBytes);
        int ackSends = x.transport.sends;
        x.router.receive(z.id, ackBytes);
        check(x.transport.sends == ackSends, "duplicate ACK cannot create an ACK loop");
        // Actual module executor serializes simultaneous callbacks and durable dedup updates.
        String local = UUID.randomUUID().toString(), remote = UUID.randomUUID().toString();
        FakeTransport concurrentTransport = new FakeTransport(local);
        MeshModule concurrentModule = new MeshModule(local, concurrentTransport, new File(directory, "concurrent"));
        int count = 40;
        CountDownLatch received = new CountDownLatch(count);
        List<String> deliveredIds = Collections.synchronizedList(new ArrayList<>());
        concurrentModule.onEvent(e -> { deliveredIds.add(e.id); received.countDown(); });
        concurrentModule.start();
        ExecutorService producers = Executors.newFixedThreadPool(4);
        for (int i = 0; i < count; i++) {
            MeshEvent e = MeshEvent.create(remote, local, MeshEvent.Type.TEXT, new byte[]{42}, System.currentTimeMillis(), 60000, 4, 1);
            byte[] encoded = new MessageCodec().encode(e);
            producers.execute(() -> { concurrentTransport.listener.onBytes(remote, encoded); concurrentTransport.listener.onBytes(remote, encoded); });
        }
        producers.shutdown();
        check(producers.awaitTermination(10, TimeUnit.SECONDS), "concurrent producers completed");
        check(received.await(10, TimeUnit.SECONDS), "concurrent events delivered");
        concurrentModule.stopAsync().get(10, TimeUnit.SECONDS);
        concurrentTransport.listener.onPeerDisconnected(remote); // late callbacks must not crash after shutdown
        check(new MessageStore(new File(directory, "concurrent"), new MessageCodec()).seen.keySet().containsAll(deliveredIds), "concurrent state survived restart");

        // CASCADE AI: Offline extraction and structured emergency JSON wire transmission check
        String emergencyText = "Water rising fast trapped on 2nd floor need boat and medical kit for 3 people";
        CascadeEmergency extracted = CascadeExtractor.extract(emergencyText);
        check("RESCUE_REQUEST".equals(extracted.intent), "extractor intent must be RESCUE_REQUEST");
        check(extracted.needs.contains("rescue") && extracted.needs.contains("medical"), "extractor must find rescue & medical");
        check(extracted.hazards.contains("flood"), "extractor must detect flood hazard");
        check(Integer.valueOf(3).equals(extracted.peopleCount), "extractor must detect 3 people");
        check("critical".equals(extracted.urgency), "urgency must be critical for 3 people trapped in flood");

        byte[] emergencyPayload = extracted.toJsonBytes();
        check(emergencyPayload.length > 0 && emergencyPayload.length < 500, "emergency payload fits safely within 2048-byte frame");
        
        // Round trip through CascadeEmergency.fromJsonBytes
        CascadeEmergency parsed = CascadeEmergency.fromJsonBytes(emergencyPayload);
        check(parsed != null, "parsed emergency must not be null");
        check(extracted.intent.equals(parsed.intent), "parsed intent matches");
        check(extracted.peopleCount.equals(parsed.peopleCount), "parsed count matches");
        check(extracted.needs.equals(parsed.needs), "parsed needs match");
        check(extracted.hazards.equals(parsed.hazards), "parsed hazards match");

        // Transmission of structured emergency event through MessageCodec
        MeshEvent emergencyEvent = MeshEvent.create(a.id, c.id, MeshEvent.Type.DISTRESS, emergencyPayload, now[0], 60000, 4, 10);
        byte[] encodedFrame = new MessageCodec().encode(emergencyEvent);
        check(encodedFrame.length <= MessageCodec.MAX_BYTES, "GNET frame with emergency payload within 2048 bytes");
        MeshEvent decodedEvent = new MessageCodec().decode(encodedFrame);
        CascadeEmergency wireEmergency = CascadeEmergency.fromJsonBytes(decodedEvent.payload);
        check(wireEmergency != null && wireEmergency.needs.contains("rescue"), "wire emergency extracted on destination");

        // CASCADE AI: Roles, Battery Awareness & Probabilistic Multi-Agent Dispatch with Warehouse Routing
        // Case 1: Problem statement input: "Need insulin for dad, ground floor flooded" with 12% battery from Normal User
        String problemStatementMsg = "Need insulin for dad, ground floor flooded 12% battery";
        CascadeEmergency victimSos = CascadeExtractor.extract(problemStatementMsg, "NORMAL_USER", 12, 37.7780, -122.4150, false);
        check("NORMAL_USER".equals(victimSos.senderRole), "role is NORMAL_USER");
        check(Integer.valueOf(12).equals(victimSos.batteryPct), "extracted 12% battery constraint");
        check(victimSos.needs.contains("medical"), "medical need identified");
        check(victimSos.hazards.contains("flood"), "flood hazard identified");
        check("critical".equals(victimSos.urgency), "critical urgency due to dying 12% battery in flood");

        // Test WarehouseManager inventory search
        WarehouseManager wm = new WarehouseManager();
        List<WarehouseManager.WarehouseMatch> insulinWarehouses = wm.findWarehousesWithStock("insulin", 37.7749, -122.4194);
        check(!insulinWarehouses.isEmpty(), "found warehouse with insulin");
        check("WH-ALPHA".equals(insulinWarehouses.get(0).warehouse.id), "WH-ALPHA is closest for insulin");

        // Test multi-depot routing for insulin (medical) + boat (flood rescue)
        WarehouseManager.RoutePlan routePlan = wm.planRoute(Arrays.asList("insulin", "boat"), 37.7700, -122.4200, 37.7780, -122.4150);
        check(routePlan.stops.size() >= 3, "route plan includes stops at warehouses");
        check(routePlan.fulfilledItems.contains("insulin") && routePlan.fulfilledItems.contains("boat"), "both insulin and boat fulfilled");
        check(routePlan.summaryText.contains("Route via"), "summary generated for responder");

        // Test DispatchEngine 5-step Probabilistic Evaluation Chain
        DispatchEngine engine = new DispatchEngine(wm);
        DispatchEngine.EvaluationResult eval = engine.evaluate(victimSos, a.id, 4, 37.7700, -122.4200);
        check(eval.claimVerified, "claim verified");
        check(eval.trustScore >= 0.70, "trust score above threshold");
        check(eval.urgencyCost > 7.0, "urgency cost elevated by 12% battery constraint");
        check(eval.approvedForDispatch, "approved for dispatch");
        check(eval.justification.contains("CRITICAL rescue") || eval.justification.contains("Dispatched:"), "human-in-the-loop justification generated");
        check(eval.requiredEquipment.contains("boat") && eval.requiredEquipment.contains("insulin"), "required equipment identified");

        // Wire transmission of full role + battery + routing payload
        CascadeEmergency dispatchedSos = victimSos.withRouting(eval.justification, "DISPATCHED");
        byte[] fullPayload = dispatchedSos.toJsonBytes();
        check(fullPayload.length < 800, "full dispatch envelope fits well under 2048 bytes");
        CascadeEmergency roundTrip = CascadeEmergency.fromJsonBytes(fullPayload);
        check(roundTrip != null && Integer.valueOf(12).equals(roundTrip.batteryPct), "battery % preserved across wire");
        check("DISPATCHED".equals(roundTrip.dispatchStatus), "dispatch status preserved across wire");
        check(roundTrip.warehouseRouting != null, "warehouse routing preserved across wire");

        System.out.println("RouterChecks passed (including CASCADE offline AI extraction, roles, warehouses & probabilistic dispatch)");
    }
}


