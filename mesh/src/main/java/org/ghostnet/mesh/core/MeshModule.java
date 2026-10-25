package org.ghostnet.mesh.core;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Public integration API. Callbacks are emitted on the module worker, not the UI thread. */
public final class MeshModule {
    private final String nodeId;
    private final MeshTransport transport;
    private final MeshRouter router;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService timer;
    private final CopyOnWriteArrayList<Consumer<MeshEvent>> events = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BiConsumer<String, DeliveryStatus>> statuses = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> logs = new CopyOnWriteArrayList<>();
    private Runnable ready = () -> {};
    private Consumer<String> startFailure = reason -> {};
    private boolean started, stopped;
    private final CompletableFuture<Void> stoppedFuture = new CompletableFuture<>();

    public MeshModule(String nodeId, MeshTransport transport, File storeFile) throws IOException {
        if (!MessageCodec.validNode(nodeId)) throw new IllegalArgumentException("Invalid node ID");
        this.nodeId = nodeId; this.transport = transport;
        MessageCodec codec = new MessageCodec();
        router = new MeshRouter(nodeId, transport, codec, new MessageStore(storeFile, codec),
                System::currentTimeMillis, e -> events.forEach(c -> c.accept(e)),
                (id, state) -> statuses.forEach(c -> c.accept(id, state)), this::submit);
        router.setDiagnostics(this::log);
    }

    public String nodeId() { return nodeId; }
    public Set<String> peers() { return transport.connectedPeers(); }
    public void onEvent(Consumer<MeshEvent> callback) { events.add(callback); }
    public void onStatus(BiConsumer<String, DeliveryStatus> callback) { statuses.add(callback); }
    public void onLog(Consumer<String> callback) { logs.add(callback); }
    public void onReady(Runnable callback) { ready = callback; }
    public void onStartFailure(Consumer<String> callback) { startFailure = callback; }

    public synchronized void start() {
        if (stopped) throw new IllegalStateException("Module has stopped; create a new instance");
        if (started) return;
        started = true;
        transport.start(nodeId, new MeshTransport.Listener() {
            public void onPeerConnected(String peer) { submit(() -> { log("connected " + peer); router.peerConnected(peer); }); }
            public void onPeerDisconnected(String peer) { submit(() -> log("disconnected " + peer)); }
            public void onBytes(String peer, byte[] bytes) { submit(() -> router.receive(peer, bytes)); }
            public void onError(String description) { submit(() -> log("transport: " + description)); }
            public void onInfo(String description) { submit(() -> log(description)); }
            public void onReady() { submit(() -> { log("READY: advertising and discovery active"); ready.run(); }); }
            public void onStartFailed(String description) { submit(() -> { log("START FAILED: " + description); startFailure.accept(description); }); }
        });
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(() -> submit(router::tick), 5, 5, TimeUnit.SECONDS);
    }

    public String publish(String recipientId, MeshEvent.Type type, byte[] payload,
                          long ttlMs, int hopLimit, int priority) {
        MeshEvent e = MeshEvent.create(nodeId, recipientId, type, payload,
                System.currentTimeMillis(), ttlMs, hopLimit, priority);
        submit(() -> {
            try { router.publish(e); log("published " + e.id); }
            catch (IOException ex) { log("publish failed: " + ex.getMessage()); }
        });
        return e.id;
    }

    public void stop() { stopAsync(); }
    public synchronized CompletableFuture<Void> stopAsync() {
        if (stopped) return stoppedFuture;
        stopped = true;
        if (timer != null) timer.shutdownNow();
        transport.stop();
        worker.execute(() -> stoppedFuture.complete(null));
        worker.shutdown();
        return stoppedFuture;
    }

    private void log(String message) { for (Consumer<String> c : logs) c.accept(message); }
    private void submit(Runnable action) {
        try { worker.execute(action); } catch (RejectedExecutionException ignored) { /* Late radio callback after stop. */ }
    }
}
