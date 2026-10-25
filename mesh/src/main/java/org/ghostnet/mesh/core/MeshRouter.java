package org.ghostnet.mesh.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Serialized by MeshModule's executor. send() is nonblocking and callback driven. */
public final class MeshRouter {
    private static final int MAX_ATTEMPTS = 20;
    private static final long RETRY_MS = 5000;
    private final String nodeId;
    private final MeshTransport transport;
    private final MessageCodec codec;
    private final MessageStore store;
    private final LongSupplier clock;
    private final Consumer<MeshEvent> incoming;
    private final StatusListener statusListener;
    private final Executor completionExecutor;
    private Consumer<String> diagnostics = message -> {};
    public void setDiagnostics(Consumer<String> callback) { diagnostics = callback; }
    public interface StatusListener { void onStatus(String eventId, DeliveryStatus status); }

    public MeshRouter(String nodeId, MeshTransport transport, MessageCodec codec, MessageStore store,
                      LongSupplier clock, Consumer<MeshEvent> incoming, StatusListener statusListener,
                      Executor completionExecutor) {
        this.nodeId = nodeId; this.transport = transport; this.codec = codec; this.store = store;
        this.clock = clock; this.incoming = incoming; this.statusListener = statusListener;
        this.completionExecutor = completionExecutor;
    }

    public void publish(MeshEvent e) throws IOException {
        if (!nodeId.equals(e.originId) || !nodeId.equals(e.senderId)) throw new IOException("Foreign origin");
        codec.encode(e);
        if (e.expiresAtMs <= clock.getAsLong()) throw new IOException("Expired event");
        if (store.seen.containsKey(e.id)) throw new IOException("Duplicate ID");
        store.seen.put(e.id, e.expiresAtMs);
        if (e.recipientId.equals(nodeId)) {
            setStatus(e.id, DeliveryStatus.DELIVERED); save(); incoming.accept(e); return;
        }
        store.pending.put(e.id, new MessageStore.Pending(e, null));
        setStatus(e.id, DeliveryStatus.QUEUED);
        save();
        if (MeshEvent.BROADCAST.equals(e.recipientId)) incoming.accept(e);
        tick();
    }

    public void receive(String peerId, byte[] bytes) {
        try {
            MeshEvent e = codec.decode(bytes);
            long now = clock.getAsLong();
            if (e.expiresAtMs <= now || e.senderId.equals(nodeId) || !e.senderId.equals(peerId)) return;
            store.prune(now);
            if (store.seen.containsKey(e.id)) { diagnostics.accept("Duplicate ignored id=" + e.id); return; }
            // Persist before local delivery or forwarding, so a restart cannot repeat delivery.
            store.seen.put(e.id, e.expiresAtMs);
            store.reversePeer.put(e.id, peerId);
            diagnostics.accept("Received id=" + e.id + " via=" + peerId + " hops=" + e.hopLimit);
            boolean deliver = false;
            if (e.type == MeshEvent.Type.ACK) {
                handleAck(e, peerId);
            } else if (MeshEvent.BROADCAST.equals(e.recipientId)) {
                deliver = true;
                queueForward(e, peerId);
            } else if (nodeId.equals(e.recipientId)) {
                deliver = true;
                sendAck(e);
            } else {
                queueForward(e, peerId);
            }
            save();
            if (deliver) incoming.accept(e);
            tick();
        } catch (IOException | RuntimeException ex) {
            diagnostics.accept("Frame rejected or storage failed: " + ex.getMessage());
        }
    }

    private void queueForward(MeshEvent e, String incomingPeer) {
        if (e.hopLimit <= 0) return;
        store.pending.put(e.id, new MessageStore.Pending(e.forwardedBy(nodeId), incomingPeer));
    }

    private void sendAck(MeshEvent original) throws IOException {
        if (original.originId.equals(nodeId)) return;
        byte[] payload = original.id.getBytes(StandardCharsets.US_ASCII);
        MeshEvent ack = MeshEvent.create(nodeId, original.originId, MeshEvent.Type.ACK,
                payload, clock.getAsLong(), 120000, 8, 10);
        store.seen.put(ack.id, ack.expiresAtMs);
        store.pending.put(ack.id, new MessageStore.Pending(ack, null));
    }

