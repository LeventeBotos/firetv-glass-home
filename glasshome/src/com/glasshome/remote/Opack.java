package com.glasshome.remote;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPACK, Apple's compact serialization for the Companion link. Values are null, Boolean, Long, Double, String, byte[],
 * List and Map (String keys; ordered).
 *
 * Reading follows what phones actually send: scalars longer than one byte can be referred back to by index, containers
 * and one-byte values cannot be. The reader is deliberately forgiving, because only a handful of keys at the top of a
 * message matter: when something later in a message is not understood, it returns what it had read so far
 * ({@link #partial()} then says so) instead of dropping the whole message.
 */
public final class Opack {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final byte[] data;
    private int pos;
    private final List<Object> refs = new ArrayList<>();
    private boolean partial;

    private Opack(byte[] data) {
        this.data = data;
    }

    // ---- writing

    public static byte[] pack(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void write(ByteArrayOutputStream out, Object v) {
        if (v == null) {
            out.write(0x04);
        } else if (v instanceof Boolean) {
            out.write((Boolean) v ? 0x01 : 0x02);
        } else if (v instanceof Integer || v instanceof Long) {
            long n = ((Number) v).longValue();
            if (n >= 0 && n < 0x28) {
                out.write((int) n + 8);
            } else if (n >= 0 && n <= 0xFFL) {
                out.write(0x30);
                le(out, n, 1);
            } else if (n >= 0 && n <= 0xFFFFL) {
                out.write(0x31);
                le(out, n, 2);
            } else if (n >= 0 && n <= 0xFFFFFFFFL) {
                out.write(0x32);
                le(out, n, 4);
            } else {
                out.write(0x33);
                le(out, n, 8);
            }
        } else if (v instanceof Double) {
            out.write(0x36);
            le(out, Double.doubleToLongBits((Double) v), 8);
        } else if (v instanceof String) {
            byte[] s = ((String) v).getBytes(UTF8);
            if (s.length <= 0x20) {
                out.write(0x40 + s.length);
            } else {
                lengthPrefix(out, 0x60, s.length);
            }
            out.write(s, 0, s.length);
        } else if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            if (b.length <= 0x20) {
                out.write(0x70 + b.length);
            } else {
                lengthPrefix(out, 0x90, b.length);
            }
            out.write(b, 0, b.length);
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            out.write(0xD0 + Math.min(l.size(), 0xF));
            for (Object o : l) {
                write(out, o);
            }
            if (l.size() >= 0xF) {
                out.write(0x03);
            }
        } else if (v instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) v;
            out.write(0xE0 + Math.min(m.size(), 0xF));
            for (Map.Entry<Object, Object> e : m.entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
            if (m.size() >= 0xF) {
                out.write(0x03);
            }
        } else {
            throw new IllegalArgumentException(v.getClass().getName());
        }
    }

    /** 0x61.. / 0x91..: a 1, 2, 3 or 4 byte length (strings), or 1, 2, 4, 8 (data), then the bytes. */
    private static void lengthPrefix(ByteArrayOutputStream out, int base, int len) {
        boolean text = base == 0x60;
        int size = len <= 0xFF ? 1 : len <= 0xFFFF ? 2 : len <= 0xFFFFFF ? 3 : 4;
        if (!text && size == 3) {
            size = 4;
        }
        out.write(text ? base + size : base + (size == 1 ? 1 : size == 2 ? 2 : 3));
        le(out, len, size);
    }

    private static void le(ByteArrayOutputStream out, long v, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) (v >>> (8 * i)) & 0xff);
        }
    }

    // ---- reading

    /** Unpack one value from the start of the data. */
    public static Result unpack(byte[] data) {
        Opack r = new Opack(data);
        Object value;
        try {
            value = r.read();
        } catch (RuntimeException e) {
            r.partial = true;
            value = r.salvaged;
        }
        Result result = new Result();
        result.value = value;
        result.partial = r.partial;
        result.consumed = r.pos;
        return result;
    }

    public static final class Result {
        public Object value;
        public boolean partial;
        public int consumed;

        public int unread(byte[] data) {
            return data.length - consumed;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map() {
            return value instanceof Map ? (Map<String, Object>) value : null;
        }
    }

    // the outermost container read so far, handed back when a later value cannot be read
    private Object salvaged;
    private int depth;

    private int u8() {
        return data[pos++] & 0xff;
    }

    private long leLong(int bytes) {
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            v |= (long) u8() << (8 * i);
        }
        return v;
    }

    private byte[] take(int n) {
        if (n < 0 || pos + n > data.length) {
            throw new IllegalStateException("short");
        }
        byte[] b = java.util.Arrays.copyOfRange(data, pos, pos + n);
        pos += n;
        return b;
    }

    private Object ref(Object v, int startPos) {
        if (pos - startPos > 1) {
            refs.add(v);
        }
        return v;
    }

    private Object read() {
        int start = pos;
        int t = u8();
        if (t == 0x01) {
            return Boolean.TRUE;
        } else if (t == 0x02) {
            return Boolean.FALSE;
        } else if (t == 0x04) {
            return null;
        } else if (t == 0x05) {
            return ref(take(16), start);
        } else if (t == 0x06) {
            return ref(leLong(8), start);
        } else if (t >= 0x08 && t <= 0x2F) {
            return (long) (t - 8);
        } else if (t == 0x35) {
            return ref((double) Float.intBitsToFloat((int) leLong(4)), start);
        } else if (t == 0x36) {
            return ref(Double.longBitsToDouble(leLong(8)), start);
        } else if ((t & 0xF0) == 0x30) {
            return ref(leLong(1 << (t & 0xF)), start);
        } else if (t >= 0x40 && t <= 0x60) {
            return ref(new String(take(t - 0x40), UTF8), start);
        } else if (t > 0x60 && t <= 0x64) {
            int n = (int) leLong(t & 0xF);
            return ref(new String(take(n), UTF8), start);
        } else if (t >= 0x70 && t <= 0x90) {
            return ref(take(t - 0x70), start);
        } else if (t >= 0x91 && t <= 0x94) {
            int n = (int) leLong(1 << ((t & 0xF) - 1));
            return ref(take(n), start);
        } else if ((t & 0xF0) == 0xD0) {
            int count = t & 0xF;
            List<Object> list = new ArrayList<>();
            depth++;
            if (depth == 1) {
                salvaged = list;
            }
            if (count == 0xF) {
                while (data[pos] != 0x03) {
                    list.add(read());
                }
                pos++;
            } else {
                for (int i = 0; i < count; i++) {
                    list.add(read());
                }
            }
            depth--;
            return list;
        } else if ((t & 0xF0) == 0xE0) {
            int count = t & 0xF;
            Map<String, Object> map = new LinkedHashMap<>();
            depth++;
            if (depth == 1) {
                salvaged = map;
            }
            if (count == 0xF) {
                while (data[pos] != 0x03) {
                    put(map);
                }
                pos++;
            } else {
                for (int i = 0; i < count; i++) {
                    put(map);
                }
            }
            depth--;
            return map;
        } else if (t >= 0xA0 && t <= 0xC0) {
            return refs.get(t - 0xA0);
        } else if (t >= 0xC1 && t <= 0xC4) {
            return refs.get((int) leLong(t - 0xC0));
        }
        throw new IllegalStateException("opack type " + Integer.toHexString(t));
    }

    private void put(Map<String, Object> map) {
        Object key = read();
        Object value = read();
        map.put(String.valueOf(key), value);
    }

    // ---- small helpers for callers

    public static long asLong(Object o, long fallback) {
        return o instanceof Number ? ((Number) o).longValue() : fallback;
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
