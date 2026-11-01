package dev.kindred.core.atlas;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasTest {
    private static final String OVER = "overworld";
    private static final Priors PRIORS = Priors.standard();

    private static Atlas.Place at(int x, int y, int z) { return new Atlas.Place(OVER, x, y, z); }

    /** It stood in each chunk from (x0, z0) to (x1, z1) inclusive, its biome {@code biome}. */
    private static void walked(Atlas atlas, int x0, int z0, int x1, int z1, String biome, long time) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) atlas.visit(at(x * 16 + 8, 64, z * 16 + 8), biome, time);
    }

    @Test
    void theFrontierIsEveryKnownChunkWithAnUnknownSideNeighbourAndTheNearestIsAnEdgeOne() {
        var atlas = new Atlas(PRIORS);
        walked(atlas, -1, -1, 1, 1, "minecraft:forest", 10);
        assertFalse(atlas.frontier(new Atlas.ChunkKey(OVER, 0, 0)), "The middle of a block it knows all round is not the edge");
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (x != 0 || z != 0) assertTrue(atlas.frontier(new Atlas.ChunkKey(OVER, x, z)), x + "," + z);
        }
        var nearest = atlas.nearestFrontier(at(8, 64, 8), Atlas.Resource.IRON).orElseThrow();
        assertEquals(1, Math.abs(nearest.x()) + Math.abs(nearest.z()), "A side chunk, sixteen blocks off, before a corner: " + nearest);
        assertEquals(Optional.empty(), atlas.nearestFrontier(new Atlas.Place("the_nether", 0, 64, 0), null), "Nothing known in the Nether");
    }

    @Test
    void forAVillageAFrontierChunkOnPlainsRanksAboveANearerOneInForestButNotForOre() {
        var atlas = new Atlas(PRIORS);
        atlas.visit(at(8, 64, 8), "minecraft:forest", 1);            // chunk 0,0: sixteen blocks off
        atlas.visit(at(2 * 16 + 8, 64, 8), "minecraft:plains", 1);   // chunk 2,0: forty-eight off, but plains
        assertEquals(new Atlas.ChunkKey(OVER, 2, 0), atlas.nearestFrontier(at(-8, 64, 8), Atlas.Resource.VILLAGE).orElseThrow());
        assertEquals(new Atlas.ChunkKey(OVER, 0, 0), atlas.nearestFrontier(at(-8, 64, 8), Atlas.Resource.IRON).orElseThrow(),
                "Ore is not by biome: the nearer");
    }

    @Test
    void dangerFadesToNothingOverThreeDaysAndTheOtherTimeOfDayCountsAQuarter() {
        var atlas = new Atlas(PRIORS);
        long t = 100_000;
        atlas.harmed(at(5, 70, 5), true, t, Atlas.DEATH);
        assertEquals(3.0, atlas.dangerAt(at(12, 74, 12), true, t), 1e-9, "Anywhere in the sixteen-block cell");
        assertEquals(1.5, atlas.dangerAt(at(5, 70, 5), true, t + Atlas.FADE / 2), 1e-9, "Half faded after a day and a half");
        assertEquals(0.0, atlas.dangerAt(at(5, 70, 5), true, t + Atlas.FADE), 1e-9, "Nothing after three days");
        assertEquals(0.75, atlas.dangerAt(at(5, 70, 5), false, t), 1e-9, "A night's death read by day: a quarter");
        assertEquals(0.0, atlas.dangerAt(at(17, 70, 5), true, t), 1e-9, "The next cell over is clear");
        atlas.harmed(at(5, 70, 5), true, t, 1);
        assertEquals(4.0, atlas.dangerAt(at(5, 70, 5), true, t), 1e-9, "A hurt adds one");
        assertEquals(java.util.Set.of(new Atlas.CellKey(OVER, 0, 4, 0)), atlas.dangerNear(at(20, 70, 20), 32, true, t, 1.0).keySet());
    }

    @Test
    void pastItsBoundTheChunksItNeverStoodInGoFirstOldestFirst() {
        var atlas = new Atlas(PRIORS);
        atlas.visit(at(8, 64, 8), "minecraft:plains", 0);            // stood in, the oldest of all
        for (int i = 1; i <= Atlas.MAX_CHUNKS + 500; i++) atlas.sighted(Atlas.Resource.COAL, at(i * 16 + 8, 64, 8), i, null);
        assertEquals(Atlas.MAX_CHUNKS, atlas.explored());
        assertTrue(atlas.whereToLookFor(Atlas.Resource.COAL, at(8, 64, 8)).isPresent());
        var kept = atlas.snapshot().chunks();
        assertTrue(kept.stream().anyMatch(row -> row.x() == 0 && row.visited()), "The chunk it stood in outlasts newer ones it only saw");
        assertTrue(kept.stream().noneMatch(row -> row.x() >= 1 && row.x() <= 501), "The 501 oldest seen-only chunks went first");
        assertTrue(kept.stream().anyMatch(row -> row.x() == Atlas.MAX_CHUNKS + 500));
    }

    @Test
    void whereToLookForIsTheNearestPlaceItSawOneElseTheEdgeAtTheLikelyHeight() {
        var atlas = new Atlas(PRIORS);
        walked(atlas, 0, 0, 2, 2, "minecraft:plains", 5);
        atlas.sighted(Atlas.Resource.DIAMOND, at(100, -58, 100), 7, null);
        atlas.sighted(Atlas.Resource.DIAMOND, at(30, -60, 20), 8, "Sol");
        var lead = atlas.whereToLookFor(Atlas.Resource.DIAMOND, at(8, 64, 8)).orElseThrow();
        assertTrue(lead.seen());
        assertEquals(at(30, -60, 20), lead.at(), "The nearer of the two sightings");
        assertTrue(lead.why().contains("Sol did"), "A teammate's sighting is marked as theirs: " + lead.why());
        atlas.gone(Atlas.Resource.DIAMOND, at(30, -60, 20));
        assertEquals(at(100, -58, 100), atlas.whereToLookFor(Atlas.Resource.DIAMOND, at(8, 64, 8)).orElseThrow().at(), "Looked, and none there");
        atlas.gone(Atlas.Resource.DIAMOND, at(100, -58, 100));
        var guess = atlas.whereToLookFor(Atlas.Resource.DIAMOND, at(8, 64, 8)).orElseThrow();
        assertFalse(guess.seen());
        assertTrue(guess.at().y() >= -64 && guess.at().y() <= -50, "At the height diamonds are likeliest: " + guess.at());
        assertEquals(Optional.empty(), atlas.whereToLookFor(Atlas.Resource.STRONGHOLD, at(8, 64, 8)), "The eyes find the stronghold");
        assertEquals(Optional.empty(), atlas.whereToLookFor(Atlas.Resource.FORTRESS, at(8, 64, 8)), "No fortress in the Overworld");
    }

    @Test
    void theNearestShelterWithinReachInItsDimension() {
        var atlas = new Atlas(PRIORS);
        atlas.shelter(at(0, 64, 0), "bed");
        atlas.shelter(at(40, 64, 0), "seal");
        atlas.shelter(new Atlas.Place("the_nether", 2, 64, 0), "seal");
        assertEquals("seal", atlas.safeShelterNear(at(35, 64, 0), 64).orElseThrow().kind());
        assertEquals(Optional.empty(), atlas.safeShelterNear(at(200, 64, 0), 64));
    }

    @Test
    void aSnapshotRestoresTheSameAtlas() {
        var atlas = new Atlas(PRIORS);
        walked(atlas, 0, 0, 1, 1, "minecraft:taiga", 3);
        atlas.sighted(Atlas.Resource.IRON, at(20, 12, 20), 4, null);
        atlas.harmed(at(1, 60, 1), false, 50, 1);
        atlas.shelter(at(2, 64, 2), "bed");
        var restored = Atlas.restore(atlas.snapshot(), PRIORS);
        assertEquals(atlas.snapshot(), restored.snapshot());
        assertEquals(atlas.explored(), Atlas.restore(new Atlas.Snapshot(atlas.snapshot().chunks(), null, null), PRIORS).explored(),
                "A file missing its danger and shelters reads with none");
    }

    @Test
    void thePriorsAreReadAsWrittenAndAMisspeltLineIsRefused() {
        assertEquals(4.0, PRIORS.atHeight(Atlas.Resource.DIAMOND, OVER, -59), 1e-9);
        assertEquals(1.5 + 1.0, PRIORS.atHeight(Atlas.Resource.IRON, OVER, -4), 1e-9, "Half way up iron's lower triangle, and the even band");
        assertEquals(2.0, PRIORS.atHeight(Atlas.Resource.IRON, OVER, 232), 1e-9, "The peaks' triangle at its top");
        assertEquals(1.0, PRIORS.atHeight(Atlas.Resource.DIAMOND, OVER, 0), 1e-9);
        assertEquals(0.0, PRIORS.atHeight(Atlas.Resource.DIAMOND, OVER, 20), 1e-9);
        assertEquals(16, PRIORS.bestHeight(Atlas.Resource.IRON, OVER).orElseThrow(), "Iron's heaviest band's middle");
        assertEquals(96, PRIORS.bestHeight(Atlas.Resource.COAL, OVER).orElseThrow());
        assertEquals(-59, PRIORS.bestHeight(Atlas.Resource.LAVA, OVER).orElseThrow());
        assertTrue(PRIORS.inBiome(Atlas.Resource.VILLAGE, "minecraft:snowy_plains"));
        assertFalse(PRIORS.inBiome(Atlas.Resource.VILLAGE, "minecraft:jungle"));
        assertTrue(PRIORS.inBiome(Atlas.Resource.FORTRESS, "minecraft:basalt_deltas") && !PRIORS.byBiome(Atlas.Resource.FORTRESS));
        assertEquals("the_nether", PRIORS.dimension(Atlas.Resource.FORTRESS));
        assertTrue(PRIORS.byEyes(Atlas.Resource.STRONGHOLD));
        assertThrows(IllegalArgumentException.class, () -> Priors.parse(List.of("diamond overworld y -64")));
        assertThrows(IllegalArgumentException.class, () -> Priors.parse(List.of("diamond overworld depth -64 16 1")));
    }
}
