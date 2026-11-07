package com.glasshome.remote.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The server side of SRP-6a as HomeKit-style pair-setup uses it: the 3072-bit group of RFC 5054, SHA-512, user name
 * "Pair-Setup" and the PIN as the password. The conventions (integers hashed as minimal big-endian bytes, K = H(S),
 * M2 = H(A | M1 | K)) are the ones the Python srptools library uses, which is what phones have been checked against.
 */
public final class Srp {
    private static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
            + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
            + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
            + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
            + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
            + "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
            + "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
            + "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864"
            + "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2"
            + "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
    private static final BigInteger G = BigInteger.valueOf(5);
    private static final int PAD = 384;
    private static final String USER = "Pair-Setup";

    private final BigInteger b;
    private final BigInteger v;
    private final BigInteger bigB;
    private final BigInteger saltInt;
    private final byte[] salt;
    private BigInteger bigA;
    private byte[] key;
    private byte[] m1;

    public Srp(String pin, SecureRandom random) {
        byte[] s = new byte[16];
        random.nextBytes(s);
        s[0] |= (byte) 0x80;   // minimal-length hashing of the salt only agrees with the phone when no byte is dropped
        salt = s;
        saltInt = new BigInteger(1, s);
        BigInteger x = hashInt(minimal(saltInt), hash((USER + ":" + pin).getBytes()));
        v = G.modPow(x, N);
        b = new BigInteger(1024, random);
        BigInteger k = hashInt(minimal(N), pad(G));
        bigB = k.multiply(v).add(G.modPow(b, N)).mod(N);
    }

    public byte[] salt() {
        return salt;
    }

    public byte[] publicKey() {
        return minimal(bigB);
    }

    /** Checks the phone's proof (M1); returns our proof (M2) or null if the PIN was wrong. */
    public byte[] verify(byte[] clientPublic, byte[] clientProof) {
        bigA = new BigInteger(1, clientPublic);
        if (bigA.mod(N).signum() == 0) {
            return null;
        }
        BigInteger u = hashInt(pad(bigA), pad(bigB));
        BigInteger s = bigA.multiply(v.modPow(u, N)).modPow(b, N);
        key = hash(minimal(s));
        BigInteger hn = hashInt(minimal(N));
        BigInteger hg = hashInt(minimal(G));
        m1 = hash(minimal(hn.xor(hg)), minimal(hashInt(USER.getBytes())), minimal(saltInt), minimal(bigA),
                minimal(bigB), key);
        if (!MessageDigest.isEqual(m1, clientProof)) {
            return null;
        }
        return hash(minimal(bigA), m1, key);
    }

    /** K, the shared secret both sides derive the pair-setup keys from. Only valid after a successful verify(). */
    public byte[] sessionKey() {
        return key;
    }

    // --- helpers

    static byte[] minimal(BigInteger v) {
        byte[] be = v.toByteArray();
        return be.length > 1 && be[0] == 0 ? Arrays.copyOfRange(be, 1, be.length) : be;
    }

    private static byte[] pad(BigInteger v) {
        byte[] m = minimal(v);
        byte[] out = new byte[PAD];
        System.arraycopy(m, 0, out, PAD - m.length, m.length);
        return out;
    }

    private static byte[] hash(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigInteger hashInt(byte[]... parts) {
        return new BigInteger(1, hash(parts));
    }
}
