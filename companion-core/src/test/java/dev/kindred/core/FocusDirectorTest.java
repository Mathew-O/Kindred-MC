package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FocusDirectorTest {
    private final FocusDirector focus = new FocusDirector();
    private final UUID owner = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private final Personality neutral = Personality.defaults();
    private final Set<Mannerisms.Quirk> none = Set.of();

    @Test
    void aChoiceIsHeldInsteadOfFlickeringAndThenDriftsOnPurpose() {
        var first = focus.choose(0, false, owner, null, null, List.of(alex), neutral, none);
        assertEquals(FocusDirector.Anchor.OWNER, first.anchor());
        for (int age = 1; age < first.dwellTicks(); age++) {
            assertEquals(first, focus.choose(age, false, owner, null, null, List.of(alex), neutral, none));
        }
        var next = focus.choose(first.dwellTicks(), true, owner, null, null, List.of(), neutral, none);
        assertEquals(FocusDirector.Anchor.PATH, next.anchor());
        var back = focus.choose(first.dwellTicks() + next.dwellTicks(), true, owner, null, null, List.of(), neutral, none);
        assertEquals(FocusDirector.Anchor.OWNER, back.anchor(), "while moving, focus comes back to the owner after watching the path");
    }

    @Test
    void aNewPersonAndAThreatInterruptAndTheThreatDoesNotLockTheGazeForever() {
        focus.choose(0, true, owner, null, null, List.of(), neutral, none);
        var notice = focus.choose(5, true, owner, alex, null, List.of(), neutral, none);
        assertEquals(FocusDirector.Anchor.OTHER, notice.anchor());
        assertEquals(alex, notice.subject());
        assertEquals(notice, focus.choose(6, true, owner, alex, null, List.of(), neutral, none), "the same arrival is not re-noticed every tick");
        UUID zombie = UUID.randomUUID();
        var threat = focus.choose(7, true, owner, null, zombie, List.of(), neutral, none);
        assertEquals(FocusDirector.Anchor.THREAT, threat.anchor());
        int after = 7 + threat.dwellTicks();
        var released = focus.choose(after, true, owner, null, zombie, List.of(), neutral, none);
        assertNotEquals(FocusDirector.Anchor.THREAT, released.anchor(), "after a look, focus moves on even if the threat is still there");
        assertNotEquals(FocusDirector.Anchor.THREAT, focus.choose(after + 10, true, owner, null, zombie, List.of(), neutral, none).anchor());
    }

    @Test
    void curiosityDriftsTowardSomeoneNearbyAndSteadyDoesNot() {
        Personality curious = neutral.withTrait(Personality.Trait.CURIOSITY, 80);
        var ownerLook = focus.choose(0, false, owner, null, null, List.of(alex), curious, Set.of(Mannerisms.Quirk.CURIOUSGLANCE));
        boolean drifted = false;
        int age = ownerLook.dwellTicks();
        for (int i = 0; i < 4; i++) {
            var choice = focus.choose(age, false, owner, null, null, List.of(alex), curious, Set.of(Mannerisms.Quirk.CURIOUSGLANCE));
            if (choice.anchor() == FocusDirector.Anchor.OTHER) {
                assertEquals(alex, choice.subject());
                drifted = true;
                break;
            }
            age += choice.dwellTicks();
        }
        assertTrue(drifted, "a curious companion looks over at someone nearby while waiting");
        FocusDirector steady = new FocusDirector();
        int steadyAge = 0;
        for (int i = 0; i < 6; i++) {
            var choice = steady.choose(steadyAge, false, owner, null, null, List.of(alex), curious, Set.of(Mannerisms.Quirk.STEADY));
            assertEquals(FocusDirector.Anchor.OWNER, choice.anchor());
            steadyAge += choice.dwellTicks();
        }
    }
}
