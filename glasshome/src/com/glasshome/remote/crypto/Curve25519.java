package com.glasshome.remote.crypto;

import java.math.BigInteger;

/** X25519 (RFC 7748), the Diffie-Hellman half of pair-verify. Android 7 has no provider for it. */
public final class Curve25519 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    private Curve25519() {
    }

    public static byte[] publicKey(byte[] secret) {
        byte[] nine = new byte[32];
        nine[0] = 9;
        return scalarMult(secret, nine);
    }

    public static byte[] scalarMult(byte[] scalar, byte[] uBytes) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248; k[31] &= 127; k[31] |= 64;
        byte[] u = uBytes.clone();
        u[31] &= 127;
        BigInteger kk = fromLe(k);
        BigInteger x1 = fromLe(u).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int bit = kk.testBit(t) ? 1 : 0;
            swap ^= bit;
            if (swap == 1) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = bit;
            BigInteger a = x2.add(z2).mod(P), aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P), bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P), d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P), cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P), df = da.subtract(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            z3 = x1.multiply(df.multiply(df).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap == 1) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        return toLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P), 32);
    }

    static BigInteger fromLe(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            be[b.length - 1 - i] = b[i];
        }
        return new BigInteger(1, be);
    }

    static byte[] toLe(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < be.length && i < len + 1; i++) {
            int src = be.length - 1 - i;
            if (i < len) {
                out[i] = be[src];
            }
        }
        return out;
    }
}
