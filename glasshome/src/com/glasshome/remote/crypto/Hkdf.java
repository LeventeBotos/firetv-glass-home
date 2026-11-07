package com.glasshome.remote.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;

/** HKDF (RFC 5869) over HMAC-SHA512, the key derivation HomeKit-style pairing uses. */
public final class Hkdf {
    private Hkdf() {
    }

    public static byte[] derive(String salt, String info, byte[] secret, int length) {
        try {
            byte[] saltBytes = salt.getBytes("UTF-8");
            // an empty salt means HashLen zero bytes, which HMAC's own key padding already gives
            byte[] prk = hmac(saltBytes.length == 0 ? new byte[64] : saltBytes, secret);
            byte[] infoBytes = info.getBytes("UTF-8");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] t = new byte[0];
            for (int i = 1; out.size() < length; i++) {
                ByteArrayOutputStream block = new ByteArrayOutputStream();
                block.write(t);
                block.write(infoBytes);
                block.write(i);
                t = hmac(prk, block.toByteArray());
                out.write(t);
            }
            byte[] all = out.toByteArray();
            byte[] result = new byte[length];
            System.arraycopy(all, 0, result, 0, length);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The 32-byte keys this protocol uses. */
    public static byte[] key(String salt, String info, byte[] secret) {
        return derive(salt, info, secret, 32);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key, "HmacSHA512"));
        return mac.doFinal(data);
    }
}
