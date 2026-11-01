package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the fight log a player keeps: each fight is written as a heading line and one line
 * per moment, the log never grows past a fixed size, and odd text cannot break the file.
 */
class EncounterRecorderTest {
    private static EncounterRecorder.Frame frame(long tick, String decision, List<String> events) {
        return new EncounterRecorder.Frame(tick, new EncounterRecorder.Self(1.5, 64, -2.25, 18, 0.95f, true, false, "minecraft:iron_sword"),
                List.of(new EncounterRecorder.Seen("z1", "zombie", 2.4, true, 14.1, 0.85, true)), decision, events);
    }

    @Test
    void aFightIsWrittenAsAHeaderAndAFrameALine() {
        var recorder = new EncounterRecorder("enc-1", "player-1", 100);
        recorder.record(frame(100, "close in", List.of()));
        recorder.record(frame(101, "strike", List.of("hit-dealt target=z1 dealt=5.904")));
        recorder.end("The target was defeated or is gone");
        var lines = recorder.jsonLines();
        assertEquals(3, lines.size());
        assertTrue(lines.getFirst().contains("\"ruleset\":\"" + CombatRuleset.ID + "\""), lines.getFirst());
        assertTrue(lines.getFirst().contains("\"frames\":2") && lines.getFirst().contains("\"dropped\":0"), lines.getFirst());
        assertTrue(lines.getFirst().contains("\"outcome\":\"The target was defeated or is gone\""), lines.getFirst());
        assertEquals("{\"tick\":101,\"self\":{\"x\":1.500,\"y\":64.000,\"z\":-2.250,\"health\":18.000,\"charge\":0.950,\"onGround\":true,"
                + "\"blocking\":false,\"held\":\"minecraft:iron_sword\"},\"seen\":[{\"id\":\"z1\",\"type\":\"zombie\",\"distance\":2.400,"
                + "\"visible\":true,\"believedHealth\":14.100,\"confidence\":0.850,\"afterMe\":true}],\"decision\":\"strike\","
                + "\"events\":[\"hit-dealt target=z1 dealt=5.904\"]}", lines.get(2));
    }

    /** A long fight keeps only its latest moments, and the log counts how many were dropped. */
    @Test
    void itIsBoundedTheOldestFramesDroppedAndCounted() {
        var recorder = new EncounterRecorder("enc-2", "player-1", 0);
        for (int t = 0; t < EncounterRecorder.MAX_FRAMES + 5; t++) recorder.record(frame(t, "close in", List.of()));
        assertEquals(EncounterRecorder.MAX_FRAMES, recorder.frames().size());
        assertEquals(5, recorder.dropped());
        assertEquals(5, recorder.frames().getFirst().tick(), "the oldest went first");
        var many = new ArrayList<String>();
        for (int i = 0; i < 40; i++) many.add("hit-taken from=zombie");
        assertEquals(EncounterRecorder.MAX_EVENTS, frame(0, "strike", many).events().size());
    }

    @Test
    void textIsEscapedSoEveryLineIsValidJson() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", EncounterRecorder.quote("a\"b\\c\nd\u0001"));
        assertEquals("null", EncounterRecorder.quote(null));
    }
}
