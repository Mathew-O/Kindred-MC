package dev.kindred.core.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.market.Auctions;
import dev.kindred.core.market.MarketLedger;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.DeathLedger;
import dev.kindred.core.record.Ledgers;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the audit of a faction world's history (M-06i's scenario): a history played by the rules comes
 * back clean, and each kind of broken rule (a claim the rules refuse, a capture of a secure faction, a kill in the safe
 * zone, coins from nowhere, a balance that does not add up, a sale with no intent, a delivery handed over twice) is
 * found and named.
 */
class FactionsAuditTest {
    private static final FactionSettings S = FactionSettings.DEFAULTS;
    private static final String W = Territory.OVERWORLD;
    private static final long H = Capture.HOUR;
    private final List<WorldEvent> record = new ArrayList<>();
    private Ledgers state = Ledgers.EMPTY;
    private long now = 200 * H;
    private long id = 1;
    private final UUID red = UUID.randomUUID(), blue = UUID.randomUUID();
    private UUID redFaction, blueFaction;

    /** Two factions founded long ago, each leader with starting coins, Red holding a chunk at (100, 100). */
    private void played() {
        now -= 60 * H;
        redFaction = UUID.randomUUID();
        blueFaction = UUID.randomUUID();
        add(Factions.create(state.factions(), red, "Red", "RED", FactionColour.RED, redFaction, at()).events());
        add(Factions.create(state.factions(), blue, "Blue", "BLU", FactionColour.BLUE, blueFaction, at()).events());
        add(List.of(PowerRules.seen(red, 0, 0), PowerRules.seen(blue, 0, 0)));
        add(List.of(Coins.start(state.coins(), red, S, at()).orElseThrow(), Coins.start(state.coins(), blue, S, at()).orElseThrow()));
        now += 60 * H;
        add(Territory.claim(state.factions(), state.power(), state.claims(), state.safe().chunks(), state.coins().of(red), S, red,
                new Territory.Chunk(W, 100, 100), at()).events());
    }

    @Test
    void aHistoryPlayedByTheRulesIsClean() {
        played();
        add(Territory.claim(state.factions(), state.power(), state.claims(), state.safe().chunks(), state.coins().of(red), S, red,
                new Territory.Chunk(W, 101, 100), at()).events());
        add(Territory.claim(state.factions(), state.power(), state.claims(), state.safe().chunks(), state.coins().of(blue), S, blue,
                new Territory.Chunk(W, 110, 100), at()).events());
        var report = FactionsAudit.check(record, S);
        assertTrue(report.clean(), "played by the rules: " + report.violations());
        assertEquals(3, report.counts().get("claims"));
        assertEquals(2, report.counts().get("factions"));
    }

    @Test
    void aClaimTheRulesRefuseIsFound() {
        played();
        // Beside Red's land with no gap between: Blue's claim the rules refuse, written straight to the record.
        add(List.of(raw(ClaimLedger.CLAIM, blue, blueFaction.toString(), ClaimLedger.chunk(W, 101, 100))));
        var report = FactionsAudit.check(record, S);
        assertEquals(1, report.violations().size(), report.violations().toString());
        assertTrue(report.violations().getFirst().contains("Leave a chunk between your land and another faction's"), report.violations().getFirst());
        // And one no player made.
        add(List.of(raw(ClaimLedger.CLAIM, null, blueFaction.toString(), ClaimLedger.chunk(W, 120, 120))));
        assertTrue(FactionsAudit.check(record, S).violations().getLast().contains("no player made"));
    }

    @Test
    void aCaptureOfASecureFactionIsFound() {
        played();
        add(Territory.claim(state.factions(), state.power(), state.claims(), state.safe().chunks(), state.coins().of(blue), S, blue,
                new Territory.Chunk(W, 102, 100), at()).events());
        // Blue is secure (one chunk on power 10): a capture of it is outside the rules.
        add(List.of(raw(ClaimLedger.CAPTURE, red, redFaction.toString(), ClaimLedger.chunk(W, 102, 100), Map.of("from", blueFaction.toString()))));
        var report = FactionsAudit.check(record, S);
        assertEquals(1, report.violations().size(), report.violations().toString());
        assertTrue(report.violations().getFirst().contains("Blue is secure"), report.violations().getFirst());
    }

    @Test
    void aKillInTheSafeZoneIsFound() {
        played();
        add(List.of(SafeZones.zone(SafeZones.HUB, SafeZones.hub(S.safeRadius()), null, 1, now)));
        add(List.of(raw(DeathLedger.DEATH, blue, "Blue", "3,64,3", Map.of("cause", "player", "killer", red.toString()))));
        add(List.of(raw(DeathLedger.DEATH, blue, "Blue", "5000,64,5000", Map.of("cause", "player", "killer", red.toString()))));
        var report = FactionsAudit.check(record, S);
        assertEquals(1, report.violations().size(), "only the kill inside the hub: " + report.violations());
        assertTrue(report.violations().getFirst().contains("killed in the safe zone"));
        assertEquals(2, report.counts().get("kills"));
    }

    @Test
    void coinsFromNowhereAndABalanceThatDoesNotAddUpAreFound() {
        played();
        long before = state.coins().of(red);
        add(List.of(raw(Coins.KIND, red, "", "", Map.of("amount", "500", "balance", String.valueOf(before + 500), "for", "found on the floor"))));
        add(List.of(raw(Coins.KIND, blue, "", "", Map.of("amount", "-100", "balance", "999999", "for", "claim x"))));
        var report = FactionsAudit.check(record, S);
        assertEquals(2, report.violations().size(), report.violations().toString());
        assertTrue(report.violations().get(0).contains("coins out of nowhere"));
        assertTrue(report.violations().get(1).contains("does not follow"));
    }

    @Test
    void aSaleWithoutItsIntentAndADeliveryHandedOverTwiceAreFound() {
        played();
        add(List.of(raw("market.intent", red, "i1", "", Map.of()), raw(MarketLedger.SALE, red, "minecraft:iron_ingot", "", Map.of("intent", "i1"))));
        add(List.of(raw(MarketLedger.SALE, blue, "minecraft:iron_ingot", "", Map.of("intent", "i2"))));
        add(List.of(raw("market.intent", red, "i3", "", Map.of()), raw("market.cancel", red, "i3", "", Map.of()),
                raw(MarketLedger.SALE, red, "minecraft:iron_ingot", "", Map.of("intent", "i3"))));
        add(List.of(raw(Auctions.DELIVERING, red, "d1", "", Map.of()), raw(Auctions.DELIVERED, red, "d1", "", Map.of()),
                raw(Auctions.DELIVERED, red, "d1", "", Map.of())));
        var report = FactionsAudit.check(record, S);
        assertEquals(3, report.violations().size(), report.violations().toString());
        assertTrue(report.violations().get(0).contains("no intent"));
        assertTrue(report.violations().get(1).contains("after its intent was cancelled"));
        assertTrue(report.violations().get(2).contains("handed over twice"));
    }

    private WorldEvent raw(String kind, UUID actor, String subject, String place) { return raw(kind, actor, subject, place, Map.of()); }

    private WorldEvent raw(String kind, UUID actor, String subject, String place, Map<String, String> data) {
        return WorldEvent.recorded(1, now, W, kind, actor, subject, place, data);
    }

    /** Events as the Record keeps them: each under its sequence, folded into the state. */
    private void add(List<WorldEvent> events) {
        for (var e : events) {
            var kept = e.withId(id++);
            record.add(kept);
            state = state.apply(kept);
        }
    }

    private Factions.At at() { return new Factions.At(1, now, W); }
}
