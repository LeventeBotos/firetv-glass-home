package com.glasshome.remote;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the stick has to remember to look like the same Apple TV after a restart: its identity (a random id and a random
 * Ed25519 seed, made once) and the long-term public key of every phone that has paired. Backed by a plain key-value
 * {@link Storage} so it also runs outside Android (SharedPreferences on the stick).
 */
public final class PairingStore {
    public interface Storage {
        String get(String key);

        void put(String key, String value);
    }

    public static final String DEFAULT_NAME = "Nappali";

    private final Storage storage;

    public PairingStore(Storage storage) {
        this.storage = storage;
    }

    public synchronized String name() {
        String n = storage.get("name");
        return n == null || n.isEmpty() ? DEFAULT_NAME : n;
    }

    public synchronized void setName(String name) {
        storage.put("name", name);
    }

    /** The id the phone sees in pair-verify (an upper-case UUID, like a real Apple TV's). */
    public synchronized String identifier() {
        String id = storage.get("id");
        if (id == null) {
            id = UUID.randomUUID().toString().toUpperCase();
            storage.put("id", id);
        }
        return id;
    }

    public synchronized byte[] seed() {
        String hex = storage.get("seed");
        if (hex == null) {
            byte[] s = new byte[32];
            new SecureRandom().nextBytes(s);
            hex = hex(s);
            storage.put("seed", hex);
        }
        return unhex(hex);
    }

    public synchronized int port() {
        try {
            return Integer.parseInt(storage.get("port"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** iOS caches the port it resolved from mDNS, so the same one is reused across restarts when it can be. */
    public synchronized void setPort(int port) {
        storage.put("port", Integer.toString(port));
    }

    public synchronized void addClient(String id, byte[] publicKey) {
        Map<String, byte[]> all = clients();
        all.put(id, publicKey);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, byte[]> e : all.entrySet()) {
            sb.append(e.getKey()).append('=').append(hex(e.getValue())).append('\n');
        }
        storage.put("clients", sb.toString());
    }

    public synchronized byte[] client(String id) {
        return clients().get(id);
    }

    public synchronized int clientCount() {
        return clients().size();
    }

    public synchronized void forgetAll() {
        storage.put("clients", "");
        storage.put("id", null);   // a new identity too, so phones that remembered the old one start over
        storage.put("seed", null);
    }

    private Map<String, byte[]> clients() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        String raw = storage.get("clients");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    out.put(line.substring(0, eq), unhex(line.substring(eq + 1)));
                }
            }
        }
        return out;
    }

    /** The mDNS TXT records of an Apple TV. The values are what phones accepted in testing; the hex ones are only ids. */
    public Map<String, String> txtRecords() {
        String id = identifier();
        Map<String, String> t = new LinkedHashMap<>();
        t.put("rpMac", "1");
        t.put("rpHA", derived(id, "rpHA"));
        t.put("rpHN", derived(id, "rpHN"));
        t.put("rpVr", "715.2");
        t.put("rpMd", "AppleTV14,1");
        t.put("rpFl", "0x36782");
        t.put("rpAD", derived(id, "rpAD"));
        t.put("rpHI", derived(id, "rpHI"));
        t.put("rpBA", bluetoothStyle(id));
        t.put("rpMRtID", id);
        return t;
    }

    private static byte[] six(String id, String field) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("Nappali:" + id + ":" + field).getBytes("UTF-8"));
            byte[] out = new byte[6];
            System.arraycopy(h, 0, out, 0, 6);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String derived(String id, String field) {
        return hex(six(id, field));
    }

    private static String bluetoothStyle(String id) {
        byte[] b = six(id, "rpBA");
        b[0] = (byte) ((b[0] | 2) & 0xfe);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", b[i] & 0xff));
        }
        return sb.toString();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
