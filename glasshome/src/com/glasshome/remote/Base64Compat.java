package com.glasshome.remote;

/** Base64 encoding without line breaks. java.util.Base64 needs Android 8; the stick runs Android 7. */
final class Base64Compat {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Base64Compat() {
    }

    static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            sb.append(ALPHABET.charAt(b0 >> 2));
            sb.append(ALPHABET.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < data.length ? ALPHABET.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < data.length ? ALPHABET.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }
}
