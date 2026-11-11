package dev.kindred.core.wire;

import dev.kindred.core.match.GameModes;
import dev.kindred.core.match.MatchPresentation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: a match's title reaches the one AI player it was shown to, from the server, its own to know; another
 * player hears nothing of it, and a countdown's seconds are never sent.
 */
class MatchTitlesTest {
    private static final Listener.Place AT = new Listener.Place("minecraft:overworld", 0, 64, 0);

    @Test
    void aTitleReachesItsOnePlayerFromTheServerAndNoCountdownIsSent() {
        var wire = new Wire(Wire.Config.defaults());
        var fox = new Listener(UUID.randomUUID(), "Fox", false, Set.of(), "", "", Listener.side("m1", "runners"), false, AT, null);
        var hawk = new Listener(UUID.randomUUID(), "Hawk", false, Set.of(), "", "", Listener.side("m1", "hunters"), false, AT, null);
        var run = MatchTitles.of(fox.id(), new MatchPresentation.Title("Run!", "Free the End before they catch you", MatchPresentation.Cue.START)).orElseThrow();
        assertTrue(run.fromServer());
        assertTrue(run.facts().values().stream().allMatch(f -> f.visibility() == Visibility.SELF));
        assertEquals("start", run.facts().get("cue").value());
        var d = wire.send(run, GameModes.WorldMode.MANHUNT, List.of(fox, hawk), 0);
        assertEquals(List.of(fox.id()), d.receivers(), "to the runner alone: " + d.refused());
        assertTrue(d.phrase().isEmpty());
        var won = MatchTitles.of(fox.id(), new MatchPresentation.Title("Victory", "", MatchPresentation.Cue.WIN)).orElseThrow();
        assertFalse(won.facts().containsKey("subtitle"), "no empty subtitle");
        assertEquals(List.of(fox.id()), wire.send(won, GameModes.WorldMode.MANHUNT, List.of(fox, hawk), 1_000).receivers());
        assertTrue(MatchTitles.of(fox.id(), new MatchPresentation.Title("3", "Get ready", MatchPresentation.Cue.COUNT)).isEmpty(), "no countdown seconds");
    }
}
