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
 * Ordered, bounded asynchronous metadata writer. Never join its futures on the game thread.
 * Completion callbacks may run on the writer; dispatch game work to the server thread and
 * compose dependent store futures instead of joining them inside an inline callback.
 * Metadata is not atomic with native world saves; it cannot prove a physical action succeeded.
 */
public final class SqliteCompanionStore {
    private static final int QUEUE_CAPACITY = 256;
    private final ThreadPoolExecutor writer;
    private final Semaphore queueSlots = new Semaphore(QUEUE_CAPACITY);
    private final int maxCompanions;
    private Connection database;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private boolean closing;
    private CompletableFuture<Void> closeFuture;

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
                    combativeness=?, helpfulness=?, farming=?, mining=?
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
                update.setString(14, profile.companionId().toString());
                update.setLong(15, expectedRevision);
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
                            rows.getString("timezone"), rows.getLong("revision")));
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
            return record;
        }));
    }

    /** Applies one durable state transition; the stored row's state and revision must match. */
    public CompletableFuture<ActionRecord> transitionAction(ActionRecord next, long expectedRevision) {
        Objects.requireNonNull(next);
        return submit(() -> transaction(() -> {
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
            if (next.state() == ActionRecord.State.UNCERTAIN) {
                insertEvent(next.companionId(), Instant.now(), "ACTION_UNCERTAIN", next.kind() + " " + next.actionId() + ": " + next.detail());
            }
            return next;
        }));
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
                    "UPDATE activity_limits SET cap_ms=?, offline_work=?, timezone=?, revision=? WHERE companion_id=? AND revision=?")) {
                update.setLong(1, limit.capMillis());
                update.setBoolean(2, limit.offlineWork());
                update.setString(3, limit.timezone());
                update.setLong(4, limit.revision());
                update.setString(5, limit.companionId().toString());
                update.setLong(6, expectedRevision);
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
                return new ActivityLimit(companionId, row.getLong("cap_ms"), row.getBoolean("offline_work"), row.getString("timezone"), row.getLong("revision"));
            }
        }
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
                row.getBoolean("ambient_enabled"), row.getBoolean("public_conversation"), row.getLong("revision"));
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
        try (Statement statement = database.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
            int schemaVersion;
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                schemaVersion = version.next() ? version.getInt(1) : -1;
            }
            if (schemaVersion < 0 || schemaVersion > 8) {
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
        }
        recover();
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
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("SELECT last_insert_rowid()")) {
            if (!row.next()) throw new SQLException("Missing journal sequence");
            return new JournalEvent(row.getLong(1), id, Instant.ofEpochMilli(at.toEpochMilli()), type, detail);
        }
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
            return result;
        } catch (Exception failure) {
            try { database.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            database.setAutoCommit(true);
        }
    }

    private synchronized <T> CompletableFuture<T> submit(SqlWork<T> work) {
        if (closing) return CompletableFuture.failedFuture(new IllegalStateException("Metadata store is closing"));
        if (!queueSlots.tryAcquire()) return CompletableFuture.failedFuture(new RejectedExecutionException("Metadata queue is full"));
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                try { future.complete(work.run()); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
                finally { queueSlots.release(); }
            });
        } catch (RejectedExecutionException failure) {
            queueSlots.release();
            future.completeExceptionally(failure);
        }
        return future;
    }

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
