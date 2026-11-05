package dev.kindred.core.match;

import dev.kindred.core.construction.Houses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that in a Co-op Survival world an AI player's base waits for the humans' own progress: a
 * house until someone has smelted iron, a manor until someone has enchanted, never the first cabin; and that nothing
 * waits in another mode or before any human has made a start.
 */
class GroupPaceTest {
    @Test
    void theFurthestStepIsTakenFromVanillasOwnAdvancements() {
        assertEquals(Optional.empty(), GroupPace.furthest(List.of("minecraft:story/root", "minecraft:adventure/kill_a_mob")));
        assertEquals(Optional.of(GroupPace.Step.IRON), GroupPace.furthest(List.of("minecraft:story/mine_stone", "minecraft:story/smelt_iron",
                "minecraft:story/upgrade_tools")));
        assertEquals(Optional.of(GroupPace.Step.NETHER), GroupPace.furthest(List.of("minecraft:story/enter_the_nether", "minecraft:story/mine_stone")));
        assertEquals(Optional.of(GroupPace.Step.DIAMOND), GroupPace.further(Optional.of(GroupPace.Step.DIAMOND), Optional.of(GroupPace.Step.IRON)));
        assertEquals(Optional.of(GroupPace.Step.IRON), GroupPace.further(Optional.empty(), Optional.of(GroupPace.Step.IRON)));
        assertEquals(Optional.of(GroupPace.Step.ENCHANTING), GroupPace.Step.named(" enchanting"));
    }

    @Test
    void aHouseWaitsForIronAndAManorForEnchantingButTheCabinNeverWaits() {
        var stone = Optional.of(GroupPace.Step.STONE);
        assertTrue(GroupPace.hold(Houses.Stage.CABIN, true, stone).isEmpty(), "the first shelter is never held back");
        var house = GroupPace.hold(Houses.Stage.HOUSE, true, stone);
        assertTrue(house.isPresent());
        assertTrue(house.get().contains("smelted iron"), house.get());
        assertTrue(GroupPace.hold(Houses.Stage.HOUSE, true, Optional.of(GroupPace.Step.IRON)).isEmpty());
        assertTrue(GroupPace.hold(Houses.Stage.MANOR, true, Optional.of(GroupPace.Step.DIAMOND)).isPresent());
        assertTrue(GroupPace.hold(Houses.Stage.MANOR, true, Optional.of(GroupPace.Step.ENCHANTING)).isEmpty());
        assertTrue(GroupPace.hold(Houses.Stage.MANOR, true, Optional.of(GroupPace.Step.END)).isEmpty());
    }

    @Test
    void nothingWaitsOutsideCoopOrBeforeAnyHumanHasMadeAStart() {
        assertTrue(GroupPace.hold(Houses.Stage.MANOR, false, Optional.of(GroupPace.Step.STONE)).isEmpty(), "not paced in another mode");
        assertTrue(GroupPace.hold(Houses.Stage.MANOR, true, Optional.empty()).isEmpty(), "never gated on a human being there");
    }

    @Test
    void aTripIntoAnotherWorldWaitsUntilAHumanHasEnteredIt() {
        var iron = Optional.of(GroupPace.Step.IRON);
        var nether = GroupPace.holdTrip(GroupPace.Step.NETHER, true, iron);
        assertTrue(nether.isPresent() && nether.get().contains("into the Nether once someone has entered the Nether"), nether.toString());
        assertTrue(GroupPace.holdTrip(GroupPace.Step.NETHER, true, Optional.of(GroupPace.Step.NETHER)).isEmpty());
        assertTrue(GroupPace.holdTrip(GroupPace.Step.END, true, Optional.of(GroupPace.Step.NETHER)).isPresent(), "the End waits for the End");
        assertTrue(GroupPace.holdTrip(GroupPace.Step.END, true, Optional.of(GroupPace.Step.END)).isEmpty());
        assertTrue(GroupPace.holdTrip(GroupPace.Step.NETHER, false, iron).isEmpty(), "not paced in another mode");
        assertTrue(GroupPace.holdTrip(GroupPace.Step.NETHER, true, Optional.empty()).isEmpty(), "never gated on a human being there");
    }
}
