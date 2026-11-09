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

    @Test
    void withAPairOfChickensAndNoSeedsItEarnsTheSeedsFromGrassInSight() {
        var earn = RanchChoice.next(true, Map.of(Ranch.Kind.CHICKEN, 2), Map.of(), true);
        assertTrue(earn.kind().isEmpty(), "not yet: no seeds in hand");
        assertEquals(Optional.of(Ranch.Kind.CHICKEN), earn.earn(), "seeds first, from the grass");
        assertTrue(earn.why().contains("seeds first, from the grass"), earn.why());
        // Cows with no wheat are not earned for this way (their wheat is grown), so the chickens are, though cows come first.
        assertEquals(Optional.of(Ranch.Kind.CHICKEN), RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2, Ranch.Kind.CHICKEN, 2), Map.of(), true).earn());
        // No grass in sight: nothing to earn them from.
        assertTrue(RanchChoice.next(true, Map.of(Ranch.Kind.CHICKEN, 2), Map.of(), false).earn().isEmpty());
        // With the seeds, the chickens are kept, nothing earned.
        var kept = RanchChoice.next(true, Map.of(Ranch.Kind.CHICKEN, 2), Map.of(Ranch.Kind.CHICKEN, 3), true);
        assertEquals(Optional.of(Ranch.Kind.CHICKEN), kept.kind());
        assertTrue(kept.earn().isEmpty());
        // No base, or no pair: nothing.
        assertTrue(RanchChoice.next(false, Map.of(Ranch.Kind.CHICKEN, 2), Map.of(), true).earn().isEmpty());
        assertTrue(RanchChoice.next(true, Map.of(Ranch.Kind.CHICKEN, 1), Map.of(), true).earn().isEmpty());
    }

    @Test
    void withAPairOfCowsOrSheepAndNoWheatItGrowsTheWheat() {
        var cows = RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2), Map.of(), false);
        assertTrue(cows.kind().isEmpty());
        assertEquals(Optional.of(Ranch.Kind.COW), cows.earn(), "the cows' wheat grown");
        assertTrue(cows.why().contains("2 wheat first, grown from seeds"), cows.why());
        assertEquals(Optional.of(Ranch.Kind.SHEEP), RanchChoice.next(true, Map.of(Ranch.Kind.SHEEP, 3), Map.of(), true).earn());
        // Chickens with grass in sight are the quicker to feed: their seeds first.
        assertEquals(Optional.of(Ranch.Kind.CHICKEN), RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2, Ranch.Kind.CHICKEN, 2), Map.of(), true).earn());
        // Pigs' carrots and potatoes are found, not grown from what it has: not earned.
        assertTrue(RanchChoice.next(true, Map.of(Ranch.Kind.PIG, 2), Map.of(), true).earn().isEmpty());
    }

    @Test
    void aKindAlreadyKeptIsNotKeptTwiceSoTheNextKindGetsAPenOfItsOwn() {
        var cows = java.util.Set.of(Ranch.Kind.COW);
        var next = RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2, Ranch.Kind.SHEEP, 2), Map.of(Ranch.Kind.COW, 4, Ranch.Kind.SHEEP, 4), false, cows);
        assertEquals(Optional.of(Ranch.Kind.SHEEP), next.kind(), "the sheep get a pen of their own");
        var earn = RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2, Ranch.Kind.CHICKEN, 2), Map.of(Ranch.Kind.COW, 4), true, cows);
        assertTrue(earn.kind().isEmpty());
        assertEquals(Optional.of(Ranch.Kind.CHICKEN), earn.earn(), "the chickens' seeds earned, not the cows' wheat again");
        var none = RanchChoice.next(true, Map.of(Ranch.Kind.COW, 4), Map.of(Ranch.Kind.COW, 4), true, cows);
        assertTrue(none.kind().isEmpty() && none.earn().isEmpty(), "only cows about, and cows are kept: nothing");
        assertTrue(none.why().startsWith("No pair"), none.why());
    }

    @Test
    void aPenRunOnceIsBredAgainAfterTheWaitUpToAHerd() {
        long wait = RanchChoice.AGAIN;
        var again = RanchChoice.again(Ranch.Kind.COW, 2, 1, 4, wait);
        assertTrue(again.breed(), again.why());
        assertTrue(again.why().contains("cows"), again.why());
        assertFalse(RanchChoice.again(Ranch.Kind.COW, 2, 1, 4, wait - 1).breed(), "not before the breeding wait");
        assertFalse(RanchChoice.again(Ranch.Kind.COW, 2, 1, RanchChoice.FOOD - 1, wait).breed(), "not without the food for two");
        assertFalse(RanchChoice.again(Ranch.Kind.COW, 1, 2, 4, wait).breed(), "not with fewer than two grown");
        var herd = RanchChoice.again(Ranch.Kind.SHEEP, 4, RanchChoice.HERD - 4, 4, wait);
        assertFalse(herd.breed(), "a herd is enough");
        assertTrue(herd.why().contains("sheep") && !herd.why().contains("sheeps"), herd.why());
    }

    @Test
    void aPairOfPigsAndOneCarrotOrPotatoInHandGrowsMoreFromIt() {
        var one = RanchChoice.next(true, Map.of(Ranch.Kind.PIG, 2), Map.of(Ranch.Kind.PIG, 1), false);
        assertTrue(one.kind().isEmpty(), "one is not enough to breed a pair");
        assertEquals(Optional.of(Ranch.Kind.PIG), one.earn(), "the one it holds planted and grown");
        assertTrue(one.why().contains("planted"), one.why());
        assertTrue(RanchChoice.next(true, Map.of(Ranch.Kind.PIG, 2), Map.of(), false).earn().isEmpty(), "none held: none grown from nothing");
        assertEquals(Optional.of(Ranch.Kind.PIG), RanchChoice.next(true, Map.of(Ranch.Kind.PIG, 2), Map.of(Ranch.Kind.PIG, 2), false).kind(),
                "two in hand: the ranch at once");
        assertEquals(Optional.of(Ranch.Kind.COW), RanchChoice.next(true, Map.of(Ranch.Kind.COW, 2, Ranch.Kind.PIG, 2), Map.of(Ranch.Kind.PIG, 1), false).earn(),
                "cows' wheat before pigs' carrots, in the order it keeps them");
    }
}
