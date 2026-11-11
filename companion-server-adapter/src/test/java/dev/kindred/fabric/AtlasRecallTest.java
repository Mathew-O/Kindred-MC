package dev.kindred.fabric;

import dev.kindred.core.atlas.Atlas;
import dev.kindred.core.atlas.Priors;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: what a player remembers again after a restart is exactly what its atlas holds in the world it
 * stands in, as it was seen: its ores, lava, water and trees there. Nothing the atlas does not hold, nothing of
 * another world, and nothing the plan's memory does not keep (a village, a fortress) is recalled.
 *
 * Technical: F-30d (b). {@link AtlasRecall#recallable} over an atlas written as the game writes it, read back
 * through its snapshot as a restart does.
 */
class AtlasRecallTest {
    private static Atlas.Place at(String dimension, int x, int y, int z) { return new Atlas.Place(dimension, x, y, z); }

    @Test
    void onlyWhatTheAtlasHoldsInThisWorldIsRecalled() {
        var atlas = new Atlas(Priors.standard());
        atlas.sighted(Atlas.Resource.DIAMOND, at("overworld", 3, 12, 4), 100, null);
        atlas.sighted(Atlas.Resource.IRON, at("overworld", 40, 30, -8), 110, null);
        atlas.sighted(Atlas.Resource.TREES, at("overworld", -20, 70, 6), 120, null);
        atlas.sighted(Atlas.Resource.LAVA, at("the_nether", 5, 31, 5), 130, null);
        atlas.sighted(Atlas.Resource.FORTRESS, at("the_nether", 80, 60, 80), 140, null);
        atlas.sighted(Atlas.Resource.VILLAGE, at("overworld", 200, 64, 200), 150, null);
        var back = Atlas.restore(atlas.snapshot(), Priors.standard()); // As a restart reads it.

        var recalled = new HashSet<>(AtlasRecall.recallable(back, "overworld"));
        assertEquals(Set.of(new AtlasRecall.Recalled(WorldKnowledge.Kind.DIAMOND_ORE, 3, 12, 4),
                new AtlasRecall.Recalled(WorldKnowledge.Kind.IRON_ORE, 40, 30, -8),
                new AtlasRecall.Recalled(WorldKnowledge.Kind.LOG, -20, 70, 6)), recalled,
                "its overworld ores and trees, where seen; not the village, not the Nether's");
        assertEquals(List.of(new AtlasRecall.Recalled(WorldKnowledge.Kind.LAVA, 5, 31, 5)), AtlasRecall.recallable(back, "the_nether"),
                "in the Nether, its lava there alone; the fortress is not the plan's to remember");
        assertTrue(AtlasRecall.recallable(new Atlas(Priors.standard()), "overworld").isEmpty(), "an empty atlas, a world's first start: nothing");
        assertTrue(AtlasRecall.recallable(back, "the_end").isEmpty(), "nothing of a world it has not seen");
    }
}
