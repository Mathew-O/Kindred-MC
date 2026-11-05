package dev.kindred.core.match;

import dev.kindred.core.progression.PlaystyleProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the world's game modes: each is chosen by its name, sets how the AI players play, and
 * allows only its own matches (and duels, which both players opt into); each mode and each match has its rules
 * written out; and only the manhunt hands out an item, the hunters' compass, the same for everyone.
 */
class GameModesTest {
    @Test
    void aWorldModeIsChosenByTheNameItIsTypedAs() {
        assertEquals(GameModes.WorldMode.COOP_SURVIVAL, GameModes.parse("coop").orElseThrow());
        assertEquals(GameModes.WorldMode.COOP_SURVIVAL, GameModes.parse("Co-op").orElseThrow());
        assertEquals(GameModes.WorldMode.SPEEDRUN, GameModes.parse("SPEEDRUN").orElseThrow());
        assertEquals(GameModes.WorldMode.BUILDER, GameModes.parse("builder").orElseThrow());
        assertTrue(GameModes.parse("idle").isEmpty());
        assertTrue(GameModes.parse(null).isEmpty());
        for (var mode : GameModes.WorldMode.values()) assertEquals(mode, GameModes.parse(mode.key()).orElseThrow(), mode.key());
    }

    @Test
    void eachModeMapsOntoThePlaystyleAndFactionsIsNotPlayableYet() {
        assertEquals(PlaystyleProfile.OPTIMAL_SURVIVAL, GameModes.WorldMode.COOP_SURVIVAL.playstyle);
        assertEquals(PlaystyleProfile.SPEEDRUN_ANY_PERCENT, GameModes.WorldMode.SPEEDRUN.playstyle);
        assertEquals(PlaystyleProfile.IDLE_OBSERVING, GameModes.WorldMode.BUILDER.playstyle);
        assertEquals(PlaystyleProfile.IDLE_OBSERVING, GameModes.playstyle(null, PlaystyleProfile.IDLE_OBSERVING), "no mode: as it was");
        assertFalse(GameModes.WorldMode.FACTIONS.playable);
        for (var mode : GameModes.WorldMode.values()) if (mode != GameModes.WorldMode.FACTIONS) assertTrue(mode.playable, mode.label);
    }

    @Test
    void aWorldAllowsItsOwnMatchesAndDuelsAndEveryMatchWithNoModeChosen() {
        for (var match : MatchRegistry.Mode.values()) {
            assertTrue(GameModes.allows(null, match), "no mode chosen: " + match);
            for (var world : GameModes.WorldMode.values()) {
                boolean expected = match == MatchRegistry.Mode.DUEL
                        || world == GameModes.WorldMode.SPEEDRUN && match == MatchRegistry.Mode.SPEEDRUN
                        || world == GameModes.WorldMode.MANHUNT && match == MatchRegistry.Mode.MANHUNT
                        || world == GameModes.WorldMode.BUILDER && match == MatchRegistry.Mode.BUILDER;
                assertEquals(expected, GameModes.allows(world, match), world + " " + match);
            }
        }
        assertTrue(GameModes.refusal(GameModes.WorldMode.SPEEDRUN, MatchRegistry.Mode.MANHUNT).contains("Manhunt"));
    }

    @Test
    void everyModeAndMatchDeclaresItsRulesAndOnlyTheManhuntHandsOutAnItem() {
        for (var world : GameModes.WorldMode.values()) {
            var rules = GameModes.rules(world);
            assertEquals(6, rules.lines().size());
            rules.lines().forEach(line -> assertTrue(line.length() > 12, line));
        }
        for (var match : MatchRegistry.Mode.values()) {
            var rules = GameModes.rules(match);
            assertEquals(6, rules.lines().size());
            // D6: a mode hands out an item only by an announced rule, the same for people and AI players.
            assertEquals(match == MatchRegistry.Mode.MANHUNT, rules.items().contains("compass"), match + ": " + rules.items());
            // Free blocks in Builder only.
            assertEquals(match == MatchRegistry.Mode.BUILDER, rules.edits().contains("free"), match + ": " + rules.edits());
        }
        assertTrue(GameModes.rules(MatchRegistry.Mode.DUEL).pvp().contains("only while it runs"), "D7");
    }
}