    private void handleAck(MeshEvent ack, String peerId) {
        String originalId = new String(ack.payload, StandardCharsets.US_ASCII);
        try { UUID.fromString(originalId); } catch (RuntimeException ex) { return; }
        MessageStore.Pending original = store.pending.get(originalId);
        if (nodeId.equals(ack.recipientId)) {
            if (original != null && original.event.type != MeshEvent.Type.ACK &&
                    original.event.recipientId.equals(ack.originId)) {
                store.pending.remove(originalId);
                setStatus(originalId, DeliveryStatus.DELIVERED);
            }
        } else {
            if (original != null && original.event.type != MeshEvent.Type.ACK &&
                    original.event.recipientId.equals(ack.originId)) store.pending.remove(originalId);
            queueForward(ack, peerId);
        }
    }

    public void peerConnected(String peerId) { tick(); }

    public void tick() {
        long now = clock.getAsLong();
        store.prune(now);
        List<MessageStore.Pending> work = new ArrayList<>(store.pending.values());
        work.sort(Comparator.comparingInt((MessageStore.Pending p) -> p.event.priority).reversed());
        for (MessageStore.Pending p : work) {
            if (!store.pending.containsKey(p.event.id)) continue;
            if (p.event.expiresAtMs <= now) {
                store.pending.remove(p.event.id);
                setStatus(p.event.id, DeliveryStatus.EXPIRED);
                continue;
            }
            if (p.nextRetryMs > now) continue;
            Set<String> peers = transport.connectedPeers();
            List<String> eligible = new ArrayList<>();
            String reverse = p.event.type == MeshEvent.Type.ACK
                    ? store.reversePeer.get(new String(p.event.payload, StandardCharsets.US_ASCII)) : null;
            if (reverse != null && peers.contains(reverse) && !reverse.equals(p.excludedPeer)) eligible.add(reverse);
            else for (String peer : peers) if (!peer.equals(p.excludedPeer)) eligible.add(peer);
            if (eligible.isEmpty()) continue;
            if (p.attempts >= MAX_ATTEMPTS) {
                store.pending.remove(p.event.id);
                setStatus(p.event.id, DeliveryStatus.FAILED);
                continue;
            }
            p.attempts++;
            p.nextRetryMs = now + RETRY_MS;
            try {
                byte[] bytes = codec.encode(p.event);
                for (String peer : eligible) {
                    diagnostics.accept("Sending id=" + p.event.id + " to=" + peer + " hops=" + p.event.hopLimit);
                    transport.send(peer, bytes).thenAcceptAsync(success -> {
                        if (success && (p.event.type == MeshEvent.Type.ACK ||
                                MeshEvent.BROADCAST.equals(p.event.recipientId))) {
                            // Broadcasts have no final destination ACK. ACK frames themselves are
                            // hop-by-hop best effort. Once a direct transport accepts either frame,
                            // remove it so the retry timer cannot create a packet storm.
                            store.pending.remove(p.event.id);
                            if (MeshEvent.BROADCAST.equals(p.event.recipientId) &&
                                    store.statuses.get(p.event.id) == DeliveryStatus.QUEUED)
                                setStatus(p.event.id, DeliveryStatus.SENT_TO_PEER);
                            try { save(); } catch (IOException ignored) { }
                            return;
                        }
                        // A direct send only proves acceptance by the transport, never final delivery.
                        if (success && p.event.type != MeshEvent.Type.ACK &&
                                store.statuses.get(p.event.id) == DeliveryStatus.QUEUED) {
                            setStatus(p.event.id, DeliveryStatus.SENT_TO_PEER);
                            try { save(); } catch (IOException ignored) { }
                        }
                    }, completionExecutor);
                }
            } catch (IOException ex) {
                store.pending.remove(p.event.id);
                setStatus(p.event.id, DeliveryStatus.FAILED);
            }
        }
        try { save(); } catch (IOException ignored) { }
    }

    public DeliveryStatus status(String eventId) { return store.statuses.get(eventId); }
    public int pendingCount() { return store.pending.size(); }
    private void setStatus(String id, DeliveryStatus value) {
        store.statuses.put(id, value);
        statusListener.onStatus(id, value);
    }
    private void save() throws IOException { store.save(); }
}
