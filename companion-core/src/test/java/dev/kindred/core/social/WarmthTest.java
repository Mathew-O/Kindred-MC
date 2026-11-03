package dev.kindred.core.social;

import dev.kindred.core.Personality;
import dev.kindred.core.outing.OutingChoice;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks what friendship changes in how a player is with someone (E-25a): a friend gets a warmer hello, a
 * readier yes to an outing and what it has spare; someone it is cool with gets no talk started, a word for an answer and
 * no yes; and with neither, everything is as it was before.
 */
class WarmthTest {
    private static final UUID SOL = UUID.randomUUID(), ECHO = UUID.randomUUID();

    private static Conversation.Side sol(Warmth warmth, Expression sounds) {
        return new Conversation.Side(SOL, "Sol", 60, sounds, "", List.of(), List.of("4 iron ingot"), "", "", "", false, warmth);
    }

    private static Conversation.Side echo(Warmth warmth, String doing, List<String> needs) {
        return new Conversation.Side(ECHO, "Echo", 50, Expression.PLAIN, doing, needs, List.of(), "", "", "", false, warmth);
    }

    @Test
    void theBandsOfFriendship() {
        assertEquals(Warmth.WARM, Warmth.of(30));
        assertEquals(Warmth.KIND, Warmth.of(8));
        assertEquals(Warmth.PLAIN, Warmth.of(0));
        assertEquals(Warmth.PLAIN, Warmth.of(7));
        assertEquals(Warmth.COOL, Warmth.of(-1));
        assertEquals(Warmth.CURT, Warmth.of(-24));
        assertEquals(Warmth.PLAIN, Warmth.of((Rapport.Standing) null));
        for (int f = -100; f <= 100; f++) {
            var band = new Rapport.Standing(ECHO, "Echo", f, 1, 0).band();
            assertEquals(switch (band) {
                case "friend" -> Warmth.WARM;
                case "friendly" -> Warmth.KIND;
                case "cool" -> Warmth.COOL;
                case "wary" -> Warmth.CURT;
                default -> Warmth.PLAIN;
            }, Warmth.of(f), "the same bands as the friendship record at " + f);
        }
    }

    @Test
    void aFriendsHelloIsWarmerAndAnsweredWarmly() {
        var plain = Conversation.plan(sol(Warmth.PLAIN, Expression.PLAIN), echo(Warmth.PLAIN, "", List.of()), new HashMap<>(), 0).orElseThrow();
        assertEquals(Conversation.Topic.Kind.HELLO, plain.topic().kind());
        assertEquals("Hi, Echo. Good to see you.", plain.turns().get(0).text(), "as before");
        assertEquals("Hi, Sol.", plain.turns().get(1).text(), "as before");
        var friends = Conversation.plan(sol(Warmth.WARM, Expression.PLAIN), echo(Warmth.WARM, "", List.of()), new HashMap<>(), 0).orElseThrow();
        assertEquals("Hi, Echo! Always good to see you.", friends.turns().get(0).text());
        assertEquals("Hi, Sol! Good to see you too.", friends.turns().get(1).text());
        var rough = Conversation.plan(sol(Warmth.WARM, Expression.ROUGH), echo(Warmth.KIND, "", List.of()), new HashMap<>(), 0).orElseThrow();
        assertEquals("Hey, Echo! There's my favourite person.", rough.turns().get(0).text(), "a friend, whatever its manner");
        assertEquals("Hi, Sol. You too.", rough.turns().get(1).text());
    }

    @Test
    void coolWithSomeoneItStartsNoTalkAndAnswersInAWord() {
        assertTrue(Conversation.plan(sol(Warmth.COOL, Expression.PLAIN), echo(Warmth.PLAIN, "mining", List.of("iron ingot")), new HashMap<>(), 0).isEmpty(),
                "no talk started with one it is cool with");
        assertTrue(Conversation.plan(sol(Warmth.CURT, Expression.PLAYFUL), echo(Warmth.PLAIN, "", List.of()), new HashMap<>(), 0).isEmpty());
        // Asked by one it is cool with, it says a word: what it is doing and needs it keeps to itself.
        var asked = Conversation.plan(sol(Warmth.PLAIN, Expression.PLAIN), echo(Warmth.COOL, "working on mining: iron", List.of("3 iron ingot")), new HashMap<>(), 0)
                .orElseThrow();
        assertEquals("Busy.", asked.turns().get(1).text());
        assertTrue(asked.turns().stream().noneMatch(t -> t.speaker().equals(ECHO) && t.text().contains("iron")), "nothing of its own told: " + asked.turns());
        var hello = Conversation.plan(sol(Warmth.KIND, Expression.PLAIN), echo(Warmth.CURT, "", List.of()), new HashMap<>(), 0).orElseThrow();
        assertEquals("Hm.", hello.turns().get(1).text());
    }

    @Test
    void aFriendIsOfferedWhatItHasSpareAndAWaryOneIsNot() {
        var needs = echo(Warmth.PLAIN, "", List.of("3 iron ingot"));
        var friend = Conversation.plan(sol(Warmth.WARM, Expression.PLAIN), needs, new HashMap<>(), 0).orElseThrow();
        assertEquals("I've got some iron ingot spare. It's yours if you want it.", friend.turns().getLast().text());
        var plain = Conversation.plan(sol(Warmth.PLAIN, Expression.PLAIN), needs, new HashMap<>(), 0).orElseThrow();
        assertEquals("I've got some iron ingot spare if you want it.", plain.turns().getLast().text(), "as before");
        // With nothing of it spare, a friend keeps an eye out, whatever its manner.
        var none = new Conversation.Side(SOL, "Sol", 60, Expression.DRY, "", List.of(), List.of(), "", "", "", false, Warmth.WARM);
        assertEquals("I'll keep an eye out for iron ingot for you.", Conversation.plan(none, needs, new HashMap<>(), 0).orElseThrow().turns().getLast().text());
        assertFalse(Warmth.CURT.sharesSpare());
        assertTrue(Warmth.COOL.sharesSpare());
    }

    @Test
    void aFriendsOutingIsJoinedReadierAndOneItIsCoolWithNever() {
        var quiet = new Personality(50, 50, 50, 25, 50, 50, 50, 50, 50, 0); // Sociability 25.
        assertFalse(OutingChoice.answer(false, false, false, false, quiet).yes(), "as before: 25 is not sociable enough");
        assertFalse(OutingChoice.answer(false, false, false, false, quiet, Warmth.PLAIN).yes());
        assertFalse(OutingChoice.answer(false, false, false, false, quiet, Warmth.KIND).yes(), "a friendly one's at 30");
        var friend = OutingChoice.answer(false, false, false, false, quiet, Warmth.WARM);
        assertTrue(friend.yes(), friend.why());
        assertEquals("with a friend", friend.why());
        var social = new Personality(50, 50, 50, 90, 50, 50, 50, 50, 50, 0); // Sociability 90.
        var cool = OutingChoice.answer(false, false, false, false, social, Warmth.COOL);
        assertFalse(cool.yes());
        assertEquals("would rather not go with them", cool.why());
        // Its own goal, work, danger and matches come first, whoever asks.
        assertFalse(OutingChoice.answer(true, false, false, false, social, Warmth.WARM).yes());
        assertFalse(OutingChoice.answer(false, true, false, false, social, Warmth.WARM).yes());
        assertFalse(OutingChoice.answer(false, false, true, false, social, Warmth.WARM).yes());
        assertFalse(OutingChoice.answer(false, false, false, true, social, Warmth.WARM).yes());
    }
}
