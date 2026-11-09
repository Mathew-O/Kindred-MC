package dev.kindred.core.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: the Factions panel's rendering test (M-06l): from staged states, every section is laid out with its
 * colour (the faction's own, amber when claims near power, red when exposed, green when secure or shielded), compact
 * shows only faction, power, coins and the land underfoot, hidden shows nothing; an outsider's panel carries no faction's
 * private numbers; and the payload's text reads back exactly.
 */
class HudTest {
    private static final FactionSettings S = FactionSettings.DEFAULTS;
    private static final String W = Territory.OVERWORLD;
    private final UUID ana = UUID.randomUUID(), ben = UUID.randomUUID(), cy = UUID.randomUUID();
    private FactionBook factions = FactionBook.EMPTY;
    private PowerBook powers = PowerBook.EMPTY;
    private Map<String, String> claims = Map.of();
    private UUID red;

    private void stage(int claimCount, int anaPower) {
        red = UUID.randomUUID();
        var at = new Factions.At(1, 0, W);
        for (var e : Factions.create(factions, ana, "Red_Hand", "RED", FactionColour.RED, red, at).events()) factions = FactionLedger.INSTANCE.apply(factions, e);
        for (var e : Factions.invite(factions, ana, ben, true, at).events()) factions = FactionLedger.INSTANCE.apply(factions, e);
        for (var e : Factions.join(factions, ben, red, S, at).events()) factions = FactionLedger.INSTANCE.apply(factions, e);
        var land = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < claimCount; i++) land.put(ClaimLedger.chunk(W, 100 + i, 100), red.toString());
        claims = land;
        powers = PowerLedger.INSTANCE.apply(powers, PowerRules.set(ana, anaPower, null, S, 1, 0).withId(1));
        powers = PowerLedger.INSTANCE.apply(powers, PowerRules.set(ben, 0, null, S, 1, 0).withId(2));
    }

    private Hud.State state(UUID viewer) {
        var here = Hud.here(viewer, factions, powers, claims, Set.of(), S, ClaimLedger.chunk(W, 100, 100));
        return Hud.build(viewer, factions, powers, claims, Capture.Book.EMPTY, 12_345, S, Set.of(ana, cy), "+12.00 sold oak logs", here.getKey(), here.getValue(),
                List.of("Blue captured a chunk", "Red is vulnerable"), Map.of(red, 1), List.of("iron_ingot -12%"), "In hand: pays 5.10", 3 * 24 * 3_600_000L);
    }

    @Test
    void everySectionWithItsColour() {
        stage(4, 10);
        var lines = Hud.layout(state(ana), Hud.Mode.FULL);
        assertEquals(new Hud.Line("[RED] Red_Hand ★", Hud.Role.FACTION, "red"), lines.get(0), "the faction in its colour, the leader marked");
        assertEquals("1 of 2 online", lines.get(1).text());
        assertEquals("Power 10 / 10", lines.get(2).text());
        assertEquals(new Hud.Line("power 10 · claims 4 / 10", Hud.Role.GREEN, ""), lines.get(3), "secure: green");
        assertTrue(lines.stream().anyMatch(l -> l.text().equals("Coins 123.45  +12.00 sold oak logs")));
        assertTrue(lines.stream().anyMatch(l -> l.text().equals("Your land") && l.role() == Hud.Role.GREEN));
        assertTrue(lines.stream().anyMatch(l -> l.text().equals("» [RED] 10 · 4 · 2 ▲") && l.role() == Hud.Role.FACTION), "standings with the own row and its arrow");
        assertTrue(lines.stream().anyMatch(l -> l.text().equals("Red is vulnerable") && l.role() == Hud.Role.AMBER), "notices");
        assertTrue(lines.stream().anyMatch(l -> l.text().equals("iron_ingot -12%")), "the market's movers");
        assertTrue(lines.stream().anyMatch(l -> l.text().equals("In hand: pays 5.10")));
    }

    @Test
    void amberNearPowerRedWhenExposed() {
        stage(9, 10);
        assertEquals(Hud.Role.AMBER, Hud.layout(state(ana), Hud.Mode.FULL).get(3).role(), "claims within two of power: amber");
        factions = FactionBook.EMPTY;
        stage(12, 10);
        var line = Hud.layout(state(ana), Hud.Mode.FULL).get(3);
        assertEquals(new Hud.Line("power 10 · claims 12 / 10 · 2 exposed", Hud.Role.RED, ""), line, "exposed: red, with the count");
        var here = Hud.here(cy, factions, powers, claims, Set.of(), S, ClaimLedger.chunk(W, 100, 100));
        assertEquals(Map.entry("Red_Hand (enemy), exposed, capturable", Hud.Role.RED), here);
        assertEquals(Map.entry("Safe zone", Hud.Role.GREEN), Hud.here(cy, factions, powers, claims, Set.of("x"), S, "x"));
        assertEquals(Map.entry("Wilderness", Hud.Role.DIM), Hud.here(cy, factions, powers, claims, Set.of(), S, "y"));
    }

    @Test
    void shieldsAreGreenAndCountDown() {
        stage(1, 10);
        var fresh = Hud.build(ana, factions, powers, claims, Capture.Book.EMPTY, 0, S, Set.of(ana), "", "Wilderness", Hud.Role.DIM, List.of(), Map.of(), List.of(), "", 60_000);
        assertTrue(Hud.layout(fresh, Hud.Mode.FULL).contains(new Hud.Line("New-faction shield 47h 59m", Hud.Role.GREEN, "")));
    }

    @Test
    void compactAndHidden() {
        stage(4, 10);
        var compact = Hud.layout(state(ana), Hud.Mode.COMPACT);
        assertEquals(List.of("[RED] Red_Hand ★", "1 of 2 online", "Power 10 / 10", "power 10 · claims 4 / 10", "Coins 123.45  +12.00 sold oak logs", "Your land"),
                compact.stream().map(Hud.Line::text).toList());
        assertTrue(Hud.layout(state(ana), Hud.Mode.HIDDEN).isEmpty());
        assertEquals(Hud.Mode.COMPACT, Hud.Mode.FULL.next());
        assertEquals(Hud.Mode.FULL, Hud.Mode.HIDDEN.next());
    }

    @Test
    void anOutsidersPanelCarriesNoPrivateNumbersAndThePayloadReadsBack() {
        stage(4, 10);
        var outsider = state(cy);
        assertTrue(outsider.own().isEmpty(), "no faction of its own");
        assertTrue(outsider.notices().isEmpty(), "no faction's notices");
        String text = Hud.encode(outsider);
        assertFalse(text.contains("online") || text.contains("own="), "nobody's members online: " + text);
        assertEquals("[RED] 10 · 4 · 2 ▲", Hud.layout(outsider, Hud.Mode.FULL).stream().filter(l -> l.role() == Hud.Role.FACTION).findFirst().orElseThrow().text(),
                "everyone's public numbers: power, claims, members");
        assertEquals(outsider, Hud.decode(text));
        assertEquals(state(ana), Hud.decode(Hud.encode(state(ana))));
    }
}
