package dev.kindred.core.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks overclaim and capture (M-06e) against the rulebook's acceptance scenarios 2 to 5: the overclaim
 * trigger, the capture boundary (border first, then inward), protection before capture, and the protected spaces; and
 * the shields (48 hours for a new faction, not reset by founding again; 30 minutes after a loss), one capture a chunk an
 * hour, capacity and coins, and a recapture when the attacker's power falls.
 */
class CaptureTest {
    private static final FactionSettings S = FactionSettings.DEFAULTS;
    private static final String W = Territory.OVERWORLD;
    private static final long H = Capture.HOUR;
    private FactionBook factions = FactionBook.EMPTY;
    private PowerBook powers = PowerBook.EMPTY;
    private Map<String, String> claims = Map.of();
    private Coins.Book coins = Coins.Book.EMPTY;
    private Capture.Book captures = Capture.Book.EMPTY;
    private final List<WorldEvent> record = new ArrayList<>();
    private long now = 100 * H;

    private UUID redLeader, blueLeader, red, blue;
    private final List<UUID> blues = new ArrayList<>();

    /** Red attacks; Blue holds a block of land. Both founded long ago (past the 48-hour shield). */
    private void setUp(int blueMembers) {
        now -= 60 * H;
        redLeader = UUID.randomUUID();
        blueLeader = UUID.randomUUID();
        red = found(redLeader, "Red", "RED");
        blue = found(blueLeader, "Blue", "BLU");
        blues.add(blueLeader);
        for (int i = 1; i < blueMembers; i++) blues.add(enlist(blue, blueLeader));
        now += 60 * H;
        coins = new Coins.Book(Map.of(redLeader, 1_000_000L, blueLeader, 1_000_000L), 0);
    }

    @Test
    void scenarioTwoTheOverclaimTriggerOpensCapture() {
        setUp(5);
        var land = new LinkedHashMap<String, String>();
        for (int i = 0; i < 45; i++) land.put(ClaimLedger.chunk(W, 100 + i % 9, 100 + i / 9), blue.toString());
        claims = land;
        assertEquals(new Capture.Exposure(45, 50, 0), Capture.exposure(factions, powers, claims, blue, S), "45 claims at 50 power: safe");
        var edge = new Territory.Chunk(W, 100, 100);
        assertTrue(capture(redLeader, edge).reason().startsWith("Blue is secure"));
        for (int i = 0; i < 4; i++) apply(List.of(PowerRules.loss(powers, blues.get(i), redLeader, S, 1, now - (i + 1) * 30 * 60_000L, W, "")));
        assertEquals(new Capture.Exposure(45, 42, 3), Capture.exposure(factions, powers, claims, blue, S), "four −2 deaths: 42 power, 3 exposed");
        assertTrue(capture(redLeader, edge).accepted());
    }

