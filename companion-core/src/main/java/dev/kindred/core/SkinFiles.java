package dev.kindred.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * In plain terms: reads the skin images a server owner puts in the skins folder: checks each is a real
 * Minecraft skin (64 by 64, or the old 64 by 32, and small), works out its name and whether it has slim arms,
 * and explains why a file is not usable.
 *
 * Technical: Skins an operator drops into the skins folder: which files are usable Minecraft skins, the key
 * each is chosen by, and whether it uses the slim (three-pixel) arm model. Only the PNG header is trusted
 * before decoding, so an image claiming a huge size is refused without being decoded.
 */
public final class SkinFiles {
    /** A skin file is a few kilobytes; anything larger is not a skin. */
    public static final int MAX_BYTES = 64 * 1024;
    /** How many skins one folder offers; the rest are ignored. */
    public static final int MAX_SKINS = 64;
    /** Chosen to bring back the player's own bundled or default skin. */
    public static final String DEFAULT_KEY = "default";

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** A usable skin: its key, file name, arm model, the PNG itself and its SHA-1 (a texture's identity). */
    public record Skin(String key, String fileName, boolean slim, byte[] png, String sha1) { }

    /** A file read as a skin: the skin, or why it is not one. */
    public record Read(Skin skin, String problem) {
        public boolean usable() { return skin != null; }
    }

    private SkinFiles() { }

    /**
     * The key a file is chosen by: its name without {@code .png}, lower case, with anything but letters, digits,
     * {@code _} and {@code -} made {@code _}, at most 32 characters. Empty when the name gives nothing usable.
     */
    public static String keyOf(String fileName) {
        if (fileName == null) return "";
        String base = fileName.trim();
        if (base.toLowerCase(Locale.ROOT).endsWith(".png")) base = base.substring(0, base.length() - 4);
        String key = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_").replaceAll("_+", "_");
        key = key.replaceAll("^[_-]+|[_-]+$", "");
        if (key.length() > 32) key = key.substring(0, 32);
        return DEFAULT_KEY.equals(key) ? "" : key;
    }

    /** Reads one file's bytes as a skin: a PNG of 64×64 (or the old 64×32) pixels, no larger than 64 KiB. */
    public static Read read(String fileName, byte[] bytes) {
        String key = keyOf(fileName);
        if (key.isEmpty()) return new Read(null, "no usable name");
        if (bytes == null || bytes.length == 0) return new Read(null, "empty");
        if (bytes.length > MAX_BYTES) return new Read(null, "larger than 64 KiB");
        int[] size = headerSize(bytes);
        if (size == null) return new Read(null, "not a PNG");
        if (size[0] != 64 || size[1] != 64 && size[1] != 32) {
            return new Read(null, size[0] + "x" + size[1] + " pixels; a skin is 64x64 (or 64x32)");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException unreadable) {
            return new Read(null, "unreadable PNG");
        }
        if (image == null || image.getWidth() != size[0] || image.getHeight() != size[1]) return new Read(null, "unreadable PNG");
        return new Read(new Skin(key, fileName, slim(image, fileName), bytes.clone(), sha1(bytes)), null);
    }

    /**
     * The arm model. A name ending in {@code _slim} or {@code -slim} (or {@code _wide}, {@code _classic}) decides it;
     * otherwise a 64×64 skin is slim when its arms' outer pixel columns (x 54 and 55, rows 20 to 31), which only the
     * wide model uses, are empty. The old 64×32 format is always wide.
     */
    static boolean slim(BufferedImage image, String fileName) {
        String name = keyOf(fileName);
        if (name.endsWith("_slim") || name.endsWith("-slim")) return true;
        if (name.endsWith("_wide") || name.endsWith("-wide") || name.endsWith("_classic") || name.endsWith("-classic")) return false;
        if (image.getHeight() != 64) return false;
        for (int x = 54; x <= 55; x++) {
            for (int y = 20; y <= 31; y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) return false;
            }
        }
        return true;
    }

    /** Width and height from the PNG's IHDR chunk, or null when the bytes do not start as a PNG does. */
    static int[] headerSize(byte[] bytes) {
        if (bytes.length < 24) return null;
        for (int i = 0; i < SIGNATURE.length; i++) if (bytes[i] != SIGNATURE[i]) return null;
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 16, 8);
        int width = buffer.getInt(), height = buffer.getInt();
        return width <= 0 || height <= 0 ? null : new int[] {width, height};
    }

    /** A fingerprint of the image, used to recognise the same skin. */
    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
