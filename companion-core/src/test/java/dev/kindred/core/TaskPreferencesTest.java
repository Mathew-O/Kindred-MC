package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.kindred.core.TaskPreferences.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskPreferencesTest {
    private final Personality neutral = Personality.defaults();
    private static final Eligibility AVAILABLE = new Eligibility(true, true, true, true);

    @Test
    void eachPreferenceCanTipACloseChoiceWithoutBecomingAJob() {
        var traits = List.of(Personality.Trait.COMBATIVENESS, Personality.Trait.HELPFULNESS,
                Personality.Trait.FARMING, Personality.Trait.MINING);
        var affinities = List.of(new Affinities(1, 0, 0, 0), new Affinities(0, 1, 0, 0),
                new Affinities(0, 0, 1, 0), new Affinities(0, 0, 0, 1));
        for (int index = 0; index < traits.size(); index++) {
            Candidate interesting = candidate(1, 60, affinities.get(index));
            Candidate other = candidate(2, 62, Affinities.none());
            List<Candidate> tasks = List.of(interesting, other);
            assertEquals(other, rankAvailable(tasks, neutral).getFirst().task());
            assertEquals(interesting, rankAvailable(tasks, neutral.withTrait(traits.get(index), 100)).getFirst().task());
            // Low interest is a small reluctance, not inability or refusal to perform the only useful task.
            assertEquals(interesting, rankAvailable(List.of(interesting), neutral.withTrait(traits.get(index), 0)).getFirst().task());
        }
    }

    @Test
    void neutralPreferencesPreserveNeedScoresAndTieOrderIsStable() {
        Candidate a = candidate(1, 60, new Affinities(1, 1, 1, 1));
        Candidate b = candidate(2, 60, Affinities.none());
        assertEquals(0, bias(neutral, a.affinities()));
        assertEquals(List.of(a, b), rankAvailable(List.of(b, a), neutral).stream().map(Ranked::task).toList());
        assertEquals(50, new Personality(10, 20, 30, 40, 50, 8).mining());
    }

    @Test
    void blendingSeveralPreferencesCannotStackAnUnboundedBonus() {
        Personality enthusiastic = neutral.withTrait(Personality.Trait.COMBATIVENESS, 100)
                .withTrait(Personality.Trait.HELPFULNESS, 100).withTrait(Personality.Trait.FARMING, 100)
                .withTrait(Personality.Trait.MINING, 100);
        assertEquals(5, bias(enthusiastic, new Affinities(1, 1, 1, 1)));
        assertEquals(0.5, bias(enthusiastic, new Affinities(0, 0, 0.1, 0)), 0.00001);
        assertEquals(0, bias(enthusiastic.withTrait(Personality.Trait.MINING, 0), new Affinities(0, 0, 1, 1)));
        assertEquals(0, bias(enthusiastic, Affinities.none()));
    }

    @Test
    void preferencesNeverBypassAnyEligibilityGate() {
        List<Eligibility> rejected = List.of(new Eligibility(false, true, true, true),
                new Eligibility(true, false, true, true), new Eligibility(true, true, false, true),
                new Eligibility(true, true, true, false));
        for (Eligibility gate : rejected) {
            Candidate combat = new Candidate(new UUID(0, 1), Priority.URGENT, 100, new Affinities(1, 0, 0, 0), gate);
            assertTrue(rankAvailable(List.of(combat), neutral.withTrait(Personality.Trait.COMBATIVENESS, 100)).isEmpty());
        }
    }

    @Test
    void urgencyAndSubstantialNeedRemainMoreImportantThanPreference() {
        Personality farmer = neutral.withTrait(Personality.Trait.FARMING, 100).withTrait(Personality.Trait.HELPFULNESS, 0);
        Candidate crops = candidate(1, 80, new Affinities(0, 0, 1, 0));
        Candidate delivery = candidate(2, 91, new Affinities(0, 1, 0, 0));
        assertEquals(delivery, rankAvailable(List.of(crops, delivery), farmer).getFirst().task());
        Candidate urgent = new Candidate(delivery.id(), Priority.URGENT, 10, delivery.affinities(), AVAILABLE);
        assertEquals(urgent, rankAvailable(List.of(crops, urgent), farmer).getFirst().task());
    }

    @Test
    void smallScoreChangesDoNotInterruptCurrentWorkAtEveryCheckpoint() {
        Personality farmer = neutral.withTrait(Personality.Trait.FARMING, 100);
        Candidate current = candidate(1, 62, Affinities.none());
        Candidate crops = candidate(2, 60, new Affinities(0, 0, 1, 0));
        assertEquals(crops, rankAvailable(List.of(current, crops), farmer).getFirst().task());
        assertEquals(current, chooseAtCheckpoint(List.of(current, crops), farmer, current.id()).orElseThrow().task());
        Candidate moreNeeded = candidate(2, 61, crops.affinities());
        assertEquals(moreNeeded, chooseAtCheckpoint(List.of(current, moreNeeded), farmer, current.id()).orElseThrow().task());
    }

    @Test
    void unavailableCurrentTasksAreNotRetainedAndAnEmptyChoiceDoesNotInventWork() {
        Candidate blocked = new Candidate(new UUID(0, 1), Priority.PROJECT, 100, new Affinities(0, 0, 0, 1),
                new Eligibility(true, false, true, true));
        Candidate available = candidate(2, 50, Affinities.none());
        assertEquals(available, chooseAtCheckpoint(List.of(blocked, available), neutral, blocked.id()).orElseThrow().task());
        assertTrue(chooseAtCheckpoint(List.of(blocked), neutral, blocked.id()).isEmpty());
    }

    @Test
    void invalidScoresFeaturesAndDuplicateTaskIdentitiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Affinities(0, 0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Affinities(0, -0.1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> candidate(1, 101, Affinities.none()));
        assertThrows(IllegalArgumentException.class, () -> candidate(1, Double.POSITIVE_INFINITY, Affinities.none()));
        Candidate a = candidate(1, 50, Affinities.none());
        assertThrows(IllegalArgumentException.class, () -> rankAvailable(List.of(a, a), neutral));
    }

    private static Candidate candidate(long id, double need, Affinities affinities) {
        return new Candidate(new UUID(0, id), Priority.PROJECT, need, affinities, AVAILABLE);
    }
}
