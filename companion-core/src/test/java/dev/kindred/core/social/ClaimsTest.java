package dev.kindred.core.social;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks which plain things said to an AI player it notes as a person's claim about themselves, which take
 * a claim back, and which ask what it knows of someone; anything else leaves no note.
 */
class ClaimsTest {
    @Test
    void firstPersonClaimsAreNoted() {
        var going = Claims.claim("I'm off to the Nether!").orElseThrow();
        assertEquals(PeerLedger.Topic.DOING, going.topic());
        assertEquals("going to the nether", going.text());
        assertEquals("minecraft:the_nether", going.dimension());
        assertEquals("minecraft:the_end", Claims.claim("im heading to the end").orElseThrow().dimension());
        assertNull(Claims.claim("I'm going to the village").orElseThrow().dimension());
        assertEquals("going mining", Claims.claim("I'm going mining for a bit").orElseThrow().text());
        var need = Claims.claim("I need some iron.").orElseThrow();
        assertEquals(PeerLedger.Topic.NEEDS, need.topic());
        assertEquals("iron", need.text());
        assertEquals("wheat", Claims.claim("I've got spare wheat").orElseThrow().text());
        assertEquals(PeerLedger.Topic.SPARE, Claims.claim("I can spare some coal").orElseThrow().topic());
    }

    @Test
    void ordinaryTalkAndOthersAreNoClaim() {
        assertTrue(Claims.claim("hello").isEmpty());
        assertTrue(Claims.claim("Sol needs iron").isEmpty(), "Only about oneself");
        assertTrue(Claims.claim("what are you doing").isEmpty());
        assertTrue(Claims.claim("you need iron").isEmpty());
        assertTrue(Claims.claim(null).isEmpty());
    }

    @Test
    void takingItBack() {
        assertEquals(PeerLedger.Correction.FORGOTTEN, Claims.taken("Forget that.").orElseThrow().how());
        assertNull(Claims.taken("forget it").orElseThrow().about());
        assertEquals("nether", Claims.taken("forget what I said about the Nether").orElseThrow().about());
        assertEquals(PeerLedger.Correction.WRONG, Claims.taken("That's wrong").orElseThrow().how());
        assertEquals(PeerLedger.Correction.WRONG, Claims.taken("that’s not true").orElseThrow().how());
        assertTrue(Claims.taken("forget about the creeper earlier, it was funny").isEmpty());
    }

    @Test
    void questionsAboutSomeone() {
        assertEquals(new Claims.Question(Claims.Ask.NEEDS, "sol"), Claims.question("What does Sol need?").orElseThrow());
        assertEquals(new Claims.Question(Claims.Ask.DOING, "sol"), Claims.question("what's Sol doing").orElseThrow());
        assertEquals(new Claims.Question(Claims.Ask.DOING, "kindred"), Claims.question("What is Kindred up to?").orElseThrow());
        assertEquals(new Claims.Question(Claims.Ask.WHERE, "steve"), Claims.question("where is Steve").orElseThrow());
        assertEquals(new Claims.Question(Claims.Ask.WHERE, "steve"), Claims.question("have you seen steve?").orElseThrow());
        assertTrue(Claims.question("what are you doing").isEmpty());
    }
}
