package dev.kindred.core.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalRecordTest {
    private static EvalRecord.Death death(long tick) {
        return new EvalRecord.Death(tick, "Sol was shot by Skeleton", 13, 18, 122, 0, 13500, "minecraft:overworld", 6, false,
                "Wood", "PRESS_ON", "[]", "Take cover from skeleton");
    }

    @Test
    void eachStageKeepsTheFirstTickItWasReached() {
        var record = new EvalRecord(EvalScenario.parse("starters=Sol"));
        record.reached("Sol", "wooden_tool", 400);
        record.reached("Sol", "wooden_tool", 900);
        record.reached("Sol", "stone_tools", 700);
        assertTrue(record.hasReached("Sol", "wooden_tool"));
        assertFalse(record.hasReached("Sol", "iron_ingot"));
        String json = record.toJson("2bceb1f", 31102026, 1000, true);
        assertTrue(json.contains("\"stages\": {\"wooden_tool\": 400, \"stone_tools\": 700}"), json);
        assertThrows(IllegalArgumentException.class, () -> record.reached("Sol", "wither", 1));
    }

    @Test
    void theFileCarriesTheScenarioTheKindAndEveryDeathsFacts() {
        var record = new EvalRecord(EvalScenario.parse("starters=Kindred,Sol; ticks=200; keep=inventory"));
        record.died("Sol", death(98729));
        record.stuck("Kindred", new EvalRecord.Stuck(193500, -2, 45, 156, 3));
        record.tokens("Kindred", 1200);
        record.tokens("Kindred", 900);
        String json = record.toJson("abc \"quoted\"", 8675309, 200, true);
        assertTrue(json.contains("\"scenario\": \"starters=Kindred,Sol; goal=nether; edits=on; ticks=200; sprint=on; strategist=off; voice=off; keep=inventory; lessons=on; atlas=on; rescue=on; advice=on\""), json);
        assertTrue(json.contains("\"kind\": \"staged\""), "kept inventories are staged");
        assertTrue(json.contains("\"build\": \"abc \\\"quoted\\\"\""), "quotes escaped");
        assertTrue(json.contains("\"seed\": 8675309") && json.contains("\"ticksRun\": 200") && json.contains("\"complete\": true"));
        assertTrue(json.contains("{\"tick\": 98729, \"cause\": \"Sol was shot by Skeleton\", \"x\": 13, \"y\": 18, \"z\": 122, \"light\": 0, "
                + "\"timeOfDay\": 13500, \"dimension\": \"minecraft:overworld\", \"hunger\": 6, \"underSky\": false, \"stage\": \"Wood\", "
                + "\"stance\": \"PRESS_ON\", \"habits\": \"[]\", \"task\": \"Take cover from skeleton\"}"), json);
        assertTrue(json.contains("\"stuck\": [{\"tick\": 193500, \"x\": -2, \"y\": 45, \"z\": 156, \"attempt\": 3}]"), json);
        assertTrue(json.contains("\"tokens\": 1200"), "the highest total stands");
        assertEquals(1, record.deaths("Sol"));
        assertEquals(0, record.deaths("Kindred"));
    }

    @Test
    void aRunThatLoopsCannotGrowTheFileWithoutEnd() {
        var record = new EvalRecord(EvalScenario.parse("starters=Echo"));
        for (int i = 0; i < EvalRecord.KEPT + 25; i++) record.died("Echo", death(i));
        assertEquals(EvalRecord.KEPT + 25, record.deaths("Echo"));
        String json = record.toJson("b", 1, 1, false);
        assertTrue(json.contains("\"deathsNotListed\": 25"), "the rest counted");
        assertTrue(json.contains("\"complete\": false"));
        assertFalse(json.contains("\"tick\": " + (EvalRecord.KEPT + 1) + ","), "past the bound, not listed");
    }

    @Test
    void theServersLoadSaysWhenItsWorstTickFellAndHowManyRanLong() {
        var record = new EvalRecord(EvalScenario.parse("starters=Echo"));
        record.server(4.494, 1326.95, 6001, 41, 3, 1211, 14400);
        String json = record.toJson("b", 1, 1, true);
        assertTrue(json.contains("\"server\": {\"tickMsMean\": 4.49, \"tickMsWorst\": 1326.95, \"tickMsWorstAt\": 6001, "
                + "\"ticksOver50ms\": 41, \"ticksOver200ms\": 3, \"heapMbMax\": 1211, \"samples\": 14400}"), json);
    }

    @Test
    void controlCharactersAreEscaped() {
        assertEquals("\"a\\nb\\tc\\u0001\\\\\"", Json.string("a\nb\tc\u0001\\"));
        assertEquals("null", Json.string(null));
    }

    @Test
    void chunksAndTheWorldsDaysAreWrittenPerPlayerAndForTheRun() {
        var record = new EvalRecord(EvalScenario.parse("starters=Kindred; goal=nether"));
        String before = record.toJson("b", 1, 1, true);
        assertFalse(before.contains("\"days\""), "no days until set");
        record.chunks("Kindred", 289, 289 * 20);
        record.chunks("Kindred", 17, 289 * 20);
        record.chunks("Nobody", 5, 5);
        record.days(2.5);
        String json = record.toJson("b", 1, 40, true);
        assertTrue(json.contains("\"chunkLoads\": 306"), json);
        assertTrue(json.contains("\"chunkMinutes\": 9.6"), json);
        assertTrue(json.contains("\"days\": 2.500"), json);
    }

    @Test
    void bridgeBlocksAndTheirSecondsAreSummedPerPlayer() {
        var record = new EvalRecord(EvalScenario.parse("starters=Kindred; goal=nether"));
        assertTrue(record.toJson("b", 1, 1, true).contains("\"bridgeBlocks\": 0"), "none until a run ends");
        record.bridged("Kindred", 12, 77);
        record.bridged("Kindred", 5, 43);
        record.bridged("Nobody", 3, 9);
        String json = record.toJson("b", 1, 40, true);
        assertTrue(json.contains("\"bridgeBlocks\": 17"), json);
        assertTrue(json.contains("\"bridgeSeconds\": 6.0"), json);
    }
}
