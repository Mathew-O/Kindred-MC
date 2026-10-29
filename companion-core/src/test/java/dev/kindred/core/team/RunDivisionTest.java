package dev.kindred.core.team;

import dev.kindred.core.team.RunDivision.Player;
import dev.kindred.core.team.RunDivision.Run;
import dev.kindred.core.team.ShareOut.Material;
import dev.kindred.core.team.ShareOut.Work;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a team splits the work of a run to the dragon at each stage: everyone gathers
 * before the portal, one builds the portal while the rest gather supplies, the best protected goes to the
 * Nether for blaze rods, the one with the most eyes finds the stronghold, and all meet there when ready.
 */
class RunDivisionTest {
    private static final UUID WREN = new UUID(0, 1), MASON = new UUID(0, 2), RUE = new UUID(0, 3);

    private static final Player wren = new Player(WREN, "Wren", 15, Material.IRON, 0, 0);
    private static final Player mason = new Player(MASON, "Mason", 9, Material.DIAMOND, 0, 0);
    private static final Player rue = new Player(RUE, "Rue", 12, Material.IRON, 16, 0);

    @Test
    void beforeThePortalIsLitEveryoneGathersTheBestPickaxeForTheObsidian() {
        var parts = RunDivision.divide(List.of(wren, mason, rue), new Run(false, 0, 0, 0, false, false));
        assertTrue(parts.values().stream().allMatch(p -> p.work() == Work.GATHERING));
        assertTrue(parts.get(MASON).why().startsWith("Obsidian"), parts.get(MASON).why());
    }

    @Test
    void theDiggerBuildsTheTeamsOnePortalTheRestProvisionAndABegunPortalStaysWithItsBuilder() {
        var parts = RunDivision.divide(List.of(wren, mason, rue), new Run(false, 0, 0, 0, false, false));
        assertEquals(RunDivision.Role.PORTAL, parts.get(MASON).role(), "the best pickaxe: " + parts);
        assertEquals(RunDivision.Role.PROVISIONS, parts.get(WREN).role());
        assertEquals(RunDivision.Role.PROVISIONS, parts.get(RUE).role());
        assertEquals(1, parts.values().stream().filter(p -> p.role() == RunDivision.Role.PORTAL).count(), "one portal for the team");
        // Wren has begun a portal with its iron pickaxe; Mason's diamond one does not take it over.
        var building = new Player(WREN, "Wren", 15, Material.IRON, 0, 0, true);
        var kept = RunDivision.divide(List.of(building, mason, rue), new Run(false, 0, 0, 0, false, false));
        assertEquals(RunDivision.Role.PORTAL, kept.get(WREN).role());
        assertEquals("I'm building the portal.", kept.get(WREN).says());
        assertEquals(RunDivision.Role.PROVISIONS, kept.get(MASON).role());
    }

    @Test
    void withThePortalLitTheBestProtectedGoesForRodsAndAThirdWithGoldForPearls() {
        var parts = RunDivision.divide(List.of(wren, mason, rue), new Run(true, 0, 0, 0, false, false));
        assertEquals(Work.NETHER, parts.get(WREN).work(), "the best protected: " + parts);
        assertTrue(parts.get(WREN).why().startsWith("Blaze rods"));
        assertEquals("I'll go to the Nether for blaze rods.", parts.get(WREN).says());
        assertEquals("I've got gold; I'll trade with the piglins for pearls.", parts.get(RUE).says());
        assertEquals(Work.NETHER, parts.get(RUE).work(), "with gold to trade: " + parts);
        assertEquals(Work.GATHERING, parts.get(MASON).work());
        // Two of them: no second to the Nether, whatever gold it holds.
        var two = RunDivision.divide(List.of(wren, rue), new Run(true, 0, 0, 0, false, false));
        assertEquals(Work.NETHER, two.get(WREN).work());
        assertEquals(Work.GATHERING, two.get(RUE).work());
    }

    @Test
    void withEyesEnoughTheOneWithTheMostFindsTheStrongholdTheRestGatherForTheEnd() {
        var holder = new Player(MASON, "Mason", 9, Material.DIAMOND, 0, 12);
        var parts = RunDivision.divide(List.of(wren, holder, rue), new Run(true, 6, 12, 12, false, false));
        assertEquals(Work.STRONGHOLD, parts.get(MASON).work());
        assertEquals(Work.GATHERING, parts.get(WREN).work());
        assertTrue(parts.get(WREN).why().contains("Beds"), parts.get(WREN).why());
    }

    @Test
    void readyAndTheStrongholdFoundAllMeetThere() {
        var parts = RunDivision.divide(List.of(wren, mason, rue), new Run(true, 6, 12, 12, true, true));
        assertTrue(parts.values().stream().allMatch(p -> p.work() == Work.STRONGHOLD), parts.toString());
    }

    /**
     * A lone player does each part itself, and a tie between equal players is always settled the same way.
     */
    @Test
    void aLonePlayerDoesEachPartItselfAndBetweenEqualsTheLowerId() {
        assertEquals(Work.NETHER, RunDivision.divide(List.of(mason), new Run(true, 2, 0, 0, false, false)).get(MASON).work());
        var a = new Player(MASON, "Mason", 10, Material.IRON, 0, 0);
        var b = new Player(WREN, "Wren", 10, Material.IRON, 0, 0);
        var parts = RunDivision.divide(List.of(a, b), new Run(true, 0, 0, 0, false, false));
        assertEquals(Work.NETHER, parts.get(WREN).work(), "the lower id between equals, whatever order they are given in");
        assertTrue(RunDivision.divide(List.of(), new Run(true, 0, 0, 0, false, false)).isEmpty());
    }
}
