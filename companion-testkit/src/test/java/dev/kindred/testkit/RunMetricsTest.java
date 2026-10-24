package dev.kindred.testkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunMetricsTest {
    private static String at(int minute, int second, String text) {
        return String.format("[10:%02d:%02d] [Server thread/INFO] (Kindred) %s", minute, second, text);
    }

    @Test
    void theSameFailureOnTheSameTargetWithinTenMinutesIsARepeatAndAnotherTargetIsNot() {
        var lines = List.of(
                at(0, 0, "Wren native_goto BLOCKED: No walkable route toward 9, 63, 74 [Walk to logs at 9, 63, 74]"),
                at(1, 0, "Wren native_goto BLOCKED: No walkable route toward 9, 63, 74 [Walk to logs at 9, 63, 74]"),
                at(2, 0, "Wren native_goto BLOCKED: No walkable route toward 9, 63, 74 [Walk to logs at 9, 63, 74]"),
                at(3, 0, "Wren native_goto BLOCKED: No walkable route toward 5, 64, 75 [Walk to logs at 5, 64, 75]"),
                // Eleven minutes after the last: no longer a repeat.
                at(13, 1, "Wren native_goto BLOCKED: No walkable route toward 9, 63, 74 [Walk to logs at 9, 63, 74]"));
        var wren = RunMetrics.of(lines).players().get("Wren");
        assertEquals(2, wren.worstRepeats(), "Twice again within ten minutes of the last");
        assertTrue(wren.worstFailure.contains("9, 63, 74"));
    }

    @Test
    void workCutShortByAThreatIsResumedWhenTheSameWorkComesBackWithinFiveMinutes() {
        var lines = List.of(
                at(0, 0, "Wren goal at 7 62 107: Iron: Mine cobblestone - Dig for stone: clear 7, 63, 106"),
                at(0, 5, "Wren native_goto CANCELLED: A hostile mob is attacking; fight back first [Branch mine for iron: advance]"),
                at(0, 6, "Wren native_fight COMPLETED [Fight zombie]"),
                at(0, 30, "Wren goal at 7 62 107: Iron: Branch mine for iron - advance"),
                at(1, 0, "Wren build: hut 5x5: 3/112 placed - Place minecraft:cobblestone"),
                at(1, 5, "Wren native_place CANCELLED: Hazard/threat detected: urgent retreat required [Place cobblestone at Pos[x=1, y=2, z=3]]"),
                // Gone off to another goal and never back within five minutes.
                at(1, 10, "Wren goal at 7 62 107: Iron: Craft a furnace"),
                at(8, 0, "Wren build: hut 5x5: 4/112 placed - Place minecraft:cobblestone"));
        var wren = RunMetrics.of(lines).players().get("Wren");
        assertEquals(2, wren.interruptions());
        assertEquals(1, wren.resumed(), "The goal came back; the build only after five minutes");
        assertEquals(0.5, wren.resumedShare());
    }

    @Test
    void changesOfWorkAreCountedButNotInTheMinuteAfterAnInterruption() {
        var lines = List.of(
                at(0, 0, "Wren goal at 1 2 3: Iron: a step"),
                at(1, 0, "Wren build: hut 5x5: 3/112 placed"),
                at(2, 0, "Wren goal at 1 2 3: Iron: a step"),
                at(3, 0, "Wren native_goto CANCELLED: Falling: the fall to be broken with water [Iron: a step]"),
                at(3, 20, "Wren field: watering"),
                at(5, 0, "Wren goal at 1 2 3: Iron: a step"));
        var wren = RunMetrics.of(lines).players().get("Wren");
        assertEquals(3, wren.switches(), "Goal to build, build to goal, field to goal; not the field taken up right after the fall");
        assertEquals(0.6, wren.switchesPerMinute(), 1e-9);
    }

    @Test
    void theSameTaskEndingTheSameWayEightTimesRunningIsStuckAndRecoveredByOtherWorkDone() {
        var lines = new ArrayList<String>();
        for (int i = 0; i < 9; i++) lines.add(at(0, i, "Mason native_wait COMPLETED [Wait: No way to reach minecraft:cobblestone at Pos[x=1, y=2, z=3]]"));
        lines.add(at(0, 20, "Mason native_place COMPLETED [Place cobblestone at Pos[x=1, y=2, z=3]]"));
        for (int i = 0; i < 8; i++) lines.add(at(1, i, "Mason native_goto BLOCKED: No walkable route toward 1, 2, 3 [To a spot]"));
        var mason = RunMetrics.of(lines).players().get("Mason");
        assertEquals(2, mason.stuckSpells());
        assertEquals(1, mason.recovered(), "The first recovered by placing; the second never");
    }

    @Test
    void aRunPastMidnightKeepsItsOrder() {
        var lines = List.of(
                "[23:59:50] [Server thread/INFO] (Kindred) Wren native_goto BLOCKED: No walkable route toward 1, 2, 3 [Walk]",
                "[00:00:10] [Server thread/INFO] (Kindred) Wren native_goto BLOCKED: No walkable route toward 1, 2, 3 [Walk]");
        var wren = RunMetrics.of(lines).players().get("Wren");
        assertEquals(1, wren.worstRepeats(), "Twenty seconds apart, across midnight");
        assertEquals(20, wren.last - wren.first);
    }

    @Test
    void linesNotTheRuntimesArePassedOver() {
        var metrics = RunMetrics.of(List.of("[10:00:00] [Server thread/INFO] (Minecraft) Wren joined the game", "garbage"));
        assertTrue(metrics.players().isEmpty());
    }
}
