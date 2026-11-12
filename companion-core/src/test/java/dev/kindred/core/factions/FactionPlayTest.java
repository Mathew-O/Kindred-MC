package dev.kindred.core.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.Personality;
import dev.kindred.core.market.AuctionBook;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks how an AI player chooses what to do in a faction world (M-06i): founding or joining, claiming
 * land, selling, buying and bidding, going home when its land is being taken, and scouting and capturing an enemy's
 * exposed border only where it saw that land itself.
 */
class FactionPlayTest {
    private static final FactionSettings S = FactionSettings.DEFAULTS;
    private static final String W = Territory.OVERWORLD;
    private static final long H = Capture.HOUR;
    private FactionBook factions = FactionBook.EMPTY;
    private PowerBook powers = PowerBook.EMPTY;
    private Map<String, String> claims = Map.of();
    private Capture.Book captures = Capture.Book.EMPTY;
    private long now = 100 * H;
    private final UUID self = UUID.randomUUID();
    private List<UUID> nearby = List.of();
    private Set<String> setAside = Set.of();

    private static Personality person(int initiative, int caution, int sociability, int combativeness) {
        return new Personality(initiative, caution, 50, sociability, 50, combativeness, 50, 50, 50, 0);
    }

    private FactionPlay.View view(Personality p, long coins, Optional<Territory.Chunk> site, Optional<Territory.Chunk> standing, List<FactionPlay.Spare> spare,
                                  List<FactionPlay.Want> wants, Map<String, Long> asks, AuctionBook auctions, List<FactionPlay.Sighting> seen) {
        return new FactionPlay.View(self, p, factions, powers, claims, Set.of(), captures, coins, S, now, site, standing, true, spare, wants, asks, auctions, seen, nearby, setAside);
    }

    private FactionPlay.View plain(Personality p, long coins) {
        return view(p, coins, Optional.empty(), Optional.empty(), List.of(), List.of(), Map.of(), AuctionBook.EMPTY, List.of());
    }

    @Test
    void withNoInvitationItFoundsItsOwnByInitiativeOrAfterADaysWait() {
        apply(List.of(PowerRules.seen(self, 0, now)));
        assertEquals(FactionPlay.Kind.FOUND, FactionPlay.decide(plain(person(60, 50, 50, 50), 0)).getFirst().kind(), "initiative founds at once");
        assertTrue(FactionPlay.decide(plain(person(30, 50, 50, 50), 0)).isEmpty(), "a quiet player waits for an invitation");
        now += FactionPlay.GAME_DAY_MS;
        assertEquals(FactionPlay.Kind.FOUND, FactionPlay.decide(plain(person(30, 50, 50, 50), 0)).getFirst().kind(), "after a game day it founds anyway");
    }

    @Test
    void aSociablePlayerJoinsTheSmallestFactionThatInvitedIt() {
        UUID blueLeader = UUID.randomUUID(), greenLeader = UUID.randomUUID();
        UUID blue = found(blueLeader, "Blue", "BLU"), green = found(greenLeader, "Green", "GRN");
        enlist(blue, blueLeader);
        enlist(blue, blueLeader);
        apply(Factions.invite(factions, blueLeader, self, true, at()).events());
        apply(Factions.invite(factions, greenLeader, self, true, at()).events());
        var join = FactionPlay.decide(plain(person(30, 50, 60, 50), 0)).getFirst();
        assertEquals(new FactionPlay.Choice(FactionPlay.Kind.JOIN, green.toString(), 0, "Join Green, who invited it"), join,
                "Green (one member) wants it more than Blue (three)");
        assertEquals(FactionPlay.Kind.FOUND, FactionPlay.decide(plain(person(30, 50, 20, 50), 0)).getFirst().kind(), "an unsociable player founds its own");
    }

