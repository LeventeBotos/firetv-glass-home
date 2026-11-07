import com.glasshome.remote.Gestures;
import com.glasshome.remote.KeySink;
import com.glasshome.remote.Opack;
import com.glasshome.remote.Tlv8;
import com.glasshome.remote.crypto.ChaCha20Poly1305;
import com.glasshome.remote.crypto.Curve25519;
import com.glasshome.remote.crypto.Ed25519;
import com.glasshome.remote.crypto.Hkdf;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.SecureRandom;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Checks the hand-written crypto against the RFC test vectors and, with random inputs, against the JDK's own
 * implementations (the stick's Android 7 has none of these, so the app carries its own). Run by tests/test_remote_java.py.
 */
public class CryptoTest {
    static int failures;
    static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws Exception {
        x25519();
        ed25519();
        chacha();
        hkdf();
        opack();
        tlv();
        gestures();
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("all ok");
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    static byte[] h(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static void x25519() throws Exception {
        byte[] out = Curve25519.scalarMult(h("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                h("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"));
        check("X25519 RFC 7748 vector", hex(out).equals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"));

        // both sides of a key agreement arrive at the same secret, and the JDK agrees with it
        boolean allSame = true;
        for (int i = 0; i < 5; i++) {
            byte[] a = random(32), b = random(32);
            byte[] ab = Curve25519.scalarMult(a, Curve25519.publicKey(b));
            byte[] ba = Curve25519.scalarMult(b, Curve25519.publicKey(a));
            allSame &= Arrays.equals(ab, ba);
            java.security.KeyPairGenerator unused = null;
            javax.crypto.KeyAgreement ka = javax.crypto.KeyAgreement.getInstance("X25519");
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("X25519");
            ka.init(kf.generatePrivate(new java.security.spec.XECPrivateKeySpec(NamedParameterSpec.X25519, a)));
            byte[] pubB = Curve25519.publicKey(b);
            byte[] be = new byte[32];
            for (int j = 0; j < 32; j++) {
                be[31 - j] = pubB[j];
            }
            ka.doPhase(kf.generatePublic(new java.security.spec.XECPublicKeySpec(NamedParameterSpec.X25519,
                    new java.math.BigInteger(1, be))), true);
            allSame &= Arrays.equals(ka.generateSecret(), ab);
        }
        check("X25519 agrees with itself and with the JDK (5 random pairs)", allSame);
    }

    static void ed25519() throws Exception {
        byte[] seed1 = h("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        check("Ed25519 RFC 8032 test 1 public key", hex(Ed25519.publicKey(seed1))
                .equals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"));
        check("Ed25519 RFC 8032 test 1 signature", hex(Ed25519.sign(seed1, new byte[0])).equals(
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b"
                        + "d25bf5f0595bbe24655141438e7a100b"));
        byte[] seed2 = h("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
        byte[] sig2 = Ed25519.sign(seed2, h("72"));
        check("Ed25519 RFC 8032 test 2 signature", hex(sig2).equals(
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c"
                        + "387b2eaeb4302aeeb00d291612bb0c00"));
        check("Ed25519 verifies a good signature", Ed25519.verify(Ed25519.publicKey(seed2), h("72"), sig2));
        byte[] bad = sig2.clone();
        bad[10] ^= 1;
        check("Ed25519 rejects a damaged signature", !Ed25519.verify(Ed25519.publicKey(seed2), h("72"), bad));
        check("Ed25519 rejects another message", !Ed25519.verify(Ed25519.publicKey(seed2), h("73"), sig2));

        boolean same = true;
        for (int i = 0; i < 5; i++) {
            byte[] seed = random(32), msg = random(1 + RANDOM.nextInt(200));
            Signature jdk = Signature.getInstance("Ed25519");
            jdk.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(
                    new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)));
            jdk.update(msg);
            byte[] theirs = jdk.sign();
            byte[] ours = Ed25519.sign(seed, msg);
            same &= Arrays.equals(ours, theirs) && Ed25519.verify(Ed25519.publicKey(seed), msg, theirs);
        }
        check("Ed25519 signs identically to the JDK and verifies its signatures (5 random)", same);
    }

    static void chacha() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (0x80 + i);
        }
        byte[] nonce = h("070000004041424344454647");
        byte[] aad = h("50515253c0c1c2c3c4c5c6c7");
        byte[] plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
                .getBytes("US-ASCII");
        byte[] sealed = ChaCha20Poly1305.encrypt(key, nonce, aad, plain);
        String tag = hex(Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length));
        check("ChaCha20-Poly1305 RFC 8439 tag", tag.equals("1ae10b594f09e26a7e902ecbd0600691"));
        check("ChaCha20-Poly1305 RFC 8439 ciphertext start", hex(sealed).startsWith("d31a8d34648e60db7b86afbc53ef7ec2"));
        check("ChaCha20-Poly1305 decrypts", Arrays.equals(ChaCha20Poly1305.decrypt(key, nonce, aad, sealed), plain));
        sealed[3] ^= 1;
        boolean rejected = false;
        try {
            ChaCha20Poly1305.decrypt(key, nonce, aad, sealed);
        } catch (java.security.GeneralSecurityException e) {
            rejected = true;
        }
        check("ChaCha20-Poly1305 rejects a damaged message", rejected);

        boolean same = true;
        for (int i = 0; i < 8; i++) {
            byte[] k = random(32), n = random(12), ad = random(RANDOM.nextInt(40)), pt = random(RANDOM.nextInt(300));
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "ChaCha20"), new IvParameterSpec(n));
            c.updateAAD(ad);
            same &= Arrays.equals(c.doFinal(pt), ChaCha20Poly1305.encrypt(k, n, ad, pt));
        }
        check("ChaCha20-Poly1305 matches the JDK (8 random, empty and odd lengths included)", same);
    }

    static void hkdf() {
        byte[] secret = new byte[32];
        for (int i = 0; i < 32; i++) {
            secret[i] = (byte) i;
        }
        check("HKDF-SHA512 with salt matches pyatv", hex(Hkdf.key("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", secret))
                .equals("52890146745a52e57b82b859a7a3679c7f3d40bb295b055a0c8fa8af92a3746d"));
        check("HKDF-SHA512 with empty salt matches pyatv", hex(Hkdf.key("", "ServerEncrypt-main", secret))
                .equals("df91c3c41e8d3901f7c525e250cb59237271d5a0202cdd8dd8bd3a4ade77bfe8"));
    }

    static void opack() {
        // produced by pyatv's packer, with back-references to "_hidC" and friends
        byte[] packed = h("eb425f69455f68696443425f783210cc9ed3425f63e2455f6842745309a10e425f740a455f6274485002416e04416636"
                + "000000000000f83f416cd7090aa1312c013270110100a172010243626967912878787878787878787878787878787878"
                + "787878787878787878787878787878787878787878787878417361287979797979797979797979797979797979797979"
                + "7979797979797979797979797979797979797979436e6567330000000000010000");
        Opack.Result r = Opack.unpack(packed);
        Map<String, Object> m = r.map();
        boolean ok = m != null && !r.partial && r.consumed == packed.length
                && "_hidC".equals(m.get("_i")) && Long.valueOf(3550399504L).equals(m.get("_x"))
                && Long.valueOf(2).equals(m.get("_t")) && Boolean.FALSE.equals(m.get("_btHP"))
                && m.containsKey("n") && m.get("n") == null && Double.valueOf(1.5).equals(m.get("f"))
                && Long.valueOf(1L << 40).equals(m.get("neg"));
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) m.get("_c");
        ok &= c != null && Long.valueOf(1).equals(c.get("_hBtS")) && Long.valueOf(6).equals(c.get("_hidC"));
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) m.get("l");
        ok &= l != null && l.size() == 7 && "_hidC".equals(l.get(2)) && Long.valueOf(300).equals(l.get(3))
                && Long.valueOf(70000).equals(l.get(4)) && "_hidC".equals(l.get(5))
                && Arrays.equals((byte[]) l.get(6), new byte[]{1, 2});
        ok &= ((byte[]) m.get("big")).length == 40 && ((String) m.get("s")).length() == 40;
        check("OPACK reads a message with back-references (from pyatv)", ok);

        Opack.Result big = Opack.unpack(h("ef426b3008426b3109426b320a426b330b426b340c426b350d426b360e426b370f426b3810426b3911"
                + "436b313012436b313113436b313214436b313315436b313416436b313517436b313618436b313719436b31381a436b31391b03"));
        check("OPACK reads a 20-entry (terminated) dictionary", big.map() != null && big.map().size() == 20
                && Long.valueOf(19).equals(big.map().get("k19")));

        // a value the reader does not know, after the keys that matter: what came before it is kept
        Opack.Result broken = Opack.unpack(h("e3425f69445f786f4f4b425f7447ff"));
        check("OPACK keeps the readable start of a message it cannot finish",
                broken.partial && broken.map() != null && broken.map().containsKey("_i"));

        Map<String, Object> msg = Opack.map("_c", Opack.map("a", 1L, "b", new byte[]{9}), "_t", 3L, "_x", 3550399504L,
                "_i", "FetchMediaControlStatus", "s", "x");
        Opack.Result back = Opack.unpack(Opack.pack(msg));
        check("OPACK write then read gives the same message", back.map() != null && !back.partial
                && "FetchMediaControlStatus".equals(back.map().get("_i"))
                && Long.valueOf(3550399504L).equals(back.map().get("_x")));
    }

    static void tlv() {
        byte[] long300 = random(300);
        Map<Integer, byte[]> t = Tlv8.read(Tlv8.write(Tlv8.SEQ, Tlv8.one(3), Tlv8.PUBLIC_KEY, long300, Tlv8.ERROR, Tlv8.one(2)));
        check("TLV8 splits and rejoins values over 255 bytes", Arrays.equals(t.get(Tlv8.PUBLIC_KEY), long300)
                && t.get(Tlv8.SEQ)[0] == 3 && t.get(Tlv8.ERROR)[0] == 2);
    }

    static void gestures() {
        final List<String> events = new ArrayList<>();
        KeySink sink = new KeySink() {
            public void down(int k) { events.add("down" + k); }
            public void up(int k) { events.add("up" + k); }
            public void key(int k) { events.add("key" + k); }
        };
        Gestures g = new Gestures(sink, 100);
        g.touch(1, 500, 500);
        g.touch(2, 540, 520);     // not far enough yet
        g.touch(2, 700, 530);     // 200 right: two steps
        g.touch(2, 720, 900);     // the axis stays locked to x
        g.touch(4, 720, 900);
        g.touch(5, 0, 0);         // the click is the Select button's job
        check("swipe right becomes right presses on a locked axis", events.equals(Arrays.asList("key22", "key22")));
        events.clear();
        g.touch(1, 500, 500);
        g.touch(2, 480, 330);
        check("swipe up becomes one up press", events.equals(Arrays.asList("key19")));
    }
}
