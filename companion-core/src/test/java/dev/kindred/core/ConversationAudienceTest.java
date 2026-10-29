package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationAudienceTest {
    private static ConversationAudience.Situation situation(ConversationAudience.Relation relation, boolean controls, boolean stopped) {
        return new ConversationAudience.Situation("Echo", "Steve", relation, controls, 17, 15, "a Zombie",
                "the Overworld", "day", "clear", stopped, true, "gathering logs", "a Nether portal", "Wood: Gather logs for a crafting table",
                "120, 64, -35", "Zombie", List.of("Steve"), List.of("an iron pickaxe", "a stone sword"), 5, List.of("3 diamonds"),
                List.of("Died to a Zombie at 118, 40, -30"), List.of("Caves at night killed me twice"), "I've got the logs.",
                List.of("Steve: where are you?"));
    }

    private static String text(ConversationAudience.Situation s) {
        return String.join("\n", ConversationAudience.lines(s));
    }

    @Test
    void aControllerIsToldEverythingItKnowsItsPositionAndWhatItCarries() {
        String told = text(situation(ConversationAudience.Relation.OWNER, true, false));
        assertTrue(told.contains("Your position: 120, 64, -35"), told);
        assertTrue(told.contains("an iron pickaxe") && told.contains("3 diamonds"), told);
        assertTrue(told.contains("Wood: Gather logs for a crafting table"), told);
        assertTrue(told.contains("118, 40, -30"), "Its events with their places: " + told);
        assertTrue(told.contains("who may give you commands"), told);
        assertTrue(ConversationAudience.mayKnowPosition(situation(ConversationAudience.Relation.ADMIN, true, false)));
    }

    @Test
    void anotherPlayerHearsNoPositionNoInventoryDetailAndNoPlaces() {
        var s = situation(ConversationAudience.Relation.PLAYER, false, false);
        String told = text(s);
        assertFalse(told.contains("120") || told.contains("-35"), "No position: " + told);
        assertFalse(told.contains("iron pickaxe") || told.contains("diamonds"), "No inventory detail: " + told);
        assertTrue(told.contains("2 tools, 5 pieces of food, 1 notable things"), "Counts only: " + told);
        assertTrue(told.contains("Your goal: a Nether portal") && !told.contains("Gather logs for a crafting table"), "The goal without its progress: " + told);
        assertTrue(told.contains("Died to a Zombie at somewhere") && !told.contains("118"), "Events without places: " + told);
        assertFalse(ConversationAudience.givesPlace(told), told);
        assertFalse(ConversationAudience.mayKnowPosition(s));
    }

    @Test
    void anOpponentIsToldOnlyWhatItCouldSeeEvenWhenItCouldCommandThePlayer() {
        for (boolean controls : new boolean[] {false, true}) {
            var s = situation(ConversationAudience.Relation.OPPONENT, controls, false);
            String told = text(s);
            assertEquals(ConversationAudience.Tier.OPPONENT, ConversationAudience.tier(s.relation(), s.speakerControls()));
            assertTrue(told.contains("What you are doing: busy"), told);
            assertFalse(told.contains("gathering logs") || told.contains("Nether portal") || told.contains("Gather logs"), "Neither task nor goal: " + told);
            assertFalse(told.contains("120") || told.contains("pickaxe") || told.contains("tools") || told.contains("Died") || told.contains("Caves"),
                    "No position, inventory, events or memory: " + told);
            assertTrue(told.contains("Your health: 17 of 20") && told.contains("a Zombie"), "What anyone could see: " + told);
            assertFalse(told.contains("who may give you commands") && !told.contains("may not"), told);
            assertFalse(ConversationAudience.mayKnowPosition(s));
        }
    }

    @Test
    void onlyOneWhoMayCommandItMayAskAndNeverAnOpponent() {
        assertTrue(ConversationAudience.mayAsk(situation(ConversationAudience.Relation.OWNER, true, false)));
        assertTrue(ConversationAudience.mayAsk(situation(ConversationAudience.Relation.ADMIN, true, false)));
        assertFalse(ConversationAudience.mayAsk(situation(ConversationAudience.Relation.PLAYER, false, false)));
        assertFalse(ConversationAudience.mayAsk(situation(ConversationAudience.Relation.TEAMMATE, false, false)));
        assertFalse(ConversationAudience.mayAsk(situation(ConversationAudience.Relation.OPPONENT, true, false)), "an opponent never, operator or not");
    }

    @Test
    void aStoppedPlayersSituationSaysSo() {
        String told = text(situation(ConversationAudience.Relation.OWNER, true, true));
        assertTrue(told.contains("You are stopped"), told);
        assertFalse(told.contains("gathering logs"), told);
    }

    @Test
    void placesAreFoundHoweverTheyAreWritten() {
        assertTrue(ConversationAudience.givesPlace("I'm at 120, 64, -35."));
        assertTrue(ConversationAudience.givesPlace("Come to 120 64 -35"));
        assertTrue(ConversationAudience.givesPlace("x: 120 and z=-35"));
        assertTrue(ConversationAudience.givesPlace("X 120, Z -35"));
        assertFalse(ConversationAudience.givesPlace("I've got 3 bread, 2 apples and 5 logs."));
        assertFalse(ConversationAudience.givesPlace("Chopping logs for a table."));
        assertEquals("Died to a Zombie at somewhere", ConversationAudience.withoutPlaces("Died to a Zombie at 118, 40, -30"));
    }
}
