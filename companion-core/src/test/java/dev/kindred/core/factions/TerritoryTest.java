package dev.kindred.core.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the rules for claiming land (M-06d): adjacency after the first chunk, a chunk's gap from another
 * faction's land, the 500-block exclusion round spawn, the border, the 80-chunk cap and power as capacity, the 100-coin
 * fee, who may change what on claimed land, and that nothing crosses a border into another owner's land.
 */
class TerritoryTest {
    private static final FactionSettings S = FactionSettings.DEFAULTS;
    private static final String W = Territory.OVERWORLD;
    private FactionBook factions = FactionBook.EMPTY;
    private PowerBook powers = PowerBook.EMPTY;
    private Map<String, String> claims = Map.of();
    private Coins.Book coins = Coins.Book.EMPTY;
    private final List<WorldEvent> record = new ArrayList<>();
    private long tick = 1;

    @Test
    void theFirstClaimIsAnywhereLegalAndTheRestGrowFromIt() {
        UUID red = UUID.randomUUID();
        found(red, "Red", "RED");
        assertTrue(claim(red, 100, 100).accepted());
        assertEquals(40_000, coins.of(red), "100 coins a claim (in hundredths)");
        assertTrue(claim(red, 101, 100).accepted(), "edge-adjacent");
        assertEquals("Not touching your land: claims grow from your own chunks", claim(red, 103, 100).reason());
        assertFalse(claim(red, 102, 101).accepted(), "a diagonal does not touch");
        assertEquals("Already yours", claim(red, 100, 100).reason());
        assertEquals(Map.of(chunk(100, 100), factionId(red), chunk(101, 100), factionId(red)), claims);
    }

    @Test
    void aChunkIsLeftBetweenUnrelatedFactionsButNotBetweenAllies() {
        UUID red = UUID.randomUUID(), blue = UUID.randomUUID();
        found(red, "Red", "RED");
        found(blue, "Blue", "BLU");
        assertTrue(claim(red, 100, 100).accepted());
        assertEquals("Leave a chunk between your land and another faction's", claim(blue, 101, 101).reason(), "a diagonal neighbour too");
        assertEquals("Leave a chunk between your land and another faction's", claim(blue, 101, 100).reason());
        assertTrue(claim(blue, 102, 100).accepted(), "one chunk between");
        assertEquals("Claimed by another faction", claim(blue, 100, 100).reason());
        // Allies may border each other; a truce is not enough.
        apply(Factions.wish(factions, red, factions.of(blue).orElseThrow().id(), Faction.Relation.TRUCE, at()).events());
        apply(Factions.wish(factions, blue, factions.of(red).orElseThrow().id(), Faction.Relation.TRUCE, at()).events());
        assertFalse(claim(blue, 101, 100).accepted(), "a truce still leaves the chunk");
        apply(Factions.wish(factions, red, factions.of(blue).orElseThrow().id(), Faction.Relation.ALLY, at()).events());
        apply(Factions.wish(factions, blue, factions.of(red).orElseThrow().id(), Faction.Relation.ALLY, at()).events());
        assertTrue(claim(blue, 101, 100).accepted(), "allies may border");
    }

    @Test
    void spawnTheBorderTheNetherAndSafeZonesAreNeverClaimed() {
        UUID red = UUID.randomUUID();
        found(red, "Red", "RED");
        assertEquals("Within 500 blocks of spawn", claim(red, 400 >> 4, 0).reason(), "at 400 blocks");
        assertEquals("Within 500 blocks of spawn", claim(red, 31, 0).reason(), "the chunk's nearest block at 496");
        assertTrue(claim(red, 600 >> 4, 0).accepted(), "at 600 blocks");
        UUID blue = UUID.randomUUID();
        found(blue, "Blue", "BLU");
        assertEquals("Beyond the border", claim(blue, 4000 >> 4, 0).reason(), "the chunk past +4,000 of an 8,000 border");
        assertTrue(claim(blue, (4000 >> 4) - 1, 0).accepted(), "the last chunk inside");
        UUID green = UUID.randomUUID();
        found(green, "Green", "GRN");
        assertEquals("Land is claimed in the overworld only",
                Territory.claim(factions, powers, claims, Set.of(), coins.of(green), S, green, new Territory.Chunk("minecraft:the_nether", 100, 100), at()).reason());
        assertEquals("A safe zone is never claimed",
                Territory.claim(factions, powers, claims, Set.of(chunk(-200, 0)), coins.of(green), S, green, new Territory.Chunk(W, -200, 0), at()).reason());
        assertTrue(Territory.playable(new Territory.Chunk("minecraft:the_nether", (500 >> 4) - 1, 0), S), "the Nether's border is the eighth: 1,000 across");
        assertFalse(Territory.playable(new Territory.Chunk("minecraft:the_nether", 500 >> 4, 0), S));
    }

    @Test
    void powerAndTheEightyChunkCapBoundTheClaimsAndCoinsPayForThem() {
        UUID red = UUID.randomUUID();
        found(red, "Red", "RED");
        coins = new Coins.Book(Map.of(red, 100_000_000L), 0);
        for (int i = 0; i < 10; i++) assertTrue(claim(red, 100 + i, 100).accepted(), "chunk " + i);
        assertEquals("Over capacity: 10 claimed of 10 (power 10, cap 80)", claim(red, 110, 100).reason(), "one member supports 10");
        for (int i = 0; i < 9; i++) enlist(red);
        for (int i = 10; i < 80; i++) assertTrue(claim(red, 100 + i % 40, 100 + i / 40).accepted(), "chunk " + i);
        assertEquals(80, claims.size());
        assertEquals("Over capacity: 80 claimed of 80 (power 100, cap 80)", claim(red, 140, 100).reason(), "ten full members, the cap");
        UUID poor = UUID.randomUUID();
        found(poor, "Poor", "POO");
        coins = new Coins.Book(Map.of(poor, 9_999L), 0);
        assertEquals("A claim costs 100 coins; you have 99.99", claim(poor, -200, -200).reason());
    }

