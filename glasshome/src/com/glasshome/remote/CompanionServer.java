package com.glasshome.remote;

import com.glasshome.remote.crypto.ChaCha20Poly1305;
import com.glasshome.remote.crypto.Curve25519;
import com.glasshome.remote.crypto.Ed25519;
import com.glasshome.remote.crypto.Hkdf;
import com.glasshome.remote.crypto.Srp;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Apple TV side of the "Companion link" that the iPhone's Remote (Control Center) speaks: TCP frames of
 * (type, 3-byte length, payload); first pair-setup (a PIN shown on the TV) or pair-verify (a phone that has paired
 * before), then ChaCha20-Poly1305 encrypted OPACK messages carrying button presses and touches.
 *
 * It knows nothing about Android; keys leave through a {@link KeySink} and the PIN through a {@link PinDisplay}.
 * Protocol facts come from pyatv (MIT), whose test server and client were used to check this one, and from what a
 * real iPhone sent in testing.
 */
public final class CompanionServer {
    public interface Log {
        void d(String message);
    }

    // frame types
    private static final int NOOP = 1, PS_START = 3, PS_NEXT = 4, PV_START = 5, PV_NEXT = 6, U_OPACK = 7, E_OPACK = 8;
    private static final int MAX_FRAME = 1 << 20;

    /** _hidC button numbers -> Android key codes. */
    private static final int[][] BUTTONS = {
            {1, 19}, {2, 20}, {3, 21}, {4, 22},   // up, down, left, right
            {5, 4},                               // menu -> back
            {6, 23},                              // select
            {7, 3},                               // TV -> home
            {8, 24}, {9, 25}, {18, 164},          // volume up, down, mute
            {14, 85},                             // play/pause
            {19, 26},                             // power
    };

    private final PairingStore store;
    private final KeySink keys;
    private final PinDisplay pinDisplay;
    private final Log log;
    private final int swipeStep;
    private final SecureRandom random = new SecureRandom();
    private final List<Socket> open = new ArrayList<>();
    private ServerSocket server;
    private volatile boolean running;

    public CompanionServer(PairingStore store, KeySink keys, PinDisplay pinDisplay, Log log, int swipeStep) {
        this.store = store;
        this.keys = keys;
        this.pinDisplay = pinDisplay;
        this.log = log;
        this.swipeStep = swipeStep;
    }

