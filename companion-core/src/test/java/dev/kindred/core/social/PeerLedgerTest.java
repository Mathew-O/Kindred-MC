package dev.kindred.core.social;

import dev.kindred.core.ConversationAudience;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks what an AI player knows of the others: what it sees, what a teammate tells it through the team
 * and what a person says of themselves, in that order of trust; a claim is never a fact, holds a game day and is retired
 * once seen; whoever said something can take it back and who said it stays known; places are kept only as far as they
 * may be; the bounds hold; and only what is to be kept survives a restart.
 */
class PeerLedgerTest {
    private static final UUID ME = UUID.randomUUID(), SOL = UUID.randomUUID(), STEVE = UUID.randomUUID(), ALEX = UUID.randomUUID();
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";

    private static PeerLedger.Sighting person(UUID id, String name, int x) {
        return new PeerLedger.Sighting(id, name, PeerLedger.Kind.PERSON, new PeerLedger.Place(OVERWORLD, x, 64, 0));
    }

    @Test
    void whatItSeesIsMetAndAPersonsPlaceIsKeptOnlyWhileInSight() {
        var ledger = new PeerLedger(ME);
        ledger.sighting(List.of(person(STEVE, "Steve", 10)), 100);
        assertTrue(ledger.knows(STEVE));
        assertEquals(new PeerLedger.Place(OVERWORLD, 10, 64, 0), ledger.place(STEVE).orElseThrow());
        assertEquals(100, ledger.seen(STEVE).orElseThrow().at());
        ledger.sighting(List.of(), 200);
        assertTrue(ledger.place(STEVE).isEmpty(), "Out of sight, no place kept");
        assertEquals(100, ledger.seen(STEVE).orElseThrow().at(), "When it last saw them is kept");
        assertEquals(OVERWORLD, ledger.seen(STEVE).orElseThrow().world());
        ledger.sighting(List.of(person(ME, "Me", 0)), 300);
        assertFalse(ledger.knows(ME), "Never its own peer");
        assertThrows(IllegalArgumentException.class, () -> ledger.episode(ME, "Me", PeerLedger.Kind.AI_PLAYER, "x", 300));
    }

    @Test
    void aTeammatesUpdateCarriesItsNeedsAndItsPlaceOnlyWhileTheTeamSharesIt() {
        var ledger = new PeerLedger(ME);
        var here = new PeerLedger.Place(OVERWORLD, 5, 60, 5);
        ledger.update(new PeerUpdate(SOL, "Sol", "gathering logs", List.of("3 string", "8 feathers"), List.of("4 iron"), here), 100);
        var needs = ledger.best(SOL, PeerLedger.Topic.NEEDS, 150).orElseThrow();
        assertEquals("3 string, 8 feathers", needs.text());
        assertEquals(PeerLedger.Source.TEAM, needs.source());
        assertEquals(SOL, needs.by());
        assertEquals(here, ledger.place(SOL).orElseThrow());
        assertEquals(PeerLedger.Kind.AI_PLAYER, ledger.kind(SOL).orElseThrow());
        ledger.update(new PeerUpdate(SOL, "Sol", "fighting", List.of(), List.of(), null), 200);
        assertTrue(ledger.place(SOL).isEmpty(), "No place once the team no longer shares it");
        assertTrue(ledger.best(SOL, PeerLedger.Topic.NEEDS, 250).isEmpty(), "Nothing needed now");
        assertEquals("fighting", ledger.best(SOL, PeerLedger.Topic.DOING, 250).orElseThrow().text());
        // Bounded as it comes: eight items of sixty characters on one line.
        var long_ = "x".repeat(200);
        var update = new PeerUpdate(SOL, "Sol\n", long_, java.util.Collections.nCopies(12, "1 stick"), null, null);
        assertEquals(PeerUpdate.MAX_ITEMS, update.needs().size());
        assertEquals(PeerUpdate.MAX_WORDS, update.doing().length());
        assertEquals("Sol", update.name());
    }

    @Test
    void aClaimIsNeverAFactAndHoldsAGameDay() {
        var ledger = new PeerLedger(ME);
        ledger.claim(STEVE, "Steve", PeerLedger.Topic.NEEDS, "iron", null, 1_000);
        assertTrue(ledger.best(STEVE, PeerLedger.Topic.NEEDS, 1_100).isEmpty(), "A claim is no fact");
        assertEquals(1, ledger.claims(STEVE, PeerLedger.Topic.NEEDS, 1_100).size());
        var told = ledger.told(1_100, 10);
        assertTrue(told.stream().anyMatch(t -> t.claim() && t.text().equals("Steve said, just now, that they needed iron (said, not seen)")), told.toString());
        assertTrue(told.stream().noneMatch(t -> t.text().contains("Steve needs")), "Never told as a fact: " + told);
        assertTrue(ledger.claims(STEVE, PeerLedger.Topic.NEEDS, 1_000 + PeerLedger.GAME_DAY).isEmpty(), "Gone after a game day");
        assertTrue(ledger.told(1_000 + PeerLedger.GAME_DAY, 10).stream().noneMatch(PeerLedger.Told::claim));
    }

