package dev.kindred.core.social;

import dev.kindred.core.CanonicalRoster;
import dev.kindred.core.Personality;
import dev.kindred.core.SocialBrain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the rough stuff (E-18b): how each player types, that a swear word is softened for anyone who did
 * not say yes to it, that dark jokes and flirting reach only people who said yes (strictest of everyone in earshot), that
 * some words are never said whatever anyone asked for, and which starters sound rough or flirt by their personality.
 */
class RoughTalkTest {
    private static final UUID SOL = UUID.randomUUID();

    @Test
    void eachStyleTypesItsOwnWayAndNeverChangesNamesNumbersOrIds() {
        String line = "I'm going to the cave at -120, 64. Do you want to come? It's got minecraft:iron_ore, Steve.";
        assertEquals(line, ChatStyle.PROPER.apply(line, SOL, List.of("Steve"), 1));
        String casual = ChatStyle.CASUAL.apply(line, SOL, List.of("Steve"), 1);
        assertEquals("im going to the cave at -120, 64. do u wanna come? its got minecraft:iron_ore, Steve", casual);
        String gamer = ChatStyle.GAMER.apply(line, SOL, List.of("Steve"), 1);
        assertEquals("im going to the cave at -120 64 do u wanna come its got minecraft:iron_ore Steve", gamer);
        assertFalse(gamer.contains("."), gamer);
        assertFalse(gamer.contains("?"), "No punctuation: " + gamer);
        assertTrue(gamer.contains("Steve"), "A name typed as it is read: " + gamer);
        for (String fact : ChatStyle.facts(line)) assertTrue(gamer.contains(fact), fact + " kept in " + gamer);
        assertEquals(gamer, ChatStyle.GAMER.apply(line, SOL, List.of("Steve"), 1), "The same line types the same way");
        assertEquals("ur right", ChatStyle.GAMER.apply("You are right.", SOL, List.of(), 1));
        assertEquals("ur right " + ChatStyle.signature(SOL), ChatStyle.GAMER.apply("You are right.", SOL, List.of(), 5), "Its own word, now and then");
        assertEquals("sols pickaxe", ChatStyle.GAMER.apply("Sol's pickaxe.", SOL, List.of(), 1));
    }

    @Test
    void starterStylesAndPresetsComeFromTheirPersonalities() {
        assertEquals(ChatStyle.CASUAL, ChatStyle.forPlayer(CanonicalRoster.KINDRED.defaultPersonality()));
        assertEquals(ChatStyle.GAMER, ChatStyle.forPlayer(CanonicalRoster.SOL.defaultPersonality()));
        assertEquals(ChatStyle.PROPER, ChatStyle.forPlayer(CanonicalRoster.ECHO.defaultPersonality()));
        assertEquals(ChatStyle.PROPER, ChatStyle.forPlayer(Personality.defaults()), "An even personality types properly");
        assertEquals(Expression.ROUGH, Expression.byPersonality(CanonicalRoster.SOL.defaultPersonality()), "Funny and combative: rough");
        assertEquals(Expression.PLAIN, Expression.byPersonality(CanonicalRoster.ECHO.defaultPersonality()));
        assertTrue(Expression.flirty(CanonicalRoster.KINDRED.defaultPersonality()), "Sociable with some humour: flirts");
        assertFalse(Expression.flirty(CanonicalRoster.ECHO.defaultPersonality()));
        assertEquals(Expression.PLAYFUL, Expression.ROUGH.capped(Expression.PLAYFUL), "The server's cap bounds rough too");
        assertEquals(Expression.ROUGH, Expression.parse("Rough").orElseThrow());
    }

    @Test
    void swearingIsSoftenedAndSomeWordsAreNeverSaid() {
        assertTrue(Decency.profane("Ow, shit. That hurt."));
        assertEquals("Ow, shoot. That hurt.", Decency.soften("Ow, shit. That hurt."));
        assertEquals("Well dang, Sol lives.", Decency.soften("Well damn, Sol lives."));
        assertEquals("WTH", Decency.soften("WTF"));
        assertEquals("Hello, shell, classic, pass.", Decency.soften("Hello, shell, classic, pass."), "Words inside words are left alone");
        assertTrue(Decency.blocked("you're such a retard"));
        assertTrue(Decency.blocked("kys"));
        assertFalse(Decency.blocked("I'm naked, a zombie took my armor."), "No armour is not a sexual word");
        assertFalse(Decency.blocked("Ow, shit."), "A swear word is not a blocked one");
    }

