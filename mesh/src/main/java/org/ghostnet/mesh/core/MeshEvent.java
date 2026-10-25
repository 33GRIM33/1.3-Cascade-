package org.ghostnet.mesh.core;

import java.util.Arrays;
import java.util.UUID;

/** Immutable logical event. A relay keeps the ID and origin but changes sender and hop limit. */
public final class MeshEvent {
    public static final String BROADCAST = "*";
    public enum Type { TEXT, DISTRESS, OBSERVATION, DISPATCH, ACK }

    public final int version;
    public final String id, originId, senderId, recipientId;
    public final Type type;
    public final long createdAtMs, expiresAtMs;
    public final int hopLimit, priority;
    public final byte[] payload;

    public MeshEvent(int version, String id, String originId, String senderId,
                     String recipientId, Type type, long createdAtMs, long expiresAtMs,
                     int hopLimit, int priority, byte[] payload) {
        this.version = version;
        this.id = id;
        this.originId = originId;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.type = type;
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
        this.hopLimit = hopLimit;
        this.priority = priority;
        this.payload = payload == null ? null : Arrays.copyOf(payload, payload.length);
    }

    public static MeshEvent create(String nodeId, String recipientId, Type type,
                                   byte[] payload, long now, long ttlMs, int hops, int priority) {
        return new MeshEvent(1, UUID.randomUUID().toString(), nodeId, nodeId,
                recipientId, type, now, now + ttlMs, hops, priority, payload);
    }

    public MeshEvent forwardedBy(String nodeId) {
        return new MeshEvent(version, id, originId, nodeId, recipientId, type,
                createdAtMs, expiresAtMs, hopLimit - 1, priority, payload);
    }
}
