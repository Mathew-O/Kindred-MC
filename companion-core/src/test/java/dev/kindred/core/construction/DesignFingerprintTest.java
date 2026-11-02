package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DesignFingerprintTest {
    private static Blueprint cabin(String wood, int variant) {
        return Houses.of(Houses.Stage.CABIN, new BuildStyle("", wood, "cobblestone", variant, 0));
    }

    @Test
    void turnedMovedOrInOtherMaterialsItIsTheSameDesign() {
        var cabin = cabin("oak", 0);
        var print = DesignFingerprint.of(cabin);
        for (int turns = 0; turns < 4; turns++) {
            var placed = DesignFingerprint.of(cabin.placed(new Pos(37, 64, -12), turns));
            assertEquals(print.hash(), placed.hash(), "turned " + turns);
            assertEquals(1.0, print.likeness(placed), 1e-9);
        }
        assertEquals(print.hash(), DesignFingerprint.of(cabin("spruce", 0)).hash(), "in spruce: the same design");
    }

    @Test
    void anotherWindowIsANearCopyAndAnotherPlanIsNot() {
        var cabin = cabin("oak", 0);
        var blocks = new ArrayList<>(cabin.blocks());
        // A window more: a wall block taken out.
        var wall = blocks.stream().filter(b -> b.spec().block().equals("minecraft:cobblestone") && b.at().y() == 1).findFirst().orElseThrow();
        blocks.remove(wall);
        var windowed = DesignFingerprint.of(blocks);
        var print = DesignFingerprint.of(cabin);
        assertNotEquals(print.hash(), windowed.hash());
        assertTrue(print.nearCopyOf(windowed), "a window more is no new design: " + print.likeness(windowed));
        // Of the variants, those of another size are other designs.
        int other = 0;
        for (int v = 1; v <= BuildStyle.VARIANTS; v++) {
            var shape = Houses.shape(Houses.Stage.CABIN, v);
            var base = Houses.shape(Houses.Stage.CABIN, 0);
            if (shape.width() == base.width() && shape.depth() == base.depth()) continue;
            assertFalse(print.nearCopyOf(DesignFingerprint.of(cabin("oak", v))), "variant " + v + " is its own design");
            other++;
        }
        assertTrue(other > 10, "most variants differ in size: " + other);
    }

    @Test
    void aPlayersDesignIsReservedAndAnothersNearCopyRefused() {
        UUID hal = UUID.randomUUID(), wren = UUID.randomUUID();
        var registry = new DesignFingerprint.Registry();
        var cabin = DesignFingerprint.of(cabin("oak", 0));
        assertTrue(registry.reserve(hal, "Hal's cabin", cabin).isEmpty());
        // Wren's, turned and in spruce: Hal's design.
        var copy = DesignFingerprint.of(cabin("spruce", 0).placed(new Pos(100, 70, 5), 2));
        var refused = registry.reserve(wren, "Wren's cabin", copy);
        assertEquals("Hal's cabin", refused.orElseThrow().name());
        // Its own again is its own; another plan is Wren's.
        assertTrue(registry.reserve(hal, "Hal's cabin", copy).isEmpty());
        int v = 1;
        while (Houses.shape(Houses.Stage.CABIN, v).width() == Houses.shape(Houses.Stage.CABIN, 0).width()) v++;
        assertTrue(registry.reserve(wren, "Wren's cabin", DesignFingerprint.of(cabin("spruce", v))).isEmpty());
        // Kept as text and read back, refusing as before; let go, it is free.
        var back = DesignFingerprint.Registry.read(registry.write());
        assertEquals(registry.reservations().size(), back.reservations().size());
        assertTrue(back.reserve(wren, "Wren's other", copy).isPresent());
        back.release(hal, cabin);
        assertTrue(back.reserve(wren, "Wren's other", copy).isEmpty());
    }
}
