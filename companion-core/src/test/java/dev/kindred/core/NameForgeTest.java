package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NameForgeTest {
    private static final Personality NEUTRAL = Personality.defaults();

    @Test
    void replayedBriefsProduceIdenticalNamesAndDifferentCompanionsDiffer() {
        UUID id = UUID.randomUUID();
        var brief = new NameForge.Brief(NameForge.Subject.PET, "wolf", id, NEUTRAL, Set.of());
        NameForge.Name first = NameForge.propose(brief).orElseThrow();
        assertEquals(first, NameForge.propose(brief).orElseThrow());
        assertEquals(first.text(), NameForge.candidate(brief, first.attempt()));
        assertEquals(NameForge.Source.FORGE, first.source());
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 48; i++) {
            names.add(NameForge.propose(new NameForge.Brief(NameForge.Subject.PET, "wolf", UUID.randomUUID(), NEUTRAL, Set.of())).orElseThrow().text());
        }
        assertTrue(names.size() > 4, "identical traits should still yield different names across companions");
    }

    @Test
    void personalityChangesFlavorNotValidity() {
        UUID id = UUID.randomUUID();
        Personality playful = NEUTRAL.withTrait(Personality.Trait.HUMOR, 80);
        Personality curious = NEUTRAL.withTrait(Personality.Trait.CURIOSITY, 80);
        assertEquals(NameForge.Flavor.PLAYFUL, NameForge.Flavor.of(playful));
        assertEquals(NameForge.Flavor.DESCRIPTIVE, NameForge.Flavor.of(curious));
        assertEquals(NameForge.Flavor.CLASSIC, NameForge.Flavor.of(NEUTRAL));
        for (NameForge.Subject subject : NameForge.Subject.values()) {
            for (Personality personality : List.of(playful, curious, NEUTRAL)) {
                for (String descriptor : List.of("wolf", "mossy ridge", "oak farmhouse", "iron pickaxe", "???")) {
                    var name = NameForge.propose(new NameForge.Brief(subject, descriptor, id, personality, Set.of())).orElseThrow();
                    assertTrue(name.text().length() >= NameForge.MIN_LENGTH && name.text().length() <= NameForge.MAX_LENGTH, name.text());
                    assertTrue(Character.isUpperCase(name.text().charAt(0)), name.text());
                    assertTrue(name.text().matches("[A-Za-z' -]+"), name.text());
                }
            }
        }
        String place = NameForge.propose(new NameForge.Brief(NameForge.Subject.PLACE, "mossy ridge", id, curious, Set.of())).orElseThrow().text();
        assertTrue(place.endsWith("Ridge"), place);
        String tool = NameForge.propose(new NameForge.Brief(NameForge.Subject.TOOL, "pickaxe", id, curious, Set.of())).orElseThrow().text();
        assertTrue(tool.endsWith("Pickaxe"), tool);
    }

    @Test
    void collisionsAdvanceToTheNextAttemptAndExhaustionIsReportedNotSuffixed() {
        UUID id = UUID.randomUUID();
        var open = new NameForge.Brief(NameForge.Subject.PET, "cat", id, NEUTRAL, Set.of());
        String first = NameForge.candidate(open, 0);
        var taken = new NameForge.Brief(NameForge.Subject.PET, "cat", id, NEUTRAL, Set.of(first.toUpperCase()));
        NameForge.Name next = NameForge.propose(taken).orElseThrow();
        assertNotEquals(first, next.text());
        assertTrue(next.attempt() > 0);
        Set<String> all = new HashSet<>();
        for (int attempt = 0; attempt < NameForge.MAX_ATTEMPTS; attempt++) all.add(NameForge.candidate(open, attempt));
        assertTrue(NameForge.propose(new NameForge.Brief(NameForge.Subject.PET, "cat", id, NEUTRAL, all)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> NameForge.candidate(open, NameForge.MAX_ATTEMPTS));
        assertThrows(IllegalArgumentException.class, () -> new NameForge.Brief(NameForge.Subject.PET, " ", id, NEUTRAL, Set.of()));
    }

    @Test
    void externalNamesPassTheSameRulesAndCarryTheirSource() {
        var owner = NameForge.external("  Sir   Barks-a-Lot ", NameForge.Subject.PET, NameForge.Source.OWNER, Set.of());
        assertEquals("Sir Barks-a-Lot", owner.text());
        assertEquals(NameForge.Source.OWNER, owner.source());
        assertThrows(IllegalArgumentException.class, () -> NameForge.external("biscuit", NameForge.Subject.PET, NameForge.Source.OWNER, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> NameForge.external("Biscuit§k", NameForge.Subject.PET, NameForge.Source.MODEL, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> NameForge.external("B", NameForge.Subject.PET, NameForge.Source.MODEL, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> NameForge.external("A".repeat(33), NameForge.Subject.PET, NameForge.Source.MODEL, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> NameForge.external("Biscuit", NameForge.Subject.PET, NameForge.Source.MODEL, Set.of("biscuit")));
        assertThrows(IllegalArgumentException.class, () -> NameForge.external("Biscuit", NameForge.Subject.PET, NameForge.Source.FORGE, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new NameForge.Name("/give @s diamond", NameForge.Subject.TOOL, NameForge.Source.MODEL, 0));
    }
}
