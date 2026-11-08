package dev.kindred.core.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the rules of factions and power (M-06c) against the owner's rulebook: section 3's table, its
 * worked example, and acceptance scenarios 1 (power accounting) and 2 (the overclaim trigger), plus who may fight whom,
 * the anti-farming rules, ranks and relations, and that everything comes back the same from the World Record.
 */
class FactionsAndPowerTest {
    private static final FactionSettings S = FactionSettings.DEFAULTS;
    private static final long HOUR = 3_600_000L, LONG_AGO = 0;
    private final List<WorldEvent> record = new ArrayList<>();
    private FactionBook factions = FactionBook.EMPTY;
    private PowerBook powers = PowerBook.EMPTY;
    private final PowerLedger powerLedger = PowerLedger.INSTANCE;
    private long now = 10 * HOUR, id = 1;

    // --- Section 3's table ---

    @Test
    void theTableOfSectionThree() {
        assertEquals(10, S.maxMembers());
        assertEquals(10, S.startPower());
        assertEquals(10, S.maxPower());
        assertEquals(2, S.deathLoss());
        assertEquals(3600, S.regenSeconds());
        assertEquals(80, S.claimCap());
        assertEquals(500, S.spawnExclusion());
        assertEquals(8000, S.border(), "the owner's 8,000-block border for the run, not the rulebook's 24,000");
        UUID p = UUID.randomUUID();
        assertEquals(new PowerBook.Power(10, 0), powers.of(p, S), "starting personal power 10");
        assertEquals(new PowerBook.Power(10, 0), PowerRules.regenerate(new PowerBook.Power(10, 0), 7200, S), "no regeneration above the maximum");
        powers = apply(loss(p, UUID.randomUUID(), 10));
        powers = apply(loss(p, UUID.randomUUID(), 10));
        assertEquals(new PowerBook.Power(0, 0), powers.of(p, S), "personal power minimum 0");
        assertEquals(80, PowerRules.capacity(100, S), "ten full members support 100 but the cap is 80");
        assertEquals(10, PowerRules.capacity(10, S), "a member at 10 supports 10 chunks");
    }

    @Test
    void scenarioOnePowerAccounting() {
        UUID p = UUID.randomUUID();
        // 10 -> 8 on an eligible PvP death.
        powers = apply(loss(p, UUID.randomUUID(), S.deathLoss()));
        assertEquals(8, powers.of(p, S).power());
        // 8 -> 9 after 60 accumulated online minutes, not 15.
        powers = apply(online(p, 15 * 60));
        assertEquals(8, powers.of(p, S).power(), "not after 15 minutes");
        powers = apply(online(p, 20 * 60));
        powers = apply(online(p, 24 * 60 + 59));
        assertEquals(new PowerBook.Power(8, 3599), powers.of(p, S), "one second short of the hour, every second kept");
        powers = apply(online(p, 1));
        assertEquals(new PowerBook.Power(9, 0), powers.of(p, S), "9 after the sixtieth minute");
        powers = apply(online(p, 2 * 3600 + 30));
        assertEquals(new PowerBook.Power(10, 0), powers.of(p, S), "back to the maximum; nothing accrues past it");
    }

    @Test
    void scenarioTwoTheOverclaimTriggerAndTheWorkedExample() {
        // Five members x 10 = 50 power, 45 chunks: safe.
        UUID leader = UUID.randomUUID();
        var faction = found(leader, "Red", "RED");
        List<UUID> members = new ArrayList<>(List.of(leader));
        for (int i = 0; i < 4; i++) members.add(enlist(faction, leader));
        assertEquals(50, PowerRules.factionPower(factions, powers, faction, S));
        assertEquals(0, PowerRules.exposed(45, 50));
        // Four qualifying PvP deaths cost 8: 42 power, 3 chunks exposed.
        for (int i = 0; i < 4; i++) powers = apply(loss(members.get(i), UUID.randomUUID(), S.deathLoss()));
        int power = PowerRules.factionPower(factions, powers, faction, S);
        assertEquals(42, power);
        assertEquals(3, PowerRules.exposed(45, power));
        assertEquals(42, PowerRules.capacity(power, S));
    }

