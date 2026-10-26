package org.ghostnet.mesh.core;

import java.io.*;
import java.util.UUID;

/** Immutable, bounded hazard observation. Reports expire; a clearance retains its original ID. */
public final class HazardReport {
    private static final int MAGIC = 0x474e4831; // GNH1
    public static final long LIFETIME_MS = 60 * 60 * 1000L;
    public enum Type { FIRE, BLOCKED, FLOOD, DEBRIS }
    public final String id, reporter, mapId, edgeId, street, note;
    public final Type type;
    public final double lat, lon, radiusMeters;
    public final long createdAt, updatedAt, expiresAt;
    public final boolean cleared;

    public HazardReport(String id, String reporter, String mapId, String edgeId, String street,
                        Type type, double lat, double lon, double radius, String note,
                        long created, long updated, long expires, boolean cleared) {
        this.id = id; this.reporter = reporter; this.mapId = mapId; this.edgeId = edgeId;
        this.street = street; this.type = type; this.lat = lat; this.lon = lon;
        radiusMeters = radius; this.note = note; createdAt = created; updatedAt = updated;
        expiresAt = expires; this.cleared = cleared;
        validate();
    }
    public static HazardReport create(String reporter, StreetMap map, StreetMap.Edge edge, Type type, String note, long now) {
        double radius = type == Type.FIRE ? 40 : type == Type.FLOOD ? 55 : 12;
        return new HazardReport(UUID.randomUUID().toString(), reporter, map.id, edge.id, edge.name,
                type, (edge.a.lat + edge.b.lat) / 2, (edge.a.lon + edge.b.lon) / 2, radius,
                note.trim(), now, now, now + LIFETIME_MS, false);
    }
    public HazardReport clear(long now) {
        return new HazardReport(id, reporter, mapId, edgeId, street, type, lat, lon, radiusMeters,
                note, createdAt, Math.max(updatedAt + 1, now), expiresAt, true);
    }
    public boolean active(long now) { return !cleared && expiresAt > now; }
    public boolean blocks(StreetMap.Edge edge, String graphId, long now) {
        return mapId.equals(graphId) && active(now) &&
                (edgeId.equals(edge.id) || StreetMap.segmentDistance(lat, lon, edge.a, edge.b) <= radiusMeters);
    }
    private void validate() {
        if (!UUID.fromString(id).toString().equals(id) || !MessageCodec.validNode(reporter)
                || mapId == null || mapId.length() > 64 || edgeId == null || edgeId.length() > 80
                || street == null || street.length() > 160 || note == null || note.length() > 200 || type == null
                || !Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180
                || !Double.isFinite(radiusMeters) || radiusMeters < 5 || radiusMeters > 100
                || createdAt <= 0 || updatedAt < createdAt || expiresAt <= updatedAt
                || expiresAt - createdAt > LIFETIME_MS) throw new IllegalArgumentException("Invalid hazard report");
    }
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeUTF(id); out.writeUTF(reporter); out.writeUTF(mapId);
            out.writeUTF(edgeId); out.writeUTF(street); out.writeByte(type.ordinal());
            out.writeDouble(lat); out.writeDouble(lon); out.writeDouble(radiusMeters); out.writeUTF(note);
            out.writeLong(createdAt); out.writeLong(updatedAt); out.writeLong(expiresAt); out.writeBoolean(cleared);
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public static boolean isHazard(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'N' && bytes[2] == 'H' && bytes[3] == '1';
    }
    public static HazardReport decode(byte[] bytes) throws IOException {
        if (!isHazard(bytes) || bytes.length > 1400) throw new IOException("Invalid hazard payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            in.readInt(); String id = in.readUTF(), reporter = in.readUTF(), map = in.readUTF();
            String edge = in.readUTF(), street = in.readUTF(); int type = in.readUnsignedByte();
            if (type >= Type.values().length) throw new IOException("Unknown hazard type");
            HazardReport report = new HazardReport(id, reporter, map, edge, street, Type.values()[type],
                    in.readDouble(), in.readDouble(), in.readDouble(), in.readUTF(),
                    in.readLong(), in.readLong(), in.readLong(), in.readBoolean());
            if (in.available() != 0) throw new IOException("Trailing hazard bytes");
            return report;
        } catch (RuntimeException ex) { throw new IOException("Invalid hazard", ex); }
    }
}
