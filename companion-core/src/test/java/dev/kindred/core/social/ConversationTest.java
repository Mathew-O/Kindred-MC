package dev.kindred.core.social;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks what two AI players say when they meet: the topic is what is new to the one asking, the answer
 * comes from the other's own record whatever was asked, nothing is said twice within half an hour, a tease goes out
 * plainly to anyone who asked for none, and the server's AI players stay within a few lines a minute and a pair within
 * one talk in two minutes.
 */
class ConversationTest {
    private static final UUID SOL = UUID.randomUUID(), ECHO = UUID.randomUUID();

    private static Conversation.Side sol(Expression sounds, String knownDoing, String episode) {
        return new Conversation.Side(SOL, "Sol", 60, sounds, "working on find the end portal: Nether", List.of(), List.of("4 iron ingot"),
                knownDoing, "", episode);
    }

    private static Conversation.Side echo(String doing, List<String> needs) {
        return new Conversation.Side(ECHO, "Echo", 50, Expression.PLAIN, doing, needs, List.of(), "", "", "");
    }

    @Test
    void theTopicIsWhatIsNewToTheOneAskingAndTheAnswerComesFromTheOthersRecord() {
        var b = echo("working on build a nether portal: Wood: gather logs", List.of("3 iron ingot"));
        var plan = Conversation.plan(sol(Expression.PLAIN, "mining", ""), b, new HashMap<>(), 0).orElseThrow();
        assertEquals(Conversation.Topic.Kind.DOING, plan.topic().kind(), "Its record of Echo is out of date");
        assertEquals(3, plan.turns().size());
        var asked = plan.turns().get(0);
        assertEquals(SOL, asked.speaker());
        assertEquals(ECHO, asked.listener());
        assertEquals("How's it going, Echo? Last I heard you were mining.", asked.text());
        var answer = plan.turns().get(1);
        assertEquals(ECHO, answer.speaker());
        assertEquals("I'm working on build a nether portal. I could use 3 iron ingot.", answer.text());
        assertEquals(Conversation.answerDoing(b), answer.text(), "From Echo's own record, not Sol's words");
        assertEquals("I've got some iron ingot spare if you want it.", plan.turns().get(2).text(), "Sol has iron spare");
        // Whatever Sol asked, Echo's answer is the same: it is written from Echo's record alone.
        var other = Conversation.plan(sol(Expression.PLAYFUL, "", ""), b, new HashMap<>(), 0).orElseThrow();
        assertEquals(answer.text(), other.turns().get(1).text());
    }

    @Test
    void nothingSaidTwiceWithinHalfAnHour() {
        var spoken = new HashMap<String, Long>();
        var b = echo("idle", List.of());
        var first = Conversation.plan(sol(Expression.PLAIN, "", ""), b, spoken, 0).orElseThrow();
        Conversation.spoken(first, spoken, 0);
        var second = Conversation.plan(sol(Expression.PLAIN, "", ""), b, spoken, 60_000).orElseThrow();
        assertNotEquals(first.topic().key(), second.topic().key(), "The next topic, not the same again");
        Conversation.spoken(second, spoken, 60_000);
        assertTrue(Conversation.plan(sol(Expression.PLAIN, "", ""), b, spoken, 120_000).isEmpty(), "Nothing new left to say");
        assertTrue(Conversation.plan(sol(Expression.PLAIN, "", ""), b, spoken, Conversation.DEDUPE_MILLIS).isPresent(), "Half an hour on");
    }

    @Test
    void aPlayfulPlayerTeasesWithWhatTheyWentThroughAndPlainlyToWhoAskedForNone() {
        var plan = Conversation.plan(sol(Expression.PLAYFUL, "", "the pit last night"), echo("idle", List.of()), new HashMap<>(), 0).orElseThrow();
        assertEquals(Conversation.Topic.Kind.EPISODE, plan.topic().kind());
        var tease = plan.turns().getFirst();
        assertTrue(tease.tease());
        assertEquals("Hi, Echo. After the pit last night, I'm keeping an eye on you.", tease.text());
        assertEquals("Hi, Echo.", tease.textFor(ListenerPreferences.defaults(UUID.randomUUID()).withNoTeasing(true)));
        assertEquals(tease.text(), tease.textFor(ListenerPreferences.defaults(UUID.randomUUID())));
        var plain = Conversation.plan(sol(Expression.PLAIN, "", "the pit last night"), echo("idle", List.of()), new HashMap<>(), 0).orElseThrow();
        assertNotEquals(Conversation.Topic.Kind.EPISODE, plain.topic().kind(), "Only a playful one teases");
    }

    @Test
    void theServersBudgetAndThePairs() {
        var chatter = new Conversation.Chatter(4);
        assertTrue(chatter.may(SOL, ECHO, 3, 0));
        chatter.began(SOL, ECHO, 0);
        for (int i = 0; i < 3; i++) chatter.line(i * 1_000);
        assertFalse(chatter.may(SOL, ECHO, 1, 10_000), "The pair, within two minutes");
        UUID kin = UUID.randomUUID();
        assertTrue(chatter.may(SOL, kin, 1, 10_000), "Another pair, one line left in the minute");
        assertFalse(chatter.may(SOL, kin, 2, 10_000), "Not two");
        assertTrue(chatter.mayLine(10_000));
        chatter.line(10_000);
        assertFalse(chatter.mayLine(20_000), "Four in the minute");
        assertTrue(chatter.mayLine(60_000), "The minute over");
        assertTrue(chatter.may(SOL, ECHO, 3, Conversation.Chatter.PAIR_MILLIS + 11_000));
        assertEquals(0, Conversation.Chatter.of("quiet").perMinute());
        assertFalse(Conversation.Chatter.of("quiet").may(SOL, ECHO, 1, 0));
        assertEquals(4, Conversation.Chatter.of(null).perMinute());
        assertEquals(10, Conversation.Chatter.of("10").perMinute());
        assertEquals(4, Conversation.Chatter.of("lots").perMinute(), "Unreadable: the default");
    }
}
