package dev.kindred.core.ui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HudOptionsTest {
    @Test
    void theDefaultIsTheCardAsItWasDrawn() {
        var d = HudOptions.DEFAULT;
        assertEquals(0x59_3C_3C_3C, d.card(), "35% stone grey");
        assertEquals(0x80_8A_8A_8A, d.edge(), "its lighter edge");
        assertEquals(1.0, d.scale());
        assertEquals(HudOptions.Side.RIGHT, d.side());
        assertTrue(d.hideForTitles());
    }

    @Test
    void valuesAreClampedToTheirRanges() {
        assertEquals(10, new HudOptions(0, 1, HudOptions.Side.LEFT, true).opacity());
        assertEquals(100, new HudOptions(400, 1, HudOptions.Side.LEFT, true).opacity());
        assertEquals(0.5, new HudOptions(35, 0.1, HudOptions.Side.LEFT, true).scale());
        assertEquals(2.0, new HudOptions(35, 9, HudOptions.Side.LEFT, true).scale());
        assertEquals(1.0, new HudOptions(35, Double.NaN, HudOptions.Side.LEFT, true).scale());
        assertEquals(1.25, new HudOptions(35, 1.26, HudOptions.Side.LEFT, true).scale(), "to the nearest 0.05");
        assertEquals(0xFF, new HudOptions(100, 1, HudOptions.Side.LEFT, true).card() >>> 24);
        assertEquals(0xFF, new HudOptions(100, 1, HudOptions.Side.LEFT, true).edge() >>> 24, "the edge never past opaque");
    }

    @Test
    void eachSettingIsChangedFromTextOrRefusedWithWhy() {
        var d = HudOptions.DEFAULT;
        assertEquals(50, d.with("opacity", "50%").options().orElseThrow().opacity());
        assertEquals(1.5, d.with("Scale", " 1.5 ").options().orElseThrow().scale());
        assertEquals(HudOptions.Side.LEFT, d.with("side", "LEFT").options().orElseThrow().side());
        assertFalse(d.with("titles", "off").options().orElseThrow().hideForTitles());
        var bad = d.with("opacity", "lots");
        assertTrue(bad.options().isEmpty() && bad.error().startsWith("Not a number"), bad.error());
        assertTrue(d.with("side", "up").options().isEmpty());
        assertTrue(d.with("colour", "red").error().startsWith("No setting"));
    }

    @Test
    void keptAsTextAndReadBackEachOnItsOwn() {
        var o = new HudOptions(60, 1.5, HudOptions.Side.LEFT, false);
        assertEquals(o, HudOptions.fromMap(o.toMap()));
        var partly = HudOptions.fromMap(Map.of("opacity", "70", "scale", "huge", "side", "sideways"));
        assertEquals(70, partly.opacity());
        assertEquals(1.0, partly.scale(), "an unreadable value keeps its default");
        assertEquals(HudOptions.Side.RIGHT, partly.side());
        assertEquals(HudOptions.DEFAULT, HudOptions.fromMap(Map.of()));
        assertEquals("opacity 35%, scale 1.0, right side, hidden during titles", HudOptions.DEFAULT.describe());
    }

    @Test
    void theCardIsPlacedOnItsSideAtItsScale() {
        // At the default it is where it always was: a quarter of the width, four from the right, a third down.
        var card = HudOptions.DEFAULT.place(640, 360, 5, 9);
        assertEquals(new HudOptions.Card(640 - 160 - 4, (360 - 63) / 3, 160, 4 * 2 + 5 * 11), card);
        var left = new HudOptions(35, 1.0, HudOptions.Side.LEFT, true).place(640, 360, 5, 9);
        assertEquals(4, left.x());
        // At twice the scale it is laid out on a screen half the size, so it covers the same share of the real one.
        var big = new HudOptions(35, 2.0, HudOptions.Side.RIGHT, true).place(640, 360, 5, 9);
        assertEquals(new HudOptions.Card(320 - 120 - 4, (180 - 63) / 3, 120, 63), big);
        assertTrue((big.x() + big.width()) * 2 <= 640, "still on the screen at scale 2");
        // Narrow screens keep a readable 120 wide.
        assertEquals(120, HudOptions.DEFAULT.place(300, 200, 2, 9).width());
    }
}
