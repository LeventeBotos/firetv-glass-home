package com.glasshome.remote;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** TLV8, the tag-length-value format HomeKit-style pairing messages use. Values over 255 bytes are split and rejoined. */
public final class Tlv8 {
    public static final int METHOD = 0, IDENTIFIER = 1, SALT = 2, PUBLIC_KEY = 3, PROOF = 4, ENCRYPTED = 5, SEQ = 6,
            ERROR = 7, SIGNATURE = 10, NAME = 17;
    public static final int ERROR_AUTHENTICATION = 2;

    private Tlv8() {
    }

    public static Map<Integer, byte[]> read(byte[] data) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        int pos = 0;
        while (pos + 2 <= data.length) {
            int tag = data[pos] & 0xff;
            int len = data[pos + 1] & 0xff;
            int end = Math.min(data.length, pos + 2 + len);
            byte[] value = java.util.Arrays.copyOfRange(data, pos + 2, end);
            byte[] old = out.get(tag);
            if (old == null) {
                out.put(tag, value);
            } else {
                byte[] joined = java.util.Arrays.copyOf(old, old.length + value.length);
                System.arraycopy(value, 0, joined, old.length, value.length);
                out.put(tag, joined);
            }
            pos += 2 + len;
        }
        return out;
    }

    /** Pairs of (Integer tag, byte[] value), in order. */
    public static byte[] write(Object... pairs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < pairs.length; i += 2) {
            int tag = (Integer) pairs[i];
            byte[] value = (byte[]) pairs[i + 1];
            for (int pos = 0; pos < value.length; pos += 255) {
                int n = Math.min(255, value.length - pos);
                out.write(tag);
                out.write(n);
                out.write(value, pos, n);
            }
        }
        return out.toByteArray();
    }

    public static byte[] one(int v) {
        return new byte[]{(byte) v};
    }
}
