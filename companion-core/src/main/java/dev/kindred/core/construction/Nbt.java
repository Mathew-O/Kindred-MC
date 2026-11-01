package dev.kindred.core.construction;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * In plain terms: reads the game's own file format for saved structures and building plans, packed or not,
 * and refuses a file too large or too deeply nested to be safe.
 *
 * Technical: Minecraft's NBT, read (M-05): the binary format structure files and schematics are saved in,
 * gzipped or not. Compounds come back as maps, lists as lists, numbers boxed, arrays as arrays. Bounded
 * against a hostile file: at most {@link #MAX_BYTES} unpacked, {@link #MAX_DEPTH} deep, and no list or array
 * longer than what is left to read. Pure.
 */
public final class Nbt {
    public static final int MAX_BYTES = 16 << 20;
    public static final int MAX_DEPTH = 64;

    private Nbt() { }

    /** The root compound of {@code data}. */
    public static Map<String, Object> read(byte[] data) {
        try {
            InputStream in = new ByteArrayInputStream(data);
            if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) in = new GZIPInputStream(in);
            var bounded = new Bounded(in, MAX_BYTES);
            var reader = new DataInputStream(bounded);
            int type = reader.readUnsignedByte();
            if (type != 10) throw new IllegalArgumentException("Not an NBT compound");
            reader.readUTF();
            @SuppressWarnings("unchecked")
            var root = (Map<String, Object>) payload(reader, bounded, 10, 0);
            return root;
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("The file ends too soon");
        } catch (IOException failure) {
            throw new IllegalArgumentException("Not readable as NBT: " + failure.getMessage());
        }
    }

    private static Object payload(DataInputStream in, Bounded left, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("NBT nested too deep");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] bytes = new byte[length(in, left, 1)];
                in.readFully(bytes);
                yield bytes;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int element = in.readUnsignedByte();
                int n = length(in, left, 1);
                var list = new ArrayList<Object>(Math.min(n, 4096));
                if (element == 0 && n > 0) throw new IllegalArgumentException("A list of nothing");
                for (int i = 0; i < n; i++) list.add(payload(in, left, element, depth + 1));
                yield List.copyOf(list);
            }
            case 10 -> {
                var map = new LinkedHashMap<String, Object>();
                while (true) {
                    int tag = in.readUnsignedByte();
                    if (tag == 0) break;
                    String name = in.readUTF();
                    map.put(name, payload(in, left, tag, depth + 1));
                }
                yield java.util.Collections.unmodifiableMap(map);
            }
            case 11 -> {
                int[] ints = new int[length(in, left, 4)];
                for (int i = 0; i < ints.length; i++) ints[i] = in.readInt();
                yield ints;
            }
            case 12 -> {
                long[] longs = new long[length(in, left, 8)];
                for (int i = 0; i < longs.length; i++) longs[i] = in.readLong();
                yield longs;
            }
            default -> throw new IllegalArgumentException("Unknown NBT tag " + type);
        };
    }

    /** A length read from the stream, no more than the bytes still allowed could hold. */
    private static int length(DataInputStream in, Bounded left, int each) throws IOException {
        int n = in.readInt();
        if (n < 0 || (long) n * each > left.remaining()) throw new IllegalArgumentException("An NBT length past the end: " + n);
        return n;
    }

    /** At most so many bytes from the stream; past that the file is refused. */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long left;

        Bounded(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        long remaining() {
            return left;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) throw new IllegalArgumentException("Larger than " + (MAX_BYTES >> 20) + " MB unpacked");
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IllegalArgumentException("Larger than " + (MAX_BYTES >> 20) + " MB unpacked");
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    /** A compound's child compound, or empty. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Map<String, Object> parent, String key) {
        return parent.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** A compound's child list, or empty. */
    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> parent, String key) {
        return parent.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** A compound's whole number, whatever its width, or {@code fallback}. */
    static int integer(Map<String, Object> parent, String key, int fallback) {
        return parent.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    /** A piece of text from the file, or empty when it has none. */
    static String string(Map<String, Object> parent, String key) {
        return parent.get(key) instanceof String s ? s : "";
    }
}