    @Test
    void theGamesRecordComesFirstThenTheTeamThenWhatWasSaid() {
        var ledger = new PeerLedger(ME);
        // What was said yields to a teammate's own record on the same topic, unless it is newer, and then only as a claim.
        ledger.update(new PeerUpdate(SOL, "Sol", "mining", List.of("iron"), List.of(), null), 100);
        ledger.claim(SOL, "Sol", PeerLedger.Topic.NEEDS, "diamonds", null, 200);
        assertEquals("iron", ledger.best(SOL, PeerLedger.Topic.NEEDS, 300).orElseThrow().text());
        assertEquals("diamonds", ledger.claims(SOL, PeerLedger.Topic.NEEDS, 300).getFirst().text(), "Newer, so shown, as a claim");
        ledger.update(new PeerUpdate(SOL, "Sol", "mining", List.of("iron"), List.of(), null), 400);
        assertTrue(ledger.claims(SOL, PeerLedger.Topic.NEEDS, 500).isEmpty(), "Older than the team's own record: not shown");
        // What it sees comes before what it was told: a claim of going to the Nether, then a sighting there.
        ledger.claim(STEVE, "Steve", PeerLedger.Topic.DOING, "going to the Nether", NETHER, 1_000);
        ledger.sighting(List.of(new PeerLedger.Sighting(STEVE, "Steve", PeerLedger.Kind.PERSON, new PeerLedger.Place(NETHER, 0, 70, 0))), 1_500);
        var doing = ledger.best(STEVE, PeerLedger.Topic.DOING, 1_600).orElseThrow();
        assertEquals(PeerLedger.Source.GAME, doing.source());
        assertEquals("seen in the Nether", doing.text());
        assertTrue(ledger.claims(STEVE, PeerLedger.Topic.DOING, 1_600).isEmpty(), "Retired once seen");
        assertTrue(ledger.claimsKept(STEVE).getFirst().confirmed(), "Kept, marked seen");
        // A sighting in the same tick as the claim is no confirmation.
        ledger.claim(ALEX, "Alex", PeerLedger.Topic.DOING, "going to the Nether", NETHER, 2_000);
        ledger.sighting(List.of(new PeerLedger.Sighting(ALEX, "Alex", PeerLedger.Kind.PERSON, new PeerLedger.Place(NETHER, 0, 70, 0))), 2_000);
        assertFalse(ledger.claimsKept(ALEX).getFirst().confirmed());
    }

    @Test
    void whoeverSaidItCanTakeItBackAndWhoSaidItStaysKnown() {
        var ledger = new PeerLedger(ME);
        ledger.claim(STEVE, "Steve", PeerLedger.Topic.DOING, "going to the Nether", NETHER, 100);
        ledger.claim(STEVE, "Steve", PeerLedger.Topic.NEEDS, "iron", null, 200);
        assertTrue(ledger.correct(ALEX, null, PeerLedger.Correction.FORGOTTEN, 300).isEmpty(), "Only whoever said it");
        var corrected = ledger.correct(STEVE, "nether", PeerLedger.Correction.FORGOTTEN, 300).orElseThrow();
        assertEquals("going to the Nether", corrected.text());
        assertEquals(STEVE, corrected.by());
        assertEquals(100, corrected.at(), "Who said it and when, kept");
        assertEquals(PeerLedger.Correction.FORGOTTEN, corrected.correction());
        assertEquals(STEVE, corrected.correctedBy());
        assertEquals(300, corrected.correctedAt());
        assertTrue(ledger.claims(STEVE, PeerLedger.Topic.DOING, 400).isEmpty(), "Never told again");
        assertTrue(ledger.told(400, 10).stream().noneMatch(t -> t.text().contains("Nether")));
        assertEquals(1, ledger.claims(STEVE, PeerLedger.Topic.NEEDS, 400).size(), "Only that one");
        assertEquals(PeerLedger.Correction.WRONG, ledger.correct(STEVE, null, PeerLedger.Correction.WRONG, 500).orElseThrow().correction());
        assertTrue(ledger.correct(STEVE, null, PeerLedger.Correction.WRONG, 600).isEmpty(), "Nothing current left to take back");
        assertEquals(2, ledger.claimsKept(STEVE).size(), "Both kept, with their provenance");
        assertTrue(ledger.forget(STEVE), "An operator's forget removes everything");
        assertFalse(ledger.knows(STEVE));
    }

