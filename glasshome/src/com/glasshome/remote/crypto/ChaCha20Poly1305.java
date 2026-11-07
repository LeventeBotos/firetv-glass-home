package com.glasshome.remote.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** ChaCha20-Poly1305 AEAD (RFC 8439). Android 7 has no provider for it, so it is written out here. */
public final class ChaCha20Poly1305 {
    public static final int TAG = 16;
    private static final BigInteger P1305 = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));

    private ChaCha20Poly1305() {
    }

    /** Returns ciphertext followed by the 16-byte tag. */
    public static byte[] encrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plain) {
        byte[] cipher = xor(key, nonce, 1, plain);
        byte[] tag = tag(key, nonce, aad, cipher);
        byte[] out = new byte[cipher.length + TAG];
        System.arraycopy(cipher, 0, out, 0, cipher.length);
        System.arraycopy(tag, 0, out, cipher.length, TAG);
        return out;
    }

    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) throws GeneralSecurityException {
        if (sealed.length < TAG) {
            throw new GeneralSecurityException("too short");
        }
        byte[] cipher = new byte[sealed.length - TAG];
        byte[] given = new byte[TAG];
        System.arraycopy(sealed, 0, cipher, 0, cipher.length);
        System.arraycopy(sealed, cipher.length, given, 0, TAG);
        if (!MessageDigest.isEqual(given, tag(key, nonce, aad, cipher))) {
            throw new GeneralSecurityException("bad tag");
        }
        return xor(key, nonce, 1, cipher);
    }

    private static byte[] tag(byte[] key, byte[] nonce, byte[] aad, byte[] cipher) {
        byte[] otk = block(key, nonce, 0);
        byte[] r = new byte[16];
        System.arraycopy(otk, 0, r, 0, 16);
        r[3] &= 15; r[7] &= 15; r[11] &= 15; r[15] &= 15;
        r[4] &= (byte) 252; r[8] &= (byte) 252; r[12] &= (byte) 252;
        BigInteger rr = le(r, 0, 16);
        BigInteger s = le(otk, 16, 16);

        byte[] mac = new byte[pad16(aad.length) + pad16(cipher.length) + 16];
        System.arraycopy(aad, 0, mac, 0, aad.length);
        System.arraycopy(cipher, 0, mac, pad16(aad.length), cipher.length);
        int at = pad16(aad.length) + pad16(cipher.length);
        putLe64(mac, at, aad.length);
        putLe64(mac, at + 8, cipher.length);

        BigInteger acc = BigInteger.ZERO;
        for (int i = 0; i < mac.length; i += 16) {
            int n = Math.min(16, mac.length - i);
            BigInteger block = le(mac, i, n).add(BigInteger.ONE.shiftLeft(8 * n));
            acc = acc.add(block).multiply(rr).mod(P1305);
        }
        acc = acc.add(s).mod(BigInteger.ONE.shiftLeft(128));
        byte[] tag = new byte[16];
        byte[] be = acc.toByteArray();
        for (int i = 0; i < be.length && i < 16 + 1; i++) {
            int dst = be.length - 1 - i;   // be[dst] is byte i counting from the least significant end
            if (i < 16) {
                tag[i] = be[dst];
            }
        }
        return tag;
    }

    private static int pad16(int n) {
        return (n + 15) / 16 * 16;
    }

    private static void putLe64(byte[] b, int at, long v) {
        for (int i = 0; i < 8; i++) {
            b[at + i] = (byte) (v >>> (8 * i));
        }
    }

    private static BigInteger le(byte[] b, int off, int len) {
        byte[] be = new byte[len];
        for (int i = 0; i < len; i++) {
            be[len - 1 - i] = b[off + i];
        }
        return new BigInteger(1, be);
    }

    private static byte[] xor(byte[] key, byte[] nonce, int counter, byte[] data) {
        byte[] out = new byte[data.length];
        for (int off = 0; off < data.length; off += 64, counter++) {
            byte[] ks = block(key, nonce, counter);
            int n = Math.min(64, data.length - off);
            for (int i = 0; i < n; i++) {
                out[off + i] = (byte) (data[off + i] ^ ks[i]);
            }
        }
        return out;
    }

    private static int rotl(int v, int c) {
        return (v << c) | (v >>> (32 - c));
    }

    private static void qr(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = rotl(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = rotl(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = rotl(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = rotl(x[b] ^ x[c], 7);
    }

    private static int word(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    private static byte[] block(byte[] key, byte[] nonce, int counter) {
        int[] s = new int[16];
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) {
            s[4 + i] = word(key, 4 * i);
        }
        s[12] = counter;
        for (int i = 0; i < 3; i++) {
            s[13 + i] = word(nonce, 4 * i);
        }
        int[] x = s.clone();
        for (int i = 0; i < 10; i++) {
            qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15);
            qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14);
        }
        byte[] out = new byte[64];
        for (int i = 0; i < 16; i++) {
            int v = x[i] + s[i];
            out[4 * i] = (byte) v; out[4 * i + 1] = (byte) (v >>> 8);
            out[4 * i + 2] = (byte) (v >>> 16); out[4 * i + 3] = (byte) (v >>> 24);
        }
        return out;
    }
}