    @Test
    void factionPowerIsRecalculatedAtOnceWhenAMemberLeaves() {
        UUID leader = UUID.randomUUID();
        var faction = found(leader, "Red", "RED");
        UUID member = enlist(faction, leader);
        assertEquals(20, PowerRules.factionPower(factions, powers, faction, S));
        write(Factions.leave(factions, member, at()));
        assertEquals(10, PowerRules.factionPower(factions, powers, faction, S));
        assertEquals(10, powers.of(member, S).power(), "power is the player's own and goes with it");
    }

    // --- Who may fight, and which deaths count ---

    @Test
    void onlyEnemyFactionMembersOutsideASafeZoneMayStrikeAndOnlyTheirKillsCount() {
        UUID red = UUID.randomUUID(), blue = UUID.randomUUID(), loner = UUID.randomUUID(), redMate;
        var redId = found(red, "Red", "RED");
        var blueId = found(blue, "Blue", "BLU");
        redMate = enlist(redId, red);
        seen(loner);
        assertTrue(PowerRules.mayAttack(factions, red, blue, Zone.WILDERNESS, Zone.CLAIMED));
        assertFalse(PowerRules.mayAttack(factions, red, blue, Zone.WILDERNESS, Zone.SAFE), "safe zones are absolute");
        assertFalse(PowerRules.mayAttack(factions, red, redMate, Zone.WILDERNESS, Zone.WILDERNESS), "never a faction-mate");
        assertFalse(PowerRules.mayAttack(factions, red, loner, Zone.WILDERNESS, Zone.WILDERNESS), "never a player who joined no faction (D7)");
        assertFalse(PowerRules.mayAttack(factions, loner, red, Zone.WILDERNESS, Zone.WILDERNESS));

        assertTrue(PowerRules.qualifies(factions, powers, S, blue, red, Zone.WILDERNESS, now).counts());
        assertTrue(PowerRules.qualifies(factions, powers, S, blue, red, Zone.CLAIMED, now).counts());
        assertFalse(PowerRules.qualifies(factions, powers, S, blue, red, Zone.SAFE, now).counts());
        assertFalse(PowerRules.qualifies(factions, powers, S, blue, null, Zone.WILDERNESS, now).counts(), "environmental deaths cost nothing");
        assertFalse(PowerRules.qualifies(factions, powers, S, blue, blue, Zone.WILDERNESS, now).counts());
        assertFalse(PowerRules.qualifies(factions, powers, S, redMate, red, Zone.WILDERNESS, now).counts());
        assertFalse(PowerRules.qualifies(factions, powers, S, loner, red, Zone.WILDERNESS, now).counts());

        // An ally's or a truce's kill counts nothing; a truce needs both sides, an alliance both to wish it.
        write(Factions.wish(factions, red, blueId, Faction.Relation.ALLY, at()));
        assertEquals(Faction.Relation.ENEMY, factions.relation(redId, blueId), "one side alone changes nothing");
        assertTrue(PowerRules.mayAttack(factions, red, blue, Zone.WILDERNESS, Zone.WILDERNESS));
        write(Factions.wish(factions, blue, redId, Faction.Relation.TRUCE, at()));
        assertEquals(Faction.Relation.TRUCE, factions.relation(redId, blueId));
        assertFalse(PowerRules.mayAttack(factions, red, blue, Zone.WILDERNESS, Zone.WILDERNESS));
        assertEquals("by a faction in truce", PowerRules.qualifies(factions, powers, S, blue, red, Zone.WILDERNESS, now).reason());
        write(Factions.wish(factions, blue, redId, Faction.Relation.ALLY, at()));
        assertEquals(Faction.Relation.ALLY, factions.relation(redId, blueId));
        assertEquals("by an ally", PowerRules.qualifies(factions, powers, S, blue, red, Zone.WILDERNESS, now).reason());
        write(Factions.wish(factions, red, blueId, Faction.Relation.ENEMY, at()));
        assertEquals(Faction.Relation.ENEMY, factions.relation(redId, blueId), "either side may break it");
    }

