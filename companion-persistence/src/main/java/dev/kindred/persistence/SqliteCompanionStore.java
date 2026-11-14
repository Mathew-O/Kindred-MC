package dev.kindred.persistence;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.ActivityLimit;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.GoalRecord;
import dev.kindred.core.MemoryNote;
import dev.kindred.core.OperatingMode;
import dev.kindred.core.Companion;
import dev.kindred.core.LifecycleState;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.Personality;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.WorkSetup;
import dev.kindred.core.record.ActionLedgerView;
import dev.kindred.core.record.EventData;
import dev.kindred.core.record.Ledger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.core.social.Expression;
import dev.kindred.core.social.ListenerPreferences;
import dev.kindred.core.team.ProjectId;
import dev.kindred.core.team.SharedProject;
import dev.kindred.core.team.SharedTask;
import dev.kindred.core.team.StockReservation;
import dev.kindred.core.team.TaskId;
import dev.kindred.scheduler.AccountingPeriod;
import dev.kindred.scheduler.BudgetState;
import dev.kindred.scheduler.PeriodTransition;
import dev.kindred.scheduler.Reservation;
import dev.kindred.scheduler.ScheduleClock;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * In plain terms: the database file where each player's saved details are kept: who it is and who may command
 * it, its personality and social settings, work area, goals and memories, the record of its actions and its
 * saved task progress, the team's shared projects, and the day's play time. Every write goes through one
 * queue, in order, away from the game's own work; items themselves stay in the game's save, so this record
 * alone cannot prove that an action happened.
 *
 * Technical: Ordered, bounded asynchronous metadata writer. Never join its futures on the game thread.
 * Completion callbacks may run on the writer; dispatch game work to the server thread and
 * compose dependent store futures instead of joining them inside an inline callback.
 * Metadata is not atomic with native world saves; it cannot prove a physical action succeeded.
 */