    @Test
    void theBoundsHold() {
        var ledger = new PeerLedger(ME);
        UUID first = UUID.randomUUID();
        ledger.sighting(List.of(person(first, "First", 0)), 0);
        for (int i = 1; i <= PeerLedger.MAX_PEERS; i++) ledger.sighting(List.of(person(UUID.randomUUID(), "P" + i, i)), i);
        assertEquals(PeerLedger.MAX_PEERS, ledger.size());
        assertFalse(ledger.knows(first), "The least lately met is dropped");
        for (int i = 0; i < 25; i++) ledger.episode(SOL, "Sol", PeerLedger.Kind.AI_PLAYER, "episode " + i, 1_000 + i);
        var episodes = ledger.episodes(SOL);
        assertEquals(PeerLedger.MAX_EPISODES, episodes.size());
        assertEquals("episode 5", episodes.getFirst().text(), "Oldest dropped");
        for (int i = 0; i < 12; i++) ledger.claim(STEVE, "Steve", PeerLedger.Topic.NEEDS, "thing " + i, null, 2_000 + i);
        assertEquals(PeerLedger.MAX_CLAIMS, ledger.claimsKept(STEVE).size());
    }

    @Test
    void handOversAndEpisodesAreTheGamesRecord() {
        var ledger = new PeerLedger(ME);
        ledger.gave(STEVE, "Steve", PeerLedger.Kind.PERSON, "bread", 3, 100);
        ledger.gave(STEVE, "Steve", PeerLedger.Kind.PERSON, "bread", 2, 200);
        ledger.received(STEVE, "Steve", PeerLedger.Kind.PERSON, "iron ingot", 4, 300);
        ledger.episode(STEVE, "Steve", PeerLedger.Kind.PERSON, "struck me", 400);
        assertEquals(5, ledger.given(STEVE).get("bread"));
        assertEquals(4, ledger.received(STEVE).get("iron ingot"));
        var told = ledger.told(500, 10);
        assertTrue(told.contains(new PeerLedger.Told(STEVE, "Steve", "You have handed Steve 5 bread", PeerLedger.Reach.ANYONE, false)), told.toString());
        assertTrue(told.contains(new PeerLedger.Told(STEVE, "Steve", "Steve has handed you 4 iron ingot", PeerLedger.Reach.ANYONE, false)), told.toString());
        assertTrue(told.stream().anyMatch(t -> t.text().equals("With Steve, just now: struck me")));
    }

    @Test
    void onlyWhatIsToBeKeptSurvivesARestart() {
        var ledger = new PeerLedger(ME);
        var here = new PeerLedger.Place(OVERWORLD, 5, 60, 5);
        ledger.update(new PeerUpdate(SOL, "Sol", "gathering logs", List.of("3 string"), List.of(), here), 100);
        ledger.sighting(List.of(person(STEVE, "Steve", 10)), 150);
        ledger.claim(STEVE, "Steve", PeerLedger.Topic.NEEDS, "iron", null, 160);
        ledger.episode(SOL, "Sol", PeerLedger.Kind.AI_PLAYER, "we fought a skeleton", 170);
        ledger.gave(STEVE, "Steve", PeerLedger.Kind.PERSON, "bread", 3, 180);
        var restored = PeerLedger.restore(ME, ledger.snapshot());
        assertEquals("3 string", restored.best(SOL, PeerLedger.Topic.NEEDS, 200).orElseThrow().text());
        assertEquals(here, restored.place(SOL).orElseThrow(), "A teammate's shared place is kept");
        assertTrue(restored.place(STEVE).isEmpty(), "A person's place is not");
        assertTrue(restored.claimsKept(STEVE).isEmpty(), "Nothing said is kept");
        assertEquals(150, restored.seen(STEVE).orElseThrow().at());
        assertEquals("we fought a skeleton", restored.episodes(SOL).getFirst().text());
        assertEquals(3, restored.given(STEVE).get("bread"));
        assertEquals(0, PeerLedger.restore(ME, null).size());
        // A row naming the player itself, or with no kind, is left out.
        var bad = new PeerLedger.Snapshot(ME, java.util.Arrays.asList(null,
                new PeerLedger.PeerRow(ME, "Me", PeerLedger.Kind.AI_PLAYER, 0, 0, 0, null, null, null, null, null, null),
                new PeerLedger.PeerRow(ALEX, "Alex", null, 0, 0, 0, null, null, null, null, null, null)));
        assertEquals(0, PeerLedger.restore(ME, bad).size());
    }

