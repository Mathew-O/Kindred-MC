package dev.kindred.core.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.record.ClaimLedger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the safe zones (M-06f): the hub's chunks round spawn, the zone kept in and rebuilt from the
 * World Record, no claim, PvP or change by anyone but an operator inside it, and the hub's plan from the generators.
 */
class SafeZonesTest {
    @Test
    void theHubCoversSpawnAndComesBackFromTheRecord() {
        var hub = SafeZones.hub(64);
        assertTrue(hub.contains(ClaimLedger.chunk(Territory.OVERWORLD, 0, 0)));
        assertTrue(hub.contains(ClaimLedger.chunk(Territory.OVERWORLD, -4, 0)), "x -64..-49 is within 64");
        assertFalse(hub.contains(ClaimLedger.chunk(Territory.OVERWORLD, 5, 0)), "x 80.. is not");
        assertFalse(hub.contains(ClaimLedger.chunk(Territory.OVERWORLD, 4, 4)), "a corner beyond 64 on the diagonal");
        var event = SafeZones.zone(SafeZones.HUB, hub, null, 1, 2).withId(1);
        var book = SafeZones.INSTANCE.replay(List.of(event));
        assertEquals(hub, book.chunks());
        var smaller = SafeZones.zone(SafeZones.HUB, SafeZones.hub(16), null, 2, 3).withId(2);
        assertEquals(SafeZones.hub(16), SafeZones.INSTANCE.replay(List.of(event, smaller)).chunks(), "a later zone of the same name replaces it");
        var removed = SafeZones.zone(SafeZones.HUB, Set.of(), null, 3, 4).withId(3);
        assertTrue(SafeZones.INSTANCE.replay(List.of(event, removed)).chunks().isEmpty());
    }

    @Test
    void nobodyFightsClaimsOrBuildsInASafeZone() {
        var settings = FactionSettings.DEFAULTS;
        UUID red = UUID.randomUUID(), blue = UUID.randomUUID();
        var book = FactionBook.EMPTY;
        var at = new Factions.At(1, 1, Territory.OVERWORLD);
        for (var e : Factions.create(book, red, "Red", "RED", FactionColour.RED, UUID.randomUUID(), at).events()) book = FactionLedger.INSTANCE.apply(book, e);
        for (var e : Factions.create(book, blue, "Blue", "BLU", FactionColour.BLUE, UUID.randomUUID(), at).events()) book = FactionLedger.INSTANCE.apply(book, e);
        assertFalse(PowerRules.mayAttack(book, red, blue, Zone.SAFE, Zone.WILDERNESS));
        assertFalse(PowerRules.qualifies(book, PowerBook.EMPTY, settings, blue, red, Zone.SAFE, 1).counts());
        var safe = SafeZones.hub(settings.safeRadius());
        var spawn = new Territory.Chunk(Territory.OVERWORLD, 0, 0);
        assertFalse(Territory.claim(book, PowerBook.EMPTY, Map.of(), safe, 50_000, settings, red, spawn, at).accepted());
        assertFalse(Territory.mayChange(book, Map.of(), safe, red, false, spawn));
        assertTrue(Territory.mayChange(book, Map.of(), safe, red, true, spawn), "an operator");
        assertFalse(Territory.mayCross(Map.of(), safe, new Territory.Chunk(Territory.OVERWORLD, 10, 0), spawn), "nothing flows in from outside");
    }

    @Test
    void theHubIsPlannedFromTheBuildersGenerators() {
        var plan = SafeZones.plan();
        assertEquals(List.of("spawn lighthouse", "market district"), plan.stream().map(SafeZones.Part::name).toList());
        for (var part : plan) {
            assertFalse(part.blueprint().blocks().isEmpty());
            assertTrue(part.blueprint().source().startsWith("kindred:generator/") || !part.blueprint().source().isEmpty(), part.blueprint().source());
            for (var block : part.blueprint().blocks()) {
                int x = part.origin().x() + block.at().x(), z = part.origin().z() + block.at().z();
                assertTrue(Math.sqrt((double) x * x + (double) z * z) <= FactionSettings.DEFAULTS.safeRadius(), part.name() + " inside the hub at " + x + "," + z);
            }
        }
    }
}
