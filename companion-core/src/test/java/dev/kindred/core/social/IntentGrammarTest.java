package dev.kindred.core.social;

import dev.kindred.core.Personality;
import dev.kindred.core.match.MatchChoice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the plain ways of asking an AI player something in chat, each read as the one ask it is with
 * nothing else let through; a race or duel with nobody named asked back, the answer read once; the operator's switches
 * and anything with coordinates recognised so the player can name the command; every ask's bounds; and an AI player's
 * own yes or no to a match.
 */
class IntentGrammarTest {
    private static Intent ask(String message) {
        var reading = IntentGrammar.read(message).orElseThrow(() -> new AssertionError("Not read: " + message));
        return ((IntentGrammar.Ask) reading).intent();
    }

    @Test
    void theGoalsModesAndTeamsInTheirPlainForms() {
        assertEquals(new Intent(Intent.Kind.GOAL, "nether", List.of()), ask("Go for the Nether!"));
        assertEquals("nether", ask("light a nether portal").arg());
        assertEquals("end", ask("find the End portal").arg());
        assertEquals("dragon", ask("beat the ender dragon").arg());
        assertEquals("none", ask("drop your goal").arg());
        assertEquals(new Intent(Intent.Kind.MODE, "survival", List.of()), ask("play it safe"));
        assertEquals("relaxed", ask("take it easy").arg());
        assertEquals("speedrun", ask("hurry up").arg());
        assertEquals(new Intent(Intent.Kind.TEAM, "together", List.of()), ask("work together"));
        assertEquals("solo", ask("go solo").arg());
    }

    @Test
    void matchesWithTheirPlayersAndOneWithNobodyNamedAskedBack() {
        assertEquals(new Intent(Intent.Kind.RACE, "", List.of("me")), ask("Race me!"));
        assertEquals(List.of("me", "sol"), ask("race me and Sol").names());
        assertEquals(new Intent(Intent.Kind.DUEL, "", List.of("sol")), ask("duel Sol"));
        assertEquals(List.of("me"), ask("spar with me").names());
        var unclear = IntentGrammar.read("let's race");
        assertInstanceOf(IntentGrammar.Unclear.class, unclear.orElseThrow());
        assertEquals("Race whom?", ((IntentGrammar.Unclear) unclear.get()).question());
        assertEquals(new Intent(Intent.Kind.RACE, "", List.of("me")), IntentGrammar.answer(Intent.Kind.RACE, "me").orElseThrow());
        assertEquals(List.of("sol", "echo"), IntentGrammar.answer(Intent.Kind.RACE, "against Sol and Echo").orElseThrow().names());
        assertTrue(IntentGrammar.answer(Intent.Kind.RACE, "what do you mean?").isEmpty(), "Names nobody: dropped");
        assertEquals(Intent.Kind.JOIN, ask("I'm in").kind());
        assertEquals(Intent.Kind.JOIN, ask("count me in").kind());
        assertEquals(Intent.Kind.LEAVE, ask("count me out").kind());
    }

    @Test
    void buildsRanchesAndFieldsHereButNotAtCoordinates() {
        assertEquals(new Intent(Intent.Kind.BUILD, "cabin", List.of()), ask("Build a cabin here"));
        assertEquals("hut", ask("build me a hut").arg());
        assertEquals(new Intent(Intent.Kind.RANCH, "cow", List.of()), ask("make a cow ranch here"));
        assertEquals("sheep", ask("ranch the sheep here").arg());
        assertEquals(Intent.Kind.FARM, ask("plant a field here").kind());
        assertEquals(new Intent(Intent.Kind.PLACE, "build", List.of()), ask("build a cabin at 10, 64, -20"));
        assertTrue(IntentGrammar.read("build a castle here").isEmpty(), "Only the plans it knows");
        assertTrue(IntentGrammar.read("build a cabin over by the lake").isEmpty(), "Somewhere else is not here");
    }

