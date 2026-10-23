package dev.kindred.core.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BaseProgressionTest {
    private static BaseProgression.Progress at(Houses.Stage built, boolean stone, boolean iron, boolean table, int free, int slots, long days) {
        return new BaseProgression.Progress(Optional.ofNullable(built), stone, iron, table, free, slots, days);
    }

    @Test
    void aBaseStartsAsACabinOnceItCanMineItsStone() {
        assertTrue(BaseProgression.next(at(null, false, false, false, 0, 0, 0)).next().isEmpty());
        assertEquals(Optional.of(Houses.Stage.CABIN), BaseProgression.next(at(null, true, false, false, 0, 0, 0)).next());
    }

    @Test
    void aCabinGivesWayToAHouseWithIronToolsWhenItsChestsFillOrTheWorldGrowsOlder() {
        // No iron: it waits, however full its chests.
        assertTrue(BaseProgression.next(at(Houses.Stage.CABIN, true, false, false, 0, 54, 9)).next().isEmpty());
        // Iron, room to spare and a young world: the cabin still serves.
        assertTrue(BaseProgression.next(at(Houses.Stage.CABIN, true, true, false, 40, 54, 1)).next().isEmpty());
        // Chests three-quarters full, or a few days gone by.
        assertEquals(Optional.of(Houses.Stage.HOUSE), BaseProgression.next(at(Houses.Stage.CABIN, true, true, false, 13, 54, 1)).next());
        assertEquals(Optional.of(Houses.Stage.HOUSE), BaseProgression.next(at(Houses.Stage.CABIN, true, true, false, 40, 54, BaseProgression.CABIN_DAYS)).next());
    }

    @Test
    void aManorIsBuiltRoundItsEnchantingTableAndIsTheLastStage() {
        assertTrue(BaseProgression.next(at(Houses.Stage.HOUSE, true, true, false, 0, 135, 20)).next().isEmpty());
        assertEquals(Optional.of(Houses.Stage.MANOR), BaseProgression.next(at(Houses.Stage.HOUSE, true, true, true, 100, 135, 5)).next());
        var last = BaseProgression.next(at(Houses.Stage.MANOR, true, true, true, 0, 189, 50));
        assertTrue(last.next().isEmpty());
        assertTrue(last.why().contains("last"));
    }
}