    @Test
    void theSamePairCountsOnceInTwentyMinutesAndNewAccountsNotAtAll() {
        UUID red = UUID.randomUUID(), blue = UUID.randomUUID();
        found(red, "Red", "RED");
        found(blue, "Blue", "BLU");
        assertTrue(PowerRules.qualifies(factions, powers, S, blue, red, Zone.WILDERNESS, now).counts());
        powers = apply(loss(blue, red, S.deathLoss()));
        now += 19 * 60_000;
        assertFalse(PowerRules.qualifies(factions, powers, S, blue, red, Zone.WILDERNESS, now).counts(), "19 minutes later");
        UUID other = enlist(factions.of(red).orElseThrow().id(), red);
        assertTrue(PowerRules.qualifies(factions, powers, S, blue, other, Zone.WILDERNESS, now).counts(), "another killer counts");
        now += 60_000;
        assertTrue(PowerRules.qualifies(factions, powers, S, blue, red, Zone.WILDERNESS, now).counts(), "20 minutes later");

        UUID fresh = UUID.randomUUID();
        record(PowerRules.seen(fresh, 0, now - 59 * 60_000));
        var created = Factions.create(factions, fresh, "Green", "GRN", FactionColour.GREEN, UUID.randomUUID(), at());
        write(created);
        assertFalse(PowerRules.qualifies(factions, powers, S, fresh, red, Zone.WILDERNESS, now).counts(), "a victim seen 59 minutes ago");
        assertFalse(PowerRules.qualifies(factions, powers, S, blue, fresh, Zone.WILDERNESS, now).counts(), "a killer seen 59 minutes ago");
        now += 60_000;
        assertTrue(PowerRules.qualifies(factions, powers, S, fresh, red, Zone.WILDERNESS, now).counts(), "an hour on");
    }

    // --- Factions: founding, joining, ranks ---

    @Test
    void foundingJoiningAndRanks() {
        UUID leader = UUID.randomUUID(), officer = UUID.randomUUID(), member = UUID.randomUUID(), stranger = UUID.randomUUID();
        var id = found(leader, "Red_Hand", "RH");
        assertFalse(Factions.create(factions, leader, "Other", "OT", FactionColour.BLUE, UUID.randomUUID(), at()).accepted(), "one faction at a time");
        assertFalse(Factions.create(factions, stranger, "red_hand", "XX", FactionColour.BLUE, UUID.randomUUID(), at()).accepted(), "names are unique");
        assertFalse(Factions.create(factions, stranger, "Fresh", "rh", FactionColour.BLUE, UUID.randomUUID(), at()).accepted(), "tags are unique");
        assertFalse(Factions.create(factions, stranger, "ab", "AB", FactionColour.BLUE, UUID.randomUUID(), at()).accepted(), "names of 3 to 16");
        assertFalse(Factions.join(factions, stranger, id, S, at()).accepted(), "only by invitation");
        write(Factions.invite(factions, leader, officer, true, at()));
        write(Factions.join(factions, officer, id, S, at()));
        write(Factions.rank(factions, leader, officer, Faction.Rank.OFFICER, at()));
        write(Factions.invite(factions, officer, member, true, at()));
        write(Factions.join(factions, member, id, S, at()));
        assertFalse(Factions.invite(factions, member, stranger, true, at()).accepted(), "a member does not invite");
        assertFalse(Factions.rank(factions, officer, member, Faction.Rank.OFFICER, at()).accepted(), "only the leader sets ranks");
        assertFalse(Factions.kick(factions, officer, leader, at()).accepted(), "an officer cannot kick the leader");
        assertFalse(Factions.colour(factions, officer, FactionColour.RED, at()).accepted(), "only the leader chooses the colour");
        write(Factions.colour(factions, leader, FactionColour.RED, at()));
        assertEquals(FactionColour.RED, factions.get(id).orElseThrow().colour());
        assertFalse(Factions.leave(factions, leader, at()).accepted(), "the leader hands over first");
        write(Factions.rank(factions, leader, officer, Faction.Rank.LEADER, at()));
        assertEquals(officer, factions.get(id).orElseThrow().leader());
        assertEquals(Faction.Rank.OFFICER, factions.get(id).orElseThrow().members().get(leader));
        write(Factions.kick(factions, officer, member, at()));
        assertFalse(factions.get(id).orElseThrow().members().containsKey(member));
        write(Factions.leave(factions, leader, at()));
        write(Factions.leave(factions, officer, at()));
        assertTrue(factions.get(id).isEmpty(), "the last leader leaving disbands it");
        assertTrue(factions.founders().containsKey(leader), "a founder stays a founder (the shield is once a player, M-06e)");
    }

