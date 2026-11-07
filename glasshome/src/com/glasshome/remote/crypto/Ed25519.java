package com.glasshome.remote.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

/** Ed25519 signatures (RFC 8032) on BigInteger, for the long-term keys in pair-setup and pair-verify. */
public final class Ed25519 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger Q = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] G;

    static {
        BigInteger gy = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger gx = recoverX(gy, 0);
        G = new BigInteger[]{gx, gy, BigInteger.ONE, gx.multiply(gy).mod(P)};
    }

    private Ed25519() {
    }

    public static byte[] publicKey(byte[] seed) {
        byte[] h = sha512(seed);
        return compress(mul(clampedScalar(h), G));
    }

    public static byte[] sign(byte[] seed, byte[] message) {
        byte[] h = sha512(seed);
        BigInteger a = clampedScalar(h);
        byte[] pub = compress(mul(a, G));
        BigInteger r = leMod(sha512(concat(Arrays.copyOfRange(h, 32, 64), message)));
        byte[] rEnc = compress(mul(r, G));
        BigInteger k = leMod(sha512(concat(rEnc, pub, message)));
        BigInteger s = r.add(k.multiply(a)).mod(Q);
        return concat(rEnc, Curve25519.toLe(s, 32));
    }

    public static boolean verify(byte[] pub, byte[] message, byte[] sig) {
        if (sig.length != 64 || pub.length != 32) {
            return false;
        }
        BigInteger[] a = decompress(pub);
        BigInteger[] r = decompress(Arrays.copyOfRange(sig, 0, 32));
        if (a == null || r == null) {
            return false;
        }
        BigInteger s = Curve25519.fromLe(Arrays.copyOfRange(sig, 32, 64));
        if (s.compareTo(Q) >= 0) {
            return false;
        }
        BigInteger k = leMod(sha512(concat(Arrays.copyOfRange(sig, 0, 32), pub, message)));
        BigInteger[] left = mul(s, G);
        BigInteger[] right = add(r, mul(k, a));
        return Arrays.equals(compress(left), compress(right));
    }

    // --- group arithmetic, extended coordinates (X, Y, Z, T)

    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = p[3].multiply(BigInteger.valueOf(2)).multiply(D).multiply(q[3]).mod(P);
        BigInteger d = p[2].multiply(BigInteger.valueOf(2)).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new BigInteger[]{e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, BigInteger[] point) {
        BigInteger[] result = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        BigInteger[] addend = point;
        for (int i = 0; i < s.bitLength(); i++) {
            if (s.testBit(i)) {
                result = add(result, addend);
            }
            addend = add(addend, addend);
        }
        return result;
    }

    private static byte[] compress(BigInteger[] p) {
        BigInteger zinv = p[2].modInverse(P);
        BigInteger x = p[0].multiply(zinv).mod(P), y = p[1].multiply(zinv).mod(P);
        byte[] out = Curve25519.toLe(y, 32);
        if (x.testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    private static BigInteger[] decompress(byte[] enc) {
        int sign = (enc[31] >> 7) & 1;
        byte[] yb = enc.clone();
        yb[31] &= 0x7f;
        BigInteger y = Curve25519.fromLe(yb);
        if (y.compareTo(P) >= 0) {
            return null;
        }
        BigInteger x = recoverX(y, sign);
        if (x == null) {
            return null;
        }
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger x2 = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P);
        if (x2.signum() == 0) {
            return sign == 1 ? null : BigInteger.ZERO;
        }
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) {
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) {
            return null;
        }
        if ((x.testBit(0) ? 1 : 0) != sign) {
            x = P.subtract(x);
        }
        return x;
    }

    private static BigInteger clampedScalar(byte[] h) {
        byte[] a = Arrays.copyOfRange(h, 0, 32);
        a[0] &= (byte) 248; a[31] &= 63; a[31] |= 64;
        return Curve25519.fromLe(a);
    }

    private static BigInteger leMod(byte[] h) {
        return Curve25519.fromLe(h).mod(Q);
    }

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
