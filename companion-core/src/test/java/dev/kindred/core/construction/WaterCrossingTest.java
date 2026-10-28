package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterCrossingTest {
    private static final Pos HALL = new Pos(0, 64, 0);

    /** Flat firm ground at 63, open over it, with {@code changes} by column. */
    private static BaseSite.Ground ground(Map<Long, BaseSite.Column> changes) {
        return (x, z) -> Optional.of(changes.getOrDefault(key(x, z), new BaseSite.Column(63, true, 8)));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** A lake right across the way from z {@code from} to {@code to}, its water's top level with the ground's. */
    private static Map<Long, BaseSite.Column> lake(int from, int to) {
        var lake = new HashMap<Long, BaseSite.Column>();
        for (int x = -80; x <= 80; x++) for (int z = from; z <= to; z++) lake.put(key(x, z), new BaseSite.Column(63, false, 8, true));
        return lake;
    }

    @Test
    void aLakeTooWideToBridgeIsRowedAcrossToABankWithALineHome() {
        var cell = TradingHall.cells(3).get(1);
        // Forty columns of water between the villager and the hall: more than a bridge is laid over.
        var lake = lake(14, 53);
        assertTrue(RailRoute.plan(ground(lake), 3, 56, HALL, 3, cell).isEmpty(), "No line over forty columns of water");
        var crossing = WaterCrossing.plan(ground(lake), 3, 56, HALL, 3, cell).orElseThrow();
        // Across to the hall's bank, beside the last of the water; a line home from there with a boat's room.
        assertEquals(13, crossing.landing().z(), "Landed on the hall's bank: " + crossing);
        assertEquals(14, crossing.water().z(), "From the water beside it");
        assertEquals(1, Math.abs(crossing.landing().x() - crossing.water().x()) + Math.abs(crossing.landing().z() - crossing.water().z()), "Side by side");
        assertTrue(crossing.row() >= 40 && crossing.row() <= WaterCrossing.MAX_ROW, "A row across: " + crossing.row());
        assertTrue(crossing.home().bridge().isEmpty(), "No bridge on the line home");
        assertEquals(new Pos(cell.stand().x(), 65, cell.stand().z()), crossing.home().rails().getLast(), "The line ends on the cell's floor");
    }

    @Test
    void noCrossingWithoutLevelGroundToTheWaterOrAFarBankToStepOutOnto() {
        var cell = TradingHall.cells(3).get(1);
        // Too far from the water's edge: six columns of ground before it.
        assertTrue(WaterCrossing.plan(ground(lake(14, 53)), 3, 60, HALL, 3, cell).isEmpty(), "More than four columns of ground to row over");
        // A far bank two blocks over the water all along: no stepping out onto it.
        var cliff = lake(14, 53);
        for (int x = -80; x <= 80; x++) for (int z = -10; z <= 13; z++) cliff.put(key(x, z), new BaseSite.Column(65, true, 8));
        assertTrue(WaterCrossing.plan(ground(cliff), 3, 56, HALL, 3, cell).isEmpty(), "A bank too high to land on");
        // Standing in the water itself: nothing to hold it in a boat on.
        assertTrue(WaterCrossing.plan(ground(lake(14, 53)), 3, 40, HALL, 3, cell).isEmpty(), "A villager in the water");
        // Water too wide to row across.
        assertTrue(WaterCrossing.plan(ground(lake(14, 90)), 3, 93, HALL, 3, cell).isEmpty(), "More than a row's length of water");
    }

    @Test
    void ofTheBanksFoundTheOneLeastRowedAndLaidIsTaken() {
        var cell = TradingHall.cells(3).get(1);
        var lake = lake(14, 53);
        // The hall's bank right by the door is lava but for a gap off to the side, long enough for a line to set out along
        // it with its run-up behind it (a line from a bank never sets out from the water): landed in the gap.
        for (int x = -80; x <= 80; x++) if (x < -12 || x > -2) lake.put(key(x, 13), new BaseSite.Column(62, false, 8));
        var crossing = WaterCrossing.plan(ground(lake), 3, 56, HALL, 3, cell).orElseThrow();
        assertTrue(crossing.landing().z() == 13 && crossing.landing().x() >= -12 && crossing.landing().x() <= -2, "Landed at the gap: " + crossing);
        assertTrue(crossing.home().rails().stream().noneMatch(p -> p.z() == 13 && (p.x() < -12 || p.x() > -2)), "Never over the lava: " + crossing.home().rails());
    }
}