    @Test
    void onlyOfficersClaimAndGiveUpLand() {
        UUID red = UUID.randomUUID();
        found(red, "Red", "RED");
        UUID member = enlist(red);
        coins = new Coins.Book(Map.of(red, 50_000L, member, 50_000L), 0);
        assertEquals("Only an officer or the leader claims land", claim(member, 100, 100).reason());
        assertTrue(claim(red, 100, 100).accepted());
        assertFalse(Territory.unclaim(factions, claims, member, new Territory.Chunk(W, 100, 100), at()).accepted());
        var released = Territory.unclaim(factions, claims, red, new Territory.Chunk(W, 100, 100), at());
        assertTrue(released.accepted());
        apply(released.events());
        assertTrue(claims.isEmpty());
    }

    @Test
    void protectionAndCrossingTheBorder() {
        UUID red = UUID.randomUUID(), blue = UUID.randomUUID(), loner = UUID.randomUUID();
        found(red, "Red", "RED");
        found(blue, "Blue", "BLU");
        claim(red, 100, 100);
        var home = new Territory.Chunk(W, 100, 100);
        var wild = new Territory.Chunk(W, 99, 100);
        var spawn = new Territory.Chunk(W, 0, 0);
        Set<String> safe = Set.of(spawn.key());
        assertTrue(Territory.mayChange(factions, claims, safe, red, false, home));
        assertFalse(Territory.mayChange(factions, claims, safe, blue, false, home), "an enemy");
        assertFalse(Territory.mayChange(factions, claims, safe, loner, false, home), "a player in no faction");
        assertFalse(Territory.mayChange(factions, claims, safe, blue, true, home), "an operator's rank is no faction membership");
        assertTrue(Territory.mayChange(factions, claims, safe, blue, false, wild));
        assertFalse(Territory.mayChange(factions, claims, safe, red, false, spawn), "nobody changes a safe zone");
        assertTrue(Territory.mayChange(factions, claims, safe, red, true, spawn), "but an operator");
        assertFalse(Territory.mayCross(claims, safe, wild, home), "water, a piston, a hopper from the wilderness in");
        assertTrue(Territory.mayCross(claims, safe, home, wild), "out of a claim into the wilderness");
        assertTrue(Territory.mayCross(claims, safe, home, home));
        assertFalse(Territory.mayCross(claims, safe, wild, spawn));
        assertEquals(Territory.Access.MEMBERS_ONLY, Territory.access(claims, safe, home));
        assertEquals(home, Territory.Chunk.of(home.key()));
        assertEquals(new Territory.Chunk(W, -1, -1), Territory.Chunk.at(W, -1, -16));
    }

    @Test
    void claimsAndCoinsComeBackFromTheRecord() {
        UUID red = UUID.randomUUID();
        found(red, "Red", "RED");
        claim(red, 100, 100);
        claim(red, 100, 101);
        var written = new ArrayList<WorldEvent>();
        for (int i = 0; i < record.size(); i++) written.add(record.get(i).withId(i + 1));
        assertEquals(claims, ClaimLedger.INSTANCE.replay(written));
        assertEquals(coins, Coins.INSTANCE.replay(written));
        assertEquals(30_000, coins.of(red));
        assertEquals(50_000, coins.minted(), "only the start rule puts coins into the world");
    }

    // --- helpers ---

    private Territory.Decision claim(UUID player, int x, int z) {
        var decision = Territory.claim(factions, powers, claims, Set.of(), coins.of(player), S, player, new Territory.Chunk(W, x, z), at());
        apply(decision.events());
        return decision;
    }

    private void found(UUID leader, String name, String tag) {
        apply(Factions.create(factions, leader, name, tag, FactionColour.WHITE, UUID.randomUUID(), at()).events());
        apply(List.of(Coins.start(coins, leader, S, at()).orElseThrow()));
    }

    private UUID enlist(UUID leader) {
        UUID player = UUID.randomUUID();
        apply(Factions.invite(factions, leader, player, true, at()).events());
        apply(Factions.join(factions, player, factions.of(leader).orElseThrow().id(), S, at()).events());
        return player;
    }

    private String factionId(UUID member) { return factions.of(member).orElseThrow().id().toString(); }

    private static String chunk(int x, int z) { return ClaimLedger.chunk(W, x, z); }

    private void apply(List<WorldEvent> events) {
        for (var event : events) {
            record.add(event);
            factions = FactionLedger.INSTANCE.apply(factions, event);
            powers = PowerLedger.INSTANCE.apply(powers, event);
            if (ClaimLedger.INSTANCE.kinds().contains(event.kind())) claims = ClaimLedger.INSTANCE.apply(claims, event);
            if (Coins.INSTANCE.kinds().contains(event.kind())) coins = Coins.INSTANCE.apply(coins, event);
        }
    }

    private Factions.At at() { return new Factions.At(tick++, 1_000_000 + tick, W); }
}
