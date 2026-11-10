package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkSetAsideTest {
    private static final long T = 42L;

    @Test
    void aTargetRefusedThreeTimesFromAroundOnePlaceIsSetAsideForTenMinutes() {
        var book = new WalkSetAside();
        assertFalse(book.refused(T, 0, 64, 0, 100));
        assertFalse(book.refused(T, 1, 64, 0, 140));
        assertFalse(book.aside(T, 1, 64, 0, 150));
        assertTrue(book.refused(T, 2, 64, 1, 180));
        assertTrue(book.aside(T, 2, 64, 1, 181));
        assertTrue(book.aside(T, 0, 64, 0, 180 + WalkSetAside.ASIDE_TICKS - 1));
        assertFalse(book.aside(T, 0, 64, 0, 180 + WalkSetAside.ASIDE_TICKS), "Ten minutes, then another try");
    }

    @Test
    void movedAwayTheTargetIsTriedAgain() {
        var book = new WalkSetAside();
        for (int i = 0; i < 3; i++) book.refused(T, 0, 64, 0, 100 + i);
        assertTrue(book.aside(T, 0, 64, 0, 200));
        assertFalse(book.aside(T, 20, 64, 0, 200), "Twenty blocks off, the way there may be another");
    }

    @Test
    void refusalsFromFarApartDoNotAddUp() {
        var book = new WalkSetAside();
        book.refused(T, 0, 64, 0, 100);
        book.refused(T, 30, 64, 0, 110);
        assertFalse(book.refused(T, 31, 64, 0, 120), "Counted afresh from the new place: two, not three");
        assertTrue(book.refused(T, 31, 64, 1, 130));
    }

    @Test
    void anArrivalOrItsOwnEditsGiveThePlaceAnotherTry() {
        var book = new WalkSetAside();
        for (int i = 0; i < 3; i++) book.refused(T, 0, 64, 0, 100 + i);
        book.walked(T);
        assertFalse(book.aside(T, 0, 64, 0, 200));
        for (int i = 0; i < 3; i++) book.refused(T, 0, 64, 0, 300 + i);
        book.groundChanged();
        assertFalse(book.aside(T, 0, 64, 0, 400));
        assertEquals(0, book.size());
    }

    @Test
    void theBookIsBounded() {
        var book = new WalkSetAside();
        for (int i = 0; i < WalkSetAside.MAX + 50; i++) book.refused(i, 0, 64, 0, i);
        assertEquals(WalkSetAside.MAX, book.size());
    }
}
