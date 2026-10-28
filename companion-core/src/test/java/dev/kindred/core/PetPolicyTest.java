package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the rules for taming and keeping pets.
 */
class PetPolicyTest {
    private static final Personality NEUTRAL = Personality.defaults();

    /** Mutable test-only builder; the production request is an immutable record. */
    private static final class Facts {
        PetPolicy.Species species = PetPolicy.Species.WOLF;
        boolean adapter = true, enabled = true, wild = true, visible = true, inside = true, playerOwned, penned, farm;
        boolean affordable = true, active = true, offlineAllowed, ownerOnline = true;
        int cap = PetPolicy.DEFAULT_CAP, owned, ownedOfSpecies, items = 3;

        Facts(PetPolicy.Species species) { this.species = species; }

        PetPolicy.Request build() {
            return new PetPolicy.Request(species, adapter, enabled, cap, owned, ownedOfSpecies, items, wild, visible, inside,
                    playerOwned, penned, farm, affordable, active, offlineAllowed, ownerOnline);
        }

        List<PetPolicy.Blocker> blockers() { return PetPolicy.evaluate(build()).blockers(); }
    }

    @Test
    void aFullyQualifiedWolfOrCatAttemptIsEligibleAndEveryMissingFactBlocksIt() {
        assertTrue(new Facts(PetPolicy.Species.WOLF).blockers().isEmpty());
        assertTrue(new Facts(PetPolicy.Species.CAT).blockers().isEmpty());
        Facts f = new Facts(PetPolicy.Species.WOLF); f.adapter = false;
        assertEquals(List.of(PetPolicy.Blocker.ADAPTER_MISSING), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.enabled = false;
        assertEquals(List.of(PetPolicy.Blocker.PETS_DISABLED), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.owned = 2;
        assertEquals(List.of(PetPolicy.Blocker.CAP_REACHED), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.owned = 1; f.ownedOfSpecies = 1;
        assertEquals(List.of(PetPolicy.Blocker.SPECIES_CAP_REACHED), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.items = 0;
        assertEquals(List.of(PetPolicy.Blocker.INSUFFICIENT_ITEMS), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.wild = false; f.playerOwned = true;
        assertEquals(List.of(PetPolicy.Blocker.CANDIDATE_NOT_WILD, PetPolicy.Blocker.CANDIDATE_PLAYER_OWNED), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.visible = false;
        assertEquals(List.of(PetPolicy.Blocker.CANDIDATE_NOT_VISIBLE), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.inside = false;
        assertEquals(List.of(PetPolicy.Blocker.OUTSIDE_PERMITTED_AREA), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.penned = true;
        assertEquals(List.of(PetPolicy.Blocker.CANDIDATE_PENNED), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.farm = true;
        assertEquals(List.of(PetPolicy.Blocker.CANDIDATE_FARM_POPULATION), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.affordable = false;
        assertEquals(List.of(PetPolicy.Blocker.UNAFFORDABLE), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.active = false;
        assertEquals(List.of(PetPolicy.Blocker.COMPANION_INACTIVE), f.blockers());
        f = new Facts(PetPolicy.Species.WOLF); f.ownerOnline = false;
        assertEquals(List.of(PetPolicy.Blocker.OWNER_OFFLINE_WORK_DISABLED), f.blockers());
        f.offlineAllowed = true;
        assertTrue(f.blockers().isEmpty(), "offline work explicitly allowed");
    }

    @Test
    void unsupportedSpeciesStayBlockedEvenWhenEverythingElseIsReady() {
        for (PetPolicy.Species species : List.of(PetPolicy.Species.HORSE, PetPolicy.Species.DONKEY, PetPolicy.Species.MULE)) {
            assertEquals(List.of(PetPolicy.Blocker.UNSUPPORTED_SPECIES), new Facts(species).blockers(), species.name());
        }
        assertEquals(PetPolicy.Support.SECOND, PetPolicy.catalog().get(PetPolicy.Species.PARROT));
        assertTrue(new Facts(PetPolicy.Species.PARROT).blockers().isEmpty(), "parrot is supported once its adapter is installed");
        Facts tooMany = new Facts(PetPolicy.Species.WOLF); tooMany.cap = PetPolicy.MAX_CAP + 1;
        assertThrows(IllegalArgumentException.class, tooMany::build);
    }

    @Test
    void personalityOnlyNudgesSpeciesInsideTheSharedCeilingAndNeverPermits() {
        Personality fierce = NEUTRAL.withTrait(Personality.Trait.COMBATIVENESS, 100).withTrait(Personality.Trait.CAUTION, 100);
        Personality gentle = NEUTRAL.withTrait(Personality.Trait.COMBATIVENESS, 0).withTrait(Personality.Trait.SOCIABILITY, 100)
                .withTrait(Personality.Trait.CURIOSITY, 100).withTrait(Personality.Trait.HELPFULNESS, 100);
        assertTrue(PetPolicy.preference(PetPolicy.Species.WOLF, fierce) > PetPolicy.preference(PetPolicy.Species.CAT, fierce));
        assertTrue(PetPolicy.preference(PetPolicy.Species.CAT, gentle) > PetPolicy.preference(PetPolicy.Species.WOLF, gentle));
        for (PetPolicy.Species species : PetPolicy.Species.values()) {
            for (Personality personality : List.of(fierce, gentle, NEUTRAL)) {
                assertTrue(Math.abs(PetPolicy.preference(species, personality)) <= TaskPreferences.MAX_PERSONALITY_BIAS);
            }
            assertEquals(0, PetPolicy.preference(species, NEUTRAL), 1e-9);
        }
        // Maximum enthusiasm for wolves does not make a horse supported or a disabled wolf eligible.
        assertFalse(new Facts(PetPolicy.Species.HORSE).blockers().isEmpty());
        Facts disabled = new Facts(PetPolicy.Species.WOLF); disabled.enabled = false;
        assertFalse(disabled.blockers().isEmpty());
    }
}
