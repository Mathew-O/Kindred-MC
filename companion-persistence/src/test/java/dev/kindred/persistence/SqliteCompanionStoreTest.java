package dev.kindred.persistence;

import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.Companion;
import dev.kindred.core.LifecycleState;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.OperatingMode;
import dev.kindred.core.Personality;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.WorkSetup;
import dev.kindred.scheduler.AccountingPeriod;
import dev.kindred.scheduler.BudgetState;
import dev.kindred.scheduler.PeriodTransition;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SqliteCompanionStoreTest {
    @TempDir Path directory;
    private final List<SqliteCompanionStore> opened = new ArrayList<>();
    private final AccountingPeriod period = AccountingPeriod.on(LocalDate.of(2026, 10, 13), ZoneOffset.UTC);

    @AfterEach
    void closeStores() {
        for (SqliteCompanionStore store : opened) store.closeAsync().join();
    }

    @Test
    void concurrentCreationRetriesAndNewOperationsReturnOneOwnedIdentity() {
        SqliteCompanionStore store = open(10);
        CreationRequest request = request(UUID.randomUUID());
        List<StoredCompanion> results = IntStream.range(0, 30).parallel()
                .mapToObj(ignored -> store.create(request).join()).toList();
        assertEquals(1, results.stream().map(value -> value.companion().id()).distinct().count());
        StoredCompanion repeatedCommand = store.create(request(request.ownerId())).join();
        assertEquals(results.getFirst(), repeatedCommand);
        assertEquals(1, store.list().join().size());
        assertEquals(LifecycleState.SLEEPING, repeatedCommand.companion().lifecycle());
    }

    @Test
    void ownersRemainSeparateAndStolenOperationCannotReadOrCreate() {
        SqliteCompanionStore store = open(10);
        CreationRequest first = request(UUID.randomUUID());
        StoredCompanion a = store.create(first).join();
        UUID otherOwner = UUID.randomUUID();
        CreationRequest stolen = new CreationRequest(first.operationId(), otherOwner, "Stolen",
                first.worldId(), first.dimension());
        assertCause(SecurityException.class, store.create(stolen));
        assertTrue(store.findByOwner(otherOwner).join().isEmpty());
        StoredCompanion b = store.create(request(otherOwner)).join();
        assertNotEquals(a.companion().id(), b.companion().id());
        assertEquals(Optional.of(a), store.findByOwner(first.ownerId()).join());
        assertEquals(Optional.of(b), store.findByOwner(otherOwner).join());
    }

    @Test
    void serverQuotaDoesNotRejectIdempotentRetry() {
        SqliteCompanionStore store = open(1);
        CreationRequest request = request(UUID.randomUUID());
        StoredCompanion created = store.create(request).join();
        assertEquals(created, store.create(request(request.ownerId())).join());
        assertCause(IllegalStateException.class, store.create(request(UUID.randomUUID())));
        assertEquals(1, store.list().join().size());
    }

    @Test
    void compareAndSetProtectsConcurrentStateAndIdentity() {
        SqliteCompanionStore store = open(10);
        StoredCompanion original = store.create(request(UUID.randomUUID())).join();
        Companion stopped = original.companion().withStopLatched(true).withLifecycle(LifecycleState.PAUSED)
                .withRevisions(1, 0);
        StoredCompanion saved = store.save(stopped, original.revision()).join();
        assertEquals(1, saved.revision());
        assertCause(ConcurrentModificationException.class, store.save(original.companion(), original.revision()));
        Companion changedOwner = new Companion(stopped.id(), UUID.randomUUID(), stopped.displayName(), stopped.worldId(),
                stopped.dimension(), null, stopped.lifecycle(), 1, 0, true);
        assertCause(IllegalArgumentException.class, store.save(changedOwner, saved.revision()));
        assertEquals(saved, store.find(stopped.id()).join().orElseThrow());
    }

    @Test
    void stopSurvivesReopenAndActiveMetadataBecomesDormant() {
        SqliteCompanionStore store = open(10);
        StoredCompanion stopped = store.create(request(UUID.randomUUID())).join();
        store.save(stopped.companion().withStopLatched(true).withLifecycle(LifecycleState.PAUSED), 0).join();
        StoredCompanion active = store.create(request(UUID.randomUUID())).join();
        store.save(active.companion().withLifecycle(LifecycleState.ACTIVE), 0).join();
        store.closeAsync().join();
        SqliteCompanionStore reopened = open(10);
        Companion persistedStop = reopened.find(stopped.companion().id()).join().orElseThrow().companion();
        assertTrue(persistedStop.stopLatched());
        assertEquals(LifecycleState.PAUSED, persistedStop.lifecycle());
        StoredCompanion persistedActive = reopened.find(active.companion().id()).join().orElseThrow();
        assertEquals(LifecycleState.SLEEPING, persistedActive.companion().lifecycle());
        assertEquals(2, persistedActive.revision());
    }

    @Test
    void crashReservationChargesOnceWithoutInventingObservedTime() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        BudgetState empty = store.openBudget(id, period).join();
        BudgetState reserved = reserve(empty, UUID.randomUUID(), period.startInclusive());
        store.saveBudget(reserved, empty.sequence()).join();
        // Closing preserves an unresolved slice, equivalent to the durable state left by a crash.
        store.closeAsync().join();
        SqliteCompanionStore reopened = open(10);
        BudgetState recovered = reopened.findBudget(id, period.startInclusive()).join().orElseThrow();
        assertEquals(5000, recovered.chargedMillis());
        assertEquals(0, recovered.observedActiveMillis());
        assertTrue(recovered.outstanding().isEmpty());
        assertEquals(2, recovered.sequence());
        assertEquals(1, reopened.eventsSince(id, 0, 100).join().stream()
                .filter(event -> event.type().equals("RESERVATION_RECOVERED")).count());
        reopened.closeAsync().join();
        SqliteCompanionStore twice = open(10);
        assertEquals(recovered, twice.openBudget(id, period).join());
    }

    @Test
    void renewalAtomicallyReconcilesSliceAndRejectsStaleWriter() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        BudgetState empty = store.openBudget(id, period).join();
        UUID first = UUID.randomUUID();
        BudgetState reserved = store.saveBudget(reserve(empty, first, period.startInclusive()), 0).join();
        BudgetState renewed = reserved.renew(first, Duration.ofMillis(2500), UUID.randomUUID(), Duration.ofSeconds(5),
                Duration.ofMinutes(2), period.startInclusive().plusMillis(2500), period.endExclusive());
        assertEquals(3, renewed.sequence());
        store.saveBudget(renewed, reserved.sequence()).join();
        assertCause(ConcurrentModificationException.class, store.saveBudget(reserved.settle(first, Duration.ofSeconds(1)), 1));
        BudgetState settled = renewed.settle(renewed.outstanding().orElseThrow().id(), Duration.ofMillis(1500));
        store.saveBudget(settled, renewed.sequence()).join();
        assertEquals(4000, settled.chargedMillis());
        assertEquals(4000, settled.observedActiveMillis());
        assertEquals(settled, store.findBudget(id, period.startInclusive()).join().orElseThrow());
    }

    @Test
    void overlappingChangedAndRolledBackPeriodsCannotResetAllowance() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        BudgetState initial = store.openBudget(id, period).join();
        assertEquals(initial, store.openBudget(id, period).join());
        AccountingPeriod changed = new AccountingPeriod(period.localDate(), ZoneOffset.ofHours(-4),
                period.startInclusive(), period.endExclusive());
        assertCause(IllegalArgumentException.class, store.openBudget(id, changed));
        AccountingPeriod overlap = new AccountingPeriod(period.localDate(), ZoneOffset.UTC,
                period.startInclusive().plusSeconds(1), period.endExclusive().plusSeconds(1));
        assertCause(IllegalArgumentException.class, store.openBudget(id, overlap));
        AccountingPeriod next = AccountingPeriod.on(period.localDate().plusDays(1), ZoneOffset.UTC);
        store.openBudget(id, next).join();
        assertCause(IllegalArgumentException.class, store.openBudget(id, period));
        assertCause(IllegalArgumentException.class, store.saveBudget(reserve(initial, UUID.randomUUID(), period.startInclusive()), 0));
        assertEquals(initial, store.findBudget(id, period.startInclusive()).join().orElseThrow());
    }

    @Test
    void outstandingSlicePreventsOpeningAnotherPeriod() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        BudgetState initial = store.openBudget(id, period).join();
        BudgetState reserved = reserve(initial, UUID.randomUUID(), period.startInclusive());
        store.saveBudget(reserved, 0).join();
        AccountingPeriod next = AccountingPeriod.on(period.localDate().plusDays(1), ZoneOffset.UTC);
        assertCause(IllegalStateException.class, store.openBudget(id, next));
        store.saveBudget(reserved.settle(reserved.outstanding().orElseThrow().id(), Duration.ZERO), 1).join();
        assertEquals(next, store.openBudget(id, next).join().period());
    }

    @Test
    void chargeableBudgetOpensEachPeriodOnceAndNeitherARestartNorAZoneEditReplenishesIt() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        Instant now = period.startInclusive().plus(Duration.ofHours(1));
        ChargeableBudget first = store.chargeableBudget(id, now, ZoneOffset.UTC).join();
        assertEquals(PeriodTransition.Kind.OPEN, first.transition().kind());
        BudgetState empty = first.budget().orElseThrow();
        assertEquals(BudgetState.empty(id, period), empty);
        BudgetState reserved = store.saveBudget(reserve(empty, UUID.randomUUID(), now), 0).join();
        BudgetState settled = store.saveBudget(reserved.settle(reserved.outstanding().orElseThrow().id(),
                Duration.ofSeconds(2)), 1).join();
        assertEquals(new ChargeableBudget(PeriodTransition.current(period), Optional.of(settled)),
                store.chargeableBudget(id, now.plusSeconds(60), ZoneOffset.UTC).join());
        store.closeAsync().join();
        SqliteCompanionStore reopened = open(10);
        assertEquals(Optional.of(settled), reopened.chargeableBudget(id, now.plusSeconds(120), ZoneOffset.UTC).join().budget());
        // A zone edit mid-period is charged to the saved period to its end.
        ChargeableBudget edited = reopened.chargeableBudget(id, now.plus(Duration.ofHours(2)), ZoneId.of("Asia/Tokyo")).join();
        assertEquals(PeriodTransition.Kind.CURRENT, edited.transition().kind());
        assertEquals(2000, edited.budget().orElseThrow().chargedMillis());
        AccountingPeriod next = AccountingPeriod.on(period.localDate().plusDays(1), ZoneOffset.UTC);
        ChargeableBudget tomorrow = reopened.chargeableBudget(id, period.endExclusive(), ZoneOffset.UTC).join();
        assertEquals(new ChargeableBudget(PeriodTransition.open(next), Optional.of(BudgetState.empty(id, next))), tomorrow);
        assertEquals(settled, reopened.findBudget(id, period.startInclusive()).join().orElseThrow());
        assertEquals(new ChargeableBudget(PeriodTransition.current(next), tomorrow.budget()),
                reopened.chargeableBudget(id, period.endExclusive(), ZoneOffset.UTC).join());
    }

    @Test
    void chargeableBudgetWritesNothingWhileTheClockIsBehindOrAZoneEditWaits() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        BudgetState opened = store.chargeableBudget(id, period.startInclusive(), ZoneOffset.UTC).join().budget().orElseThrow();
        ChargeableBudget behind = store.chargeableBudget(id, period.startInclusive().minusSeconds(1), ZoneOffset.UTC).join();
        assertEquals(new ChargeableBudget(PeriodTransition.clockBehind(period.startInclusive()), Optional.empty()), behind);
        ZoneId newYork = ZoneId.of("America/New_York");
        ChargeableBudget waiting = store.chargeableBudget(id, period.endExclusive().plus(Duration.ofHours(1)), newYork).join();
        assertEquals(new ChargeableBudget(PeriodTransition.zonePending(Instant.parse("2026-10-14T04:00:00Z")), Optional.empty()),
                waiting);
        assertTrue(store.findBudget(id, AccountingPeriod.on(period.localDate(), newYork).startInclusive()).join().isEmpty());
        assertEquals(opened, store.findBudget(id, period.startInclusive()).join().orElseThrow());
        AccountingPeriod local = AccountingPeriod.on(LocalDate.of(2026, 10, 14), newYork);
        BudgetState fresh = store.chargeableBudget(id, waiting.transition().opensAt().orElseThrow(), newYork).join().budget().orElseThrow();
        assertEquals(BudgetState.empty(id, local), fresh);
        // An unsettled slice left in the latest period is an inconsistency, not a transition.
        store.saveBudget(reserve(fresh, UUID.randomUUID(), local.startInclusive()), 0).join();
        assertCause(IllegalStateException.class, store.chargeableBudget(id, local.endExclusive(), newYork));
        assertThrows(IllegalArgumentException.class, () -> new ChargeableBudget(PeriodTransition.current(period), Optional.empty()));
    }

    @Test
    void forgedDecreasingTotalsAndChangedPeriodsAreRejected() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        BudgetState initial = store.openBudget(id, period).join();
        BudgetState reservation = reserve(initial, UUID.randomUUID(), period.startInclusive());
        store.saveBudget(reservation, 0).join();
        BudgetState settled = reservation.settle(reservation.outstanding().orElseThrow().id(), Duration.ofSeconds(2));
        store.saveBudget(settled, 1).join();
        BudgetState reset = new BudgetState(id, period, 0, 0, 3, Optional.empty());
        assertCause(IllegalArgumentException.class, store.saveBudget(reset, 2));
        AccountingPeriod differentEnd = new AccountingPeriod(period.localDate(), period.zone(), period.startInclusive(),
                period.endExclusive().plusSeconds(1));
        BudgetState changed = new BudgetState(id, differentEnd, 2000, 2000, 3, Optional.empty());
        assertCause(IllegalArgumentException.class, store.saveBudget(changed, 2));
    }

    @Test
    void queuedWritesAreOrderedAndCloseFlushesEveryAcceptedOperation() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        List<CompletableFuture<JournalEvent>> writes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            writes.add(store.appendEvent(id, period.startInclusive(), "ORDER", Integer.toString(i)));
        }
        CompletableFuture<Void> closing = store.closeAsync();
        assertSame(closing, store.closeAsync());
        assertCause(IllegalStateException.class, store.list());
        closing.join();
        assertTrue(writes.stream().allMatch(CompletableFuture::isDone));
        SqliteCompanionStore reopened = open(10);
        List<JournalEvent> events = reopened.eventsSince(id, 0, 1000).join().stream()
                .filter(event -> event.type().equals("ORDER")).toList();
        assertEquals(100, events.size());
        for (int i = 0; i < 100; i++) assertEquals(Integer.toString(i), events.get(i).detail());
        List<JournalEvent> later = reopened.eventsSince(id, events.get(50).sequence(), 1000).join();
        assertEquals(49, later.size());
    }

    @Test
    void separateOwnersHaveSeparateJournalQueries() {
        SqliteCompanionStore store = open(10);
        UUID first = store.create(request(UUID.randomUUID())).join().companion().id();
        UUID second = store.create(request(UUID.randomUUID())).join().companion().id();
        store.appendEvent(first, period.startInclusive(), "PRIVATE", "first owner fact").join();
        assertTrue(store.eventsSince(second, 0, 100).join().stream().noneMatch(event -> event.type().equals("PRIVATE")));
    }

    @Test
    void simultaneousStoreOpenIsRejectedAndLockReleasesOnClose() {
        SqliteCompanionStore store = open(10);
        assertThrows(CompletionException.class, () -> SqliteCompanionStore.open(path(), 10).join());
        store.closeAsync().join();
        assertNotNull(open(10));
    }

    @Test
    void unknownFutureSchemaIsRefusedWithoutDowngrade() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }
        assertCause(IllegalStateException.class, SqliteCompanionStore.open(path(), 10));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement();
             var row = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(row.next());
            assertEquals(99, row.getInt(1));
        }
    }

    @Test
    void persistenceRejectsPrecisionLossAndRemovedStateRevival() {
        SqliteCompanionStore store = open(10);
        StoredCompanion created = store.create(request(UUID.randomUUID())).join();
        Companion removed = created.companion().withLifecycle(LifecycleState.REMOVED);
        store.save(removed, 0).join();
        assertCause(IllegalStateException.class, store.save(created.companion(), 1));
        AccountingPeriod fractional = new AccountingPeriod(period.localDate(), period.zone(),
                period.startInclusive().plusNanos(1), period.endExclusive());
        assertThrows(IllegalArgumentException.class, () -> store.openBudget(created.companion().id(), fractional));
    }

    private SqliteCompanionStore open(int quota) {
        SqliteCompanionStore store = SqliteCompanionStore.open(path(), quota).join();
        opened.add(store);
        return store;
    }

    @Test
    void socialPreferencesAndTraitRevisionsSurviveRestart() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        SocialProfile defaults = store.profiles().join().getFirst();
        assertEquals(SocialProfile.defaults(id), defaults);
        SocialProfile publicProfile = store.saveProfile(defaults.withPublic(true), 0).join();
        SocialProfile outgoing = store.saveProfile(publicProfile.withTrait(Personality.Trait.SOCIABILITY, 80), 1).join();
        SocialProfile muted = store.saveProfile(outgoing.withAmbient(false), 2).join();
        assertCause(ConcurrentModificationException.class, store.saveProfile(defaults.withPublic(false), 0));
        store.closeAsync().join();
        assertEquals(List.of(muted), open(10).profiles().join());
    }

    @Test
    void versionOneMigrationPreservesIdentityStopAndChargedAllowance() throws Exception {
        SqliteCompanionStore store = open(10);
        StoredCompanion created = store.create(request(UUID.randomUUID())).join();
        UUID id = created.companion().id();
        StoredCompanion stopped = store.save(created.companion().withStopLatched(true), 0).join();
        BudgetState empty = store.openBudget(id, period).join();
        BudgetState reservation = reserve(empty, UUID.randomUUID(), period.startInclusive());
        store.saveBudget(reservation, 0).join();
        BudgetState used = reservation.settle(reservation.outstanding().orElseThrow().id(), Duration.ofSeconds(1));
        store.saveBudget(used, 1).join();
        store.closeAsync().join();
        // The remaining schema and records are exactly the pre-social (v1) foundation.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE social_profiles");
            statement.execute("DROP TABLE mannerism_profiles");
            dropSchemaFive(statement);
            statement.execute("PRAGMA user_version = 1");
        }
        SqliteCompanionStore upgraded = open(10);
        assertEquals(stopped, upgraded.find(id).join().orElseThrow());
        assertEquals(used, upgraded.findBudget(id, period.startInclusive()).join().orElseThrow());
        assertEquals(List.of(SocialProfile.defaults(id)), upgraded.profiles().join());
        assertEquals(List.of(Mannerisms.defaults(id)), upgraded.mannerisms().join());
    }

    @Test
    void mannerismsPersistAcrossReopenAndRejectStaleOrForgedWrites() {
        SqliteCompanionStore store = open(10);
        StoredCompanion created = store.create(request(UUID.randomUUID())).join();
        UUID id = created.companion().id();
        assertEquals(List.of(Mannerisms.defaults(id)), store.mannerisms().join());
        Mannerisms edited = store.saveMannerisms(Mannerisms.defaults(id).with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON), 0).join();
        edited = store.saveMannerisms(edited.with(Mannerisms.Quirk.STEADY, Mannerisms.Setting.OFF), 1).join();
        assertCause(ConcurrentModificationException.class, store.saveMannerisms(Mannerisms.defaults(id).with(Mannerisms.Quirk.LIVELY, Mannerisms.Setting.OFF), 0));
        assertCause(IllegalArgumentException.class, store.saveMannerisms(new Mannerisms(id, edited.settings(), 9), 2));
        SocialProfile profile = store.profiles().join().getFirst();
        store.closeAsync().join();
        SqliteCompanionStore reopened = open(10);
        assertEquals(List.of(edited), reopened.mannerisms().join());
        assertEquals(profile, reopened.profiles().join().getFirst(), "quirk edits never touch the social profile or traits");
        reopened.save(created.companion().withLifecycle(LifecycleState.REMOVED), 0).join();
        assertCause(IllegalStateException.class, reopened.saveMannerisms(edited.with(Mannerisms.Quirk.LIVELY, Mannerisms.Setting.ON), 2));
    }

    @Test
    void schemaThreeDatabasesGainAutoMannerismsWithoutTouchingProfilesOrAllowance() throws Exception {
        SqliteCompanionStore store = open(10);
        StoredCompanion original = store.create(request(UUID.randomUUID())).join();
        UUID id = original.companion().id();
        SocialProfile profile = store.saveProfile(SocialProfile.defaults(id).withTrait(Personality.Trait.HUMOR, 88), 0).join();
        BudgetState empty = store.openBudget(id, period).join();
        BudgetState reservation = reserve(empty, UUID.randomUUID(), period.startInclusive());
        store.saveBudget(reservation, 0).join();
        BudgetState used = reservation.settle(reservation.outstanding().orElseThrow().id(), Duration.ofSeconds(2));
        store.saveBudget(used, 1).join();
        store.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE mannerism_profiles");
            dropSchemaFive(statement);
            statement.execute("PRAGMA user_version = 3");
        }
        SqliteCompanionStore upgraded = open(10);
        assertEquals(original, upgraded.find(id).join().orElseThrow());
        assertEquals(profile, upgraded.profiles().join().getFirst());
        assertEquals(used, upgraded.findBudget(id, period.startInclusive()).join().orElseThrow());
        assertEquals(List.of(Mannerisms.defaults(id)), upgraded.mannerisms().join());
    }

    @Test
    void removedCompanionsAndForgedPersonalityRevisionsCannotChangeProfiles() {
        SqliteCompanionStore store = open(10);
        StoredCompanion created = store.create(request(UUID.randomUUID())).join();
        UUID id = created.companion().id();
        SocialProfile forged = new SocialProfile(id, new Personality(50, 50, 50, 100, 50, 0), true, false, 1);
        assertCause(IllegalArgumentException.class, store.saveProfile(forged, 0));
        store.save(created.companion().withLifecycle(LifecycleState.REMOVED), 0).join();
        assertCause(IllegalStateException.class, store.saveProfile(SocialProfile.defaults(id).withPublic(true), 0));
    }

    @Test
    void preferenceWeightsPersistWithoutChangingTheExistingSocialTraits() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        SocialProfile profile = SocialProfile.defaults(id);
        for (Personality.Trait trait : List.of(Personality.Trait.COMBATIVENESS, Personality.Trait.HELPFULNESS,
                Personality.Trait.FARMING, Personality.Trait.MINING)) {
            profile = store.saveProfile(profile.withTrait(trait, 70), profile.revision()).join();
        }
        assertEquals(50, profile.personality().sociability());
        store.closeAsync().join();
        assertEquals(profile, open(10).profiles().join().getFirst());
    }

    @Test
    void schemaTwoProfilesGainNeutralPreferencesWithoutResettingSavedChoices() throws Exception {
        SqliteCompanionStore store = open(10);
        StoredCompanion original = store.create(request(UUID.randomUUID())).join();
        UUID id = original.companion().id();
        SocialProfile profile = store.saveProfile(SocialProfile.defaults(id).withTrait(Personality.Trait.SOCIABILITY, 77), 0).join();
        profile = store.saveProfile(profile.withPublic(true), 1).join();
        store.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            for (String column : List.of("combativeness", "helpfulness", "farming", "mining")) {
                statement.execute("ALTER TABLE social_profiles DROP COLUMN " + column);
            }
            statement.execute("DROP TABLE mannerism_profiles");
            dropSchemaFive(statement);
            statement.execute("PRAGMA user_version = 2");
        }
        SqliteCompanionStore upgraded = open(10);
        assertEquals(original, upgraded.find(id).join().orElseThrow());
        assertEquals(profile, upgraded.profiles().join().getFirst());
        assertEquals(50, upgraded.profiles().join().getFirst().personality().farming());
    }

    @Test
    void workSetupsPersistValidateDimensionAndMigrateFromSchemaFour() throws Exception {
        SqliteCompanionStore store = open(10);
        StoredCompanion created = store.create(request(UUID.randomUUID())).join();
        UUID id = created.companion().id();
        assertEquals(List.of(WorkSetup.empty(id, "minecraft:overworld")), store.workSetups().join());
        var home = new WorkSetup.BlockPoint(100, 64, 100);
        WorkSetup setup = store.saveWorkSetup(WorkSetup.empty(id, "minecraft:overworld").withHome(new WorkSetup.HomeMarker(home)), 0).join();
        setup = store.saveWorkSetup(setup.withArea(WorkSetup.WorkRegion.around(home)), 1).join();
        setup = store.saveWorkSetup(setup.withStorage(new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(102, 64, 100), "minecraft:chest", 44)), 2).join();
        assertCause(ConcurrentModificationException.class, store.saveWorkSetup(WorkSetup.empty(id, "minecraft:overworld").withHome(new WorkSetup.HomeMarker(home)), 0));
        assertCause(IllegalArgumentException.class, store.saveWorkSetup(WorkSetup.empty(id, "minecraft:the_nether").withHome(new WorkSetup.HomeMarker(home)), 3));
        store.closeAsync().join();
        SqliteCompanionStore reopened = open(10);
        assertEquals(List.of(setup), reopened.workSetups().join());
        assertEquals(List.of(WorkSetup.Field.PATCH), reopened.workSetups().join().getFirst().missing());
        reopened.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            dropSchemaFive(statement);
            statement.execute("PRAGMA user_version = 4");
        }
        SqliteCompanionStore upgraded = open(10);
        assertEquals(created, upgraded.find(id).join().orElseThrow());
        assertEquals(List.of(WorkSetup.empty(id, "minecraft:overworld")), upgraded.workSetups().join(),
                "existing companions gain an empty setup; nothing is granted by migration");
    }

    @Test
    void ledgerReplaysByActionIdEnforcesTransitionsAndNeverPromotesUnknownToSuccess() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        ActionRecord prepared = ActionRecord.prepare(UUID.randomUUID(), lease, "deposit", "chest 102,64,100 v44; 8 logs");
        assertEquals(prepared, store.prepareAction(prepared).join());
        ActionRecord dispatched = store.transitionAction(prepared.dispatched("companion 8 logs, chest 0"), 0).join();
        // Replaying the same action ID returns the recorded row rather than a second intent.
        assertEquals(dispatched, store.prepareAction(prepared).join());
        assertCause(ConcurrentModificationException.class, store.transitionAction(prepared.dispatched("again"), 0));
        assertCause(IllegalStateException.class, store.transitionAction(new ActionRecord(prepared.actionId(), prepared.taskId(), id, prepared.epoch(),
                1, 1, "deposit", prepared.preconditions(), ActionRecord.State.CANCELLED, null, null, ActionRecord.Outcome.NONE, "late", 2), 1));
        // (a dispatched action cannot be cleanly cancelled; the world may already have changed)
        ActionRecord uncertain = store.transitionAction(dispatched.uncertain("verification never observed"), 1).join();
        assertEquals(List.of(uncertain), store.unresolvedActions(id).join());
        ActionRecord resolved = store.transitionAction(uncertain.reconciled(false, "chest still 0, companion still 8", "no effect"), 2).join();
        assertEquals(ActionRecord.State.FAILED, resolved.state());
        assertTrue(store.unresolvedActions(id).join().isEmpty());
        assertCause(IllegalStateException.class, store.transitionAction(new ActionRecord(prepared.actionId(), prepared.taskId(), id, prepared.epoch(),
                1, 1, "deposit", prepared.preconditions(), ActionRecord.State.UNCERTAIN, null, null, ActionRecord.Outcome.UNKNOWN, "reopen", 4), 3));
        assertThrows(IllegalArgumentException.class, () -> store.prepareAction(dispatched));
    }

    @Test
    void crashBetweenIntentAndVerificationLeavesUncertainRecordsThatBlockDependents() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        ActionRecord onlyPrepared = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "break_block", "log at 1,64,1")).join();
        ActionRecord dispatched = store.transitionAction(store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "pickup", "drop 7"))
                .join().dispatched("inv 3"), 0).join();
        ActionRecord verified = store.transitionAction(store.transitionAction(store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "pickup", "drop 8"))
                .join().dispatched("inv 3"), 0).join().verified("inv 4", "attributed"), 1).join();
        store.closeAsync().join(); // No clean shutdown marker exists; reopening is the crash boundary.
        SqliteCompanionStore reopened = open(10);
        List<ActionRecord> unresolved = reopened.unresolvedActions(id).join();
        assertEquals(2, unresolved.size());
        assertTrue(unresolved.stream().allMatch(r -> r.state() == ActionRecord.State.UNCERTAIN && r.outcome() == ActionRecord.Outcome.UNKNOWN));
        assertEquals(verified, reopened.findAction(verified.actionId()).join().orElseThrow(), "a verified outcome is never reopened");
        assertEquals(1, reopened.findAction(onlyPrepared.actionId()).join().orElseThrow().revision());
        assertEquals(2, reopened.findAction(dispatched.actionId()).join().orElseThrow().revision());
        assertTrue(reopened.eventsSince(id, 0, 100).join().stream().anyMatch(e -> e.type().equals("ACTION_UNCERTAIN")));
    }

    @Test
    void checkpointsRoundTripReplaceAndClear() {
        SqliteCompanionStore store = open(10);
        UUID id = store.create(request(UUID.randomUUID())).join().companion().id();
        assertTrue(store.checkpoint(id).join().isEmpty());
        UUID task = UUID.randomUUID();
        Checkpoint first = new Checkpoint("gather_logs", task, 3, Map.of("count", "8", "patch", "90,60,90"), Map.of("collected", "3", "note", "a=b&c d"), 1_000);
        store.saveCheckpoint(id, first).join();
        assertEquals(first, store.checkpoint(id).join().orElseThrow());
        Checkpoint second = new Checkpoint("gather_logs", task, 3, first.arguments(), Map.of("collected", "5"), 2_000);
        store.saveCheckpoint(id, second).join();
        store.closeAsync().join();
        SqliteCompanionStore reopened = open(10);
        assertEquals(second, reopened.checkpoint(id).join().orElseThrow());
        assertTrue(reopened.clearCheckpoint(id).join());
        assertFalse(reopened.clearCheckpoint(id).join());
        assertTrue(reopened.checkpoint(id).join().isEmpty());
    }

    @Test
    void readStartupLedgerAndModesRoundTrip() {
        SqliteCompanionStore store = open(10);
        UUID idA = store.create(request(UUID.randomUUID())).join().companion().id();
        UUID idB = store.create(request(UUID.randomUUID())).join().companion().id();
        UUID idUnknown = UUID.randomUUID();

        // Initially no actions exist
        StartupLedgerSnapshot initial = store.readStartupLedger(Set.of(idA, idB, idUnknown)).join();
        assertEquals(3, initial.byLogicalPlayer().size());
        assertEquals(0, initial.byLogicalPlayer().get(idA).unresolvedCount());
        assertTrue(initial.byLogicalPlayer().get(idA).sample().isEmpty());
        assertEquals(0, initial.byLogicalPlayer().get(idB).unresolvedCount());
        assertEquals(0, initial.byLogicalPlayer().get(idUnknown).unresolvedCount());

        // Prepare actions on idA
        var leaseA = new ActionLease(idA, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        ActionRecord prep = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), leaseA, "swap", "s=0;t=1")).join();

        StartupLedgerSnapshot during = store.readStartupLedger(Set.of(idA, idB)).join();
        assertEquals(1, during.byLogicalPlayer().get(idA).unresolvedCount());
        assertEquals(1, during.byLogicalPlayer().get(idA).sample().size());
        assertEquals(prep.actionId(), during.byLogicalPlayer().get(idA).sample().getFirst().actionId());
        assertEquals(ActionRecord.State.PREPARED, during.byLogicalPlayer().get(idA).sample().getFirst().state());
        assertEquals(0, during.byLogicalPlayer().get(idB).unresolvedCount());

        // Modes round trip
        OperatingMode modeB = new OperatingMode(idB, OperatingMode.Mode.INDEPENDENT, OperatingMode.Scope.HOME, 1);
        store.saveMode(modeB, 0).join();

        Map<UUID, OperatingMode> modes = store.modes(Set.of(idA, idB)).join();
        assertEquals(OperatingMode.Mode.COMPANION, modes.get(idA).mode());
        assertEquals(OperatingMode.Mode.INDEPENDENT, modes.get(idB).mode());
        assertEquals(OperatingMode.Scope.HOME, modes.get(idB).scope());

        // Rejects null
        assertThrows(NullPointerException.class, () -> store.readStartupLedger(null));
        assertThrows(NullPointerException.class, () -> store.modes(null));
    }

    private static void dropSchemaFive(java.sql.Statement statement) throws java.sql.SQLException {
        statement.execute("DROP TABLE IF EXISTS stock_reservations");
        statement.execute("DROP TABLE IF EXISTS shared_tasks");
        statement.execute("DROP TABLE IF EXISTS shared_projects");
        statement.execute("DROP TABLE IF EXISTS native_players");
        statement.execute("ALTER TABLE creation_operations DROP COLUMN request_fingerprint");
        statement.execute("DROP TABLE IF EXISTS activity_limits");
        statement.execute("DROP TABLE IF EXISTS memories");
        statement.execute("DROP TABLE IF EXISTS goals");
        statement.execute("DROP TABLE IF EXISTS companion_modes");
        statement.execute("DROP TABLE skill_checkpoints");
        statement.execute("DROP TABLE action_ledger");
        statement.execute("DROP TABLE work_setups");
    }

    private Path path() { return directory.resolve("companions.sqlite"); }

    private CreationRequest request(UUID ownerId) {
        return new CreationRequest(UUID.randomUUID(), ownerId, "Juniper", UUID.randomUUID(), "minecraft:overworld");
    }

    private BudgetState reserve(BudgetState state, UUID reservationId, Instant now) {
        return state.reserve(reservationId, Duration.ofSeconds(5), Duration.ofMinutes(2), now, state.period().endExclusive());
    }

    private static void assertCause(Class<? extends Throwable> type, CompletableFuture<?> future) {
        assertInstanceOf(type, assertThrows(CompletionException.class, future::join).getCause());
    }
}
