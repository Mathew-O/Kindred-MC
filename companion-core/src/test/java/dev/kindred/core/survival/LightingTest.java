package dev.kindred.core.survival;

import dev.kindred.core.construction.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingTest {
    @Test
    void nothingDarkNothingToLight() {
        assertTrue(Lighting.next(List.of(), new Pos(0, 0, 0)).isEmpty());
    }

    @Test
    void theTorchGoesWhereItLightsTheMostOfWhatIsDark() {
        // A dark row thirty long and a lone dark cell beside the player: into the row first, though it is farther, where
        // a torch lights twenty-one of its cells, and of those spots the nearest.
        var dark = new ArrayList<Pos>();
        for (int x = 10; x < 40; x++) dark.add(new Pos(x, 0, 0));
        dark.add(new Pos(0, 0, 3));
        var first = Lighting.next(dark, new Pos(0, 0, 0)).orElseThrow();
        assertEquals(new Pos(20, 0, 0), first);
        assertEquals(21, dark.stream().filter(c -> Lighting.steps(c, first) <= Lighting.COVER).count());
    }

    @Test
    void ofCellsAlikeTheNearestAndTheLightCountedBySteps() {
        var dark = List.of(new Pos(5, 0, 0), new Pos(-5, 0, 0));
        assertEquals(new Pos(-5, 0, 0), Lighting.next(dark, new Pos(-4, 0, 0)).orElseThrow());
        assertEquals(7, Lighting.steps(new Pos(1, 2, 3), new Pos(-1, 0, 0)));
        assertEquals("I lit the work area: 1 torch.", Lighting.said(1));
        assertEquals("I lit the work area: 3 torches.", Lighting.said(3));
    }
}
