package com.glasshome.remote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/**
 * A small ADB client: just enough of the protocol to connect to an adbd (here the stick's own, on 127.0.0.1:5555),
 * prove who it is with an RSA key, and run one shell command with its input and output. The first time a key is used
 * the TV shows "Allow USB debugging?"; once "Always allow" is ticked it is remembered by the stick.
 *
 * Fire OS's adbd serves one client at a time, so callers connect, do the one thing, and close.
 */
public final class AdbClient implements Closeable {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int A_SYNC = 0x434e5953, A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    // adb strings end in a NUL byte; written as a runtime value because the dex tool crashes on "\0" in a string constant
    private static final String NUL = String.valueOf((char) 0);
    private static final int VERSION = 0x01000000;   // the old protocol: 4 KB payloads, checksums on
    private static final int MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // DER prefix of a SHA-1 DigestInfo: adb signs the 20-byte token as if it were already a SHA-1 hash
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14};

    private final Socket socket = new Socket();
    private InputStream in;
    private OutputStream out;
    private int remoteId;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean closed;

    /** An open command: what it prints can be read, and what it reads can be written. */
    public final class Shell {
        public void write(String text) throws IOException {
            send(A_WRTE, 1, remoteId, text.getBytes(UTF8));
            // the stream must be acknowledged before the next write; reading handles that
            awaitOkay();
        }

        /** Reads output until `marker` has appeared, the command ended, or the time is up. Returns what was read. */
        public String readUntil(String marker, long timeoutMs) throws IOException {
            long end = System.currentTimeMillis() + timeoutMs;
            while (pending.toString("UTF-8").indexOf(marker) < 0 && !closed) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                pump(left);
            }
            String all = pending.toString("UTF-8");
            pending.reset();
            return all;
        }
    }

    public AdbClient(String host, int port, int connectTimeoutMs) throws IOException {
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setTcpNoDelay(true);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    /**
     * Connects and authenticates. `approvalTimeoutMs` is how long to wait for someone to tick the prompt on the TV the
     * first time. `onApproval` (may be null) runs when that prompt is up.
     */
    public void authenticate(KeyPair keys, String keyName, long approvalTimeoutMs, Runnable onApproval) throws IOException {
        try {
            doAuthenticate(keys, keyName, approvalTimeoutMs, onApproval);
        } catch (IOException e) {
            throw new IOException("while " + step + ": " + e.getMessage(), e);
        }
    }

    private String step = "connecting";

    private void doAuthenticate(KeyPair keys, String keyName, long approvalTimeoutMs, Runnable onApproval) throws IOException {
        step = "sending the connect request";
        send(A_CNXN, VERSION, MAX_DATA, ("host::" + NUL).getBytes(UTF8));
        boolean signed = false, offered = false;
        long deadline = System.currentTimeMillis() + approvalTimeoutMs;
        while (true) {
            step = "waiting for the stick's answer";
            Packet p = read(Math.max(1000, deadline - System.currentTimeMillis()));
            if (p.cmd == A_CNXN) {
                return;
            }
            if (p.cmd != A_AUTH || p.arg0 != AUTH_TOKEN) {
                throw new IOException("unexpected packet during connect: " + Integer.toHexString(p.cmd));
            }
            if (!signed) {
                signed = true;
                step = "signing the stick's challenge";
                byte[] signature = sign(keys, p.data);
                step = "sending the signature";
                send(A_AUTH, AUTH_SIGNATURE, 0, signature);
            } else if (!offered) {
                offered = true;
                step = "offering the key (the prompt on the TV)";
                byte[] key = (Base64Compat.encode(publicKeyBlob((RSAPublicKey) keys.getPublic())) + " " + keyName + NUL)
                        .getBytes(UTF8);
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key);
                if (onApproval != null) {
                    onApproval.run();
                }
            } else if (System.currentTimeMillis() > deadline) {
                throw new IOException("the prompt on the TV was not accepted in time");
            }
        }
    }

    public Shell shell(String command) throws IOException {
        send(A_OPEN, 1, 0, ("shell:" + command + NUL).getBytes(UTF8));
        while (true) {
            Packet p = read(10000);
            if (p.cmd == A_OKAY) {
                remoteId = p.arg0;
                return new Shell();
            }
            if (p.cmd == A_CLSE) {
                throw new IOException("the stick refused the shell");
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    // ---- packets

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) {
            sum += b & 0xff;
        }
        byte[] h = new byte[24];
        put(h, 0, cmd);
        put(h, 4, arg0);
        put(h, 8, arg1);
        put(h, 12, data.length);
        put(h, 16, sum);
        put(h, 20, cmd ^ 0xffffffff);
        out.write(h);
        out.write(data);
        out.flush();
    }

    private Packet read(long timeoutMs) throws IOException {
        socket.setSoTimeout((int) Math.max(1, Math.min(timeoutMs, Integer.MAX_VALUE)));
        byte[] h = new byte[24];
        readFully(h);
        Packet p = new Packet();
        p.cmd = get(h, 0);
        p.arg0 = get(h, 4);
        p.arg1 = get(h, 8);
        int len = get(h, 12);
        if (len < 0 || len > (1 << 20)) {
            throw new IOException("bad packet length " + len);
        }
        p.data = new byte[len];
        readFully(p.data);
        return p;
    }

    /** Handles one incoming packet while a shell is open: output is queued and acknowledged. */
    private void pump(long timeoutMs) throws IOException {
        Packet p;
        try {
            p = read(timeoutMs);
        } catch (SocketTimeoutException e) {
            return;
        }
        if (p.cmd == A_WRTE) {
            pending.write(p.data, 0, p.data.length);
            send(A_OKAY, 1, p.arg0, new byte[0]);
        } else if (p.cmd == A_CLSE) {
            closed = true;
        }
    }

    private void awaitOkay() throws IOException {
        while (true) {
            Packet p = read(5000);
            if (p.cmd == A_OKAY) {
                return;
            }
            if (p.cmd == A_WRTE) {
                pending.write(p.data, 0, p.data.length);
                send(A_OKAY, 1, p.arg0, new byte[0]);
            } else if (p.cmd == A_CLSE) {
                closed = true;
                throw new IOException("the command ended");
            }
        }
    }

    private void readFully(byte[] b) throws IOException {
        int at = 0;
        while (at < b.length) {
            int n = in.read(b, at, b.length - at);
            if (n < 0) {
                throw new IOException("the stick closed the connection");
            }
            at += n;
        }
    }

    private static void put(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }

    private static int get(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    // ---- keys

    private static byte[] sign(KeyPair keys, byte[] token) throws IOException {
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(keys.getPrivate());
            s.update(SHA1_PREFIX);
            s.update(token);
            return s.sign();
        } catch (Exception e) {
            throw new IOException("could not sign: " + e);
        }
    }

    /** The key in the layout adbd reads: length in words, -1/n[0] mod 2^32, n, R^2 mod n (all little-endian), exponent. */
    static byte[] publicKeyBlob(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        int words = (n.bitLength() + 31) / 32;
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = two32.subtract(n.mod(two32).modInverse(two32)).longValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(32 * words * 2).mod(n);
        byte[] blob = new byte[12 + 8 * words];
        put(blob, 0, words);
        put(blob, 4, (int) n0inv);
        littleEndian(n, blob, 8, 4 * words);
        littleEndian(rr, blob, 8 + 4 * words, 4 * words);
        put(blob, 8 + 8 * words, key.getPublicExponent().intValue());
        return blob;
    }

    private static void littleEndian(BigInteger v, byte[] dst, int off, int len) {
        byte[] be = v.toByteArray();
        for (int i = 0; i < len && i < be.length; i++) {
            dst[off + i] = be[be.length - 1 - i];
        }
    }
}