    @Test
    void itClaimsItsSiteThenLandBesideItWithinTheRulesAndKeepsCoinsBack() {
        UUID mine = found(self, "Red", "RED");
        var site = new Territory.Chunk(W, 100, 100);
        var first = FactionPlay.decide(view(person(50, 0, 50, 50), 100 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(), Map.of(), AuctionBook.EMPTY, List.of()));
        assertEquals(new FactionPlay.Choice(FactionPlay.Kind.CLAIM, site.key(), 100 * Coins.CENT, "Claim the base site"), first.getFirst());
        var nearSpawn = new Territory.Chunk(W, 2, 2);
        var out = FactionPlay.decide(view(person(50, 0, 50, 50), 1000 * Coins.CENT, Optional.of(nearSpawn), Optional.empty(), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, List.of()));
        var beyond = Territory.Chunk.of(out.getFirst().target());
        assertTrue(beyond.nearestToSpawn() >= S.spawnExclusion() && beyond.nearestToSpawn() < S.spawnExclusion() + 48 && beyond.x() > 0 && beyond.z() > 0,
                "never inside the spawn exclusion: just beyond it, on the site's bearing: " + beyond);
        assertTrue(FactionPlay.beyondSpawn(new Territory.Chunk(W, 0, 0), S).nearestToSpawn() >= S.spawnExclusion(), "from spawn's own chunk too");
        assertEquals(new Territory.Chunk(W, 100, 100), FactionPlay.beyondSpawn(new Territory.Chunk(W, 100, 100), S), "a site outside the exclusion is kept");
        claim(mine, 100, 100);
        var next = FactionPlay.decide(view(person(50, 50, 50, 50), 1000 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(), Map.of(), AuctionBook.EMPTY, List.of()));
        assertEquals(FactionPlay.Kind.CLAIM, next.getFirst().kind());
        assertTrue(Territory.touchesOwn(claims, mine.toString(), Territory.Chunk.of(next.getFirst().target())), "beside its own land");
        assertTrue(FactionPlay.decide(view(person(50, 50, 50, 50), 150 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, List.of())).isEmpty(), "a cautious player keeps 200 coins back for the next claim");
        // Power 10 holds ten chunks: an eleventh is never chosen.
        for (int i = 1; i < 10; i++) claim(mine, 100 + i, 100);
        assertTrue(FactionPlay.decide(view(person(50, 50, 50, 50), 10_000 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, List.of())).isEmpty(), "over capacity, no claim");
    }

    @Test
    void itsSecondClaimIsTheOtherChunkItsBasesGroundCovers() {
        UUID mine = found(self, "Red", "RED");
        var site = new Territory.Chunk(W, 100, 100);
        claim(mine, 100, 100);
        // Without a second chunk: the nearest free neighbour, west first on a tie (x 99).
        var plainNext = FactionPlay.decide(view(person(50, 50, 50, 50), 1000 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(),
                Map.of(), AuctionBook.EMPTY, List.of())).getFirst();
        assertEquals(new Territory.Chunk(W, 99, 100).key(), plainNext.target());
        // Its base's ground runs on south into (100, 101): that one next.
        var also = new Territory.Chunk(W, 100, 101);
        var v = view(person(50, 50, 50, 50), 1000 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(), Map.of(), AuctionBook.EMPTY, List.of());
        var withAlso = new FactionPlay.View(v.self(), v.personality(), v.factions(), v.powers(), v.claims(), v.safe(), v.captures(), v.coins(), v.settings(),
                v.nowMs(), v.site(), v.standing(), v.healthy(), v.spare(), v.wants(), v.asks(), v.auctions(), v.seen(), v.nearby(), v.setAside(), Optional.of(also));
        var next = FactionPlay.decide(withAlso).getFirst();
        assertEquals(new FactionPlay.Choice(FactionPlay.Kind.CLAIM, also.key(), 100 * Coins.CENT, "Claim land beside its own"), next);
        // Once held, the neighbours again.
        claim(mine, 100, 101);
        var held = view(person(50, 50, 50, 50), 1000 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(), Map.of(), AuctionBook.EMPTY, List.of());
        var heldAlso = new FactionPlay.View(held.self(), held.personality(), held.factions(), held.powers(), held.claims(), held.safe(), held.captures(),
                held.coins(), held.settings(), held.nowMs(), held.site(), held.standing(), held.healthy(), held.spare(), held.wants(), held.asks(),
                held.auctions(), held.seen(), held.nearby(), held.setAside(), Optional.of(also));
        assertTrue(!FactionPlay.decide(heldAlso).getFirst().target().equals(also.key()), "not claimed twice");
    }

