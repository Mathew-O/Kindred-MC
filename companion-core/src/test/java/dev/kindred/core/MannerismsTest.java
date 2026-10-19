package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MannerismsTest {
    private static final Personality NEUTRAL = Personality.defaults();

    @Test
    void autoResolutionIsDeterministicAndFollowsTraitExtremes() {
        UUID id = UUID.randomUUID();
        Mannerisms profile = Mannerisms.defaults(id);
        Personality bold = NEUTRAL.withTrait(Personality.Trait.INITIATIVE, 100).withTrait(Personality.Trait.HUMOR, 100)
                .withTrait(Personality.Trait.CAUTION, 0);
        Personality careful = NEUTRAL.withTrait(Personality.Trait.CAUTION, 100).withTrait(Personality.Trait.HUMOR, 0)
                .withTrait(Personality.Trait.SOCIABILITY, 0).withTrait(Personality.Trait.INITIATIVE, 0);
        for (int i = 0; i < 3; i++) {
            assertTrue(profile.resolve(bold).contains(Mannerisms.Quirk.SPRINTJUMP));
            assertFalse(profile.resolve(bold).contains(Mannerisms.Quirk.STEADY));
            assertTrue(profile.resolve(careful).contains(Mannerisms.Quirk.STEADY));
            assertFalse(profile.resolve(careful).contains(Mannerisms.Quirk.SPRINTJUMP));
        }
        // The tie-break is bounded and independent of traits, so extremes cannot be overturned by identity.
        for (Mannerisms.Quirk quirk : Mannerisms.Quirk.values()) assertTrue(Math.abs(profile.tieBreak(quirk)) <= 6);
    }

    @Test
    void identicalNeutralTraitsStillProduceDifferentCharactersAcrossCompanions() {
        Set<Set<Mannerisms.Quirk>> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) seen.add(Mannerisms.defaults(UUID.randomUUID()).resolve(NEUTRAL));
        assertTrue(seen.size() > 1, "UUID tie-break should produce more than one neutral character");
        UUID id = UUID.randomUUID();
        assertEquals(Mannerisms.defaults(id).resolve(NEUTRAL), Mannerisms.defaults(id).resolve(NEUTRAL));
    }

    @Test
    void explicitSettingsOverrideAutoAndSteadySuppressesOnlyAutoFidgets() {
        UUID id = UUID.randomUUID();
        Personality careful = NEUTRAL.withTrait(Personality.Trait.CAUTION, 100);
        Mannerisms forced = Mannerisms.defaults(id).with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON);
        assertTrue(forced.resolve(careful).contains(Mannerisms.Quirk.SPRINTJUMP));
        assertEquals(1, forced.revision());
        Mannerisms off = forced.with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.OFF)
                .with(Mannerisms.Quirk.LIVELY, Mannerisms.Setting.OFF);
        Personality bouncy = NEUTRAL.withTrait(Personality.Trait.HUMOR, 100).withTrait(Personality.Trait.INITIATIVE, 100);
        assertFalse(off.resolve(bouncy).contains(Mannerisms.Quirk.SPRINTJUMP));
        assertFalse(off.resolve(bouncy).contains(Mannerisms.Quirk.LIVELY));
        Mannerisms steady = Mannerisms.defaults(id).with(Mannerisms.Quirk.STEADY, Mannerisms.Setting.ON);
        assertEquals(Set.of(Mannerisms.Quirk.STEADY), steady.resolve(bouncy));
        Mannerisms steadyButBouncy = steady.with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON);
        assertTrue(steadyButBouncy.resolve(bouncy).containsAll(Set.of(Mannerisms.Quirk.STEADY, Mannerisms.Quirk.SPRINTJUMP)));
    }

    @Test
    void parsingAndValidationRejectUnknownValues() {
        assertEquals(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Quirk.parse("SprintJump"));
        assertEquals(Mannerisms.Setting.ON, Mannerisms.Setting.parse("true"));
        assertEquals(Mannerisms.Setting.OFF, Mannerisms.Setting.parse("off"));
        assertThrows(IllegalArgumentException.class, () -> Mannerisms.Quirk.parse("moonwalk"));
        assertThrows(IllegalArgumentException.class, () -> Mannerisms.Setting.parse("sometimes"));
        assertThrows(IllegalArgumentException.class, () -> new Mannerisms(UUID.randomUUID(), Map.of(), -1));
        Mannerisms partial = new Mannerisms(UUID.randomUUID(), Map.of(Mannerisms.Quirk.LIVELY, Mannerisms.Setting.OFF), 0);
        assertEquals(Mannerisms.Setting.AUTO, partial.setting(Mannerisms.Quirk.SPRINTJUMP));
        assertEquals(Mannerisms.Setting.OFF, partial.setting(Mannerisms.Quirk.LIVELY));
    }

    @Test
    void accommodationsExpireAreNotExtendedByRollbackAndAreClearedByOwnerCommands() {
        UUID id = UUID.randomUUID();
        Personality bouncy = NEUTRAL.withTrait(Personality.Trait.HUMOR, 100).withTrait(Personality.Trait.INITIATIVE, 100);
        MannerismState state = new MannerismState(Mannerisms.defaults(id).with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON));
        assertTrue(state.active(bouncy, 0).contains(Mannerisms.Quirk.SPRINTJUMP));
        assertTrue(state.accommodate(Mannerisms.Quirk.SPRINTJUMP, 1_000, 60_000));
        assertFalse(state.active(bouncy, 1_000).contains(Mannerisms.Quirk.SPRINTJUMP));
        // Repeating inside the window does not push the expiry out.
        assertFalse(state.accommodate(Mannerisms.Quirk.SPRINTJUMP, 50_000, 60_000));
        assertEquals(11_000L, state.accommodationRemaining(Mannerisms.Quirk.SPRINTJUMP, 50_000).orElseThrow());
        // A rolled-back clock cannot revive or lengthen the promise.
        assertFalse(state.active(bouncy, 60_999).contains(Mannerisms.Quirk.SPRINTJUMP));
        assertTrue(state.active(bouncy, 61_000).contains(Mannerisms.Quirk.SPRINTJUMP));
        assertTrue(state.active(bouncy, 10).contains(Mannerisms.Quirk.SPRINTJUMP));
        assertTrue(state.accommodationRemaining(Mannerisms.Quirk.SPRINTJUMP, 10).isEmpty());
        assertTrue(state.accommodate(Mannerisms.Quirk.SPRINTJUMP, 70_000, 60_000));
        Mannerisms edited = state.edit(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON);
        assertEquals(2, edited.revision());
        assertTrue(state.active(bouncy, 71_000).contains(Mannerisms.Quirk.SPRINTJUMP), "an owner command replaces the promise");
        // A reloaded profile drops promises only for quirks whose saved setting changed.
        state.accommodate(Mannerisms.Quirk.SPRINTJUMP, 80_000, 60_000);
        state.accommodate(Mannerisms.Quirk.LIVELY, 80_000, 60_000);
        state.replaceProfile(state.profile().with(Mannerisms.Quirk.LIVELY, Mannerisms.Setting.ON));
        assertFalse(state.active(bouncy, 81_000).contains(Mannerisms.Quirk.SPRINTJUMP));
        assertTrue(state.active(bouncy, 81_000).contains(Mannerisms.Quirk.LIVELY));
        assertThrows(IllegalArgumentException.class, () -> state.accommodate(Mannerisms.Quirk.LIVELY, 0, 31 * 60_000L));
        assertThrows(IllegalArgumentException.class, () -> state.replaceProfile(Mannerisms.defaults(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> state.replaceProfile(Mannerisms.defaults(id)));
    }

    @Test
    void gaitHonorsLeaseAndHardBoundsBeforeAnyHabit() {
        Personality bouncy = NEUTRAL.withTrait(Personality.Trait.HUMOR, 100).withTrait(Personality.Trait.INITIATIVE, 100);
        MannerismState state = new MannerismState(Mannerisms.defaults(UUID.randomUUID()).with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON));
        assertEquals(MannerismState.Gait.WALK, state.gait(bouncy, 0, MannerismState.MovementContext.openGround(false)));
        assertEquals(MannerismState.Gait.SPRINT_JUMP, state.gait(bouncy, 0, MannerismState.MovementContext.openGround(true)));
        assertEquals(MannerismState.Gait.SPRINT, state.gait(bouncy, 0, new MannerismState.MovementContext(true, false, false, false, false, false)));
        assertEquals(MannerismState.Gait.SPRINT, state.gait(bouncy, 0, new MannerismState.MovementContext(true, true, true, false, false, false)));
        assertEquals(MannerismState.Gait.SPRINT, state.gait(bouncy, 0, new MannerismState.MovementContext(true, true, false, true, false, false)));
        assertEquals(MannerismState.Gait.SPRINT, state.gait(bouncy, 0, new MannerismState.MovementContext(true, true, false, false, true, false)));
        assertEquals(MannerismState.Gait.SPRINT, state.gait(bouncy, 0, new MannerismState.MovementContext(true, true, false, false, false, true)));
        state.accommodate(Mannerisms.Quirk.SPRINTJUMP, 0, 1_000);
        assertEquals(MannerismState.Gait.WALK, state.gait(bouncy, 500, MannerismState.MovementContext.openGround(true)));
        MannerismState steady = new MannerismState(Mannerisms.defaults(UUID.randomUUID()).with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.OFF));
        assertEquals(MannerismState.Gait.WALK, steady.gait(bouncy, 0, MannerismState.MovementContext.openGround(true)));
    }

    @Test
    void restoredAccommodationsSurviveRestartWithoutBecomingSettings() {
        Personality bouncy = NEUTRAL.withTrait(Personality.Trait.HUMOR, 100).withTrait(Personality.Trait.INITIATIVE, 100);
        Mannerisms profile = Mannerisms.defaults(UUID.randomUUID()).with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON);
        MannerismState restarted = new MannerismState(profile);
        restarted.restoreAccommodation(Mannerisms.Quirk.SPRINTJUMP, 1_000, 60_000, 30_000);
        assertFalse(restarted.active(bouncy, 30_000).contains(Mannerisms.Quirk.SPRINTJUMP));
        assertEquals(Mannerisms.Setting.ON, restarted.profile().setting(Mannerisms.Quirk.SPRINTJUMP));
        MannerismState late = new MannerismState(profile);
        late.restoreAccommodation(Mannerisms.Quirk.SPRINTJUMP, 1_000, 60_000, 90_000);
        assertTrue(late.active(bouncy, 90_000).contains(Mannerisms.Quirk.SPRINTJUMP));
    }
}
