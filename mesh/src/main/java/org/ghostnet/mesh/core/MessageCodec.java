package org.ghostnet.mesh.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Versioned, bounded binary frame. No Java object serialization. */
public final class MessageCodec {
    public static final int MAX_BYTES = 2048;
    private static final int MAGIC = 0x474e4554; // GNET
    private final int maxBytes;

    public MessageCodec(int maxBytes) { this.maxBytes = maxBytes; }
    public MessageCodec() { this(MAX_BYTES); }

    public byte[] encode(MeshEvent e) throws IOException {
        validate(e);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeByte(e.version);
        writeString(out, e.id);
        writeString(out, e.originId);
        writeString(out, e.senderId);
        writeString(out, e.recipientId);
        out.writeByte(e.type.ordinal());
        out.writeLong(e.createdAtMs);
        out.writeLong(e.expiresAtMs);
        out.writeByte(e.hopLimit);
        out.writeByte(e.priority);
        out.writeShort(e.payload.length);
        out.write(e.payload);
        out.flush();
        if (bytes.size() > maxBytes) throw new IOException("Message exceeds " + maxBytes + " bytes");
        return bytes.toByteArray();
    }

    public MeshEvent decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > maxBytes) throw new IOException("Invalid frame size");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC) throw new IOException("Invalid frame magic");
        int version = in.readUnsignedByte();
        if (version != 1) throw new IOException("Unsupported protocol version");
        String id = readString(in), origin = readString(in), sender = readString(in), recipient = readString(in);
        int typeValue = in.readUnsignedByte();
        if (typeValue >= MeshEvent.Type.values().length) throw new IOException("Invalid event type");
        long created = in.readLong(), expires = in.readLong();
        int hops = in.readUnsignedByte(), priority = in.readUnsignedByte();
        int length = in.readUnsignedShort();
        if (length > in.available()) throw new IOException("Truncated payload");
        byte[] payload = new byte[length];
        in.readFully(payload);
        if (in.available() != 0) throw new IOException("Trailing bytes");
        MeshEvent e = new MeshEvent(version, id, origin, sender, recipient,
                MeshEvent.Type.values()[typeValue], created, expires, hops, priority, payload);
        validate(e);
        return e;
    }

    private void validate(MeshEvent e) throws IOException {
        if (e == null || e.version != 1 || e.type == null || e.payload == null) throw new IOException("Invalid event");
        try {
            if (!UUID.fromString(e.id).toString().equalsIgnoreCase(e.id)) throw new IllegalArgumentException("Noncanonical UUID");
        } catch (RuntimeException ex) { throw new IOException("Invalid event ID", ex); }
        if (!validNode(e.originId) || !validNode(e.senderId) ||
                !(MeshEvent.BROADCAST.equals(e.recipientId) || validNode(e.recipientId))) throw new IOException("Invalid node ID");
        if (e.createdAtMs <= 0 || e.expiresAtMs <= e.createdAtMs || e.hopLimit < 0 || e.hopLimit > 32 ||
                e.priority < 0 || e.priority > 10 || e.payload.length > maxBytes) throw new IOException("Invalid fields");
        if (e.type == MeshEvent.Type.ACK && e.payload.length == 0) throw new IOException("Invalid ACK");
    }

    public static boolean validNode(String id) {
        if (id == null || id.length() < 8 || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') || c == '-')) return false;
        }
        return true;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 255) throw new IOException("String too long");
        out.writeByte(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedByte();
        byte[] b = new byte[length];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