public final class SqliteCompanionStore {
    private static final int QUEUE_CAPACITY = 256;
    /** What the write-ahead log is cut back to after a checkpoint (F-31f): a thousand pages and a little over, as #227 measured it. */
    static final long JOURNAL_SIZE_LIMIT = 4_194_304;
    /** Every store's work queued or running, for diagnostics and the GameTest server's pacing (F-22c5). */
    private static final java.util.concurrent.atomic.AtomicInteger IN_FLIGHT = new java.util.concurrent.atomic.AtomicInteger();
    private final ThreadPoolExecutor writer;
    private final Semaphore queueSlots = new Semaphore(QUEUE_CAPACITY);
    private final int maxCompanions;
    private Connection database;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private boolean closing;
    private CompletableFuture<Void> closeFuture;
    /** The database file, for the agreement check's own read-only connection (F-31f). */
    private volatile Path databasePath;
    /**
     * The agreement check's thread (F-31f): it reads from a read-only connection of its own, so that the writer is never
     * held by it. It never writes; the writer above stays the only one that does.
     */
    private final java.util.concurrent.ExecutorService checker = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kindred-metadata-check");
        thread.setDaemon(true);
        return thread;
    });
    /** The check's statements under way, to interrupt at a stop; whether checks are stopped. */
    private final java.util.Set<Statement> checking = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean checksStopped;

    private SqliteCompanionStore(int maxCompanions) {
        this.maxCompanions = maxCompanions;
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY + 1), task -> {
                    Thread thread = new Thread(task, "kindred-metadata-writer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static CompletableFuture<SqliteCompanionStore> open(Path path, int maxCompanions) {
        Objects.requireNonNull(path, "path");
        if (maxCompanions < 1) throw new IllegalArgumentException("Server quota must be positive");
        SqliteCompanionStore store = new SqliteCompanionStore(maxCompanions);
        return store.submit(() -> {
            try {
                store.initialize(path);
                return store;
            } catch (Throwable failure) {
                try { store.releaseResources(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                store.writer.shutdown();
                throw failure;
            }
        });
    }

    public CompletableFuture<StoredCompanion> create(CreationRequest request) {
        Objects.requireNonNull(request, "request");
        return submit(() -> transaction(() -> {
            try (PreparedStatement query = database.prepareStatement(
                    "SELECT owner_id, companion_id, request_fingerprint FROM creation_operations WHERE operation_id = ?")) {
                query.setString(1, request.operationId().toString());
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        if (!row.getString("owner_id").equals(request.ownerId().toString())) {
                            throw new SecurityException("Creation operation belongs to another owner");
                        }
                        if (row.getString("request_fingerprint") != null) throw new SecurityException("Creation operation belongs to native-player creation");
                        return required(UUID.fromString(row.getString("companion_id")));
                    }
                }
            }
            Optional<StoredCompanion> existing = selectByOwner(request.ownerId());
            StoredCompanion stored;
            if (existing.isPresent()) {
                stored = existing.orElseThrow();
            } else {
                try (Statement count = database.createStatement();
                     ResultSet row = count.executeQuery("SELECT COUNT(*) FROM companions WHERE lifecycle <> 'REMOVED'")) {
                    if (row.next() && row.getLong(1) >= maxCompanions) {
                        throw new IllegalStateException("Server companion quota is full");
                    }
                }
                Companion companion = Companion.create(UUID.randomUUID(), request.ownerId(),
                        request.displayName(), request.worldId(), request.dimension());
                try (PreparedStatement insert = database.prepareStatement(
                        "INSERT INTO companions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    bindCompanion(insert, companion, 0);
                    insert.executeUpdate();
                }
                stored = new StoredCompanion(companion, 0);
                insertCompanionDefaults(companion);
                insertEvent(companion.id(), Instant.now(), "COMPANION_CREATED", "Created sleeping metadata identity");
            }
            try (PreparedStatement insert = database.prepareStatement(
                    "INSERT INTO creation_operations(operation_id, owner_id, companion_id) VALUES (?, ?, ?)")) {
                insert.setString(1, request.operationId().toString());
                insert.setString(2, request.ownerId().toString());
                insert.setString(3, stored.companion().id().toString());
                insert.executeUpdate();
            }
            return stored;
        }));
    }


    /** Multiple AI identities may share a controller; the compatibility envelope self-owns each one. */
    public CompletableFuture<StoredNativePlayer> createNativePlayer(PlayerCreationRequest request) {
        Objects.requireNonNull(request);
        return submit(() -> transaction(() -> {
            String fingerprint = nativeFingerprint(request);
            try (PreparedStatement query = database.prepareStatement(
                    "SELECT owner_id, companion_id, request_fingerprint FROM creation_operations WHERE operation_id = ?")) {
                query.setString(1, request.operationId().toString());
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        if (!row.getString("owner_id").equals(request.controllerId().toString())
                                || !fingerprint.equals(row.getString("request_fingerprint"))) {
                            throw new SecurityException("Creation operation belongs to another request");
                        }
                        return requiredNative(UUID.fromString(row.getString("companion_id")));
                    }
                }
            }
            try (Statement count = database.createStatement();
                 ResultSet row = count.executeQuery("SELECT COUNT(*) FROM companions WHERE lifecycle <> 'REMOVED'")) {
                if (row.next() && row.getLong(1) >= maxCompanions) throw new IllegalStateException("Server AI-player quota is full");
            }
            UUID id = UUID.randomUUID();
            Companion companion = Companion.create(id, id, request.displayName(), request.worldId(), request.dimension())
                    .withEntityId(UUID.randomUUID());
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO companions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindCompanion(insert, companion, 0);
                insert.executeUpdate();
            }
            insertCompanionDefaults(companion);
            try (PreparedStatement insert = database.prepareStatement(
                    "INSERT INTO native_players(companion_id, controller_id, phase, revision) VALUES (?, ?, 'ALLOCATED', 0)")) {
                insert.setString(1, id.toString());
                insert.setString(2, request.controllerId().toString());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = database.prepareStatement(
                    "INSERT INTO creation_operations(operation_id, owner_id, companion_id, request_fingerprint) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, request.operationId().toString());
                insert.setString(2, request.controllerId().toString());
                insert.setString(3, id.toString());
                insert.setString(4, fingerprint);
                insert.executeUpdate();
            }
            insertEvent(id, Instant.now(), "NATIVE_PLAYER_ALLOCATED", "Independent identity allocated; native body not yet established");
            return requiredNative(id);
        }));
    }

    public CompletableFuture<List<StoredNativePlayer>> nativePlayers() {
        return submit(() -> {
            List<UUID> ids = new ArrayList<>();
            try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("SELECT companion_id FROM native_players ORDER BY companion_id")) {
                while (row.next()) ids.add(UUID.fromString(row.getString(1)));
            }
            List<StoredNativePlayer> players = new ArrayList<>();
            for (UUID id : ids) players.add(requiredNative(id));
            return List.copyOf(players);
        });
    }

    /** A server-verified native save receipt; this metadata never contains or reconstructs items. */
    public CompletableFuture<StoredNativePlayer> establishNativePlayer(UUID id, UUID receipt, long expectedRevision) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(receipt);
        return submit(() -> transaction(() -> {
            StoredNativePlayer current = requiredNative(id);
            if (current.phase() == StoredNativePlayer.Phase.BLOCKED
                    || current.metadata().companion().lifecycle() == LifecycleState.REMOVED) {
                throw new IllegalStateException("Blocked/removed native player requires explicit reconciliation");
            }
            try (PreparedStatement update = database.prepareStatement(
                    "UPDATE native_players SET phase='ESTABLISHED', save_receipt=?, blocker='', revision=revision+1 WHERE companion_id=? AND revision=?")) {
                update.setString(1, receipt.toString());
                update.setString(2, id.toString());
                update.setLong(3, expectedRevision);
                if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Native player revision changed");
            }
            return requiredNative(id);
        }));
    }

    public CompletableFuture<StoredNativePlayer> blockNativePlayer(UUID id, String reason, long expectedRevision) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(reason);
        if (reason.isBlank() || reason.length() > 256 || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Blocker must contain 1..256 printable characters");
        }
        return submit(() -> transaction(() -> {
            try (PreparedStatement update = database.prepareStatement(
                    "UPDATE native_players SET phase='BLOCKED', blocker=?, revision=revision+1 WHERE companion_id=? AND revision=?")) {
                update.setString(1, reason);
                update.setString(2, id.toString());
                update.setLong(3, expectedRevision);
                if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Native player revision changed");
            }
            insertEvent(id, Instant.now(), "NATIVE_BODY_BLOCKED", reason);
            return requiredNative(id);
        }));
    }

    private StoredNativePlayer requiredNative(UUID id) throws SQLException {
        UUID controller;
        StoredNativePlayer.Phase phase;
        UUID receipt;
        String blocker;
        long revision;
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM native_players WHERE companion_id=?")) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Unknown native player");
                controller = UUID.fromString(row.getString("controller_id"));
                phase = StoredNativePlayer.Phase.valueOf(row.getString("phase"));
                receipt = row.getString("save_receipt") == null ? null : UUID.fromString(row.getString("save_receipt"));
                blocker = row.getString("blocker");
                revision = row.getLong("revision");
            }
        }
        return new StoredNativePlayer(required(id), controller, phase, receipt, blocker, revision);
    }

    private static String nativeFingerprint(PlayerCreationRequest request) {
        // Names and dimension identifiers forbid newlines, so each field has an unambiguous boundary.
        String payload = "native-player\n" + request.controllerId() + "\n" + request.displayName()
                + "\n" + request.worldId() + "\n" + request.dimension();
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void insertCompanionDefaults(Companion companion) throws SQLException {
        try (PreparedStatement insert = database.prepareStatement(
                """
                INSERT INTO social_profiles(companion_id, initiative, caution, curiosity, sociability, humor,
                personality_revision, ambient_enabled, public_conversation, revision)
                VALUES (?, 50, 50, 50, 50, 50, 0, 1, 0, 0)
                """)) {
            insert.setString(1, companion.id().toString());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO mannerism_profiles(companion_id, policy_version, revision) VALUES (?, ?, 0)")) {
            insert.setString(1, companion.id().toString());
            insert.setInt(2, Mannerisms.POLICY_VERSION);
            insert.executeUpdate();
        }
        try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO work_setups(companion_id, dimension, revision) VALUES (?, ?, 0)")) {
            insert.setString(1, companion.id().toString());
            insert.setString(2, companion.dimension());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO companion_modes(companion_id, revision) VALUES (?, 0)")) {
            insert.setString(1, companion.id().toString());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO activity_limits(companion_id, cap_ms, offline_work, timezone, revision) VALUES (?, 7200000, 0, 'UTC', 0)")) {
            insert.setString(1, companion.id().toString());
            insert.executeUpdate();
        }
    }

    public CompletableFuture<Optional<StoredCompanion>> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return submit(() -> select(id));
    }

    public CompletableFuture<Optional<StoredCompanion>> findByOwner(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return submit(() -> selectByOwner(ownerId));
    }

    public CompletableFuture<List<StoredCompanion>> list() {
        return submit(this::selectAll);
    }

    public CompletableFuture<StoredCompanion> save(Companion companion, long expectedRevision) {
        Objects.requireNonNull(companion, "companion");
        return submit(() -> transaction(() -> {
            StoredCompanion previous = required(companion.id());
            requireRevision(previous.revision(), expectedRevision);
            Companion old = previous.companion();
            if (!old.ownerId().equals(companion.ownerId()) || !old.worldId().equals(companion.worldId())) {
                throw new IllegalArgumentException("Companion ownership and world identity are immutable");
            }
            if (!companion.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("A valid dimension identifier is required");
            }
            if (companion.intentRevision() < old.intentRevision() || companion.taskRevision() < old.taskRevision()) {
                throw new IllegalArgumentException("Control revisions cannot move backwards");
            }
            if (old.lifecycle() == LifecycleState.REMOVED && companion.lifecycle() != LifecycleState.REMOVED) {
                throw new IllegalStateException("A removed companion cannot be revived by a late write");
            }
            long next = Math.incrementExact(expectedRevision);
            updateCompanion(companion, next, expectedRevision);
            insertEvent(companion.id(), Instant.now(), "CONTROL_STATE_SAVED",
                    "Lifecycle " + companion.lifecycle() + "; stopLatched=" + companion.stopLatched());
            return new StoredCompanion(companion, next);
        }));
    }

    public CompletableFuture<List<SocialProfile>> profiles() {
        return submit(() -> {
            List<SocialProfile> profiles = new ArrayList<>();
            try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM social_profiles")) {
                while (rows.next()) profiles.add(readProfile(rows));
            }
            return List.copyOf(profiles);
        });
    }

    /** Every person's own preferences for how AI players speak to them (E-19a). */
    public CompletableFuture<List<ListenerPreferences>> listenerPreferences() {
        return submit(() -> {
            List<ListenerPreferences> all = new ArrayList<>();
            try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM listener_preferences")) {
                while (rows.next()) {
                    all.add(new ListenerPreferences(UUID.fromString(rows.getString("person_id")),
                            ListenerPreferences.Unasked.parse(rows.getString("unasked")),
                            rows.getBoolean("no_teasing"), rows.getBoolean("no_jokes"),
                            ListenerPreferences.Opt.parse(rows.getString("swearing")), ListenerPreferences.Opt.parse(rows.getString("dark_humor")),
                            ListenerPreferences.Opt.parse(rows.getString("flirting")), rows.getLong("revision")));
                }
            }
            return List.copyOf(all);
        });
    }

    /** A person's preferences kept, replacing theirs; the defaults delete them, as there is then nothing to keep. */
    public CompletableFuture<ListenerPreferences> saveListenerPreferences(ListenerPreferences preferences) {
        Objects.requireNonNull(preferences);
        Objects.requireNonNull(preferences.person());
        if (preferences.isDefault()) return forgetListenerPreferences(preferences.person()).thenApply(ignored -> preferences);
        return submit(() -> transaction(() -> {
            try (PreparedStatement upsert = database.prepareStatement(
                    "INSERT INTO listener_preferences (person_id, unasked, no_teasing, no_jokes, revision, swearing, dark_humor, flirting) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(person_id) DO UPDATE SET unasked=excluded.unasked, no_teasing=excluded.no_teasing, "
                            + "no_jokes=excluded.no_jokes, revision=excluded.revision, swearing=excluded.swearing, "
                            + "dark_humor=excluded.dark_humor, flirting=excluded.flirting")) {
                upsert.setString(1, preferences.person().toString());
                upsert.setString(2, preferences.unasked().name().toLowerCase(java.util.Locale.ROOT));
                upsert.setBoolean(3, preferences.noTeasing());
                upsert.setBoolean(4, preferences.noJokes());
                upsert.setLong(5, preferences.revision());
                upsert.setString(6, preferences.swearing().id());
                upsert.setString(7, preferences.darkHumor().id());
                upsert.setString(8, preferences.flirting().id());
                upsert.executeUpdate();
            }
            return preferences;
        }));
    }

    /** A person's preferences deleted at their request; true if there were any. */
    public CompletableFuture<Boolean> forgetListenerPreferences(UUID person) {
        Objects.requireNonNull(person);
        return submit(() -> transaction(() -> {
            try (PreparedStatement delete = database.prepareStatement("DELETE FROM listener_preferences WHERE person_id = ?")) {
                delete.setString(1, person.toString());
                return delete.executeUpdate() > 0;
            }
        }));
    }

    public CompletableFuture<SocialProfile> saveProfile(SocialProfile profile, long expectedRevision) {
        Objects.requireNonNull(profile);
        return submit(() -> transaction(() -> {
            if (required(profile.companionId()).companion().lifecycle() == LifecycleState.REMOVED) {
                throw new IllegalStateException("A removed companion cannot change its profile");
            }
            SocialProfile previous;
            try (PreparedStatement query = database.prepareStatement("SELECT * FROM social_profiles WHERE companion_id = ?")) {
                query.setString(1, profile.companionId().toString());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Missing companion profile");
                    previous = readProfile(rows);
                }
            }
            requireRevision(previous.revision(), expectedRevision);
            if (profile.revision() != Math.incrementExact(expectedRevision)
                    || (!profile.personality().equals(previous.personality())
                    && profile.personality().revision() != Math.incrementExact(previous.personality().revision()))) {
                throw new IllegalArgumentException("Profile revisions must advance without resetting personality");
            }
            try (PreparedStatement update = database.prepareStatement("""
                    UPDATE social_profiles SET initiative=?, caution=?, curiosity=?, sociability=?, humor=?,
                    personality_revision=?, ambient_enabled=?, public_conversation=?, revision=?,
                    combativeness=?, helpfulness=?, farming=?, mining=?, expression=?, chat_style=?
                    WHERE companion_id=? AND revision=?
                    """)) {
                Personality traits = profile.personality();
                update.setInt(1, traits.initiative());
                update.setInt(2, traits.caution());
                update.setInt(3, traits.curiosity());
                update.setInt(4, traits.sociability());
                update.setInt(5, traits.humor());
                update.setLong(6, traits.revision());
                update.setBoolean(7, profile.ambientEnabled());
                update.setBoolean(8, profile.publicConversation());
                update.setLong(9, profile.revision());
                update.setInt(10, traits.combativeness());
                update.setInt(11, traits.helpfulness());
                update.setInt(12, traits.farming());
                update.setInt(13, traits.mining());
                update.setString(14, profile.expression() == null ? null : profile.expression().id());
                update.setString(15, profile.chatStyle() == null ? null : profile.chatStyle().id());
                update.setString(16, profile.companionId().toString());
                update.setLong(17, expectedRevision);
                if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Stale profile revision");
            }
            return profile;
        }));
    }

    public CompletableFuture<List<Mannerisms>> mannerisms() {
        return submit(() -> {
            List<Mannerisms> result = new ArrayList<>();
            try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM mannerism_profiles")) {
                while (rows.next()) result.add(readMannerisms(rows));
            }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<List<ActivityLimit>> limits() {
        return submit(() -> {
            List<ActivityLimit> result = new ArrayList<>();
            try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM activity_limits")) {
                while (rows.next()) {
                    result.add(new ActivityLimit(UUID.fromString(rows.getString("companion_id")),
                            rows.getLong("cap_ms"), rows.getBoolean("offline_work"),
                            rows.getString("timezone"), rows.getLong("revision"), readWindow(rows)));
                }
            }
            return List.copyOf(result);
        });
    }

    /** Saves the durable quirk settings only; temporary accommodations are runtime promises, not settings. */
    public CompletableFuture<Mannerisms> saveMannerisms(Mannerisms mannerisms, long expectedRevision) {
        Objects.requireNonNull(mannerisms);
        return submit(() -> transaction(() -> {
            if (required(mannerisms.companionId()).companion().lifecycle() == LifecycleState.REMOVED) {
                throw new IllegalStateException("A removed companion cannot change its mannerisms");
            }
            Mannerisms previous;
            try (PreparedStatement query = database.prepareStatement("SELECT * FROM mannerism_profiles WHERE companion_id = ?")) {
                query.setString(1, mannerisms.companionId().toString());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Missing companion mannerisms");
                    previous = readMannerisms(rows);
                }
            }
            requireRevision(previous.revision(), expectedRevision);
            if (mannerisms.revision() != Math.incrementExact(expectedRevision)) {
                throw new IllegalArgumentException("Mannerism revisions must advance by one");
            }
            try (PreparedStatement update = database.prepareStatement("""
                    UPDATE mannerism_profiles SET sprintjump=?, lively=?, curiousglance=?, steady=?, policy_version=?, revision=?
                    WHERE companion_id=? AND revision=?
                    """)) {
                update.setString(1, mannerisms.setting(Mannerisms.Quirk.SPRINTJUMP).name());
                update.setString(2, mannerisms.setting(Mannerisms.Quirk.LIVELY).name());
                update.setString(3, mannerisms.setting(Mannerisms.Quirk.CURIOUSGLANCE).name());
                update.setString(4, mannerisms.setting(Mannerisms.Quirk.STEADY).name());
                update.setInt(5, Mannerisms.POLICY_VERSION);
                update.setLong(6, mannerisms.revision());
                update.setString(7, mannerisms.companionId().toString());
                update.setLong(8, expectedRevision);
                if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Stale mannerism revision");
            }
            return mannerisms;
        }));
    }

    // ----- Work authority (F-03) -----

    public CompletableFuture<List<WorkSetup>> workSetups() {
        return submit(() -> {
            List<WorkSetup> result = new ArrayList<>();
            try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM work_setups")) {
                while (rows.next()) result.add(readSetup(rows));
            }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<WorkSetup> saveWorkSetup(WorkSetup setup, long expectedRevision) {
        Objects.requireNonNull(setup);
        return submit(() -> transaction(() -> {
            StoredCompanion owner = required(setup.companionId());
            if (owner.companion().lifecycle() == LifecycleState.REMOVED) throw new IllegalStateException("A removed companion cannot change its setup");
            if (!owner.companion().dimension().equals(setup.dimension())) {
                throw new IllegalArgumentException("Setup must stay in the companion's own dimension");
            }
            WorkSetup previous;
            try (PreparedStatement query = database.prepareStatement("SELECT * FROM work_setups WHERE companion_id = ?")) {
                query.setString(1, setup.companionId().toString());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Missing companion setup");
                    previous = readSetup(rows);
                }
            }
            requireRevision(previous.revision(), expectedRevision);
            if (setup.revision() != Math.incrementExact(expectedRevision)) throw new IllegalArgumentException("Setup revisions must advance by one");
            try (PreparedStatement update = database.prepareStatement("""
                    UPDATE work_setups SET dimension=?, home_x=?, home_y=?, home_z=?, area_min_x=?, area_min_z=?, area_max_x=?, area_max_z=?,
                    storage_x=?, storage_y=?, storage_z=?, storage_block=?, storage_version=?,
                    patch_min_x=?, patch_min_y=?, patch_min_z=?, patch_max_x=?, patch_max_y=?, patch_max_z=?, patch_resource=?, revision=?
                    WHERE companion_id=? AND revision=?
                    """)) {
                update.setString(1, setup.dimension());
                var home = setup.home();
                setPoint(update, 2, home == null ? null : home.position());
                var area = setup.area();
                setNullableInt(update, 5, area == null ? null : area.minX());
                setNullableInt(update, 6, area == null ? null : area.minZ());
                setNullableInt(update, 7, area == null ? null : area.maxX());
                setNullableInt(update, 8, area == null ? null : area.maxZ());
                var storage = setup.storage();
                setPoint(update, 9, storage == null ? null : storage.position());
                update.setString(12, storage == null ? null : storage.blockId());
                if (storage == null) update.setObject(13, null); else update.setLong(13, storage.observedVersion());
                var patch = setup.patch();
                setPoint(update, 14, patch == null ? null : patch.min());
                setPoint(update, 17, patch == null ? null : patch.max());
                update.setString(20, patch == null ? null : patch.resourceId());
                update.setLong(21, setup.revision());
                update.setString(22, setup.companionId().toString());
                update.setLong(23, expectedRevision);
                if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Stale setup revision");
            }
            insertEvent(setup.companionId(), Instant.now(), "SETUP_SAVED", setup.explanation());
            return setup;
        }));
    }

    private static WorkSetup readSetup(ResultSet row) throws SQLException {
        UUID id = UUID.fromString(row.getString("companion_id"));
        WorkSetup.HomeMarker home = row.getObject("home_x") == null ? null
                : new WorkSetup.HomeMarker(new WorkSetup.BlockPoint(row.getInt("home_x"), row.getInt("home_y"), row.getInt("home_z")));
        WorkSetup.WorkRegion area = row.getObject("area_min_x") == null ? null
                : new WorkSetup.WorkRegion(row.getInt("area_min_x"), row.getInt("area_min_z"), row.getInt("area_max_x"), row.getInt("area_max_z"));
        WorkSetup.StorageTarget storage = row.getObject("storage_x") == null ? null
                : new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(row.getInt("storage_x"), row.getInt("storage_y"), row.getInt("storage_z")),
                row.getString("storage_block"), row.getLong("storage_version"));
        WorkSetup.ResourcePatch patch = row.getObject("patch_min_x") == null ? null
                : new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(row.getInt("patch_min_x"), row.getInt("patch_min_y"), row.getInt("patch_min_z")),
                new WorkSetup.BlockPoint(row.getInt("patch_max_x"), row.getInt("patch_max_y"), row.getInt("patch_max_z")), row.getString("patch_resource"));
        return new WorkSetup(id, row.getString("dimension"), home, area, storage, patch, row.getLong("revision"));
    }

    private static void setPoint(PreparedStatement statement, int index, WorkSetup.BlockPoint point) throws SQLException {
        setNullableInt(statement, index, point == null ? null : point.x());
        setNullableInt(statement, index + 1, point == null ? null : point.y());
        setNullableInt(statement, index + 2, point == null ? null : point.z());
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setObject(index, null); else statement.setInt(index, value);
    }

    // ----- Action ledger (F-05) -----

    /** Idempotent: replaying an action ID returns the recorded row instead of a second intent. */
    public CompletableFuture<ActionRecord> prepareAction(ActionRecord record) {
        Objects.requireNonNull(record);
        if (record.state() != ActionRecord.State.PREPARED || record.revision() != 0) {
            throw new IllegalArgumentException("Only a fresh PREPARED record can be inserted");
        }
        return submit(() -> transaction(() -> {
            Optional<ActionRecord> existing = selectAction(record.actionId());
            if (existing.isPresent()) return existing.orElseThrow();
            required(record.companionId());
            long now = Instant.now().toEpochMilli();
            try (PreparedStatement insert = database.prepareStatement(
                    "INSERT INTO action_ledger VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindAction(insert, record);
                insert.setLong(15, now);
                insert.setLong(16, now);
                insert.executeUpdate();
            }
            insertWorldEvent(actionEvent(record, now));
            return record;
        }));
    }

    /** Applies one durable state transition; the stored row's state and revision must match. */
    public CompletableFuture<ActionRecord> transitionAction(ActionRecord next, long expectedRevision) {
        Objects.requireNonNull(next);
        return submit(() -> transaction(() -> applyTransition(next, expectedRevision)));
    }

    /**
     * An uncertain action settled after a restart (F-31g1): its outcome written as {@link #transitionAction} writes one,
     * and in the same transaction {@code settled}, the Record's note of who settled it and what was seen. Only an
     * UNCERTAIN row may be settled, and only to VERIFIED or FAILED; the stored state and revision are checked here, at the
     * write, so a row settled or changed meanwhile is refused and nothing is written.
     */
    public CompletableFuture<ActionRecord> settleAction(ActionRecord next, long expectedRevision, WorldEvent settled) {
        Objects.requireNonNull(next);
        Objects.requireNonNull(settled);
        if (next.state() != ActionRecord.State.VERIFIED && next.state() != ActionRecord.State.FAILED) {
            throw new IllegalArgumentException("A settlement is done or not done, never " + next.state());
        }
        return submit(() -> transaction(() -> {
            ActionRecord stored = selectAction(next.actionId()).orElseThrow(() -> new IllegalArgumentException("Unknown action " + next.actionId()));
            if (stored.state() != ActionRecord.State.UNCERTAIN) throw new IllegalStateException("Only an uncertain action is settled, not " + stored.state());
            ActionRecord written = applyTransition(next, expectedRevision);
            insertWorldEvent(settled);
            insertEvent(next.companionId(), Instant.now(), "ACTION_SETTLED", next.kind() + " " + next.actionId() + ": " + next.detail());
            return written;
        }));
    }

    /** One transition's checks and writes, inside the caller's transaction on the ordered writer. */
    private ActionRecord applyTransition(ActionRecord next, long expectedRevision) throws SQLException {
            ActionRecord stored = selectAction(next.actionId()).orElseThrow(() -> new IllegalArgumentException("Unknown action " + next.actionId()));
            requireRevision(stored.revision(), expectedRevision);
            if (next.revision() != Math.incrementExact(expectedRevision)) throw new IllegalArgumentException("Action revisions advance by one");
            if (!stored.taskId().equals(next.taskId()) || !stored.companionId().equals(next.companionId()) || !stored.epoch().equals(next.epoch())
                    || !stored.kind().equals(next.kind()) || stored.intentRevision() != next.intentRevision()
                    || stored.taskRevision() != next.taskRevision() || !Objects.equals(stored.preconditions(), next.preconditions())) {
                throw new IllegalArgumentException("Action identity is immutable");
            }
            if (!ActionRecord.validTransition(stored.state(), next.state())) {
                throw new IllegalStateException("Invalid ledger transition " + stored.state() + " -> " + next.state());
            }
            if (stored.state() != ActionRecord.State.PREPARED && !Objects.equals(stored.beforeEvidence(), next.beforeEvidence())) {
                throw new IllegalArgumentException("Recorded action pre-effect evidence is immutable");
            }
            try (PreparedStatement update = database.prepareStatement("""
                    UPDATE action_ledger SET state=?, before_evidence=?, after_evidence=?, outcome=?, detail=?, revision=?, updated_at_ms=?
                    WHERE action_id=? AND revision=?
                    """)) {
                update.setString(1, next.state().name());
                update.setString(2, next.beforeEvidence());
                update.setString(3, next.afterEvidence());
                update.setString(4, next.outcome().name());
                update.setString(5, next.detail());
                update.setLong(6, next.revision());
                update.setLong(7, Instant.now().toEpochMilli());
                update.setString(8, next.actionId().toString());
                update.setLong(9, expectedRevision);
                if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Stale action revision");
            }
            insertWorldEvent(actionEvent(next, Instant.now().toEpochMilli()));
            if (next.state() == ActionRecord.State.UNCERTAIN) {
                insertEvent(next.companionId(), Instant.now(), "ACTION_UNCERTAIN", next.kind() + " " + next.actionId() + ": " + next.detail());
            }
        return next;
    }

    public CompletableFuture<Optional<ActionRecord>> findAction(UUID actionId) {
        Objects.requireNonNull(actionId);
        return submit(() -> selectAction(actionId));
    }

    /** PREPARED, DISPATCHED and UNCERTAIN records block dependent work until resolved. */
    public CompletableFuture<List<ActionRecord>> unresolvedActions(UUID companionId) {
        Objects.requireNonNull(companionId);
        return submit(() -> {
            List<ActionRecord> result = new ArrayList<>();
            try (PreparedStatement query = database.prepareStatement(
                    "SELECT * FROM action_ledger WHERE companion_id = ? AND state IN ('PREPARED', 'DISPATCHED', 'UNCERTAIN') ORDER BY prepared_at_ms")) {
                query.setString(1, companionId.toString());
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(readAction(rows)); }
            }
            return List.copyOf(result);
        });
    }

    /**
     * Reads an immutable startup snapshot of unresolved action ledger rows across all expected logical player IDs.
     * Guaranteed to return an entry for every requested identity.
     */
    public CompletableFuture<StartupLedgerSnapshot> readStartupLedger(Set<UUID> expectedLogicalPlayerIds) {
        Objects.requireNonNull(expectedLogicalPlayerIds, "expectedLogicalPlayerIds");
        Set<UUID> requested = Set.copyOf(expectedLogicalPlayerIds);
        return submit(() -> {
            Map<UUID, LedgerScan> results = new HashMap<>();
            for (UUID companionId : requested) {
                long count = 0;
                List<ActionRef> sample = new ArrayList<>();
                try (PreparedStatement query = database.prepareStatement(
                        "SELECT action_id, state, revision FROM action_ledger WHERE companion_id = ? AND state IN ('PREPARED', 'DISPATCHED', 'UNCERTAIN') ORDER BY prepared_at_ms")) {
                    query.setString(1, companionId.toString());
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            count++;
                            if (sample.size() < 16) {
                                UUID actionId = UUID.fromString(rows.getString("action_id"));
                                ActionRecord.State state = ActionRecord.State.valueOf(rows.getString("state"));
                                long revision = rows.getLong("revision");
                                sample.add(new ActionRef(actionId, state, revision));
                            }
                        }
                    }
                }
                results.put(companionId, new LedgerScan(count, sample));
            }
            return new StartupLedgerSnapshot(results);
        });
    }

    private Optional<ActionRecord> selectAction(UUID actionId) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM action_ledger WHERE action_id = ?")) {
            query.setString(1, actionId.toString());
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? Optional.of(readAction(rows)) : Optional.empty(); }
        }
    }

    private static ActionRecord readAction(ResultSet row) throws SQLException {
        return new ActionRecord(UUID.fromString(row.getString("action_id")), UUID.fromString(row.getString("task_id")),
                UUID.fromString(row.getString("companion_id")), UUID.fromString(row.getString("epoch")),
                row.getLong("intent_revision"), row.getLong("task_revision"), row.getString("kind"), row.getString("preconditions"),
                ActionRecord.State.valueOf(row.getString("state")), row.getString("before_evidence"), row.getString("after_evidence"),
                ActionRecord.Outcome.valueOf(row.getString("outcome")), row.getString("detail"), row.getLong("revision"));
    }

    private static void bindAction(PreparedStatement statement, ActionRecord record) throws SQLException {
        statement.setString(1, record.actionId().toString());
        statement.setString(2, record.taskId().toString());
        statement.setString(3, record.companionId().toString());
        statement.setString(4, record.epoch().toString());
        statement.setLong(5, record.intentRevision());
        statement.setLong(6, record.taskRevision());
        statement.setString(7, record.kind());
        statement.setString(8, record.preconditions());
        statement.setString(9, record.state().name());
        statement.setString(10, record.beforeEvidence());
        statement.setString(11, record.afterEvidence());
        statement.setString(12, record.outcome().name());
        statement.setString(13, record.detail());
        statement.setLong(14, record.revision());
    }

    // ----- Skill checkpoints (F-04) -----

    public CompletableFuture<Optional<Checkpoint>> checkpoint(UUID companionId) {
        Objects.requireNonNull(companionId);
        return submit(() -> selectCheckpoint(companionId));
    }

    /** One checkpoint per companion; a newer save replaces the older one. */
    public CompletableFuture<Checkpoint> saveCheckpoint(UUID companionId, Checkpoint checkpoint) {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(checkpoint);
        return submit(() -> transaction(() -> {
            if (required(companionId).companion().lifecycle() == LifecycleState.REMOVED) throw new IllegalStateException("Removed companion");
            long revision = selectCheckpoint(companionId).isPresent() ? selectCheckpointRevision(companionId) + 1 : 0;
            try (PreparedStatement upsert = database.prepareStatement("""
                    INSERT INTO skill_checkpoints(companion_id, skill_id, task_id, task_revision, arguments, progress, at_ms, revision)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(companion_id) DO UPDATE SET skill_id=excluded.skill_id, task_id=excluded.task_id,
                    task_revision=excluded.task_revision, arguments=excluded.arguments, progress=excluded.progress,
                    at_ms=excluded.at_ms, revision=excluded.revision
                    """)) {
                upsert.setString(1, companionId.toString());
                upsert.setString(2, checkpoint.skillId());
                upsert.setString(3, checkpoint.taskId().toString());
                upsert.setLong(4, checkpoint.taskRevision());
                upsert.setString(5, encode(checkpoint.arguments()));
                upsert.setString(6, encode(checkpoint.progress()));
                upsert.setLong(7, checkpoint.at());
                upsert.setLong(8, revision);
                upsert.executeUpdate();
            }
            return checkpoint;
        }));
    }

    public CompletableFuture<Boolean> clearCheckpoint(UUID companionId) {
        Objects.requireNonNull(companionId);
        return submit(() -> {
            try (PreparedStatement delete = database.prepareStatement("DELETE FROM skill_checkpoints WHERE companion_id = ?")) {
                delete.setString(1, companionId.toString());
                return delete.executeUpdate() == 1;
            }
        });
    }

    private Optional<Checkpoint> selectCheckpoint(UUID companionId) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM skill_checkpoints WHERE companion_id = ?")) {
            query.setString(1, companionId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new Checkpoint(row.getString("skill_id"), UUID.fromString(row.getString("task_id")),
                        row.getLong("task_revision"), decode(row.getString("arguments")), decode(row.getString("progress")), row.getLong("at_ms")));
            }
        }
    }

    private long selectCheckpointRevision(UUID companionId) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT revision FROM skill_checkpoints WHERE companion_id = ?")) {
            query.setString(1, companionId.toString());
            try (ResultSet row = query.executeQuery()) { return row.next() ? row.getLong(1) : -1; }
        }
    }

    private static String encode(Map<String, String> map) {
        StringBuilder text = new StringBuilder();
        for (var entry : new java.util.TreeMap<>(map).entrySet()) {
            if (!text.isEmpty()) text.append('&');
            text.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    private static Map<String, String> decode(String text) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (text == null || text.isEmpty()) return map;
        for (String pair : text.split("&")) {
            int split = pair.indexOf('=');
            map.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    public CompletableFuture<OperatingMode> mode(UUID companionId) {
        Objects.requireNonNull(companionId);
        return submit(() -> readMode(companionId));
    }

    public CompletableFuture<Map<UUID, OperatingMode>> modes(Set<UUID> companionIds) {
        Objects.requireNonNull(companionIds, "companionIds");
        Set<UUID> requested = Set.copyOf(companionIds);
        return submit(() -> {
            Map<UUID, OperatingMode> results = new HashMap<>();
            for (UUID id : requested) {
                results.put(id, readMode(id));
            }
            return Map.copyOf(results);
        });
    }

    private OperatingMode readMode(UUID companionId) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM companion_modes WHERE companion_id = ?")) {
            query.setString(1, companionId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return OperatingMode.defaults(companionId);
                return new OperatingMode(companionId, OperatingMode.Mode.valueOf(row.getString("mode")),
                        OperatingMode.Scope.valueOf(row.getString("scope")), row.getLong("revision"));
            }
        }
    }

    public CompletableFuture<OperatingMode> saveMode(OperatingMode mode, long expectedRevision) {
        Objects.requireNonNull(mode);
        return submit(() -> transaction(() -> {
            OperatingMode previous = readMode(mode.companionId());
            requireRevision(previous.revision(), expectedRevision);
            if (mode.revision() != Math.incrementExact(expectedRevision)) throw new IllegalArgumentException("Mode revisions advance by one");
            try (PreparedStatement update = database.prepareStatement(
                    "UPDATE companion_modes SET mode=?, scope=?, revision=? WHERE companion_id=? AND revision=?")) {
                update.setString(1, mode.mode().name());
                update.setString(2, mode.scope().name());
                update.setLong(3, mode.revision());
                update.setString(4, mode.companionId().toString());
                update.setLong(5, expectedRevision);
                if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Stale mode revision");
            }
            return mode;
        }));
    }

    public CompletableFuture<GoalRecord> addGoal(GoalRecord goal) {
        Objects.requireNonNull(goal);
        return submit(() -> {
            required(goal.companionId());
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO goals VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, goal.id().toString());
                insert.setString(2, goal.companionId().toString());
                insert.setString(3, goal.origin().name());
                insert.setString(4, goal.description());
                insert.setString(5, goal.outcome());
                insert.setInt(6, goal.priority());
                insert.setString(7, goal.status().name());
                insert.setLong(8, goal.revision());
                insert.executeUpdate();
            }
            return goal;
        });
    }

    public CompletableFuture<List<GoalRecord>> goals(UUID companionId) {
        Objects.requireNonNull(companionId);
        return submit(() -> {
            List<GoalRecord> result = new ArrayList<>();
            try (PreparedStatement query = database.prepareStatement("SELECT * FROM goals WHERE companion_id = ? ORDER BY priority, goal_id")) {
                query.setString(1, companionId.toString());
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) result.add(new GoalRecord(UUID.fromString(row.getString("goal_id")), companionId,
                            GoalRecord.Origin.valueOf(row.getString("origin")), row.getString("description"), row.getString("outcome"),
                            row.getInt("priority"), GoalRecord.Status.valueOf(row.getString("status")), row.getLong("revision")));
                }
            }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<MemoryNote> addMemory(MemoryNote note) {
        Objects.requireNonNull(note);
        return submit(() -> {
            required(note.companionId());
            try (PreparedStatement insert = database.prepareStatement("INSERT INTO memories VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, note.id().toString());
                insert.setString(2, note.companionId().toString());
                insert.setString(3, note.kind().name());
                insert.setString(4, note.text());
                insert.setString(5, note.provenance());
                insert.setLong(6, note.revision());
                insert.executeUpdate();
            }
            return note;
        });
    }

    public CompletableFuture<List<MemoryNote>> memories(UUID companionId) {
        Objects.requireNonNull(companionId);
        return submit(() -> {
            List<MemoryNote> result = new ArrayList<>();
            try (PreparedStatement query = database.prepareStatement("SELECT * FROM memories WHERE companion_id = ? ORDER BY memory_id")) {
                query.setString(1, companionId.toString());
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) result.add(new MemoryNote(UUID.fromString(row.getString("memory_id")), companionId,
                            MemoryNote.Kind.valueOf(row.getString("kind")), row.getString("text"), row.getString("provenance"), row.getLong("revision")));
                }
            }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<Boolean> forgetMemory(UUID companionId, UUID memoryId) {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(memoryId);
        return submit(() -> {
            try (PreparedStatement delete = database.prepareStatement("DELETE FROM memories WHERE companion_id = ? AND memory_id = ?")) {
                delete.setString(1, companionId.toString());
                delete.setString(2, memoryId.toString());
                return delete.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<ActivityLimit> limit(UUID companionId) {
        Objects.requireNonNull(companionId);
        return submit(() -> readLimit(companionId));
    }

    public CompletableFuture<ActivityLimit> saveLimit(ActivityLimit limit, long expectedRevision) {
        Objects.requireNonNull(limit);
        return submit(() -> transaction(() -> {
            ActivityLimit previous = readLimit(limit.companionId());
            requireRevision(previous.revision(), expectedRevision);
            if (limit.revision() != Math.incrementExact(expectedRevision)) throw new IllegalArgumentException("Limit revisions advance by one");
            try (PreparedStatement update = database.prepareStatement(
                    "UPDATE activity_limits SET cap_ms=?, offline_work=?, timezone=?, revision=?, window_start=?, window_end=? "
                            + "WHERE companion_id=? AND revision=?")) {
                update.setLong(1, limit.capMillis());
                update.setBoolean(2, limit.offlineWork());
                update.setString(3, limit.timezone());
                update.setLong(4, limit.revision());
                update.setString(5, limit.window().map(window -> window.start().toString()).orElse(null));
                update.setString(6, limit.window().map(window -> window.end().toString()).orElse(null));
                update.setString(7, limit.companionId().toString());
                update.setLong(8, expectedRevision);
                if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Stale limit revision");
            }
            return limit;
        }));
    }

    private ActivityLimit readLimit(UUID companionId) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM activity_limits WHERE companion_id = ?")) {
            query.setString(1, companionId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return ActivityLimit.defaults(companionId);
                return new ActivityLimit(companionId, row.getLong("cap_ms"), row.getBoolean("offline_work"), row.getString("timezone"),
                        row.getLong("revision"), readWindow(row));
            }
        }
    }

    /** Both window columns or neither: a half-written window is not read as all day. */
    private static Optional<ActivityLimit.Window> readWindow(ResultSet row) throws SQLException {
        String start = row.getString("window_start");
        String end = row.getString("window_end");
        if (start == null && end == null) return Optional.empty();
        if (start == null || end == null) throw new IllegalStateException("Activity window is half saved");
        return Optional.of(new ActivityLimit.Window(java.time.LocalTime.parse(start), java.time.LocalTime.parse(end)));
    }

    public CompletableFuture<SharedProject> createProject(SharedProject project) {
        Objects.requireNonNull(project, "project");
        return submit(() -> transaction(() -> {
            try (PreparedStatement insert = database.prepareStatement(
                    "INSERT INTO shared_projects (project_id, name, description, status, revision, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, project.id().id());
                insert.setString(2, project.name());
                insert.setString(3, project.description());
                insert.setString(4, project.state().name());
                insert.setLong(5, project.revision());
                insert.setLong(6, project.createdAtMs());
                insert.setLong(7, project.updatedAtMs());
                insert.executeUpdate();
            }
            for (SharedTask task : project.tasks().values()) {
                insertTask(task);
            }
            return project;
        }));
    }

    public CompletableFuture<SharedProject> saveProject(SharedProject project, long expectedRevision) {
        Objects.requireNonNull(project, "project");
        return submit(() -> transaction(() -> {
            try (PreparedStatement update = database.prepareStatement(
                    "UPDATE shared_projects SET name=?, description=?, status=?, revision=?, updated_at_ms=? WHERE project_id=? AND revision=?")) {
                update.setString(1, project.name());
                update.setString(2, project.description());
                update.setString(3, project.state().name());
                update.setLong(4, project.revision());
                update.setLong(5, project.updatedAtMs());
                update.setString(6, project.id().id());
                update.setLong(7, expectedRevision);
                if (update.executeUpdate() != 1) {
                    throw new ConcurrentModificationException("Project revision changed");
                }
            }
            return project;
        }));
    }

    public CompletableFuture<SharedTask> saveTask(SharedTask task, long expectedRevision) {
        Objects.requireNonNull(task, "task");
        return submit(() -> transaction(() -> {
            boolean exists;
            try (PreparedStatement check = database.prepareStatement("SELECT 1 FROM shared_tasks WHERE task_id = ?")) {
                check.setString(1, task.id().toString());
                try (ResultSet rs = check.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (!exists) {
                insertTask(task);
            } else {
                try (PreparedStatement update = database.prepareStatement(
                        "UPDATE shared_tasks SET title=?, skill=?, arguments_json=?, dependencies_json=?, assigned_player_id=?, status=?, claimed_at_tick=?, lease_duration_ticks=?, completion_evidence=?, revision=?, updated_at_ms=? WHERE task_id=? AND revision=?")) {
                    update.setString(1, task.title());
                    update.setString(2, task.skill());
                    update.setString(3, encodeArguments(task.arguments()));
                    update.setString(4, encodeDependencies(task.dependencies()));
                    update.setString(5, task.assignedPlayerId() == null ? null : task.assignedPlayerId().toString());
                    update.setString(6, task.state().name());
                    update.setLong(7, task.claimedAtTick());
                    update.setLong(8, task.leaseDurationTicks());
                    update.setString(9, task.completionEvidence());
                    update.setLong(10, task.revision());
                    update.setLong(11, Instant.now().toEpochMilli());
                    update.setString(12, task.id().toString());
                    update.setLong(13, expectedRevision);
                    if (update.executeUpdate() != 1) {
                        throw new ConcurrentModificationException("Task revision changed");
                    }
                }
            }
            if (task.stockReservation().isPresent()) {
                StockReservation res = task.stockReservation().get();
                saveReservationInternal(res);
            }
            return task;
        }));
    }

    public CompletableFuture<StockReservation> saveReservation(StockReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        return submit(() -> transaction(() -> {
            saveReservationInternal(reservation);
            return reservation;
        }));
    }

    private void saveReservationInternal(StockReservation res) throws SQLException {
        boolean exists;
        try (PreparedStatement check = database.prepareStatement("SELECT 1 FROM stock_reservations WHERE reservation_id = ?")) {
            check.setString(1, res.reservationId().toString());
            try (ResultSet rs = check.executeQuery()) {
                exists = rs.next();
            }
        }
        if (!exists) {
            try (PreparedStatement insert = database.prepareStatement(
                    "INSERT INTO stock_reservations (reservation_id, task_id, project_id, player_id, item_id, quantity, destination, status, revision, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, res.reservationId().toString());
                insert.setString(2, res.taskId().toString());
                insert.setString(3, res.projectId().id());
                insert.setString(4, res.assignedPlayerId() == null ? null : res.assignedPlayerId().toString());
                insert.setString(5, res.itemId());
                insert.setInt(6, res.quantity());
                insert.setString(7, res.destination());
                insert.setString(8, res.status().name());
                insert.setLong(9, res.revision());
                long now = Instant.now().toEpochMilli();
                insert.setLong(10, now);
                insert.setLong(11, now);
                insert.executeUpdate();
            }
        } else {
            try (PreparedStatement update = database.prepareStatement(
                    "UPDATE stock_reservations SET status=?, revision=?, updated_at_ms=? WHERE reservation_id=?")) {
                update.setString(1, res.status().name());
                update.setLong(2, res.revision());
                update.setLong(3, Instant.now().toEpochMilli());
                update.setString(4, res.reservationId().toString());
                update.executeUpdate();
            }
        }
    }

    private void insertTask(SharedTask task) throws SQLException {
        try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO shared_tasks (task_id, project_id, title, skill, arguments_json, dependencies_json, assigned_player_id, status, claimed_at_tick, lease_duration_ticks, completion_evidence, revision, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, task.id().toString());
            insert.setString(2, task.projectId().id());
            insert.setString(3, task.title());
            insert.setString(4, task.skill());
            insert.setString(5, encodeArguments(task.arguments()));
            insert.setString(6, encodeDependencies(task.dependencies()));
            insert.setString(7, task.assignedPlayerId() == null ? null : task.assignedPlayerId().toString());
            insert.setString(8, task.state().name());
            insert.setLong(9, task.claimedAtTick());
            insert.setLong(10, task.leaseDurationTicks());
            insert.setString(11, task.completionEvidence());
            insert.setLong(12, task.revision());
            long now = Instant.now().toEpochMilli();
            insert.setLong(13, now);
            insert.setLong(14, now);
            insert.executeUpdate();
        }
        if (task.stockReservation().isPresent()) {
            saveReservationInternal(task.stockReservation().get());
        }
    }

    public CompletableFuture<Optional<SharedProject>> findProject(ProjectId id) {
        Objects.requireNonNull(id, "id");
        return submit(() -> {
            try (PreparedStatement query = database.prepareStatement("SELECT * FROM shared_projects WHERE project_id = ?")) {
                query.setString(1, id.id());
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    SharedProject proj = readProject(rs);
                    Map<TaskId, SharedTask> tasks = readTasksForProject(id);
                    return Optional.of(new SharedProject(proj.id(), proj.name(), proj.description(), tasks, proj.state(), proj.revision(), proj.createdAtMs(), proj.updatedAtMs()));
                }
            }
        });
    }

    public CompletableFuture<List<SharedProject>> listProjects() {
        return submit(() -> {
            List<SharedProject> list = new ArrayList<>();
            try (Statement query = database.createStatement();
                 ResultSet rs = query.executeQuery("SELECT * FROM shared_projects ORDER BY created_at_ms")) {
                while (rs.next()) {
                    SharedProject proj = readProject(rs);
                    Map<TaskId, SharedTask> tasks = readTasksForProject(proj.id());
                    list.add(new SharedProject(proj.id(), proj.name(), proj.description(), tasks, proj.state(), proj.revision(), proj.createdAtMs(), proj.updatedAtMs()));
                }
            }
            return List.copyOf(list);
        });
    }

    private SharedProject readProject(ResultSet rs) throws SQLException {
        ProjectId id = ProjectId.of(rs.getString("project_id"));
        String name = rs.getString("name");
        String desc = rs.getString("description");
        SharedProject.ProjectState state = SharedProject.ProjectState.valueOf(rs.getString("status"));
        long rev = rs.getLong("revision");
        long created = rs.getLong("created_at_ms");
        long updated = rs.getLong("updated_at_ms");
        return new SharedProject(id, name, desc, Map.of(), state, rev, created, updated);
    }

    private Map<TaskId, SharedTask> readTasksForProject(ProjectId projectId) throws SQLException {
        Map<TaskId, SharedTask> map = new HashMap<>();
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM shared_tasks WHERE project_id = ?")) {
            query.setString(1, projectId.id());
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    TaskId tid = TaskId.of(UUID.fromString(rs.getString("task_id")));
                    String title = rs.getString("title");
                    String skill = rs.getString("skill");
                    Map<String, String> args = decodeArguments(rs.getString("arguments_json"));
                    Set<TaskId> deps = decodeDependencies(rs.getString("dependencies_json"));
                    String assigneeStr = rs.getString("assigned_player_id");
                    UUID assignee = assigneeStr == null ? null : UUID.fromString(assigneeStr);
                    SharedTask.TaskState state = SharedTask.TaskState.valueOf(rs.getString("status"));
                    long claimedTick = rs.getLong("claimed_at_tick");
                    long leaseTick = rs.getLong("lease_duration_ticks");
                    String evidence = rs.getString("completion_evidence");
                    long rev = rs.getLong("revision");

                    Optional<StockReservation> res = readReservationForTask(tid);
                    SharedTask task = new SharedTask(tid, projectId, title, skill, args, deps, assignee, state, res, rev, claimedTick, leaseTick, evidence);
                    map.put(tid, task);
                }
            }
        }
        return map;
    }

    private Optional<StockReservation> readReservationForTask(TaskId taskId) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM stock_reservations WHERE task_id = ? AND status = 'ACTIVE'")) {
            query.setString(1, taskId.toString());
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                UUID resId = UUID.fromString(rs.getString("reservation_id"));
                ProjectId pid = ProjectId.of(rs.getString("project_id"));
                String pStr = rs.getString("player_id");
                UUID pidOwner = pStr == null ? null : UUID.fromString(pStr);
                String itemId = rs.getString("item_id");
                int qty = rs.getInt("quantity");
                String dest = rs.getString("destination");
                StockReservation.ReservationStatus status = StockReservation.ReservationStatus.valueOf(rs.getString("status"));
                long rev = rs.getLong("revision");
                return Optional.of(new StockReservation(resId, taskId, pid, pidOwner, itemId, qty, dest, status, rev));
            }
        }
    }

    private static String encodeDependencies(Set<TaskId> deps) {
        if (deps == null || deps.isEmpty()) return "";
        List<String> list = new ArrayList<>();
        for (TaskId tid : deps) {
            list.add(tid.toString());
        }
        return String.join(",", list);
    }

    private static Set<TaskId> decodeDependencies(String str) {
        if (str == null || str.isBlank()) return Set.of();
        Set<TaskId> set = new java.util.HashSet<>();
        for (String part : str.split(",")) {
            if (!part.isBlank()) set.add(TaskId.of(UUID.fromString(part.trim())));
        }
        return Collections.unmodifiableSet(set);
    }

    private static String encodeArguments(Map<String, String> args) {
        if (args == null || args.isEmpty()) return "";
        List<String> list = new ArrayList<>();
        for (var e : args.entrySet()) {
            list.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return String.join("&", list);
    }

    private static Map<String, String> decodeArguments(String str) {
        if (str == null || str.isBlank()) return Map.of();
        Map<String, String> map = new HashMap<>();
        for (String pair : str.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Mannerisms readMannerisms(ResultSet row) throws SQLException {
        return new Mannerisms(UUID.fromString(row.getString("companion_id")), Map.of(
                Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.valueOf(row.getString("sprintjump")),
                Mannerisms.Quirk.LIVELY, Mannerisms.Setting.valueOf(row.getString("lively")),
                Mannerisms.Quirk.CURIOUSGLANCE, Mannerisms.Setting.valueOf(row.getString("curiousglance")),
                Mannerisms.Quirk.STEADY, Mannerisms.Setting.valueOf(row.getString("steady"))), row.getLong("revision"));
    }

    private static SocialProfile readProfile(ResultSet row) throws SQLException {
        return new SocialProfile(UUID.fromString(row.getString("companion_id")), new Personality(
                row.getInt("initiative"), row.getInt("caution"), row.getInt("curiosity"),
                row.getInt("sociability"), row.getInt("humor"), row.getInt("combativeness"), row.getInt("helpfulness"),
                row.getInt("farming"), row.getInt("mining"), row.getLong("personality_revision")),
                row.getBoolean("ambient_enabled"), row.getBoolean("public_conversation"), row.getLong("revision"),
                Expression.parse(row.getString("expression")).orElse(null),
                dev.kindred.core.social.ChatStyle.parse(row.getString("chat_style")).orElse(null));
    }

    public CompletableFuture<JournalEvent> appendEvent(UUID companionId, Instant observedAt,
                                                       String type, String detail) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(detail, "detail");
        if (type.isBlank() || type.length() > 128 || detail.length() > 4096) {
            throw new IllegalArgumentException("Event type or detail exceeds metadata bounds");
        }
        return submit(() -> insertEvent(companionId, observedAt, type, detail));
    }

    public CompletableFuture<List<JournalEvent>> eventsSince(UUID companionId, long sequence, int limit) {
        Objects.requireNonNull(companionId, "companionId");
        if (sequence < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid journal cursor or limit");
        return submit(() -> {
            List<JournalEvent> result = new ArrayList<>();
            try (PreparedStatement query = database.prepareStatement(
                    "SELECT * FROM journal_events WHERE companion_id = ? AND sequence > ? ORDER BY sequence LIMIT ?")) {
                query.setString(1, companionId.toString());
                query.setLong(2, sequence);
                query.setInt(3, limit);
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) result.add(new JournalEvent(row.getLong("sequence"), companionId,
                            Instant.ofEpochMilli(row.getLong("observed_at_ms")), row.getString("type"), row.getString("detail")));
                }
            }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<BudgetState> openBudget(UUID companionId, AccountingPeriod period) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(period, "period");
        requireMilliseconds(period.startInclusive());
        requireMilliseconds(period.endExclusive());
        return submit(() -> transaction(() -> {
            required(companionId);
            return openPeriod(companionId, period, latestBudget(companionId));
        }));
    }

    /**
     * The budget activity at now is charged to, chosen from the latest saved period in the transaction that opens a
     * new one (ScheduleClock.transition). Nothing is written while the clock is behind that period or a zone edit
     * waits for its first whole local day; the result says when one opens. An open period is returned as saved, so
     * neither a restart nor a zone edit replenishes it.
     */
    public CompletableFuture<ChargeableBudget> chargeableBudget(UUID companionId, Instant now, ZoneId zone) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        return submit(() -> transaction(() -> {
            required(companionId);
            Optional<BudgetState> latest = latestBudget(companionId);
            PeriodTransition transition = ScheduleClock.transition(now, zone, latest.map(BudgetState::period));
            return new ChargeableBudget(transition, switch (transition.kind()) {
                case CURRENT -> latest;
                case OPEN -> Optional.of(openPeriod(companionId, transition.period().orElseThrow(), latest));
                case CLOCK_BEHIND, ZONE_PENDING -> Optional.empty();
            });
        }));
    }

    /** The latest saved accounting period, whose end closes every earlier one. */
    public CompletableFuture<Optional<AccountingPeriod>> latestPeriod(UUID companionId) {
        Objects.requireNonNull(companionId, "companionId");
        return submit(() -> latestBudget(companionId).map(BudgetState::period));
    }

    private BudgetState openPeriod(UUID companionId, AccountingPeriod period, Optional<BudgetState> latest) throws SQLException {
        requireMilliseconds(period.startInclusive());
        requireMilliseconds(period.endExclusive());
        if (latest.isPresent()) {
            BudgetState previous = latest.orElseThrow();
            if (previous.period().equals(period)) return previous;
            if (period.startInclusive().isBefore(previous.period().endExclusive())) {
                throw new IllegalArgumentException("Accounting periods cannot overlap or reopen an earlier period");
            }
            if (previous.outstanding().isPresent()) {
                throw new IllegalStateException("Settle the previous period before opening a new one");
            }
        }
        BudgetState empty = BudgetState.empty(companionId, period);
        try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO budget_buckets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindBudget(insert, empty);
            insert.executeUpdate();
        }
        return empty;
    }

    public CompletableFuture<Optional<BudgetState>> findBudget(UUID companionId, Instant start) {
        Objects.requireNonNull(companionId, "companionId");
        requireMilliseconds(start);
        return submit(() -> selectBudget(companionId, start));
    }

    public CompletableFuture<BudgetState> saveBudget(BudgetState state, long expectedSequence) {
        Objects.requireNonNull(state, "state");
        validatePrecision(state);
        return submit(() -> transaction(() -> {
            BudgetState previous = selectBudget(state.companionId(), state.period().startInclusive())
                    .orElseThrow(() -> new IllegalArgumentException("Open the budget period before saving it"));
            requireRevision(previous.sequence(), expectedSequence);
            if (!previous.period().equals(state.period())) throw new IllegalArgumentException("Accounting period is immutable");
            if (state.equals(previous)) return previous;
            validateTransition(previous, state);
            if (!latestBudget(state.companionId()).orElseThrow().period().equals(state.period())) {
                throw new IllegalArgumentException("Closed accounting periods cannot be updated");
            }
            updateBudget(state, expectedSequence);
            return state;
        }));
    }

    /** Rejects new submissions immediately and closes only after all accepted operations finish. */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closing = true;
        stopChecks(); // F-31f: a check off the writer never holds a close.
        closeFuture = new CompletableFuture<>();
        writer.execute(() -> {
            try {
                releaseResources();
                closeFuture.complete(null);
            } catch (Exception failure) {
                closeFuture.completeExceptionally(failure);
            }
        });
        writer.shutdown();
        return closeFuture;
    }

    private void initialize(Path requested) throws Exception {
        Path absolute = requested.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path path = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        if (Files.exists(path)) path = path.toRealPath();
        lockChannel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        fileLock = lockChannel.tryLock();
        if (fileLock == null) throw new IllegalStateException("This metadata store is already open");
        // Fabric may invoke open from a worker whose context loader cannot discover mod service
        // providers. Resolve the bundled JDBC driver through this module's defining loader.
        Class.forName("org.sqlite.JDBC", true, SqliteCompanionStore.class.getClassLoader());
        database = DriverManager.getConnection("jdbc:sqlite:" + path);
        databasePath = path;
        try (Statement statement = database.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
            // F-31f: the agreement check reads from a snapshot of its own, and while it reads no checkpoint can pass it,
            // so the log grows with what is written meanwhile (about 60 MB over a 25-second check at thirty days). Once a
            // checkpoint is past it, the log is cut back to this, its size between checks (#227: about 4 MB).
            statement.execute("PRAGMA journal_size_limit = " + JOURNAL_SIZE_LIMIT);
            int schemaVersion;
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                schemaVersion = version.next() ? version.getInt(1) : -1;
            }
            if (schemaVersion < 0 || schemaVersion > 14) {
                throw new IllegalStateException("Unsupported metadata schema version " + schemaVersion);
            }
            if (schemaVersion == 0) migrate(1, "V1__foundation.sql");
            if (schemaVersion < 2) migrate(2, "V2__social_profiles.sql");
            if (schemaVersion < 3) migrate(3, "V3__personality_preferences.sql");
            if (schemaVersion < 4) migrate(4, "V4__mannerisms.sql");
            if (schemaVersion < 5) migrate(5, "V5__work_authority.sql");
            if (schemaVersion < 6) migrate(6, "V6__intentions.sql");
            if (schemaVersion < 7) migrate(7, "V7__native_players.sql");
            if (schemaVersion < 8) migrate(8, "V8__shared_projects.sql");
            if (schemaVersion < 9) migrate(9, "V9__activity_windows.sql");
            if (schemaVersion < 10) migrate(10, "V10__expression.sql");
            if (schemaVersion < 11) migrate(11, "V11__rough_talk.sql");
            if (schemaVersion < 12) migrate(12, "V12__world_record.sql");
            if (schemaVersion < 13) migrate(13, "V13__action_fold.sql");
            if (schemaVersion < 14) migrate(14, "V14__action_bound.sql");
        }
        recover();
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("SELECT COALESCE(MAX(id), 0) FROM world_events")) {
            long last = row.next() ? row.getLong(1) : 0;
            synchronized (written) {
                writtenThrough = Math.max(writtenThrough, last);
            }
        }
    }

    // ----- The Record's growth, for the cost gauge -----

    /**
     * What the World Record has been given since this store opened, by kind: rows and characters (the data as encoded,
     * the subject and the place), and the last id it holds. Every insert counts, the journal's and the action ledger's
     * included (each written in its own row's transaction), and only once its transaction has committed.
     */
    public record Growth(Map<String, long[]> byKind, long rows, long characters, long through) {
        public Growth {
            var copy = new java.util.TreeMap<String, long[]>();
            byKind.forEach((k, v) -> copy.put(k, v.clone()));
            byKind = java.util.Collections.unmodifiableMap(copy);
        }
    }

    /** Committed counts, guarded by itself; read from any thread. */
    private final Map<String, long[]> written = new java.util.TreeMap<>();
    private long writtenThrough;
    /** Inserts of the transaction now open, on the writer thread only; counted at its commit, dropped at a rollback. */
    private final Map<String, long[]> pending = new HashMap<>();
    private long pendingThrough;

    /** The Record's growth since this store opened. */
    public Growth growth() {
        synchronized (written) {
            long rows = 0, chars = 0;
            for (long[] v : written.values()) {
                rows += v[0];
                chars += v[1];
            }
            return new Growth(written, rows, chars, writtenThrough);
        }
    }

    private void counted(WorldEvent event) throws SQLException {
        long chars = EventData.encode(event.data()).length() + event.subject().length() + event.place().length();
        var into = database.getAutoCommit() ? new HashMap<String, long[]>() : pending;
        var k = into.computeIfAbsent(event.kind(), x -> new long[2]);
        k[0]++;
        k[1] += chars;
        pendingThrough = Math.max(pendingThrough, event.id());
        if (into != pending) commitCounts(into);
    }

    private void commitCounts(Map<String, long[]> counts) {
        synchronized (written) {
            counts.forEach((kind, v) -> {
                var k = written.computeIfAbsent(kind, x -> new long[2]);
                k[0] += v[0];
                k[1] += v[1];
            });
            writtenThrough = Math.max(writtenThrough, pendingThrough);
        }
        counts.clear();
    }

    private void migrate(int version, String resource) throws Exception {
        String schema;
        try (var stream = SqliteCompanionStore.class.getResourceAsStream("/db/migration/" + resource)) {
            if (stream == null) throw new IOException("Missing schema migration");
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        transaction(() -> {
            try (Statement statement = database.createStatement()) {
                for (String command : schema.split(";")) if (!command.isBlank()) statement.execute(command);
                if (version == 12) {
                    // The World Record is append-only (F-31): the database itself refuses an update or a delete.
                    statement.execute("CREATE TRIGGER world_events_append_only_update BEFORE UPDATE ON world_events BEGIN SELECT RAISE(ABORT, 'The World Record is append-only'); END");
                    statement.execute("CREATE TRIGGER world_events_append_only_delete BEFORE DELETE ON world_events BEGIN SELECT RAISE(ABORT, 'The World Record is append-only'); END");
                }
                if (version == 13) {
                    // Settled action history may be compacted (the action fold); every other kind stays append-only.
                    statement.execute("DROP TRIGGER IF EXISTS world_events_append_only_delete");
                    statement.execute("CREATE TRIGGER world_events_append_only_delete BEFORE DELETE ON world_events WHEN OLD.kind <> '"
                            + ActionLedgerView.ACTION + "' BEGIN SELECT RAISE(ABORT, 'The World Record is append-only'); END");
                }
                if (version == 14) {
                    // The action bound (F-31b1): a folded summary may be deleted only once its ledger row is gone, as the
                    // bound's prune deletes them (the row first, in one transaction); every other kind stays append-only.
                    statement.execute("DROP TRIGGER IF EXISTS world_events_append_only_delete");
                    statement.execute("CREATE TRIGGER world_events_append_only_delete BEFORE DELETE ON world_events WHEN OLD.kind <> '"
                            + ActionLedgerView.ACTION + "' AND NOT (OLD.kind = '" + ActionLedgerView.FOLDED
                            + "' AND NOT EXISTS (SELECT 1 FROM action_ledger WHERE action_id = OLD.subject))"
                            + " BEGIN SELECT RAISE(ABORT, 'The World Record is append-only'); END");
                }
                statement.execute("PRAGMA user_version = " + version);
            }
            return null;
        });
    }

    private void recover() throws Exception {
        transaction(() -> {
            for (StoredCompanion stored : selectAll()) {
                Companion companion = stored.companion();
                LifecycleState state = companion.lifecycle();
                if (state == LifecycleState.ACTIVE || state == LifecycleState.RETURNING
                        || state == LifecycleState.WAKING || state == LifecycleState.WAITING) {
                    Companion recovered = companion.withLifecycle(companion.stopLatched()
                            ? LifecycleState.PAUSED : LifecycleState.SLEEPING);
                    updateCompanion(recovered, Math.incrementExact(stored.revision()), stored.revision());
                    insertEvent(companion.id(), Instant.now(), "STARTUP_RECONCILIATION",
                            "Restored dormant metadata; native world reconciliation is required before work");
                }
            }
            List<BudgetState> outstanding = new ArrayList<>();
            try (Statement query = database.createStatement();
                 ResultSet row = query.executeQuery("SELECT * FROM budget_buckets WHERE reservation_id IS NOT NULL")) {
                while (row.next()) outstanding.add(readBudget(row));
            }
            for (BudgetState previous : outstanding) {
                BudgetState recovered = previous.recoverAfterCrash();
                updateBudget(recovered, previous.sequence());
                insertEvent(previous.companionId(), Instant.now(), "RESERVATION_RECOVERED",
                        "Conservatively charged " + previous.outstanding().orElseThrow().reservedMillis()
                                + " ms; observed active duration unchanged");
            }
            // An intent that was in flight when the process died has an unknown outcome until the world is inspected.
            List<ActionRecord> inFlight = new ArrayList<>();
            try (Statement query = database.createStatement();
                 ResultSet row = query.executeQuery("SELECT * FROM action_ledger WHERE state IN ('PREPARED', 'DISPATCHED')")) {
                while (row.next()) inFlight.add(readAction(row));
            }
            for (ActionRecord record : inFlight) {
                ActionRecord uncertain = record.uncertain("Startup reconciliation: the process ended before this action's outcome was recorded");
                try (PreparedStatement update = database.prepareStatement(
                        "UPDATE action_ledger SET state=?, outcome=?, detail=?, revision=?, updated_at_ms=? WHERE action_id=? AND revision=?")) {
                    update.setString(1, uncertain.state().name());
                    update.setString(2, uncertain.outcome().name());
                    update.setString(3, uncertain.detail());
                    update.setLong(4, uncertain.revision());
                    update.setLong(5, Instant.now().toEpochMilli());
                    update.setString(6, record.actionId().toString());
                    update.setLong(7, record.revision());
                    update.executeUpdate();
                }
                insertWorldEvent(actionEvent(uncertain, Instant.now().toEpochMilli()));
                insertEvent(record.companionId(), Instant.now(), "ACTION_UNCERTAIN", record.kind() + " " + record.actionId() + ": " + uncertain.detail());
            }

            // In-flight shared task and stock reservation recovery (E-11). A task whose player now has an action of
            // unknown outcome is held, with its reservation, until that player is reconciled: reopened at once, another
            // player could spend what the uncertain action may already have spent. Every other task in flight is
            // reopened and its reservation released, as is any reservation left active by a task no longer in flight.
            Set<String> uncertain = new HashSet<>();
            try (Statement query = database.createStatement();
                 ResultSet rows = query.executeQuery("SELECT DISTINCT companion_id FROM action_ledger WHERE state = 'UNCERTAIN'")) {
                while (rows.next()) uncertain.add(rows.getString(1));
            }
            Set<String> held = new HashSet<>();
            List<String[]> inFlightTasks = new ArrayList<>();
            try (Statement query = database.createStatement();
                 ResultSet rows = query.executeQuery("SELECT task_id, assigned_player_id FROM shared_tasks WHERE status IN ('CLAIMED', 'IN_PROGRESS')")) {
                while (rows.next()) inFlightTasks.add(new String[] {rows.getString(1), rows.getString(2)});
            }
            for (String[] row : inFlightTasks) {
                String tId = row[0], player = row[1];
                if (player != null && uncertain.contains(player)) {
                    held.add(tId);
                    insertEvent(UUID.fromString(player), Instant.now(), "TEAM_TASK_HELD",
                            "Shared task " + tId + " held with its reservation: an action of this player's is uncertain");
                    continue;
                }
                try (PreparedStatement update = database.prepareStatement(
                        "UPDATE shared_tasks SET status='OPEN', assigned_player_id=NULL, revision=revision+1, updated_at_ms=? WHERE task_id=?")) {
                    update.setLong(1, Instant.now().toEpochMilli());
                    update.setString(2, tId);
                    update.executeUpdate();
                }
            }
            List<String[]> activeRes = new ArrayList<>();
            try (Statement query = database.createStatement();
                 ResultSet rows = query.executeQuery("SELECT reservation_id, task_id FROM stock_reservations WHERE status = 'ACTIVE'")) {
                while (rows.next()) activeRes.add(new String[] {rows.getString(1), rows.getString(2)});
            }
            for (String[] row : activeRes) {
                if (row[1] != null && held.contains(row[1])) continue;
                try (PreparedStatement update = database.prepareStatement(
                        "UPDATE stock_reservations SET status='RELEASED', revision=revision+1, updated_at_ms=? WHERE reservation_id=?")) {
                    update.setLong(1, Instant.now().toEpochMilli());
                    update.setString(2, row[0]);
                    update.executeUpdate();
                }
            }
            return null;
        });
    }

    private Optional<StoredCompanion> select(UUID id) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM companions WHERE id = ?")) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(readCompanion(row)) : Optional.empty(); }
        }
    }

    private StoredCompanion required(UUID id) throws SQLException {
        return select(id).orElseThrow(() -> new IllegalArgumentException("Unknown companion " + id));
    }

    private Optional<StoredCompanion> selectByOwner(UUID ownerId) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM companions WHERE owner_id = ?")) {
            query.setString(1, ownerId.toString());
            try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(readCompanion(row)) : Optional.empty(); }
        }
    }

    private List<StoredCompanion> selectAll() throws SQLException {
        List<StoredCompanion> result = new ArrayList<>();
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("SELECT * FROM companions ORDER BY id")) {
            while (row.next()) result.add(readCompanion(row));
        }
        return List.copyOf(result);
    }

    private static StoredCompanion readCompanion(ResultSet row) throws SQLException {
        String entityId = row.getString("entity_id");
        Companion companion = new Companion(UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("owner_id")), row.getString("display_name"),
                UUID.fromString(row.getString("world_id")), row.getString("dimension"),
                entityId == null ? null : UUID.fromString(entityId), LifecycleState.valueOf(row.getString("lifecycle")),
                row.getLong("intent_revision"), row.getLong("task_revision"), row.getBoolean("stop_latched"));
        return new StoredCompanion(companion, row.getLong("revision"));
    }

    private static void bindCompanion(PreparedStatement statement, Companion companion, long revision) throws SQLException {
        statement.setString(1, companion.id().toString());
        statement.setString(2, companion.ownerId().toString());
        statement.setString(3, companion.displayName());
        statement.setString(4, companion.worldId().toString());
        statement.setString(5, companion.dimension());
        statement.setString(6, companion.entityId() == null ? null : companion.entityId().toString());
        statement.setString(7, companion.lifecycle().name());
        statement.setLong(8, companion.intentRevision());
        statement.setLong(9, companion.taskRevision());
        statement.setBoolean(10, companion.stopLatched());
        statement.setLong(11, revision);
    }

    private void updateCompanion(Companion companion, long revision, long expected) throws SQLException {
        try (PreparedStatement update = database.prepareStatement("UPDATE companions SET id=?, owner_id=?, display_name=?, "
                + "world_id=?, dimension=?, entity_id=?, lifecycle=?, intent_revision=?, task_revision=?, stop_latched=?, revision=? "
                + "WHERE id=? AND revision=?")) {
            bindCompanion(update, companion, revision);
            update.setString(12, companion.id().toString());
            update.setLong(13, expected);
            if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Companion revision changed");
        }
    }

    private JournalEvent insertEvent(UUID id, Instant at, String type, String detail) throws SQLException {
        try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO journal_events(companion_id, observed_at_ms, type, detail) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, id.toString());
            insert.setLong(2, at.toEpochMilli());
            insert.setString(3, type);
            insert.setString(4, detail);
            insert.executeUpdate();
        }
        long sequence;
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("SELECT last_insert_rowid()")) {
            if (!row.next()) throw new SQLException("Missing journal sequence");
            sequence = row.getLong(1);
        }
        // The journal is a kind of event (F-31): the same line in the World Record, in the same transaction.
        var data = new java.util.LinkedHashMap<String, String>();
        data.put("sequence", String.valueOf(sequence));
        data.put("detail", detail.length() > 4_000 ? detail.substring(0, 4_000) : detail);
        if (detail.length() > 4_000) data.put("truncated", "1");
        insertWorldEvent(WorldEvent.recorded(-1, at.toEpochMilli(), "", dev.kindred.core.record.ActionLedgerView.JOURNAL, id, type.length() > WorldEvent.MAX_TEXT ? type.substring(0, WorldEvent.MAX_TEXT) : type, "", data));
        return new JournalEvent(sequence, id, Instant.ofEpochMilli(at.toEpochMilli()), type, detail);
    }

    // ----- The World Record (F-31) -----

    /** An action-ledger row as its event: the action ledger is a kind of event, written in the row's own transaction. */
    private static WorldEvent actionEvent(ActionRecord record, long atMs) {
        var data = new java.util.LinkedHashMap<String, String>();
        data.put("state", record.state().name());
        data.put("revision", String.valueOf(record.revision()));
        data.put("kind", record.kind());
        data.put("outcome", record.outcome().name());
        return WorldEvent.recorded(-1, atMs, "", ActionLedgerView.ACTION, record.companionId(), record.actionId().toString(), "", data);
    }

    private WorldEvent insertWorldEvent(WorldEvent draft) throws SQLException {
        if (draft.id() != 0) throw new IllegalArgumentException("Only an unwritten event is recorded");
        try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO world_events(tick, real_time_ms, world, kind, actor, subject, place, data, how_known) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setLong(1, draft.tick());
            insert.setLong(2, draft.realTimeMs());
            insert.setString(3, draft.world());
            insert.setString(4, draft.kind());
            insert.setString(5, draft.actor() == null ? null : draft.actor().toString());
            insert.setString(6, draft.subject());
            insert.setString(7, draft.place());
            insert.setString(8, EventData.encode(draft.data()));
            insert.setString(9, draft.howKnown().code());
            insert.executeUpdate();
        }
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("SELECT last_insert_rowid()")) {
            if (!row.next()) throw new SQLException("Missing event id");
            var event = draft.withId(row.getLong(1));
            counted(event);
            return event;
        }
    }

    private static WorldEvent readWorldEvent(ResultSet row) throws SQLException {
        String actor = row.getString("actor");
        return new WorldEvent(row.getLong("id"), row.getLong("tick"), row.getLong("real_time_ms"), row.getString("world"), row.getString("kind"),
                actor == null ? null : UUID.fromString(actor), row.getString("subject"), row.getString("place"),
                EventData.decode(row.getString("data")), WorldEvent.HowKnown.parse(row.getString("how_known")));
    }

    /**
     * Appends events to the World Record, all or none, in order, through this ordered writer: the only way anything is
     * written there. Each returned event carries its id.
     */
    public CompletableFuture<List<WorldEvent>> record(List<WorldEvent> drafts) {
        List<WorldEvent> copy = List.copyOf(drafts);
        if (copy.isEmpty()) return CompletableFuture.completedFuture(List.of());
        for (WorldEvent draft : copy) if (draft.id() != 0) throw new IllegalArgumentException("Only an unwritten event is recorded");
        // Only a registered kind is written (F-31, event kinds as a registry): all or none, the batch refused, not the tick.
        for (WorldEvent draft : copy) {
            if (!dev.kindred.core.record.EventKinds.known(draft.kind())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unregistered event kind " + draft.kind()));
            }
        }
        return submit(() -> transaction(() -> {
            var written = new ArrayList<WorldEvent>(copy.size());
            for (WorldEvent draft : copy) written.add(insertWorldEvent(draft));
            return List.copyOf(written);
        }));
    }

    /** Appends one event to the World Record. */
    public CompletableFuture<WorldEvent> record(WorldEvent draft) {
        return record(List.of(draft)).thenApply(List::getFirst);
    }

    /** Up to {@code limit} events after {@code afterId}, of {@code kinds} (every kind when empty), in the Record's order. */
    public CompletableFuture<List<WorldEvent>> worldEvents(Set<String> kinds, long afterId, int limit) {
        Set<String> wanted = Set.copyOf(kinds);
        if (afterId < 0 || limit < 1 || limit > 10_000) throw new IllegalArgumentException("Invalid Record cursor or limit");
        return submit(() -> selectWorldEvents(wanted, afterId, limit));
    }

    /** Every event of {@code kinds} (every kind when empty), in the Record's order, read in pages on the writer. */
    public CompletableFuture<List<WorldEvent>> allWorldEvents(Set<String> kinds) {
        Set<String> wanted = Set.copyOf(kinds);
        return submit(() -> {
            var all = new ArrayList<WorldEvent>();
            long after = 0;
            while (true) {
                List<WorldEvent> page = selectWorldEvents(wanted, after, 5_000);
                all.addAll(page);
                if (page.size() < 5_000) return List.copyOf(all);
                after = page.getLast().id();
            }
        });
    }

    private List<WorldEvent> selectWorldEvents(Set<String> kinds, long afterId, int limit) throws SQLException {
        String filter = kinds.isEmpty() ? "" : " AND kind IN (" + String.join(", ", Collections.nCopies(kinds.size(), "?")) + ")";
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM world_events WHERE id > ?" + filter + " ORDER BY id LIMIT ?")) {
            int index = 1;
            query.setLong(index++, afterId);
            for (String kind : new java.util.TreeSet<>(kinds)) query.setString(index++, kind);
            query.setInt(index, limit);
            var out = new ArrayList<WorldEvent>();
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) out.add(readWorldEvent(rows)); }
            return out;
        }
    }

    /** What one fold did: the actions folded, the events they had, and how long the writer spent on it. */
    public record ActionFold(int actions, int eventsRemoved, long millis) { }

    /** The lowest action subject's first event id the next fold looks after (actions skipped before it stay skipped). */
    private long foldCursor;

    /**
     * The action fold (F-31, from F-33's month-long cost check): on the ordered writer, the history of up to {@code max}
     * settled actions whose last event is older than {@code olderThanMs} replaced, in one transaction, by one
     * {@link ActionLedgerView#FOLDED} event each: its final state, revision, kind and outcome, how many events it replaces
     * ({@code folded}) and when the first was ({@code first_ms}). Settled is verified, failed or cancelled; an action
     * that was ever uncertain, or is not settled, is left whole. The action ledger's own table is never touched, so the
     * agreement check still agrees. Only {@code action} events are ever deleted; every other kind stays as written.
     */
    public CompletableFuture<ActionFold> foldSettledActions(long olderThanMs, int max) {
        if (max < 1) throw new IllegalArgumentException("A fold takes at least one action");
        return submit(() -> {
            long start = System.nanoTime();
            var candidates = new ArrayList<String>();
            var firstIds = new ArrayList<Long>();
            try (PreparedStatement query = database.prepareStatement("SELECT subject, MIN(id) FROM world_events WHERE kind = ? GROUP BY subject"
                    + " HAVING MAX(real_time_ms) < ? AND MIN(id) > ? ORDER BY MIN(id) LIMIT ?")) {
                query.setString(1, ActionLedgerView.ACTION);
                query.setLong(2, olderThanMs);
                query.setLong(3, foldCursor);
                query.setInt(4, max);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) { candidates.add(rows.getString(1)); firstIds.add(rows.getLong(2)); }
                }
            }
            if (candidates.size() < max) foldCursor = 0; // Back to the start next time: newly settled actions behind it.
            else foldCursor = firstIds.getLast();
            int[] counts = new int[2];
            transaction(() -> {
                for (String subject : candidates) {
                    var events = new ArrayList<WorldEvent>();
                    try (PreparedStatement query = database.prepareStatement("SELECT * FROM world_events WHERE kind = ? AND subject = ? ORDER BY id")) {
                        query.setString(1, ActionLedgerView.ACTION);
                        query.setString(2, subject);
                        try (ResultSet rows = query.executeQuery()) { while (rows.next()) events.add(readWorldEvent(rows)); }
                    }
                    if (events.isEmpty()) continue;
                    WorldEvent last = events.getLast();
                    boolean settled = ActionLedgerView.settled(last.get("state", ""));
                    boolean wasUncertain = events.stream().anyMatch(e -> ActionRecord.State.UNCERTAIN.name().equals(e.get("state", "")));
                    if (!settled || wasUncertain) continue;
                    try (PreparedStatement delete = database.prepareStatement("DELETE FROM world_events WHERE kind = ? AND subject = ? AND id <= ?")) {
                        delete.setString(1, ActionLedgerView.ACTION);
                        delete.setString(2, subject);
                        delete.setLong(3, last.id());
                        delete.executeUpdate();
                    }
                    var data = new java.util.LinkedHashMap<String, String>(last.data());
                    data.put("folded", String.valueOf(events.size()));
                    data.put("first_ms", String.valueOf(events.getFirst().realTimeMs()));
                    insertWorldEvent(new WorldEvent(0, last.tick(), last.realTimeMs(), last.world(), ActionLedgerView.FOLDED, last.actor(), last.subject(),
                            last.place(), data, last.howKnown()));
                    counts[0]++;
                    counts[1] += events.size();
                }
                return null;
            });
            return new ActionFold(counts[0], counts[1], (System.nanoTime() - start) / 1_000_000);
        });
    }

    /** What one pass of the action bound pruned: actions, the game days tallied, and how long it held the writer. */
    public record ActionPrune(int actions, int days, long millis) { }

    /** A game day on the fold's clock, in real milliseconds at 20 ticks a second (action events carry no game tick). */
    public static final long GAME_DAY_MS = 24_000L / 20 * 1_000;

    /**
     * The action bound (F-31b1, Session A's decision): on the ordered writer, up to {@code max} settled actions whose
     * {@link ActionLedgerView#FOLDED} summary is older than {@code olderThanMs} removed, the row in {@code action_ledger}
     * and the summary together, in one transaction with one {@link ActionLedgerView#PRUNED} tally for each game day of the
     * fold's clock they began on: how many by state, and the first and last of them. Only after an agreement check that
     * agreed: {@code check} is that pass's, and one that did not agree prunes nothing, so a disagreement is never deleted
     * before it is seen. An action that is not settled or was ever uncertain is never folded and so never pruned; a row
     * that no longer matches its summary (state or revision) is left. Nothing else is deleted. A pass that takes its full
     * {@code max} leaves the rest to the next, so one game day can have several tallies: its figures are their sum.
     */
    public CompletableFuture<ActionPrune> pruneSettledActions(LedgerCheck check, long olderThanMs, int max) {
        if (max < 1) throw new IllegalArgumentException("A prune takes at least one action");
        if (check == null || !check.agrees()) return CompletableFuture.completedFuture(new ActionPrune(0, 0, 0));
        return submit(() -> {
            long start = System.nanoTime();
            var summaries = new ArrayList<WorldEvent>();
            try (PreparedStatement query = database.prepareStatement("SELECT * FROM world_events WHERE kind = ? AND real_time_ms < ? ORDER BY real_time_ms, id LIMIT ?")) {
                query.setString(1, ActionLedgerView.FOLDED);
                query.setLong(2, olderThanMs);
                query.setInt(3, max);
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) summaries.add(readWorldEvent(rows)); }
            }
            int[] pruned = new int[2];
            transaction(() -> {
                // Per game day of the first event: counts by state, and the first and last action by when they began.
                var days = new java.util.TreeMap<Long, java.util.LinkedHashMap<String, String>>();
                var edges = new HashMap<Long, long[]>();
                for (WorldEvent summary : summaries) {
                    String state = summary.get("state", "");
                    if (!ActionLedgerView.settled(state)) continue;
                    String subject = summary.subject();
                    String rowState;
                    long rowRevision;
                    try (PreparedStatement row = database.prepareStatement("SELECT state, revision FROM action_ledger WHERE action_id = ?")) {
                        row.setString(1, subject);
                        try (ResultSet found = row.executeQuery()) {
                            if (!found.next()) continue;
                            rowState = found.getString(1);
                            rowRevision = found.getLong(2);
                        }
                    }
                    if (!rowState.equals(state) || !String.valueOf(rowRevision).equals(summary.get("revision", ""))) continue;
                    try (PreparedStatement live = database.prepareStatement("SELECT 1 FROM world_events WHERE kind = ? AND subject = ? LIMIT 1")) {
                        live.setString(1, ActionLedgerView.ACTION);
                        live.setString(2, subject);
                        try (ResultSet found = live.executeQuery()) { if (found.next()) continue; }
                    }
                    try (PreparedStatement delete = database.prepareStatement("DELETE FROM action_ledger WHERE action_id = ?")) {
                        delete.setString(1, subject);
                        delete.executeUpdate();
                    }
                    try (PreparedStatement delete = database.prepareStatement("DELETE FROM world_events WHERE id = ? AND kind = ?")) {
                        delete.setLong(1, summary.id());
                        delete.setString(2, ActionLedgerView.FOLDED);
                        delete.executeUpdate();
                    }
                    long began = parseLong(summary.get("first_ms", ""), summary.realTimeMs());
                    long day = Math.floorDiv(began, GAME_DAY_MS);
                    var tally = days.computeIfAbsent(day, d -> new java.util.LinkedHashMap<>());
                    tally.merge(state.toLowerCase(java.util.Locale.ROOT), "1", (x, y) -> String.valueOf(Long.parseLong(x) + 1));
                    tally.merge("total", "1", (x, y) -> String.valueOf(Long.parseLong(x) + 1));
                    long[] edge = edges.computeIfAbsent(day, d -> new long[] {Long.MAX_VALUE, Long.MIN_VALUE});
                    if (began < edge[0]) { edge[0] = began; tally.put("first", subject); }
                    if (began >= edge[1]) { edge[1] = began; tally.put("last", subject); }
                    pruned[0]++;
                }
                long now = System.currentTimeMillis();
                for (var day : days.entrySet()) {
                    var data = new java.util.LinkedHashMap<String, String>();
                    data.put("day_start_ms", String.valueOf(day.getKey() * GAME_DAY_MS));
                    data.put("older_than_ms", String.valueOf(olderThanMs));
                    data.putAll(day.getValue());
                    insertWorldEvent(WorldEvent.recorded(-1, now, "", ActionLedgerView.PRUNED, null, String.valueOf(day.getKey()), "", data));
                }
                pruned[1] = days.size();
                return null;
            });
            return new ActionPrune(pruned[0], pruned[1], (System.nanoTime() - start) / 1_000_000);
        });
    }

    private static long parseLong(String text, long otherwise) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException notANumber) {
            return otherwise;
        }
    }

    /**
     * The agreement check for the action ledger (F-31): every row of {@code action_ledger}, its state and revision, against
     * the same ledger rebuilt from the Record's action events alone.
     */
    public CompletableFuture<Ledger.Agreement<Map<UUID, ActionLedgerView.Row>>> checkActionLedger() {
        return submit(() -> {
            var kept = new java.util.TreeMap<UUID, ActionLedgerView.Row>();
            try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("SELECT action_id, state, revision FROM action_ledger")) {
                while (rows.next()) kept.put(UUID.fromString(rows.getString(1)), new ActionLedgerView.Row(rows.getString(2), rows.getLong(3)));
            }
            var events = new ArrayList<WorldEvent>();
            long after = 0;
            while (true) {
                List<WorldEvent> page = selectWorldEvents(ActionLedgerView.INSTANCE.kinds(), after, 5_000);
                events.addAll(page);
                if (page.size() < 5_000) break;
                after = page.getLast().id();
            }
            return ActionLedgerView.INSTANCE.check(events, Collections.unmodifiableMap(kept));
        });
    }

    /**
     * The action ledger's agreement check as a stream (F-31f): whether it agrees, the rows and events it read, the first
     * differences found (at most {@link #DIFFERENCES}) with how many there were, and the actions it did not check: named,
     * in a row or an event, other than as the store writes an action's id (the first few of them, and how many). Any
     * action not checked means it does not agree: never a pass for what it did not look at.
     */
    public record LedgerCheck(boolean agrees, long rows, long events, List<String> differences, long differing,
                              List<String> notChecked, long unchecked) {
        public LedgerCheck {
            differences = List.copyOf(differences);
            notChecked = List.copyOf(notChecked);
        }
    }

    /** How many differences a check keeps to report; it counts them all. */
    public static final int DIFFERENCES = 20;

    /**
     * The agreement check for the action ledger off the writer (F-31f): the same verdict as {@link #checkActionLedger},
     * every row of {@code action_ledger} against the ledger rebuilt from the Record's action events, but read on its own
     * thread from a connection of its own, opened read-only (the driver's read-only open flag, and {@code query_only}),
     * inside one read transaction: the table and the events are one snapshot of the database, as it stood between two of
     * the writer's transactions, as the writer-held check saw it, every row written with its event in one transaction.
     * The writer is not held: writes go on while it reads. Streamed, a merge of the table in {@code action_id} order with
     * each action's events in {@code (subject, id)} order (V13's index), so that it holds one action at a time, not the
     * ledger. A row or an event that names an action other than as the store writes its id (in upper case, say; the store
     * writes none) is not checked, and counted: the check then does not agree, and says how many, never a load of the
     * whole ledger to read it another way. Stopped by {@link #stopChecks}: then it completes with a
     * {@link java.util.concurrent.CancellationException}, never a verdict.
     */
    public CompletableFuture<LedgerCheck> checkActionLedgerFromSnapshot() {
        var result = new CompletableFuture<LedgerCheck>();
        if (checksStopped || closing || databasePath == null) {
            result.completeExceptionally(new java.util.concurrent.CancellationException("The store's checks are stopped"));
            return result;
        }
        try {
            checker.execute(() -> {
                try {
                    if (checksStopped) throw new java.util.concurrent.CancellationException("The store's checks are stopped");
                    result.complete(streamedCheck());
                } catch (Throwable failure) {
                    result.completeExceptionally(checksStopped ? new java.util.concurrent.CancellationException("Stopped while checking") : failure);
                }
            });
        } catch (RejectedExecutionException stopped) {
            result.completeExceptionally(new java.util.concurrent.CancellationException("The store's checks are stopped"));
        }
        return result;
    }

    /**
     * Stops every agreement check off the writer at once (F-31f): one under way is interrupted where it reads, and none
     * after starts. A stopped check reports nothing. Writes are not touched.
     */
    public void stopChecks() {
        checksStopped = true;
        for (Statement statement : checking) {
            try { statement.cancel(); } catch (SQLException | RuntimeException ignored) { }
        }
        checker.shutdownNow();
    }

    private LedgerCheck streamedCheck() throws SQLException {
        var config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(5000);
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + databasePath, config.toProperties())) {
            try (Statement pragma = reader.createStatement()) { pragma.execute("PRAGMA query_only = ON"); }
            reader.setAutoCommit(false);
            try (var table = reader.prepareStatement("SELECT action_id, state, revision FROM action_ledger ORDER BY action_id");
                 var actions = reader.prepareStatement("SELECT subject, id, data FROM world_events WHERE kind = ? ORDER BY subject, id");
                 var folded = reader.prepareStatement("SELECT subject, id, data FROM world_events WHERE kind = ? ORDER BY subject, id")) {
                checking.add(table);
                checking.add(actions);
                checking.add(folded);
                try {
                    if (checksStopped) throw new java.util.concurrent.CancellationException("Stopped");
                    actions.setString(1, ActionLedgerView.ACTION);
                    folded.setString(1, ActionLedgerView.FOLDED);
                    return merged(table.executeQuery(), actions.executeQuery(), folded.executeQuery());
                } finally {
                    checking.remove(table);
                    checking.remove(actions);
                    checking.remove(folded);
                    reader.rollback();
                }
            }
        }
    }

    /** One event of an action, as read: its subject, id and data. */
    private record Read(String subject, long id, String data) { }

    private static Read next(ResultSet rows) throws SQLException {
        return rows.next() ? new Read(rows.getString(1), rows.getLong(2), rows.getString(3)) : null;
    }

    /** Two texts in SQLite's order for them (BINARY: their UTF-8 bytes), as its {@code ORDER BY} gave them. */
    static int binary(String one, String other) {
        boolean ascii = true;
        for (int i = 0; ascii && i < one.length(); i++) ascii = one.charAt(i) < 0x80;
        for (int i = 0; ascii && i < other.length(); i++) ascii = other.charAt(i) < 0x80;
        if (ascii) return one.compareTo(other);
        return java.util.Arrays.compareUnsigned(one.getBytes(StandardCharsets.UTF_8), other.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether {@code text} is an action id as the store writes one. */
    private static boolean canonical(String text) {
        try {
            return text != null && UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException notAnId) {
            return false;
        }
    }

    /** One action of the Record as the events give it: its id as written, and its row, or none when no event of it reads. */
    private record Group(String subject, ActionLedgerView.Row row) { }

    /** The two event streams, merged in {@code (subject, id)} order and taken an action at a time. */
    private final class Groups {
        private final ResultSet actions, folded;
        private Read a, f;
        private long events;
        /** Actions named other than as the store writes an id: how many, and the first few. */
        private long unchecked;
        private final List<String> notChecked = new ArrayList<>();

        Groups(ResultSet actions, ResultSet folded) throws SQLException {
            this.actions = actions;
            this.folded = folded;
            a = SqliteCompanionStore.next(actions);
            f = SqliteCompanionStore.next(folded);
        }

        /** The next action with a parseable id; null at the end. Subjects that are no id are no action, as ActionLedgerView reads them. */
        Group next() throws SQLException {
            while (a != null || f != null) {
                if (checksStopped) throw new java.util.concurrent.CancellationException("Stopped");
                String subject = a == null ? f.subject() : f == null ? a.subject() : binary(a.subject(), f.subject()) <= 0 ? a.subject() : f.subject();
                ActionLedgerView.Row row = null;
                boolean id;
                try {
                    UUID.fromString(subject);
                    id = true;
                } catch (IllegalArgumentException notAnId) {
                    id = false;
                }
                while (a != null && a.subject().equals(subject) || f != null && f.subject().equals(subject)) {
                    boolean fromA = f == null || !f.subject().equals(subject) || a != null && a.subject().equals(subject) && a.id() < f.id();
                    Read event = fromA ? a : f;
                    if (fromA) a = SqliteCompanionStore.next(actions); else f = SqliteCompanionStore.next(folded);
                    events++;
                    if (!id) continue;
                    var data = EventData.decode(event.data());
                    try {
                        row = new ActionLedgerView.Row(data.getOrDefault("state", ""), Long.parseLong(data.getOrDefault("revision", "")));
                    } catch (NumberFormatException malformed) {
                        // A malformed event changes nothing, as ActionLedgerView reads it.
                    }
                }
                if (!id) continue;
                if (!canonical(subject)) {
                    unchecked++;
                    if (notChecked.size() < DIFFERENCES) notChecked.add(subject);
                    continue;
                }
                return new Group(subject, row);
            }
            return null;
        }
    }

    /**
     * The merge of the table, in {@code action_id} order, with the Record's actions, in subject order. A row or an event
     * naming an action other than as the store writes its id is left out of it, and counted as not checked.
     */
    private LedgerCheck merged(ResultSet table, ResultSet actions, ResultSet folded) throws SQLException {
        long rows = 0, differing = 0;
        var differences = new ArrayList<String>();
        var groups = new Groups(actions, folded);
        Group group = groups.next();
        boolean row = table.next();
        while (group != null || row) {
            if (checksStopped) throw new java.util.concurrent.CancellationException("Stopped");
            String id = row ? table.getString(1) : null;
            if (row && !canonical(id)) {
                groups.unchecked++;
                if (groups.notChecked.size() < DIFFERENCES) groups.notChecked.add(id);
                row = table.next();
                continue;
            }
            int order = !row ? 1 : group == null ? -1 : binary(id, group.subject());
            String difference = null;
            if (order < 0) {
                rows++;
                difference = id + ": kept " + new ActionLedgerView.Row(table.getString(2), table.getLong(3)) + ", the Record gives nothing";
                row = table.next();
            } else if (order > 0) {
                if (group.row() != null) difference = group.subject() + ": kept nothing, the Record gives " + group.row();
                group = groups.next();
            } else {
                rows++;
                var kept = new ActionLedgerView.Row(table.getString(2), table.getLong(3));
                if (!kept.equals(group.row())) difference = id + ": kept " + kept + ", the Record gives " + (group.row() == null ? "nothing" : group.row());
                row = table.next();
                group = groups.next();
            }
            if (difference != null) {
                differing++;
                if (differences.size() < DIFFERENCES) differences.add(difference);
            }
        }
        return new LedgerCheck(differing == 0 && groups.unchecked == 0, rows, groups.events, differences, differing, groups.notChecked, groups.unchecked);
    }

    private Optional<BudgetState> selectBudget(UUID id, Instant start) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT * FROM budget_buckets WHERE companion_id=? AND start_ms=?")) {
            query.setString(1, id.toString());
            query.setLong(2, start.toEpochMilli());
            try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(readBudget(row)) : Optional.empty(); }
        }
    }

    private Optional<BudgetState> latestBudget(UUID id) throws SQLException {
        try (PreparedStatement query = database.prepareStatement(
                "SELECT * FROM budget_buckets WHERE companion_id=? ORDER BY start_ms DESC LIMIT 1")) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) { return row.next() ? Optional.of(readBudget(row)) : Optional.empty(); }
        }
    }

    private static BudgetState readBudget(ResultSet row) throws SQLException {
        AccountingPeriod period = new AccountingPeriod(LocalDate.parse(row.getString("local_date")),
                ZoneId.of(row.getString("timezone")), Instant.ofEpochMilli(row.getLong("start_ms")),
                Instant.ofEpochMilli(row.getLong("end_ms")));
        String reservationId = row.getString("reservation_id");
        Optional<Reservation> reservation = reservationId == null ? Optional.empty() : Optional.of(new Reservation(
                UUID.fromString(reservationId), row.getLong("reserved_ms"), Instant.ofEpochMilli(row.getLong("reserved_at_ms")),
                Instant.ofEpochMilli(row.getLong("expires_at_ms"))));
        return new BudgetState(UUID.fromString(row.getString("companion_id")), period, row.getLong("charged_ms"),
                row.getLong("observed_ms"), row.getLong("sequence"), reservation);
    }

    private static void bindBudget(PreparedStatement statement, BudgetState state) throws SQLException {
        statement.setString(1, state.companionId().toString());
        statement.setLong(2, state.period().startInclusive().toEpochMilli());
        statement.setLong(3, state.period().endExclusive().toEpochMilli());
        statement.setString(4, state.period().localDate().toString());
        statement.setString(5, state.period().zone().getId());
        statement.setLong(6, state.chargedMillis());
        statement.setLong(7, state.observedActiveMillis());
        statement.setLong(8, state.sequence());
        if (state.outstanding().isPresent()) {
            Reservation reservation = state.outstanding().orElseThrow();
            statement.setString(9, reservation.id().toString());
            statement.setLong(10, reservation.reservedMillis());
            statement.setLong(11, reservation.reservedAt().toEpochMilli());
            statement.setLong(12, reservation.expiresAt().toEpochMilli());
        } else {
            statement.setString(9, null);
            statement.setObject(10, null);
            statement.setObject(11, null);
            statement.setObject(12, null);
        }
    }

    private void updateBudget(BudgetState state, long expected) throws SQLException {
        try (PreparedStatement update = database.prepareStatement("UPDATE budget_buckets SET companion_id=?, start_ms=?, "
                + "end_ms=?, local_date=?, timezone=?, charged_ms=?, observed_ms=?, sequence=?, reservation_id=?, reserved_ms=?, "
                + "reserved_at_ms=?, expires_at_ms=? WHERE companion_id=? AND start_ms=? AND sequence=?")) {
            bindBudget(update, state);
            update.setString(13, state.companionId().toString());
            update.setLong(14, state.period().startInclusive().toEpochMilli());
            update.setLong(15, expected);
            if (update.executeUpdate() != 1) throw new ConcurrentModificationException("Budget sequence changed");
        }
    }

    private static void validateTransition(BudgetState old, BudgetState next) {
        long sequenceDelta = next.sequence() - old.sequence();
        long chargedDelta = next.chargedMillis() - old.chargedMillis();
        long observedDelta = next.observedActiveMillis() - old.observedActiveMillis();
        if (sequenceDelta < 1 || sequenceDelta > 2 || chargedDelta < 0 || observedDelta < 0
                || observedDelta > chargedDelta) throw new IllegalArgumentException("Accounting totals must advance monotonically");
        if (old.outstanding().isEmpty()) {
            if (sequenceDelta != 1 || chargedDelta != 0 || observedDelta != 0 || next.outstanding().isEmpty()) {
                throw new IllegalArgumentException("New usage requires a durable reservation first");
            }
        } else {
            Reservation previous = old.outstanding().orElseThrow();
            if (chargedDelta > previous.reservedMillis()) throw new IllegalArgumentException("Charge exceeds reserved slice");
            if (next.outstanding().isEmpty()) {
                if (sequenceDelta != 1) throw new IllegalArgumentException("Settlement advances one sequence");
            } else if (sequenceDelta != 2 || previous.id().equals(next.outstanding().orElseThrow().id())) {
                throw new IllegalArgumentException("Renewal requires a fresh reservation and two sequence advances");
            }
        }
    }

    private static void validatePrecision(BudgetState state) {
        requireMilliseconds(state.period().startInclusive());
        requireMilliseconds(state.period().endExclusive());
        state.outstanding().ifPresent(reservation -> {
            requireMilliseconds(reservation.reservedAt());
            requireMilliseconds(reservation.expiresAt());
        });
    }

    private static void requireMilliseconds(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        if (instant.getNano() % 1_000_000 != 0) throw new IllegalArgumentException("Persistence requires millisecond precision");
    }

    private static void requireRevision(long actual, long expected) {
        if (actual != expected) throw new ConcurrentModificationException("Expected revision " + expected + ", found " + actual);
    }

    private <T> T transaction(SqlWork<T> work) throws Exception {
        database.setAutoCommit(false);
        try {
            T result = work.run();
            database.commit();
            commitCounts(pending);
            return result;
        } catch (Exception failure) {
            try { database.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            pending.clear();
            synchronized (written) {
                pendingThrough = writtenThrough;
            }
            throw failure;
        } finally {
            database.setAutoCommit(true);
        }
    }

    private synchronized <T> CompletableFuture<T> submit(SqlWork<T> work) {
        if (closing) return CompletableFuture.failedFuture(new IllegalStateException("Metadata store is closing"));
        if (!queueSlots.tryAcquire()) return CompletableFuture.failedFuture(new RejectedExecutionException("Metadata queue is full"));
        CompletableFuture<T> future = new CompletableFuture<>();
        IN_FLIGHT.incrementAndGet();
        try {
            writer.execute(() -> {
                try { future.complete(work.run()); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
                finally {
                    queueSlots.release();
                    IN_FLIGHT.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException failure) {
            queueSlots.release();
            IN_FLIGHT.decrementAndGet();
            future.completeExceptionally(failure);
        }
        return future;
    }

    /**
     * How much work every open store in this process has queued or running: reads and writes alike. Zero when all of it
     * is done. For diagnostics, and so the GameTest server can give a durable write the tick a live server's would have
     * (F-22c5).
     */
    public static int workInFlight() { return IN_FLIGHT.get(); }

    private void releaseResources() throws Exception {
        Exception failure = null;
        try { if (database != null) database.close(); } catch (Exception error) { failure = error; }
        try { if (fileLock != null) fileLock.release(); } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }

    @FunctionalInterface
    private interface SqlWork<T> { T run() throws Exception; }
}
