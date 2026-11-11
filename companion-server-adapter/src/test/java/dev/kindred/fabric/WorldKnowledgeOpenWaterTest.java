package dev.kindred.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Open water by its own sight alone (F-35j): what {@code WorldKnowledge} remembers of still water open to the air, by
 * column, and nothing read from the world. A walk's end within its sight is open water when every column within two of it
 * was seen as water; beyond its sight, when the last columns out to the edge of its sight along the way were.
 */
class WorldKnowledgeOpenWaterTest {
    private static final BlockPos AT = new BlockPos(0, 64, 0);
    private static final int RUN = FactionsPlayer.OPEN_WATER;

    /** Seen still water at the surface, Y 62, across x {@code fromX..toX} and z {@code fromZ..toZ}. */
    private static void sea(WorldKnowledge seen, int fromX, int toX, int fromZ, int toZ) {
        for (int x = fromX; x <= toX; x++) for (int z = fromZ; z <= toZ; z++) seen.remember(WorldKnowledge.Kind.WATER, new BlockPos(x, 62, z));
    }

    @Test
    void aWalksEndInSightAmidWaterItSawIsOpenWater() {
        var seen = new WorldKnowledge();
        sea(seen, 5, 30, -10, 10);
        assertTrue(seen.seenOpenWaterToward(AT, new BlockPos(15, 64, 0), RUN), "fifteen east, water all round it seen");
    }

    @Test
    void oneColumnRoundTheEndUnseenAndItIsNot() {
        var seen = new WorldKnowledge();
        for (int x = 5; x <= 30; x++) for (int z = -10; z <= 10; z++) if (!(x == 17 && z == 2)) seen.remember(WorldKnowledge.Kind.WATER, new BlockPos(x, 62, z));
        assertFalse(seen.seenOpenWaterToward(AT, new BlockPos(15, 64, 0), RUN), "a column by the end it has not seen: not known as water");
    }

    @Test
    void nothingSeenIsNothingKnownAsWater() {
        assertFalse(new WorldKnowledge().seenOpenWaterToward(AT, new BlockPos(15, 64, 0), RUN), "out of its sight: asked as before");
        assertFalse(new WorldKnowledge().seenOpenWaterToward(AT, new BlockPos(48, 64, 0), RUN));
    }

    @Test
    void aLegBeyondItsSightOverWaterSeenToTheEdgeOfItsSightIsOpenWater() {
        var seen = new WorldKnowledge();
        sea(seen, 5, WorldKnowledge.HORIZONTAL_RADIUS, -2, 2);
        assertTrue(seen.seenOpenWaterToward(AT, new BlockPos(48, 64, 0), RUN), "a leg of 48 east, the sea seen out to 32");
    }

    @Test
    void landSeenBeyondAStraitIsNotOpenWater() {
        var seen = new WorldKnowledge();
        // Water from 5 to 15 east, then land it saw (not water) from 16 on: a strait, with land beyond.
        sea(seen, 5, 15, -2, 2);
        assertFalse(seen.seenOpenWaterToward(AT, new BlockPos(48, 64, 0), RUN), "a crossing to land: not open water");
    }

    @Test
    void waterSeenOnlyPartWayOutIsNotOpenWater() {
        var seen = new WorldKnowledge();
        // Low over the water it saw the surface only to 18 east; beyond, nothing seen.
        sea(seen, 5, 18, -2, 2);
        assertFalse(seen.seenOpenWaterToward(AT, new BlockPos(48, 64, 0), RUN), "past what it saw: asked as before");
    }
}
