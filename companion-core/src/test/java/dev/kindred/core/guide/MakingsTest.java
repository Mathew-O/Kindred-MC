package dev.kindred.core.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: a player sets a crafting table down only for what its pack makes there, cuts its logs into planks
 * first when that is all it lacks, and never puts a table down for something it cannot make.
 *
 * Technical: {@link Makings} (F-25c7a), for seed 303 Sol's take-and-place loop: three white wool and logs but no planks,
 * a table set down for a bed and picked straight back up.
 */
class MakingsTest {
    private static final Set<String> PLANKS = Set.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks");
    private static final Set<String> WHITE_WOOL = Set.of("minecraft:white_wool");
    private static final Map<String, String> PLANKS_OF = Map.of("minecraft:oak_log", "minecraft:oak_planks",
            "minecraft:spruce_log", "minecraft:spruce_planks", "minecraft:birch_log", "minecraft:birch_planks");
    /** A white bed: three white wool over three planks of any wood. */
    private static final List<Set<String>> WHITE_BED = List.of(WHITE_WOOL, WHITE_WOOL, WHITE_WOOL, PLANKS, PLANKS, PLANKS);

    @Test
    void threeWoolAndLogsButNoPlanksIsPlanksFirstNotATable() {
        // Sol's pack in its stuck spell, as near as its log tells: the wool for a bed and logs, no planks.
        var held = Map.of("minecraft:white_wool", 3, "minecraft:oak_log", 6, "minecraft:cobblestone", 64);
        assertEquals(Makings.Choice.PLANKS_FIRST, Makings.of(WHITE_BED, held, PLANKS_OF));
    }

    @Test
    void threeWoolAndThreePlanksOfMixedWoodIsMadeAtATable() {
        var held = Map.of("minecraft:white_wool", 3, "minecraft:oak_planks", 2, "minecraft:spruce_planks", 1);
        assertEquals(Makings.Choice.HELD, Makings.of(WHITE_BED, held, PLANKS_OF));
    }

    @Test
    void noTableForWhatNeitherItsPackNorItsLogsMake() {
        // Two wool: the planks are no help.
        assertEquals(Makings.Choice.NONE, Makings.of(WHITE_BED, Map.of("minecraft:white_wool", 2, "minecraft:oak_log", 6), PLANKS_OF));
        // Wool of three colours: no bed of any one of them.
        assertEquals(Makings.Choice.NONE, Makings.of(WHITE_BED,
                Map.of("minecraft:white_wool", 1, "minecraft:red_wool", 1, "minecraft:blue_wool", 1, "minecraft:oak_planks", 3), PLANKS_OF));
        // Wool and nothing that makes planks.
        assertEquals(Makings.Choice.NONE, Makings.of(WHITE_BED, Map.of("minecraft:white_wool", 3, "minecraft:stick", 8), PLANKS_OF));
    }

    @Test
    void oneLogMakesFourPlanksNotOne() {
        // Two planks and no logs fall short of three; one log makes four, enough.
        assertEquals(Makings.Choice.NONE, Makings.of(WHITE_BED, Map.of("minecraft:white_wool", 3, "minecraft:oak_planks", 2), PLANKS_OF));
        assertEquals(Makings.Choice.PLANKS_FIRST, Makings.of(WHITE_BED,
                Map.of("minecraft:white_wool", 3, "minecraft:oak_planks", 2, "minecraft:birch_log", 1), PLANKS_OF));
    }

    @Test
    void anItemIsSpentOnceACount() {
        // A bucket: three iron ingots in a V. Two held is not three, however many slots each could fill.
        Set<String> iron = Set.of("minecraft:iron_ingot");
        var bucket = List.of(iron, iron, iron);
        assertEquals(Makings.Choice.NONE, Makings.of(bucket, Map.of("minecraft:iron_ingot", 2), PLANKS_OF));
        assertEquals(Makings.Choice.HELD, Makings.of(bucket, Map.of("minecraft:iron_ingot", 3), PLANKS_OF));
    }

    @Test
    void theMostParticularSlotIsFilledFirst() {
        // A slot that takes only oak planks, and two that take any planks, from one oak and two spruce: filled, the
        // oak kept for the slot that takes nothing else.
        var recipe = List.of(PLANKS, PLANKS, Set.of("minecraft:oak_planks"));
        assertEquals(Makings.Choice.HELD, Makings.of(recipe, Map.of("minecraft:oak_planks", 1, "minecraft:spruce_planks", 2), PLANKS_OF));
    }

    @Test
    void emptySlotsAskNothing() {
        var recipe = List.of(Set.<String>of(), Set.of("minecraft:stick"), Set.<String>of());
        assertEquals(Makings.Choice.HELD, Makings.of(recipe, Map.of("minecraft:stick", 1), PLANKS_OF));
    }
}
