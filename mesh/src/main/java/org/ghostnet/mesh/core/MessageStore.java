package org.ghostnet.mesh.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Small atomic snapshot store. Synchronize access through MeshRouter. */
public final class MessageStore {
    public static final class Pending {
        public MeshEvent event;
        public String excludedPeer;
        public int attempts;
        public long nextRetryMs;
        Pending(MeshEvent event, String excludedPeer) { this.event = event; this.excludedPeer = excludedPeer; }
    }
    private final File file;
    private final MessageCodec codec;
    final Map<String, Long> seen = new HashMap<>();
    final Map<String, Pending> pending = new HashMap<>();
    final Map<String, DeliveryStatus> statuses = new HashMap<>();
    final Map<String, String> reversePeer = new HashMap<>();

    public MessageStore(File file, MessageCodec codec) throws IOException {
        this.file = file;
        this.codec = codec;
        load();
    }

    public void save() throws IOException {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create store directory");
        File temp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temp))) {
            out.writeInt(0x474e5354);
            out.writeInt(1);
            out.writeInt(seen.size());
            for (Map.Entry<String, Long> x : seen.entrySet()) { out.writeUTF(x.getKey()); out.writeLong(x.getValue()); }
            out.writeInt(pending.size());
            for (Pending p : pending.values()) {
                byte[] data = codec.encode(p.event);
                out.writeInt(data.length); out.write(data);
                out.writeUTF(p.excludedPeer == null ? "" : p.excludedPeer);
                out.writeInt(p.attempts); out.writeLong(p.nextRetryMs);
            }
            out.writeInt(statuses.size());
            for (Map.Entry<String, DeliveryStatus> x : statuses.entrySet()) {
                out.writeUTF(x.getKey()); out.writeUTF(x.getValue().name());
            }
            out.writeInt(reversePeer.size());
            for (Map.Entry<String, String> x : reversePeer.entrySet()) { out.writeUTF(x.getKey()); out.writeUTF(x.getValue()); }
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void load() throws IOException {
        if (file == null || !file.exists()) return;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != 0x474e5354 || in.readInt() != 1) throw new IOException("Invalid store version");
            int count = checkedCount(in.readInt());
            for (int i = 0; i < count; i++) seen.put(in.readUTF(), in.readLong());
            count = checkedCount(in.readInt());
            for (int i = 0; i < count; i++) {
                int size = in.readInt();
                if (size < 1 || size > MessageCodec.MAX_BYTES) throw new IOException("Invalid stored frame");
                byte[] bytes = new byte[size]; in.readFully(bytes);
                MeshEvent e = codec.decode(bytes);
                String excluded = in.readUTF();
                Pending p = new Pending(e, excluded.isEmpty() ? null : excluded);
                p.attempts = in.readInt(); p.nextRetryMs = in.readLong();
                pending.put(e.id, p);
            }
            count = checkedCount(in.readInt());
            for (int i = 0; i < count; i++) statuses.put(in.readUTF(), DeliveryStatus.valueOf(in.readUTF()));
            count = checkedCount(in.readInt());
            for (int i = 0; i < count; i++) reversePeer.put(in.readUTF(), in.readUTF());
        }
    }

    private static int checkedCount(int n) throws IOException {
        if (n < 0 || n > 10000) throw new IOException("Invalid store count");
        return n;
    }

    public void prune(long now) {
        for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue() < now) it.remove();
        }
        if (seen.size() > 5000) {
            List<Map.Entry<String, Long>> oldest = new ArrayList<>(seen.entrySet());
            oldest.sort(Comparator.comparingLong(Map.Entry::getValue));
            for (int i = 0; i < oldest.size() - 5000; i++) seen.remove(oldest.get(i).getKey());
        }
        reversePeer.keySet().retainAll(seen.keySet());
    }
}
