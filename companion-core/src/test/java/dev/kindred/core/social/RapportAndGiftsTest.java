package dev.kindred.core.social;

import dev.kindred.core.gift.GiftPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks what an AI player may give (half of its unpromised finds, never its tools, armour or bucket) and
 * what it offers for a need someone said they have; and that its friendship record counts each thing that happened once,
 * strikes out what was wrong and works itself out again, survives being saved, and never counts chat.
 */
class RapportAndGiftsTest {
    private static final UUID ME = UUID.randomUUID(), STEVE = UUID.randomUUID(), SOL = UUID.randomUUID();

    @Test
    void giftsAreHalfOfWhatIsSpareAndNeverItsTools() {
        var spare = GiftPolicy.spare(List.of("8 iron ingot", "1 diamond", "2 iron pickaxe", "6 torch", "40 cobblestone", "nonsense", "4 water bucket"));
        assertEquals(List.of(new GiftPolicy.Spare("iron ingot", 4), new GiftPolicy.Spare("torch", 3), new GiftPolicy.Spare("cobblestone", 16)), spare,
                "Half, at most sixteen; one diamond is not half of anything; tools and buckets never");
        var iron = GiftPolicy.offerFor("iron", spare).orElseThrow();
        assertEquals("iron_ingot", iron.item());
        assertEquals(4, iron.count());
        assertEquals("torch", GiftPolicy.offerFor("some torches", spare).orElseThrow().item(), "Plurals and 'some'");
        assertTrue(GiftPolicy.offerFor("diamonds", spare).isEmpty(), "Nothing spare answers it");
        assertTrue(GiftPolicy.offerFor("", spare).isEmpty());
    }

    @Test
    void eachThingCountsOnceAndAStrikeOutChangesTheStandingAtOnce() {
        var rapport = new Rapport(ME);
        assertTrue(rapport.note(STEVE, "Steve", Rapport.Kind.RECEIVED, "4 bread", "gift:1", "game", 100).isPresent());
        assertTrue(rapport.note(STEVE, "Steve", Rapport.Kind.RECEIVED, "4 bread", "gift:1", "game", 200).isEmpty(), "The same gift again is nothing new");
        rapport.note(STEVE, "Steve", Rapport.Kind.GAVE, "3 iron ingot", "gift:2", "game", 300);
        assertEquals(9, rapport.standing(STEVE).friendship());
        assertEquals("friendly", rapport.standing(STEVE).band());
        rapport.note(STEVE, "Steve", Rapport.Kind.HARMED, "hit it outside any match", "harm:1", "game", 400);
        assertEquals(-3, rapport.standing(STEVE).friendship());
        assertTrue(rapport.correct(STEVE, "harm:1", STEVE, 500), "Struck out: it was a mistake");
        assertEquals(9, rapport.standing(STEVE).friendship(), "Worked out again from what is left");
        var struck = rapport.events(STEVE).stream().filter(Rapport.Event::corrected).findFirst().orElseThrow();
        assertEquals(STEVE, struck.correctedBy());
        assertEquals("harm:1", struck.key(), "Kept, with who struck it out");
        assertFalse(rapport.correct(STEVE, "harm:1", STEVE, 600), "Not twice");
        assertTrue(rapport.note(ME, "me", Rapport.Kind.GAVE, "", "self", "game", 1).isEmpty(), "Never about itself");
    }

    @Test
    void boundedSavedAndForgotten() {
        var rapport = new Rapport(ME);
        for (int i = 0; i < Rapport.MAX_EVENTS + 5; i++) rapport.note(SOL, "Sol", Rapport.Kind.GAVE, "", "g" + i, "game", i);
        assertEquals(Rapport.MAX_EVENTS, rapport.events(SOL).size(), "The newest fifty");
        assertEquals(Rapport.HIGHEST, rapport.standing(SOL).friendship(), "Bounded");
        for (int i = 0; i < Rapport.MAX_PEERS + 3; i++) rapport.note(UUID.randomUUID(), "p" + i, Rapport.Kind.GAVE, "", "x", "game", 1_000 + i);
        assertTrue(rapport.standings().size() <= Rapport.MAX_PEERS);
        rapport.note(STEVE, "Steve", Rapport.Kind.OUTING, "to the lake", "outing:1", "game", 5_000);
        var back = Rapport.restore(ME, rapport.snapshot());
        assertEquals(rapport.standing(STEVE), back.standing(STEVE));
        assertEquals(rapport.events(STEVE), back.events(STEVE));
        assertTrue(back.forget(STEVE));
        assertEquals(0, back.standing(STEVE).counted(), "Forgotten");
    }
}
