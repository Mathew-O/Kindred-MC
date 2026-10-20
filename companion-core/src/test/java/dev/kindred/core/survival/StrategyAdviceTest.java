package dev.kindred.core.survival;

import dev.kindred.core.survival.StrategyAdvice.Place;
import dev.kindred.core.survival.StrategyAdvice.Stance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyAdviceTest {

    private static final List<Place> REPORTED = List.of(new Place(35, 32, 87), new Place(-5, 65, 97));

    @Test
    void aKnownStanceIsAcceptedInAnyCase() {
        var advice = StrategyAdvice.validated(" play_safe ", List.of(), "Caves near spawn are deadly", "Two deaths there", REPORTED);
        assertTrue(advice.isPresent());
        assertEquals(Stance.PLAY_SAFE, advice.get().stance());
        assertEquals("Caves near spawn are deadly", advice.get().note());
    }

    @Test
    void anUnknownOrMissingStanceIsNoAdviceAtAll() {
        assertTrue(StrategyAdvice.validated("teleport_home", List.of(), "", "", REPORTED).isEmpty());
        assertTrue(StrategyAdvice.validated(null, List.of(), "", "", REPORTED).isEmpty());
    }

    @Test
    void onlyPlacesNearWhatThePlayerReportedAreKept() {
        var advice = StrategyAdvice.validated("PLAY_SAFE", List.of(new Place(40, 30, 90), new Place(500, 12, -300)), "", "", REPORTED).orElseThrow();
        assertEquals(List.of(new Place(40, 30, 90)), advice.avoid(), "a place the player never reported near is dropped");
    }

    @Test
    void atMostThreePlacesAreKept() {
        var many = List.of(new Place(35, 32, 87), new Place(36, 32, 87), new Place(37, 32, 87), new Place(38, 32, 87));
        assertEquals(StrategyAdvice.MAX_AVOID, StrategyAdvice.validated("PRESS_ON", many, "", "", REPORTED).orElseThrow().avoid().size());
    }

    @Test
    void textIsTrimmedToOnePlainLine() {
        String longText = "a".repeat(500);
        var advice = StrategyAdvice.validated("SURFACE_AND_RECOVER", null, "line one\nline two", longText, REPORTED).orElseThrow();
        assertEquals("line one line two", advice.note());
        assertEquals(StrategyAdvice.MAX_TEXT, advice.reason().length());
        assertTrue(advice.avoid().isEmpty());
    }
}