    @Test
    void itSellsWhatIsSpareAndBuysOrBidsForWhatItLacksWithinWhatItCanSpare() {
        found(self, "Red", "RED");
        var spare = List.of(new FactionPlay.Spare("minecraft:cobblestone", 128, 300));
        var wants = List.of(new FactionPlay.Want("minecraft:iron_ingot", 8, 50 * Coins.CENT), new FactionPlay.Want("minecraft:diamond", 1, 400 * Coins.CENT));
        var asks = Map.of("minecraft:iron_ingot", 40L * Coins.CENT, "minecraft:diamond", 500L * Coins.CENT);
        var seller = UUID.randomUUID();
        var listing = new AuctionBook.Listing("a1", seller, "minecraft:iron_ingot", "{}", 20 * Coins.CENT, 0, now + H, null, 0);
        var own = new AuctionBook.Listing("a2", self, "minecraft:iron_ingot", "{}", 1 * Coins.CENT, 0, now + H, null, 0);
        var house = new AuctionBook(Map.of("a1", listing, "a2", own), Map.of(), Map.of(), List.of());
        var choices = FactionPlay.decide(view(person(50, 0, 50, 50), 300 * Coins.CENT, Optional.empty(), Optional.empty(), spare, wants, asks, house, List.of()));
        assertEquals(List.of(FactionPlay.Kind.SELL, FactionPlay.Kind.BID), choices.stream().map(FactionPlay.Choice::kind).toList(),
                "sells, bids under the ask; the diamond asks more than it would pay: " + choices);
        assertEquals("a1", choices.get(1).target(), "never its own listing");
        assertEquals(20 * Coins.CENT, choices.get(1).amount(), "the least the house takes");
        var noAuction = FactionPlay.decide(view(person(50, 0, 50, 50), 300 * Coins.CENT, Optional.empty(), Optional.empty(), List.of(), wants, asks, AuctionBook.EMPTY, List.of()));
        assertEquals(List.of(new FactionPlay.Choice(FactionPlay.Kind.BUY, "minecraft:iron_ingot", 8, "Buy 8 minecraft:iron_ingot for 40.00")), noAuction);
        assertTrue(FactionPlay.decide(view(person(50, 0, 50, 50), 120 * Coins.CENT, Optional.empty(), Optional.empty(), List.of(), wants, asks, AuctionBook.EMPTY,
                List.of())).isEmpty(), "the claim reserve is never spent");
    }

    @Test
    void aKindIsSoldOnlyOnceItsSpareMakesALotWorthSelling() {
        found(self, "Red", "RED");
        var under = new FactionPlay.Spare("minecraft:cobblestone", 64, FactionPlay.MIN_LOT - 1);
        var at = new FactionPlay.Spare("minecraft:cobbled_deepslate", 256, FactionPlay.MIN_LOT);
        var over = new FactionPlay.Spare("minecraft:andesite", 320, 3 * FactionPlay.MIN_LOT);
        var none = FactionPlay.decide(view(person(50, 0, 50, 50), 300 * Coins.CENT, Optional.empty(), Optional.empty(), List.of(under), List.of(), Map.of(),
                AuctionBook.EMPTY, List.of()));
        assertTrue(none.stream().noneMatch(c -> c.kind() == FactionPlay.Kind.SELL), "under a lot, it keeps it for now: " + none);
        var some = FactionPlay.decide(view(person(50, 0, 50, 50), 300 * Coins.CENT, Optional.empty(), Optional.empty(), List.of(under, at, over), List.of(),
                Map.of(), AuctionBook.EMPTY, List.of()));
        var sell = some.stream().filter(c -> c.kind() == FactionPlay.Kind.SELL).findFirst().orElseThrow();
        assertEquals("minecraft:cobbled_deepslate,minecraft:andesite", sell.target(), "a lot at the least and over it; not the kind under it");
        assertEquals(4 * FactionPlay.MIN_LOT, sell.amount(), "what those lots pay");
    }