    @Test
    void theVoiceAndTheFixedAnswersAreToldByTier() {
        var ledger = new PeerLedger(ME);
        ledger.update(new PeerUpdate(SOL, "Sol", "gathering logs", List.of("3 string"), List.of(), new PeerLedger.Place(OVERWORLD, 5, 60, 5)), 100);
        ledger.claim(STEVE, "Steve", PeerLedger.Topic.DOING, "going to the Nether", NETHER, 120);
        var needs = new Claims.Question(Claims.Ask.NEEDS, "sol");
        var owner = ConversationAudience.Relation.OWNER;
        assertEquals("Sol needs 3 string (Sol's own update, just now).", PeerAnswers.answer(needs, ledger, owner, true, "Alex", 200).orElseThrow());
        assertEquals("I don't know of anything Sol needs.", PeerAnswers.answer(needs, ledger, ConversationAudience.Relation.PLAYER, false, "Alex", 200).orElseThrow(),
                "The team's business is not a stranger's");
        assertEquals("Sol is at 5, 60, 5 in the Overworld.", PeerAnswers.answer(new Claims.Question(Claims.Ask.WHERE, "sol"), ledger, owner, true, "Alex", 200).orElseThrow());
        assertEquals("I haven't seen Sol.", PeerAnswers.answer(new Claims.Question(Claims.Ask.WHERE, "sol"), ledger, ConversationAudience.Relation.TEAMMATE, false, "Alex", 200).orElseThrow(),
                "A place to a controller alone");
        assertEquals("Steve said, just now, that they were going to the Nether; I haven't seen that myself.",
                PeerAnswers.answer(new Claims.Question(Claims.Ask.WHERE, "steve"), ledger, owner, true, "Alex", 200).orElseThrow());
        assertEquals("Steve said, just now, that they were going to the Nether; I haven't seen that myself.",
                PeerAnswers.answer(new Claims.Question(Claims.Ask.DOING, "steve"), ledger, ConversationAudience.Relation.PLAYER, false, "Steve", 200).orElseThrow(),
                "What someone said, back to them");
        assertEquals("I don't know what Steve is doing.",
                PeerAnswers.answer(new Claims.Question(Claims.Ask.DOING, "steve"), ledger, ConversationAudience.Relation.PLAYER, false, "Alex", 200).orElseThrow(),
                "Not to anyone else");
        assertEquals("I'd rather not say.", PeerAnswers.answer(needs, ledger, ConversationAudience.Relation.OPPONENT, true, "Alex", 200).orElseThrow());
        assertTrue(PeerAnswers.answer(new Claims.Question(Claims.Ask.NEEDS, "herobrine"), ledger, owner, true, "Alex", 200).isEmpty(),
                "Nobody it knows by that name: ordinary talk");

        // The voice: the same lines, by the same rules.
        var told = ledger.told(200, 10);
        var forOwner = ConversationAudience.lines(situation("Alex", owner, true, told));
        assertTrue(forOwner.contains("You know of others: Sol is at 5, 60, 5 in the Overworld"), forOwner.toString());
        assertTrue(forOwner.contains("You know of others: Sol needs 3 string (from Sol's own update)"), forOwner.toString());
        var forStranger = ConversationAudience.lines(situation("Alex", ConversationAudience.Relation.PLAYER, false, told));
        assertTrue(forStranger.stream().noneMatch(line -> line.contains("Sol is at") || line.contains("Sol needs") || line.contains("Steve said")), forStranger.toString());
        var forSteve = ConversationAudience.lines(situation("Steve", ConversationAudience.Relation.PLAYER, false, told));
        assertTrue(forSteve.stream().anyMatch(line -> line.contains("Steve said, just now, that they were going to the Nether (said, not seen)")), forSteve.toString());
        var forOpponent = ConversationAudience.lines(situation("Alex", ConversationAudience.Relation.OPPONENT, true, told));
        assertTrue(forOpponent.stream().noneMatch(line -> line.startsWith("You know of others")), forOpponent.toString());
    }

    private static ConversationAudience.Situation situation(String speaker, ConversationAudience.Relation relation, boolean controls, List<PeerLedger.Told> peers) {
        return new ConversationAudience.Situation("Echo", speaker, relation, controls, 20, 20, "", "", "", "", false, false, "", "", "", "1, 2, 3", "",
                List.of(), List.of(), 0, List.of(), List.of(), List.of(), "", List.of(), "", peers);
    }
}
