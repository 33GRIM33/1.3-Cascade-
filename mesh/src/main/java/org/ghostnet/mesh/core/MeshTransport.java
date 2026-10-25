package org.ghostnet.mesh.core;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Direct peers only. IDs here are stable node IDs, never Nearby endpoint IDs. */
public interface MeshTransport {
    interface Listener {
        void onPeerConnected(String peerId);
        void onPeerDisconnected(String peerId);
        void onBytes(String peerId, byte[] bytes);
        void onError(String description);
        default void onInfo(String description) { }
        default void onReady() { }
        default void onStartFailed(String description) { onError(description); }
    }
    void start(String localNodeId, Listener listener);
    void stop();
    Set<String> connectedPeers();
    CompletableFuture<Boolean> send(String peerId, byte[] bytes);
}