    @Test
    void itScoutsAndCapturesOnlyAnExposedEnemyBorderItSaw() {
        UUID mine = found(self, "Red", "RED");
        UUID blueLeader = UUID.randomUUID();
        UUID blue = found(blueLeader, "Blue", "BLU");
        now += 60 * H; // past both new-faction shields
        claim(mine, 150, 150);
        for (int[] c : new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}) claim(blue, 200 + c[0], 200 + c[1]);
        apply(List.of(PowerRules.set(blueLeader, 1, null, S, 1, now)));
        var west = new Territory.Chunk(W, 199, 200);
        var centre = new Territory.Chunk(W, 200, 200);
        var seenCentre = List.of(new FactionPlay.Sighting(centre.key(), blue.toString(), now - 60_000));
        assertTrue(FactionPlay.decide(view(person(50, 50, 50, 70), 10_000 * Coins.CENT, Optional.empty(), Optional.empty(), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, seenCentre)).stream().noneMatch(FactionPlayTest::raid), "the interior it saw is no way in, and the border it never saw is never chosen");
        var seenWest = List.of(new FactionPlay.Sighting(west.key(), blue.toString(), now - 60_000));
        var scout = FactionPlay.decide(view(person(50, 50, 50, 70), 10_000 * Coins.CENT, Optional.empty(), Optional.of(new Territory.Chunk(W, 150, 150)), List.of(),
                List.of(), Map.of(), AuctionBook.EMPTY, seenWest));
        assertEquals(new FactionPlay.Choice(FactionPlay.Kind.SCOUT, west.key(), 0, "Go to an exposed enemy's border it saw"), scout.getFirst(), "combativeness 70: the raid before its own expansion");
        var capture = FactionPlay.decide(view(person(50, 50, 50, 70), 10_000 * Coins.CENT, Optional.empty(), Optional.of(west), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, seenWest));
        assertEquals(new FactionPlay.Choice(FactionPlay.Kind.CAPTURE, west.key(), 100 * Coins.CENT, "Capture this exposed border chunk"), capture.getFirst());
        var fierce = FactionPlay.decide(view(person(50, 50, 50, 50), 10_000 * Coins.CENT, Optional.empty(), Optional.of(west), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, seenWest));
        assertEquals(FactionPlay.Kind.CLAIM, fierce.getFirst().kind(), "combativeness 50: its own land grows first, the raid after");
        assertTrue(FactionPlay.decide(view(person(50, 50, 50, 20), 10_000 * Coins.CENT, Optional.empty(), Optional.of(west), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, seenWest)).stream().noneMatch(FactionPlayTest::raid), "a peaceable player does not raid");
        // Blue secure again (its power back): nothing to take.
        apply(List.of(PowerRules.set(blueLeader, 10, null, S, 1, now)));
        assertTrue(FactionPlay.decide(view(person(50, 50, 50, 70), 10_000 * Coins.CENT, Optional.empty(), Optional.of(west), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, seenWest)).stream().noneMatch(FactionPlayTest::raid), "a secure faction's land is never chosen");
    }

    @Test
    void aNewFactionsShieldIsRespected() {
        UUID mine = found(self, "Red", "RED");
        UUID blueLeader = UUID.randomUUID();
        UUID blue = found(blueLeader, "Blue", "BLU");
        claim(mine, 150, 150);
        claim(blue, 200, 200);
        claim(blue, 201, 200);
        apply(List.of(PowerRules.set(blueLeader, 1, null, S, 1, now)));
        var edge = new Territory.Chunk(W, 200, 200);
        var seen = List.of(new FactionPlay.Sighting(edge.key(), blue.toString(), now));
        assertTrue(FactionPlay.decide(view(person(50, 50, 50, 70), 10_000 * Coins.CENT, Optional.empty(), Optional.of(edge), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, seen)).stream().noneMatch(FactionPlayTest::raid), "Blue founded within 48 hours: shielded");
    }

    @Test
    void itGoesHomeWhenItsLandIsBeingTakenAndRaidsNothingMeanwhile() {
        UUID mine = found(self, "Red", "RED");
        UUID blueLeader = UUID.randomUUID();
        UUID blue = found(blueLeader, "Blue", "BLU");
        now += 60 * H;
        claim(mine, 150, 150);
        claim(blue, 200, 200);
        claim(blue, 201, 200);
        apply(List.of(PowerRules.set(blueLeader, 1, null, S, 1, now)));
        apply(Factions.home(factions, self, "minecraft:overworld 2400 64 2400", at()).events());
        captures = new Capture.Book(Map.of(), Map.of(mine.toString(), now - 5 * 60_000));
        var edge = new Territory.Chunk(W, 200, 200);
        var seen = List.of(new FactionPlay.Sighting(edge.key(), blue.toString(), now));
        var choices = FactionPlay.decide(view(person(50, 50, 50, 90), 10_000 * Coins.CENT, Optional.empty(), Optional.of(edge), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, seen));
        assertEquals(List.of(new FactionPlay.Choice(FactionPlay.Kind.DEFEND, "minecraft:overworld 2400 64 2400", 0, "Home: its land is being taken")), choices);
        var home = FactionPlay.decide(view(person(50, 50, 50, 90), 10_000 * Coins.CENT, Optional.empty(), Optional.of(new Territory.Chunk(W, 150, 150)), List.of(),
                List.of(), Map.of(), AuctionBook.EMPTY, List.of()));
        assertTrue(home.stream().noneMatch(c -> c.kind() == FactionPlay.Kind.DEFEND), "already home: nothing to go back to");
    }

    @Test
    void anOfficerInvitesAPlayerItMetWhoIsInNoFaction() {
        UUID mine = found(self, "Red", "RED");
        UUID loner = UUID.randomUUID(), taken = UUID.randomUUID(), takenLeader = UUID.randomUUID();
        found(takenLeader, "Blue", "BLU");
        apply(Factions.invite(factions, takenLeader, taken, true, at()).events());
        apply(Factions.join(factions, taken, factions.named("Blue").orElseThrow().id(), S, at()).events());
        nearby = List.of(taken, loner, self);
        var choices = FactionPlay.decide(plain(person(50, 50, 50, 50), 0));
        assertEquals(List.of(new FactionPlay.Choice(FactionPlay.Kind.INVITE, loner.toString(), 0, "Invite a player it met into Red")), choices,
                "only the one in no faction, never itself");
        apply(Factions.invite(factions, self, loner, true, at()).events());
        assertTrue(FactionPlay.decide(plain(person(50, 50, 50, 50), 0)).isEmpty(), "asked once");
        apply(Factions.invite(factions, self, loner, false, at()).events());
        assertTrue(FactionPlay.decide(plain(person(50, 50, 20, 50), 0)).isEmpty(), "an unsociable leader invites nobody");
        assertTrue(mine != null);
    }

    @Test
    void aFoundersSiteBesideAnotherFactionsLandMovesToTheNearestChunkTheRulesAllow() {
        UUID other = UUID.randomUUID();
        UUID blue = found(other, "Blue", "BLU");
        claim(blue, 100, 100);
        found(self, "Red", "RED");
        var site = new Territory.Chunk(W, 101, 100);
        var first = FactionPlay.decide(view(person(50, 0, 50, 50), 100 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, List.of())).getFirst();
        assertEquals(FactionPlay.Kind.CLAIM, first.kind());
        var chunk = Territory.Chunk.of(first.target());
        assertTrue(Math.max(Math.abs(chunk.x() - 100), Math.abs(chunk.z() - 100)) == 2, "a chunk's gap from Blue, as near its site as that allows: " + chunk);
    }

    @Test
    void aPlaceItCouldFindNoWayToIsNotChosenWhileItIsSetAside() {
        UUID mine = found(self, "Red", "RED");
        var site = new Territory.Chunk(W, 100, 100);
        setAside = Set.of(site.key());
        var first = FactionPlay.decide(view(person(50, 0, 50, 50), 100 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(), Map.of(),
                AuctionBook.EMPTY, List.of())).getFirst();
        assertEquals(FactionPlay.Kind.CLAIM, first.kind());
        assertTrue(!first.target().equals(site.key()), "another chunk near it, not the one set aside: " + first.target());
        setAside = Set.of();
        assertEquals(site.key(), FactionPlay.decide(view(person(50, 0, 50, 50), 100 * Coins.CENT, Optional.of(site), Optional.empty(), List.of(), List.of(),
                Map.of(), AuctionBook.EMPTY, List.of())).getFirst().target(), "chosen again once its wait is over");
        assertTrue(mine != null);
    }

    private static boolean raid(FactionPlay.Choice c) { return c.kind() == FactionPlay.Kind.SCOUT || c.kind() == FactionPlay.Kind.CAPTURE; }

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
        var next = new LinkedHashMap<>(claims);
        apply(List.of(WorldEvent.recorded(1, now, W, ClaimLedger.CLAIM, null, faction.toString(), ClaimLedger.chunk(W, x, z), Map.of())));
        assertTrue(claims.size() == next.size() + 1);
    }

    private void apply(List<WorldEvent> events) {
        for (var e : new ArrayList<>(events)) {
            factions = FactionLedger.INSTANCE.apply(factions, e);
            powers = PowerLedger.INSTANCE.apply(powers, e);
            if (ClaimLedger.INSTANCE.kinds().contains(e.kind())) claims = ClaimLedger.INSTANCE.apply(claims, e);
        }
    }

    private Factions.At at() { return new Factions.At(1, now, W); }
}
