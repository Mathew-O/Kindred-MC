package dev.kindred.core.survival;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks when a player starts a ranch of its own: only with a base standing, only for a kind it has
 * seen a pair of, only with the food to breed them, and cows before sheep before pigs before chickens.
 */
class RanchChoiceTest {
    @Test
    void withABaseAPairSeenAndTheFoodItKeepsThem() {
        var d = RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2), Map.of(Ranch.Kind.COW, 4));
        assertEquals(Optional.of(Ranch.Kind.COW), d.kind());
        var sheepFirst = RanchChoice.next(true, Map.of(Ranch.Kind.SHEEP, 3, Ranch.Kind.CHICKEN, 5), Map.of(Ranch.Kind.SHEEP, 2, Ranch.Kind.CHICKEN, 9));
        assertEquals(Optional.of(Ranch.Kind.SHEEP), sheepFirst.kind(), "sheep before chickens");
        var fedKindFirst = RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2, Ranch.Kind.PIG, 2), Map.of(Ranch.Kind.PIG, 3));
        assertEquals(Optional.of(Ranch.Kind.PIG), fedKindFirst.kind(), "a kind it can feed before one it cannot");
    }

    @Test
    void notWithoutABaseAPairOrTheFood() {
        assertTrue(RanchChoice.next(false, Map.of(Ranch.Kind.COW, 2), Map.of(Ranch.Kind.COW, 4)).kind().isEmpty(), "a base first");
        assertTrue(RanchChoice.next(true, Map.of(Ranch.Kind.COW, 1), Map.of(Ranch.Kind.COW, 4)).kind().isEmpty(), "one cow is not a pair");
        var hungry = RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2), Map.of(Ranch.Kind.COW, 1));
        assertTrue(hungry.kind().isEmpty());
        assertTrue(hungry.why().contains("2 wheat first"), hungry.why());
        assertTrue(RanchChoice.next(true, Map.of(), Map.of(Ranch.Kind.COW, 9)).why().startsWith("No pair"));
    }
}