    @Test
    void theRestOfTheAsksAndTheOperatorsSwitches() {
        assertEquals(new Intent(Intent.Kind.SKIN, "knight", List.of()), ask("wear the knight skin"));
        assertEquals(Intent.Kind.COME, ask("come here").kind());
        assertEquals(Intent.Kind.WAIT, ask("hold on").kind());
        assertEquals(Intent.Kind.STOP, ask("stop").kind());
        assertEquals(new Intent(Intent.Kind.TOSS, "bread", List.of()), ask("toss me some bread"));
        assertEquals("cooked_beef", ask("give me some cooked beef").arg());
        assertEquals(Intent.Kind.QUIET, ask("keep it down").kind());
        assertEquals(Intent.Kind.TALK, ask("you can talk more").kind());
        assertEquals(new Intent(Intent.Kind.OPERATOR, "dragonfight", List.of()), ask("allow the dragon fight"));
        assertEquals("edits", ask("turn on world edits").arg());
        assertEquals("create", ask("make a new player called Bob").arg());
        assertEquals("limit", ask("set your limit to 2 hours").arg());
    }

    @Test
    void ordinaryTalkIsNoAsk() {
        for (String said : List.of("hello", "what are you doing?", "nice day", "I'm off to the Nether", "where is Sol?", "thanks", "")) {
            assertEquals(Optional.empty(), IntentGrammar.read(said), said);
        }
    }

    @Test
    void everyAskPassesTheSameCheckWhoeverReadIt() {
        assertTrue(Intent.of("GOAL", "nether", List.of()).isPresent());
        assertTrue(Intent.of("goal", "mars", List.of()).isEmpty(), "Not one of its own");
        assertTrue(Intent.of("TELEPORT", "", List.of()).isEmpty(), "Not a kind");
        assertTrue(Intent.of("RACE", "", List.of()).isEmpty(), "A race names its players");
        assertTrue(Intent.of("RACE", "", List.of("bad name!")).isEmpty());
        assertTrue(Intent.of("RACE", "", java.util.Collections.nCopies(1, "a").stream().toList()).isEmpty(), "Too short a name");
        assertTrue(Intent.of("RACE", "", List.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9")).isEmpty(), "Eight at most");
        assertTrue(Intent.of("GOAL", "nether", List.of("sol")).isEmpty(), "Only a match names players");
        assertTrue(Intent.of("TOSS", "minecraft:bread", List.of()).isPresent());
        assertTrue(Intent.of("TOSS", "bread; /op me", List.of()).isEmpty());
        assertTrue(Intent.of("COME", "now", List.of()).isEmpty(), "No argument");
        assertEquals(Intent.Who.CONTROLLER, Intent.of("GOAL", "end", List.of()).orElseThrow().who());
        assertEquals(Intent.Who.ANYONE, Intent.of("RACE", "", List.of("me")).orElseThrow().who());
        assertEquals(Intent.Who.SELF, Intent.of("JOIN", "", List.of()).orElseThrow().who());
    }

    @Test
    void anAiPlayerSaysYesToAMatchByItsOwnRule() {
        var keen = Personality.defaults().withTrait(Personality.Trait.COMBATIVENESS, 60).withTrait(Personality.Trait.SOCIABILITY, 60);
        var well = new MatchChoice.State(20, 20, false, 5, 5);
        assertTrue(MatchChoice.decide(keen, well, MatchChoice.Kind.RACE).accepts());
        assertFalse(MatchChoice.decide(keen, new MatchChoice.State(9, 20, false, 5, 5), MatchChoice.Kind.RACE).accepts(), "Hurt");
        assertEquals("Not now: I'm hungry.", MatchChoice.decide(keen, new MatchChoice.State(20, 6, false, 5, 5), MatchChoice.Kind.RACE).words());
        assertFalse(MatchChoice.decide(keen, new MatchChoice.State(20, 20, true, 5, 5), MatchChoice.Kind.DUEL).accepts(), "Somewhere it will not leave");
        assertFalse(MatchChoice.decide(keen, new MatchChoice.State(20, 20, false, 2, 20), MatchChoice.Kind.DUEL).accepts(), "Not a fair duel");
        var fighter = keen.withTrait(Personality.Trait.COMBATIVENESS, 90);
        assertTrue(MatchChoice.decide(fighter, new MatchChoice.State(20, 20, false, 2, 20), MatchChoice.Kind.DUEL).accepts(), "Unless it chooses to");
        var shy = Personality.defaults().withTrait(Personality.Trait.SOCIABILITY, 0).withTrait(Personality.Trait.INITIATIVE, 10);
        assertEquals("I'll sit this one out.", MatchChoice.decide(shy, well, MatchChoice.Kind.RACE).words());
    }
}
