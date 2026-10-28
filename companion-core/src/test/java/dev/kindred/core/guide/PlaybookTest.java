package dev.kindred.core.guide;

import dev.kindred.core.guide.Playbook.Approach;
import dev.kindred.core.guide.Playbook.Mode;
import dev.kindred.core.guide.Playbook.Option;
import dev.kindred.core.guide.Playbook.Part;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlaybookTest {
    private static Optional<Approach> choose(Part part, List<Option> options, Mode mode) {
        return Playbook.choose(part, options, mode, Optional.empty());
    }

    @Test
    void normalPlayTakesTheOrdinaryWayUnlessAFasterOneIsAtHand() {
        var walk = new Option(Approach.OVERWORLD_WALK, true, true, 300, 0);
        var shortcut = new Option(Approach.NETHER_SHORTCUT, true, false, 120, 0);
        assertEquals(Approach.OVERWORLD_WALK, choose(Part.STRONGHOLD_WAY, List.of(walk, shortcut), Mode.NORMAL).orElseThrow(),
                "the shortcut would mean going out of its way for it");
        assertEquals(Approach.NETHER_SHORTCUT, choose(Part.STRONGHOLD_WAY, List.of(walk, shortcut), Mode.SPEEDRUN).orElseThrow());
        var atHand = new Option(Approach.NETHER_SHORTCUT, true, true, 120, 0);
        assertEquals(Approach.NETHER_SHORTCUT, choose(Part.STRONGHOLD_WAY, List.of(walk, atHand), Mode.NORMAL).orElseThrow(),
                "the chance is there: taken");
        // At hand but slower: the ordinary way still.
        var slowAtHand = new Option(Approach.NETHER_SHORTCUT, true, true, 400, 0);
        assertEquals(Approach.OVERWORLD_WALK, choose(Part.STRONGHOLD_WAY, List.of(walk, slowAtHand), Mode.NORMAL).orElseThrow());
    }

    @Test
    void aWayThatFailsTwiceIsSetAsideForTheNext() {
        var through = new Option(Approach.SHOOT_THROUGH, true, true, 10, 0);
        var pillar = new Option(Approach.PILLAR, true, true, 60, 0);
        assertEquals(Approach.SHOOT_THROUGH, choose(Part.CAGED_CRYSTAL, List.of(through, pillar), Mode.NORMAL).orElseThrow());
        var missed = new Option(Approach.SHOOT_THROUGH, true, true, 10, Playbook.MAX_FAILURES - 1);
        assertEquals(Approach.SHOOT_THROUGH, choose(Part.CAGED_CRYSTAL, List.of(missed, pillar), Mode.NORMAL).orElseThrow(), "once is not set aside");
        var failed = new Option(Approach.SHOOT_THROUGH, true, true, 10, Playbook.MAX_FAILURES);
        assertEquals(Approach.PILLAR, choose(Part.CAGED_CRYSTAL, List.of(failed, pillar), Mode.NORMAL).orElseThrow());
        var noBlocks = new Option(Approach.PILLAR, false, true, 60, 0);
        assertTrue(choose(Part.CAGED_CRYSTAL, List.of(failed, noBlocks), Mode.NORMAL).isEmpty(), "nothing left to try");
    }

    @Test
    void aRecommendationIsTakenWhenItCanBeDoneAndIsNotFarWorse() {
        var hunt = new Option(Approach.HUNT_ENDERMEN, true, true, 200, 0);
        var barter = new Option(Approach.BARTER, true, true, 250, 0);
        assertEquals(Approach.HUNT_ENDERMEN, Playbook.choose(Part.PEARLS, List.of(hunt, barter), Mode.NORMAL, Optional.empty()).orElseThrow());
        assertEquals(Approach.BARTER, Playbook.choose(Part.PEARLS, List.of(hunt, barter), Mode.NORMAL, Optional.of(Approach.BARTER)).orElseThrow());
        var dearBarter = new Option(Approach.BARTER, true, true, 400, 0);
        assertEquals(Approach.HUNT_ENDERMEN, Playbook.choose(Part.PEARLS, List.of(hunt, dearBarter), Mode.NORMAL, Optional.of(Approach.BARTER)).orElseThrow(),
                "twice the cost: not taken");
        // A recommendation for another part changes nothing, and one that cannot be done is passed over.
        assertEquals(Approach.HUNT_ENDERMEN, Playbook.choose(Part.PEARLS, List.of(hunt, barter), Mode.NORMAL, Optional.of(Approach.PILLAR)).orElseThrow());
        var noGold = new Option(Approach.BARTER, false, false, 100, 0);
        assertEquals(Approach.HUNT_ENDERMEN, Playbook.choose(Part.PEARLS, List.of(hunt, noGold), Mode.SPEEDRUN, Optional.of(Approach.BARTER)).orElseThrow());
    }

    @Test
    void everyApproachBelongsToAPartAndParsesByName() {
        for (var approach : Approach.values()) {
            assertNotNull(approach.part());
            assertEquals(Optional.of(approach), Approach.parse(approach.name().toLowerCase().replace('_', ' ')));
        }
        assertTrue(Approach.parse("teleport").isEmpty());
    }
}
