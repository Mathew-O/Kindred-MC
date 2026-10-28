package dev.kindred.core.team;

import dev.kindred.core.team.Missing.Audience;
import dev.kindred.core.team.Missing.Mode;
import dev.kindred.core.team.Missing.Seen;
import dev.kindred.core.team.Storage.Place;
import dev.kindred.core.team.Storage.Whose;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    private static final Place HOME = new Place("minecraft:overworld", 12, 64, -30);
    private static final Place SHED = new Place("minecraft:overworld", 20, 64, -30);

    @Test
    void whatItStoredCountsWithWhatItHoldsAndChangesAsItPutsInAndTakesOut() {
        var storage = new Storage();
        var first = storage.looked(HOME, Whose.TEAM, Map.of("minecraft:iron_ingot", 12, "minecraft:bread", 5), 100);
        assertFalse(first.isShort(), "A first look only notes what is there");
        storage.put(HOME, Whose.TEAM, "minecraft:diamond", 3, 200);
        storage.looked(SHED, Whose.OWN, Map.of("minecraft:iron_ingot", 4), 250);
        assertEquals(16, storage.stored("minecraft:iron_ingot"));
        var extended = storage.extended(Map.of("minecraft:iron_ingot", 2, "minecraft:stick", 8));
        assertEquals(18, extended.get("minecraft:iron_ingot"), "Held and stored together");
        assertEquals(3, extended.get("minecraft:diamond"));
        assertEquals(8, extended.get("minecraft:stick"));
        storage.took(HOME, "minecraft:iron_ingot", 5, 300);
        assertEquals(7, storage.store(HOME).orElseThrow().count("minecraft:iron_ingot"));
        assertEquals(HOME, storage.holding("minecraft:iron_ingot").getFirst().place(), "The fullest first");
        assertEquals(Optional.of(50L), storage.age(HOME, 350));
        assertTrue(storage.age(new Place("minecraft:overworld", 0, 0, 0), 350).isEmpty());
    }

    @Test
    void aLookFindsWhatIsGoneAndWhatMoreIsThereAndCountsWhatItSees() {
        var storage = new Storage();
        storage.looked(HOME, Whose.TEAM, Map.of("minecraft:iron_ingot", 12, "minecraft:diamond", 2), 100);
        var look = storage.looked(HOME, Whose.TEAM, Map.of("minecraft:iron_ingot", 7, "minecraft:bread", 4), 200);
        assertTrue(look.isShort());
        assertEquals(Map.of("minecraft:iron_ingot", 5, "minecraft:diamond", 2), look.gone());
        assertEquals(Map.of("minecraft:bread", 4), look.more());
        assertEquals(Map.of("minecraft:iron_ingot", 7, "minecraft:bread", 4), storage.store(HOME).orElseThrow().counts());
        // What a teammate said it took is no loss when it is looked for again.
        storage.took(HOME, "minecraft:bread", 4, 250);
        assertFalse(storage.looked(HOME, Whose.TEAM, Map.of("minecraft:iron_ingot", 7), 300).isShort());
    }

    @Test
    void inCoOpItAsksItsTeamBeforeReckoningAnythingLostAndBlamesNobody() {
        var storage = new Storage();
        storage.looked(HOME, Whose.TEAM, Map.of("minecraft:iron_ingot", 12), 100);
        var look = storage.looked(HOME, Whose.TEAM, Map.of("minecraft:iron_ingot", 7), 200);
        var ask = Missing.respond(Mode.TEAM, look, Optional.empty(), 4);
        assertEquals("Did anyone take 5 iron ingots from our chest at 12, 64, -30?", ask.say());
        assertEquals(Audience.TEAM, ask.to());
        assertTrue(ask.ask());
        assertFalse(ask.moveRest());
        assertEquals("Nobody took them, so 5 iron ingots are gone from our chest at 12, 64, -30.", Missing.unanswered(look).say());
        // A teammate seen at it: only noted, so every count agrees.
        var noted = Missing.respond(Mode.TEAM, look, Optional.of(new Seen("Wren", true)), 4);
        assertEquals("Wren took 5 iron ingots from our chest at 12, 64, -30.", noted.say());
        assertFalse(noted.ask());
    }

    @Test
    void inAMatchItReckonsItsStorageRaidedMovesWhatIsLeftAndNamesOnlyWhoItSawToItsOwnTeam() {
        var storage = new Storage();
        storage.looked(HOME, Whose.TEAM, Map.of("minecraft:diamond", 4, "minecraft:gold_ingot", 10), 100);
        var look = storage.looked(HOME, Whose.TEAM, Map.of("minecraft:gold_ingot", 10), 200);
        var unseen = Missing.respond(Mode.MATCH, look, Optional.empty(), 6);
        assertEquals("Someone has been in our chest at 12, 64, -30: 4 diamonds gone. I'm moving the rest.", unseen.say());
        assertEquals(Audience.TEAM, unseen.to(), "Its own team only");
        assertTrue(unseen.moveRest());
        assertFalse(unseen.say().contains("Steve"), "Nobody named it did not see");
        var seen = Missing.respond(Mode.MATCH, look, Optional.of(new Seen("Steve", false)), 6);
        assertEquals("I saw Steve take 4 diamonds from our chest at 12, 64, -30. I'm moving the rest.", seen.say());
        assertEquals(Audience.TEAM, seen.to());
        // Little left: not worth moving.
        assertFalse(Missing.respond(Mode.MATCH, look, Optional.empty(), 1).moveRest());
    }

    @Test
    void aloneItReckonsTheItemsLostAndTellsThoseItPlaysFor() {
        var storage = new Storage();
        storage.looked(SHED, Whose.OWN, Map.of("minecraft:diamond", 1, "minecraft:bread", 6), 100);
        var look = storage.looked(SHED, Whose.OWN, Map.of("minecraft:bread", 6), 200);
        var response = Missing.respond(Mode.ALONE, look, Optional.empty(), 1);
        assertEquals("A diamond is gone from my chest at 20, 64, -30.", response.say());
        assertEquals(Audience.OWN, response.to());
        assertFalse(response.ask());
        assertSame(Audience.NOBODY, Missing.respond(Mode.ALONE, storage.looked(SHED, Whose.OWN, Map.of("minecraft:bread", 6), 300),
                Optional.empty(), 1).to(), "Nothing gone: nothing said");
    }

    @Test
    void whatItPutsInOrTakesOutOfTheTeamsStorageItTellsTheTeam() {
        assertEquals("I put 12 iron ingots in our chest at 12, 64, -30.", TeamTalk.stored(true, HOME, Map.of("minecraft:iron_ingot", 12)));
        assertEquals("I took 3 bread and a bucket from our chest at 12, 64, -30.",
                TeamTalk.stored(false, HOME, Map.of("minecraft:bucket", 1, "minecraft:bread", 3)));
    }

    @Test
    void aTeammateOwnsUpInPlainWordsAndItsWordIsTaken() {
        for (String said : List.of("me", "Me!", "I did.", "that was me", "I took them", "i took the name tag, sorry", "I've got it"))
            assertTrue(Missing.ownsUp(said), said);
        for (String said : List.of("not me", "I did not", "me neither", "who knows", "", "I didn't take it"))
            assertFalse(Missing.ownsUp(said), said);
        assertFalse(Missing.ownsUp(null));
        assertEquals("Thanks, Steve. I've counted them out.", Missing.thanked("Steve"));
    }
}
