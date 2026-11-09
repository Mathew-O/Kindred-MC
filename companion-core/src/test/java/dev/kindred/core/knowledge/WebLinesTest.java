package dev.kindred.core.knowledge;

import dev.kindred.core.ConversationAudience;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Listener;
import dev.kindred.core.wire.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the voice's prompt built from what a player knows: an opponent's carries nothing beyond what
 * is public, a teammate's what their side shares, and nobody's a fact meant for others.
 */
class WebLinesTest {
    private static ConversationAudience.Situation situation(ConversationAudience.Relation relation, List<dev.kindred.core.social.PeerLedger.Told> peers) {
        return new ConversationAudience.Situation("Fox", "Speaker", relation, false, 20, 20, "", "the Overworld", "day", "clear", false, false,
                "running", "the dragon", "", "10, 64, 10", "", List.of(), List.of(), 0, List.of(), List.of(), List.of(), "", List.of(), "", peers);
    }

    @Test
    void anOpponentsPromptCarriesNothingBeyondPublicAndATeammatesCarriesTheSides() {
        String hunters = Listener.side("m1", "hunters"), runners = Listener.side("m1", "runners");
        var fox = new Listener(UUID.randomUUID(), "Fox", false, Set.of(), "", "", runners, false, null, null);
        var web = new KnowledgeWeb(fox.id());
        web.entity("player:wolf", KnowledgeWeb.Kind.PLAYER, "Wolf");
        web.add("player:wolf", KnowledgeWeb.Verb.SAW_AT, "the ravine by the river", "", HowKnown.TOLD, List.of(3L), 0, Visibility.SIDE, runners);
        web.add("player:wolf", KnowledgeWeb.Verb.NEEDS, "food", "", HowKnown.TOLD, List.of(4L), 0, Visibility.SIDE, runners);
        web.add("faction:oak", KnowledgeWeb.Verb.CLAIMED, "the hill fort", "", HowKnown.RECORDED, List.of(5L), 0, Visibility.PUBLIC, "");
        web.add("person:mat", KnowledgeWeb.Verb.LIVES_AT, "the cabin", "", HowKnown.CLAIMED, List.of(6L), 0, Visibility.SELF, "person:mat");
        var hunter = KnowledgeWeb.Viewer.of(new Listener(UUID.randomUUID(), "Hawk", true, Set.of(), "", "", hunters, false, null, null));
        var teammate = KnowledgeWeb.Viewer.of(new Listener(UUID.randomUUID(), "Wolf2", true, Set.of(), "", "", runners, false, null, null));
        var stranger = KnowledgeWeb.Viewer.of(new Listener(UUID.randomUUID(), "Guest", true, Set.of(), "", "", "", false, null, null));

        var forHunter = WebLines.forListener(web, hunter, 10, 6);
        assertEquals(1, forHunter.size(), "only the public claim: " + forHunter);
        String opponentPrompt = String.join("\n", ConversationAudience.lines(situation(ConversationAudience.Relation.OPPONENT, forHunter)));
        assertFalse(opponentPrompt.contains("ravine") || opponentPrompt.contains("needs food") || opponentPrompt.contains("cabin"), opponentPrompt);

        var forStranger = WebLines.forListener(web, stranger, 10, 6);
        String playerPrompt = String.join("\n", ConversationAudience.lines(situation(ConversationAudience.Relation.PLAYER, forStranger)));
        assertTrue(playerPrompt.contains("hill fort"), playerPrompt);
        assertFalse(playerPrompt.contains("ravine") || playerPrompt.contains("needs food") || playerPrompt.contains("cabin"), playerPrompt);

        var forTeammate = WebLines.forListener(web, teammate, 10, 6);
        String teamPrompt = String.join("\n", ConversationAudience.lines(situation(ConversationAudience.Relation.TEAMMATE, forTeammate)));
        assertTrue(teamPrompt.contains("Wolf saw at the ravine") && teamPrompt.contains("Wolf needs food"), teamPrompt);
        assertFalse(teamPrompt.contains("cabin"), "a person's own place is theirs: " + teamPrompt);
        assertEquals(2, WebLines.forListener(web, teammate, 10, 2).size(), "bounded");
    }
}
