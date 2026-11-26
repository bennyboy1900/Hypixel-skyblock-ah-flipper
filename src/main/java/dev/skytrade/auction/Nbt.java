package dev.skytrade.auction;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Minimal, allocation-conscious reader for the (GZIP-compressed, big-endian) NBT used by Hypixel's
 * {@code item_bytes}. Compounds become {@code Map<String,Object>}, lists become {@code List<Object>},
 * numbers keep their boxed Java type, arrays become primitive arrays.
 */
public final class Nbt {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_LIST = 1 << 16;

    private Nbt() {}

    public static Map<String, Object> readBase64Gzip(String base64) throws IOException {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid base64", e);
        }
        return readGzip(raw);
    }

    public static Map<String, Object> readGzip(byte[] gz) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(gz))))) {
            int type = in.readUnsignedByte();
            if (type != 10) throw new IOException("root is not a compound: " + type);
            in.readUTF(); // root name
            return readCompound(in, 0);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT too deep");
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return map;
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int n = checkLen(in.readInt());
                byte[] b = new byte[n];
                in.readFully(b);
                return b;
            }
            case 8: return in.readUTF();
            case 9: {
                int elemType = in.readUnsignedByte();
                int n = checkLen(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(n, 256));
                for (int i = 0; i < n; i++) list.add(elemType == 0 ? null : readPayload(in, elemType, depth + 1));
                return list;
            }
            case 10: return readCompound(in, depth);
            case 11: {
                int n = checkLen(in.readInt());
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                int n = checkLen(in.readInt());
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("unknown NBT tag " + type);
        }
    }

    private static int checkLen(int n) throws IOException {
        if (n < 0 || n > MAX_LIST) throw new IOException("bad NBT length " + n);
        return n;
    }

    // ---- typed navigation helpers ----
    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof List ? (List<Object>) o : List.of();
    }

    public static String string(Map<String, Object> m, String key, String def) {
        Object o = m == null ? null : m.get(key);
        return o instanceof String s ? s : def;
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        Object o = m == null ? null : m.get(key);
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
