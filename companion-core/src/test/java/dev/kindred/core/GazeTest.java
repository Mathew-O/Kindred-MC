package dev.kindred.core;

import dev.kindred.core.Gaze.Kind;
import dev.kindred.core.Gaze.Point;
import dev.kindred.core.Gaze.Seen;
import dev.kindred.core.Gaze.View;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GazeTest {
    private static final Personality PLAIN = new Personality(50, 50, 50, 50, 50, 0);
    private static final UUID ZOMBIE = new UUID(0, 1), WREN = new UUID(0, 2), MASON = new UUID(0, 3);

    private static View view(long tick, Seen threat, Seen speaker, List<Seen> people, Point work) {
        return new View(tick, 0, 1.62, 0, 0, threat, speaker, people, work);
    }

    @Test
    void aThreatInSightIsWatchedBeforeAnyoneSpeakingOrTheWork() {
        var gaze = new Gaze(7, PLAIN);
        // A zombie due east: Minecraft's yaw for +x is -90.
        var look = gaze.choose(view(0, new Seen(ZOMBIE, 5, 1.62, 0, false), new Seen(WREN, 0, 1.62, 5, true),
                List.of(new Seen(WREN, 0, 1.62, 5, true)), new Point(-3, 1, 0)));
        assertEquals(Kind.THREAT, look.kind());
        assertEquals(-90, look.yaw(), 1e-3);
        assertEquals(0, look.pitch(), 1e-3);
        assertEquals(Gaze.THREAT_TURN, look.maxTurn());
    }

    @Test
    void aSpeakerIsLookedAtAndOtherwiseWhatItWaitsOn() {
        var gaze = new Gaze(7, PLAIN);
        var spoken = gaze.choose(view(0, null, new Seen(WREN, 0, 1.62, 5, false), List.of(), new Point(-3, 1, 0)));
        assertEquals(Kind.SPEAKER, spoken.kind());
        assertEquals(0, spoken.yaw(), 1e-3, "due south is yaw 0");
        var work = gaze.choose(view(1, null, null, List.of(), new Point(-3, 0.5, 0)));
        assertEquals(Kind.WORK, work.kind());
        assertEquals(90, work.yaw(), 1e-3, "due west is yaw 90");
        assertTrue(work.pitch() > 0, "the furnace below its eyes: looking down is a positive pitch");
    }

    @Test
    void aPlayerLookingAtItCloseByIsLookedBackAtForAMomentThenLeftBeAWhile() {
        var gaze = new Gaze(7, PLAIN);
        var wren = new Seen(WREN, 3, 1.62, 0, true);
        var first = gaze.choose(view(0, null, null, List.of(wren), null));
        assertEquals(Kind.PERSON, first.kind());
        // Held while it lasts (30 + 50/3 = 46 ticks), even once Wren looks away.
        assertEquals(Kind.PERSON, gaze.choose(view(40, null, null, List.of(new Seen(WREN, 3, 1.62, 0, false)), null)).kind());
        // Then back to its own business, though Wren still looks at it.
        assertEquals(Kind.ABOUT, gaze.choose(view(47, null, null, List.of(wren), null)).kind());
        assertEquals(Kind.ABOUT, gaze.choose(view(150, null, null, List.of(wren), null)).kind(), "not again so soon");
        // Another player who looks at it is met, and after a while Wren is again.
        assertEquals(Kind.PERSON, gaze.choose(view(151, null, null, List.of(wren, new Seen(MASON, -2, 1.62, 2, true)), null)).kind());
        assertEquals(MASON, gaze.choose(view(152, null, null, List.of(wren, new Seen(MASON, -2, 1.62, 2, true)), null)).subject());
        assertEquals(WREN, gaze.choose(view(400, null, null, List.of(wren), null)).subject());
    }

    @Test
    void aPlayerFarOffOrNotLookingIsNotStaredAt() {
        var gaze = new Gaze(7, PLAIN);
        assertEquals(Kind.ABOUT, gaze.choose(view(0, null, null, List.of(new Seen(WREN, 10, 1.62, 0, true)), null)).kind());
        assertEquals(Kind.ABOUT, gaze.choose(view(1, null, null, List.of(new Seen(MASON, 2, 1.62, 0, false)), null)).kind());
        // Gone from view mid-look: no look held on nothing.
        var held = new Gaze(7, PLAIN);
        held.choose(view(0, null, null, List.of(new Seen(WREN, 3, 1.62, 0, true)), null));
        assertEquals(Kind.ABOUT, held.choose(view(5, null, null, List.of(), null)).kind());
    }

    @Test
    void lookingAboutHoldsEachWayAFewSecondsAndPlayersDoNotTurnAsOne() {
        var gaze = new Gaze(7, PLAIN);
        var ways = new HashSet<Float>();
        float first = gaze.choose(view(0, null, null, List.of(), null)).yaw();
        for (int t = 1; t < 40; t++) assertEquals(first, gaze.choose(view(t, null, null, List.of(), null)).yaw(), "held at least two seconds");
        for (int t = 0; t < 2000; t++) {
            var look = gaze.choose(view(t, null, null, List.of(), null));
            ways.add(look.yaw());
            assertTrue(look.pitch() >= -10 && look.pitch() <= 25, "never at the sky: " + look.pitch());
            assertTrue(Math.abs(look.yaw()) <= 70, "within seventy degrees of where it faces: " + look.yaw());
        }
        assertTrue(ways.size() >= 15 && ways.size() <= 40, "a way every two to four and a half seconds over a hundred seconds: " + ways.size());
        var other = new Gaze(8, PLAIN);
        assertNotEquals(first, other.choose(view(0, null, null, List.of(), null)).yaw(), "another player's seed, another way");
    }

    @Test
    void theHeadEasesBrisklyThenSettlesNeverOvershootingNorPassingItsTurn() {
        var look = new Gaze.Look(Kind.THREAT, ZOMBIE, 170, 20, Gaze.THREAT_TURN);
        float yaw = -170, pitch = 0;
        float[] next = Gaze.step(yaw, pitch, look);
        // The short way round is twenty degrees west through 180, not 340 east.
        assertTrue(next[0] < -170 || next[0] > 170, "through 180: " + next[0]);
        int ticks = 0;
        while ((Math.abs(Gaze.wrap(look.yaw() - yaw)) > 1e-3 || Math.abs(look.pitch() - pitch) > 1e-3) && ticks < 100) {
            float[] step = Gaze.step(yaw, pitch, look);
            assertTrue(Math.abs(Gaze.wrap(step[0] - yaw)) <= Gaze.THREAT_TURN + 1e-3);
            assertTrue(Math.abs(step[1] - pitch) <= Gaze.THREAT_TURN + 1e-3);
            yaw = step[0];
            pitch = step[1];
            ticks++;
        }
        assertTrue(ticks < 20, "settled within a second: " + ticks);
        assertEquals(20, pitch, 1e-3);
        var slow = new Gaze.Look(Kind.ABOUT, null, 90, 0, Gaze.ABOUT_TURN);
        assertEquals(Gaze.ABOUT_TURN, Gaze.step(0, 0, slow)[0], 1e-3, "looking about turns slowly");
    }
}
