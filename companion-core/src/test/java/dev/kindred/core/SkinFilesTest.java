package dev.kindred.core;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class SkinFilesTest {
    private static byte[] png(int width, int height, boolean wideArms) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) image.setRGB(x, y, 0xFF336699);
        if (!wideArms && height == 64) for (int x = 54; x <= 55; x++) for (int y = 20; y <= 31; y++) image.setRGB(x, y, 0);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aFileNameBecomesAShortSafeKey() {
        assertEquals("steve", SkinFiles.keyOf("Steve.png"));
        assertEquals("red_knight", SkinFiles.keyOf("Red Knight.PNG"));
        assertEquals("a_b-c", SkinFiles.keyOf("__a..b-c__.png"));
        assertEquals("", SkinFiles.keyOf("....png"), "nothing usable left");
        assertEquals("", SkinFiles.keyOf("Default.png"), "the word that brings back the player's own skin");
        assertEquals(32, SkinFiles.keyOf("x".repeat(80) + ".png").length());
        assertEquals("", SkinFiles.keyOf(null));
    }

    @Test
    void aSkinIsA64PixelPngWithItsArmModel() throws IOException {
        var wide = SkinFiles.read("Knight.png", png(64, 64, true));
        assertTrue(wide.usable(), wide.problem());
        assertEquals("knight", wide.skin().key());
        assertFalse(wide.skin().slim());
        assertEquals(40, wide.skin().sha1().length());
        var slim = SkinFiles.read("Archer.png", png(64, 64, false));
        assertTrue(slim.skin().slim(), "the wide model's outer arm columns are empty");
        assertFalse(SkinFiles.read("Archer_wide.png", png(64, 64, false)).skin().slim(), "the name decides");
        assertTrue(SkinFiles.read("Knight-slim.png", png(64, 64, true)).skin().slim(), "the name decides");
        var legacy = SkinFiles.read("Old.png", png(64, 32, false));
        assertTrue(legacy.usable());
        assertFalse(legacy.skin().slim(), "the old format is always wide");
    }

    @Test
    void anythingElseIsRefusedWithItsReason() throws IOException {
        assertEquals("64x128 pixels; a skin is 64x64 (or 64x32)", SkinFiles.read("Big.png", png(64, 128, true)).problem());
        assertEquals("not a PNG", SkinFiles.read("Text.png", "hello, this is not an image at all".getBytes()).problem());
        assertEquals("larger than 64 KiB", SkinFiles.read("Huge.png", new byte[SkinFiles.MAX_BYTES + 1]).problem());
        assertEquals("empty", SkinFiles.read("Empty.png", new byte[0]).problem());
        assertEquals("no usable name", SkinFiles.read("default.png", png(64, 64, true)).problem());
        // A header claiming an enormous image is refused before anything is decoded.
        byte[] bomb = png(64, 64, true);
        ByteBuffer.wrap(bomb, 16, 8).putInt(100_000).putInt(100_000);
        assertEquals("100000x100000 pixels; a skin is 64x64 (or 64x32)", SkinFiles.read("Bomb.png", bomb).problem());
        // A correct header over broken image data.
        byte[] broken = png(64, 64, true);
        for (int i = 40; i < broken.length; i++) broken[i] = 7;
        assertEquals("unreadable PNG", SkinFiles.read("Broken.png", broken).problem());
    }
}