    @Test
    void aFactionIsFullAtTenMembers() {
        UUID leader = UUID.randomUUID();
        var id = found(leader, "Ten", "TEN");
        for (int i = 0; i < 9; i++) enlist(id, leader);
        UUID eleventh = UUID.randomUUID();
        write(Factions.invite(factions, leader, eleventh, true, at()));
        var refused = Factions.join(factions, eleventh, id, S, at());
        assertFalse(refused.accepted());
        assertEquals("Ten is full (10 members)", refused.reason());
    }

    @Test
    void everythingComesBackTheSameFromTheRecord() {
        UUID red = UUID.randomUUID(), blue = UUID.randomUUID();
        var redId = found(red, "Red", "RED");
        found(blue, "Blue", "BLU");
        enlist(redId, red);
        powers = apply(loss(blue, red, 2));
        powers = apply(online(blue, 1234));
        var written = new ArrayList<WorldEvent>();
        for (int i = 0; i < record.size(); i++) written.add(record.get(i).withId(i + 1));
        assertEquals(factions, FactionLedger.INSTANCE.replay(written));
        assertEquals(powers, powerLedger.replay(written));
        assertTrue(powerLedger.check(written, powers).agrees());
        assertEquals(new PowerBook.Power(8, 1234), powers.of(blue, S));
    }

    @Test
    void settingsAreReadAndCheckedFromTheFile() {
        assertEquals(S, FactionSettings.parse(Map.of()));
        assertEquals(S, FactionSettings.parse(S.properties()));
        assertEquals(24_000, FactionSettings.parse(Map.of("border", "24000")).border());
        assertThrows(IllegalArgumentException.class, () -> FactionSettings.parse(Map.of("bogus", "1")));
        assertThrows(IllegalArgumentException.class, () -> FactionSettings.parse(Map.of("maxPower", "x")));
        assertThrows(IllegalArgumentException.class, () -> FactionSettings.parse(Map.of("startPower", "11")));
        assertEquals(FactionColour.LIGHT_GRAY, FactionColour.parse("light grey").orElseThrow());
        assertEquals(16, FactionColour.values().length);
    }

    // --- helpers ---

    private UUID found(UUID leader, String name, String tag) {
        seen(leader);
        UUID faction = UUID.randomUUID();
        write(Factions.create(factions, leader, name, tag, FactionColour.WHITE, faction, at()));
        return faction;
    }

    private UUID enlist(UUID faction, UUID inviter) {
        UUID player = UUID.randomUUID();
        seen(player);
        write(Factions.invite(factions, inviter, player, true, at()));
        write(Factions.join(factions, player, faction, S, at()));
        return player;
    }

    private void seen(UUID player) { record(PowerRules.seen(player, 0, LONG_AGO)); }

    private void write(Factions.Decision decision) {
        assertTrue(decision.accepted(), decision.reason());
        for (var event : decision.events()) record(event);
    }

    private void record(WorldEvent event) {
        record.add(event);
        factions = FactionLedger.INSTANCE.kinds().contains(event.kind()) ? FactionLedger.INSTANCE.apply(factions, event) : factions;
        powers = powerLedger.kinds().contains(event.kind()) ? powerLedger.apply(powers, event) : powers;
    }

    private PowerBook apply(WorldEvent event) {
        record(event);
        return powers;
    }

    private WorldEvent loss(UUID victim, UUID killer, int amount) {
        var settings = new FactionSettings(10, 10, 10, Math.min(amount, 10), 3600, 80, 1200, 3600, 500, 100, 500, 48, 30, 8000, 3, 25, 5, 48);
        return PowerRules.loss(powers, victim, killer, settings, id++, now, "w", "0,64,0");
    }

    private WorldEvent online(UUID player, int seconds) { return PowerRules.online(powers, player, seconds, S, id++, now); }

    private Factions.At at() { return new Factions.At(id++, now, "w"); }
}