    @Test
    void theRoughStuffReachesOnlyPeopleWhoSaidYesAndTheStrictestInEarshotWins() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var nothingSaid = ListenerPreferences.defaults(a);
        assertFalse(nothingSaid.resolved(false).swears(), "Clean by default");
        assertTrue(nothingSaid.resolved(true).swears(), "Unless the operator said yes for everyone");
        assertFalse(nothingSaid.resolved(true).flirts(), "Flirting is never the server's to say");
        var yes = ListenerPreferences.defaults(a).withSwearing(ListenerPreferences.Opt.ON).withDarkHumor(ListenerPreferences.Opt.ON)
                .withFlirting(ListenerPreferences.Opt.ON);
        assertTrue(yes.resolved(false).swears() && yes.resolved(false).dark() && yes.resolved(false).flirts());
        assertFalse(yes.withNoJokes(true).resolved(false).dark(), "No jokes means no dark jokes or flirting either");
        var no = ListenerPreferences.defaults(b).withSwearing(ListenerPreferences.Opt.OFF);
        assertFalse(no.resolved(true).swears(), "A person's no beats the server's yes");
        var both = ListenerPreferences.strictest(List.of(yes.resolved(false), no.resolved(true)));
        assertFalse(both.swears(), "Anyone in earshot who said no");
        assertTrue(both.dark(), "Both said yes to dark jokes (one by the server's default)");

        String dark = SocialBrain.Speech.wordsFor(SocialBrain.Tone.DARK, "That zombie almost sent me back to spawn lol.", "Ouch, a zombie got me.", no.resolved(false));
        assertEquals("Ouch, a zombie got me.", dark, "A dark joke is said plainly to whoever did not say yes");
        assertEquals("Ow, wtf.", SocialBrain.Speech.wordsFor(SocialBrain.Tone.ROUGH, "Ow, wtf.", "Ouch.", no.resolved(false)),
                "A rough line goes out; its swear words are softened at delivery");
        assertEquals("Hi, Echo.", SocialBrain.Speech.wordsFor(SocialBrain.Tone.FLIRT, "Looking good, Echo.", "Hi, Echo.", nothingSaid.resolved(true)));
        assertEquals("Looking good, Echo.", SocialBrain.Speech.wordsFor(SocialBrain.Tone.FLIRT, "Looking good, Echo.", "Hi, Echo.", yes.resolved(false)));
    }

    @Test
    void theVoiceIsToldWhatThisListenerSaidYesToAndHowThePlayerTypes() {
        UUID a = UUID.randomUUID();
        var clean = ListenerPreferences.defaults(a).resolved(false);
        assertEquals("How you speak to them: plainly, without jokes; clean words only.", Expression.voiceLine(Expression.PLAIN, clean));
        String rough = Expression.voiceLine(Expression.ROUGH, clean, true, ChatStyle.GAMER);
        assertTrue(rough.contains("clean words only"), rough);
        assertFalse(rough.contains("flirting is fine"), rough);
        assertTrue(rough.contains("no punctuation"), rough);
        var yes = ListenerPreferences.defaults(a).withSwearing(ListenerPreferences.Opt.ON).withFlirting(ListenerPreferences.Opt.ON).resolved(false);
        String said = Expression.voiceLine(Expression.ROUGH, yes, true, ChatStyle.PROPER);
        assertTrue(said.contains("casual swearing is fine"), said);
        assertTrue(said.contains("light, harmless flirting is fine, never sexual"), said);
        assertTrue(said.contains("never a slur"), said);
        assertFalse(said.contains("Type like"), "A proper typist is not told to type otherwise");
    }

    @Test
    void aFlirtyPlayerFlirtsWithAnotherAiPlayerWhenThereIsNoNews() {
        UUID kindred = UUID.randomUUID(), echo = UUID.randomUUID();
        var a = new Conversation.Side(kindred, "Kindred", 80, Expression.PLAYFUL, "", List.of(), List.of(), "", "", "", true);
        var b = new Conversation.Side(echo, "Echo", 55, Expression.PLAIN, "", List.of(), List.of(), "", "", "", false);
        var plan = Conversation.plan(a, b, new java.util.HashMap<>(), 0).orElseThrow();
        assertEquals(Conversation.Topic.Kind.FLIRT, plan.topic().kind());
        var first = plan.turns().getFirst();
        assertEquals(SocialBrain.Tone.FLIRT, first.tone());
        assertEquals("Hi, Echo.", first.textFor(ListenerPreferences.defaults(null).resolved(true)), "Plainly to whoever did not say yes");
        var rough = new Conversation.Side(kindred, "Sol", 65, Expression.ROUGH, "", List.of(), List.of(), "", "", "", false);
        var hello = Conversation.plan(rough, b, new java.util.HashMap<>(), 0).orElseThrow();
        assertEquals(SocialBrain.Tone.ROUGH, hello.turns().getFirst().tone());
        assertTrue(Decency.profane(hello.turns().getFirst().text()), hello.turns().getFirst().text());
    }
}