    /** Starts listening; tries the port used last time first. Returns the port actually bound. */
    public synchronized int start(int preferredPort) throws IOException {
        try {
            server = new ServerSocket(preferredPort, 8, (InetAddress) null);
        } catch (IOException e) {
            server = new ServerSocket(0, 8, (InetAddress) null);
        }
        running = true;
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "companion-accept");
        accept.setDaemon(true);
        accept.start();
        return server.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // closing anyway
        }
        synchronized (open) {
            for (Socket s : open) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
            open.clear();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                s.setTcpNoDelay(true);
                synchronized (open) {
                    open.add(s);
                }
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            new Connection(s).run();
                        } catch (IOException e) {
                            log.d("could not start a connection: " + e);
                        } finally {
                            synchronized (open) {
                                open.remove(s);
                            }
                        }
                    }
                }, "companion-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    log.d("accept failed: " + e);
                }
            }
        }
    }

    private final class Connection {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private final Gestures gestures = new Gestures(keys, swipeStep);

        private byte[] outKey, inKey;
        private long outCounter, inCounter;
        private boolean encrypted;

        // pair-setup
        private Srp srp;
        // pair-verify
        private byte[] verifyKey, clientPub, serverPub, sharedSecret;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void run() {
            log.d("phone connected from " + socket.getRemoteSocketAddress());
            try {
                while (true) {
                    byte[] header = new byte[4];
                    in.readFully(header);
                    int type = header[0] & 0xff;
                    int len = (header[1] & 0xff) << 16 | (header[2] & 0xff) << 8 | (header[3] & 0xff);
                    if (len > MAX_FRAME) {
                        throw new IOException("frame too large: " + len);
                    }
                    byte[] payload = new byte[len];
                    in.readFully(payload);
                    if (encrypted && len > 0) {
                        payload = ChaCha20Poly1305.decrypt(inKey, counterNonce(inCounter++), header, payload);
                    }
                    handle(type, payload);
                }
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                log.d("phone disconnected: " + e);
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }

        private void handle(int type, byte[] payload) throws IOException, GeneralSecurityException {
            if (type == NOOP || payload.length == 0) {
                return;
            }
            Opack.Result r = Opack.unpack(payload);
            Map<String, Object> msg = r.map();
            if (msg == null) {
                log.d("ignoring a frame of type " + type + " that is not a dictionary");
                return;
            }
            switch (type) {
                case PS_START:
                case PS_NEXT:
                    pairSetup(msg);
                    break;
                case PV_START:
                case PV_NEXT:
                    pairVerify(msg);
                    break;
                case E_OPACK:
                case U_OPACK:
                    if (encrypted) {
                        message(msg);
                    }
                    break;
                default:
                    break;
            }
        }

        // ---------------------------------------------------------------- pair-setup (a new phone, with the PIN)

        private void pairSetup(Map<String, Object> msg) throws IOException, GeneralSecurityException {
            Map<Integer, byte[]> tlv = Tlv8.read((byte[]) msg.get("_pd"));
            int seq = tlv.get(Tlv8.SEQ)[0];
            if (seq == 1) {
                String pin = String.format("%04d", random.nextInt(10000));
                srp = new Srp(pin, random);
                pinDisplay.show(pin);
                log.d("pairing started; PIN shown on the TV");
                sendAuth(PS_NEXT, Tlv8.write(Tlv8.SEQ, Tlv8.one(2), Tlv8.SALT, srp.salt(),
                        Tlv8.PUBLIC_KEY, srp.publicKey(), 27, Tlv8.one(1)), true);
            } else if (seq == 3 && srp != null) {
                byte[] proof = srp.verify(tlv.get(Tlv8.PUBLIC_KEY), tlv.get(Tlv8.PROOF));
                if (proof == null) {
                    log.d("wrong PIN");
                    sendAuth(PS_NEXT, Tlv8.write(Tlv8.ERROR, Tlv8.one(Tlv8.ERROR_AUTHENTICATION), Tlv8.SEQ, Tlv8.one(4)),
                            false);
                    pinDisplay.hide();
                    srp = null;
                    return;
                }
                sendAuth(PS_NEXT, Tlv8.write(Tlv8.PROOF, proof, Tlv8.SEQ, Tlv8.one(4)), false);
            } else if (seq == 5 && srp != null) {
                byte[] k = srp.sessionKey();
                byte[] sessionKey = Hkdf.key("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", k);
                byte[] plain = ChaCha20Poly1305.decrypt(sessionKey, tagNonce("PS-Msg05"), new byte[0],
                        tlv.get(Tlv8.ENCRYPTED));
                Map<Integer, byte[]> inner = Tlv8.read(plain);
                byte[] controllerX = Hkdf.key("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", k);
                byte[] clientId = inner.get(Tlv8.IDENTIFIER);
                byte[] clientKey = inner.get(Tlv8.PUBLIC_KEY);
                if (!Ed25519.verify(clientKey, concat(controllerX, clientId, clientKey), inner.get(Tlv8.SIGNATURE))) {
                    log.d("the phone's signature did not verify");
                    sendAuth(PS_NEXT, Tlv8.write(Tlv8.SEQ, Tlv8.one(6), Tlv8.ERROR,
                            Tlv8.one(Tlv8.ERROR_AUTHENTICATION)), false);
                    return;
                }
                store.addClient(new String(clientId, "UTF-8"), clientKey);

                byte[] seed = store.seed();
                byte[] serverId = store.identifier().getBytes("UTF-8");
                byte[] serverKey = Ed25519.publicKey(seed);
                byte[] accessoryX = Hkdf.key("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", k);
                byte[] signature = Ed25519.sign(seed, concat(accessoryX, serverId, serverKey));
                byte[] info = Tlv8.write(Tlv8.IDENTIFIER, serverId, Tlv8.PUBLIC_KEY, serverKey,
                        Tlv8.SIGNATURE, signature, Tlv8.NAME, Opack.pack(accessoryInfo()));
                byte[] sealed = ChaCha20Poly1305.encrypt(sessionKey, tagNonce("PS-Msg06"), new byte[0], info);
                sendAuth(PS_NEXT, Tlv8.write(Tlv8.SEQ, Tlv8.one(6), Tlv8.ENCRYPTED, sealed), false);
                pinDisplay.hide();
                srp = null;
                log.d("paired with " + new String(clientId, "UTF-8"));
            }
        }

        private Map<String, Object> accessoryInfo() {
            byte[] mac = new byte[6];
            byte[] irk = new byte[16];
            random.nextBytes(mac);
            random.nextBytes(irk);
            return Opack.map("altIRK", irk, "accountID", java.util.UUID.nameUUIDFromBytes(store.seed()).toString(),
                    "model", "AppleTV14,1", "wifiMAC", mac, "name", store.name(), "mac", mac);
        }

        // ---------------------------------------------------------------- pair-verify (a phone that paired before)

        private void pairVerify(Map<String, Object> msg) throws IOException, GeneralSecurityException {
            Map<Integer, byte[]> tlv = Tlv8.read((byte[]) msg.get("_pd"));
            int seq = tlv.get(Tlv8.SEQ)[0];
            if (seq == 1) {
                clientPub = tlv.get(Tlv8.PUBLIC_KEY);
                byte[] secret = new byte[32];
                random.nextBytes(secret);
                serverPub = Curve25519.publicKey(secret);
                sharedSecret = Curve25519.scalarMult(secret, clientPub);
                verifyKey = Hkdf.key("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", sharedSecret);

                byte[] serverId = store.identifier().getBytes("UTF-8");
                byte[] signature = Ed25519.sign(store.seed(), concat(serverPub, serverId, clientPub));
                byte[] sealed = ChaCha20Poly1305.encrypt(verifyKey, tagNonce("PV-Msg02"), new byte[0],
                        Tlv8.write(Tlv8.IDENTIFIER, serverId, Tlv8.SIGNATURE, signature));
                sendAuth(PV_NEXT, Tlv8.write(Tlv8.SEQ, Tlv8.one(2), Tlv8.PUBLIC_KEY, serverPub, Tlv8.ENCRYPTED, sealed),
                        false);
            } else if (seq == 3 && verifyKey != null) {
                Map<Integer, byte[]> inner = Tlv8.read(ChaCha20Poly1305.decrypt(verifyKey, tagNonce("PV-Msg03"),
                        new byte[0], tlv.get(Tlv8.ENCRYPTED)));
                String clientId = new String(inner.get(Tlv8.IDENTIFIER), "UTF-8");
                byte[] known = store.client(clientId);
                if (known == null || !Ed25519.verify(known, concat(clientPub, inner.get(Tlv8.IDENTIFIER), serverPub),
                        inner.get(Tlv8.SIGNATURE))) {
                    log.d("unknown phone or bad signature in pair-verify: " + clientId);
                    sendAuth(PV_NEXT, Tlv8.write(Tlv8.SEQ, Tlv8.one(4), Tlv8.ERROR,
                            Tlv8.one(Tlv8.ERROR_AUTHENTICATION)), false);
                    return;
                }
                sendAuth(PV_NEXT, Tlv8.write(Tlv8.SEQ, Tlv8.one(4)), false);
                outKey = Hkdf.key("", "ServerEncrypt-main", sharedSecret);
                inKey = Hkdf.key("", "ClientEncrypt-main", sharedSecret);
                encrypted = true;
                log.d("phone verified: " + clientId);
            }
        }

        private void sendAuth(int type, byte[] tlv, boolean passwordType) throws IOException {
            Map<String, Object> m = Opack.map("_pd", tlv);
            if (passwordType) {
                m.put("_pwTy", 1L);
            }
            frame(type, Opack.pack(m));
        }

        // ---------------------------------------------------------------- the session itself

        private void message(Map<String, Object> msg) throws IOException {
            Object ident = msg.get("_i");
            long kind = Opack.asLong(msg.get("_t"), 0);
            Object xid = msg.get("_x");
            Object content = msg.get("_c");
            Map<String, Object> c = content instanceof Map ? castMap(content) : new java.util.HashMap<String, Object>();
            Map<String, Object> reply = new java.util.LinkedHashMap<>();

            if ("_hidC".equals(ident)) {
                int key = keyFor((int) Opack.asLong(c.get("_hidC"), -1));
                long state = Opack.asLong(c.get("_hBtS"), 0);
                if (key > 0) {
                    if (state == 1) {
                        keys.down(key);
                    } else if (state == 2) {
                        keys.up(key);
                    }
                }
            } else if ("_hidT".equals(ident)) {
                gestures.touch((int) Opack.asLong(c.get("_tPh"), 0), (int) Opack.asLong(c.get("_cx"), 0),
                        (int) Opack.asLong(c.get("_cy"), 0));
            } else if ("_mcc".equals(ident)) {
                int media = (int) Opack.asLong(c.get("_mcc"), 0);
                int code = media == 1 ? 126 : media == 2 ? 127 : media == 3 ? 87 : media == 4 ? 88 : 0;
                if (code != 0) {
                    keys.key(code);
                }
            } else if ("_touchStart".equals(ident)) {
                reply.put("_i", 1L);
            } else if ("FetchMediaControlStatus".equals(ident)) {
                reply.put("MediaControlFlags", 256L);
            } else if ("_sessionStart".equals(ident)) {
                reply.put("_sid", 0x1A2B3C4DL);
            }

            if (kind == 2 && xid != null) {   // requests want an answer carrying the same _x; events do not
                frame(E_OPACK, Opack.pack(Opack.map("_c", reply, "_t", 3L, "_x", xid, "_i", ident)));
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> castMap(Object o) {
            return (Map<String, Object>) o;
        }

        private int keyFor(int button) {
            for (int[] b : BUTTONS) {
                if (b[0] == button) {
                    return b[1];
                }
            }
            return 0;
        }

        // ---------------------------------------------------------------- framing

        private synchronized void frame(int type, byte[] payload) throws IOException {
            int len = payload.length + (encrypted && payload.length > 0 ? ChaCha20Poly1305.TAG : 0);
            byte[] header = {(byte) type, (byte) (len >> 16), (byte) (len >> 8), (byte) len};
            byte[] body = payload;
            if (encrypted && payload.length > 0) {
                body = ChaCha20Poly1305.encrypt(outKey, counterNonce(outCounter++), header, payload);
            }
            ByteArrayOutputStream both = new ByteArrayOutputStream();
            both.write(header);
            both.write(body);
            out.write(both.toByteArray());
            out.flush();
        }
    }

    /** The 12-byte nonce of the session: a little-endian counter. */
    private static byte[] counterNonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) {
            n[i] = (byte) (counter >>> (8 * i));
        }
        return n;
    }

    /** The nonce of one pairing message: four zero bytes, then its eight-character name. */
    private static byte[] tagNonce(String name) {
        byte[] n = new byte[12];
        byte[] s = name.getBytes();
        System.arraycopy(s, 0, n, 4, 8);
        return n;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