    @Test
    void scenarioThreeBorderFirstThenInward() {
        setUp(1);
        // Blue's plus of five chunks, its power 1: four exposed. The centre is interior.
        for (int[] c : new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}) claim(blue, 200 + c[0], 200 + c[1]);
        setPower(blueLeader, 1);
        var centre = new Territory.Chunk(W, 200, 200);
        assertEquals("Not on the border: take a chunk touching the wilderness or your own land first", capture(redLeader, centre).reason(), "no jump to the treasure chunk");
        var west = new Territory.Chunk(W, 199, 200);
        apply(capture(redLeader, west).events());
        assertEquals(red.toString(), claims.get(west.key()));
        // Inward from the captured chunk, once Blue's 30-minute shield is over.
        assertTrue(capture(redLeader, centre).reason().startsWith("Blue lost land lately"));
        now += 31 * 60_000L;
        assertTrue(Capture.onTheBorder(claims, Set.of(), blue.toString(), red.toString(), centre), "now touching Red's own land");
        assertTrue(capture(redLeader, centre).accepted());
    }

    @Test
    void scenarioFourAVulnerableChunkIsProtectedUntilCaptured() {
        setUp(1);
        claim(blue, 130, 130);
        claim(blue, 131, 130);
        setPower(blueLeader, 0);
        var chunk = new Territory.Chunk(W, 130, 130);
        assertTrue(Capture.exposure(factions, powers, claims, blue, S).overextended());
        assertFalse(Territory.mayChange(factions, claims, Set.of(), redLeader, false, chunk), "exposed, not captured: no chest, no block");
        assertTrue(Territory.mayChange(factions, claims, Set.of(), blueLeader, false, chunk));
        apply(capture(redLeader, chunk).events());
        assertTrue(Territory.mayChange(factions, claims, Set.of(), redLeader, false, chunk), "captured: the new owner's, its chests with it");
        assertFalse(Territory.mayChange(factions, claims, Set.of(), blueLeader, false, chunk), "no longer the old owner's");
    }

    @Test
    void scenarioFiveSafeZonesAreNeverCaptured() {
        setUp(1);
        claim(blue, 0, 0);
        setPower(blueLeader, 0);
        var spawn = new Territory.Chunk(W, 0, 0);
        assertEquals("A safe zone is never captured", Capture.capture(factions, powers, claims, Set.of(spawn.key()), captures, coins.of(redLeader), S, redLeader, spawn, at()).reason());
    }

    @Test
    void shieldsTheHourCapacityAndCoins() {
        // A new faction: 48 hours, not reset by founding again.
        UUID founder = UUID.randomUUID(), attacker = UUID.randomUUID();
        UUID fresh = found(founder, "Fresh", "FR");
        UUID att = found(attacker, "Att", "AT");
        now += 49 * H;
        coins = new Coins.Book(Map.of(attacker, 1_000_000L), 0);
        claim(fresh, 140, 140);
        setPower(founder, 0);
        var chunk = new Territory.Chunk(W, 140, 140);
        apply(Factions.disband(factions, founder, at()).events());
        UUID again = found(founder, "Again", "AG");
        claims = Map.of(chunk.key(), again.toString());
        setPower(founder, 0);
        assertTrue(capture(attacker, chunk).accepted(), "founding again does not reset the shield");
        UUID other = UUID.randomUUID();
        UUID newer = found(other, "Newer", "NEW");
        claims = Map.of(new Territory.Chunk(W, 150, 150).key(), newer.toString());
        setPower(other, 0);
        assertTrue(capture(attacker, new Territory.Chunk(W, 150, 150)).reason().startsWith("Newer is a new faction, shielded for 2880 more minutes"));
        // Capacity and coins.
        coins = new Coins.Book(Map.of(attacker, 9_999L), 0);
        now += 49 * H;
        assertEquals("A capture costs 100 coins; you have 99.99", capture(attacker, new Territory.Chunk(W, 150, 150)).reason());
        setPower(attacker, 0);
        coins = new Coins.Book(Map.of(attacker, 1_000_000L), 0);
        assertTrue(capture(attacker, new Territory.Chunk(W, 150, 150)).reason().startsWith("Over capacity"));
        assertEquals(att, factions.of(attacker).orElseThrow().id());
    }

    @Test
    void theSameChunkOnceAnHourAndARecaptureWhenTheAttackersPowerFalls() {
        setUp(1);
        claim(blue, 160, 160);
        claim(blue, 161, 160);
        setPower(blueLeader, 0);
        var chunk = new Territory.Chunk(W, 160, 160);
        apply(capture(redLeader, chunk).events());
        // Red's power falls; Blue, recovered, takes the chunk back, but not within the hour.
        setPower(redLeader, 0);
        setPower(blueLeader, 10);
        now += 31 * 60_000L;
        assertEquals("This chunk changed hands within the hour", capture(blueLeader, chunk).reason());
        now += 30 * 60_000L;
        var back = capture(blueLeader, chunk);
        assertTrue(back.accepted(), back.reason());
        apply(back.events());
        assertEquals(blue.toString(), claims.get(chunk.key()));
        var written = new ArrayList<WorldEvent>();
        for (int i = 0; i < record.size(); i++) written.add(record.get(i).withId(i + 1));
        assertEquals(captures, Capture.INSTANCE.replay(written));
        assertEquals(claims, ClaimLedger.INSTANCE.replay(written));
    }

    // --- helpers ---

    private Territory.Decision capture(UUID player, Territory.Chunk chunk) {
        return Capture.capture(factions, powers, claims, Set.of(), captures, coins.of(player), S, player, chunk, at());
    }

    private UUID found(UUID leader, String name, String tag) {
        UUID id = UUID.randomUUID();
        apply(Factions.create(factions, leader, name, tag, FactionColour.WHITE, id, at()).events());
        apply(List.of(PowerRules.seen(leader, 0, 0)));
        return id;
    }

    private UUID enlist(UUID faction, UUID leader) {
        UUID p = UUID.randomUUID();
        apply(Factions.invite(factions, leader, p, true, at()).events());
        apply(Factions.join(factions, p, faction, S, at()).events());
        return p;
    }

    private void claim(UUID faction, int x, int z) {
        apply(List.of(WorldEvent.recorded(1, now, W, ClaimLedger.CLAIM, null, faction.toString(), ClaimLedger.chunk(W, x, z), Map.of())));
    }

    private void setPower(UUID player, int power) { apply(List.of(PowerRules.set(player, power, null, S, 1, now))); }

    private void apply(List<WorldEvent> events) {
        for (var e : events) {
            record.add(e);
            factions = FactionLedger.INSTANCE.apply(factions, e);
            powers = PowerLedger.INSTANCE.apply(powers, e);
            if (ClaimLedger.INSTANCE.kinds().contains(e.kind())) claims = ClaimLedger.INSTANCE.apply(claims, e);
            if (Capture.INSTANCE.kinds().contains(e.kind())) captures = Capture.INSTANCE.apply(captures, e);
            if (Coins.INSTANCE.kinds().contains(e.kind())) coins = Coins.INSTANCE.apply(coins, e);
        }
    }

    private Factions.At at() { return new Factions.At(1, now, W); }
}
