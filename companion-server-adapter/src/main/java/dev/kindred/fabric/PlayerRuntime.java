package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.AdmissionBoard;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.JournalSummary;
import dev.kindred.core.OperatingMode;
import dev.kindred.core.PeerProgressDialogue;
import dev.kindred.core.GoalRecord;
import dev.kindred.core.MemoryNote;
import dev.kindred.core.ActivityLimit;
import dev.kindred.core.ProposalGate;
import dev.kindred.core.ActionArbiter;
import dev.kindred.core.ActionLease;
import dev.kindred.core.Actor;
import dev.kindred.core.AttentionPolicy;
import dev.kindred.core.Authorization;
import dev.kindred.core.BlockObservation;
import dev.kindred.core.BlockObservations;
import dev.kindred.core.BlockPerception;
import dev.kindred.core.BodyAdapter;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.Companion;
import dev.kindred.core.LifecycleState;
import dev.kindred.core.SkillRunner;
import dev.kindred.core.SprintStride;
import dev.kindred.core.MannerismState;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.Personality;
import dev.kindred.core.QuirkFeedback;
import dev.kindred.core.SectionKey;
import dev.kindred.core.SectionPlanner;
import dev.kindred.core.SocialDialogue;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.WorkSetup;
import dev.kindred.core.RemovalToken;
import dev.kindred.core.attention.AttentionSchedulePolicy;
import dev.kindred.core.attention.AttentionSnapshot;
import dev.kindred.core.attention.AttentionState;
import dev.kindred.core.attention.AttentionStateMachine;
import dev.kindred.core.attention.AttentionWorkScheduler;
import dev.kindred.core.attention.SchedulerDiagnostics;
import dev.kindred.core.attention.SignalFrame;
import dev.kindred.core.attention.ThreatKnowledge;
import dev.kindred.core.attention.WorkKind;
import dev.kindred.core.attention.WorkToken;
import dev.kindred.persistence.ActionRef;
import dev.kindred.persistence.CreationRequest;
import dev.kindred.persistence.LedgerScan;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.persistence.StartupLedgerSnapshot;
import dev.kindred.persistence.StoredCompanion;
import dev.kindred.persistence.StoredNativePlayer;
import dev.kindred.persistence.PlayerCreationRequest;
import dev.kindred.core.CanonicalRoster;
import dev.kindred.core.InventorySummary;
import dev.kindred.core.idle.IdleObservationController;
import dev.kindred.core.progression.PlaystyleProfile;
import dev.kindred.core.logistics.ChestOrganizationPolicy;
import dev.kindred.core.team.CollaborationMode;
import dev.kindred.core.team.HandOvers;
import dev.kindred.core.team.ShareOut;
import dev.kindred.core.progression.MilestoneGraph;
import dev.kindred.core.progression.MilestoneId;
import dev.kindred.core.progression.MilestoneSnapshot;
import dev.kindred.core.progression.MilestoneStatus;
import dev.kindred.core.progression.ProgressionObservation;
import dev.kindred.core.progression.ProgressionPolicy;
import dev.kindred.core.progression.ProgressionRecommendation;
import dev.kindred.core.progression.ProgressionState;
import dev.kindred.core.team.ProjectId;
import dev.kindred.core.team.SharedProject;
import dev.kindred.core.team.SharedTask;
import dev.kindred.core.team.StockLedger;
import dev.kindred.core.team.StockReservation;
import dev.kindred.core.team.TaskId;
import dev.kindred.core.team.TeamCoordinator;
import dev.kindred.core.expedition.ExpeditionObservation;
import dev.kindred.core.expedition.ExpeditionPlan;
import dev.kindred.core.expedition.ExpeditionPlanner;
import dev.kindred.core.expedition.ExpeditionState;
import dev.kindred.core.expedition.ExpeditionStatus;
import dev.kindred.core.expedition.ReturnReason;
import dev.kindred.core.expedition.Waypoint;
import dev.kindred.core.expedition.WaypointKind;
import dev.kindred.scheduler.PaidActivitySession;
import dev.kindred.scheduler.AccountingPeriod;
import dev.kindred.scheduler.BudgetState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI-player lifecycle host. The saved mob body is a compatibility backend, not qualified player mechanics. */
final class PlayerRuntime {
    enum LedgerCheckState {
        PENDING,
        KNOWN_LEDGER_CLEAR,
        BLOCKED
    }

    record ConnectionToken(long startupGeneration, UUID logicalId, long entryGeneration, UUID requestId, long expectedNativeRevision) { }

    @FunctionalInterface
    interface StartupLedgerReader {
        CompletableFuture<StartupLedgerSnapshot> read(SqliteCompanionStore store, Set<UUID> expectedLogicalPlayerIds);
    }

    private final MinecraftServer server;
    private final Path metadataDirectory;
    /** The optional model strategist for goals (F-19b), when switched on and a key is set. */
    private Optional<StrategistBridge> strategist;
    /** What each player has learnt about this world, kept across restarts. */
    private final PlayerMemoryStore memories;
    private final NativePlayerSaves.Reader nativeSaveReader;
    private final java.util.function.UnaryOperator<dev.kindred.core.ActionAdmission.Writer> actionWriterDecorator;
    private final StartupLedgerReader startupLedgerReader;
    private long startupGeneration = 0;
    private int nativeCreationsPending;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Set<CompanionEntity> awaitingRegistry = new HashSet<>();
    private final Set<UUID> creating = new HashSet<>();
    private SqliteCompanionStore store;
    private UUID worldId;
    private volatile boolean running = true;
    private boolean ready;
    private CompletableFuture<LoadedRegistry> startup;
    private CompletableFuture<Void> shutdown = CompletableFuture.completedFuture(null);
    private final CompanionSocials socials;
    private EditPolicy.ServerPolicy edits = EditPolicy.ServerPolicy.deferredD4();
    /**
     * Whether the server allows an AI player to fight the Ender Dragon (E-39a): off until an operator turns it on, as
     * the dragon fight is an encounter a server may want to keep for its people.
     */
    private boolean dragonFight;
    /** The operator's leave for AI players to choose, site and build their own bases (M-01c); off by default. */
    private boolean bases;
    private final AdmissionBoard admission = AdmissionBoard.serverTarget(10);
    private final ClaimRegistry claims = new ClaimRegistry();
    ClaimRegistry claims() { return claims; }
    private final ProposalGate proposals = new ProposalGate(java.util.Set.of("move_to", "return_home", "wait", "gather_logs", "deposit", "follow"));
    /** One bounded accommodation per speaker per companion per window; keyed "speaker:companion". */
    private final Map<String, Long> accommodationWindows = new LinkedHashMap<>();
    /** One-use removal tokens keyed by token UUID (F-14). */
    private final Map<UUID, RemovalToken> removalTokens = new HashMap<>();

    private final UUID runtimeSession = UUID.randomUUID();
    private final AttentionWorkScheduler attentionScheduler;
    static final int MAX_BLOCK_CHECKS_PER_TICK = 128;
    /** How long a direct native command keeps autonomous task selection from replacing its outcome. */
    static final int MANUAL_COMMAND_HOLD_TICKS = 200;
    /** A running native skill's paid slice is renewed when this little of it is left (F-11c). */
    static final java.time.Duration RENEW_AHEAD = java.time.Duration.ofMillis(1500);
    private int blocksScannedThisTick = 0;
    private long totalBlockChecksMonotonic = 0;

    AttentionWorkScheduler attentionScheduler() { return attentionScheduler; }
    long totalBlockChecks() { return totalBlockChecksMonotonic; }
    int blocksScannedThisTick() { return blocksScannedThisTick; }

    private final TeamCoordinator teamCoordinator = new TeamCoordinator();
    /** The shared projects the store holds, whose every change is saved; a project only in memory (a fixture's) is not. */
    private final Set<ProjectId> storedProjects = new HashSet<>();
    /** Matches, and who may attack whom in them (M-00a, D7). */
    private final dev.kindred.core.match.MatchRegistry matches = new dev.kindred.core.match.MatchRegistry();
    /** Titles, sounds and a boss bar for the players in a match (M-00b). */
    private final MatchPresenter presenter;
    /** Each race's splits, by match (M-03a). */
    private final Map<UUID, dev.kindred.core.match.SpeedrunSplits> races = new HashMap<>();
    /** The pace each AI runner had before its race, given back when it is out of it. */
    private final Map<UUID, PlaystyleProfile> racePace = new HashMap<>();
    /** The goal a race or a hunt set each AI player in it, ended when it is out of it. */
    private final Map<UUID, dev.kindred.core.survival.SurvivalGoal> raceGoal = new HashMap<>();
    /** Each build battle's round: its theme, plots and votes, by match (M-04a). */
    private final Map<UUID, dev.kindred.core.match.BuildBattle> battles = new HashMap<>();
    /** The game mode each builder had before its build battle's creative mode, given back when it is out of it. */
    private final Map<UUID, net.minecraft.world.GameMode> modeBefore = new HashMap<>();
    /** Build battles whose building has begun, by match. */
    private final Set<UUID> buildsBegun = new HashSet<>();
    /** Manhunts begun, by match (M-02a). */
    private final Set<UUID> hunts = new HashSet<>();
    /** Where each runner was last seen in each world: where a hunter's compass points once the runner has left it. */
    private final Map<UUID, Map<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, BlockPos>> trails = new HashMap<>();
    public TeamCoordinator teamCoordinator() { return teamCoordinator; }

    public ExpeditionState getExpeditionState(UUID id) {
        Entry entry = entries.get(id);
        return entry != null ? entry.expeditionState : null;
    }

    public void setExpeditionState(UUID id, ExpeditionState state) {
        Entry entry = entries.get(id);
        if (entry != null) {
            entry.expeditionState = state;
        }
    }

    /** GameTest / portal handoff: apply dimension and expedition waypoint updates immediately after a qualified teleport. */
    void syncNativeDimensionAfterTransit(UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        if (entry == null || entry.nativeBody == null || !entry.nativeBody.connected()) {
            return;
        }
        applyDimensionTransit(entry, entry.nativeBody);
    }

    private void applyDimensionTransit(Entry entry, NativePlayerBody body) {
        if (body.entity() == null) {
            return;
        }
        String currentDim = body.entity().getServerWorld().getRegistryKey().getValue().toString();
        if (currentDim.equals(entry.data.dimension())) {
            return;
        }
        entry.data = entry.data.withDimension(currentDim);
        // The arbiter holds the record that persist() writes: it must cross too, or every write sets the old dimension
        // back and the crossing is seen again, with another save each time (three saves for one crossing here, and in
        // the staged End run receipts that collided and blocked the player in the Nether).
        if (entry.arbiter != null) entry.arbiter.relocate(currentDim);
        if (entry.setup != null && !currentDim.equals(entry.setup.dimension())) {
            entry.setup = new dev.kindred.core.WorkSetup(entry.setup.companionId(), currentDim, entry.setup.home(),
                    entry.setup.area(), entry.setup.storage(), entry.setup.patch(), entry.setup.revision());
        }
        if (entry.expeditionState != null) {
            BlockPos pPos = body.entity().getBlockPos();
            Waypoint portalWp = Waypoint.of("nether_portal_arrival", pPos.getX(), pPos.getY(), pPos.getZ(),
                    currentDim, WaypointKind.NETHER_PORTAL, server.getTicks());
            entry.expeditionState.addDiscoveredWaypoint(portalWp);
        }
        // Dimension changes are physical native state: persist the metadata and a matching
        // vanilla player save receipt so reconnect cannot restore the body in the old world.
        persist(entry);
        persistNativeSaveReceipt(entry);
    }

    private void reconcileExpeditionArrival(Entry entry) {
        if (entry.expeditionState == null || entry.nativeBody == null || !entry.nativeBody.connected()) {
            return;
        }
        if (entry.expeditionState.status() != ExpeditionStatus.RETURNING) {
            return;
        }
        var player = entry.nativeBody.entity();
        if (player == null) {
            return;
        }
        Waypoint anchor = entry.expeditionState.homeAnchor();
        double dx = player.getX() - (anchor.x() + 0.5);
        double dz = player.getZ() - (anchor.z() + 0.5);
        if (Math.hypot(dx, dz) <= 2.0) {
            entry.expeditionState.arriveAtBase();
        }
    }

    /** Keeps SQLite save receipts aligned with {@link NativePlayerBody#saveNow()} for same-tick reconnect. */
    private void persistNativeSaveReceipt(Entry entry) {
        if (entry.nativeBody == null || !entry.nativeBody.connected() || entry.nativeState == null
                || entry.nativeState.phase() != StoredNativePlayer.Phase.ESTABLISHED) {
            return;
        }
        UUID receipt;
        try {
            receipt = entry.nativeBody.saveNow();
        } catch (RuntimeException failure) {
            nativeFailure(server.getCommandSource(), entry, "Native save could not be written", failure);
            return;
        }
        long expectedRevision = entry.nativeState.revision();
        entry.nativeState = new StoredNativePlayer(entry.nativeState.metadata(), entry.nativeState.controllerId(),
                entry.nativeState.phase(), receipt, entry.nativeState.blocker(), expectedRevision + 1);
        store.establishNativePlayer(entry.data.id(), receipt, expectedRevision).whenComplete((established, failure) -> server.execute(() -> {
            if (!running || entries.get(entry.data.id()) != entry) return;
            if (failure != null) {
                nativeFailure(server.getCommandSource(), entry, "Native save receipt could not be recorded", failure);
                return;
            }
            // Receipts are written in order, and each later one already counted on this one's revision: a reply that
            // arrives after a later receipt was issued must not wind the revision back (crossing a portal right after
            // other recorded work blocked the staged End run's player this way).
            if (entry.nativeState == null || established.revision() >= entry.nativeState.revision()) entry.nativeState = established;
        }));
    }

    PlayerRuntime(MinecraftServer server) {
        this(server, server.getSavePath(WorldSavePath.ROOT).resolve("kindred"));
    }

    PlayerRuntime(MinecraftServer server, Path metadataDirectory) {
        this(server, metadataDirectory, NativePlayerSaves::read);
    }

    PlayerRuntime(MinecraftServer server, Path metadataDirectory, NativePlayerSaves.Reader nativeSaveReader) {
        this(server, metadataDirectory, nativeSaveReader, writer -> writer);
    }

    PlayerRuntime(MinecraftServer server, Path metadataDirectory, NativePlayerSaves.Reader nativeSaveReader,
                  java.util.function.UnaryOperator<dev.kindred.core.ActionAdmission.Writer> actionWriterDecorator) {
        this(server, metadataDirectory, nativeSaveReader, actionWriterDecorator, SqliteCompanionStore::readStartupLedger);
    }

    PlayerRuntime(MinecraftServer server, Path metadataDirectory, NativePlayerSaves.Reader nativeSaveReader,
                  java.util.function.UnaryOperator<dev.kindred.core.ActionAdmission.Writer> actionWriterDecorator,
                  StartupLedgerReader startupLedgerReader) {
        this.server = server;
        this.presenter = new MatchPresenter(server);
        this.metadataDirectory = metadataDirectory;
        this.strategist = StrategistBridge.fromEnvironment(metadataDirectory);
        this.memories = new PlayerMemoryStore(metadataDirectory);
        this.nativeSaveReader = nativeSaveReader;
        this.actionWriterDecorator = java.util.Objects.requireNonNull(actionWriterDecorator);
        this.startupLedgerReader = java.util.Objects.requireNonNull(startupLedgerReader);
        socials = new CompanionSocials(this::socialParticipant, PlayerRuntime::now,
                (player, message) -> player.sendMessage(message, false), new HabitHooks(), this::aiPlayerOf);
        socials.setConferenceHandler(new CompanionSocials.PeerConferenceHandler() {
            @Override
            public void onConference(CompanionSocials.Participant speaker, CompanionSocials.Participant peer, PeerProgressDialogue.Exchange exchange) {
                handlePeerConference(speaker, peer, exchange);
            }

            @Override
            public PeerProgressDialogue.PeerState state(UUID id) {
                return peerState(id);
            }
        });
        this.attentionScheduler = new AttentionWorkScheduler(this.runtimeSession, AttentionSchedulePolicy.defaultPolicy());
        teamCoordinator.onChange(this::saveTeamChange);
    }

    private static long now() { return System.nanoTime() / 1_000_000; }
    private static long nanos() { return System.nanoTime(); }

    /**
     * One of the operator's leaves in edits.properties: {@code dragonFight}, {@code bases}; off when absent or
     * unreadable.
     */
    private static boolean readLeave(Path directory, String key) {
        try {
            java.nio.file.Path file = directory.resolve("edits.properties");
            if (!java.nio.file.Files.exists(file)) return false;
            java.util.Properties properties = new java.util.Properties();
            try (var in = java.nio.file.Files.newInputStream(file)) { properties.load(in); }
            return Boolean.parseBoolean(properties.getProperty(key, "false"));
        } catch (Exception failure) {
            KindredMod.LOGGER.error("Kindred setting " + key + " could not be read; it stays off", failure);
            return false;
        }
    }

    private static EditPolicy.ServerPolicy readEdits(Path directory) {
        try {
            java.nio.file.Path file = directory.resolve("edits.properties");
            if (!java.nio.file.Files.exists(file)) {
                // Decision D4: autonomous world edits stay off until an operator records the server policy.
                return EditPolicy.ServerPolicy.deferredD4();
            }
            java.util.Properties properties = new java.util.Properties();
            try (var in = java.nio.file.Files.newInputStream(file)) { properties.load(in); }
            return new EditPolicy.ServerPolicy(Boolean.parseBoolean(properties.getProperty("autonomousEdits", "false")),
                    Boolean.parseBoolean(properties.getProperty("protectionKnown", "false")));
        } catch (Exception failure) {
            KindredMod.LOGGER.error("Kindred edit policy could not be read; autonomous edits stay disabled", failure);
            return EditPolicy.ServerPolicy.deferredD4();
        }
    }

    boolean isReady() {
        requireThread();
        return ready && running;
    }

    void start() {
        Path directory = metadataDirectory;
        long currentStartupGen = ++startupGeneration;
        // Only paths and immutable values cross into filesystem/database workers.
        startup = CompletableFuture.supplyAsync(() -> new Bootstrap(loadWorldId(directory), readEdits(directory), readLeave(directory, "dragonFight"), readLeave(directory, "bases")))
                .thenCompose(inputs -> SqliteCompanionStore.open(directory.resolve("companions.sqlite"), 128)
                        .thenCompose(database -> database.list().thenCompose(records -> {
                            Set<UUID> logicalIds = records.stream().map(r -> r.companion().id()).collect(java.util.stream.Collectors.toSet());
                            return database.profiles().thenCompose(profiles ->
                                    database.mannerisms().thenCompose(mannerisms ->
                                            database.workSetups().thenCompose(setups ->
                                                    database.nativePlayers().thenCompose(nativePlayers ->
                                                            startupLedgerReader.read(database, logicalIds).thenCompose(ledgerSnapshot ->
                                                                    database.modes(logicalIds).thenApply(modes ->
                                                                            new LoadedRegistry(inputs.worldId(), inputs.edits(), inputs.dragonFight(), inputs.bases(), database, records,
                                                                                    profiles, mannerisms, setups, nativePlayers, ledgerSnapshot, modes)
                                                                    )
                                                            )
                                                    )
                                            )
                                    )
                            );
                        }).handle((registry, failure) -> {
                            if (failure == null) return CompletableFuture.completedFuture(registry);
                            return database.closeAsync().<LoadedRegistry>handle((ignored, closeFailure) -> {
                                if (closeFailure != null) failure.addSuppressed(closeFailure);
                                throw new CompletionException(failure);
                            });
                        }).thenCompose(future -> future)));
        startup.whenComplete((loaded, failure) -> {
            if (!running) {
                if (loaded != null) loaded.store().closeAsync();
                return; // Shutdown chains resource cleanup directly to startup, independent of ticks.
            }
            server.execute(() -> {
                if (!running || startupGeneration != currentStartupGen) {
                    if (loaded != null) loaded.store().closeAsync();
                    return;
                }
                if (failure != null) {
                    KindredMod.LOGGER.error("Kindred metadata could not open; all bodies remain dormant", failure);
                    return;
                }
                try {
                    if (loaded.ledgerSnapshot() == null || loaded.ledgerSnapshot().byLogicalPlayer() == null) {
                        throw new IllegalStateException("Startup ledger snapshot is missing or null");
                    }
                    Map<UUID, Entry> pendingEntries = new HashMap<>();
                    for (StoredCompanion record : loaded.records()) {
                        UUID compId = record.companion().id();
                        LedgerScan scan = loaded.ledgerSnapshot().byLogicalPlayer().get(compId);
                        if (scan == null) {
                            throw new IllegalStateException("Missing ledger scan for companion " + compId);
                        }
                        Entry entry = new Entry(record);
                        entry.nativeState = loaded.nativePlayers().stream().filter(nativePlayer ->
                                nativePlayer.metadata().companion().id().equals(compId)).findFirst().orElse(null);

                        if (scan.unresolvedCount() > 0) {
                            entry.ledgerState = LedgerCheckState.BLOCKED;
                            entry.reconciliationBlocked = true;
                            entry.blocker = "Unresolved action ledger rows detected (" + scan.unresolvedCount() + "); operator reconciliation required.";
                        } else {
                            entry.ledgerState = LedgerCheckState.KNOWN_LEDGER_CLEAR;
                        }

                        if (entry.nativeState != null) {
                            if (entry.nativeState.phase() != StoredNativePlayer.Phase.ESTABLISHED) {
                                entry.reconciliationBlocked = true;
                                entry.blocker = "Incomplete native body; reconcile before connecting.";
                            } else if (entry.ledgerState == LedgerCheckState.BLOCKED) {
                                // Blocked by unresolved action rows
                            } else {
                                entry.blocker = "Native body is offline; explicit connect is required.";
                            }
                        }
                        entry.profile = loaded.profiles().stream().filter(p -> p.companionId().equals(compId))
                                .findFirst().orElseThrow(() -> new IllegalStateException("Missing saved social profile for " + compId));
                        entry.mannerisms = new MannerismState(loaded.mannerisms().stream()
                                .filter(m -> m.companionId().equals(compId)).findFirst()
                                .orElseThrow(() -> new IllegalStateException("Missing saved mannerisms for " + compId)));
                        entry.setup = loaded.setups().stream().filter(s -> s.companionId().equals(compId)).findFirst()
                                .orElseThrow(() -> new IllegalStateException("Missing saved work setup for " + compId));
                        OperatingMode mode = loaded.modes() != null ? loaded.modes().get(compId) : null;
                        if (mode != null) entry.mode = mode;
                        pendingEntries.put(compId, entry);
                    }

                    worldId = loaded.worldId();
                    claims.invalidateOtherEpochs(worldId);
                    store = loaded.store();
                    entries.putAll(pendingEntries);
                    ready = true;
                    edits = loaded.edits();
                    dragonFight = loaded.dragonFight();
                    bases = loaded.bases();
                    for (CompanionEntity entity : new ArrayList<>(awaitingRegistry)) loaded(entity);
                    awaitingRegistry.clear();
                    store.listProjects().whenComplete((loadedProjects, err) -> {
                        if (err == null && loadedProjects != null) {
                            server.execute(() -> {
                                for (SharedProject proj : loadedProjects) {
                                    storedProjects.add(proj.id());
                                    teamCoordinator.registerProject(proj);
                                }
                            });
                        }
                    });
                    KindredMod.LOGGER.info("Kindred AI-player foundation ready: {} durable records; autonomous progression pending qualification", entries.size());
                } catch (Exception validationError) {
                    KindredMod.LOGGER.error("Kindred startup validation failed; closing store and remaining offline", validationError);
                    loaded.store().closeAsync();
                }
            });
        });
    }

    record NativeStatus(UUID id, UUID nativeId, String name, StoredNativePlayer.Phase phase,
                        boolean connected, boolean stopped, String explanation) { }

    List<NativeStatus> nativeStatuses(Actor actor) {
        requireThread();
        if (actor == null) return List.of();
        return entries.values().stream().filter(entry -> entry.nativeState != null && entry.nativeState.access().mayControl(actor))
                .map(entry -> new NativeStatus(entry.data.id(), entry.data.entityId(), entry.data.displayName(),
                        entry.nativeState.phase(), entry.nativeBody != null && entry.nativeBody.connected(),
                        entry.snapshot().stopLatched(), entry.explanation())).toList();
    }

    List<NativeTaskCandidate> nativeCandidates(Actor actor, UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        if (entry == null || entry.nativeBody == null || entry.nativeState == null || !entry.nativeState.access().mayControl(actor)) {
            return List.of();
        }
        return evaluateNativeCandidates(entry, entry.nativeBody);
    }

    java.util.Optional<NativePlayerBody> nativeBody(UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        return entry != null ? java.util.Optional.ofNullable(entry.nativeBody) : java.util.Optional.empty();
    }

    boolean shutdownComplete() { requireThread(); return !running && shutdown.isDone() && !shutdown.isCompletedExceptionally(); }

    int createNative(ServerCommandSource source, String name) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading saved records.");
        Actor actor = nativeActor(source);
        if (actor == null || !actor.operator()) return reject(source, "Only an operator may create a development native player.");
        long count = entries.values().stream().filter(entry -> entry.nativeState != null
                && entry.snapshot().lifecycle() != LifecycleState.REMOVED).count();
        if (count + nativeCreationsPending >= 3) return reject(source, "The development envelope supports three native players; incomplete records still occupy a slot.");
        if (nativeSpawn(source) == null) return reject(source, "Make room on solid ground in an already loaded area.");
        PlayerCreationRequest request;
        try {
            request = new PlayerCreationRequest(UUID.randomUUID(), actor.playerId() == null ? new UUID(0, 0) : actor.playerId(),
                    name, worldId, source.getWorld().getRegistryKey().getValue().toString());
        } catch (IllegalArgumentException invalid) { return reject(source, invalid.getMessage()); }
        ++nativeCreationsPending;
        feedback(source, "Allocating a native AI identity; autonomous work remains disabled.");
        store.createNativePlayer(request).whenComplete((record, failure) -> server.execute(() -> {
            --nativeCreationsPending;
            if (!running || !ready) return;
            if (failure != null) { reject(source, "Native identity could not be saved; no body was created."); return; }
            Entry entry = new Entry(record.metadata());
            entry.nativeState = record;
            entry.ledgerState = LedgerCheckState.KNOWN_LEDGER_CLEAR;
            entries.put(entry.data.id(), entry);
            feedback(source, "Allocated " + name + " (" + entry.data.id() + "). Preparing its native body.");
            if (nativeActor(source) == null || !nativeActor(source).operator()) {
                entry.reconciliationBlocked = true;
                entry.blocker = "Creation lost administrative authority before spawn; reconcile allocation.";
                return;
            }
            connectNative(source, entry, true);
        }));
        return 1;
    }

    int listNative(ServerCommandSource source) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading saved records.");
        var statuses = nativeStatuses(nativeActor(source));
        if (statuses.isEmpty()) feedback(source, "No native AI players are available to your controls.");
        for (var status : statuses) feedback(source, status.name() + " (" + status.id() + "): " + status.phase()
                + ", " + (status.connected() ? "connected" : "offline") + (status.stopped() ? "; stopped" : "") + ". " + status.explanation());
        return 1;
    }

    int nativeLifecycle(ServerCommandSource source, String verb, UUID id) {
        requireThread();
        if (id == null) {
            id = defaultBotId();
        }
        if (id == null) return reject(source, "Specify an AI player name or UUID. Available: " + availableBotNames());
        Entry entry = entries.get(id);
        if (entry != null && entry.nativeState == null) {
            return reject(source, entry.data.displayName() + " is a legacy companion entity. Use '/aiplayer stop "
                    + entry.data.displayName() + "' or '/aiplayer remove " + entry.data.displayName() + "' instead.");
        }
        if (entry == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (verb.equals("status")) {
            var activity = nativeActivityStatus(nativeActor(source), id);
            feedback(source, entry.data.displayName() + " (" + id + "): " + entry.nativeState.phase() + "; "
                    + (entry.nativeBody != null && entry.nativeBody.connected() ? "connected" : "offline") + ". " + entry.explanation()
                    + (activity.chargedMillis() < 0 ? " Activity totals load with the next request; saved usage is retained."
                    : " Activity " + activity.phase() + "; charged " + activity.chargedMillis() + " ms, observed " + activity.observedMillis() + " ms.")
                    + (entry.setup.patch() == null ? " Work patch: none." : " Work patch: " + entry.setup.patch().resourceId() + " at "
                    + point(entry.setup.patch().min()) + " to " + point(entry.setup.patch().max()) + (entry.nativeSetupPending ? " (saving)." : ".")));
            return 1;
        }
        if (entry.snapshot().lifecycle() == LifecycleState.REMOVED) return reject(source, "This native player was removed.");
        // The source grant is checked above; the compatibility arbiter receives trusted administration.
        if (verb.equals("stop") || verb.equals("stay")) {
            halt(entry, Actor.console(), "Stopped by an authorized controller.");
        } else if (verb.equals("disconnect")) {
            detachNative(entry, "Native body disconnected; its physical state remains in native saves.");
        } else if (verb.equals("connect")) {
            if (entry.nativePending) return reject(source, "Native save/load is still pending.");
            if (entry.nativeState.phase() != StoredNativePlayer.Phase.ESTABLISHED || entry.reconciliationBlocked || entry.metadataFault
                    || entry.ledgerState != LedgerCheckState.KNOWN_LEDGER_CLEAR) {
                return reject(source, entry.explanation() + " Reconcile incomplete native state first.");
            }
            if (entry.nativeBody == null || !entry.nativeBody.connected()) connectNative(source, entry, false);
        } else if (verb.equals("pause")) {
            if (entry.runner == null || !entry.runner.pause()) return reject(source, "No running skill to pause.");
            entry.blocker = "Pausing native skill at the next checkpoint.";
            persist(entry);
        } else if (verb.equals("continue")) {
            Actor actor = nativeActor(source);
            return continueSkillEntry(source, entry, actor != null ? actor : Actor.console());
        } else if (verb.equals("resume")) {
            if (entry.nativeBody == null || !entry.nativeBody.connected()) {
                if (!entry.reconciliationBlocked && !entry.metadataFault && entry.ledgerState == LedgerCheckState.KNOWN_LEDGER_CLEAR) {
                    feedback(source, "Connecting " + entry.data.displayName() + " before resuming...");
                    connectNative(source, entry, false);
                    return 1;
                }
                return reject(source, "Connect a reconciled native body before resuming.");
            }
            if (entry.nativePending || entry.nativeSetupPending || entry.setupWrite.isCompletedExceptionally()
                    || entry.reconciliationBlocked || entry.metadataFault || entry.ledgerState != LedgerCheckState.KNOWN_LEDGER_CLEAR || entry.arbiter == null) {
                return reject(source, "Connect a reconciled native body before resuming.");
            }
            if (entry.runtimeSettings.playstyleProfile().isIdleObserving()) {
                entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(PlaystyleProfile.OPTIMAL_SURVIVAL);
            }
            entry.cachedRoutineCandidates = List.of();
            attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
            attentionScheduler.invalidate(entry.data.id(), WorkKind.EQUIPMENT_READINESS);
            entry.routineRefreshPending = true;
            if (entry.nativeBody != null && entry.nativeBody.connected()) {
                bindBodyBlockScanBudget(entry.nativeBody);
                entry.cachedRoutineCandidates = evaluateScheduledRoutineCandidates(entry, entry.nativeBody);
                entry.routineRefreshPending = false;
            }
            entry.arbiter.resume(Actor.console());
            entry.data = entry.arbiter.snapshot();
            entry.blocker = "Resumed; autonomous progression active (" + entry.runtimeSettings.playstyleProfile() + ").";
            persist(entry);
        } else return reject(source, "Native bodies currently support status, stop, pause, continue, resume, connect and disconnect only.");
        feedback(source, entry.explanation());
        return 1;
    }

    private Actor nativeActor(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            if (server.getPlayerManager().getPlayer(player.getUuid()) != player) return null;
            return new Actor(player.getUuid(), player.getCommandSource().hasPermissionLevel(2));
        }
        return source.hasPermissionLevel(2) ? Actor.console() : null;
    }

    private boolean nativeMayControl(ServerCommandSource source, Entry entry) {
        Actor actor = nativeActor(source);
        return actor != null && entry.nativeState.access().mayControl(actor);
    }

    private void connectNative(ServerCommandSource source, Entry entry, boolean fresh) {
        if (!fresh && entry.ledgerState != LedgerCheckState.KNOWN_LEDGER_CLEAR) {
            entry.nativePending = false;
            entry.blocker = "Native connection denied: ledger check state is " + entry.ledgerState + ". Reconciliation required.";
            reject(source, entry.blocker);
            return;
        }
        if (entry.metadataFault || entry.reconciliationBlocked) {
            entry.nativePending = false;
            entry.blocker = "Native connection denied: companion has metadata/reconciliation fault.";
            reject(source, entry.blocker);
            return;
        }
        entry.nativePending = true;
        long generation = entry.generation;
        UUID nativeId = entry.data.entityId();
        UUID requestId = UUID.randomUUID();
        long expectedRevision = entry.nativeState != null ? entry.nativeState.revision() : 0L;
        ConnectionToken token = new ConnectionToken(startupGeneration, entry.data.id(), generation, requestId, expectedRevision);
        entry.pendingConnection = token;

        nativeSaveReader.read(server.getSavePath(WorldSavePath.PLAYERDATA), nativeId).whenComplete((saved, readFailure) -> server.execute(
                () -> completeNativeConnection(source, entry, token, fresh, saved, readFailure, 0)));
    }

    /** Ticks a connection waits for the chunk its body was saved in to load before it goes on regardless. */
    private static final int CONNECT_LOAD_TICKS = 200;
    private static final net.minecraft.server.world.ChunkTicketType<net.minecraft.util.math.ChunkPos> NATIVE_CONNECT =
            net.minecraft.server.world.ChunkTicketType.create("kindred_native_connect",
                    java.util.Comparator.comparingLong(net.minecraft.util.math.ChunkPos::toLong), CONNECT_LOAD_TICKS);

    private void completeNativeConnection(ServerCommandSource source, Entry entry, ConnectionToken token, boolean fresh,
                                          java.util.Optional<NativePlayerSaves.Bytes> saved, Throwable readFailure, int waited) {
        if (!running || !ready || startupGeneration != token.startupGeneration() || entries.get(entry.data.id()) != entry) return;
        if (entry.pendingConnection == null || !token.equals(entry.pendingConnection)) {
            // Obsolete request or retired by stop/detach/halt/superseding request.
            return;
        }
        // A body saved where no chunk is loaded (in the Nether after a restart, or far from spawn): its chunk is
        // loaded first, as a joining player's is, and the connection goes on once it is in. Staged End run 7 was
        // blocked for reconciliation on reconnecting a player saved in the Nether.
        if (!fresh && readFailure == null && waited < CONNECT_LOAD_TICKS) {
            var area = savedArea(entry, saved);
            if (area != null && !area.getKey().isChunkLoaded(area.getValue())) {
                var chunk = new net.minecraft.util.math.ChunkPos(area.getValue());
                area.getKey().getChunkManager().addTicket(NATIVE_CONNECT, chunk, 1, chunk);
                NextTick.run(() -> completeNativeConnection(source, entry, token, fresh, saved, readFailure, waited + 1));
                return;
            }
        }
        entry.pendingConnection = null;
        if (entry.generation != token.entryGeneration() || (fresh && entry.snapshot().stopLatched())) {
            entry.nativePending = false;
            entry.blocker = "Native connection cancelled before dispatch; no late callback may restart it.";
            return;
        }
        if (!fresh && entry.ledgerState != LedgerCheckState.KNOWN_LEDGER_CLEAR) {
            entry.nativePending = false;
            entry.blocker = "Native connection denied: ledger check state is " + entry.ledgerState + ". Reconciliation required.";
            return;
        }
        if (entry.metadataFault || entry.reconciliationBlocked) {
            entry.nativePending = false;
            entry.blocker = "Native connection denied: companion has metadata/reconciliation fault.";
            return;
        }
        if (entry.nativeState == null || entry.nativeState.revision() != token.expectedNativeRevision()) {
            entry.nativePending = false;
            entry.blocker = "Native connection denied: native revision changed before dispatch.";
            return;
        }
        if (!nativeMayControl(source, entry) || (fresh && (nativeActor(source) == null || !nativeActor(source).operator()))) {
            entry.nativePending = false;
            entry.blocker = "Native connection lost administrative authority before dispatch.";
            return;
        }
        try {
            if (readFailure != null) throw new IllegalStateException("Native save could not be read", readFailure);
            var world = server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,
                    Identifier.of(entry.data.dimension())));
            if (world == null) throw new IllegalStateException("Native dimension is unavailable");
            java.util.Optional<net.minecraft.nbt.NbtCompound> state = saved.map(bytes -> bytes.decode(server));
            net.minecraft.util.math.Vec3d position;
            if (fresh) {
                if (state.isPresent()) throw new IllegalStateException("Fresh creation conflicts with native data");
                SpawnLocation spawn = nativeSpawn(source);
                if (spawn == null || source.getWorld() != world) throw new IllegalStateException("Creation area is no longer available");
                position = new net.minecraft.util.math.Vec3d(spawn.x(), spawn.y(), spawn.z());
            } else {
                var tag = state.orElseThrow(() -> new IllegalStateException("Native save is missing"));
                var pos = tag.getList("Pos", net.minecraft.nbt.NbtElement.DOUBLE_TYPE);
                if (pos.size() != 3) throw new IllegalStateException("Native save position is invalid");
                position = new net.minecraft.util.math.Vec3d(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2));
            }
            NativePlayerBody body = new NativePlayerBody(world, entry.snapshot(), position,
                    (lease, intent, target) -> nativeMutationAllowed(entry, lease, intent, target));
            body.pvp((attacker, target) -> server.isPvpEnabled() && matches.mayAttack(attacker, target));
            entry.arbiter = new ActionArbiter(entry.snapshot(), body);
            body.bind(entry.arbiter);
            if (!fresh) body.verifySave(state.orElseThrow(), entry.nativeState.saveReceipt());
            entry.nativeBody = body;
            entry.runner = new SkillRunner(entry.arbiter, PlayerRuntime::nanos);
            body.onTick(() -> beforeNativeTick(entry, body));
            body.onDeath(() -> onNativeDeath(entry, body));
            body.onHitByPlayer((by, taken) -> hitByPlayer(entry, body.entity(), by, taken));
            body.connect(state, !fresh);
            entry.data = entry.arbiter.snapshot();
            if (fresh) establishNative(source, entry, body, body.saveNow());
            else {
                entry.nativePending = false;
                entry.blocker = entry.snapshot().stopLatched() ? "Native body connected; stopped. Resume explicitly."
                        : "Native body connected; automatic progression remains disabled.";
                persist(entry);
                feedback(source, entry.explanation());
            }
        } catch (RuntimeException failure) { nativeFailure(source, entry, "Native connection requires reconciliation", failure); }
    }

    /** The world and block a saved body stands in, or null when the save cannot say. */
    private java.util.Map.Entry<net.minecraft.server.world.ServerWorld, BlockPos> savedArea(Entry entry, java.util.Optional<NativePlayerSaves.Bytes> saved) {
        try {
            var world = server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, Identifier.of(entry.data.dimension())));
            if (world == null || saved.isEmpty()) return null;
            var pos = saved.get().decode(server).getList("Pos", net.minecraft.nbt.NbtElement.DOUBLE_TYPE);
            if (pos.size() != 3) return null;
            return java.util.Map.entry(world, BlockPos.ofFloored(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)));
        } catch (RuntimeException unreadable) {
            return null; // The connection itself reports it.
        }
    }

    private void establishNative(ServerCommandSource source, Entry entry, NativePlayerBody body, UUID receipt) {
        nativeSaveReader.read(server.getSavePath(WorldSavePath.PLAYERDATA), entry.data.entityId()).whenComplete((saved, failure) -> server.execute(() -> {
            if (!running || entries.get(entry.data.id()) != entry) return;
            try {
                if (failure != null) throw new IllegalStateException("Native save verification failed", failure);
                body.verifySave(saved.orElseThrow(() -> new IllegalStateException("Native save was not written")).decode(server), receipt);
                // This settles save bookkeeping even after stop; it cannot resume work or spawn a body.
                store.establishNativePlayer(entry.data.id(), receipt, entry.nativeState.revision()).whenComplete((established, storeFailure) -> server.execute(() -> {
                    if (!running || entries.get(entry.data.id()) != entry) return;
                    if (storeFailure != null) { nativeFailure(source, entry, "Native establishment could not be saved", storeFailure); return; }
                    entry.nativeState = established;
                    entry.nativePending = false;
                    entry.blocker = "Native body established with a verified save; autonomous progression remains disabled.";
                    persist(entry);
                    feedback(source, entry.data.displayName() + " (" + entry.data.id() + "): " + entry.explanation());
                }));
            } catch (RuntimeException invalid) { nativeFailure(source, entry, "Native save requires reconciliation", invalid); }
        }));
    }

    private void nativeFailure(ServerCommandSource source, Entry entry, String reason, Throwable failure) {
        entry.pendingConnection = null;
        entry.reconciliationBlocked = true;
        detachNative(entry, reason);
        entry.nativePending = false;
        store.blockNativePlayer(entry.data.id(), reason, entry.nativeState.revision()).whenComplete((blocked, error) -> server.execute(() -> {
            if (!running || entries.get(entry.data.id()) != entry) return;
            if (error == null) entry.nativeState = blocked;
            else entry.metadataFault = true;
        }));
        KindredMod.LOGGER.error("Native player {} blocked: {}", entry.data.id(), reason, failure);
        reject(source, reason + "; no replacement inventory will be created.");
    }

    private void detachNative(Entry entry, String reason) {
        entry.pendingConnection = null;
        entry.nativePending = false;
        halt(entry, Actor.console(), reason);
        // A body taken out of the game leaves its match as any player who leaves does.
        if (entry.nativeBody != null && entry.nativeBody.entity() != null) matches.departed(entry.nativeBody.entity().getUuid()).ifPresent(this::announce);
        if (entry.nativeBody != null) entry.nativeBody.disconnect();
        socials.discard(entry.data.id());
        entry.data = entry.snapshot();
        entry.nativeBody = null;
        entry.arbiter = null;
        entry.runner = null;
        entry.cachedRoutineCandidates = List.of();
        entry.cachedMiningTarget = null;
        entry.cachedApproachTarget = null;
        entry.cachedCraftingTablePos = null;
        entry.cachedFurnacePos = null;
        attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
        attentionScheduler.invalidate(entry.data.id(), WorkKind.RESOURCE_DISCOVERY);
        attentionScheduler.invalidate(entry.data.id(), WorkKind.EQUIPMENT_READINESS);
        closeNativeActivity(entry);
    }

    record NativeActivityStatus(String phase, long chargedMillis, long observedMillis, String work) { }

    record NativeSetupStatus(WorkSetup setup, boolean pending) { }
    NativeSetupStatus nativeSetupStatus(Actor actor, UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        return entry == null || entry.nativeState == null || actor == null || !entry.nativeState.access().mayControl(actor)
                ? null : new NativeSetupStatus(entry.setup, entry.nativeSetupPending);
    }

    int nativePatch(ServerCommandSource source, UUID id, BlockPos a, BlockPos b, String resource) {
        requireThread();
        Entry entry = entries.get(id);
        if (!running || !ready || entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (entry.metadataFault || entry.reconciliationBlocked || entry.nativeState.phase() != StoredNativePlayer.Phase.ESTABLISHED
                || entry.nativeSetupPending || !entry.setupWrite.isDone() || entry.setupWrite.isCompletedExceptionally()) {
            return reject(source, "Reconcile native state and finish pending setup writes first.");
        }
        var world = source.getWorld();
        if (!world.getRegistryKey().getValue().toString().equals(entry.data.dimension())) return reject(source, "Work patch must be assigned in the native player's dimension.");
        WorkSetup previous = entry.setup;
        WorkSetup changed;
        try {
            var block = Identifier.tryParse(resource);
            if (block == null || !Registries.BLOCK.containsId(block)) throw new IllegalArgumentException("Unknown resource block");
            var min = new WorkSetup.BlockPoint(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            var max = new WorkSetup.BlockPoint(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
            var area = new WorkSetup.WorkRegion(min.x(), min.z(), max.x(), max.z());
            var patch = new WorkSetup.ResourcePatch(min, max, block.toString());
            var low = new BlockPos(min.x(), min.y(), min.z());
            var high = new BlockPos(max.x(), max.y(), max.z());
            if (!world.isInBuildLimit(low) || !world.isInBuildLimit(high) || !world.getWorldBorder().contains(low) || !world.getWorldBorder().contains(high)) {
                throw new IllegalArgumentException("Work patch must stay inside loaded world/build bounds");
            }
            for (int cx = min.x() >> 4; cx <= max.x() >> 4; cx++) for (int cz = min.z() >> 4; cz <= max.z() >> 4; cz++) {
                if (!world.isChunkLoaded(new BlockPos(cx << 4, min.y(), cz << 4))) throw new IllegalArgumentException("Load every work-patch chunk before assigning it");
            }
            changed = new WorkSetup(id, previous.dimension(), previous.home(), area, previous.storage(), patch, Math.incrementExact(previous.revision()));
        } catch (IllegalArgumentException invalid) { return reject(source, invalid.getMessage()); }
        halt(entry, Actor.console(), "Resource-work patch changed; stopped until explicit resume.");
        entry.nativeSetupPending = true;
        entry.setup = changed; // Apply changed authority immediately; stop stays latched throughout IO.
        entry.setupWrite = store.saveWorkSetup(changed, previous.revision()).thenApply(saved -> {
            if (!changed.equals(saved)) throw new IllegalStateException("Mismatched durable native setup acknowledgement");
            return saved;
        });
        entry.setupWrite.whenComplete((saved, failure) -> server.execute(() -> {
            if (!running || entries.get(id) != entry) return;
            entry.nativeSetupPending = false;
            if (failure != null) {
                entry.metadataFault = true;
                halt(entry, Actor.console(), "Native work scope could not be saved; reconcile metadata.");
                reject(source, entry.blocker);
            } else feedback(source, "Saved resource-work patch " + saved.patch().resourceId() + " at " + point(saved.patch().min()) + " to " + point(saved.patch().max())
                    + ". Player remains stopped. This configures no new mining capability or server-edit permission.");
        }));
        feedback(source, "Saving bounded resource-work scope; native work revoked immediately.");
        return 1;
    }

    /**
     * Assigns the container at {@code pos} as a native player's own storage (E-08c), saved with its work setup as a
     * patch is: the container verified now and again before every take, the player stopped until resumed.
     */
    int nativeStorage(ServerCommandSource source, UUID id, BlockPos pos) {
        requireThread();
        Entry entry = entries.get(id);
        if (!running || !ready || entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (entry.metadataFault || entry.reconciliationBlocked || entry.nativeState.phase() != StoredNativePlayer.Phase.ESTABLISHED
                || entry.nativeSetupPending || !entry.setupWrite.isDone() || entry.setupWrite.isCompletedExceptionally()) {
            return reject(source, "Reconcile native state and finish pending setup writes first.");
        }
        var world = source.getWorld();
        if (!world.getRegistryKey().getValue().toString().equals(entry.data.dimension())) return reject(source, "Storage must be in the native player's dimension.");
        StorageTargets.Observation observed = StorageTargets.observe(world, pos);
        if (observed == null || !observed.container()) return reject(source, "That block is not a container.");
        WorkSetup previous = entry.setup;
        WorkSetup changed = previous.withStorage(new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()),
                observed.blockId(), observed.version()));
        halt(entry, Actor.console(), "Storage changed; stopped until explicit resume.");
        entry.nativeSetupPending = true;
        entry.setup = changed; // Apply changed authority immediately; stop stays latched throughout IO.
        entry.setupWrite = store.saveWorkSetup(changed, previous.revision()).thenApply(saved -> {
            if (!changed.equals(saved)) throw new IllegalStateException("Mismatched durable native setup acknowledgement");
            return saved;
        });
        entry.setupWrite.whenComplete((saved, failure) -> server.execute(() -> {
            if (!running || entries.get(id) != entry) return;
            entry.nativeSetupPending = false;
            if (failure != null) {
                entry.metadataFault = true;
                halt(entry, Actor.console(), "Native storage could not be saved; reconcile metadata.");
                reject(source, entry.blocker);
            } else feedback(source, "Saved storage " + observed.blockId() + " at " + pos.toShortString() + ". Player remains stopped.");
        }));
        feedback(source, "Saving the player's storage; native work revoked immediately.");
        return 1;
    }

    /** Whether a native player's setup change is still being saved. */
    boolean nativeSetupSaving(UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        return entry != null && (entry.nativeSetupPending || !entry.setupWrite.isDone());
    }

    record NativeActionStatus(String phase, ActionRecord record) { }
    NativeActionStatus nativeActionStatus(Actor actor, UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        if (entry == null || entry.nativeState == null || actor == null || !entry.nativeState.access().mayControl(actor)) return null;
        return entry.nativeTask instanceof NativeRecordedAction recorded
                ? new NativeActionStatus(recorded.phase(), recorded.snapshot())
                : new NativeActionStatus(entry.lastNativeAction == null ? "NONE" : entry.lastNativeAction.state().name(), entry.lastNativeAction);
    }

    NativeActivityStatus nativeActivityStatus(Actor actor, UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        if (entry == null || entry.nativeState == null || actor == null || !entry.nativeState.access().mayControl(actor)) return null;
        var session = entry.activity;
        return session == null ? new NativeActivityStatus("UNLOADED", -1, -1, entry.blocker)
                : new NativeActivityStatus(session.phase().name(), session.snapshot().chargedMillis(),
                    session.snapshot().observedActiveMillis(), entry.blocker);
    }

    /** A native task with its arguments, as the commands start one, for fixtures. */
    int nativeTaskForFixtures(ServerCommandSource source, UUID id, String skill, Map<String, String> arguments) {
        return nativeTask(source, id, skill, arguments);
    }

    int nativeSkill(ServerCommandSource source, UUID id, String skill, int value) {
        return nativeTask(source, id, skill, Map.of(skill.equals("native_wait") ? "seconds" : "slot", String.valueOf(value)));
    }

    /** How soon after it is spoken to a player stops to look, in ticks: a task ending later goes on to the next. */
    static final int ATTEND_WITHIN = 60;

    /**
     * Spoken to by name a moment ago by a player in plain sight within twelve blocks: a two-second pause between steps,
     * in which its wait's gaze turns to the speaker, before it goes on with its work, as a player stops to answer
     * (E-33a). Once each time it is spoken to; only on its feet on dry ground, not riding, swimming or asleep. Its
     * place in the work is untouched: the pause comes between one task and the next, never in the middle of one.
     */
    private NativeTaskCandidate attentionCandidate(Entry entry, NativePlayerBody body) {
        if (entry.addressedBy == null || entry.attendedAt == entry.addressedAt || server.getTicks() - entry.addressedAt > ATTEND_WITHIN) return null;
        ServerPlayerEntity player = body.entity();
        ServerPlayerEntity speaker = server.getPlayerManager().getPlayer(entry.addressedBy);
        if (player == null || speaker == null || speaker.getWorld() != player.getWorld()
                || speaker.squaredDistanceTo(player) > NativeWaitSkill.PEOPLE * NativeWaitSkill.PEOPLE || !player.canSee(speaker)) return null;
        if (!player.isOnGround() || player.isTouchingWater() || player.isInLava() || player.hasVehicle() || player.isSleeping()) return null;
        UUID taskId = UUID.nameUUIDFromBytes(("attend:" + entry.data.id() + ":" + entry.addressedAt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entry.attentionTaskId = taskId;
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 99.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        return new NativeTaskCandidate(candidate, "native_wait", Map.of("seconds", "2"));
    }

    /** A wait looking at what it waits on, at {@code at} (E-33a). */
    int nativeWaitAt(ServerCommandSource source, UUID id, int seconds, BlockPos at) {
        return nativeTask(source, id, "native_wait", Map.of("seconds", String.valueOf(seconds),
                "x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()), "z", String.valueOf(at.getZ())));
    }

    int nativeWalk(ServerCommandSource source, UUID id, String direction, int blocks) {
        return nativeTask(source, id, "native_walk", Map.of("direction", direction, "blocks", String.valueOf(blocks)));
    }

    int nativeTraverse(ServerCommandSource source, UUID id, String direction, int blocks) {
        return nativeTask(source, id, "native_traverse", Map.of("direction", direction, "blocks", String.valueOf(blocks)));
    }

    int nativeGoto(ServerCommandSource source, UUID id, BlockPos target, double range) {
        return nativeTask(source, id, "native_goto", Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()), "range", String.valueOf(range)));
    }

    /** As {@link #nativeGoto}, allowing one deliberate drop of up to {@code drop} blocks (broken with water when deep). */
    int nativeGotoDropping(ServerCommandSource source, UUID id, BlockPos target, double range, int drop) {
        return nativeTask(source, id, "native_goto", Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()), "range", String.valueOf(range), "partial", "false", "drop", String.valueOf(drop)));
    }

    /** As {@link #nativeGoto}, keeping out of the notice of hostile mobs other than the {@code engage} kinds. */
    int nativeGotoAvoiding(ServerCommandSource source, UUID id, BlockPos target, double range, String engage) {
        return nativeTask(source, id, "native_goto", Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()), "range", String.valueOf(range), "avoid", "true", "engage", engage));
    }

    int nativeBreak(ServerCommandSource source, UUID id, BlockPos target) {
        return nativeTask(source, id, "native_break", Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ())));
    }

    /** As {@link #nativeBreak}, leaving the drop where it falls when {@code collect} is false (bars broken up on a cage). */
    int nativeBreak(ServerCommandSource source, UUID id, BlockPos target, boolean collect) {
        return nativeTask(source, id, "native_break", Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()), "collect", String.valueOf(collect)));
    }

    /** Cast one obsidian block in place at {@code target} from the player's own lava and water buckets. */
    int nativeCast(ServerCommandSource source, UUID id, BlockPos target) {
        return nativeTask(source, id, "native_cast", Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ())));
    }

    int nativeRetreat(ServerCommandSource source, UUID id, String direction, int blocks) {
        return nativeTask(source, id, "native_retreat", Map.of("direction", direction, "blocks", String.valueOf(blocks)));
    }

    int nativeSwap(ServerCommandSource source, UUID id, int fromSlot, int toSlot) {
        return nativeTask(source, id, "native_swap", Map.of("source", String.valueOf(fromSlot), "target", String.valueOf(toSlot)));
    }

    int nativeMine(ServerCommandSource source, UUID id, BlockPos target, int hotbarSlot) {
        return nativeTask(source, id, "native_mine", Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()), "slot", String.valueOf(hotbarSlot)));
    }

    int nativeGather(ServerCommandSource source, UUID id, int count, int hotbarSlot) {
        return nativeTask(source, id, "native_gather", Map.of("count", String.valueOf(count), "slot", String.valueOf(hotbarSlot)));
    }

    int nativeCraft(ServerCommandSource source, UUID id, String recipe) {
        return nativeTask(source, id, "native_craft", Map.of("recipe", recipe));
    }

    int nativePlace(ServerCommandSource source, UUID id, BlockPos target, String side, int hotbarSlot) {
        return nativeTask(source, id, "native_place", Map.of(
                "x", String.valueOf(target.getX()),
                "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()),
                "side", side == null ? "up" : side,
                "slot", String.valueOf(hotbarSlot)));
    }

    int nativeSmelt(ServerCommandSource source, UUID id, BlockPos target, String action) {
        Map<String, String> args = new java.util.HashMap<>();
        if (target != null) {
            args.put("x", String.valueOf(target.getX()));
            args.put("y", String.valueOf(target.getY()));
            args.put("z", String.valueOf(target.getZ()));
        }
        if (action != null) {
            args.put("action", action);
        }
        return nativeTask(source, id, "native_smelt", args);
    }

    int nativeAttack(ServerCommandSource source, UUID id, UUID targetId, Integer slot) {
        Map<String, String> args = new java.util.HashMap<>();
        args.put("target", targetId.toString());
        if (slot != null) {
            args.put("slot", String.valueOf(slot));
        }
        return nativeTask(source, id, "native_attack", args);
    }

    int nativeFight(ServerCommandSource source, UUID id, UUID targetId, int slot) {
        return nativeTask(source, id, "native_fight", Map.of("target", targetId.toString(), "slot", String.valueOf(slot)));
    }

    int nativeBlock(ServerCommandSource source, UUID id, int ticks) {
        return nativeTask(source, id, "native_block", Map.of("ticks", String.valueOf(ticks)));
    }

    int nativeTill(ServerCommandSource source, UUID id, BlockPos target, int hotbarSlot) {
        return nativeTask(source, id, "native_till", Map.of(
                "x", String.valueOf(target.getX()),
                "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()),
                "slot", String.valueOf(hotbarSlot)));
    }

    int nativePlant(ServerCommandSource source, UUID id, BlockPos target, int hotbarSlot) {
        return nativeTask(source, id, "native_plant", Map.of(
                "x", String.valueOf(target.getX()),
                "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()),
                "slot", String.valueOf(hotbarSlot)));
    }

    int nativeHarvest(ServerCommandSource source, UUID id, BlockPos target, int hotbarSlot, boolean replant) {
        return nativeTask(source, id, "native_harvest", Map.of(
                "x", String.valueOf(target.getX()),
                "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()),
                "slot", String.valueOf(hotbarSlot),
                "replant", String.valueOf(replant)));
    }

    int nativeSleep(ServerCommandSource source, UUID id, BlockPos target) {
        return nativeTask(source, id, "native_sleep", Map.of(
                "x", String.valueOf(target.getX()),
                "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ())));
    }

    int nativeIgnitePortal(ServerCommandSource source, UUID id, BlockPos target, int hotbarSlot) {
        return nativeTask(source, id, "native_ignite_portal", Map.of(
                "x", String.valueOf(target.getX()),
                "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()),
                "slot", String.valueOf(hotbarSlot)));
    }

    /**
     * One step toward {@code target} on a route that may dig, bridge or pillar (F-10e), dispatched as the ordinary
     * recorded skill it needs; 0 when there is nothing to do from here (arrived, or no way at all).
     */
    int nativeRouteTo(ServerCommandSource source, UUID id, BlockPos target) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeBody == null || !entry.nativeBody.connected()) return reject(source, "Native player unavailable.");
        if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
        entry.campaign.observe(entry.nativeBody.entity());
        var task = entry.campaign.buildRoute(entry.nativeBody, edits, target, pos -> pos.equals(target), "Route");
        if (task.isEmpty()) return 0;
        return nativeTask(source, id, task.get().skill(), task.get().args());
    }

    /** One recorded shot from the bow in {@code hotbarSlot} at the entity {@code target}, as the dragon goal takes it (E-39b). */
    int nativeShoot(ServerCommandSource source, UUID id, int hotbarSlot, UUID target) {
        return nativeTask(source, id, "native_shoot", Map.of("slot", String.valueOf(hotbarSlot), "target", target.toString()));
    }

    /** As {@link #nativeShoot}, on the high arc when {@code high} (down through an opened cage roof). */
    int nativeShoot(ServerCommandSource source, UUID id, int hotbarSlot, UUID target, boolean high) {
        return nativeTask(source, id, "native_shoot", high ? Map.of("slot", String.valueOf(hotbarSlot), "target", target.toString(), "arc", "high")
                : Map.of("slot", String.valueOf(hotbarSlot), "target", target.toString()));
    }

    /** One recorded boat step ({@code launch} from {@code hotbarSlot} onto {@code water}, {@code board} or {@code land}), as the goal takes it. */
    int nativeBoat(ServerCommandSource source, UUID id, String mode, int hotbarSlot, BlockPos water) {
        var args = new java.util.HashMap<String, String>(Map.of("mode", mode));
        if (hotbarSlot >= 0) args.put("slot", String.valueOf(hotbarSlot));
        if (water != null) {
            args.put("x", String.valueOf(water.getX()));
            args.put("y", String.valueOf(water.getY()));
            args.put("z", String.valueOf(water.getZ()));
        }
        return nativeTask(source, id, "native_boat", args);
    }

    /** Pushes the minecart {@code cart} along its rails by walking into it, until it rolls (E-45e). */
    int nativePush(ServerCommandSource source, UUID id, UUID cart, int seconds) {
        return nativeTask(source, id, "native_push", Map.of("target", cart.toString(), "seconds", String.valueOf(seconds)));
    }

    /** Rows the boat the native player is in toward {@code x}, {@code z} for at most {@code seconds}. */
    int nativeRow(ServerCommandSource source, UUID id, double x, double z, int seconds) {
        return nativeTask(source, id, "native_row", Map.of("x", String.valueOf(x), "z", String.valueOf(z), "seconds", String.valueOf(seconds)));
    }

    /** One recorded bed set down on top of {@code floor} facing {@code facing} and used, setting the respawn point (E-39g). */
    int nativeBed(ServerCommandSource source, UUID id, int hotbarSlot, BlockPos floor, Direction facing) {
        return nativeTask(source, id, "native_bed", Map.of("x", String.valueOf(floor.getX()), "y", String.valueOf(floor.getY()),
                "z", String.valueOf(floor.getZ()), "slot", String.valueOf(hotbarSlot), "facing", facing.asString()));
    }

    /** One recorded ender pearl throw from {@code hotbarSlot} at {@code yaw} and {@code pitch} (E-39f), as the goal throws its own. */
    int nativePearl(ServerCommandSource source, UUID id, int hotbarSlot, float yaw, float pitch) {
        return nativeTask(source, id, "native_pearl", Map.of("slot", String.valueOf(hotbarSlot), "yaw", String.valueOf(yaw), "pitch", String.valueOf(pitch)));
    }

    /** One recorded eye-of-ender throw from {@code hotbarSlot}, facing {@code yaw}; the goal throws its own the same way. */
    int nativeThrowEye(ServerCommandSource source, UUID id, int hotbarSlot, float yaw) {
        return nativeTask(source, id, "native_throw_eye", Map.of("slot", String.valueOf(hotbarSlot), "yaw", String.valueOf(yaw)));
    }

    /** What the native player's last skill reported (an eye's flight, a block's position), for fixtures. */
    Map<String, String> nativeLastResult(UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        return entry == null || entry.runner == null ? Map.of() : entry.runner.lastProgress();
    }

    int nativeProgression(ServerCommandSource source, UUID requestedId) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading saved records.");
        if (requestedId == null) {
            requestedId = defaultBotId();
        }
        Entry entry = requestedId != null ? entries.get(requestedId) : null;
        if (entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        var state = nativeProgressionState(requestedId);
        if (state == null) {
            feedback(source, entry.data.displayName() + " has no progression state available.");
            return 1;
        }
        long achievedCount = state.snapshots().values().stream().filter(MilestoneSnapshot::historicallyAchieved).count();
        long physicalCount = state.snapshots().values().stream().filter(MilestoneSnapshot::physicalPostconditionSatisfied).count();
        feedback(source, entry.data.displayName() + " progression: " + physicalCount + " physical, " + achievedCount + " achieved ("
                + state.snapshots().size() + " total). Active frontier: " + state.activeFrontier()
                + ". Recovery frontier: " + (state.recoveryFrontier().isEmpty() ? "none" : state.recoveryFrontier()) + ".");
        return 1;
    }

    private dev.kindred.core.ActionAdmission.Writer nativeActionWriter() {
        SqliteCompanionStore database = store;
        return actionWriterDecorator.apply(new dev.kindred.core.ActionAdmission.Writer() {
            public CompletableFuture<ActionRecord> prepare(ActionRecord record) { return database.prepareAction(record); }
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long revision) { return database.transitionAction(next, revision); }
        });
    }

    private int nativeTask(ServerCommandSource source, UUID id, String skill, Map<String, String> arguments) {
        requireThread();
        if (id == null) {
            id = defaultBotId();
        }
        Entry entry = id != null ? entries.get(id) : null;
        if (!running || !ready || entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        int res = dispatchNativeTask(nativeActor(source), entry, skill, arguments);
        if (res == 1) {
            // A direct request holds autonomous selection briefly so the requester sees its own outcome.
            entry.manualHoldUntilTick = server.getTicks() + MANUAL_COMMAND_HOLD_TICKS;
            feedback(source, "Requested " + skill + "; no work begins before durable paid admission.");
        } else {
            reject(source, entry.blocker);
        }
        return res;
    }

    private int dispatchNativeTask(Actor actor, Entry entry, String skill, Map<String, String> arguments) {
        requireThread();
        UUID id = entry.data.id();
        if (!running || !ready || entry.nativeState == null || (actor != null && !entry.nativeState.access().mayControl(actor))) {
            return 0;
        }
        if (entry.metadataFault || entry.reconciliationBlocked || entry.nativePending || entry.nativeWorkPending || entry.nativeSetupPending
                || entry.ledgerState != LedgerCheckState.KNOWN_LEDGER_CLEAR
                || entry.nativeState.phase() != StoredNativePlayer.Phase.ESTABLISHED || entry.nativeBody == null
                || !entry.nativeBody.connected() || !entry.nativeBody.entity().isAlive() || entry.snapshot().stopLatched()
                || runnerBusy(entry) || !entry.activityFlush.isDone() || entry.nativeActionPending || !entry.nativeActionFlush.isDone()
                || entry.nativeActionFlush.isCompletedExceptionally()
                || (entry.activity != null && entry.activity.phase() != PaidActivitySession.Phase.IDLE)) {
            entry.blocker = "Connect and resume a reconciled, idle native player; activity settlement must finish first.";
            return 0;
        }
        final dev.kindred.core.SkillDescriptor descriptor;
        final dev.kindred.core.SkillBody task;
        try {
            descriptor = NativePlayerSkills.descriptor(skill);
            descriptor.validate(arguments);
            if (skill.equals("native_mine")) {
                BlockPos mineTarget = new BlockPos(Integer.parseInt(arguments.get("x")),
                        Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z")));
                entry.nativeBody.faceBlock(mineTarget);
                task = new NativeMiningAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute, mineTarget, Integer.parseInt(arguments.get("slot")));
            } else if (skill.equals("native_gather")) {
                task = new NativeGatherAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute, Integer.parseInt(arguments.get("count")), Integer.parseInt(arguments.get("slot")));
            } else if (skill.equals("native_craft")) {
                task = new NativeCraftAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        arguments.get("recipe"), entry.cachedCraftingTablePos);
            } else if (skill.equals("native_pillar")) {
                task = new NativePillarAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        Integer.parseInt(arguments.get("slot")));
            } else if (skill.equals("native_stair")) {
                var directions = new java.util.ArrayList<net.minecraft.util.math.Direction>();
                for (String name : arguments.get("dirs").split(",")) {
                    var direction = net.minecraft.util.math.Direction.byName(name.trim());
                    if (direction == null) throw new IllegalArgumentException("Unknown staircase direction " + name);
                    directions.add(direction);
                }
                task = new NativeStairAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        directions, Integer.parseInt(arguments.get("slot")));
            } else if (skill.equals("native_clutch")) {
                task = new NativeClutchAction(entry.nativeBody, () -> entry.lease, () -> entry.setup, () -> edits, nativeActionWriter(), server::execute,
                        Integer.parseInt(arguments.get("slot")));
            } else if (skill.equals("native_pearl")) {
                task = new NativePearlAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        Integer.parseInt(arguments.get("slot")), Float.parseFloat(arguments.get("yaw")), Float.parseFloat(arguments.get("pitch")),
                        arguments.containsKey("x") ? new Vec3d(Double.parseDouble(arguments.get("x")), Double.parseDouble(arguments.get("y")),
                                Double.parseDouble(arguments.get("z"))) : null);
            } else if (skill.equals("native_throw_eye")) {
                task = new NativeThrowEyeAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        Integer.parseInt(arguments.get("slot")), Float.parseFloat(arguments.get("yaw")));
            } else if (skill.equals("native_dragon")) {
                task = new NativeDragonAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        UUID.fromString(arguments.get("target")), Integer.parseInt(arguments.getOrDefault("slot", "-1")),
                        arguments.containsKey("x") ? new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")),
                                Integer.parseInt(arguments.get("z"))) : null);
            } else if (skill.equals("native_shoot")) {
                task = new NativeShootAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        Integer.parseInt(arguments.get("slot")), UUID.fromString(arguments.get("target")), "high".equals(arguments.get("arc")));
            } else if (skill.equals("native_take")) {
                task = new NativeTakeAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute, arguments.get("item"), Integer.parseInt(arguments.get("count")));
            } else if (skill.equals("native_loot")) {
                BlockPos chest = arguments.containsKey("x") ? new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")),
                        Integer.parseInt(arguments.get("z"))) : null;
                UUID cart = arguments.containsKey("cart") ? UUID.fromString(arguments.get("cart")) : null;
                if (arguments.containsKey("raid")) {
                    UUID owner = UUID.fromString(arguments.get("raid"));
                    UUID self = entry.nativeBody.entity().getUuid();
                    task = new NativeLootAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> edits, claims, nativeActionWriter(),
                            server::execute, chest, cart, () -> lootWanted(entry), owner, () -> matches.opponents(self, owner));
                } else {
                    task = new NativeLootAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> edits, claims, nativeActionWriter(),
                            server::execute, chest, cart, () -> lootWanted(entry));
                }
            } else if (skill.equals("native_store")) {
                BlockPos chest = new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z")));
                boolean out = "all".equals(arguments.get("take"));
                var items = out || arguments.containsKey("look") ? Set.<String>of() : Set.of(arguments.getOrDefault("items", "").split(","));
                task = new NativeStoreAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> edits, claims, nativeActionWriter(),
                        server::execute, chest, items, out,
                        () -> entry.nativeBody != null && entry.nativeBody.connected() && ownStore(entry, entry.nativeBody.entity(), chest) != null);
            } else if (skill.equals("native_trade")) {
                task = new NativeTradeAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), claims, nativeActionWriter(), server::execute,
                        UUID.fromString(arguments.get("target")), arguments.containsKey("look"),
                        () -> Valuables.holding(entry.nativeBody.entity(), lootWanted(entry)));
            } else if (skill.equals("native_rename")) {
                task = new NativeRenameAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits, claims,
                        nativeActionWriter(), server::execute, new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")),
                        Integer.parseInt(arguments.get("z"))), Integer.parseInt(arguments.get("slot")), arguments.get("name"));
            } else if (skill.equals("native_feed")) {
                task = new NativeFeedAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        Integer.parseInt(arguments.get("slot")), UUID.fromString(arguments.get("target")));
            } else if (skill.equals("native_fill_frame")) {
                task = new NativeFillFrameAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        Integer.parseInt(arguments.get("slot")),
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))));
            } else if (skill.equals("native_boat")) {
                var mode = NativeBoatAction.Mode.valueOf(arguments.get("mode").toUpperCase(java.util.Locale.ROOT));
                BlockPos water = arguments.containsKey("x") ? new BlockPos(Integer.parseInt(arguments.get("x")),
                        Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))) : null;
                task = new NativeBoatAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        mode, Integer.parseInt(arguments.getOrDefault("slot", "-1")), water);
            } else if (skill.equals("native_cart")) {
                var mode = NativeCartAction.Mode.valueOf(arguments.get("mode").toUpperCase(java.util.Locale.ROOT));
                BlockPos rail = arguments.containsKey("x") ? new BlockPos(Integer.parseInt(arguments.get("x")),
                        Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))) : null;
                task = new NativeCartAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        mode, Integer.parseInt(arguments.getOrDefault("slot", "-1")), rail,
                        arguments.containsKey("target") ? UUID.fromString(arguments.get("target")) : null);
            } else if (skill.equals("native_bed")) {
                task = new NativeBedAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        Direction.byName(arguments.get("facing")), Integer.parseInt(arguments.get("slot")));
            } else if (skill.equals("native_place")) {
                task = new NativePlaceAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        Direction.byName(arguments.getOrDefault("side", "up")), Integer.parseInt(arguments.get("slot")),
                        BuildRunner.aim(entry.nativeBody.entity(), arguments));
            } else if (skill.equals("native_smelt")) {
                BlockPos targetPos = null;
                if (arguments.containsKey("x") && arguments.containsKey("y") && arguments.containsKey("z")) {
                    targetPos = new BlockPos(
                            Integer.parseInt(arguments.get("x")),
                            Integer.parseInt(arguments.get("y")),
                            Integer.parseInt(arguments.get("z")));
                }
                String actionArg = arguments.get("action");
                Integer fuelSlotArg = arguments.containsKey("fuel_slot") ? Integer.parseInt(arguments.get("fuel_slot")) : null;
                Integer inputSlotArg = arguments.containsKey("input_slot") ? Integer.parseInt(arguments.get("input_slot")) : null;
                Integer countArg = arguments.containsKey("count") ? Integer.parseInt(arguments.get("count")) : null;
                task = new NativeSmeltAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute, targetPos, actionArg, fuelSlotArg, inputSlotArg, countArg);
            } else if (skill.equals("native_swap")) {
                int source = Integer.parseInt(arguments.get("source"));
                int target = Integer.parseInt(arguments.get("target"));
                var operation = NativePlayerSkills.body(skill, arguments, entry.nativeBody, () -> entry.lease, nativeMovementHabits(entry));
                task = new NativeSwapAction(operation, entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute, source, target);
            } else if (skill.equals("native_fight")) {
                task = new NativeFightAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        UUID.fromString(arguments.get("target")), Integer.parseInt(arguments.getOrDefault("slot", "-1")));
            } else if (skill.equals("native_attack")) {
                UUID targetId = UUID.fromString(arguments.get("target"));
                Integer slot = arguments.containsKey("slot") ? Integer.parseInt(arguments.get("slot")) : null;
                var operation = NativePlayerSkills.body(skill, arguments, entry.nativeBody, () -> entry.lease, nativeMovementHabits(entry));
                task = new NativeAttackAction(operation, entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute, targetId, slot);
            } else if (skill.equals("native_till")) {
                task = new NativeTillAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        Integer.parseInt(arguments.get("slot")), Boolean.parseBoolean(arguments.getOrDefault("roam", "false")));
            } else if (skill.equals("native_plant")) {
                task = new NativePlantAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        Integer.parseInt(arguments.get("slot")), Boolean.parseBoolean(arguments.getOrDefault("roam", "false")));
            } else if (skill.equals("native_harvest")) {
                int harvestSlot = arguments.containsKey("slot") ? Integer.parseInt(arguments.get("slot")) : -1;
                boolean replant = Boolean.parseBoolean(arguments.getOrDefault("replant", "true"));
                task = new NativeHarvestAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        harvestSlot, replant);
            } else if (skill.equals("native_sleep")) {
                task = new NativeSleepAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        bed -> ownBed(entry, entry.nativeBody.entity().getServerWorld(), bed));
            } else if (skill.equals("native_ignite_portal")) {
                task = new NativeIgnitePortalAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        Integer.parseInt(arguments.get("slot")));
            } else if (skill.equals("native_organize_chest")) {
                int maxMoves = arguments.containsKey("max_moves") ? Integer.parseInt(arguments.get("max_moves")) : 4;
                task = new NativeChestOrganizeAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        maxMoves);
            } else if (skill.equals("native_break")) {
                task = new NativeBreakAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> edits, claims,
                        nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        Boolean.parseBoolean(arguments.getOrDefault("collect", "true")),
                        Boolean.parseBoolean(arguments.getOrDefault("guarded", "false")),
                        Boolean.parseBoolean(arguments.getOrDefault("underfoot", "false")));
            } else if (skill.equals("native_bucket")) {
                task = new NativeBucketAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> edits, claims,
                        nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))),
                        "pour".equals(arguments.get("mode")) ? NativeBucketAction.Mode.POUR
                                : "pour_lava".equals(arguments.get("mode")) ? NativeBucketAction.Mode.POUR_LAVA : NativeBucketAction.Mode.FILL);
            } else if (skill.equals("native_cast")) {
                task = new NativeCastAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> edits, claims,
                        nativeActionWriter(), server::execute,
                        new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))));
            } else if (skill.equals("native_discard")) {
                task = new NativeTossAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        Integer.parseInt(arguments.get("slot")), Integer.parseInt(arguments.get("count")), null);
            } else if (skill.equals("native_toss")) {
                task = new NativeTossAction(entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute,
                        Integer.parseInt(arguments.get("slot")),
                        Integer.parseInt(arguments.get("count")),
                        UUID.fromString(arguments.get("target")));
            } else {
                var operation = NativePlayerSkills.body(skill, arguments, entry.nativeBody, () -> entry.lease, nativeMovementHabits(entry));
                task = skill.equals("native_equip") ? new NativeEquipmentAction(operation, entry.nativeBody, () -> entry.lease, nativeActionWriter(), server::execute) : operation;
            }
        } catch (IllegalArgumentException invalid) {
            entry.blocker = invalid.getMessage();
            return 0;
        }
        if (admission.request(id, id, 1) != AdmissionBoard.Decision.ADMIT) {
            entry.blocker = "No activity seat is available.";
            return 0;
        }
        entry.nativeWorkPending = true;
        long generation = entry.generation;
        var preparation = store.limit(id).thenCompose(limit -> {
            var period = AccountingPeriod.containing(java.time.Instant.now(), java.time.ZoneId.of(limit.timezone()));
            return store.openBudget(id, period).thenApply(budget -> new NativeBudget(limit, budget));
        });
        // Track preparation as well as settlement so shutdown never closes the store underneath it.
        entry.activityFlush = preparation.handle((ignored, failure) -> null);
        preparation.whenComplete((prepared, failure) -> server.execute(() -> {
            if (!running || entry.generation != generation || (actor != null && !entry.nativeState.access().mayControl(actor))
                    || entry.metadataFault || entry.reconciliationBlocked
                    || entry.ledgerState != LedgerCheckState.KNOWN_LEDGER_CLEAR
                    || entry.snapshot().stopLatched()
                    || entry.arbiter.cancellationPending() || entry.hazardInterrupted) {
                entry.nativeWorkPending = false;
                admission.release(id);
                return;
            }
            if (failure != null) { nativeActivityFailure(entry, "Activity budget could not be loaded"); return; }
            try {
                entry.activity = new PaidActivitySession(prepared.budget(), Duration.ofMillis(prepared.limit().capMillis()),
                        store::saveBudget, server::execute, java.time.Clock.systemUTC(), System::nanoTime);
                entry.activity.request(prepared.budget().period().endExclusive()).whenComplete((paid, error) -> {
                    entry.nativeWorkPending = false; // Session completion runs on the server thread.
                    if (error != null) { nativeActivityFailure(entry, "Activity acknowledgement is uncertain"); return; }
                    if (!running || generation != entry.generation || (actor != null && !entry.nativeState.access().mayControl(actor))
                            || entry.metadataFault || entry.reconciliationBlocked
                            || entry.ledgerState != LedgerCheckState.KNOWN_LEDGER_CLEAR
                            || entry.snapshot().stopLatched()
                            || entry.arbiter.cancellationPending() || entry.hazardInterrupted
                            || entry.nativeBody == null || !entry.nativeBody.connected() || !Boolean.TRUE.equals(paid)
                            || !entry.activity.paid()) {
                        entry.activity.stop(); admission.release(id);
                        if (running && generation == entry.generation) entry.blocker = entry.activity.blocker().isBlank()
                                ? "Native request cancelled before paid dispatch." : entry.activity.blocker();
                        return;
                    }
                    try {
                        entry.skillId = skill;
                        entry.skillArgs = Map.copyOf(arguments);
                        entry.nativeTask = task;
                        UUID taskId = entry.currentTaskId != null ? entry.currentTaskId : UUID.randomUUID();
                        entry.lease = entry.runner.start(descriptor, arguments,
                                task, actor == null ? Actor.console() : actor, taskId, java.util.Optional.empty());
                        entry.lastRunnerState = SkillRunner.State.RUNNING;
                        entry.blocker = "Running " + skill + " under one acknowledged activity slice.";
                        persist(entry);
                    } catch (RuntimeException denied) {
                        entry.activity.stop(); admission.release(id);
                        entry.blocker = "Native skill denied: " + denied.getMessage();
                    }
                });
            } catch (RuntimeException invalid) { nativeActivityFailure(entry, "Activity state requires reconciliation"); }
        }));
        return 1;
    }

    private record NativeBudget(ActivityLimit limit, BudgetState budget) { }

    private void nativeActivityFailure(Entry entry, String reason) {
        entry.metadataFault = true;
        entry.nativeWorkPending = false;
        halt(entry, Actor.console(), reason + "; stopped pending reconciliation.");
    }

    private void closeNativeActivity(Entry entry) {
        if (entry.activity != null) {
            entry.activityFlush = CompletableFuture.allOf(entry.activityFlush, entry.activity.close());
            entry.activity = null;
        }
    }

    private void closeNativeAction(Entry entry) {
        if (!(entry.nativeTask instanceof NativeRecordedAction recorded)) return;
        entry.nativeTask = null; // A flush callback may stop the entry; never register the same close twice.
        entry.nativeActionPending = true;
        var flush = recorded.close();
        entry.nativeActionFlush = CompletableFuture.allOf(entry.nativeActionFlush, flush);
        flush.whenComplete((saved, failure) -> server.execute(() -> {
            if (!running || entries.get(entry.data.id()) != entry) return;
            entry.nativeActionPending = false;
            if (saved != null) entry.lastNativeAction = saved;
            if (!entry.reconciliationBlocked && (failure != null || (saved != null && saved.state() == ActionRecord.State.UNCERTAIN))) {
                nativeFailure(server.getCommandSource(), entry, "Durable native action requires reconciliation",
                        failure == null ? new IllegalStateException(saved.detail()) : failure);
            }
        }));
    }

    private NativePlayerSkills.NativeMovementHabits nativeMovementHabits(Entry entry) {
        Personality personality = entry.profile.personality();
        // One gaze for the player, kept from one wait to the next so a look held runs on; made again if its personality changes.
        if (entry.gaze == null || !personality.equals(entry.gazePersonality)) {
            entry.gaze = new dev.kindred.core.Gaze(entry.data.id().getLeastSignificantBits() ^ entry.data.id().getMostSignificantBits(), personality);
            entry.gazePersonality = personality;
        }
        return new NativePlayerSkills.NativeMovementHabits(entry.mannerisms, personality, PlayerRuntime::now, entry.gaze,
                () -> server.getTicks() - entry.addressedAt <= NativeWaitSkill.SPOKEN ? entry.addressedBy : null);
    }

    private boolean nativeMutationAllowed(Entry entry, ActionLease lease, NativePlayerBody.Intent intent, BlockPos target) {
        return running && ready && !entry.metadataFault && !entry.reconciliationBlocked && !entry.nativePending && !entry.nativeSetupPending
                && entry.ledgerState == LedgerCheckState.KNOWN_LEDGER_CLEAR
                && entry.nativeState.phase() == StoredNativePlayer.Phase.ESTABLISHED && !entry.snapshot().stopLatched()
                && java.util.Objects.equals(entry.lease, lease) && entry.arbiter.isCurrent(lease)
                && (("native_eat".equals(entry.skillId) && (intent == NativePlayerBody.Intent.EAT || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || (("native_walk".equals(entry.skillId) || "native_traverse".equals(entry.skillId) || "native_retreat".equals(entry.skillId)
                        || "native_goto".equals(entry.skillId) || "native_step_in".equals(entry.skillId)) && intent == NativePlayerBody.Intent.MOVE)
                    || ("native_goto".equals(entry.skillId) && (intent == NativePlayerBody.Intent.SELECT_SLOT || intent == NativePlayerBody.Intent.DOOR))
                    || ("native_feed".equals(entry.skillId) && (intent == NativePlayerBody.Intent.FEED || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_gate".equals(entry.skillId) && intent == NativePlayerBody.Intent.GATE)
                    || (("native_take".equals(entry.skillId) || "native_loot".equals(entry.skillId)) && intent == NativePlayerBody.Intent.TAKE)
                    || ("native_rename".equals(entry.skillId) && intent == NativePlayerBody.Intent.RENAME)
                    || ("native_trade".equals(entry.skillId) && intent == NativePlayerBody.Intent.TRADE)
                    || ("native_cart".equals(entry.skillId) && intent == NativePlayerBody.Intent.CART)
                    || ("native_store".equals(entry.skillId) && intent == NativePlayerBody.Intent.TAKE)
                    || ("native_break".equals(entry.skillId) && (intent == NativePlayerBody.Intent.MINE || intent == NativePlayerBody.Intent.SELECT_SLOT
                        || intent == NativePlayerBody.Intent.SWAP || intent == NativePlayerBody.Intent.MOVE))
                    || (("native_bucket".equals(entry.skillId) || "native_cast".equals(entry.skillId))
                        && (intent == NativePlayerBody.Intent.SWAP || intent == NativePlayerBody.Intent.BUCKET))
                    || ("native_equip".equals(entry.skillId) && intent == NativePlayerBody.Intent.EQUIP)
                    || ("native_mine".equals(entry.skillId) && (intent == NativePlayerBody.Intent.MINE || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_gather".equals(entry.skillId) && (intent == NativePlayerBody.Intent.MINE || intent == NativePlayerBody.Intent.SELECT_SLOT || intent == NativePlayerBody.Intent.MOVE))
                    || ("native_craft".equals(entry.skillId) && intent == NativePlayerBody.Intent.CRAFT)
                    || ("native_place".equals(entry.skillId) && (intent == NativePlayerBody.Intent.PLACE || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_bed".equals(entry.skillId) && (intent == NativePlayerBody.Intent.PLACE || intent == NativePlayerBody.Intent.SLEEP
                        || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || (("native_pillar".equals(entry.skillId) || "native_stair".equals(entry.skillId)) && (intent == NativePlayerBody.Intent.PLACE
                        || intent == NativePlayerBody.Intent.SELECT_SLOT || intent == NativePlayerBody.Intent.MOVE))
                    || ("native_throw_eye".equals(entry.skillId) && (intent == NativePlayerBody.Intent.THROW || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_pearl".equals(entry.skillId) && (intent == NativePlayerBody.Intent.THROW || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_clutch".equals(entry.skillId) && (intent == NativePlayerBody.Intent.BUCKET || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_fill_frame".equals(entry.skillId) && (intent == NativePlayerBody.Intent.FRAME || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_shoot".equals(entry.skillId) && (intent == NativePlayerBody.Intent.SHOOT || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_dragon".equals(entry.skillId) && (intent == NativePlayerBody.Intent.ATTACK || intent == NativePlayerBody.Intent.SELECT_SLOT
                        || intent == NativePlayerBody.Intent.MOVE))
                    || ("native_boat".equals(entry.skillId) && (intent == NativePlayerBody.Intent.BOAT || intent == NativePlayerBody.Intent.SELECT_SLOT
                        || intent == NativePlayerBody.Intent.ATTACK))
                    || (("native_row".equals(entry.skillId) || "native_push".equals(entry.skillId)) && intent == NativePlayerBody.Intent.MOVE)
                    || ("native_smelt".equals(entry.skillId) && intent == NativePlayerBody.Intent.SMELT)
                    || ("native_swap".equals(entry.skillId) && intent == NativePlayerBody.Intent.SWAP)
                    || ("native_attack".equals(entry.skillId) && (intent == NativePlayerBody.Intent.ATTACK || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_fight".equals(entry.skillId) && (intent == NativePlayerBody.Intent.ATTACK || intent == NativePlayerBody.Intent.SELECT_SLOT
                        || intent == NativePlayerBody.Intent.MOVE || intent == NativePlayerBody.Intent.BLOCK))
                    || ("native_block".equals(entry.skillId) && intent == NativePlayerBody.Intent.BLOCK)
                    || ("native_till".equals(entry.skillId) && (intent == NativePlayerBody.Intent.TILL || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_plant".equals(entry.skillId) && (intent == NativePlayerBody.Intent.PLANT || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_harvest".equals(entry.skillId) && (intent == NativePlayerBody.Intent.SELECT_SLOT || intent == NativePlayerBody.Intent.MINE || intent == NativePlayerBody.Intent.MOVE || intent == NativePlayerBody.Intent.PLANT))
                    || ("native_sleep".equals(entry.skillId) && intent == NativePlayerBody.Intent.SLEEP)
                    || ("native_ignite_portal".equals(entry.skillId) && (intent == NativePlayerBody.Intent.IGNITE_PORTAL || intent == NativePlayerBody.Intent.SELECT_SLOT))
                    || ("native_organize_chest".equals(entry.skillId) && intent == NativePlayerBody.Intent.CHEST_ORGANIZE)
                    || (("native_toss".equals(entry.skillId) || "native_discard".equals(entry.skillId)) && intent == NativePlayerBody.Intent.TOSS)
                    || ("native_wait".equals(entry.skillId) && intent == NativePlayerBody.Intent.LOOK))
                && NativePlayerSkills.permits(entry.nativeTask, intent, target)
                && entry.activity != null && entry.activity.paid();
    }

    static boolean isHazardousSituation(ServerPlayerEntity player) {
        return isHazardousSituation(player, true);
    }

    /**
     * The hotbar slot of a water bucket to break the fall the player is in, or -1 (E-07d): falling, out of water and
     * lava, not climbing or gliding, with ground under it that a fall onto would hurt, and in a dimension where water
     * stays poured (not the Nether).
     */
    static int clutchSlot(ServerPlayerEntity player) {
        if (player.isOnGround() || player.isTouchingWater() || player.isInLava() || player.isClimbing() || player.isFallFlying()
                || player.getAbilities().flying || player.hasVehicle() || player.getVelocity().y > -0.3 || player.fallDistance < 2
                || player.getServerWorld().getDimension().ultrawarm()) return -1;
        int slot = -1;
        for (int i = 0; i < 9 && slot < 0; i++) if (player.getInventory().getStack(i).isOf(Items.WATER_BUCKET)) slot = i;
        if (slot < 0) return -1;
        var landing = NativeClutchAction.landing(player);
        if (landing.isEmpty() || !dev.kindred.core.tactics.FallClutch.worth(player.fallDistance, landing.get().getValue(), player.getHealth())) return -1;
        return slot;
    }

    /** As {@link #isHazardousSituation(ServerPlayerEntity)}, counting being alight only when {@code burning} says so. */
    static boolean isHazardousSituation(ServerPlayerEntity player, boolean burning) {
        var world = player.getServerWorld();
        BlockPos feetPos = player.getBlockPos();
        BlockPos groundPos = feetPos.down();
        var feetState = world.getBlockState(feetPos);
        var groundState = world.getBlockState(groundPos);
        // Wading is ordinary movement; water becomes a hazard only once breath is running out.
        boolean drowning = player.isSubmergedInWater() && player.getAir() < player.getMaxAir() / 2;
        boolean inHazard = feetState.isOf(Blocks.FIRE) || feetState.isOf(Blocks.SOUL_FIRE)
                || feetState.isOf(Blocks.LAVA) || feetState.isOf(Blocks.SWEET_BERRY_BUSH)
                || feetState.isOf(Blocks.POWDER_SNOW) || world.getFluidState(feetPos).isIn(FluidTags.LAVA)
                || player.isInLava() || drowning;
        boolean onHazard = groundState.isOf(Blocks.MAGMA_BLOCK) || groundState.isOf(Blocks.CACTUS)
                || groundState.isOf(Blocks.LAVA);
        return inHazard || onHazard || burning && player.isOnFire();
    }

    /** Swimming or sunk: not wading on a shallow bottom with the head above the surface. */
    static boolean inDeepWater(ServerPlayerEntity player) {
        if (player.isSubmergedInWater()) return true;
        var below = player.getBlockPos().down();
        return player.isTouchingWater() && !player.isOnGround() && player.getServerWorld().getFluidState(below).isIn(FluidTags.WATER);
    }

    static String findBestEscapeDirection(ServerPlayerEntity player, Vec3d threatPos) {
        var world = player.getServerWorld();
        BlockPos feetPos = player.getBlockPos();
        String[] dirs = new String[]{"north", "south", "east", "west"};
        BlockPos[] adj = new BlockPos[]{feetPos.north(), feetPos.south(), feetPos.east(), feetPos.west()};
        String bestDir = null;
        double maxDistSq = -1.0;

        for (int d = 0; d < 4; d++) {
            BlockPos targetFeet = adj[d];
            BlockPos targetGround = targetFeet.down();
            if (!world.isChunkLoaded(targetFeet) || !world.isInBuildLimit(targetFeet.up()) || !world.getWorldBorder().contains(targetFeet)) continue;
            var targetSupport = world.getBlockState(targetGround);
            if (!targetSupport.isSideSolidFullSquare(world, targetGround, Direction.UP)
                    || targetSupport.isOf(Blocks.MAGMA_BLOCK) || targetSupport.isOf(Blocks.CACTUS)
                    || !world.getFluidState(targetFeet).isEmpty()) continue;
            var targetFeetState = world.getBlockState(targetFeet);
            if (targetFeetState.isOf(Blocks.FIRE) || targetFeetState.isOf(Blocks.SOUL_FIRE)
                    || targetFeetState.isOf(Blocks.SWEET_BERRY_BUSH) || targetFeetState.isOf(Blocks.POWDER_SNOW)
                    || targetFeetState.isOf(Blocks.LAVA)) continue;
            var box = player.getBoundingBox().offset(Vec3d.ofBottomCenter(targetFeet).subtract(player.getPos()));
            if (!world.isSpaceEmpty(player, box)) continue;

            double distSq = threatPos == null ? 1.0 : Vec3d.ofBottomCenter(targetFeet).squaredDistanceTo(threatPos);
            if (distSq > maxDistSq) {
                maxDistSq = distSq;
                bestDir = dirs[d];
            }
        }
        return bestDir;
    }

    private boolean isWaterNearby(ServerWorld world, BlockPos pos) {
        for (BlockPos p : BlockPos.iterate(pos.add(-4, 0, -4), pos.add(4, 1, 4))) {
            if (!canScanBlock()) {
                return false;
            }
            recordBlockScan();
            if (world.getFluidState(p).isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private void bindBodyBlockScanBudget(NativePlayerBody body) {
        body.bindBlockScanBudget(() -> {
            if (!canScanBlock()) {
                return false;
            }
            recordBlockScan();
            return true;
        });
    }

    /** Workstation discovery for crafting policy; bounded and not charged against the shared scan budget. */
    private void primeWorkstationCaches(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos center = BlockPos.ofFloored(player.getEyePos());
        if (entry.cachedCraftingTablePos == null) {
            entry.cachedCraftingTablePos = locateWorkstationBlock(world, center, Blocks.CRAFTING_TABLE, 4.5);
        }
        if (entry.cachedFurnacePos == null) {
            entry.cachedFurnacePos = locateWorkstationBlock(world, center, Blocks.FURNACE, 4.5);
        }
    }

    private static BlockPos locateWorkstationBlock(ServerWorld world, BlockPos center, net.minecraft.block.Block block, double reach) {
        int radius = (int) Math.ceil(reach);
        int vertical = Math.min(radius, 3);
        BlockPos best = null;
        double bestDistSq = reach * reach;
        for (int dy = -vertical; dy <= vertical; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    double distSq = center.getSquaredDistance(pos);
                    if (distSq > bestDistSq) continue;
                    if (!world.isChunkLoaded(pos)) continue;
                    if (world.getBlockState(pos).isOf(block)) {
                        bestDistSq = distSq;
                        best = pos.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    record NativeTaskCandidate(dev.kindred.core.TaskPreferences.Candidate candidate, String skill, Map<String, String> arguments) { }

    private boolean canScanBlock() {
        return blocksScannedThisTick < MAX_BLOCK_CHECKS_PER_TICK;
    }

    private void recordBlockScan() {
        blocksScannedThisTick++;
        totalBlockChecksMonotonic++;
    }

    private static long computeInventoryFingerprint(ServerPlayerEntity player) {
        long hash = 1;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            var st = inv.getStack(i);
            if (!st.isEmpty()) {
                hash = 31 * hash + i;
                hash = 31 * hash + Registries.ITEM.getRawId(st.getItem());
                hash = 31 * hash + st.getCount();
                hash = 31 * hash + st.getDamage();
                hash = 31 * hash + st.getComponents().hashCode();
            }
        }
        for (int i = 0; i < 4; i++) {
            var st = inv.getArmorStack(i);
            if (!st.isEmpty()) {
                hash = 31 * hash + (36 + i);
                hash = 31 * hash + Registries.ITEM.getRawId(st.getItem());
                hash = 31 * hash + st.getCount();
                hash = 31 * hash + st.getDamage();
                hash = 31 * hash + st.getComponents().hashCode();
            }
        }
        var off = player.getOffHandStack();
        if (!off.isEmpty()) {
            hash = 31 * hash + 40;
            hash = 31 * hash + Registries.ITEM.getRawId(off.getItem());
            hash = 31 * hash + off.getCount();
            hash = 31 * hash + off.getDamage();
            hash = 31 * hash + off.getComponents().hashCode();
        }
        return hash;
    }

    private void refreshTacticalObservation(Entry entry, long tick) {
        if (entry.nativeBody == null || !entry.nativeBody.connected() || entry.nativeBody.entity() == null) {
            return;
        }
        var player = entry.nativeBody.entity();
        long fp = computeInventoryFingerprint(player);
        int held = player.getInventory().selectedSlot;
        if (fp != entry.cachedWeaponFingerprint || held != entry.cachedWeaponHeldSlot) {
            entry.cachedWeapons = NativeTacticalObserver.scanWeaponProfiles(player);
            entry.cachedWeaponFingerprint = fp;
            entry.cachedWeaponHeldSlot = held;
        }
        if (entry.lastTacticalObservation != null
                && entry.lastTacticalObservation.tick() == tick
                && entry.lastTacticalObservation.bodyGeneration() == entry.generation) {
            return;
        }
        try {
            var obs = NativeTacticalObserver.observe(player, entry.data.id(), entry.generation,
                    entry.foodTaskAuthorized, entry.shelterProtectionAuthorized, tick, entry.cachedWeapons);
            var candidates = dev.kindred.core.tactics.TacticalPriorityPolicy.generateCandidateOptions(obs, entry.tacticalPredictor);
            entry.lastTacticalObservation = obs;
            entry.lastTacticalDecision = dev.kindred.core.tactics.TacticalPriorityPolicy.evaluate(obs, candidates);
            entry.lastTacticalRefreshTick = tick;
        } catch (Exception ignored) {
        }
    }

    private SignalFrame createSignalFrame(Entry entry, long tick) {
        if (entry.nativeBody == null || !entry.nativeBody.connected()) {
            return new SignalFrame(tick, entry.generation, false, false, ThreatKnowledge.UNKNOWN, tick, null, false);
        }
        var player = entry.nativeBody.entity();
        boolean operational = running && ready && !entry.metadataFault && !entry.reconciliationBlocked
                && !entry.nativePending && entry.nativeState != null && entry.nativeState.phase() == StoredNativePlayer.Phase.ESTABLISHED
                && !entry.snapshot().stopLatched();
        boolean danger = isHazardousSituation(player);
        boolean recovery = player.getHealth() < 18.0f || player.getHungerManager().getFoodLevel() < 14;
        ThreatKnowledge threat = ThreatKnowledge.CLEAR;
        long threatTick = tick;
        if (entry.lastTacticalObservation != null
                && !entry.lastTacticalObservation.isStale(tick, entry.generation)) {
            boolean hasHostileThreat = entry.lastTacticalObservation.observedMobs().stream()
                    .anyMatch(m -> !m.isCow() && (m.isCreeper() || m.isZombie() || m.isSkeleton() || m.isUndead() || m.isArthropod()));
            threat = hasHostileThreat
                    ? ThreatKnowledge.PRESENT
                    : ThreatKnowledge.CLEAR;
            threatTick = entry.lastTacticalObservation.tick();
        }
        return new SignalFrame(tick, entry.generation, operational, danger, threat, threatTick,
                entry.lastAttributedAttackSequence, recovery);
    }

    void onServerTick(MinecraftServer server) {
        if (!running || !ready) return;
        long tick = server.getTicks();
        if (attentionScheduler.currentTick() == tick) return;
        if (tick % 20 == 0) {
            teamCoordinator.handleTickExpiry(tick);
        }
        attentionScheduler.beginTick(tick, System.nanoTime());
        blocksScannedThisTick = 0;
        for (var changed : matches.tick(tick)) announce(changed);
        if (tick % 20 == 7) pollRaces();
        if (tick % 20 == 13) pollHunts();
        if (tick % 20 == 17) pollBuildBattles();
        races.keySet().removeIf(id -> matches.get(id).isEmpty());
        presenter.tick(matches.list(), tick, this::playerName, this::raceProgress);

        var batch = new ArrayList<dev.kindred.core.attention.AttentionWorkScheduler.PlayerEntrySnapshot>();
        // Home after the credits: out of every world, a body gets no tick of its own to ask with (E-39d).
        for (Entry entry : entries.values()) {
            if (entry.nativeBody != null && entry.nativeBody.awayAfterCredits()) returnFromCredits(entry, entry.nativeBody);
        }
        for (Entry entry : entries.values()) {
            if (entry.nativeBody != null && entry.nativeBody.connected()) {
                refreshTacticalObservation(entry, tick);
                SignalFrame frame = createSignalFrame(entry, tick);
                entry.attentionSnapshot = entry.attention.observe(frame);
                batch.add(new dev.kindred.core.attention.AttentionWorkScheduler.PlayerEntrySnapshot(
                        entry.data.id(),
                        tick,
                        entry.generation,
                        entry.attentionSnapshot.revision(),
                        entry.attentionSnapshot.state(),
                        entry.attentionSnapshot.optionalWorkActive()
                ));
            }
        }
        attentionScheduler.synchronizeEntries(batch);

        java.util.Optional<WorkToken> nextToken;
        while ((nextToken = attentionScheduler.selectNext()).isPresent()) {
            WorkToken token = nextToken.get();
            if (!attentionScheduler.tryStart(token, System.nanoTime())) continue;
            Entry targetEntry = entries.get(token.playerId());
            if (targetEntry == null || targetEntry.nativeBody == null || !targetEntry.nativeBody.connected()) {
                attentionScheduler.abandon(token);
                continue;
            }

            try {
                executeOptionalSlice(targetEntry, token);
                attentionScheduler.complete(token);
            } catch (Exception failure) {
                attentionScheduler.abandon(token);
            }
        }
    }

    private void executeOptionalSlice(Entry entry, WorkToken token) {
        bindBodyBlockScanBudget(entry.nativeBody);
        switch (token.kind()) {
            case ROUTINE_GOALS -> {
                entry.cachedRoutineCandidates = evaluateScheduledRoutineCandidates(entry, entry.nativeBody);
            }
            case RESOURCE_DISCOVERY -> {
                budgetedResourceScan(entry, entry.nativeBody);
            }
            case EQUIPMENT_READINESS -> {
                entry.cachedRoutineCandidates = evaluateScheduledRoutineCandidates(entry, entry.nativeBody);
            }
            case BROAD_THREAT, SOCIAL_DELIBERATION, LEARNING_REVIEW -> {
            }
        }
    }

    private void budgetedResourceScan(Entry entry, NativePlayerBody body) {
        bindBodyBlockScanBudget(body);
        var player = body.entity();
        var world = player.getServerWorld();
        var center = player.getBlockPos();

        // 1. Workstation verification
        if (entry.cachedCraftingTablePos != null) {
            if (!world.isChunkLoaded(entry.cachedCraftingTablePos)
                    || !world.getBlockState(entry.cachedCraftingTablePos).isOf(Blocks.CRAFTING_TABLE)
                    || player.getEyePos().squaredDistanceTo(entry.cachedCraftingTablePos.getX() + 0.5, entry.cachedCraftingTablePos.getY() + 0.5, entry.cachedCraftingTablePos.getZ() + 0.5) > 20.25) {
                entry.cachedCraftingTablePos = null;
                attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
            }
        }
        if (entry.cachedCraftingTablePos == null) {
            var found = body.findNearbyBlock(Blocks.CRAFTING_TABLE, 4.5);
            if (found.isPresent()) {
                entry.cachedCraftingTablePos = found.get();
                attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
            }
        }

        if (entry.cachedFurnacePos != null) {
            if (!world.isChunkLoaded(entry.cachedFurnacePos)
                    || !world.getBlockState(entry.cachedFurnacePos).isOf(Blocks.FURNACE)
                    || player.getEyePos().squaredDistanceTo(entry.cachedFurnacePos.getX() + 0.5, entry.cachedFurnacePos.getY() + 0.5, entry.cachedFurnacePos.getZ() + 0.5) > 20.25) {
                entry.cachedFurnacePos = null;
                attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
            }
        }
        if (entry.cachedFurnacePos == null) {
            var found = body.findNearbyBlock(Blocks.FURNACE, 4.5);
            if (found.isPresent()) {
                entry.cachedFurnacePos = found.get();
                attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
            }
        }

        // 2. Patch resource scan
        if (entry.setup != null && entry.setup.patch() != null && entry.setup.patch().resourceId() != null
                && entry.setup.dimension().equals(world.getRegistryKey().getValue().toString())) {
            var patch = entry.setup.patch();
            boolean patchRequiresPickaxe = patch.resourceId().equals("minecraft:stone")
                    || patch.resourceId().equals("minecraft:cobblestone")
                    || patch.resourceId().contains("ore");
            boolean playerHasPickaxe = false;
            for (int s = 0; s < 36; s++) {
                if (player.getInventory().getStack(s).isIn(ItemTags.PICKAXES)) {
                    playerHasPickaxe = true;
                    break;
                }
            }
            if (!patchRequiresPickaxe || playerHasPickaxe) {
            int reach = 4;
            int checks = 0;
            boolean foundNearby = false;

            if (entry.cachedMiningTarget != null) {
                if (canScanBlock()) {
                    recordBlockScan();
                    var pos = entry.cachedMiningTarget;
                    if (world.isChunkLoaded(pos) && Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString().equals(patch.resourceId())
                            && body.miningEligible(pos) && player.getEyePos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 16.0) {
                        foundNearby = true;
                    } else {
                        entry.cachedMiningTarget = null;
                        attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
                    }
                }
            }

            if (!foundNearby) {
                for (int y = -1; y <= 2 && checks < 32; y++) {
                    for (int x = -reach; x <= reach && checks < 32; x++) {
                        for (int z = -reach; z <= reach && checks < 32; z++) {
                            BlockPos pos = center.add(x, y, z);
                            if (!patch.contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) continue;
                            if (entry.setup.area() != null && !entry.setup.area().contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) continue;
                            if (!world.isChunkLoaded(pos)) continue;
                            if (!canScanBlock()) break;
                            checks++;
                            recordBlockScan();
                            var state = world.getBlockState(pos);
                            boolean isLog = state.isIn(BlockTags.LOGS);
                            boolean isStone = state.isIn(BlockTags.PICKAXE_MINEABLE) || state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE);
                            if ((!isLog && !isStone) || state.hasBlockEntity()) continue;
                            if (!Registries.BLOCK.getId(state.getBlock()).toString().equals(patch.resourceId())) continue;
                            if (!body.miningEligible(pos)) continue;
                            entry.cachedMiningTarget = pos;
                            entry.cachedMiningIsStone = isStone;
                            foundNearby = true;
                            attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
                            break;
                        }
                        if (foundNearby) break;
                    }
                    if (foundNearby) break;
                }
            }

            if (!foundNearby && canScanBlock()) {
                int searchRadius = 12;
                int distantChecks = 0;
                for (int y = -3; y <= 3 && distantChecks < 32 && canScanBlock(); y++) {
                    for (int x = -searchRadius; x <= searchRadius && distantChecks < 32 && canScanBlock(); x++) {
                        for (int z = -searchRadius; z <= searchRadius && distantChecks < 32 && canScanBlock(); z++) {
                            distantChecks++;
                            recordBlockScan();
                            BlockPos pos = center.add(x, y, z);
                            if (!patch.contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) continue;
                            if (entry.setup.area() != null && !entry.setup.area().contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) continue;
                            if (!world.isChunkLoaded(pos)) continue;
                            var state = world.getBlockState(pos);
                            if (!Registries.BLOCK.getId(state.getBlock()).toString().equals(patch.resourceId())) continue;
                            entry.cachedApproachTarget = pos;
                            attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
                            break;
                        }
                        if (entry.cachedApproachTarget != null) break;
                    }
                    if (entry.cachedApproachTarget != null) break;
                }
            }
            }
        }
    }

    /** Bounded patch-only scan that does not consume the per-tick block budget (small gametest patches). */
    private void primePatchResourceCaches(Entry entry, NativePlayerBody body) {
        if (entry.setup == null || entry.setup.patch() == null || entry.cachedMiningTarget != null || entry.cachedApproachTarget != null) {
            return;
        }
        var player = body.entity();
        var world = player.getServerWorld();
        if (!entry.setup.dimension().equals(world.getRegistryKey().getValue().toString())) {
            return;
        }
        var patch = entry.setup.patch();
        boolean playerHasPickaxe = false;
        for (int s = 0; s < 36; s++) {
            if (player.getInventory().getStack(s).isIn(ItemTags.PICKAXES)) {
                playerHasPickaxe = true;
                break;
            }
        }
        boolean patchRequiresPickaxe = patch.resourceId().equals("minecraft:stone")
                || patch.resourceId().equals("minecraft:cobblestone")
                || patch.resourceId().contains("ore");
        if (patchRequiresPickaxe && !playerHasPickaxe) {
            return;
        }
        BlockPos approachFallback = null;
        for (int y = patch.min().y(); y <= patch.max().y(); y++) {
            for (int x = patch.min().x(); x <= patch.max().x(); x++) {
                for (int z = patch.min().z(); z <= patch.max().z(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.isChunkLoaded(pos)) continue;
                    var state = world.getBlockState(pos);
                    if (!Registries.BLOCK.getId(state.getBlock()).toString().equals(patch.resourceId())) continue;
                    if (body.miningEligible(pos)
                            && player.getEyePos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 16.0) {
                        entry.cachedMiningTarget = pos;
                        entry.cachedMiningIsStone = state.isIn(BlockTags.PICKAXE_MINEABLE) || state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE);
                        return;
                    }
                    if (approachFallback == null) {
                        approachFallback = pos;
                    }
                }
            }
        }
        if (approachFallback != null) {
            entry.cachedApproachTarget = approachFallback;
        }
    }

    private static boolean milestoneSeeking(Entry entry, MilestoneId id) {
        if (entry.lastProgressionState == null) return false;
        MilestoneSnapshot snap = entry.lastProgressionState.snapshots().get(id);
        return snap != null && snap.status() != MilestoneStatus.ACHIEVED && !snap.historicallyAchieved();
    }


    private boolean validateCandidateStillExecutable(Entry entry, NativePlayerBody body, NativeTaskCandidate tc) {
        var player = body.entity();
        var world = player.getServerWorld();
        return switch (tc.skill()) {
            case "native_eat", "native_swap" -> {
                String sourceStr = tc.arguments().get("slot");
                if (sourceStr == null) sourceStr = tc.arguments().get("source");
                if (sourceStr != null) {
                    try {
                        int slot = Integer.parseInt(sourceStr);
                        yield slot >= 0 && slot < 36 && !player.getInventory().getStack(slot).isEmpty();
                    } catch (NumberFormatException ignored) {
                        yield false;
                    }
                }
                yield true;
            }
            case "native_mine" -> {
                try {
                    int x = Integer.parseInt(tc.arguments().get("x"));
                    int y = Integer.parseInt(tc.arguments().get("y"));
                    int z = Integer.parseInt(tc.arguments().get("z"));
                    BlockPos pos = new BlockPos(x, y, z);
                    body.faceBlock(pos);
                    yield world.isChunkLoaded(pos) && !world.getBlockState(pos).isAir() && body.miningEligible(pos);
                } catch (Exception ignored) {
                    yield false;
                }
            }
            case "native_craft" -> {
                String recipe = tc.arguments().get("recipe");
                if (recipe == null) yield false;
                primeWorkstationCaches(entry, body);
                if (body.planCraft(recipe, entry.cachedCraftingTablePos).isPresent()) yield true;
                yield body.planCraft2x2(recipe).isPresent();
            }
            case "native_place" -> {
                try {
                    int slot = Integer.parseInt(tc.arguments().get("slot"));
                    yield slot >= 0 && slot < 36 && !player.getInventory().getStack(slot).isEmpty();
                } catch (Exception ignored) {
                    yield false;
                }
            }
            case "native_attack" -> {
                String targetStr = tc.arguments().get("target");
                if (targetStr != null) {
                    try {
                        UUID targetId = UUID.fromString(targetStr);
                        var target = world.getEntity(targetId);
                        yield target instanceof net.minecraft.entity.LivingEntity living && living.isAlive()
                                && player.canInteractWithEntity(living, 0) && player.canSee(living);
                    } catch (Exception ignored) {
                        yield false;
                    }
                }
                yield false;
            }
            case "native_block" -> {
                yield player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD)
                        || player.getMainHandStack().isOf(net.minecraft.item.Items.SHIELD);
            }
            default -> true;
        };
    }

    /**
     * Its survival needs this tick. With {@code admitted} false (the server's edits off, or the player idle) only its
     * reflexes, none of which changes a block: eating, getting out of a hazard, deep water or a creeper's blast, and
     * defending itself from a mob that is on it. A fall is broken with water only where it may edit ({@code mayEdit});
     * a barricade, the Nether's blazes and an expedition's return are its work, and need {@code admitted}.
     */
    private List<NativeTaskCandidate> evaluateEssentialSurvivalCandidates(Entry entry, NativePlayerBody body, boolean admitted, boolean mayEdit) {
        var candidates = new java.util.ArrayList<NativeTaskCandidate>();
        var player = body.entity();
        var world = player.getServerWorld();
        // 00. A long fall with a water bucket in the hotbar: broken with water before anything else (E-07d).
        int clutchSlot = clutchSlot(player);
        if (clutchSlot >= 0 && mayEdit) {
            UUID taskId = UUID.nameUUIDFromBytes(("clutch:" + entry.data.id() + ":" + server.getTicks()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 100.0,
                    dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
            candidates.add(new NativeTaskCandidate(candidate, "native_clutch", Map.of("slot", String.valueOf(clutchSlot))));
            return candidates;
        }
        // 0. Hit hard by a player (E-41g): defended against, or got away from, before anything but a fall.
        var aggression = aggressionCandidates(entry, body);
        if (aggression != null) return new java.util.ArrayList<>(aggression);

        // 0. Environmental hazard detection and evasive retreat (URGENT)
        BlockPos feetPos = player.getBlockPos();
        if (isHazardousSituation(player)) {
            String[] dirs = new String[]{"north", "south", "east", "west"};
            BlockPos[] adj = new BlockPos[]{feetPos.north(), feetPos.south(), feetPos.east(), feetPos.west()};
            for (int d = 0; d < 4; d++) {
                BlockPos targetFeet = adj[d];
                BlockPos targetGround = targetFeet.down();
                if (!world.isChunkLoaded(targetFeet) || !world.isInBuildLimit(targetFeet.up()) || !world.getWorldBorder().contains(targetFeet)) continue;
                var targetSupport = world.getBlockState(targetGround);
                if (!targetSupport.isSideSolidFullSquare(world, targetGround, Direction.UP)
                        || targetSupport.isOf(Blocks.MAGMA_BLOCK) || targetSupport.isOf(Blocks.CACTUS)
                        || !world.getFluidState(targetFeet).isEmpty()) continue;
                var targetFeetState = world.getBlockState(targetFeet);
                if (targetFeetState.isOf(Blocks.FIRE) || targetFeetState.isOf(Blocks.SOUL_FIRE)
                        || targetFeetState.isOf(Blocks.SWEET_BERRY_BUSH) || targetFeetState.isOf(Blocks.POWDER_SNOW)
                        || targetFeetState.isOf(Blocks.LAVA)) continue;
                var box = player.getBoundingBox().offset(net.minecraft.util.math.Vec3d.ofBottomCenter(targetFeet).subtract(player.getPos()));
                if (!world.isSpaceEmpty(player, box)) continue;

                // Found a safe retreat direction
                UUID taskId = UUID.nameUUIDFromBytes(("hazard_retreat:" + entry.data.id() + ":" + feetPos.asLong() + ":" + dirs[d]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 99.0,
                        dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                candidates.add(new NativeTaskCandidate(candidate, "native_retreat", Map.of("direction", dirs[d], "blocks", "1")));
                break;
            }
        }

        // 0a. Deep water: a player that stops swimming sinks, so head for the nearest dry ground while breath lasts.
        // A running goal does this itself, choosing a way out it can reach (straight up for air when short of breath).
        boolean shortOfBreath = player.getAir() < player.getMaxAir() / 2;
        boolean goalSurfaces = entry.goal != dev.kindred.core.survival.SurvivalGoal.NONE && entry.campaign != null;
        if (candidates.isEmpty() && !goalSurfaces && inDeepWater(player) && (server.getTicks() >= entry.waterEscapeRetryTick || shortOfBreath)) {
            // A shore it could not reach is passed over for a minute, and dry ground looked for along the ways it can
            // swim: the nearest by distance, behind a wall, was made for again at once while short of breath, nineteen
            // times in ten minutes (live run 69).
            long now = server.getTicks();
            entry.unreachedShores.values().removeIf(until -> until <= now);
            var navigator = new NativeNavigator(world);
            var shore = navigator.nearestDryStand(feetPos, 16, 4).filter(p -> !entry.unreachedShores.containsKey(p.asLong()));
            if (shore.isEmpty() && !entry.unreachedShores.isEmpty()) {
                shore = navigator.nearestReachableDryStand(feetPos, 1500, 16).filter(p -> !entry.unreachedShores.containsKey(p.asLong()));
            }
            if (shore.isPresent()) {
                BlockPos dry = shore.get();
                entry.waterEscapeShore = dry;
                UUID taskId = UUID.nameUUIDFromBytes(("water_escape:" + entry.data.id() + ":" + dry.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 97.0,
                        dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                entry.waterEscapeTaskId = taskId;
                candidates.add(new NativeTaskCandidate(candidate, "native_goto", Map.of("x", String.valueOf(dry.getX()),
                        "y", String.valueOf(dry.getY()), "z", String.valueOf(dry.getZ()), "range", "0.8", "partial", "true")));
            }
        }

        // 0b. Creeper explosion emergency retreat (URGENT 98.0)
        if (entry.lastTacticalObservation != null) {
            for (var mob : entry.lastTacticalObservation.observedMobs()) {
                if (mob.isCreeper() && (mob.isIgnited() || (mob.fuseTicksRemaining() >= 0 && mob.fuseTicksRemaining() <= 25))) {
                    var creeperEntity = world.getEntity(mob.entityId());
                    Vec3d threatPos = creeperEntity != null ? creeperEntity.getPos() : null;
                    String escapeDir = findBestEscapeDirection(player, threatPos);
                    if (escapeDir != null) {
                        UUID taskId = UUID.nameUUIDFromBytes(("creeper_retreat:" + entry.data.id() + ":" + mob.entityId() + ":" + escapeDir).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 98.0,
                                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_retreat", Map.of("direction", escapeDir, "blocks", "1")));
                        break;
                    }
                }
            }
        }

        // 1. Hunger and health recovery eating (URGENT)
        float currentHealth = player.getHealth();
        float maxHealth = player.getMaxHealth();
        int foodLevel = player.getHungerManager().getFoodLevel();
        boolean needsHealth = currentHealth < maxHealth;
        boolean canEatVanilla = player.canConsume(false);
        // As a person eats: only food that fills what is missing, unless hurt or too hungry to sprint (SurvivalPolicy).
        java.util.function.Predicate<ItemStack> worth = stack -> {
            var food = stack.get(DataComponentTypes.FOOD);
            return food != null && (dev.kindred.core.SurvivalPolicy.worthEating(foodLevel, food.nutrition(), needsHealth)
                    || needsHealth && food.canAlwaysEat());
        };
        if (canEatVanilla || needsHealth) {
            int foodSlot = -1;
            double bestFoodScore = Double.NEGATIVE_INFINITY;
            for (int slot = 0; slot < 9; slot++) {
                var stack = player.getInventory().getStack(slot);
                if (isEligibleFood(stack, canEatVanilla, needsHealth) && worth.test(stack)) {
                    double score = foodQualityScore(stack, needsHealth);
                    if (score > bestFoodScore) {
                        bestFoodScore = score;
                        foodSlot = slot;
                    }
                }
            }
            if (foodSlot == -1) {
                int invFoodSlot = -1;
                double bestInvScore = Double.NEGATIVE_INFINITY;
                for (int slot = 9; slot < 36; slot++) {
                    var stack = player.getInventory().getStack(slot);
                    if (isEligibleFood(stack, canEatVanilla, needsHealth) && worth.test(stack)) {
                        double score = foodQualityScore(stack, needsHealth);
                        if (score > bestInvScore) {
                            bestInvScore = score;
                            invFoodSlot = slot;
                        }
                    }
                }
                if (invFoodSlot != -1) {
                    int slot = invFoodSlot;
                    var stack = player.getInventory().getStack(slot);
                    if (isEligibleFood(stack, canEatVanilla, needsHealth) && worth.test(stack)) {
                        int targetH = -1;
                        for (int h = 0; h < 9; h++) {
                            if (player.getInventory().getStack(h).isEmpty()) {
                                targetH = h;
                                break;
                            }
                        }
                        if (targetH == -1) {
                            for (int h = 8; h >= 0; h--) {
                                Item item = player.getInventory().getStack(h).getItem();
                                if (!(item instanceof net.minecraft.item.MiningToolItem)
                                        && !(item instanceof net.minecraft.item.SwordItem)
                                        && !(item instanceof net.minecraft.item.AxeItem)
                                        && !(item instanceof net.minecraft.item.BowItem)
                                        && !(item instanceof net.minecraft.item.CrossbowItem)
                                        && !(item instanceof net.minecraft.item.TridentItem)) {
                                    targetH = h;
                                    break;
                                }
                            }
                        }
                        if (targetH == -1) {
                            targetH = 8;
                        }
                        double need = dev.kindred.core.SurvivalPolicy.eatingNeed(currentHealth, maxHealth, foodLevel);
                        UUID taskId = UUID.nameUUIDFromBytes(("swap_food:" + entry.data.id() + ":" + slot + ":" + targetH).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, need,
                                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                                "source", String.valueOf(slot),
                                "target", String.valueOf(targetH))));
                    }
                }
            } else {
                double need = dev.kindred.core.SurvivalPolicy.eatingNeed(currentHealth, maxHealth, foodLevel);
                UUID taskId = UUID.nameUUIDFromBytes(("eat:" + entry.data.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, need,
                                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                candidates.add(new NativeTaskCandidate(candidate, "native_eat", Map.of("slot", String.valueOf(foodSlot))));
            }
        }

        // 2. A visible Blaze is a bounded, local opportunity for the Nether resource
        // milestone.  It still goes through the ordinary observed-mob, paid
        // native_attack path: this creates no item and does not assume a fortress or
        // a drop exists.  Vanilla loot and normal collision pickup remain the only
        // way a Blaze Rod can enter the inventory.
        if (admitted && entry.lastProgressionObservation != null && entry.lastProgressionObservation.isInNether()
                && milestoneSeeking(entry, MilestoneId.GATHER_BLAZE_RODS)
                && entry.lastTacticalObservation != null) {
            int weaponSlot = -1;
            for (int slot = 0; slot < 9; slot++) {
                if (player.getInventory().getStack(slot).isIn(ItemTags.SWORDS)
                        || player.getInventory().getStack(slot).isIn(ItemTags.AXES)) {
                    weaponSlot = slot;
                    break;
                }
            }
            if (weaponSlot >= 0) {
                for (var mob : entry.lastTacticalObservation.observedMobs()) {
                    if (!"minecraft:blaze".equals(mob.entityType()) || mob.distance() > 4.0) continue;
                    var entity = world.getEntity(mob.entityId());
                    if (!(entity instanceof net.minecraft.entity.LivingEntity living) || !living.isAlive()
                            || !player.canInteractWithEntity(living, 0) || !player.canSee(living)) continue;
                    UUID taskId = UUID.nameUUIDFromBytes(("blaze_rod_attack:" + entry.data.id() + ":" + mob.entityId()
                            + ":" + weaponSlot).getBytes(StandardCharsets.UTF_8));
                    var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId,
                            dev.kindred.core.TaskPreferences.Priority.URGENT, 91.0,
                            dev.kindred.core.TaskPreferences.Affinities.none(),
                            new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                    candidates.add(new NativeTaskCandidate(candidate, "native_attack", Map.of(
                            "target", mob.entityId().toString(), "slot", String.valueOf(weaponSlot))));
                    break;
                }
            }
        }

        // 3. Tactical combat choices from TacticalPriorityPolicy. While a goal runs, its campaign is the one controller
        // of a fight (strike, back off, cover, hole up); only a weapon swap is still offered from here.
        boolean goalOwnsCombat = entry.goal != dev.kindred.core.survival.SurvivalGoal.NONE && entry.campaign != null;
        if (entry.lastTacticalObservation != null && !entry.lastTacticalObservation.observedMobs().isEmpty()
                && entry.lastTacticalDecision != null && entry.lastTacticalDecision.hasViableOption()) {
            var bestOpt = entry.lastTacticalDecision.bestOption();
            if (bestOpt.isPresent()) {
                var option = bestOpt.get();
                var seq = option.sequence();
                UUID targetId = seq.targetEntityId();
                var weapon = seq.weapon();

                if (seq.containsStep(dev.kindred.core.tactics.ActionStep.PREPARE_WEAPON) && weapon != null && weapon.slot() >= 9) {
                    int targetHotbar = 0;
                    for (int h = 0; h < 9; h++) {
                        if (player.getInventory().getStack(h).isEmpty()) {
                            targetHotbar = h;
                            break;
                        }
                    }
                    UUID taskId = UUID.nameUUIDFromBytes(("swap_weapon:" + entry.data.id() + ":" + weapon.slot() + ":" + targetHotbar).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 95.0,
                            dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                    candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                            "source", String.valueOf(weapon.slot()),
                            "target", String.valueOf(targetHotbar))));
                } else if (goalOwnsCombat) {
                    // The goal's own fight, back-off or cover is under way or about to be chosen.
                } else if (seq.containsStep(dev.kindred.core.tactics.ActionStep.NORMAL_STRIKE)
                        || seq.containsStep(dev.kindred.core.tactics.ActionStep.CRITICAL_STRIKE)) {
                    // Only a strike that can land now: an out-of-reach strike fails dispatch and, being urgent, would win
                    // again every tick and starve every other task, including closing the distance.
                    // A hoglin is never struck first, nor one that is after it from a distance: left alone it does no
                    // harm, and its toss is dangerous (E-07e). Only one that is on the player is fought.
                    boolean hoglinLeftAlone = targetId != null && world.getEntity(targetId) instanceof net.minecraft.entity.mob.MobEntity mob
                            && (mob instanceof net.minecraft.entity.mob.HoglinEntity || mob instanceof net.minecraft.entity.mob.ZoglinEntity)
                            && (mob.getTarget() != player || player.squaredDistanceTo(mob) > 3.0 * 3.0);
                    // Its reflexes alone strike only what is after it: defending itself, not hunting.
                    boolean onIt = world.getEntity(targetId) instanceof net.minecraft.entity.mob.MobEntity attacker && attacker.getTarget() == player;
                    if (targetId != null && !hoglinLeftAlone && (admitted || onIt) && world.getEntity(targetId) instanceof net.minecraft.entity.LivingEntity living
                            && player.canInteractWithEntity(living, 0) && player.canSee(living)) {
                        double need = option.outcome().primaryObjective() == dev.kindred.core.tactics.TacticalObjective.RESOLVE_IMMEDIATE_SERIOUS_HARM
                                ? 96.0 : 90.0;
                        int weaponSlot = weapon != null && weapon.slot() >= 0 && weapon.slot() < 9 ? weapon.slot() : player.getInventory().selectedSlot;
                        UUID taskId = UUID.nameUUIDFromBytes(("tactical_attack:" + entry.data.id() + ":" + targetId + ":" + weaponSlot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, need,
                                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_attack", Map.of(
                                "target", targetId.toString(),
                                "slot", String.valueOf(weaponSlot))));
                    }
                } else if (seq.containsStep(dev.kindred.core.tactics.ActionStep.RETREAT_TO_SAFE_CORRIDOR)) {
                    String escapeDir = findBestEscapeDirection(player, null);
                    // One step back, then reassess: retreating again at once from a mob that is not pressing in
                    // walks the player away forever. Only fresh damage justifies another step straight away.
                    boolean rested = server.getTicks() - entry.lastTacticalRetreatTick >= 100 || player.hurtTime > 0;
                    if (escapeDir != null && rested) {
                        UUID taskId = UUID.nameUUIDFromBytes(("tactical_retreat:" + entry.data.id() + ":" + escapeDir).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 94.0,
                                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_retreat", Map.of("direction", escapeDir, "blocks", "1")));
                        entry.tacticalRetreatTaskId = taskId;
                    }
                } else if (seq.containsStep(dev.kindred.core.tactics.ActionStep.STAY_AND_GUARD)) {
                    boolean hasShield = player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD)
                            || player.getMainHandStack().isOf(net.minecraft.item.Items.SHIELD);
                    if (hasShield && entry.lastTacticalObservation != null && !entry.lastTacticalObservation.observedMobs().isEmpty()) {
                        UUID taskId = UUID.nameUUIDFromBytes(("tactical_block:" + entry.data.id() + ":" + server.getTicks()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 92.0,
                                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_block", Map.of("ticks", "20")));
                    }
                }
            }
        }

        // 4. Emergency night shelter / barricade placement (URGENT 95.0)
        // One barricade per threat: walling in again after every step back would spend the whole stock.
        boolean barricadeRested = server.getTicks() - entry.lastBarricadeTick >= 200 || player.hurtTime > 0;
        if (admitted && world.isNight() && barricadeRested && entry.setup != null && entry.setup.area() != null
                && entry.lastTacticalObservation != null && !entry.lastTacticalObservation.observedMobs().isEmpty()) {
            int blockSlot = -1;
            for (int s = 0; s < 9; s++) {
                var stack = player.getInventory().getStack(s);
                if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.item.BlockItem bi
                        && bi.getBlock().getDefaultState().isSolidBlock(world, feetPos)) {
                    blockSlot = s;
                    break;
                }
            }
            if (blockSlot >= 0) {
                var mob = entry.lastTacticalObservation.observedMobs().getFirst();
                var mobEntity = world.getEntity(mob.entityId());
                if (mobEntity != null) {
                    Vec3d toMob = mobEntity.getPos().subtract(player.getPos());
                    Direction primaryDir = Direction.getFacing(toMob.x, 0, toMob.z);
                    BlockPos barricadePos = feetPos.offset(primaryDir);
                    BlockPos barricadeGround = barricadePos.down();
                    var blockPoint = new WorkSetup.BlockPoint(barricadePos.getX(), barricadePos.getY(), barricadePos.getZ());
                    if (entry.setup.area().contains(blockPoint)
                            && world.isChunkLoaded(barricadePos) && world.isInBuildLimit(barricadePos)
                            && (world.getBlockState(barricadePos).isAir() || world.getBlockState(barricadePos).isReplaceable())
                            && world.getBlockState(barricadeGround).isSideSolidFullSquare(world, barricadeGround, Direction.UP)) {
                        String blockId = net.minecraft.registry.Registries.BLOCK.getId(((net.minecraft.item.BlockItem) player.getInventory().getStack(blockSlot).getItem()).getBlock()).toString();
                        var editDecision = EditPolicy.evaluate(entry.setup, EditPolicy.Kind.PLACE,
                                world.getRegistryKey().getValue().toString(), blockPoint, blockId, edits);
                        if (editDecision.allowed()) {
                            UUID taskId = UUID.nameUUIDFromBytes(("night_barricade:" + entry.data.id() + ":" + barricadePos.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            entry.barricadeTaskId = taskId;
                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 95.0,
                                    dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                            candidates.add(new NativeTaskCandidate(candidate, "native_place", Map.of(
                                    "x", String.valueOf(barricadePos.getX()),
                                    "y", String.valueOf(barricadePos.getY()),
                                    "z", String.valueOf(barricadePos.getZ()),
                                    "side", "up",
                                    "slot", String.valueOf(blockSlot))));
                        }
                    }
                }
            }
        }

        // 4. Urgent nightfall, thunderstorm, or low-health expedition return to shelter (URGENT 92.0)
        if (admitted) ensureExpeditionState(entry, body);
        if (admitted && entry.expeditionState != null) {
            ExpeditionObservation expObs = observeExpedition(entry, body);
            if (entry.expeditionState.status() == ExpeditionStatus.EXPLORING_OUTWARD) {
                ReturnReason trigger = ExpeditionPlanner.evaluateReturnTriggers(entry.expeditionState, expObs);
                if (trigger == ReturnReason.NIGHT || trigger == ReturnReason.THUNDERSTORM || trigger == ReturnReason.LOW_HEALTH) {
                    entry.expeditionState.triggerReturn(trigger);
                }
            }
            if (entry.expeditionState.status() == ExpeditionStatus.RETURNING) {
                ReturnReason reason = entry.expeditionState.returnReason();
                if (reason == ReturnReason.NIGHT || reason == ReturnReason.THUNDERSTORM || reason == ReturnReason.LOW_HEALTH) {
                    ExpeditionPlan plan = ExpeditionPlanner.plan(entry.expeditionState, expObs);
                    if ("native_traverse".equals(plan.action()) && plan.blocks() > 0) {
                        UUID taskId = UUID.nameUUIDFromBytes(("urgent_expedition_return:" + entry.data.id() + ":" + plan.direction() + ":" + reason).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 92.0,
                                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_traverse", Map.of(
                                "direction", plan.direction(),
                                "blocks", String.valueOf(plan.blocks())
                        )));
                    }
                }
            }
        }

        return candidates;
    }

    private InventorySummary observeInventory(ServerPlayerEntity player) {
        int totalItems = 0;
        int occupied = 0;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                occupied++;
                totalItems += stack.getCount();
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        ItemStack offhand = player.getOffHandStack();
        if (!offhand.isEmpty()) {
            String id = Registries.ITEM.getId(offhand.getItem()).toString();
            counts.merge(id, offhand.getCount(), Integer::sum);
        }
        for (ItemStack armor : player.getArmorItems()) {
            if (!armor.isEmpty()) {
                String id = Registries.ITEM.getId(armor.getItem()).toString();
                counts.merge(id, armor.getCount(), Integer::sum);
            }
        }
        return new InventorySummary(0, 36, occupied, totalItems, counts);
    }

    private ProgressionObservation observeProgression(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();

        primeWorkstationCaches(entry, body);

        BlockPos tablePos = entry.cachedCraftingTablePos;
        boolean hasPlacedTable = false;
        if (tablePos != null) {
            if (world.isChunkLoaded(tablePos) && world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)
                    && player.getEyePos().squaredDistanceTo(tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5) <= 20.25) {
                hasPlacedTable = true;
            } else {
                entry.cachedCraftingTablePos = null;
                tablePos = null;
            }
        }

        BlockPos furnacePos = entry.cachedFurnacePos;
        boolean hasPlacedFurnace = false;
        if (furnacePos != null) {
            if (world.isChunkLoaded(furnacePos) && world.getBlockState(furnacePos).isOf(Blocks.FURNACE)
                    && player.getEyePos().squaredDistanceTo(furnacePos.getX() + 0.5, furnacePos.getY() + 0.5, furnacePos.getZ() + 0.5) <= 20.25) {
                hasPlacedFurnace = true;
            } else {
                entry.cachedFurnacePos = null;
                furnacePos = null;
            }
        }

        boolean hasSmeltOutputWaiting = false;
        boolean canSmelt = false;
        if (hasPlacedFurnace && furnacePos != null) {
            var fEntity = world.getBlockEntity(furnacePos);
            if (fEntity instanceof AbstractFurnaceBlockEntity furnace) {
                if (!furnace.getStack(2).isEmpty() && body.canReceiveStack(furnace.getStack(2))) {
                    hasSmeltOutputWaiting = true;
                } else {
                    var smeltPlan = body.planSmelt(furnacePos, "load", null, null, null);
                    if (smeltPlan.isPresent()) {
                        canSmelt = true;
                    }
                }
            }
        }

        boolean hasShelter = !world.isSkyVisible(player.getBlockPos()) || entry.shelterProtectionAuthorized;
        boolean hasAssignedStonePatch = entry.setup != null && entry.setup.patch() != null
                && entry.setup.patch().resourceId() != null
                && (entry.setup.patch().resourceId().contains("stone") || entry.setup.patch().resourceId().contains("cobblestone"));
        boolean hasAssignedLogPatch = entry.setup != null && entry.setup.patch() != null
                && entry.setup.patch().resourceId() != null
                && entry.setup.patch().resourceId().contains("log");
        boolean hasAssignedNetherWartPatch = entry.setup != null && entry.setup.patch() != null
                && "minecraft:nether_wart".equals(entry.setup.patch().resourceId())
                && world.getRegistryKey().getValue().toString().equals("minecraft:the_nether");
        var farmPosOpt = body.findNearbyFarmland(4.5);
        boolean hasNearbyFarmland = farmPosOpt.isPresent();
        boolean hasNearbyBed = body.findNearbyBed(4.5).isPresent();
        boolean hasCultivatedFarm = false;
        if (farmPosOpt.isPresent()) {
            BlockPos farmPos = farmPosOpt.get();
            if (canScanBlock()) {
                recordBlockScan();
                hasCultivatedFarm = world.getBlockState(farmPos.up()).getBlock() instanceof CropBlock;
            }
        }

        InventorySummary invSummary = observeInventory(player);

        return new ProgressionObservation(
                entry.data.id(),
                server.getTicks(),
                invSummary,
                hasPlacedTable,
                hasPlacedFurnace,
                hasSmeltOutputWaiting,
                canSmelt,
                world.isNight(),
                hasShelter,
                hasAssignedStonePatch,
                hasAssignedLogPatch,
                hasNearbyFarmland,
                hasNearbyBed,
                hasCultivatedFarm,
                world.getRegistryKey().getValue().toString(),
                hasAssignedNetherWartPatch
        );
    }

    private void ensureExpeditionState(Entry entry, NativePlayerBody body) {
        if (entry.expeditionState != null) return;
        var player = body.entity();
        var world = player.getServerWorld();
        if (entry.setup.home() != null) {
            var hp = entry.setup.home().position();
            Waypoint anchor = Waypoint.of("home_shelter", hp.x(), hp.y(), hp.z(), entry.setup.dimension(), WaypointKind.SHELTER, world.getTime());
            entry.expeditionState = new ExpeditionState(anchor, ExpeditionPlanner.DEFAULT_MAX_EXPEDITION_RADIUS);
        } else if (player.getSpawnPointPosition() != null) {
            BlockPos sp = player.getSpawnPointPosition();
            Waypoint anchor = Waypoint.of("spawn_bed", sp.getX(), sp.getY(), sp.getZ(), player.getSpawnPointDimension().getValue().toString(), WaypointKind.HOME_BED, world.getTime());
            entry.expeditionState = new ExpeditionState(anchor, ExpeditionPlanner.DEFAULT_MAX_EXPEDITION_RADIUS);
        } else if (entry.setup.area() != null) {
            int cx = (entry.setup.area().minX() + entry.setup.area().maxX()) / 2;
            int cz = (entry.setup.area().minZ() + entry.setup.area().maxZ()) / 2;
            Waypoint anchor = Waypoint.of("work_area", cx, player.getBlockY(), cz, entry.setup.dimension(), WaypointKind.SHELTER, world.getTime());
            entry.expeditionState = new ExpeditionState(anchor, ExpeditionPlanner.DEFAULT_MAX_EXPEDITION_RADIUS);
        }
    }

    private ExpeditionObservation observeExpedition(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        int x = player.getBlockX();
        int y = player.getBlockY();
        int z = player.getBlockZ();
        String dim = world.getRegistryKey().getValue().toString();
        long timeOfDay = world.getTimeOfDay();
        boolean thundering = world.isThundering();
        boolean raining = world.isRaining();
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        int foodLevel = player.getHungerManager().getFoodLevel();

        int foodCount = 0;
        int occupied = 0;
        boolean hasWeapon = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                occupied++;
                if (stack.contains(DataComponentTypes.FOOD)) {
                    foodCount += stack.getCount();
                }
                if (stack.getItem() instanceof net.minecraft.item.SwordItem || stack.getItem() instanceof net.minecraft.item.AxeItem) {
                    hasWeapon = true;
                }
            }
        }
        return new ExpeditionObservation(x, y, z, dim, timeOfDay, thundering, raining, health, maxHealth, foodCount, foodLevel, occupied, 36, hasWeapon);
    }

    /**
     * In the Nether, one piece of gold armour worn, so piglins leave the player alone (E-07e): put on, or swapped for
     * the piece worn in its slot (a golden helmet is as good as an iron one). Out of the Nether, the better piece it
     * carries for that slot goes back on. The piece is moved to the hotbar first for the swap, as a player does it.
     */
    private static java.util.Optional<NativeTaskCandidate> goldCandidate(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var inventory = player.getInventory();
        boolean nether = player.getWorld().getRegistryKey() == net.minecraft.world.World.NETHER;
        net.minecraft.entity.EquipmentSlot wornGold = null;
        for (var slot : List.of(net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET)) {
            if (FollowUps.golden(player.getEquippedStack(slot))) wornGold = slot;
        }
        int pick = -1;
        if (nether && wornGold == null) {
            for (int s = 0; s < 36 && pick < 0; s++) if (FollowUps.golden(inventory.getStack(s))) pick = s;
        } else if (!nether && wornGold != null) {
            // Better protection, or as good and longer lasting: a golden helmet guards as well as iron's but wears out
            // in half the hits, which tells in the End.
            var wornStack = player.getEquippedStack(wornGold);
            int worn = ((net.minecraft.item.ArmorItem) wornStack.getItem()).getProtection();
            for (int s = 0; s < 36 && pick < 0; s++) {
                var stack = inventory.getStack(s);
                if (stack.getItem() instanceof net.minecraft.item.ArmorItem armour && player.getPreferredEquipmentSlot(stack) == wornGold
                        && !FollowUps.golden(stack) && (armour.getProtection() > worn || armour.getProtection() == worn
                        && stack.getMaxDamage() - stack.getDamage() > wornStack.getMaxDamage() - wornStack.getDamage())) pick = s;
            }
        }
        if (pick < 0) return java.util.Optional.empty();
        String why = nether ? "gold_on:" : "gold_off:";
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(UUID.nameUUIDFromBytes((why + entry.data.id() + ":" + pick)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)), dev.kindred.core.TaskPreferences.Priority.PROJECT, 76.0,
                new dev.kindred.core.TaskPreferences.Affinities(0.5, 0.0, 0.0, 0.0), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        if (body.equipmentDestination(pick).isPresent()) {
            return java.util.Optional.of(new NativeTaskCandidate(candidate, "native_equip", Map.of("slot", String.valueOf(pick))));
        }
        if (pick >= 9) {
            int hotbar = 8;
            for (int h = 0; h < 9; h++) if (inventory.getStack(h).isEmpty()) { hotbar = h; break; }
            return java.util.Optional.of(new NativeTaskCandidate(candidate, "native_swap", Map.of("source", String.valueOf(pick), "target", String.valueOf(hotbar))));
        }
        if (body.wearDestination(pick).isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(new NativeTaskCandidate(candidate, "native_equip", Map.of("slot", String.valueOf(pick), "swap", "true")));
    }

    private static java.util.Optional<NativeTaskCandidate> equipCandidate(Entry entry, NativePlayerBody body,
                                                                         dev.kindred.core.TaskPreferences.Priority priority, double need) {
        for (int slot = 0; slot < 36; slot++) {
            if (body.equipmentDestination(slot).isPresent()) {
                UUID taskId = UUID.nameUUIDFromBytes(("equip:" + entry.data.id() + ":" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, priority, need,
                        new dev.kindred.core.TaskPreferences.Affinities(0.5, 0.0, 0.0, 0.0), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                return java.util.Optional.of(new NativeTaskCandidate(candidate, "native_equip", Map.of("slot", String.valueOf(slot))));
            }
        }
        return java.util.Optional.empty();
    }

    private List<NativeTaskCandidate> evaluateScheduledRoutineCandidates(Entry entry, NativePlayerBody body) {
        var candidates = new java.util.ArrayList<NativeTaskCandidate>();
        var player = body.entity();
        var world = player.getServerWorld();
        primeWorkstationCaches(entry, body);

        // In a running match it joined, the fight comes first (M-00a): no goal, build or team task goes on meanwhile.
        var duel = duelCandidates(body);
        if (duel != null) return duel;
        var hunt = huntCandidates(entry, body);
        if (hunt != null) return hunt;
        var evade = evadeCandidates(entry, body);
        if (evade != null) return evade;
        var raid = raidCandidates(entry, body);
        if (raid != null) return raid;

        // A find shared out with its team as it comes, and its hand-overs taken up again after a stop (E-40a).
        shareOutFinds(entry, body);

        // 0. Team project task candidates (TEAM-01, TEAM-02)
        java.util.Optional<SharedTask> currentSharedTask = teamCoordinator.getPlayerCurrentTask(entry.data.id());
        if (currentSharedTask.isPresent() && HandOvers.Piece.of(currentSharedTask.get()) != null) {
            // A piece it promised from a find of its own: made, carried over and handed across, one step at a time.
            handOverStep(entry, body, currentSharedTask.get()).ifPresent(candidates::add);
        } else if (currentSharedTask.isPresent()) {
            SharedTask task = currentSharedTask.get();
            var candidate = new dev.kindred.core.TaskPreferences.Candidate(
                    task.id().id(),
                    dev.kindred.core.TaskPreferences.Priority.PROJECT,
                    68.0,
                    dev.kindred.core.TaskPreferences.Affinities.none(),
                    new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true)
            );
            candidates.add(new NativeTaskCandidate(candidate, task.skill(), task.arguments()));
        } else {
            List<SharedTask> eligible = teamCoordinator.findEligibleTasks(entry.data.id());
            for (SharedTask task : eligible) {
                if (HandOvers.Piece.of(task) != null) continue; // A hand-over is its giver's alone.
                var candidate = new dev.kindred.core.TaskPreferences.Candidate(
                        task.id().id(),
                        dev.kindred.core.TaskPreferences.Priority.PROJECT,
                        68.0,
                        dev.kindred.core.TaskPreferences.Affinities.none(),
                        new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true)
                );
                candidates.add(new NativeTaskCandidate(candidate, task.skill(), task.arguments()));
            }
        }

        if (entry.runtimeSettings.playstyleProfile().isIdleObserving()) {
            return candidates;
        }

        // What keeps it alive comes before its ranch, its build or its base (F-25c9): its own bed at night, food enough
        // to last when hunger is low. A running goal weighs food and the night itself; the routine work of a work
        // area keeps its own order for now.
        if (entry.goal == dev.kindred.core.survival.SurvivalGoal.NONE
                && (entry.ranch != null || entry.field != null || entry.build != null || bases && worldEditsEnabled())) {
            var life = lifeCandidates(entry, body);
            if (!life.isEmpty()) {
                candidates.addAll(life);
                return candidates;
            }
        }

        // Its best kit named at an anvil of its own once it has reached the enchanting stage (E-16d), between its work.
        var naming = kitNameCandidates(entry, body);
        if (naming != null) return naming;

        if (entry.ranch != null) {
            candidates.addAll(ranchCandidates(entry, body));
            return candidates;
        }
        if (entry.field != null) {
            candidates.addAll(fieldCandidates(entry, body));
            return candidates;
        }
        if (entry.build != null) {
            candidates.addAll(buildCandidates(entry, body));
            return candidates;
        }

        // What it keeps for later put away in a store of its own, and a store it no longer trusts emptied (E-43).
        var storing = storeCandidates(entry, body);
        if (storing != null) return storing;

        // A lectern of its own by an unemployed villager, its offers rerolled until it is worth keeping (E-45c).
        var rerolling = rerollCandidates(entry, body);
        if (rerolling != null) return rerolling;

        // A villager kept brought home to its cell in the player's trading hall, by minecart (E-45e).
        var bringing = bringCandidates(entry, body);
        if (bringing != null) return bringing;

        // A villager by its way: its offers looked at, and what is worth trading traded (E-45).
        var trading = tradeCandidates(entry, body);
        if (trading != null) return trading;

        if (entry.goal == dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL || entry.goal == dev.kindred.core.survival.SurvivalGoal.STRONGHOLD
                || entry.goal == dev.kindred.core.survival.SurvivalGoal.DRAGON) {
            candidates.addAll(campaignCandidates(entry, body));
            // A piece of gold on in the Nether, the better piece back on out of it (E-07e).
            goldCandidate(entry, body).ifPresent(candidates::add);
            // Armor the goal earned is put on before the next goal step, not carried.
            equipCandidate(entry, body, dev.kindred.core.TaskPreferences.Priority.PROJECT, 75.0).ifPresent(candidates::add);
            return candidates;
        }

        // With the operator's leave, a player with no goal of its own looks to its base (M-01c): the next stage, sited
        // and then built as any build is.
        if (baseDecision(entry, body)) {
            candidates.addAll(buildCandidates(entry, body));
            return candidates;
        }

        boolean playerHasPickaxe = false;
        for (int s = 0; s < 36; s++) {
            if (player.getInventory().getStack(s).isIn(ItemTags.PICKAXES)) {
                playerHasPickaxe = true;
                break;
            }
        }

        // 1. Milestone-driven progression & crafting (ROUTINE / PROJECT)
        var progObs = observeProgression(entry, body);
        entry.lastProgressionObservation = progObs;
        entry.lastProgressionState = entry.progressionGraph.evaluate(progObs, entry.progressionHistory);
        for (var snapEntry : entry.lastProgressionState.snapshots().entrySet()) {
            if (snapEntry.getValue().historicallyAchieved() && !entry.progressionHistory.containsKey(snapEntry.getKey())) {
                entry.progressionHistory.put(snapEntry.getKey(), (long) server.getTicks());
            }
        }

        var recommendations = ProgressionPolicy.recommend(entry.lastProgressionState, progObs);
        if (!entry.lastProgressionState.recoveryFrontier().isEmpty()) {
            var recoveryOnly = java.util.Set.copyOf(entry.lastProgressionState.recoveryFrontier());
            recommendations = recommendations.stream()
                    .filter(rec -> recoveryOnly.contains(rec.milestoneId()))
                    .toList();
        }
        for (var rec : recommendations) {
            switch (rec.skill()) {
                case "native_craft" -> {
                    String recipe = rec.arguments().get("recipe");
                    String actualRecipe = recipe;
                    if (actualRecipe != null) {
                        BlockPos craftTable = entry.cachedCraftingTablePos;
                        if (actualRecipe.equals("oak_planks") && body.planCraft("oak_planks", craftTable).isEmpty()) {
                            for (String logType : List.of("birch", "spruce", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo")) {
                                if (body.planCraft(logType + "_planks", craftTable).isPresent()) {
                                    actualRecipe = logType + "_planks";
                                    break;
                                }
                            }
                        }
                        if (body.planCraft(actualRecipe, craftTable).isPresent()) {
                            UUID taskId = UUID.nameUUIDFromBytes(("craft_" + actualRecipe + ":" + entry.data.id()).getBytes(StandardCharsets.UTF_8));
                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                    rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                            candidates.add(new NativeTaskCandidate(candidate, "native_craft", Map.of("recipe", actualRecipe)));
                        }
                    }
                }
                case "native_place" -> {
                    String block = rec.arguments().get("block");
                    if ("minecraft:crafting_table".equals(block)) {
                        int tableHotbarSlot = -1;
                        int tableMainSlot = -1;
                        for (int s = 0; s < 9; s++) {
                            if (player.getInventory().getStack(s).isOf(Items.CRAFTING_TABLE)) {
                                tableHotbarSlot = s;
                                break;
                            }
                        }
                        if (tableHotbarSlot == -1) {
                            for (int s = 9; s < 36; s++) {
                                if (player.getInventory().getStack(s).isOf(Items.CRAFTING_TABLE)) {
                                    tableMainSlot = s;
                                    break;
                                }
                            }
                        }
                        if (tableHotbarSlot != -1) {
                            BlockPos placeSpot = findPlacementSpot(player, entry.setup, edits, "minecraft:crafting_table");
                            if (placeSpot != null) {
                                UUID taskId = UUID.nameUUIDFromBytes(("place_table:" + entry.data.id()).getBytes(StandardCharsets.UTF_8));
                                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                        rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                                candidates.add(new NativeTaskCandidate(candidate, "native_place", Map.of(
                                        "slot", String.valueOf(tableHotbarSlot),
                                        "x", String.valueOf(placeSpot.getX()),
                                        "y", String.valueOf(placeSpot.getY()),
                                        "z", String.valueOf(placeSpot.getZ()),
                                        "side", "up")));
                            }
                        } else if (tableMainSlot != -1) {
                            int targetH = 0;
                            for (int h = 0; h < 9; h++) {
                                if (player.getInventory().getStack(h).isEmpty()) {
                                    targetH = h;
                                    break;
                                }
                            }
                            UUID taskId = UUID.nameUUIDFromBytes(("swap_table:" + entry.data.id() + ":" + tableMainSlot + ":" + targetH).getBytes(StandardCharsets.UTF_8));
                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need() + 0.1,
                                    rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                            candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                                    "source", String.valueOf(tableMainSlot),
                                    "target", String.valueOf(targetH))));
                        }
                    } else if ("minecraft:furnace".equals(block)) {
                        int furnaceHotbarSlot = -1;
                        int furnaceMainSlot = -1;
                        for (int s = 0; s < 9; s++) {
                            if (player.getInventory().getStack(s).isOf(Items.FURNACE)) {
                                furnaceHotbarSlot = s;
                                break;
                            }
                        }
                        if (furnaceHotbarSlot == -1) {
                            for (int s = 9; s < 36; s++) {
                                if (player.getInventory().getStack(s).isOf(Items.FURNACE)) {
                                    furnaceMainSlot = s;
                                    break;
                                }
                            }
                        }
                        if (furnaceHotbarSlot != -1) {
                            BlockPos placeSpot = findPlacementSpot(player, entry.setup, edits, "minecraft:furnace");
                            if (placeSpot != null) {
                                UUID taskId = UUID.nameUUIDFromBytes(("place_furnace:" + entry.data.id()).getBytes(StandardCharsets.UTF_8));
                                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                        rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                                candidates.add(new NativeTaskCandidate(candidate, "native_place", Map.of(
                                        "slot", String.valueOf(furnaceHotbarSlot),
                                        "x", String.valueOf(placeSpot.getX()),
                                        "y", String.valueOf(placeSpot.getY()),
                                        "z", String.valueOf(placeSpot.getZ()),
                                        "side", "up")));
                            }
                        } else if (furnaceMainSlot != -1) {
                            int targetH = 0;
                            for (int h = 0; h < 9; h++) {
                                if (player.getInventory().getStack(h).isEmpty()) {
                                    targetH = h;
                                    break;
                                }
                            }
                            UUID taskId = UUID.nameUUIDFromBytes(("swap_furnace:" + entry.data.id() + ":" + furnaceMainSlot + ":" + targetH).getBytes(StandardCharsets.UTF_8));
                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need() + 0.1,
                                    rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                            candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                                    "source", String.valueOf(furnaceMainSlot),
                                    "target", String.valueOf(targetH))));
                        }
                    } else if (block != null && block.endsWith("_bed")) {
                        int bedHotbarSlot = -1;
                        int bedMainSlot = -1;
                        for (int s = 0; s < 9; s++) {
                            if (player.getInventory().getStack(s).getItem() instanceof net.minecraft.item.BedItem) {
                                bedHotbarSlot = s;
                                break;
                            }
                        }
                        if (bedHotbarSlot == -1) {
                            for (int s = 9; s < 36; s++) {
                                if (player.getInventory().getStack(s).getItem() instanceof net.minecraft.item.BedItem) {
                                    bedMainSlot = s;
                                    break;
                                }
                            }
                        }
                        if (bedHotbarSlot != -1) {
                            BlockPos placeSpot = findPlacementSpot(player, entry.setup, edits, block);
                            if (placeSpot != null) {
                                UUID taskId = UUID.nameUUIDFromBytes(("place_bed:" + entry.data.id()).getBytes(StandardCharsets.UTF_8));
                                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                        rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                                candidates.add(new NativeTaskCandidate(candidate, "native_place", Map.of(
                                        "slot", String.valueOf(bedHotbarSlot),
                                        "x", String.valueOf(placeSpot.getX()),
                                        "y", String.valueOf(placeSpot.getY()),
                                        "z", String.valueOf(placeSpot.getZ()),
                                        "side", "up")));
                            }
                        } else if (bedMainSlot != -1) {
                            int targetH = 0;
                            for (int h = 0; h < 9; h++) {
                                if (player.getInventory().getStack(h).isEmpty()) {
                                    targetH = h;
                                    break;
                                }
                            }
                            UUID taskId = UUID.nameUUIDFromBytes(("swap_bed:" + entry.data.id() + ":" + bedMainSlot + ":" + targetH).getBytes(StandardCharsets.UTF_8));
                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need() + 0.1,
                                    rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                            candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                                    "source", String.valueOf(bedMainSlot),
                                    "target", String.valueOf(targetH))));
                        }
                    }
                }
                case "native_plant" -> {
                    var farmlandPos = body.findNearbyFarmland(4.5);
                    if (farmlandPos.isPresent()) {
                        BlockPos fPos = farmlandPos.get();
                        if (world.getBlockState(fPos.up()).isAir() || world.getBlockState(fPos.up()).isReplaceable()) {
                            int seedSlot = -1;
                            for (int s = 0; s < 9; s++) {
                                var it = player.getInventory().getStack(s).getItem();
                                if (it == Items.WHEAT_SEEDS || it == Items.CARROT || it == Items.POTATO || it == Items.BEETROOT_SEEDS) {
                                    seedSlot = s;
                                    break;
                                }
                            }
                            if (seedSlot != -1) {
                                UUID taskId = UUID.nameUUIDFromBytes(("plant:" + entry.data.id() + ":" + fPos.asLong()).getBytes(StandardCharsets.UTF_8));
                                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                        rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                                candidates.add(new NativeTaskCandidate(candidate, "native_plant", Map.of(
                                        "slot", String.valueOf(seedSlot),
                                        "x", String.valueOf(fPos.getX()),
                                        "y", String.valueOf(fPos.getY()),
                                        "z", String.valueOf(fPos.getZ()))));
                            }
                        }
                    }
                }
                case "native_till" -> {
                    int hoeSlot = -1;
                    for (int s = 0; s < 9; s++) {
                        if (player.getInventory().getStack(s).getItem() instanceof net.minecraft.item.HoeItem) {
                            hoeSlot = s;
                            break;
                        }
                    }
                    if (hoeSlot != -1) {
                        BlockPos center = player.getBlockPos();
                        outerTill:
                        for (int y = -1; y <= 1; y++) {
                            for (int x = -3; x <= 3; x++) {
                                for (int z = -3; z <= 3; z++) {
                                    if (!canScanBlock()) {
                                        break outerTill;
                                    }
                                    BlockPos pos = center.add(x, y, z);
                                    if (!world.isChunkLoaded(pos)) continue;
                                    recordBlockScan();
                                    var state = world.getBlockState(pos);
                                    if (state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK)) {
                                        if (!canScanBlock()) {
                                            break outerTill;
                                        }
                                        recordBlockScan();
                                        if (world.getBlockState(pos.up()).isAir() && isWaterNearby(world, pos)) {
                                            UUID taskId = UUID.nameUUIDFromBytes(("till:" + entry.data.id() + ":" + pos.asLong()).getBytes(StandardCharsets.UTF_8));
                                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                                    rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                                            candidates.add(new NativeTaskCandidate(candidate, "native_till", Map.of(
                                                    "slot", String.valueOf(hoeSlot),
                                                    "x", String.valueOf(pos.getX()),
                                                    "y", String.valueOf(pos.getY()),
                                                    "z", String.valueOf(pos.getZ()))));
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                case "native_gather" -> {
                    if ("minecraft:nether_wart".equals(rec.arguments().get("resource"))
                            && entry.setup != null && entry.setup.patch() != null
                            && "minecraft:nether_wart".equals(entry.setup.patch().resourceId())
                            && progObs.isInNether()) {
                        var wartPos = body.findNearbyBlock(Blocks.NETHER_WART, 5.0);
                        if (wartPos.isPresent() && entry.setup.patch().contains(new WorkSetup.BlockPoint(
                                wartPos.get().getX(), wartPos.get().getY(), wartPos.get().getZ()))) {
                            UUID taskId = UUID.nameUUIDFromBytes(("gather_nether_wart:" + entry.data.id() + ":" + wartPos.get().asLong()).getBytes(StandardCharsets.UTF_8));
                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                    rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                            candidates.add(new NativeTaskCandidate(candidate, "native_gather", Map.of(
                                    "count", rec.arguments().getOrDefault("count", "1"),
                                    "slot", rec.arguments().getOrDefault("slot", "0"))));
                        }
                    }
                }
                case "native_ignite_portal" -> {
                    int flintSlot = -1;
                    for (int s = 0; s < 9; s++) {
                        var st = player.getInventory().getStack(s);
                        if (!st.isEmpty() && (st.getItem() == Items.FLINT_AND_STEEL || st.getItem() == Items.FIRE_CHARGE)) {
                            flintSlot = s;
                            break;
                        }
                    }
                    boolean foundPortal = false;
                    if (flintSlot != -1) {
                        BlockPos center = player.getBlockPos();
                        for (int y = -2; y <= 2; y++) {
                            for (int x = -4; x <= 4; x++) {
                                for (int z = -4; z <= 4; z++) {
                                    BlockPos pos = center.add(x, y, z);
                                    if (!world.isChunkLoaded(pos)) continue;
                                    var state = world.getBlockState(pos);
                                    if (state.isOf(Blocks.OBSIDIAN) && world.getBlockState(pos.up()).isAir()) {
                                        var pX = net.minecraft.world.dimension.NetherPortal.getNewPortal(world, pos.up(), Direction.Axis.X);
                                        var pZ = pX.isPresent() ? pX : net.minecraft.world.dimension.NetherPortal.getNewPortal(world, pos.up(), Direction.Axis.Z);
                                        if (pZ.isPresent()) {
                                            foundPortal = true;
                                            UUID taskId = UUID.nameUUIDFromBytes(("ignite_portal:" + entry.data.id() + ":" + pos.asLong()).getBytes(StandardCharsets.UTF_8));
                                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                                    rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                                            candidates.add(new NativeTaskCandidate(candidate, "native_ignite_portal", Map.of(
                                                    "slot", String.valueOf(flintSlot),
                                                    "x", String.valueOf(pos.getX()),
                                                    "y", String.valueOf(pos.getY()),
                                                    "z", String.valueOf(pos.getZ()))));
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (flintSlot != -1 && !foundPortal && entry.setup != null && entry.setup.area() != null) {
                        int obsidianHotbar = -1;
                        int obsidianMain = -1;
                        for (int s = 0; s < 9; s++) {
                            if (player.getInventory().getStack(s).isOf(Items.OBSIDIAN)) { obsidianHotbar = s; break; }
                        }
                        if (obsidianHotbar == -1) {
                            for (int s = 9; s < 36; s++) {
                                if (player.getInventory().getStack(s).isOf(Items.OBSIDIAN)) { obsidianMain = s; break; }
                            }
                        }
                        if (obsidianMain >= 0) {
                            int target = -1;
                            for (int h = 0; h < 9; h++) {
                                if (player.getInventory().getStack(h).isEmpty()) { target = h; break; }
                            }
                            if (target >= 0) {
                                UUID taskId = UUID.nameUUIDFromBytes(("portal_obsidian_swap:" + entry.data.id() + ":" + obsidianMain).getBytes(StandardCharsets.UTF_8));
                                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need() + 0.2,
                                        rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                                candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                                        "source", String.valueOf(obsidianMain), "target", String.valueOf(target))));
                            }
                        } else if (obsidianHotbar >= 0) {
                            findPortalFrameAssemblyStep(entry, body, obsidianHotbar).ifPresent(step -> {
                                UUID taskId = UUID.nameUUIDFromBytes(("portal_frame:" + entry.data.id() + ":" + step.get("x") + ":" + step.get("y") + ":" + step.get("z")).getBytes(StandardCharsets.UTF_8));
                                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need() + 0.1,
                                        rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                                candidates.add(new NativeTaskCandidate(candidate, "native_place", step));
                            });
                        }
                    }
                }
                case "native_smelt" -> {
                    BlockPos furnacePos = entry.cachedFurnacePos;
                    if (furnacePos != null) {
                        String action = rec.arguments().get("action");
                        UUID taskId = UUID.nameUUIDFromBytes((action + "_smelt:" + entry.data.id() + ":" + furnacePos.asLong()).getBytes(StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, rec.priority(), rec.need(),
                                rec.affinities(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_smelt", Map.of(
                                "x", String.valueOf(furnacePos.getX()),
                                "y", String.valueOf(furnacePos.getY()),
                                "z", String.valueOf(furnacePos.getZ()),
                                "action", action)));
                    }
                }
            }
        }

        appendPlayerQolCandidates(entry, body, candidates);
        appendHotbarOrganizationCandidates(entry, player, candidates);
        appendChestOrganizeCandidate(entry, body, candidates);

        // Autonomous crop harvesting: if farmland is nearby within reach, harvest mature crops!
        if (entry.lastProgressionObservation != null && entry.lastProgressionObservation.hasNearbyFarmland()) {
            BlockPos centerPos = player.getBlockPos();
            outerHarvest:
            for (int y = -1; y <= 1; y++) {
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        if (!canScanBlock()) {
                            break outerHarvest;
                        }
                        BlockPos cPos = centerPos.add(x, y, z);
                        if (!world.isChunkLoaded(cPos)) continue;
                        recordBlockScan();
                        var cState = world.getBlockState(cPos);
                        if (cState.getBlock() instanceof CropBlock cb && cb.isMature(cState)) {
                            UUID taskId = UUID.nameUUIDFromBytes(("harvest:" + entry.data.id() + ":" + cPos.asLong()).getBytes(StandardCharsets.UTF_8));
                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId,
                                    dev.kindred.core.TaskPreferences.Priority.ROUTINE, 62.5,
                                    new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                    new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                            candidates.add(new NativeTaskCandidate(candidate, "native_harvest", Map.of(
                                    "x", String.valueOf(cPos.getX()),
                                    "y", String.valueOf(cPos.getY()),
                                    "z", String.valueOf(cPos.getZ()),
                                    "replant", "true")));
                            break outerHarvest;
                        }
                    }
                }
            }
        }

        // Autonomous sleep: if night or thunderstorm and bed is nearby, sleep to anchor spawn point and skip danger
        boolean nightOrStorm = world.isNight() || world.isThundering()
                || (world.getTimeOfDay() % 24000L >= 12542L && world.getTimeOfDay() % 24000L <= 23460L);
        if (nightOrStorm) {
            var nearbyBed = body.findNearbyBed(4.5);
            if (nearbyBed.isPresent()) {
                BlockPos bedPos = nearbyBed.get();
                var bedState = world.getBlockState(bedPos);
                if (bedState.isIn(BlockTags.BEDS) && !bedState.get(net.minecraft.block.BedBlock.OCCUPIED)) {
                    UUID taskId = UUID.nameUUIDFromBytes(("sleep:" + entry.data.id() + ":" + bedPos.asLong()).getBytes(StandardCharsets.UTF_8));
                    var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId,
                            dev.kindred.core.TaskPreferences.Priority.PROJECT, 78.0,
                            dev.kindred.core.TaskPreferences.Affinities.none(),
                            new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                    candidates.add(new NativeTaskCandidate(candidate, "native_sleep", Map.of(
                            "x", String.valueOf(bedPos.getX()),
                            "y", String.valueOf(bedPos.getY()),
                            "z", String.valueOf(bedPos.getZ()))));
                }
            }
        }

        // 2. Scoped resource mining in assigned patch (PROJECT)
        boolean deferPatchMiningForCraft = candidates.stream().anyMatch(c ->
                "native_craft".equals(c.skill()) && c.candidate().needScore() >= 61.0
                        && c.candidate().eligibility().affordable());
        boolean recoveryBlocksPatchMining = entry.lastProgressionState != null
                && !entry.lastProgressionState.recoveryFrontier().isEmpty();
        if (!deferPatchMiningForCraft && !recoveryBlocksPatchMining && entry.setup != null && entry.setup.patch() != null && entry.setup.patch().resourceId() != null
                && entry.setup.dimension().equals(world.getRegistryKey().getValue().toString())) {
            primePatchResourceCaches(entry, body);
            var patch = entry.setup.patch();
            boolean patchRequiresPickaxe = patch.resourceId().equals("minecraft:stone")
                    || patch.resourceId().equals("minecraft:cobblestone")
                    || patch.resourceId().contains("ore");
            if (!patchRequiresPickaxe || playerHasPickaxe) {
                BlockPos center = player.getBlockPos();
                int reach = 4;
                BlockPos bestPos = null;
                double bestDistSq = Double.MAX_VALUE;
                boolean bestIsStone = false;

                if (entry.cachedMiningTarget != null) {
                    var pos = entry.cachedMiningTarget;
                    if (world.isChunkLoaded(pos) && Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString().equals(patch.resourceId())
                            && body.miningEligible(pos) && player.getEyePos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 16.0) {
                        bestPos = pos;
                        bestIsStone = entry.cachedMiningIsStone;
                    } else {
                        entry.cachedMiningTarget = null;
                    }
                }

                if (bestPos == null) {
                    int minX = Math.max(center.getX() - reach, patch.min().x());
                    int maxX = Math.min(center.getX() + reach, patch.max().x());
                    int minY = Math.max(center.getY() - 1, patch.min().y());
                    int maxY = Math.min(center.getY() + 2, patch.max().y());
                    int minZ = Math.max(center.getZ() - reach, patch.min().z());
                    int maxZ = Math.min(center.getZ() + reach, patch.max().z());
                    outerMine:
                    for (int y = minY; y <= maxY; y++) {
                        for (int x = minX; x <= maxX; x++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                BlockPos pos = new BlockPos(x, y, z);
                                if (!patch.contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) continue;
                                if (entry.setup.area() != null && !entry.setup.area().contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) continue;
                                if (!world.isChunkLoaded(pos)) continue;
                                if (!canScanBlock()) break outerMine;
                                recordBlockScan();
                                var state = world.getBlockState(pos);
                                boolean isLog = state.isIn(BlockTags.LOGS);
                                boolean isStone = state.isIn(BlockTags.PICKAXE_MINEABLE) || state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE);
                                if ((!isLog && !isStone) || state.hasBlockEntity()) continue;
                                if (isStone && !playerHasPickaxe) continue;
                                if (!Registries.BLOCK.getId(state.getBlock()).toString().equals(patch.resourceId())) continue;
                                if (!body.miningEligible(pos)) continue;
                                double distSq = player.getEyePos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(pos));
                                if (distSq < bestDistSq) {
                                    bestDistSq = distSq;
                                    bestPos = pos;
                                    bestIsStone = isStone;
                                }
                            }
                        }
                    }
                    if (bestPos != null) {
                        entry.cachedMiningTarget = bestPos;
                        entry.cachedMiningIsStone = bestIsStone;
                    }
                }

                if (bestPos != null) {
                    int toolSlot = -1;
                    int bestTier = -1;
                    var preferredTag = bestIsStone ? ItemTags.PICKAXES : ItemTags.AXES;
                    for (int s = 0; s < 9; s++) {
                        var st = player.getInventory().getStack(s);
                        if (st.isIn(preferredTag)) {
                            int tier = toolTier(st.getItem());
                            if (tier > bestTier) {
                                bestTier = tier;
                                toolSlot = s;
                            }
                        }
                    }
                    int mainToolSlot = -1;
                    int mainBestTier = bestTier;
                    for (int s = 9; s < 36; s++) {
                        var st = player.getInventory().getStack(s);
                        if (st.isIn(preferredTag)) {
                            int tier = toolTier(st.getItem());
                            if (tier > mainBestTier) {
                                mainBestTier = tier;
                                mainToolSlot = s;
                            }
                        }
                    }
                    if (mainToolSlot != -1) {
                        int targetH = 0;
                        for (int h = 0; h < 9; h++) {
                            if (player.getInventory().getStack(h).isEmpty()) {
                                targetH = h;
                                break;
                            }
                        }
                        UUID taskId = UUID.nameUUIDFromBytes(("swap_tool:" + entry.data.id() + ":" + mainToolSlot + ":" + targetH).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 60.1,
                                new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 1.0), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                                "source", String.valueOf(mainToolSlot),
                                "target", String.valueOf(targetH))));
                    } else {
                        if (toolSlot == -1) toolSlot = 0;
                        UUID taskId = UUID.nameUUIDFromBytes(("mine:" + entry.data.id() + ":" + bestPos.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 60.0,
                                new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 1.0), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_mine", Map.of(
                                "x", String.valueOf(bestPos.getX()),
                                "y", String.valueOf(bestPos.getY()),
                                "z", String.valueOf(bestPos.getZ()),
                                "slot", String.valueOf(toolSlot))));
                    }
                } else {
                    // Search wider radius for unreached resource in patch to approach via traverse
                    int searchRadius = 12;
                    BlockPos distantTarget = entry.cachedApproachTarget;
                    if (distantTarget != null) {
                        if (!world.isChunkLoaded(distantTarget) || !Registries.BLOCK.getId(world.getBlockState(distantTarget).getBlock()).toString().equals(patch.resourceId())) {
                            distantTarget = null;
                            entry.cachedApproachTarget = null;
                        }
                    }
                    if (distantTarget == null) {
                        double closestDistantSq = Double.MAX_VALUE;
                        int minX = Math.max(center.getX() - searchRadius, patch.min().x());
                        int maxX = Math.min(center.getX() + searchRadius, patch.max().x());
                        int minY = Math.max(center.getY() - 3, patch.min().y());
                        int maxY = Math.min(center.getY() + 3, patch.max().y());
                        int minZ = Math.max(center.getZ() - searchRadius, patch.min().z());
                        int maxZ = Math.min(center.getZ() + searchRadius, patch.max().z());
                        outerDistant:
                        for (int y = minY; y <= maxY; y++) {
                            for (int x = minX; x <= maxX; x++) {
                                for (int z = minZ; z <= maxZ; z++) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    if (!patch.contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) continue;
                                    if (entry.setup.area() != null && !entry.setup.area().contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) continue;
                                    if (!world.isChunkLoaded(pos)) continue;
                                    if (!canScanBlock()) break outerDistant;
                                    recordBlockScan();
                                    var state = world.getBlockState(pos);
                                    boolean isLog = state.isIn(BlockTags.LOGS);
                                    boolean isStone = state.isIn(BlockTags.PICKAXE_MINEABLE) || state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE);
                                    if ((!isLog && !isStone) || state.hasBlockEntity()) continue;
                                    if (isStone && !playerHasPickaxe) continue;
                                    if (!Registries.BLOCK.getId(state.getBlock()).toString().equals(patch.resourceId())) continue;
                                    double dSq = player.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(pos));
                                    if (dSq < closestDistantSq) {
                                        closestDistantSq = dSq;
                                        distantTarget = pos;
                                    }
                                }
                            }
                        }
                        if (distantTarget != null) entry.cachedApproachTarget = distantTarget;
                    }
                    if (distantTarget != null) {
                        int dx = distantTarget.getX() - center.getX();
                        int dz = distantTarget.getZ() - center.getZ();
                        String dir = null;
                        int dist = 0;
                        // Up to the block beside it, never onto it: two off, a walk of two stepped up onto the log it was
                        // to mine, where it could not break what it stood on (the distant-log fixture, 2026-10-24).
                        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
                            dir = dx > 0 ? "east" : "west";
                            dist = Math.min(3, Math.abs(dx) - 1);
                        } else if (dz != 0) {
                            dir = dz > 0 ? "south" : "north";
                            dist = Math.min(3, Math.abs(dz) - 1);
                        }
                        if (dir != null && dist > 0) {
                            UUID taskId = UUID.nameUUIDFromBytes(("traverse_approach:" + entry.data.id() + ":" + distantTarget.asLong() + ":" + dir).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 59.0,
                                    new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.9), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                            candidates.add(new NativeTaskCandidate(candidate, "native_traverse", Map.of(
                                    "direction", dir,
                                    "blocks", String.valueOf(dist))));
                        }
                    }
                }
            }
        }

        // 3. Unequipped armor / shield (ROUTINE)
        equipCandidate(entry, body, dev.kindred.core.TaskPreferences.Priority.ROUTINE, 55.0).ifPresent(candidates::add);

        // 4. Bounded wait when injured and hunger full (ROUTINE)
        if (player.getHealth() < player.getMaxHealth() && !player.canConsume(false)) {
            UUID taskId = UUID.nameUUIDFromBytes(("wait:" + entry.data.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            double need = Math.min(100.0, (player.getMaxHealth() - player.getHealth()) * 10.0 + 30.0);
            var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.ROUTINE, need,
                    dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
            candidates.add(new NativeTaskCandidate(candidate, "native_wait", Map.of("seconds", "2")));
        }

        // 5. Bounded expedition and exploration planning (E-14)
        ensureExpeditionState(entry, body);
        if (entry.expeditionState != null) {
            ExpeditionObservation expObs = observeExpedition(entry, body);
            ExpeditionPlan plan = ExpeditionPlanner.plan(entry.expeditionState, expObs);
            KindredMod.LOGGER.debug("Expedition plan for {}: status={}, plan={}, obs={}", entry.data.displayName(), entry.expeditionState.status(), plan, expObs);
            if ("native_traverse".equals(plan.action()) && plan.blocks() > 0) {
                UUID taskId = UUID.nameUUIDFromBytes(("expedition:" + entry.data.id() + ":" + entry.expeditionState.status() + ":" + plan.direction()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var candidate = new dev.kindred.core.TaskPreferences.Candidate(
                        taskId,
                        plan.priority(),
                        plan.need(),
                        new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                        new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true)
                );
                candidates.add(new NativeTaskCandidate(candidate, "native_traverse", Map.of(
                        "direction", plan.direction(),
                        "blocks", String.valueOf(plan.blocks())
                )));
            }
        }

        return candidates;
    }

    /** The standing goal's next concrete step, as one ordinary candidate; survival needs still outrank it. */
    /**
     * The player's memory of this world: read once for each campaign and recalled into it, then written whenever it
     * learns something worth keeping. Reads and writes are off the game thread.
     */
    private void keepMemory(Entry entry) {
        var campaign = entry.campaign;
        UUID id = entry.data.id();
        if (entry.memoryCampaign != campaign) {
            entry.memoryCampaign = campaign;
            entry.memorySaved = -1;
            entry.memoryLoaded = false;
            String name = entry.data.displayName();
            memories.load(id, name).whenComplete((memory, failure) -> server.execute(() -> {
                if (!running || entries.get(id) != entry || entry.memoryCampaign != campaign) return;
                if (memory != null) campaign.stronghold().recall(memory);
                entry.memoryLoaded = true;
                entry.memorySaved = campaign.stronghold().memoryRevision();
            }));
            return;
        }
        if (entry.memoryLoaded && campaign.stronghold().memoryRevision() != entry.memorySaved) {
            entry.memorySaved = campaign.stronghold().memoryRevision();
            var memory = new PlayerMemoryStore.Memory();
            memory.player = entry.data.displayName();
            memory.id = id.toString();
            memories.save(id, campaign.stronghold().remember(memory));
        }
    }

    private List<NativeTaskCandidate> campaignCandidates(Entry entry, NativePlayerBody body) {
        if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
        keepMemory(entry);
        entry.campaign.pursue(entry.goal);
        entry.campaign.dragonAllowed(dragonFight);
        entry.campaign.huntersKit(gearingHunter(body));
        entry.campaign.pace(entry.runtimeSettings.playstyleProfile() == PlaystyleProfile.SPEEDRUN_ANY_PERCENT
                ? dev.kindred.core.guide.Playbook.Mode.SPEEDRUN : dev.kindred.core.guide.Playbook.Mode.NORMAL);
        divideTheRun(entry, body);
        var task = entry.campaign.next(body, edits, server.getTicks());
        // Through a portal only on purpose: while it is after the End portal (the operator's goal is what allows the
        // trip) and the step it is on or about to take goes through one. Never while it works beside one: a player
        // digging stone by its lit portal stood in it and was carried into the Nether (suite on 2026-10-22).
        boolean mayTravel = entry.goal == dev.kindred.core.survival.SurvivalGoal.STRONGHOLD
                || entry.goal == dev.kindred.core.survival.SurvivalGoal.DRAGON && dragonFight;
        body.setPortalTravelAuthorized(mayTravel && (task.filter(PortalRunCampaign::throughPortal).isPresent()
                || PortalRunCampaign.throughPortal(entry.activeCampaignTask)));
        // Advice is asked for off the game thread and taken when it comes; nothing here waits for it.
        strategist.ifPresent(bridge -> bridge.tick(entry.data.id(), entry.data.displayName(), entry.campaign, body.entity(), server, storedWords(entry)));
        entry.goalProgress = entry.campaign.progress();
        if (entry.campaign.stage() == dev.kindred.core.survival.PortalRunPlanner.Stage.DONE && !entry.goalAnnounced) {
            entry.goalAnnounced = true;
            var pos = body.entity().getBlockPos();
            // Its teammate built the run's portal: it says so, and claims none of it (E-40a).
            say(entry, entry.campaign.portalAdopted() ? "The team's Nether portal is lit." : "I built and lit a Nether portal!",
                    "near " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
            entry.goalProgress = entry.campaign.portalAdopted() ? "Done: the team's Nether portal is lit" : "Done: the Nether portal is built and lit";
        }
        var endPortal = entry.campaign.stronghold().endPortal();
        if ((entry.goal == dev.kindred.core.survival.SurvivalGoal.STRONGHOLD || entry.goal == dev.kindred.core.survival.SurvivalGoal.DRAGON)
                && endPortal.isPresent() && !entry.endPortalAnnounced) {
            entry.endPortalAnnounced = true;
            var at = endPortal.get();
            say(entry, "I found the End portal!", "near " + at.getX() + ", " + at.getY() + ", " + at.getZ());
        }
        if (entry.goal == dev.kindred.core.survival.SurvivalGoal.DRAGON && entry.campaign.stronghold().endFight().dragonKilled() && !entry.dragonAnnounced) {
            entry.dragonAnnounced = true;
            say(entry, "The Ender Dragon is dead. I beat the game!", "");
        }
        if (!entry.goalProgress.equals(entry.loggedGoalProgress)) {
            entry.loggedGoalProgress = entry.goalProgress;
            var at = body.entity().getBlockPos();
            KindredMod.LOGGER.info("{} goal at {} {} {}: {}", entry.data.displayName(), at.getX(), at.getY(), at.getZ(), entry.goalProgress);
        }
        if (task.isEmpty()) return List.of();
        var t = task.get();
        UUID id = UUID.nameUUIDFromBytes(("campaign:" + entry.data.id() + ":" + t.skill() + ":" + new java.util.TreeMap<>(t.args()))
                .getBytes(StandardCharsets.UTF_8));
        entry.campaignTasks.clear();
        entry.campaignTasks.put(id, t);
        boolean urgent = PortalRunCampaign.urgentTask(t) || PortalRunCampaign.combatTask(t);
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(id,
                urgent ? dev.kindred.core.TaskPreferences.Priority.URGENT : dev.kindred.core.TaskPreferences.Priority.PROJECT, urgent ? 99.0 : 70.0,
                new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.6), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        return List.of(new NativeTaskCandidate(candidate, t.skill(), t.args()));
    }

    private void campaignTaskFinished(Entry entry, SkillRunner.State state) {
        if (entry.campaign == null) return;
        var task = entry.activeCampaignTask;
        entry.activeCampaignTask = null;
        if (task != null && entry.campaignFightInterrupt) {
            entry.campaignFightInterrupt = false;
            entry.campaign.taskInterrupted(task);
        } else if (task != null && state != SkillRunner.State.COMPLETED
                && entry.runner.reason().orElse("").startsWith("Break preconditions changed before dispatch")) {
            entry.campaign.taskRefusedForNow(task, server.getTicks()); // The player was still moving; nothing happened.
        } else if (task != null) {
            boolean completed = state == SkillRunner.State.COMPLETED;
            if (completed && "native_throw_eye".equals(task.skill())) entry.campaign.stronghold().eyeFlown(entry.runner.lastProgress());
            if (completed && "native_row".equals(task.skill())) entry.campaign.stronghold().rowed(entry.runner.lastProgress());
            if (completed && "native_boat".equals(task.skill())) entry.campaign.stronghold().boatStep(task.args().get("mode"), entry.runner.lastProgress());
            if (completed && "native_place".equals(task.skill()) && entry.nativeBody != null) {
                BlockPos placed = new BlockPos(Integer.parseInt(task.args().get("x")), Integer.parseInt(task.args().get("y")),
                        Integer.parseInt(task.args().get("z")));
                var block = entry.nativeBody.entity().getServerWorld().getBlockState(placed);
                if (block.isOf(Blocks.CRAFTING_TABLE) || block.isOf(Blocks.FURNACE)) {
                    entry.campaign.placedWorkstation(placed);
                    entry.campaign.knowledge().remember(block.isOf(Blocks.FURNACE) ? WorldKnowledge.Kind.FURNACE : WorldKnowledge.Kind.CRAFTING_TABLE, placed);
                }
            }
            entry.campaign.taskFinished(task, completed, server.getTicks());
        }
        entry.routineRefreshPending = true;
        entry.cachedRoutineCandidates = List.of();
    }

    /** The build's next step, as one project candidate; survival needs still outrank it (E-08b). */
    private List<NativeTaskCandidate> buildCandidates(Entry entry, NativePlayerBody body) {
        var build = entry.build;
        // What its needs draw on: the run's gathering and crafting, and its own assigned storage (E-08c). A build
        // battle's builder holds all it needs.
        if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
        entry.campaign.policy(edits);
        var storage = entry.setup == null || entry.setup.storage() == null ? null : new BlockPos(entry.setup.storage().position().x(),
                entry.setup.storage().position().y(), entry.setup.storage().position().z());
        var task = build.next(body, new BuildRunner.Supply(entry.campaign, storage));
        // Its scaffold kept with its work as it changes, to take down as its own after a restart (F-13).
        if (entry.keptWork != null && "build".equals(entry.keptWork.kind())) {
            var scaffold = build.scaffold();
            if (!scaffold.equals(entry.keptScaffold)) {
                entry.keptScaffold = scaffold;
                var w = entry.keptWork;
                keepWork(entry, new PlayerMemoryStore.Work(w.kind(), w.plan(), w.dimension(), w.x(), w.y(), w.z(), w.turns(),
                        scaffold.stream().map(p -> new int[] {p.x(), p.y(), p.z()}).toList()));
            }
        }
        entry.goalProgress = build.progress();
        if (!entry.goalProgress.equals(entry.loggedGoalProgress)) {
            entry.loggedGoalProgress = entry.goalProgress;
            KindredMod.LOGGER.info("{} build: {}", entry.data.displayName(), entry.goalProgress);
        }
        if (task.isEmpty()) {
            if (build.done()) {
                if (build == entry.baseBuild) baseBuilt(entry, build);
                if (build == entry.hallBuild) hallBuilt(entry, build);
                entry.build = null;
                entry.buildTasks.clear();
                keepWork(entry, null);
                say(entry, build.progress() + ".", "at " + build.origin.getX() + ", " + build.origin.getY() + ", " + build.origin.getZ());
                entry.routineRefreshPending = true;
            }
            return List.of();
        }
        var t = task.get();
        UUID id = UUID.nameUUIDFromBytes(("build:" + entry.data.id() + ":" + t.skill() + ":" + new java.util.TreeMap<>(t.args()))
                .getBytes(StandardCharsets.UTF_8));
        entry.buildTasks.clear();
        entry.buildTasks.put(id, t);
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(id, dev.kindred.core.TaskPreferences.Priority.PROJECT, 70.0,
                new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.6), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        return List.of(new NativeTaskCandidate(candidate, t.skill(), t.args()));
    }

    /** The ranch's next step, as one project candidate; survival needs still outrank it (M-01a). */
    private List<NativeTaskCandidate> ranchCandidates(Entry entry, NativePlayerBody body) {
        var ranch = entry.ranch;
        if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
        var task = ranch.next(body, entry.campaign);
        entry.goalProgress = "Ranch: " + ranch.progress();
        if (!entry.goalProgress.equals(entry.loggedGoalProgress)) {
            entry.loggedGoalProgress = entry.goalProgress;
            KindredMod.LOGGER.info("{} ranch: {}", entry.data.displayName(), ranch.progress());
        }
        if (task.isEmpty()) {
            if (ranch.done()) {
                entry.ranch = null;
                entry.buildTasks.clear();
                say(entry, ranch.outcome() + ".", "the pen at " + ranch.origin.getX() + ", " + ranch.origin.getY() + ", " + ranch.origin.getZ());
                entry.routineRefreshPending = true;
            }
            return List.of();
        }
        var t = task.get();
        UUID id = UUID.nameUUIDFromBytes(("ranch:" + entry.data.id() + ":" + t.skill() + ":" + new java.util.TreeMap<>(t.args()))
                .getBytes(StandardCharsets.UTF_8));
        entry.buildTasks.clear();
        entry.buildTasks.put(id, t);
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(id, dev.kindred.core.TaskPreferences.Priority.PROJECT, 70.0,
                new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.6), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        return List.of(new NativeTaskCandidate(candidate, t.skill(), t.args()));
    }

    /** The field's next step, as one project candidate; survival needs still outrank it (M-06a). */
    private List<NativeTaskCandidate> fieldCandidates(Entry entry, NativePlayerBody body) {
        var field = entry.field;
        var task = field.next(body);
        entry.goalProgress = "Field: " + field.progress();
        if (!entry.goalProgress.equals(entry.loggedGoalProgress)) {
            entry.loggedGoalProgress = entry.goalProgress;
            KindredMod.LOGGER.info("{} field: {}", entry.data.displayName(), field.progress());
        }
        if (task.isEmpty()) {
            if (field.done()) {
                entry.field = null;
                entry.buildTasks.clear();
                keepWork(entry, null);
                say(entry, field.outcome() + ".", "the field at " + field.corner.getX() + ", " + field.corner.getY() + ", " + field.corner.getZ());
                entry.routineRefreshPending = true;
            }
            return List.of();
        }
        var t = task.get();
        UUID id = UUID.nameUUIDFromBytes(("field:" + entry.data.id() + ":" + t.skill() + ":" + new java.util.TreeMap<>(t.args()))
                .getBytes(StandardCharsets.UTF_8));
        entry.buildTasks.clear();
        entry.buildTasks.put(id, t);
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(id, dev.kindred.core.TaskPreferences.Priority.PROJECT, 70.0,
                new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.6), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        return List.of(new NativeTaskCandidate(candidate, t.skill(), t.args()));
    }

    private void buildTaskFinished(Entry entry, SkillRunner.State state) {
        var task = entry.activeBuildTask;
        entry.activeBuildTask = null;
        // A table set down to craft at for the build's needs (E-08c) is its own to come back to, however often the build
        // planned again while it was being set down; the build's own table is the build's.
        boolean tableForNeeds = task != null && "native_place".equals(task.skill()) && "Place a crafting table".equals(task.label());
        if (task != null && entry.build != null && entry.nativeBody != null && entry.campaign != null && (entry.build.needsTask(task) || tableForNeeds)) {
            // A step for the build's needs (E-08c): the run's own gathering hears how it went.
            boolean completed = state == SkillRunner.State.COMPLETED;
            if (completed && "native_place".equals(task.skill())) {
                BlockPos placed = new BlockPos(Integer.parseInt(task.args().get("x")), Integer.parseInt(task.args().get("y")),
                        Integer.parseInt(task.args().get("z")));
                if (entry.nativeBody.entity().getServerWorld().getBlockState(placed).isOf(Blocks.CRAFTING_TABLE)) {
                    entry.campaign.placedWorkstation(placed);
                    entry.campaign.knowledge().remember(WorldKnowledge.Kind.CRAFTING_TABLE, placed);
                }
            }
            entry.campaign.taskFinished(task, completed, server.getTicks());
        }
        if (task != null && entry.field != null) {
            boolean completed = state == SkillRunner.State.COMPLETED;
            if (!completed) KindredMod.LOGGER.info("{} field step did not complete: {} [{}]", entry.data.displayName(),
                    entry.runner.reason().orElse(""), task.label());
            entry.field.finished(task, completed);
            entry.routineRefreshPending = true;
            entry.cachedRoutineCandidates = List.of();
            return;
        }
        if (task != null && entry.ranch != null && entry.nativeBody != null) {
            var world = entry.nativeBody.entity().getServerWorld();
            boolean completed = state == SkillRunner.State.COMPLETED;
            if (completed && "native_place".equals(task.skill()) && entry.campaign != null) {
                // A table it set down to craft the pen's fences is its own to craft at again.
                BlockPos placed = new BlockPos(Integer.parseInt(task.args().get("x")), Integer.parseInt(task.args().get("y")),
                        Integer.parseInt(task.args().get("z")));
                if (world.getBlockState(placed).isOf(Blocks.CRAFTING_TABLE)) {
                    entry.campaign.placedWorkstation(placed);
                    entry.campaign.knowledge().remember(WorldKnowledge.Kind.CRAFTING_TABLE, placed);
                }
            }
            if (!completed) KindredMod.LOGGER.info("{} ranch step did not complete: {} [{}]", entry.data.displayName(),
                    entry.runner.reason().orElse(""), task.label());
            entry.ranch.finished(task, completed, entry.runner.reason().orElse(""), world);
            entry.routineRefreshPending = true;
            entry.cachedRoutineCandidates = List.of();
            return;
        }
        if (task == null || entry.build == null || entry.nativeBody == null) return;
        entry.build.finished(task, state == SkillRunner.State.COMPLETED, entry.runner.reason().orElse(""),
                entry.nativeBody.entity().getServerWorld());
        entry.routineRefreshPending = true;
        entry.cachedRoutineCandidates = List.of();
    }

    /**
     * Starts a build (E-08b): {@code blueprint} placed with its origin at {@code origin}, turned clockwise
     * {@code turns} quarters, built from the player's own blocks ahead of its standing goal. Null stops the build.
     */
    int startBuild(ServerCommandSource source, UUID id, dev.kindred.core.construction.Blueprint blueprint, BlockPos origin, int turns) {
        return startBuild(source, id, blueprint, origin, turns, null);
    }

    /**
     * As {@link #startBuild(ServerCommandSource, UUID, dev.kindred.core.construction.Blueprint, BlockPos, int)}, the
     * build kept for a restart under {@code plan}, the name the command gave it (F-13); with none it is not kept (a
     * base's build has its own record, a build battle's ends with the match).
     */
    int startBuild(ServerCommandSource source, UUID id, dev.kindred.core.construction.Blueprint blueprint, BlockPos origin, int turns, String plan) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (blueprint == null) {
            // A base's build stopped by its administrator is not taken up again at once (M-01c).
            if (entry.build != null && (entry.build == entry.baseBuild || entry.build == entry.hallBuild)) {
                entry.baseBuild = null;
                entry.baseRepair = null;
                entry.hallBuild = null;
                entry.baseCheckAt = server.getTicks() + BASE_RETRY;
            }
            entry.build = null;
            entry.buildTasks.clear();
            keepWork(entry, null);
            entry.goalProgress = "";
            entry.cachedRoutineCandidates = List.of();
            entry.routineRefreshPending = true;
            feedback(source, entry.data.displayName() + ": the build is stopped; what is built stays.");
            return 1;
        }
        if (!worldEditsEnabled()) return reject(source, "AI world edits are disabled on this server; an operator must enable them first.");
        entry.build = new BuildRunner(blueprint, origin, turns);
        entry.keptScaffold = Set.of();
        keepWork(entry, plan == null || entry.nativeBody == null ? null : new PlayerMemoryStore.Work("build", plan,
                entry.nativeBody.entity().getServerWorld().getRegistryKey().getValue().toString(), origin.getX(), origin.getY(), origin.getZ(), turns, List.of()));
        entry.goalProgress = "Starting: " + blueprint.name();
        if (entry.runtimeSettings.playstyleProfile().isIdleObserving()) {
            entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(PlaystyleProfile.OPTIMAL_SURVIVAL);
        }
        entry.manualHoldUntilTick = Long.MIN_VALUE;
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
        var bill = dev.kindred.core.construction.BillOfMaterials.of(entry.build.blocks(), entry.build.snapshot(entry.nativeBody.entity().getServerWorld()),
                BuildRunner.counts(entry.nativeBody.entity()));
        String missing = bill.complete() ? "" : "; short of " + bill.missing();
        String unsupported = bill.unsupported().isEmpty() ? "" : "; no way to place yet: " + String.join(", ", bill.unsupported());
        if (entry.snapshot().stopLatched()) nativeLifecycle(source, "resume", id);
        feedback(source, entry.data.displayName() + ": building " + blueprint.name() + " (" + SchematicLibrary.credit(blueprint) + ") at "
                + origin.toShortString() + missing + unsupported + ".");
        return 1;
    }

    /** How often a player with no goal looks to its base (M-01c), after finding nowhere to put one, after a stage ended short. */
    private static final int BASE_LOOK = 200, BASE_NO_SITE = 1200, BASE_RETRY = 6000;
    /** How far from its anchor a player looks for ground for a base, and how often a stage that ends short is tried. */
    private static final int BASE_RADIUS = 24, BASE_TRIES = 3;

    /**
     * A player's own base (M-01c), with the operator's leave and AI world edits on, when it has no goal, build or ranch
     * of its own: a stage it began, taken up again where it stands; otherwise the next stage {@link
     * dev.kindred.core.construction.BaseProgression} calls for, on the nearest flat open ground ({@link
     * dev.kindred.core.construction.BaseSite}) to where it first looked, or beside its last base. The stage is then a
     * build like any other, its blocks fetched through its needs (E-08c). Whether it started a build.
     */
    private boolean baseDecision(Entry entry, NativePlayerBody body) {
        // Not before the work it was at before a restart is read: a base begun first would clear it (F-13).
        if (!bases || !worldEditsEnabled() || entry.goal != dev.kindred.core.survival.SurvivalGoal.NONE || entry.build != null
                || entry.ranch != null || entry.field != null || entry.snapshot().stopLatched() || entry.workRead != WorkRead.TAKEN) return false;
        var player = body.entity();
        var world = player.getServerWorld();
        if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) return false;
        long now = server.getTicks();
        if (now < entry.baseCheckAt) return false;
        UUID id = entry.data.id();
        if (!basesLoaded(entry)) return false;
        entry.baseCheckAt = now + BASE_LOOK;

        String dimension = world.getRegistryKey().getValue().toString();
        var record = entry.bases;
        var last = record.built.isEmpty() ? null : record.built.get(record.built.size() - 1);
        if (last != null && !last.dimension().equals(dimension)) return false;
        if (last != null && !last.complete()) {
            return startBase(entry, last, "Back to its " + last.stage());
        }
        // What it built it keeps whole (E-31a): a stage with blocks gone or out of place (a chest broken, a wall blown
        // out) is built again on its own plan, which places only what is missing, its blocks fetched as any build's.
        for (var base : record.built) {
            if (!base.dimension().equals(dimension)) continue;
            int damage = baseDamage(world, base);
            if (damage > 0) {
                String why = "Repairing its " + base.stage() + ", " + damage + (damage == 1 ? " block" : " blocks") + " out of place";
                noteBase(entry, why);
                if (!startBase(entry, base, why)) return false;
                entry.baseRepair = base;
                return true;
            }
        }
        // A trading hall by its base once it keeps a villager worth a cell (E-45d), ahead of its next stage: one it
        // began taken up again, else sited on the nearest flat open ground to its base.
        if (last != null && last.complete()) {
            var hall = record.hall;
            if (hall != null && !hall.complete() && hall.dimension().equals(dimension)) return startHall(entry, hall, "Back to its trading hall");
            if (hall == null && keepsAVillager(entry, body)) {
                var before = footprint(last);
                var avoid = new java.util.ArrayList<dev.kindred.core.construction.BaseSite.Footprint>();
                for (var base : record.built) avoid.add(footprint(base));
                var site = dev.kindred.core.construction.BaseSite.choose(ground(world), (before.minX() + before.maxX()) / 2,
                        (before.minZ() + before.maxZ()) / 2, BASE_RADIUS, dev.kindred.core.construction.TradingHall.width(HALL_CELLS),
                        dev.kindred.core.construction.TradingHall.DEPTH, dev.kindred.core.construction.TradingHall.HEIGHT, avoid);
                if (site.isPresent()) {
                    record.hall = new PlayerMemoryStore.Hall(dimension, site.get().x(), site.get().y(), site.get().z(), HALL_CELLS,
                            styleOf(last).wood(), false);
                    memories.saveBases(id, record);
                    return startHall(entry, record.hall, "A villager worth keeping, and a cell to keep it in");
                }
                noteBase(entry, "No flat open ground for a trading hall within " + BASE_RADIUS + " blocks of its " + last.stage());
            }
        }
        var decision = dev.kindred.core.construction.BaseProgression.next(baseProgress(body, last));
        noteBase(entry, decision.why());
        if (decision.next().isEmpty()) return false;
        var stage = decision.next().get();
        if (record.anchor == null) {
            BlockPos home = entry.setup == null || entry.setup.home() == null ? player.getBlockPos()
                    : new BlockPos(entry.setup.home().position().x(), entry.setup.home().position().y(), entry.setup.home().position().z());
            record.anchor = new PlayerMemoryStore.Place(dimension, home.getX(), home.getY(), home.getZ());
        }
        int anchorX = record.anchor.x(), anchorZ = record.anchor.z();
        if (last != null) {
            var before = footprint(last);
            anchorX = (before.minX() + before.maxX()) / 2;
            anchorZ = (before.minZ() + before.maxZ()) / 2;
        }
        var avoid = new java.util.ArrayList<dev.kindred.core.construction.BaseSite.Footprint>();
        for (var base : record.built) avoid.add(footprint(base));
        if (record.hall != null && record.hall.dimension().equals(dimension)) avoid.add(footprint(record.hall));
        // Its style grown from its seed block, or the wood it has to hand, and its world (E-29a): this world's stage
        // is not the next world's.
        var style = entry.fixedBaseStyle != null ? entry.fixedBaseStyle : dev.kindred.core.construction.BuildStyle.grown(record.seed,
                woodAtHand(entry, body), world.getSeed() ^ id.getMostSignificantBits() ^ id.getLeastSignificantBits() * 31 + stage.ordinal());
        var shape = dev.kindred.core.construction.Houses.shape(stage, style.variant());
        int[] span = dev.kindred.core.construction.Houses.span(shape, style.turns());
        var plan = dev.kindred.core.construction.Houses.of(stage, style);
        var site = dev.kindred.core.construction.BaseSite.choose(ground(world), anchorX, anchorZ, BASE_RADIUS, span[0], span[1],
                height(plan), avoid);
        if (site.isEmpty()) {
            entry.baseCheckAt = now + BASE_NO_SITE;
            noteBase(entry, "No flat open ground for a " + stage.noun + " within " + BASE_RADIUS + " blocks of " + anchorX + ", " + anchorZ);
            return false;
        }
        var origin = dev.kindred.core.construction.Houses.originFor(site.get(), shape, style.turns());
        var base = new PlayerMemoryStore.Base(stage.noun, dimension, origin.x(), origin.y(), origin.z(), false,
                style.seed().isEmpty() ? null : style.seed(), style.wood(), style.variant(), style.turns());
        record.built.add(base);
        memories.saveBases(id, record);
        return startBase(entry, base, decision.why());
    }

    /**
     * Whether the player's record of its bases is read; if not, its reading begun, off the game thread. Its base (M-01c)
     * and its trading hall's villagers (E-45e) wait on it.
     */
    private boolean basesLoaded(Entry entry) {
        if (entry.bases != null) return true;
        if (entry.basesLoading) return false;
        entry.basesLoading = true;
        UUID id = entry.data.id();
        memories.loadBases(id, entry.data.displayName()).whenComplete((loaded, failure) -> server.execute(() -> {
            if (!running || entries.get(id) != entry) return;
            entry.bases = loaded != null ? loaded : new PlayerMemoryStore.Bases();
            // A record it cannot read as one of its stages (an edited file, a stage since renamed) is left out.
            entry.bases.built.removeIf(base -> base == null || base.dimension() == null || stageNamed(base.stage()) == null
                    || base.wood() != null && !dev.kindred.core.construction.BuildNeeds.WOODS.contains(base.wood())
                    || base.variant() < 0 || base.variant() > dev.kindred.core.construction.BuildStyle.VARIANTS);
            // A hall it cannot build again as recorded (an edited file) is left out, as a base is.
            var hall = entry.bases.hall;
            if (hall != null && (hall.dimension() == null || hall.wood() == null || !dev.kindred.core.construction.BuildNeeds.WOODS.contains(hall.wood())
                    || hall.cells() < dev.kindred.core.construction.TradingHall.MIN_CELLS || hall.cells() > dev.kindred.core.construction.TradingHall.MAX_CELLS)) {
                entry.bases.hall = null;
            }
            if (entry.seedBlock != null) entry.bases.seed = entry.seedBlock;
            entry.basesLoading = false;
        }));
        return false;
    }

    private boolean startBase(Entry entry, PlayerMemoryStore.Base base, String why) {
        var style = styleOf(base);
        if (startBuild(server.getCommandSource(), entry.data.id(), planOf(base), new BlockPos(base.x(), base.y(), base.z()), style.turns()) != 1) {
            return false;
        }
        entry.baseBuild = entry.build;
        var span = footprint(base);
        say(entry, why + ": a " + style.wood().replace('_', ' ') + " " + base.stage() + ".", "at " + span.minX() + ", " + base.y() + ", " + span.minZ());
        return true;
    }

    /** The cells of the trading hall a player builds of its own accord (E-45d). */
    static final int HALL_CELLS = 3;

    private boolean startHall(Entry entry, PlayerMemoryStore.Hall hall, String why) {
        var plan = dev.kindred.core.construction.TradingHall.of(hall.cells(), hall.wood(), "cobblestone");
        if (startBuild(server.getCommandSource(), entry.data.id(), plan, new BlockPos(hall.x(), hall.y(), hall.z()), 0) != 1) return false;
        entry.hallBuild = entry.build;
        noteBase(entry, why);
        say(entry, why + ": a trading hall of " + hall.cells() + " cells.", "at " + hall.x() + ", " + hall.y() + ", " + hall.z());
        return true;
    }

    /** Its trading hall's build has ended: whole, or after its last try as far as it goes, it stands. */
    private void hallBuilt(Entry entry, BuildRunner build) {
        entry.hallBuild = null;
        var record = entry.bases;
        if (record == null || record.hall == null) return;
        if (!build.complete() && ++entry.hallTries < BASE_TRIES) {
            entry.baseCheckAt = server.getTicks() + BASE_RETRY;
            noteBase(entry, "Its trading hall ended short (" + build.progress() + "); back to it later");
            return;
        }
        entry.hallTries = 0;
        record.hall = record.hall.finished();
        memories.saveBases(entry.data.id(), record);
        noteBase(entry, "Its trading hall stands" + (build.complete() ? "" : " as far as it goes (" + build.progress() + ")"));
    }

    /**
     * Whether it keeps a villager worth a cell of its own (E-45c's whom to keep): a librarian it rerolled and kept, or a
     * villager it has looked at whose offers are worth keeping to it as it stands.
     */
    private boolean keepsAVillager(Entry entry, NativePlayerBody body) {
        if (!entry.rerollKept.isEmpty()) return true;
        if (entry.villagers.isEmpty()) return false;
        var holding = Valuables.holding(body.entity(), lootWanted(entry));
        for (var villager : entry.villagers.values()) {
            if (dev.kindred.core.guide.Trades.worthKeeping(villager.offers(), holding)) return true;
        }
        return false;
    }

    /** The ground a trading hall covers. */
    private static dev.kindred.core.construction.BaseSite.Footprint footprint(PlayerMemoryStore.Hall hall) {
        return new dev.kindred.core.construction.BaseSite.Footprint(hall.x(), hall.z(),
                hall.x() + dev.kindred.core.construction.TradingHall.width(hall.cells()) - 1, hall.z() + dev.kindred.core.construction.TradingHall.DEPTH - 1);
    }

    /** The player's trading hall (E-45d), for fixtures. */
    Optional<PlayerMemoryStore.Hall> hall(UUID id) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        return entry == null || entry.bases == null ? Optional.empty() : Optional.ofNullable(entry.bases.hall);
    }

    /** The style a base was built in: its own, or for a base recorded before E-29a the first plan in oak. */
    private static dev.kindred.core.construction.BuildStyle styleOf(PlayerMemoryStore.Base base) {
        if (base.wood() == null) return dev.kindred.core.construction.BuildStyle.CLASSIC;
        return new dev.kindred.core.construction.BuildStyle(base.seed(), base.wood(), "cobblestone", base.variant(), base.turns());
    }

    /** A base's plan, in its own style. */
    private static dev.kindred.core.construction.Blueprint planOf(PlayerMemoryStore.Base base) {
        return dev.kindred.core.construction.Houses.of(stageOf(base), styleOf(base));
    }

    /** A base's blocks as the world holds them: its plan turned and placed at its origin. */
    private static List<dev.kindred.core.construction.Blueprint.Block> placedOf(PlayerMemoryStore.Base base) {
        return planOf(base).placed(new dev.kindred.core.construction.Pos(base.x(), base.y(), base.z()), styleOf(base).turns());
    }

    /** The ground a base covers. */
    private static dev.kindred.core.construction.BaseSite.Footprint footprint(PlayerMemoryStore.Base base) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var block : placedOf(base)) {
            minX = Math.min(minX, block.at().x());
            minZ = Math.min(minZ, block.at().z());
            maxX = Math.max(maxX, block.at().x());
            maxZ = Math.max(maxZ, block.at().z());
        }
        return new dev.kindred.core.construction.BaseSite.Footprint(minX, minZ, maxX, maxZ);
    }

    /**
     * The wood a player has to hand for a base grown with no seed block (E-29a): the wood it carries most of, as
     * planks or logs, else that of the nearest tree it knows, else none.
     */
    private static String woodAtHand(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var count = new HashMap<String, Integer>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            String wood = id.endsWith("_planks") || id.endsWith("_log") ? dev.kindred.core.construction.BuildStyle.woodOf(id) : null;
            if (wood != null) count.merge(wood, stack.getCount() * (id.endsWith("_log") ? 4 : 1), Integer::sum);
        }
        var held = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
        if (held.isPresent()) return held.get();
        if (entry.campaign == null) return null;
        var world = player.getServerWorld();
        return entry.campaign.knowledge().nearest(world, WorldKnowledge.Kind.LOG, player.getBlockPos(), p -> true)
                .map(p -> dev.kindred.core.construction.BuildStyle.woodOf(Registries.BLOCK.getId(world.getBlockState(p).getBlock()).getPath()))
                .orElse(null);
    }

    /**
     * The seed block a player grows its builds from (E-29a), as given: a block it builds round, its wood for the
     * walls' trim if it is wood. It takes effect for the next stage it builds. Null clears it.
     */
    int setSeedBlock(ServerCommandSource source, UUID id, String block) {
        requireThread();
        if (id == null) id = defaultBotId();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        String seed = null;
        if (block != null) {
            var parsed = Identifier.tryParse(block.contains(":") ? block : "minecraft:" + block);
            if (parsed == null || !Registries.BLOCK.containsId(parsed)) return reject(source, "No such block: " + block);
            seed = parsed.toString();
        }
        entry.seedBlock = seed;
        if (entry.bases != null) {
            entry.bases.seed = seed;
            memories.saveBases(entry.data.id(), entry.bases);
        }
        String wood = dev.kindred.core.construction.BuildStyle.woodOf(seed);
        feedback(source, seed == null ? entry.data.displayName() + " grows its builds from the wood it has to hand."
                : entry.data.displayName() + " grows its next builds from " + seed + (wood == null ? ", in the wood it has to hand." : ", in " + wood.replace('_', ' ') + "."));
        return 1;
    }

    /** A fixture's hook: every base this player begins from now on is built in {@code style}, not grown. */
    void fixBaseStyle(UUID id, dev.kindred.core.construction.BuildStyle style) {
        requireThread();
        Entry entry = entries.get(id);
        if (entry != null) entry.fixedBaseStyle = style;
    }

    /** A base's build has ended: whole, or after its last try as far as it goes, it is the player's base now. */
    private void baseBuilt(Entry entry, BuildRunner build) {
        entry.baseBuild = null;
        var repaired = entry.baseRepair;
        entry.baseRepair = null;
        if (repaired != null) {
            // A repair that ends short is tried again later, never at once: what it lacks may come with time.
            if (!build.complete()) entry.baseCheckAt = server.getTicks() + BASE_RETRY;
            noteBase(entry, build.complete() ? "Its " + repaired.stage() + " is whole again"
                    : "Its " + repaired.stage() + "'s repair ended short (" + build.progress() + "); back to it later");
            return;
        }
        var record = entry.bases;
        if (record == null || record.built.isEmpty()) return;
        int at = record.built.size() - 1;
        var last = record.built.get(at);
        if (!build.complete() && ++entry.baseTries < BASE_TRIES) {
            entry.baseCheckAt = server.getTicks() + BASE_RETRY;
            noteBase(entry, "Its " + last.stage() + " ended short (" + build.progress() + "); back to it later");
            return;
        }
        entry.baseTries = 0;
        // As far as it goes, it is its base now; what it still lacks is a repair, tried later rather than at once.
        if (!build.complete()) entry.baseCheckAt = server.getTicks() + BASE_RETRY;
        record.built.set(at, last.finished());
        memories.saveBases(entry.data.id(), record);
        noteBase(entry, "Its " + last.stage() + " stands" + (build.complete() ? "" : " as far as it goes (" + build.progress() + ")"));
    }

    private static final int FOOD_LOOKS = 3, FOOD_RETRY = 6000, SLEEP_RETRY = 600, BED_REACH = 96;

    /**
     * What keeps the player alive, ahead of its ranch, build or base (F-25c9), as {@link dev.kindred.core.survival.LifeFirst}
     * weighs it: at night, to a bed of its own and to sleep there, which sets its spawn; when hunger is low, food until
     * it has enough to last, hunted as the goal hunts and looked for a few walks out before it gives up for a while.
     * Empty when nothing comes before its work.
     */
    private List<NativeTaskCandidate> lifeCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        long now = server.getTicks();
        long time = world.getTimeOfDay() % 24000L;
        boolean sleepTime = world.isNight() || world.isThundering() || time >= 12542L && time <= 23460L;
        BlockPos bed = player.isSleeping() ? player.getSleepingPosition().orElse(null)
                : sleepTime && now >= entry.sleepRetryAt ? nearestOwnBed(entry, world, player.getBlockPos()) : null;
        int hunger = player.getHungerManager().getFoodLevel();
        int carried = foodCarried(player);
        var decision = dev.kindred.core.survival.LifeFirst.next(new dev.kindred.core.survival.LifeFirst.State(hunger, carried, sleepTime,
                bed != null, entry.gettingFood, now < entry.foodSoughtUntil));
        if (decision.need().isEmpty()) {
            if (entry.gettingFood) {
                entry.gettingFood = false;
                entry.foodLooks = 0;
                noteLife(entry, "Food enough to last: " + (hunger + carried) + " in its body and its pack");
            }
            return List.of();
        }
        noteLife(entry, decision.why());
        PortalRunCampaign.Task task;
        if (decision.need().get() == dev.kindred.core.survival.LifeFirst.Need.SLEEP) {
            if (player.isSleeping()) task = new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Asleep in its bed");
            else if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(bed)) > 3.5 * 3.5) task = PortalRunCampaign.go(bed, 2.0, false, "To its bed for the night");
            else task = new PortalRunCampaign.Task("native_sleep", Map.of("x", String.valueOf(bed.getX()), "y", String.valueOf(bed.getY()),
                    "z", String.valueOf(bed.getZ())), "Sleep in its own bed");
        } else {
            entry.gettingFood = true;
            if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
            entry.campaign.policy(edits);
            // Never the kind of animal its ranch is penning: those are its pair to breed.
            var ranch = entry.ranch;
            var found = entry.campaign.huntFood(body, animal -> ranch != null && dev.kindred.core.survival.Ranch.Kind.of(
                    net.minecraft.registry.Registries.ENTITY_TYPE.getId(animal.getType()).toString()).filter(k -> k == ranch.ranch.kind()).isPresent());
            if (found.isEmpty() && entry.foodLooks < FOOD_LOOKS) found = entry.campaign.lookFor(body, "food");
            if (found.isEmpty()) {
                entry.gettingFood = false;
                entry.foodLooks = 0;
                entry.foodSoughtUntil = now + FOOD_RETRY;
                noteLife(entry, "No food to be had about here for now: back to its work, and it looks again later");
                return List.of();
            }
            task = found.get();
        }
        UUID id = UUID.nameUUIDFromBytes(("life:" + entry.data.id() + ":" + task.skill() + ":" + new java.util.TreeMap<>(task.args()))
                .getBytes(StandardCharsets.UTF_8));
        entry.lifeTaskId = id;
        entry.lifeTask = task;
        // Its hunt is the goal's own, so the goal's record of how its steps went keeps it from the same dead end again.
        if (decision.need().get() == dev.kindred.core.survival.LifeFirst.Need.FOOD) {
            entry.campaignTasks.clear();
            entry.campaignTasks.put(id, task);
        }
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(id, dev.kindred.core.TaskPreferences.Priority.PROJECT, decision.weight(),
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        return List.of(new NativeTaskCandidate(candidate, task.skill(), task.args()));
    }

    /** The food points of the safe food it carries, as it would eat it. */
    private static int foodCarried(ServerPlayerEntity player) {
        int points = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (!isEligibleFood(stack, true, false)) continue;
            points += stack.get(DataComponentTypes.FOOD).nutrition() * stack.getCount();
        }
        return points;
    }

    /** The nearest bed of its own bases in this world, free and within a walk of {@code from}, or null (F-25c9). */
    private static BlockPos nearestOwnBed(Entry entry, ServerWorld world, BlockPos from) {
        BlockPos best = null;
        for (BlockPos bed : ownBeds(entry, world)) {
            if (!world.isChunkLoaded(bed)) continue;
            var state = world.getBlockState(bed);
            if (!state.isIn(BlockTags.BEDS) || state.get(net.minecraft.block.BedBlock.OCCUPIED)) continue;
            double d = bed.getSquaredDistance(from);
            if (d > BED_REACH * BED_REACH) continue;
            if (best == null || d < best.getSquaredDistance(from)) best = bed;
        }
        return best;
    }

    /** Whether {@code at} is either half of a bed of its own bases. */
    private static boolean ownBed(Entry entry, ServerWorld world, BlockPos at) {
        var state = world.getBlockState(at);
        if (!state.isIn(BlockTags.BEDS)) return false;
        BlockPos head = state.get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD ? at
                : at.offset(state.get(net.minecraft.block.BedBlock.FACING));
        return ownBeds(entry, world).contains(head);
    }

    /** Where the heads of the beds of its bases in this world stand, by their plans. */
    private static List<BlockPos> ownBeds(Entry entry, ServerWorld world) {
        if (entry.bases == null) return List.of();
        String dimension = world.getRegistryKey().getValue().toString();
        var beds = new java.util.ArrayList<BlockPos>();
        for (var base : entry.bases.built) {
            if (!base.dimension().equals(dimension)) continue;
            for (var block : placedOf(base)) {
                if (block.spec().block().endsWith("_bed") && "head".equals(block.spec().property("part"))) beds.add(BuildRunner.blockPos(block.at()));
            }
        }
        return beds;
    }

    private static void noteLife(Entry entry, String why) {
        if (why.equals(entry.lifeNote)) return;
        entry.lifeNote = why;
        KindredMod.LOGGER.info("{} life: {}", entry.data.displayName(), why);
    }

    /** What the player last said of what keeps it alive (F-25c9), for fixtures and status. */
    String lifeNote(UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        return entry == null ? "" : entry.lifeNote;
    }

    /**
     * How many of a base's blocks are gone or out of place, as the build compares them (a door open, a furnace lit and
     * the like count as in place); none when any of it is out of loaded ground.
     */
    private static int baseDamage(ServerWorld world, PlayerMemoryStore.Base base) {
        int damage = 0;
        for (var block : placedOf(base)) {
            BlockPos at = BuildRunner.blockPos(block.at());
            if (!world.isChunkLoaded(at)) return 0;
            if (!BuildRunner.spec(world.getBlockState(at)).sameAs(block.spec())) damage++;
        }
        return damage;
    }

    private static void noteBase(Entry entry, String why) {
        if (why.equals(entry.baseNote)) return;
        entry.baseNote = why;
        KindredMod.LOGGER.info("{} base: {}", entry.data.displayName(), why);
    }

    private static dev.kindred.core.construction.Houses.Stage stageOf(PlayerMemoryStore.Base base) {
        return stageNamed(base.stage());
    }

    /** The stage a base record names, or null when it names none. */
    private static dev.kindred.core.construction.Houses.Stage stageNamed(String noun) {
        if (noun == null) return null;
        for (var stage : dev.kindred.core.construction.Houses.Stage.values()) if (stage.noun.equals(noun)) return stage;
        return null;
    }

    /** A plan's height over its floor, roof included. */
    private static int height(dev.kindred.core.construction.Blueprint plan) {
        int top = 0;
        for (var b : plan.blocks()) top = Math.max(top, b.at().y());
        return top + 1;
    }

    /**
     * Where the player stands toward its next base: its tools, whether it has an enchanting table's makings, how much
     * room its base's chests have left, and the days played.
     */
    private static dev.kindred.core.construction.BaseProgression.Progress baseProgress(NativePlayerBody body, PlayerMemoryStore.Base last) {
        var player = body.entity();
        var inventory = player.getInventory();
        boolean stone = false, iron = false, table = false;
        int diamonds = 0, obsidian = 0, books = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isOf(Items.STONE_PICKAXE)) stone = true;
            if (stack.isOf(Items.IRON_PICKAXE) || stack.isOf(Items.DIAMOND_PICKAXE) || stack.isOf(Items.NETHERITE_PICKAXE)) stone = iron = true;
            if (stack.isOf(Items.ENCHANTING_TABLE)) table = true;
            if (stack.isOf(Items.DIAMOND)) diamonds += stack.getCount();
            if (stack.isOf(Items.OBSIDIAN)) obsidian += stack.getCount();
            if (stack.isOf(Items.BOOK)) books += stack.getCount();
        }
        int free = 0, slots = 0;
        var world = player.getServerWorld();
        if (last != null) {
            for (var block : placedOf(last)) {
                if (!block.spec().block().equals("minecraft:chest")) continue;
                BlockPos pos = BuildRunner.blockPos(block.at());
                if (!world.isChunkLoaded(pos) || !(world.getBlockEntity(pos) instanceof net.minecraft.block.entity.ChestBlockEntity chest)) continue;
                slots += chest.size();
                for (int slot = 0; slot < chest.size(); slot++) if (chest.getStack(slot).isEmpty()) free++;
            }
        }
        return new dev.kindred.core.construction.BaseProgression.Progress(last == null ? Optional.empty() : Optional.of(stageOf(last)),
                stone, iron, table || diamonds >= 2 && obsidian >= 4 && books >= 1, free, slots, Math.max(0, world.getTimeOfDay() / 24000));
    }

    /** The ground round a site as a player sees it, column by column, each read once. */
    private static dev.kindred.core.construction.BaseSite.Ground ground(ServerWorld world) {
        var seen = new HashMap<Long, Optional<dev.kindred.core.construction.BaseSite.Column>>();
        return (x, z) -> seen.computeIfAbsent(BlockPos.asLong(x, 0, z), key -> column(world, x, z));
    }

    /**
     * One column: the top block that blocks movement (leaves aside), whether it is natural ground to build on (dirt,
     * grass, stone, sand, gravel; not water, a log, a container or another's build), and how many open cells are over
     * it before anything that is not air or a plant in the way.
     */
    private static Optional<dev.kindred.core.construction.BaseSite.Column> column(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return Optional.empty();
        int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (top < world.getBottomY()) return Optional.empty();
        var state = world.getBlockState(new BlockPos(x, top, z));
        boolean firm = !state.hasBlockEntity() && state.getFluidState().isEmpty() && (state.isIn(net.minecraft.registry.tag.BlockTags.DIRT)
                || state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD) || state.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                || state.isOf(Blocks.GRAVEL));
        int open = 0;
        while (open < 24) {
            var above = world.getBlockState(new BlockPos(x, top + 1 + open, z));
            if (!(above.isAir() || above.isReplaceable() && above.getFluidState().isEmpty())) break;
            open++;
        }
        return Optional.of(new dev.kindred.core.construction.BaseSite.Column(top, firm, open));
    }

    /** The base a player has or is building (M-01c), if any. */
    Optional<PlayerMemoryStore.Base> currentBase(UUID id) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.bases == null || entry.bases.built.isEmpty()) return Optional.empty();
        return Optional.of(entry.bases.built.get(entry.bases.built.size() - 1));
    }

    /** What a player last decided about its base (M-01c). */
    String baseNote(UUID id) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        return entry == null ? "" : entry.baseNote;
    }

    /**
     * Starts a ranch (M-01a): a seven-by-seven pen of the wood the player has most of, its north-west post at
     * {@code origin}, and a pair of {@code kind} led into it and bred, from the player's own wood and the animals' own
     * food. Null stops it; what is built stays, and the gate stays as it is.
     */
    int startRanch(ServerCommandSource source, UUID id, BlockPos origin, dev.kindred.core.survival.Ranch.Kind kind) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeState == null || entry.nativeBody == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (kind == null) {
            entry.ranch = null;
            entry.buildTasks.clear();
            entry.goalProgress = "";
            entry.cachedRoutineCandidates = List.of();
            entry.routineRefreshPending = true;
            feedback(source, entry.data.displayName() + ": the ranch is stopped; what is built stays.");
            return 1;
        }
        if (!worldEditsEnabled()) return reject(source, "AI world edits are disabled on this server; an operator must enable them first.");
        String wood = RanchRunner.woodOf(entry.nativeBody.entity());
        if (wood == null) return reject(source, entry.data.displayName() + " carries no wood for a pen's fences.");
        entry.build = null;
        keepWork(entry, null);
        entry.ranch = new RanchRunner(origin, kind, wood);
        entry.goalProgress = "Ranch: starting";
        if (entry.runtimeSettings.playstyleProfile().isIdleObserving()) {
            entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(PlaystyleProfile.OPTIMAL_SURVIVAL);
        }
        entry.manualHoldUntilTick = Long.MIN_VALUE;
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
        if (entry.snapshot().stopLatched()) nativeLifecycle(source, "resume", id);
        feedback(source, entry.data.displayName() + ": a ranch for " + kind.noun() + "s, a " + wood + " pen at " + origin.toShortString() + ".");
        return 1;
    }

    /**
     * Starts the ranch of a pasture ward (M-06a): the ward of the keep whose plan stands at {@code keep}, as
     * {@code /aiplayer build pastureward} builds it. Its doorway is opened, a pair of {@code kind} led in through it into
     * the ward's pen and bred there, and the doorway shut behind them with the blocks it dug out of it. Stopped as a
     * ranch is.
     */
    int startPasture(ServerCommandSource source, UUID id, BlockPos keep, dev.kindred.core.survival.Ranch.Kind kind) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeState == null || entry.nativeBody == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (!worldEditsEnabled()) return reject(source, "AI world edits are disabled on this server; an operator must enable them first.");
        var style = new dev.kindred.core.construction.BuildStyle("", "oak", "cobblestone", 0, 0);
        var pen = dev.kindred.core.construction.Compounds.pen(style);
        BlockPos origin = keep.add(pen.x(), pen.y(), pen.z());
        entry.build = null;
        entry.field = null;
        keepWork(entry, null);
        entry.ranch = new RanchRunner(origin, kind, style.wood(), dev.kindred.core.construction.Compounds.doorwayShut(style), keep);
        entry.goalProgress = "Ranch: starting";
        if (entry.runtimeSettings.playstyleProfile().isIdleObserving()) {
            entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(PlaystyleProfile.OPTIMAL_SURVIVAL);
        }
        entry.manualHoldUntilTick = Long.MIN_VALUE;
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
        if (entry.snapshot().stopLatched()) nativeLifecycle(source, "resume", id);
        feedback(source, entry.data.displayName() + ": " + kind.noun() + "s brought into the pasture ward's pen at " + origin.toShortString()
                + " by its doorway.");
        return 1;
    }

    /** What the ranch is doing, if there is one. */
    Optional<String> ranchStatus(UUID id) {
        Entry entry = entries.get(id);
        return entry == null || entry.ranch == null ? Optional.empty() : Optional.of(entry.ranch.progress());
    }

    /**
     * Starts the field of a farm ward (M-06a): the ward of the keep whose plan stands at {@code keep}, as
     * {@code /aiplayer build farmward} builds it, its channel watered from a bucket the player holds, its soil tilled
     * with its own hoe and sown with its own seeds. Null stops it; what is tilled and sown stays.
     */
    int startField(ServerCommandSource source, UUID id, BlockPos keep) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeState == null || entry.nativeBody == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (keep == null) {
            entry.field = null;
            entry.buildTasks.clear();
            keepWork(entry, null);
            entry.goalProgress = "";
            entry.cachedRoutineCandidates = List.of();
            entry.routineRefreshPending = true;
            feedback(source, entry.data.displayName() + ": the field is stopped; what is tilled and sown stays.");
            return 1;
        }
        if (!worldEditsEnabled()) return reject(source, "AI world edits are disabled on this server; an operator must enable them first.");
        var plan = dev.kindred.core.construction.Compounds.field(new dev.kindred.core.construction.BuildStyle("", "oak", "cobblestone", 0, 0));
        BlockPos corner = keep.add(plan.west(), -1, plan.north());
        entry.build = null;
        entry.ranch = null;
        entry.field = fieldRunner(keep);
        keepWork(entry, new PlayerMemoryStore.Work("field", null, entry.nativeBody.entity().getServerWorld().getRegistryKey().getValue().toString(),
                keep.getX(), keep.getY(), keep.getZ(), 0, List.of()));
        entry.goalProgress = "Field: starting";
        if (entry.runtimeSettings.playstyleProfile().isIdleObserving()) {
            entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(PlaystyleProfile.OPTIMAL_SURVIVAL);
        }
        entry.manualHoldUntilTick = Long.MIN_VALUE;
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
        if (entry.snapshot().stopLatched()) nativeLifecycle(source, "resume", id);
        feedback(source, entry.data.displayName() + ": the farm ward's field, " + plan.size() + " square from " + corner.toShortString() + ".");
        return 1;
    }

    /** The field of the farm ward of the keep whose plan stands at {@code keep}. */
    private static FieldRunner fieldRunner(BlockPos keep) {
        var plan = dev.kindred.core.construction.Compounds.field(new dev.kindred.core.construction.BuildStyle("", "oak", "cobblestone", 0, 0));
        return new FieldRunner(keep.add(plan.west(), -1, plan.north()), plan.size());
    }

    /** Keeps {@code work} as what the player is at, for a restart (F-13); null keeps nothing, and what was kept is cleared. */
    private void keepWork(Entry entry, PlayerMemoryStore.Work work) {
        entry.keptWork = work;
        if (work == null) {
            entry.keptScaffold = Set.of();
            memories.clearWork(entry.data.id(), entry.data.displayName());
        } else {
            memories.saveWork(entry.data.id(), entry.data.displayName(), work);
        }
    }

    /** Whether what a player was at before a restart has been read, and taken up (F-13). */
    private enum WorkRead { UNREAD, READING, READ, TAKEN }

    /**
     * The work an operator set it to before a restart (F-13), read once when it first comes into play and taken up
     * again if it has been set to nothing else since: a build at its origin, the scaffold it had put up its own to take
     * down; a farm ward's field. What it built, tilled or sown is read from the world, so nothing is placed or spent
     * twice. Only in the world the work is in; elsewhere it waits until the player is there. Taking it up neither
     * resumes the player nor changes its playstyle: a stopped player stays stopped until its administrator resumes it.
     */
    private void takeUpWork(Entry entry, NativePlayerBody body) {
        if (entry.workRead == WorkRead.TAKEN || entry.workRead == WorkRead.READING) return;
        UUID id = entry.data.id();
        String name = entry.data.displayName();
        if (entry.workRead == WorkRead.UNREAD) {
            entry.workRead = WorkRead.READING;
            memories.loadWork(id, name).whenComplete((work, failure) -> server.execute(() -> {
                if (!running || entries.get(id) != entry) return;
                entry.savedWork = work;
                entry.workRead = WorkRead.READ;
            }));
            return;
        }
        var work = entry.savedWork;
        if (work == null || entry.build != null || entry.ranch != null || entry.field != null) {
            entry.workRead = WorkRead.TAKEN;
            entry.savedWork = null;
            return;
        }
        // Taken up in another world, a build would go up there.
        if (!body.entity().getServerWorld().getRegistryKey().getValue().toString().equals(work.dimension())) return;
        entry.workRead = WorkRead.TAKEN;
        entry.savedWork = null;
        BlockPos at = new BlockPos(work.x(), work.y(), work.z());
        if ("field".equals(work.kind())) {
            entry.field = fieldRunner(at);
            entry.keptWork = work;
            entry.goalProgress = "Field: taken up again";
            entry.cachedRoutineCandidates = List.of();
            entry.routineRefreshPending = true;
            KindredMod.LOGGER.info("{} takes up its field again, by the keep at {}, as it was before the restart", name, at.toShortString());
            return;
        }
        if (!"build".equals(work.kind()) || work.plan() == null) {
            KindredMod.LOGGER.warn("{} was at work it cannot take up again ({}); none taken up", name, work.kind());
            return;
        }
        dev.kindred.core.construction.Blueprint generated;
        try {
            generated = generated(work.plan());
        } catch (IllegalArgumentException invalid) {
            KindredMod.LOGGER.warn("{} was building {}, which is no blueprint now; none taken up", name, work.plan());
            return;
        }
        if (generated != null) {
            takeUpBuild(entry, work, generated);
            return;
        }
        schematics.load(work.plan()).whenComplete((loaded, failure) -> server.execute(() -> {
            if (!running || entries.get(id) != entry || entry.build != null || entry.ranch != null || entry.field != null) return;
            if (failure != null || loaded == null) {
                KindredMod.LOGGER.warn("{} was building the schematic {}, which could not be read again; none taken up", name, work.plan());
                return;
            }
            takeUpBuild(entry, work, loaded.blueprint());
        }));
    }

    private void takeUpBuild(Entry entry, PlayerMemoryStore.Work work, dev.kindred.core.construction.Blueprint blueprint) {
        BlockPos origin = new BlockPos(work.x(), work.y(), work.z());
        entry.build = new BuildRunner(blueprint, origin, work.turns());
        var scaffold = new HashSet<dev.kindred.core.construction.Pos>();
        if (work.scaffold() != null) {
            for (int[] p : work.scaffold()) if (p != null && p.length == 3) scaffold.add(new dev.kindred.core.construction.Pos(p[0], p[1], p[2]));
        }
        entry.build.restoreScaffold(scaffold);
        entry.keptWork = work;
        entry.keptScaffold = Set.copyOf(scaffold);
        entry.goalProgress = "Taken up again: " + blueprint.name();
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
        KindredMod.LOGGER.info("{} takes up its build of {} again at {}, as it was before the restart, {} scaffold blocks its own",
                entry.data.displayName(), blueprint.name(), origin.toShortString(), scaffold.size());
    }

    /** The generator's blueprint a build command names ({@code hut-5x5x3}, {@code walltower-3}), or null for a schematic. */
    static dev.kindred.core.construction.Blueprint generated(String name) {
        var parts = name.split("-", 2);
        if (!dev.kindred.core.construction.Generators.NAMES.contains(parts[0])) return null;
        var sizes = new ArrayList<Integer>();
        if (parts.length > 1) for (String size : parts[1].split("x")) sizes.add(Integer.parseInt(size));
        return dev.kindred.core.construction.Generators.named(parts[0], sizes);
    }

    /** What the field is doing, if there is one. */
    Optional<String> fieldStatus(UUID id) {
        Entry entry = entries.get(id);
        return entry == null || entry.field == null ? Optional.empty() : Optional.of(entry.field.progress());
    }

    /** The schematics operators put in {@code config/kindred/schematics} (M-05). */
    private final SchematicLibrary schematics = new SchematicLibrary(
            net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("kindred").resolve("schematics"));

    SchematicLibrary schematics() {
        return schematics;
    }

    /**
     * Starts a build of the schematic {@code name} from the library (M-05): read off the game thread, then started as
     * {@link #startBuild} starts any blueprint. What the reading left out is said first.
     */
    int startSchematicBuild(ServerCommandSource source, UUID id, String name, BlockPos origin, int turns) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (!worldEditsEnabled()) return reject(source, "AI world edits are disabled on this server; an operator must enable them first.");
        schematics.load(name).whenComplete((loaded, failure) -> server.execute(() -> {
            if (!running || entries.get(id) != entry) return;
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                reject(source, cause instanceof IllegalArgumentException ? cause.getMessage() : "The schematic could not be read.");
                return;
            }
            for (String note : loaded.notes()) feedback(source, name + ": " + note);
            startBuild(source, id, loaded.blueprint(), origin, turns, name);
        }));
        feedback(source, "Reading " + name + " from " + schematics.directory() + "...");
        return 1;
    }

    /** The blueprints there are to build: the generators and the library's schematics. */
    int listBlueprints(ServerCommandSource source) {
        requireThread();
        schematics.names().whenComplete((names, failure) -> server.execute(() -> feedback(source,
                "Generators: hut (hut-<width>x<depth>x<walls>), tower (tower-<width>x<height>), pyramid (pyramid-<base>), lighthouse (lighthouse-<height>), pen (pen-<width>x<depth>), and a base's three stages: cabin, house, manor. Schematics in " + schematics.directory() + ": "
                        + (names == null || names.isEmpty() ? "none" : String.join(", ", names)))));
        return 1;
    }

    /** Walks of its own that found no way, since the player came into play; -1 for no such player. */
    int walksFailed(UUID id) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        return entry == null ? -1 : entry.walksFailed;
    }

    /** How many trips the build in progress has made for its shortfall (E-08c); -1 with no build. */
    int buildTrips(UUID id) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        return entry == null || entry.build == null ? -1 : entry.build.trips();
    }

    /** The build a player is on, if any, as a line of progress. */
    Optional<String> buildStatus(UUID id) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        return entry == null || entry.build == null ? Optional.empty() : Optional.of(entry.build.progress());
    }

    private List<NativeTaskCandidate> evaluateNativeCandidates(Entry entry, NativePlayerBody body) {
        bindBodyBlockScanBudget(body);
        var list = new java.util.ArrayList<NativeTaskCandidate>();
        list.addAll(evaluateEssentialSurvivalCandidates(entry, body, true, true));
        list.addAll(evaluateScheduledRoutineCandidates(entry, body));
        return list;
    }

    private BlockPos findPlacementSpot(ServerPlayerEntity player, WorkSetup setup, EditPolicy.ServerPolicy policy, String blockId) {
        var world = player.getServerWorld();
        BlockPos playerPos = player.getBlockPos();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            if (!canScanBlock()) break;
            recordBlockScan();
            BlockPos pos = playerPos.offset(dir);
            if (!world.isChunkLoaded(pos) || !world.isChunkLoaded(pos.down()) || !world.isInBuildLimit(pos)) continue;
            if (!world.getBlockState(pos).isReplaceable()) continue;
            if (!world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) continue;
            if (player.getEyePos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 16.0) continue;
            var point = new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ());
            var decision = EditPolicy.evaluate(setup, EditPolicy.Kind.PLACE,
                    world.getRegistryKey().getValue().toString(), point, blockId, policy);
            if (decision.allowed()) {
                return pos;
            }
        }
        return null;
    }

    /** Returns exactly one durable placement step for a complete owned-obsidian portal frame. */
    private Optional<Map<String, String>> findPortalFrameAssemblyStep(Entry entry, NativePlayerBody body, int slot) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (entry.setup == null || entry.setup.area() == null || slot < 0 || slot > 8) return Optional.empty();

        // Keep the frame site stable while the repeated one-block native_place tasks assemble it.
        if (entry.portalFrameInterior == null) {
            BlockPos p = player.getBlockPos();
            entry.portalFrameInterior = new BlockPos(p.getX() - 1, p.getY() + 1, p.getZ() + 1).toImmutable();
        }
        BlockPos interior = entry.portalFrameInterior;
        int x = interior.getX();
        int y = interior.getY();
        int z = interior.getZ();
        int baseY = y - 1;
        BlockPos leftBase = new BlockPos(x - 1, baseY, z);
        BlockPos rightBase = new BlockPos(x + 2, baseY, z);
        record Piece(BlockPos destination, BlockPos clickTarget, Direction side) { }
        List<Piece> pieces = new ArrayList<>();
        // Bottom row and corner footings.
        pieces.add(new Piece(new BlockPos(x, baseY, z), new BlockPos(x, baseY, z), Direction.UP));
        pieces.add(new Piece(new BlockPos(x + 1, baseY, z), new BlockPos(x + 1, baseY, z), Direction.UP));
        pieces.add(new Piece(leftBase, leftBase, Direction.UP));
        pieces.add(new Piece(rightBase, rightBase, Direction.UP));
        // Vertical sides, including top corner supports needed to place the top row without scaffolding.
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos left = new BlockPos(x - 1, baseY + dy, z);
            BlockPos right = new BlockPos(x + 2, baseY + dy, z);
            pieces.add(new Piece(left, left, Direction.UP));
            pieces.add(new Piece(right, right, Direction.UP));
        }
        // Place top interior blocks by clicking the adjacent top corner supports.
        pieces.add(new Piece(new BlockPos(x, baseY + 4, z), new BlockPos(x - 1, baseY + 4, z), Direction.EAST));
        pieces.add(new Piece(new BlockPos(x + 1, baseY + 4, z), new BlockPos(x + 2, baseY + 4, z), Direction.WEST));

        int missing = 0;
        for (Piece piece : pieces) {
            if (!canScanBlock()) return Optional.empty();
            recordBlockScan();
            BlockPos pos = piece.destination();
            if (!entry.setup.area().contains(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))
                    || !world.isChunkLoaded(pos) || !world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) {
                entry.portalFrameInterior = null;
                return Optional.empty();
            }
            var state = world.getBlockState(pos);
            if (state.isOf(Blocks.OBSIDIAN)) continue;
            if (!state.isReplaceable()) {
                entry.portalFrameInterior = null;
                return Optional.empty();
            }
            missing++;
            var point = new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ());
            if (!EditPolicy.evaluate(entry.setup, EditPolicy.Kind.PLACE,
                    world.getRegistryKey().getValue().toString(), point, "minecraft:obsidian", edits).allowed()) {
                entry.portalFrameInterior = null;
                return Optional.empty();
            }
        }
        if (player.getInventory().getStack(slot).getCount() < missing) return Optional.empty();

        for (Piece piece : pieces) {
            if (world.getBlockState(piece.destination()).isOf(Blocks.OBSIDIAN)) continue;
            BlockPos destination = piece.destination();
            BlockPos click = piece.clickTarget();
            if (!world.isChunkLoaded(click) || player.getEyePos().squaredDistanceTo(
                    destination.getX() + 0.5, destination.getY() + 0.5, destination.getZ() + 0.5) > 25.0) continue;
            if (click.equals(destination)) {
                BlockPos support = destination.down();
                if (!world.isChunkLoaded(support) || !world.getBlockState(support).isSideSolidFullSquare(world, support, Direction.UP)) continue;
            } else if (!world.getBlockState(click).isOf(Blocks.OBSIDIAN)) {
                continue;
            }
            return Optional.of(Map.of(
                    "slot", String.valueOf(slot),
                    "x", String.valueOf(click.getX()), "y", String.valueOf(click.getY()), "z", String.valueOf(click.getZ()),
                    "side", piece.side().getName()));
        }
        return Optional.empty();
    }

    /**
     * Runs as the body dies, before vanilla drops its inventory: in-flight work stops through the shared barrier and
     * its durable record settles against the contents it actually changed. The drops stay where vanilla put them.
     */
    private void onNativeDeath(Entry entry, NativePlayerBody body) {
        if (entry.nativeBody != body || entry.awaitingRespawn) return;
        var player = body.entity();
        matches.fallen(player.getUuid()).ifPresent(this::announce);
        entry.awaitingRespawn = true;
        BlockPos deathPos = player.getBlockPos().toImmutable();
        teamCoordinator.handlePlayerDeath(entry.data.id());
        entry.hazardInterrupted = false;
        if (entry.runner != null && runnerBusy(entry)) {
            entry.runner.lease().ifPresent(entry.arbiter::cancel);
            entry.runner.abort("The player died");
            observeRunner(entry);
        }
        closeNativeAction(entry);
        if (entry.activity != null) entry.activity.stop();
        admission.release(entry.data.id());
        entry.currentTaskId = null;
        body.stopInputs();
        boolean killedByMob = player.getAttacker() instanceof net.minecraft.entity.mob.HostileEntity;
        if (entry.campaign != null) entry.campaign.died(deathPos, player.getServerWorld(), server.getTicks(), killedByMob,
                player.getDamageTracker().getDeathMessage().getString());
        entry.blocker = "Died at " + deathPos.toShortString() + "; respawning.";
        KindredMod.LOGGER.info("{} died at {}: {}", entry.data.displayName(), deathPos.toShortString(),
                player.getDamageTracker().getDeathMessage().getString());
    }

    /** After the exit portal's credits, the request home a client would make, and the runtime's state for the new entity. */
    private void returnFromCredits(Entry entry, NativePlayerBody body) {
        if (entry.reconciliationBlocked) return;
        if (entry.runner != null && runnerBusy(entry)) {
            entry.runner.lease().ifPresent(entry.arbiter::cancel);
            entry.runner.abort("Through the exit portal");
            observeRunner(entry);
        }
        closeNativeAction(entry);
        if (entry.activity != null) entry.activity.stop();
        admission.release(entry.data.id());
        entry.currentTaskId = null;
        try {
            body.returnFromCredits();
        } catch (RuntimeException failure) {
            nativeFailure(server.getCommandSource(), entry, "Native return from the End failed", failure);
            return;
        }
        entry.routineRefreshPending = true;
        entry.cachedRoutineCandidates = List.of();
        entry.cachedCraftingTablePos = null;
        entry.cachedFurnacePos = null;
        entry.lastTacticalObservation = null;
        entry.blocker = "Home from the End.";
        KindredMod.LOGGER.info("{} came home from the End after the credits, at {}", entry.data.displayName(), body.entity().getBlockPos().toShortString());
    }

    /**
     * Vanilla respawn once the dead body has lain for its last tick (vanilla removes it at twenty), about as long
     * as a person takes to press respawn. The world tolerates the swap mid-iteration, as it does portal travel.
     */
    private void respawnNative(Entry entry, NativePlayerBody body) {
        entry.awaitingRespawn = false;
        if (!body.connected() || body.entity().isAlive() || entry.reconciliationBlocked) return;
        try {
            body.respawn();
        } catch (RuntimeException failure) {
            nativeFailure(server.getCommandSource(), entry, "Native respawn failed", failure);
            return;
        }
        entry.routineRefreshPending = true;
        entry.cachedRoutineCandidates = List.of();
        entry.cachedCraftingTablePos = null;
        entry.cachedFurnacePos = null;
        entry.lastTacticalObservation = null;
        entry.blocker = "Respawned after dying.";
        KindredMod.LOGGER.info("{} respawned at {}", entry.data.displayName(), body.entity().getBlockPos().toShortString());
        persistNativeSaveReceipt(entry);
    }

    private void beforeNativeTick(Entry entry, NativePlayerBody body) {
        if (entry.nativeBody != body) { body.stopInputs(); return; }
        if (!body.connected()) { detachNative(entry, "Native connection closed; explicit reconnect is required."); return; }
        if (body.entity() != null && !body.entity().isAlive()) {
            onNativeDeath(entry, body);
            body.stopInputs();
            if (entry.awaitingRespawn && body.entity().deathTime >= 19) respawnNative(entry, body);
            return;
        }

        if (body.entity() != null) {
            applyDimensionTransit(entry, body);
            reconcileExpeditionArrival(entry);
            if (server.getTicks() % 10 == 0) {
                noteChestUse(entry, body.entity());
                storageAskLapsed(entry);
            }
        }

        if (attentionScheduler.currentTick() != server.getTicks()) {
            onServerTick(server);
        }

        boolean usable = running && ready && !entry.metadataFault && !entry.reconciliationBlocked
                && !entry.nativePending && entry.nativeState.phase() == StoredNativePlayer.Phase.ESTABLISHED;
        boolean busy = entry.runner != null && runnerBusy(entry);
        // A skill that runs on past its five-second slice (a long walk, obsidian, stone by hand) has the slice renewed
        // shortly before it lapses, while it is still admitted: the same cap, window and durable acknowledgement as
        // any slice. Without a renewal the permit lapses and the skill is halted, as before (F-11c).
        if (usable && busy && entry.runner.state() == SkillRunner.State.RUNNING && entry.activity != null
                && entry.activity.phase() == PaidActivitySession.Phase.ACTIVE && !entry.activity.renewing()
                && entry.activity.remaining().compareTo(RENEW_AHEAD) < 0
                && !entry.snapshot().stopLatched() && entry.ledgerState == LedgerCheckState.KNOWN_LEDGER_CLEAR
                && !entry.arbiter.cancellationPending() && !entry.hazardInterrupted
                && entry.nativeBody != null && entry.nativeBody.connected()) {
            entry.activity.renew(entry.activity.snapshot().period().endExclusive());
        }
        if (busy && (entry.activity == null || !entry.activity.paid())) usable = false;
        if (entry.activity != null && entry.activity.phase() == PaidActivitySession.Phase.BLOCKED && !entry.metadataFault) {
            nativeActivityFailure(entry, "Activity settlement is uncertain");
            usable = false;
        }

        if (usable && body.entity() != null) {
            takeUpWork(entry, body);
            refreshTacticalObservation(entry, server.getTicks());
            // A goal looks around as it goes, and so does a build, for the trees and stone its needs may send it to (E-08c).
            if (entry.goal != dev.kindred.core.survival.SurvivalGoal.NONE || entry.build != null) {
                if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
                entry.campaign.observe(body.entity());
            }
        }

        if (usable && !entry.snapshot().stopLatched() && entry.runtimeSettings.playstyleProfile().isIdleObserving()) {
            applyIdleObservation(entry, body, server.getTicks());
        }
        // Its social lane: what it sees and says, to the listeners its profile allows (SocialBrain).
        if (usable && body.entity() != null) {
            socials.deliverDue(entry.data.id(), now());
            CompanionSocials.Participant participant = socialParticipant(entry.data.id());
            if (participant != null) socials.tick(participant);
        }

        boolean creeperThreat = false;
        if (entry.lastTacticalObservation != null) {
            for (var mob : entry.lastTacticalObservation.observedMobs()) {
                if (mob.isCreeper() && (mob.isIgnited() || (mob.fuseTicksRemaining() >= 0 && mob.fuseTicksRemaining() <= 25))) {
                    creeperThreat = true;
                    break;
                }
            }
        }

        // Bounded danger observation while busy: interrupt through SkillRunner/ActionArbiter cancellation barrier
        // A goal task never keeps the player busy while a mob it could fight is attacking it.
        String campaignStop = usable && busy && entry.campaign != null && entry.activeCampaignTask != null
                ? entry.campaign.stopFor(body.entity(), entry.activeCampaignTask) : null;
        if (campaignStop != null) {
            entry.campaignFightInterrupt = true;
            entry.runner.lease().ifPresent(entry.arbiter::cancel);
            entry.runner.abort(campaignStop);
            body.stopInputs();
            observeRunner(entry);
            // Closing the action can stop the entry on the spot (an outcome that needs reconciling): nothing more this tick.
            if (entry.runner == null) return;
            busy = runnerBusy(entry);
        }

        // Hit hard by a player (E-41g): what it was doing gives way to its answer at once, a fall's clutch aside. It stays
        // owed until its answer is what runs: a task chosen just before the hit and still being admitted starts after it.
        if (entry.aggressor == null) {
            entry.aggressionInterrupt = false;
        } else if (busy && (aggressionTaskId(entry, entry.aggressor.who()).equals(entry.currentTaskId)
                || entry.aggressor.answer() == dev.kindred.core.tactics.FightOdds.Answer.DEFEND && "native_fight".equals(entry.skillId)
                && entry.aggressor.who().toString().equals(entry.skillArgs.get("target")))) {
            // Its answer is what runs: a getaway, or a fight with that attacker (a duel's own, defending as it goes).
            entry.aggressionInterrupt = false;
        } else if (usable && busy && entry.aggressionInterrupt && !"native_clutch".equals(entry.skillId)) {
            entry.aggressionInterrupt = false;
            entry.runner.lease().ifPresent(entry.arbiter::cancel);
            entry.runner.abort("Hit hard by a player: " + entry.aggressor.answer());
            body.stopInputs();
            observeRunner(entry);
            if (entry.runner == null) return;
            busy = runnerBusy(entry);
        }

        // Someone not on its side seen at a store of its own (E-43): a walk is given up to go and look.
        if (usable && busy && entry.sightingInterrupt && "native_goto".equals(entry.skillId)) {
            entry.sightingInterrupt = false;
            entry.runner.lease().ifPresent(entry.arbiter::cancel);
            entry.runner.abort("Someone at its chest: going to look");
            body.stopInputs();
            observeRunner(entry);
            if (entry.runner == null) return;
            busy = runnerBusy(entry);
        } else if (!busy) {
            entry.sightingInterrupt = false;
        }

        boolean escapingWater = !creeperThreat && (entry.waterEscapeTaskId != null && entry.waterEscapeTaskId.equals(entry.currentTaskId)
                || PortalRunCampaign.surfacing(entry.activeCampaignTask) && !body.entity().isInLava() && !body.entity().isOnFire());
        // A goal's creeper work or walling-in, and any recorded fight, deals with a hissing creeper by itself (a wall,
        // a raised shield, or a sprint away): cutting it short for a one-block retreat would only cost the time it needs.
        boolean handlingCreeper = PortalRunCampaign.urgentTask(entry.activeCampaignTask) || "native_fight".equals(entry.skillId);
        // Burning from a blaze's fireball is no reason to drop the fight with it; standing in fire or lava still is.
        boolean burningInFight = "native_fight".equals(entry.skillId) && body.entity().isOnFire() && !body.entity().isInLava()
                && !isHazardousSituation(body.entity(), false);
        // A long fall to break with water ends whatever else it was doing (E-07d).
        boolean breakFall = clutchSlot(body.entity()) >= 0 && !"native_clutch".equals(entry.skillId);
        if (usable && busy && (isHazardousSituation(body.entity()) && !burningInFight || creeperThreat && !handlingCreeper || breakFall)
                && !"native_retreat".equals(entry.skillId) && !"native_clutch".equals(entry.skillId) && !escapingWater) {
            entry.hazardInterrupted = true;
            entry.runner.lease().ifPresent(entry.arbiter::cancel);
            entry.runner.abort(breakFall && !isHazardousSituation(body.entity()) ? "Falling: the fall to be broken with water"
                    : "Hazard/threat detected: urgent retreat required");
            body.stopInputs();
            observeRunner(entry);
            if (entry.runner == null) return;
            busy = runnerBusy(entry);
        }

        // Check inventory fingerprint for invalidation
        long currentFp = computeInventoryFingerprint(body.entity());
        if (currentFp != entry.lastInventoryFingerprint) {
            entry.lastInventoryFingerprint = currentFp;
            entry.cachedRoutineCandidates = List.of();
            entry.routineRefreshPending = true;
            attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
            attentionScheduler.invalidate(entry.data.id(), WorkKind.EQUIPMENT_READINESS);
        }

        // A running match the player was entered into is an instruction to fight, as a goal is (M-00a).
        boolean dueling = body.entity() != null && matches.running(body.entity().getUuid()).isPresent();
        // Whether it may choose anything now: running, not stopped, nothing of its own in flight or being settled.
        boolean mayDecide = usable && !entry.snapshot().stopLatched() && !busy
                && (entry.runner == null || entry.runner.state() != SkillRunner.State.PAUSED)
                && !entry.nativeWorkPending && !entry.nativeSetupPending
                && entry.activityFlush.isDone() && !entry.nativeActionPending && entry.nativeActionFlush.isDone()
                && (entry.activity == null || entry.activity.phase() == PaidActivitySession.Phase.IDLE)
                && !entry.arbiter.cancellationPending()
                // A fall to break is a reflex: it does not wait out the hold that follows a commanded task (E-07d).
                && (server.getTicks() >= entry.manualHoldUntilTick || clutchSlot(body.entity()) >= 0);
        // Whether it may change the world: the server's switch for AI world edits (D4).
        boolean mayEdit = edits.autonomousEditsEnabled() && edits.protectionCompatibilityKnown();
        // Its work and progression: both, and not idle (unless in a match it was entered into).
        boolean autonomyAdmitted = mayDecide && mayEdit && (!entry.runtimeSettings.playstyleProfile().isIdleObserving() || dueling);

        // 1. Cheap immediate survival candidates (evaluated every tick, even with zero optional budget). Its reflexes
        // need only that it may decide, edits off or idle: eating, getting out of fire, lava or deep water, clear of a
        // hissing creeper, and defending itself, none of which changes a block. Each edit action checks the edit policy
        // itself as well.
        List<NativeTaskCandidate> essentialCandidates = mayDecide ? evaluateEssentialSurvivalCandidates(entry, body, autonomyAdmitted, mayEdit) : List.of();
        // Spoken to by name, a moment's pause between steps to look at the speaker (E-33a): after its reflexes, before its work.
        NativeTaskCandidate attention = mayDecide && !dueling ? attentionCandidate(entry, body) : null;
        if (attention != null) {
            essentialCandidates = new ArrayList<>(essentialCandidates);
            essentialCandidates.add(attention);
        }

        if (autonomyAdmitted && (entry.routineRefreshPending || entry.cachedRoutineCandidates.isEmpty())) {
            bindBodyBlockScanBudget(body);
            entry.cachedRoutineCandidates = evaluateScheduledRoutineCandidates(entry, body);
            entry.routineRefreshPending = false;
        }

        // 2. Scheduled routine candidates (cached from scheduler slices)
        List<NativeTaskCandidate> routineCandidates = autonomyAdmitted ? entry.cachedRoutineCandidates : List.of();

        // Not admitted to its work, its reflexes are all it has to choose from.
        List<NativeTaskCandidate> taskCandidates;
        if (routineCandidates.isEmpty()) {
            taskCandidates = essentialCandidates;
        } else if (essentialCandidates.isEmpty()) {
            taskCandidates = routineCandidates;
        } else {
            taskCandidates = new ArrayList<>(essentialCandidates.size() + routineCandidates.size());
            taskCandidates.addAll(essentialCandidates);
            taskCandidates.addAll(routineCandidates);
        }

        List<dev.kindred.core.TaskPreferences.Candidate> candidates = taskCandidates.stream().map(NativeTaskCandidate::candidate).toList();

        // A reflex to act on admits a decision without its work being admitted; nothing to do, and it waits as before.
        var decision = entry.mind.decide(new dev.kindred.core.PlayerDecisionLoop.Frame(usable, entry.snapshot().stopLatched(),
                busy, false, false, autonomyAdmitted || mayDecide && !essentialCandidates.isEmpty(), entry.currentTaskId, candidates),
                entry.profile.personality());
        entry.mindStatus = decision.reason();
        if (!candidates.isEmpty() || decision.action() != dev.kindred.core.PlayerDecisionLoop.Action.WAIT) {
            KindredMod.LOGGER.debug("Autonomy tick for {}: state={}, usable={}, stopLatched={}, busy={}, autonomyAdmitted={}, candidates={}, decision={}",
                    entry.data.displayName(), (entry.attentionSnapshot != null ? entry.attentionSnapshot.state() : "null"),
                    usable, entry.snapshot().stopLatched(), busy, autonomyAdmitted, candidates.size(), decision);
        }

        if (decision.action() == dev.kindred.core.PlayerDecisionLoop.Action.RUN_SKILL) {
            entry.runner.tick();
            observeRunner(entry);
        } else if (decision.action() == dev.kindred.core.PlayerDecisionLoop.Action.PROPOSE_TASK) {
            var selection = decision.proposal().orElseThrow();
            var chosen = selection.candidate();
            entry.currentTaskId = chosen.id();
            var match = taskCandidates.stream().filter(tc -> tc.candidate().id().equals(chosen.id())).findFirst();
            if (match.isPresent()) {
                NativeTaskCandidate candidateToDispatch = match.get();
                bindBodyBlockScanBudget(body);
                if (!validateCandidateStillExecutable(entry, body, candidateToDispatch)) {
                    entry.cachedRoutineCandidates = List.of();
                    entry.routineRefreshPending = true;
                    attentionScheduler.invalidate(entry.data.id(), WorkKind.ROUTINE_GOALS);
                    entry.currentTaskId = null;
                } else {
                    TaskId tId = TaskId.of(candidateToDispatch.candidate().id());
                    var optShared = teamCoordinator.findTask(tId);
                    if (optShared.isPresent()) {
                        var task = optShared.get();
                        if (task.state() == SharedTask.TaskState.OPEN || task.state() == SharedTask.TaskState.REVOKED) {
                            var claimed = teamCoordinator.claimTask(tId, entry.data.id(), server.getTicks(), 1200L);
                            if (claimed.isEmpty()) {
                                entry.currentTaskId = null;
                                return;
                            }
                            // What it holds of the task's output now, so that its completion credits what it made.
                            entry.teamBaselines.put(tId, produced(entry, claimed.get()));
                            teamCoordinator.startTask(tId, entry.data.id());
                        }
                    }
                    int res = dispatchNativeTask(Actor.console(), entry, candidateToDispatch.skill(), candidateToDispatch.arguments());
                    if (res == 1 && chosen.id().equals(entry.tacticalRetreatTaskId)) entry.lastTacticalRetreatTick = server.getTicks();
                    if (res == 1 && chosen.id().equals(entry.barricadeTaskId)) entry.lastBarricadeTick = server.getTicks();
                    if (res == 1 && chosen.id().equals(entry.attentionTaskId)) entry.attendedAt = entry.addressedAt;
                    entry.activeCampaignTask = res == 1 ? entry.campaignTasks.get(chosen.id()) : null;
                    entry.activeBuildTask = res == 1 ? entry.buildTasks.get(chosen.id()) : null;
                    if (res != 1 && (entry.build != null || entry.ranch != null || entry.field != null) && entry.buildTasks.containsKey(chosen.id())) {
                        var refusedTask = entry.buildTasks.get(chosen.id());
                        KindredMod.LOGGER.info("{} {} refused: {} [{}]", entry.data.displayName(), candidateToDispatch.skill(), entry.blocker, refusedTask.label());
                        if (entry.field != null) entry.field.finished(refusedTask, false);
                        else if (entry.ranch != null) entry.ranch.finished(refusedTask, false, String.valueOf(entry.blocker), body.entity().getServerWorld());
                        else entry.build.finished(refusedTask, false, String.valueOf(entry.blocker), body.entity().getServerWorld());
                        entry.routineRefreshPending = true;
                        entry.cachedRoutineCandidates = List.of();
                    }
                    if (res != 1 && entry.bring != null && entry.bring.taskIds.contains(candidateToDispatch.candidate().id())) {
                        KindredMod.LOGGER.info("{} bringing {} refused: {}", entry.data.displayName(), candidateToDispatch.skill(), entry.blocker);
                        entry.bring.failed++;
                        entry.routineRefreshPending = true;
                        entry.cachedRoutineCandidates = List.of();
                    }
                    if (res != 1 && entry.reroll != null && entry.reroll.taskIds.contains(candidateToDispatch.candidate().id())) {
                        // A reroll's step refused, counted as a failure, so that it is not asked for tick after tick (E-45c).
                        KindredMod.LOGGER.info("{} reroll {} refused: {}", entry.data.displayName(), candidateToDispatch.skill(), entry.blocker);
                        entry.reroll.failed++;
                        entry.routineRefreshPending = true;
                        entry.cachedRoutineCandidates = List.of();
                    }
                    if (res != 1) {
                        if (entry.campaign != null && entry.campaignTasks.containsKey(chosen.id())) {
                            KindredMod.LOGGER.info("{} {} refused: {} [{}]", entry.data.displayName(), candidateToDispatch.skill(),
                                    entry.blocker, entry.campaignTasks.get(chosen.id()).label());
                            entry.campaign.taskFinished(entry.campaignTasks.get(chosen.id()), false, server.getTicks());
                            entry.routineRefreshPending = true;
                            entry.cachedRoutineCandidates = List.of();
                        }
                        entry.currentTaskId = null;
                        if (optShared.isPresent()) {
                            teamCoordinator.revokeTask(tId, "Dispatch failed");
                        }
                    }
                }
            } else {
                entry.currentTaskId = null;
            }
        } else {
            if (!entry.nativeWorkPending && (entry.activity == null || entry.activity.phase() == PaidActivitySession.Phase.IDLE)) {
                if (!taskCandidates.isEmpty()) {
                    entry.currentTaskId = null;
                }
            }
            body.stopInputs();
            if (busy) {
                entry.runner.lease().ifPresent(entry.arbiter::cancel);
                entry.runner.abort(decision.reason());
                observeRunner(entry);
            }
        }
    }

    private SpawnLocation nativeSpawn(ServerCommandSource source) {
        var world = source.getWorld();
        var base = source.getPosition();
        for (int[] offset : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            double x = Math.floor(base.x) + offset[0] + 0.5;
            double z = Math.floor(base.z) + offset[1] + 0.5;
            BlockPos pos = BlockPos.ofFloored(x, base.y, z);
            var bounds = new net.minecraft.util.math.Box(x - .3, base.y, z - .3, x + .3, base.y + 1.8, z + .3);
            if (world.isChunkLoaded(pos) && world.isInBuildLimit(pos) && world.getWorldBorder().contains(bounds)
                    && world.isSpaceEmpty(null, bounds) && world.getOtherEntities(null, bounds).isEmpty()
                    && world.getFluidState(pos).isEmpty() && world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) {
                return new SpawnLocation(x, base.y, z, 0);
            }
        }
        return null;
    }

    int create(ServerCommandSource source, String name) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading its saved records.");
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return reject(source, "Create a companion as a player in a loaded world.");
        }
        if (name.isBlank() || name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl)) {
            return reject(source, "Choose a name of 1 to 32 printable characters.");
        }
        Entry existing = byOwner(player.getUuid());
        if (existing != null) {
            if (existing.snapshot().entityId() == null && !existing.metadataFault && !creating.contains(player.getUuid())) {
                if (!existing.snapshot().dimension().equals(player.getWorld().getRegistryKey().getValue().toString())) {
                    return reject(source, "Return to your companion's original dimension before retrying its creation.");
                }
                SpawnLocation location = findSpawn(player);
                if (location == null) return reject(source, "Make room on solid ground beside you before creating a companion.");
                creating.add(player.getUuid());
                mapAndSpawn(existing, player.getUuid(), location);
                feedback(source, "Retrying your saved companion's first body creation.");
                return 1;
            }
            if (source.hasPermissionLevel(2) || source.getServer().isSingleplayer()) {
                feedback(source, "You already have companion " + existing.snapshot().displayName()
                        + ". Creating native AI player " + name + " instead.");
                return createNative(source, name);
            }
            feedback(source, "Your companion is " + existing.snapshot().displayName() + " ("
                    + existing.snapshot().id() + "). " + existing.explanation()
                    + " To create additional native AI players, run /aiplayer native create <name> or /aiplayer roster spawn.");
            return 1;
        }
        UUID owner = player.getUuid();
        SpawnLocation location = findSpawn(player);
        if (location == null) return reject(source, "Make room on solid ground beside you before creating a companion.");
        if (!creating.add(owner)) return reject(source, "Your companion creation is already being saved.");
        CreationRequest request = new CreationRequest(UUID.randomUUID(), owner, name, worldId,
                player.getWorld().getRegistryKey().getValue().toString());
        feedback(source, "Saving your companion. It will begin sleeping.");
        store.create(request).whenComplete((record, failure) -> server.execute(() -> {
            if (!running) return;
            if (failure != null) {
                creating.remove(owner);
                reply(owner, "Creation could not be saved. No body was spawned; try again after checking server logs.");
                KindredMod.LOGGER.error("Companion creation failed for owner {}", owner, failure);
                return;
            }
            Entry entry = entries.computeIfAbsent(record.companion().id(), ignored -> new Entry(record));
            if (record.companion().entityId() != null) {
                creating.remove(owner);
                reply(owner, "Your existing companion is " + record.companion().id() + ". " + entry.explanation());
                return;
            }
            mapAndSpawn(entry, owner, location);
        }));
        return 1;
    }

    int command(ServerCommandSource source, String verb, UUID requestedId) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading its saved records.");
        UUID playerId = source.getEntity() instanceof ServerPlayerEntity player ? player.getUuid() : null;
        if (playerId == null && !source.hasPermissionLevel(2)) {
            return reject(source, "Only a player or an operator may issue companion controls.");
        }
        Entry entry = requestedId != null ? entries.get(requestedId) : byOwner(playerId);
        if (entry == null && requestedId == null) {
            UUID defId = defaultBotId();
            if (defId != null) {
                entry = entries.get(defId);
            }
        }
        if (entry == null) return reject(source, "No companion found. Use /aiplayer create <name>, or supply its name/UUID.");
        if (entry.nativeState != null) return nativeLifecycle(source, verb, entry.data.id());
        Actor actor = new Actor(playerId, source.hasPermissionLevel(2));
        if (!Authorization.mayControl(actor, entry.snapshot())) {
            return reject(source, "Only this companion's owner or an operator can control or inspect it.");
        }
        if (verb.equals("status")) {
            Companion snapshot = entry.snapshot();
            feedback(source, snapshot.displayName() + " (" + snapshot.id() + "): "
                    + (entry.following ? "following" : entry.runner != null && runnerBusy(entry) ? "running " + entry.skillId
                    : entry.runner != null && entry.runner.state() == SkillRunner.State.PAUSED ? "paused " + entry.skillId
                    : snapshot.lifecycle().name().toLowerCase())
                    + (snapshot.stopLatched() ? "; owner-stopped" : "") + ". " + entry.explanation()
                    + " Legacy restriction: " + (entry.mode != null ? entry.mode.mode().name().toLowerCase(java.util.Locale.ROOT) : "companion")
                    + ", scope " + (entry.mode != null ? entry.mode.scope().name().toLowerCase(java.util.Locale.ROOT) : "approved") + "."
                    + " Social: ambient " + entry.profile.ambientEnabled() + ", public " + entry.profile.publicConversation()
                    + ", sociability " + entry.profile.personality().sociability() + ". " + entry.setup.explanation()
                    + " Autonomous progression and unattended operation await qualification.");
            return 1;
        }
        if (verb.equals("stop") || verb.equals("stay")) {
            // Works on unloaded records too; every pending creation/action continuation is fenced.
            halt(entry, actor, verb.equals("stay") ? "Staying here; use resume, then follow." : "Stopped by owner.");
            feedback(source, entry.explanation());
            return 1;
        }
        if (entry.metadataFault || entry.reconciliationBlocked) return reject(source, entry.explanation());
        if (entry.body == null || entry.arbiter == null || !entry.body.isAlive()) {
            return reject(source, entry.explanation() + " Load its saved area; an absent body is never automatically recreated.");
        }
        try {
            if (verb.equals("resume")) {
                entry.arbiter.resume(actor);
                entry.body.ownerStopped(false);
                entry.blocker = "Ready for a direct follow command; autonomous work remains disabled.";
                persist(entry);
            } else if (verb.equals("follow")) {
                if (entry.following) {
                    feedback(source, "Already following the owner.");
                    return 1;
                }
                ServerPlayerEntity owner = server.getPlayerManager().getPlayer(entry.snapshot().ownerId());
                if (!eligibleFollow(entry.body, owner)) {
                    return reject(source, "The owner must be online in this dimension, within 48 blocks.");
                }
                if (entry.snapshot().stopLatched()) return reject(source, "Use /companion resume before following again.");
                entry.lease = entry.arbiter.acquire(actor, UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(5));
                entry.following = true;
                entry.follow = new FollowBehavior(entry.body, owner);
                entry.blocker = "Following with room beside the owner, within loaded nearby terrain (five-minute M0 command timeout).";
                persist(entry);
            }
            feedback(source, entry.explanation());
            return 1;
        } catch (IllegalStateException failure) {
            return reject(source, failure.getMessage());
        }
    }

    void loaded(CompanionEntity body) {
        requireThread();
        body.haltControls();
        if (!running) return;
        if (!ready) { awaitingRegistry.add(body); return; }
        Entry entry = entries.get(body.companionId());
        if (entry == null || entry.nativeState != null || !body.getUuid().equals(entry.snapshot().entityId())
                || !entry.snapshot().ownerId().equals(body.ownerId())
                || !worldId.equals(entry.snapshot().worldId())
                || !body.getWorld().getRegistryKey().getValue().toString().equals(entry.snapshot().dimension())) {
            KindredMod.LOGGER.error("Unreconciled Kindred body {}; preserved dormant for operator review", body.getUuid());
            return;
        }
        if (entry.body != null && entry.body != body) {
            halt(entry, Actor.console(), "Duplicate native body detected; operator review required.");
            entry.reconciliationBlocked = true;
            KindredMod.LOGGER.error("Duplicate body for companion {}; neither inventory was deleted", body.companionId());
            return;
        }
        entry.body = body;
        Companion restored = entry.data.withStopLatched(entry.data.stopLatched() || body.ownerStopped());
        entry.arbiter = new ActionArbiter(restored, new NativeBody(body));
        entry.runner = new SkillRunner(entry.arbiter, PlayerRuntime::nanos);
        entry.lastRunnerState = SkillRunner.State.IDLE;
        entry.data = entry.arbiter.snapshot();
        entry.following = false;
        entry.lease = null;
        body.ownerStopped(entry.data.stopLatched());
        body.setCustomName(Text.literal(entry.data.displayName()));
        body.setCustomNameVisible(true);
        if (!entry.metadataFault && !entry.reconciliationBlocked) {
            entry.blocker = entry.data.lifecycle() == LifecycleState.FAULTED ? "Faulted; operator recovery required."
                    : entry.data.stopLatched() ? "Owner-stopped; use resume before follow." : "Sleeping; ready for direct follow.";
        }
    }

    void unloaded(CompanionEntity body) {
        requireThread();
        awaitingRegistry.remove(body);
        Entry entry = entries.get(body.companionId());
        if (entry == null || entry.body != body) return;
        halt(entry, Actor.console(), body.isAlive()
                ? "Body is unloaded; load its saved area before controlling it."
                : "Body died or was removed; recovery requires operator inspection of actual items.");
        entry.body = null;
        entry.arbiter = null;
        entry.runner = null;
        socials.discard(entry.data.id());
        entry.perception = null; // Facts belong to a loaded body in one world; they are rebuilt, never carried over.
        entry.perceptionSource = null;
        if (entry.lookPending) finishLook(entry, entry.data.displayName() + " could not finish looking: its body unloaded first.");
    }

    void beforeEntityTick(CompanionEntity body) {
        requireThread();
        Entry entry = entries.get(body.companionId());
        if (!running || !ready || entry == null || entry.body != body || entry.arbiter == null) {
            body.haltControls();
            return;
        }
        entry.arbiter.tick();
        boolean busy = entry.runner != null && runnerBusy(entry);
        Companion state = entry.snapshot();
        boolean usable = body.isAlive() && !entry.metadataFault && !entry.reconciliationBlocked
                && state.lifecycle() != LifecycleState.FAULTED && state.lifecycle() != LifecycleState.REMOVED;
        // F-25c supplies qualified candidates and durable admission; legacy mode never enables autonomy.
        var decision = entry.mind.decide(new dev.kindred.core.PlayerDecisionLoop.Frame(usable,
                state.stopLatched(), busy, entry.following,
                entry.lease != null && entry.arbiter.isCurrent(entry.lease), false, null, List.of()),
                entry.profile.personality());
        entry.mindStatus = decision.reason();
        if (decision.action() == dev.kindred.core.PlayerDecisionLoop.Action.HALT) {
            body.haltControls();
            if (busy) {
                // abort settles runner state; authority must be revoked first, including fault paths.
                entry.runner.lease().ifPresent(entry.arbiter::cancel);
                entry.runner.abort(decision.reason());
                observeRunner(entry);
            }
            if (entry.following) {
                if (entry.lease != null) entry.arbiter.cancel(entry.lease);
                entry.following = false;
                entry.lease = null;
            }
            return;
        }
        tickPerception(entry, body);
        socials.deliverDue(entry.data.id(), now());
        switch (decision.action()) {
            case RUN_SKILL -> tickSkill(entry, body);
            case RUN_FOLLOW -> tickFollow(entry, body);
            case WAIT -> body.haltControls();
            // A proposal is not authority. Dispatch requires F-25c's admission/checkpoint integration.
            case PROPOSE_TASK -> body.haltControls();
            default -> throw new IllegalStateException("Unexpected player decision");
        }
        CompanionSocials.Participant participant = socialParticipant(entry.data.id());
        if (participant != null) socials.tick(participant);
    }

    private void tickSkill(Entry entry, CompanionEntity body) {
        entry.runner.tick();
        if (runnerBusy(entry) && entry.runner.lease().isPresent() && !"wait".equals(entry.skillId)) {
            MannerismState.Gait gait = entry.mannerisms.gait(entry.profile.personality(), now(),
                    FollowBehavior.movementContext(body, entry.arbiter.isCurrent(entry.runner.lease().orElseThrow())));
            FollowBehavior.applyGait(body, gait, entry.stride, !body.getNavigation().isIdle()
                    && body.getVelocity().horizontalLengthSquared() > 0.003);
        }
        observeRunner(entry);
    }

    private void tickFollow(Entry entry, CompanionEntity body) {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(entry.snapshot().ownerId());
        if (!eligibleFollow(body, target)) {
            halt(entry, Actor.console(), "Follow task paused: target offline, out of range or in another dimension.");
            return;
        }
        long now = now();
        MannerismState.Gait gait = entry.mannerisms.gait(entry.profile.personality(), now,
                FollowBehavior.movementContext(body, true));
        Entity glance = null;
        if (entry.glanceTarget != null) {
            if (body.age >= entry.glanceUntilAge) entry.glanceTarget = null;
            else glance = ((ServerWorld) body.getWorld()).getEntity(entry.glanceTarget);
        }
        Entity glanceTarget = glance;
        entry.arbiter.executeIfCurrent(entry.lease, () -> entry.follow.tick(body, target, gait, glanceTarget,
                entry.profile.personality(), entry.mannerisms.active(entry.profile.personality(), now)));
        if (entry.follow.blocked()) halt(entry, Actor.console(), "Follow blocked: no safe space or movement progress.");
    }

    int socialSetting(ServerCommandSource source, UUID requestedId, String setting, boolean enabled,
                      Personality.Trait trait, int value) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading its saved records.");
        UUID player = source.getEntity() instanceof ServerPlayerEntity entity ? entity.getUuid() : null;
        if (player == null && !source.hasPermissionLevel(2)) return reject(source, "Owner or operator controls only.");
        Entry entry = requestedId == null ? byOwner(player) : entries.get(requestedId);
        if (entry == null) return reject(source, "No companion found.");
        Actor actor = new Actor(player, source.hasPermissionLevel(2));
        if (!Authorization.mayControl(actor, entry.snapshot())) return reject(source, "Only the owner or an operator may change this profile.");
        if (entry.metadataFault || entry.snapshot().lifecycle() == LifecycleState.REMOVED) return reject(source, "This companion's profile is unavailable.");
        if (!entry.profileWrite.isDone()) return reject(source, "The previous profile change is still being saved.");
        SocialProfile previous = entry.profile;
        SocialProfile changed = trait != null ? previous.withTrait(trait, value)
                : setting.equals("public") ? previous.withPublic(enabled) : previous.withAmbient(enabled);
        entry.profile = changed; // Privacy restrictions and mute take effect synchronously.
        socials.reset(entry.data.id());
        entry.profileWrite = store.saveProfile(changed, previous.revision());
        entry.profileWrite.whenComplete((saved, failure) -> server.execute(() -> {
            if (!running) return;
            if (failure != null) {
                entry.metadataFault = true;
                halt(entry, Actor.console(), "Profile save failed; companion stopped for storage inspection.");
                KindredMod.LOGGER.error("Social profile write failed for {}", entry.data.id(), failure);
                source.sendError(Text.literal("The profile could not be saved. The companion has stopped."));
            } else feedback(source, "Saved " + entry.data.displayName() + "'s "
                    + (trait != null ? trait.name().toLowerCase(java.util.Locale.ROOT) + " = " + value : setting + " = " + enabled) + "."
                    + (trait != null && trait.isTaskPreference() ? " This preference will guide work when matching skills are available." : ""));
        }));
        feedback(source, "Saving the companion's profile.");
        return 1;
    }

    int personality(ServerCommandSource source, UUID requestedId) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading its saved records.");
        UUID player = source.getEntity() instanceof ServerPlayerEntity entity ? entity.getUuid() : null;
        if (player == null && !source.hasPermissionLevel(2)) return reject(source, "Owner or operator controls only.");
        Entry entry = requestedId == null ? byOwner(player) : entries.get(requestedId);
        if (entry == null) return reject(source, "No companion found.");
        if (!Authorization.mayControl(new Actor(player, source.hasPermissionLevel(2)), entry.snapshot())) {
            return reject(source, "Only the owner or an operator may inspect this profile.");
        }
        String values = java.util.Arrays.stream(Personality.Trait.values())
                .map(trait -> trait.name().toLowerCase(java.util.Locale.ROOT) + " " + entry.profile.personality().value(trait))
                .collect(java.util.stream.Collectors.joining(", "));
        String modeText = " Legacy restriction: " + (entry.mode != null ? entry.mode.mode().name().toLowerCase(java.util.Locale.ROOT) : "companion")
                + ", scope " + (entry.mode != null ? entry.mode.scope().name().toLowerCase(java.util.Locale.ROOT) : "approved") + ".";
        feedback(source, entry.data.displayName() + "'s personality: " + values + "." + modeText);
        return 1;
    }

    // ----- Movement skills through the runner (F-04 / F-10) -----

    private static boolean runnerBusy(Entry entry) {
        SkillRunner.State state = entry.runner.state();
        return state == SkillRunner.State.RUNNING || state == SkillRunner.State.PAUSING || state == SkillRunner.State.COMPLETING;
    }

    int skill(ServerCommandSource source, UUID requestedId, String skillId, Map<String, String> arguments) {
        Entry entry = authorizedEntry(source, requestedId, "control this companion");
        if (entry == null) return 0;
        if (entry.metadataFault || entry.reconciliationBlocked) return reject(source, entry.explanation());
        if (entry.body == null || entry.arbiter == null || entry.runner == null || !entry.body.isAlive()) {
            return reject(source, entry.explanation() + " Load its saved area; an absent body is never automatically recreated.");
        }
        if (entry.snapshot().stopLatched()) return reject(source, "Use /companion resume first.");
        if (entry.following) return reject(source, "Use /companion stay to end following before giving another movement command.");
        if (runnerBusy(entry)) return reject(source, "A skill is already running (" + entry.skillId + "); pause or stop it first.");
        var descriptor = MovementSkills.descriptor(skillId);
        var body = MovementSkills.body(skillId, arguments, entry.body, entry.setup, () -> edits, entry);
        if (descriptor.isEmpty() || body.isEmpty()) return reject(source, "Unknown skill " + skillId + ".");
        UUID playerId = source.getEntity() instanceof ServerPlayerEntity player ? player.getUuid() : null;
        int chunks = entry.setup.area() == null ? 1 : entry.setup.area().chunkCount();
        AdmissionBoard.Decision seat = admission.request(entry.data.id(), entry.data.ownerId(), Math.min(chunks, AdmissionBoard.MAX_CHUNKS_EACH));
        if (seat == AdmissionBoard.Decision.WAITING) return reject(source, "The active slots are full, so this companion is waiting.");
        if (seat == AdmissionBoard.Decision.REJECTED) return reject(source, "That work area is larger than one companion may keep ticking.");
        try {
            entry.lease = entry.runner.start(descriptor.orElseThrow(), arguments, body.orElseThrow(),
                    new Actor(playerId, source.hasPermissionLevel(2)), UUID.randomUUID(), java.util.Optional.empty());
        } catch (IllegalStateException | IllegalArgumentException rejected) {
            admission.release(entry.data.id());
            return reject(source, rejected.getMessage());
        }
        entry.skillId = skillId;
        entry.skillArgs = Map.copyOf(arguments);
        entry.lastRunnerState = SkillRunner.State.RUNNING;
        entry.blocker = "Running " + skillId + " " + arguments + " (two-minute deadline).";
        persist(entry);
        feedback(source, "Acknowledged: " + skillId + " " + arguments + ". Arrival or a blocker will be reported; this is not a completion.");
        return 1;
    }

    int pauseSkill(ServerCommandSource source, UUID requestedId) {
        Entry entry = authorizedEntry(source, requestedId, "control this companion");
        if (entry == null) return 0;
        if (entry.runner == null || !entry.runner.pause()) return reject(source, "No running skill to pause. Follow ends with stay.");
        feedback(source, "Pausing at the next checkpoint (within 500 ms); progress is kept for /companion continue.");
        return 1;
    }

    int continueSkill(ServerCommandSource source, UUID requestedId) {
        Entry entry = authorizedEntry(source, requestedId, "control this companion");
        if (entry == null) return 0;
        UUID playerId = source.getEntity() instanceof ServerPlayerEntity player ? player.getUuid() : null;
        Actor actor = new Actor(playerId, source.hasPermissionLevel(2));
        return continueSkillEntry(source, entry, actor);
    }

    private int continueSkillEntry(ServerCommandSource source, Entry entry, Actor actor) {
        if ((entry.body == null && entry.nativeBody == null) || entry.arbiter == null || entry.runner == null) return reject(source, entry.explanation());
        if (entry.snapshot().stopLatched()) return reject(source, "Use resume first; a stop never silently resumes a chore.");
        if (entry.runner.state() == SkillRunner.State.PAUSED) {
            if (entry.nativeState != null) {
                if (entry.activity != null && entry.activity.phase() == PaidActivitySession.Phase.IDLE) {
                    if (admission.request(entry.data.id(), entry.data.id(), 1) != AdmissionBoard.Decision.ADMIT) {
                        return reject(source, "No activity seat is available.");
                    }
                    entry.nativeWorkPending = true;
                    long generation = entry.generation;
                    entry.activity.request(entry.activity.snapshot().period().endExclusive()).whenComplete((paid, error) -> server.execute(() -> {
                        entry.nativeWorkPending = false;
                        if (!running || generation != entry.generation || entry.runner == null || entry.runner.state() != SkillRunner.State.PAUSED) {
                            admission.release(entry.data.id());
                            return;
                        }
                        if (error != null || !Boolean.TRUE.equals(paid) || !entry.activity.paid()) {
                            admission.release(entry.data.id());
                            nativeActivityFailure(entry, "Activity acknowledgement is uncertain on resume");
                            return;
                        }
                        try {
                            entry.lease = entry.runner.resume(actor);
                            entry.nativeTask = entry.runner.body();
                            entry.lastRunnerState = SkillRunner.State.RUNNING;
                            entry.blocker = "Resumed " + entry.skillId + " from its checkpoint with fresh observations.";
                            persist(entry);
                        } catch (IllegalStateException rejected) {
                            admission.release(entry.data.id());
                            entry.blocker = "Resume rejected: " + rejected.getMessage();
                        }
                    }));
                    entry.blocker = "Resuming native skill at the next paid activity slice.";
                    persist(entry);
                    feedback(source, entry.blocker);
                    return 1;
                }
            }
            try {
                entry.lease = entry.runner.resume(actor);
            } catch (IllegalStateException rejected) {
                return reject(source, rejected.getMessage());
            }
            if (entry.nativeState != null) {
                entry.nativeTask = entry.runner.body();
            }
            entry.lastRunnerState = SkillRunner.State.RUNNING;
            entry.blocker = "Resumed " + entry.skillId + " from its checkpoint with fresh observations.";
            persist(entry);
            feedback(source, entry.blocker);
            return 1;
        }
        if (runnerBusy(entry)) return reject(source, "The skill is already running.");
        // After a restart the checkpoint lives only in metadata; rebuild the body and start from it explicitly.
        feedback(source, "Looking for a saved checkpoint.");
        long generation = entry.generation;
        store.checkpoint(entry.data.id()).whenComplete((saved, failure) -> server.execute(() -> {
            if (!running || generation != entry.generation) return;
            if (failure != null || saved.isEmpty()) { reply(entry.data.ownerId(), "No saved checkpoint to continue."); return; }
            Checkpoint checkpoint = saved.orElseThrow();
            dev.kindred.core.SkillDescriptor descriptor = null;
            dev.kindred.core.SkillBody body = null;
            if (entry.nativeBody != null && checkpoint.skillId().equals("native_gather")) {
                descriptor = NativePlayerSkills.descriptor("native_gather");
                body = new NativeGatherAction(entry.nativeBody, () -> entry.lease, entry.data.worldId(), () -> entry.setup, () -> edits,
                        claims, nativeActionWriter(), server::execute, Integer.parseInt(checkpoint.arguments().get("count")), Integer.parseInt(checkpoint.arguments().get("slot")));
            } else if (entry.body != null) {
                descriptor = MovementSkills.descriptor(checkpoint.skillId()).orElse(null);
                body = MovementSkills.body(checkpoint.skillId(), checkpoint.arguments(), entry.body, entry.setup, () -> edits, entry).orElse(null);
            }
            if (descriptor == null || body == null || runnerBusy(entry)) { reply(entry.data.ownerId(), "The saved checkpoint cannot be continued now."); return; }
            try {
                entry.lease = entry.runner.start(descriptor, checkpoint.arguments(), body, actor, checkpoint.taskId(), saved);
            } catch (IllegalStateException | IllegalArgumentException rejected) {
                reply(entry.data.ownerId(), rejected.getMessage());
                return;
            }
            entry.skillId = checkpoint.skillId();
            if (entry.nativeState != null) {
                entry.nativeTask = body;
            }
            entry.lastRunnerState = SkillRunner.State.RUNNING;
            entry.blocker = "Continued " + entry.skillId + " from the saved checkpoint.";
            persist(entry);
            reply(entry.data.ownerId(), entry.blocker);
        }));
        return 1;
    }

    /** Report state changes once, persist checkpoints on pause, and release the lease on every terminal state. */
    private void observeRunner(Entry entry) {
        if (entry.runner == null) return;
        SkillRunner.State state = entry.runner.state();
        if (state == entry.lastRunnerState) return;
        entry.lastRunnerState = state;
        if (entry.nativeState != null && state == SkillRunner.State.BLOCKED && NativePlayerSkills.uncertain(entry.nativeTask)) {
            nativeFailure(server.getCommandSource(), entry, "Native action outcome requires reconciliation",
                    new IllegalStateException(entry.runner.reason().orElse("Unverified inventory change")));
            return;
        }
        UUID finishedTaskId = entry.currentTaskId;
        if (state == SkillRunner.State.CANCELLED && entry.runner.reason().filter(r -> r.startsWith("authority lost")).isPresent()) {
            var snapshot = entry.arbiter.snapshot();
            KindredMod.LOGGER.debug("{} {} lost authority: lifecycle={} stop={} intent={} task={} lease={}", entry.data.displayName(),
                    entry.skillId, snapshot.lifecycle(), snapshot.stopLatched(), snapshot.intentRevision(), snapshot.taskRevision(),
                    entry.runner.lease().map(l -> l.intentRevision() + "/" + l.taskRevision()).orElse("none"));
        }
        if (state == SkillRunner.State.BLOCKED && finishedTaskId != null && finishedTaskId.equals(entry.waterEscapeTaskId)) {
            entry.waterEscapeRetryTick = server.getTicks() + 200; // That shore is out of reach; try again in a while.
            if (entry.waterEscapeShore != null && entry.unreachedShores.size() < 64) {
                entry.unreachedShores.put(entry.waterEscapeShore.asLong(), server.getTicks() + 1200L);
            }
        }
        // A way out that ended short of its shore, still in deep water (as near as a route went to dry ground walled
        // off from it), did not reach it either.
        if (state == SkillRunner.State.COMPLETED && finishedTaskId != null && finishedTaskId.equals(entry.waterEscapeTaskId)
                && entry.waterEscapeShore != null && entry.nativeBody != null && entry.nativeBody.entity() != null
                && inDeepWater(entry.nativeBody.entity()) && entry.unreachedShores.size() < 64) {
            entry.unreachedShores.put(entry.waterEscapeShore.asLong(), server.getTicks() + 1200L);
        }
        if (finishedTaskId != null && finishedTaskId.equals(entry.handOverStepId)) handOverStepEnded(entry, state);
        // A store or an anvil it could not get to (E-43, E-16d): let be a while, never tried again tick after tick.
        if (state == SkillRunner.State.BLOCKED && finishedTaskId != null && finishedTaskId.equals(entry.storeTaskId)) {
            // A look, or the rest moved out, is tried again soon, what it saw kept; putting away waits a minute.
            if (entry.emptying != null || !entry.seenAtStores.isEmpty()) entry.lookAt = server.getTicks() + 200;
            else entry.storeAt = server.getTicks() + 1200;
            entry.routineRefreshPending = true;
        }
        if (state == SkillRunner.State.BLOCKED && finishedTaskId != null && finishedTaskId.equals(entry.kitTaskId)) {
            entry.kitNamesAt = server.getTicks() + 1200;
            entry.routineRefreshPending = true;
        }
        if (entry.nativeTask instanceof NativeLootAction loot && loot.raidOwner() != null) raidEnded(entry, loot, state);
        if (entry.nativeTask instanceof NativeRenameAction rename) renameEnded(entry, rename, state);
        if (entry.nativeTask instanceof NativeStoreAction stored) storeEnded(entry, stored, state);
        if (entry.nativeTask instanceof NativeTradeAction trade) tradeEnded(entry, trade, state);
        // A reroll's step done, the next one planned from what it did; refused, counted (E-45c).
        if (entry.reroll != null && state != SkillRunner.State.RUNNING) {
            entry.reroll.failed = state == SkillRunner.State.BLOCKED ? entry.reroll.failed + 1 : 0;
            entry.routineRefreshPending = true;
        }
        // A step of bringing a villager home done, the next one planned from what it did; refused, counted (E-45e).
        if (entry.bring != null && state != SkillRunner.State.RUNNING && finishedTaskId != null && entry.bring.taskIds.contains(finishedTaskId)) {
            if ("native_push".equals(entry.skillId)) entry.bring.pushes++;
            if (state == SkillRunner.State.BLOCKED) {
                entry.bring.failed++;
                KindredMod.LOGGER.info("{} bringing: a step blocked ({}), {} in a row", entry.data.displayName(), entry.blocker, entry.bring.failed);
            } else entry.bring.failed = 0;
            entry.routineRefreshPending = true;
        }
        if (state == SkillRunner.State.BLOCKED && finishedTaskId != null && finishedTaskId.equals(entry.tradeTaskId) && entry.tradeVillager != null) {
            entry.villagerVisitAt.put(entry.tradeVillager, (long) server.getTicks() + 1200);
            entry.routineRefreshPending = true;
        }
        // Tidied, or nothing to tidy: its chest is let be a while, not looked over again at once.
        if (entry.nativeTask instanceof NativeChestOrganizeAction && state != SkillRunner.State.RUNNING) {
            entry.organizeAt = server.getTicks() + 2400;
            entry.routineRefreshPending = true;
        }
        if (entry.nativeTask instanceof NativeTakeAction take) takeEnded(entry, take, state);
        if (finishedTaskId != null && finishedTaskId.equals(entry.lifeTaskId) && entry.lifeTask != null) {
            var life = entry.lifeTask;
            entry.lifeTaskId = null;
            entry.lifeTask = null;
            // A bed it could not sleep in (mobs about, someone in it), or could not get to (in walls still going up), is
            // tried again in a while, not at once (F-25c9).
            if (("native_sleep".equals(life.skill()) || "native_goto".equals(life.skill()) && life.label().startsWith("To its bed"))
                    && state != SkillRunner.State.COMPLETED) entry.sleepRetryAt = server.getTicks() + SLEEP_RETRY;
            if ("native_goto".equals(life.skill()) && life.label().startsWith("Explore")) entry.foodLooks++;
            entry.routineRefreshPending = true;
            entry.cachedRoutineCandidates = List.of();
        }
        if (entry.nativeState != null && (state == SkillRunner.State.COMPLETED || state == SkillRunner.State.BLOCKED
                || state == SkillRunner.State.CANCELLED)) {
            if (state == SkillRunner.State.BLOCKED && "native_goto".equals(entry.skillId)) entry.walksFailed++;
            KindredMod.LOGGER.info("{} {} {}{}{}", entry.data.displayName(), entry.skillId, state,
                    entry.runner.reason().map(r -> ": " + r).orElse(""),
                    entry.activeCampaignTask != null ? " [" + entry.activeCampaignTask.label() + "]"
                            : entry.activeBuildTask != null ? " [" + entry.activeBuildTask.label() + "]" : "");
            campaignTaskFinished(entry, state);
            buildTaskFinished(entry, state);
        }
        if (entry.nativeState != null && (state == SkillRunner.State.COMPLETED || state == SkillRunner.State.BLOCKED
                || state == SkillRunner.State.CANCELLED || state == SkillRunner.State.PAUSED)) {
            closeNativeAction(entry);
            if (entry.activity != null) entry.activity.stop();
            admission.release(entry.data.id());
            if (!entry.hazardInterrupted) {
                entry.currentTaskId = null;
            }
        }
        if (entry.runner == null) return;
        UUID companionId = entry.data.id();
        switch (state) {
            case COMPLETED -> {
                var finishedLease = entry.lease;
                if (entry.lease != null) claims.releaseTask(entry.lease.taskId());
                entry.lease = null;
                entry.blocker = "Completed " + entry.skillId + ": " + entry.runner.checkpoint().map(Checkpoint::progress).orElse(Map.of()) + ".";
                if (finishedTaskId != null) {
                    // A shared task's completion names the action it came of and credits what its player now holds
                    // more of its output than when it took the task up: what was made, not what was planned (E-11).
                    var tId = TaskId.of(finishedTaskId);
                    Map<String, Integer> observed = teamCoordinator.findTask(tId).map(task -> {
                        String item = task.arguments().get("produces_item");
                        Integer before = entry.teamBaselines.remove(tId);
                        return item == null || before == null ? Map.<String, Integer>of() : Map.of(item, Math.max(0, produced(entry, task) - before));
                    }).orElse(Map.of());
                    teamCoordinator.completeTask(tId, entry.data.id(), entry.blocker
                            + (finishedLease != null ? " (action " + finishedLease.actionId() + ")" : ""), observed);
                }
                if (entry.nativeBody != null && ("native_place".equals(entry.skillId) || "native_pillar".equals(entry.skillId)
                        || "native_stair".equals(entry.skillId) || "native_craft".equals(entry.skillId))) {
                    entry.cachedCraftingTablePos = null;
                    entry.cachedFurnacePos = null;
                    primeWorkstationCaches(entry, entry.nativeBody);
                    entry.routineRefreshPending = true;
                    server.execute(() -> persistNativeSaveReceipt(entry));
                }
                store.appendEvent(companionId, java.time.Instant.now(), "WORK_VERIFIED", entry.blocker);
                checkpointWrite(entry, store.clearCheckpoint(companionId).thenApply(ignored -> null));
                persist(entry);
                reply(entry.data.ownerId(), entry.data.displayName() + ": " + entry.blocker);
                if (entry.body != null) socials.workCompleted(companionId, entry.skillId, entry.blocker);
                reconcileExpeditionArrival(entry);
            }
            case BLOCKED, CANCELLED -> {
                if (entry.lease != null) claims.releaseTask(entry.lease.taskId());
                entry.lease = null;
                entry.blocker = (state == SkillRunner.State.BLOCKED ? "Blocked: " : "Cancelled: ") + entry.runner.reason().orElse("no reason recorded") + ".";
                if (finishedTaskId != null) {
                    teamCoordinator.revokeTask(TaskId.of(finishedTaskId), entry.blocker);
                }
                store.appendEvent(companionId, java.time.Instant.now(), "WORK_BLOCKED", entry.blocker);
                if (entry.hazardInterrupted && entry.runner.checkpoint().isPresent()) {
                    checkpointWrite(entry, store.saveCheckpoint(companionId, entry.runner.checkpoint().get()).thenApply(ignored -> null));
                } else {
                    checkpointWrite(entry, store.clearCheckpoint(companionId).thenApply(ignored -> null));
                }
                persist(entry);
                reply(entry.data.ownerId(), entry.data.displayName() + ": " + entry.blocker);
                if (state == SkillRunner.State.BLOCKED && entry.body != null) socials.workBlocked(companionId, entry.skillId, entry.blocker);
                entry.hazardInterrupted = false;
            }
            case PAUSED -> {
                if (entry.lease != null) claims.releaseTask(entry.lease.taskId());
                entry.lease = null;
                entry.blocker = "Paused " + entry.skillId + " (" + entry.runner.reason().orElse("") + "); use /companion continue.";
                if (finishedTaskId != null) {
                    teamCoordinator.revokeTask(TaskId.of(finishedTaskId), entry.blocker);
                }
                entry.runner.checkpoint().ifPresent(checkpoint -> checkpointWrite(entry, store.saveCheckpoint(companionId, checkpoint).thenApply(ignored -> null)));
                persist(entry);
                reply(entry.data.ownerId(), entry.data.displayName() + ": " + entry.blocker);
            }
            default -> { }
        }
    }

    private void checkpointWrite(Entry entry, CompletableFuture<Void> write) {
        write.whenComplete((ignored, failure) -> {
            if (failure != null) server.execute(() -> {
                if (!running) return;
                entry.metadataFault = true;
                halt(entry, Actor.console(), "Checkpoint write failed; companion stopped pending storage inspection.");
                KindredMod.LOGGER.error("Checkpoint write failed for {}", entry.data.id(), failure);
            });
        });
    }

    // ----- Work setup (F-03) -----

    int setup(ServerCommandSource source, UUID requestedId) {
        if (requestedId == null) {
            requestedId = defaultBotId();
        }
        Entry entry = requestedId != null ? entries.get(requestedId) : null;
        if (entry != null && entry.nativeState != null) {
            WorkSetup setup = entry.setup;
            String homeStr = setup != null && setup.home() != null ? point(setup.home().position())
                    : (entry.idleAnchorPos != null ? (entry.idleAnchorPos.getX() + "," + entry.idleAnchorPos.getY() + "," + entry.idleAnchorPos.getZ()) : "unset");
            String patchStr = setup != null && setup.patch() != null
                    ? setup.patch().resourceId() + " " + point(setup.patch().min()) + " to " + point(setup.patch().max())
                    : "none";
            feedback(source, entry.data.displayName() + " (native AI player): home " + homeStr
                    + "; idle radius " + entry.runtimeSettings.idleRadiusBlocks() + "m"
                    + "; playstyle " + entry.runtimeSettings.playstyleProfile()
                    + "; work patch: " + patchStr + ". "
                    + (entry.runtimeSettings.playstyleProfile().isIdleObserving()
                    ? "Currently idling. Run '/aiplayer resume' to begin autonomous survival."
                    : "Autonomous survival active."));
            return 1;
        }
        entry = authorizedEntry(source, requestedId, "inspect this setup");
        if (entry == null) return 0;
        WorkSetup setup = entry.setup;
        StringBuilder text = new StringBuilder(entry.data.displayName() + " setup (" + setup.dimension() + "): ");
        text.append(setup.home() == null ? "home unset" : "home " + point(setup.home().position()));
        text.append("; ").append(setup.area() == null ? "area unset" : "area " + setup.area().minX() + "," + setup.area().minZ()
                + " to " + setup.area().maxX() + "," + setup.area().maxZ() + " (" + setup.area().chunkCount() + " chunks)");
        text.append("; ").append(setup.storage() == null ? "storage unset" : "storage " + setup.storage().blockId() + " at " + point(setup.storage().position())
                + " [" + storageStatus(entry) + "]");
        text.append("; ").append(setup.patch() == null ? "patch unset" : "patch " + setup.patch().resourceId() + " " + point(setup.patch().min()) + " to " + point(setup.patch().max()));
        text.append(". ").append(setup.explanation());
        text.append(" Autonomous block and container edits stay disabled (decision D4 deferred).");
        feedback(source, text.toString());
        return 1;
    }

    int homeSet(ServerCommandSource source, UUID requestedId) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) return reject(source, "Stand where the companion should live.");
        Entry entry = authorizedEntry(source, requestedId, "change this setup");
        if (entry == null) return 0;
        if (!player.getWorld().getRegistryKey().getValue().toString().equals(entry.setup.dimension())) {
            return reject(source, "The home must be in the companion's own dimension (" + entry.setup.dimension() + ").");
        }
        BlockPos pos = player.getBlockPos();
        return editSetup(source, entry, setup -> {
            WorkSetup next = setup.withHome(new WorkSetup.HomeMarker(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ())));
            return next.area() == null ? next.withArea(WorkSetup.WorkRegion.around(next.home().position())) : next;
        }, "home at " + pos.toShortString() + (entry.setup.area() == null ? " with a default nine-chunk area" : ""));
    }

    int areaSet(ServerCommandSource source, UUID requestedId, int x1, int z1, int x2, int z2) {
        Entry entry = authorizedEntry(source, requestedId, "change this setup");
        if (entry == null) return 0;
        return editSetup(source, entry, setup -> setup.withArea(new WorkSetup.WorkRegion(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2))),
                "work area " + Math.min(x1, x2) + "," + Math.min(z1, z2) + " to " + Math.max(x1, x2) + "," + Math.max(z1, z2));
    }

    int areaDefault(ServerCommandSource source, UUID requestedId) {
        Entry entry = authorizedEntry(source, requestedId, "change this setup");
        if (entry == null) return 0;
        if (entry.setup.home() == null) return reject(source, "Set a home first; the default area is the nine chunks around it.");
        return editSetup(source, entry, setup -> setup.withArea(WorkSetup.WorkRegion.around(setup.home().position())), "default nine-chunk area around home");
    }

    int storageSet(ServerCommandSource source, UUID requestedId) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) return reject(source, "Look at the container as a player.");
        Entry entry = authorizedEntry(source, requestedId, "change this setup");
        if (entry == null) return 0;
        if (!player.getWorld().getRegistryKey().getValue().toString().equals(entry.setup.dimension())) {
            return reject(source, "Storage must be in the companion's own dimension (" + entry.setup.dimension() + ").");
        }
        if (!(player.raycast(6, 0, false) instanceof BlockHitResult hit)) return reject(source, "Look at a chest or barrel within six blocks.");
        return storageSetAt(source, entry, player.getWorld(), hit.getBlockPos());
    }

    /** Assigns the container at {@code pos}, in the source's own world, as the player's storage. */
    int storageSetAt(ServerCommandSource source, UUID requestedId, BlockPos pos) {
        Entry native_ = requestedId != null ? entries.get(requestedId) : null;
        if (native_ != null && native_.nativeState != null) return nativeStorage(source, requestedId, pos);
        Entry entry = authorizedEntry(source, requestedId, "change this setup");
        if (entry == null) return 0;
        if (!source.getWorld().getRegistryKey().getValue().toString().equals(entry.setup.dimension())) {
            return reject(source, "Storage must be in the companion's own dimension (" + entry.setup.dimension() + ").");
        }
        return storageSetAt(source, entry, source.getWorld(), pos);
    }

    private int storageSetAt(ServerCommandSource source, Entry entry, net.minecraft.world.World world, BlockPos pos) {
        StorageTargets.Observation observed = StorageTargets.observe(world, pos);
        if (observed == null || !observed.container()) return reject(source, "That block is not a container.");
        return editSetup(source, entry, setup -> setup.withStorage(new WorkSetup.StorageTarget(
                new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()), observed.blockId(), observed.version())),
                "storage " + observed.blockId() + " at " + pos.toShortString());
    }

    int patchSet(ServerCommandSource source, UUID requestedId, BlockPos a, BlockPos b, String blockId) {
        Entry entry = authorizedEntry(source, requestedId, "assign a resource patch");
        if (entry == null) return 0;
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) return reject(source, "Unknown block " + blockId + ".");
        var min = new WorkSetup.BlockPoint(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        var max = new WorkSetup.BlockPoint(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        return editSetup(source, entry, setup -> setup.withPatch(new WorkSetup.ResourcePatch(min, max, id.toString())),
                "resource patch " + id + " " + point(min) + " to " + point(max));
    }

    private Entry authorizedEntry(ServerCommandSource source, UUID requestedId, String verb) {
        requireThread();
        if (!ready || !running) { reject(source, "Kindred is loading its saved records."); return null; }
        UUID player = source.getEntity() instanceof ServerPlayerEntity entity ? entity.getUuid() : null;
        if (player == null && !source.hasPermissionLevel(2)) { reject(source, "Owner or operator controls only."); return null; }
        Entry entry = requestedId != null ? entries.get(requestedId) : byOwner(player);
        if (entry == null && requestedId == null) {
            var active = entries.values().stream()
                    .filter(e -> e.snapshot().lifecycle() != LifecycleState.REMOVED && e.nativeState == null)
                    .toList();
            if (active.size() == 1) {
                entry = active.get(0);
            }
        }
        if (entry == null) { reject(source, "No companion found. Specify an AI player name or UUID."); return null; }
        if (entry.nativeState != null) { reject(source, "Use native lifecycle controls; legacy skill/setup integration is unavailable for this body."); return null; }
        if (!Authorization.mayControl(new Actor(player, source.hasPermissionLevel(2)), entry.snapshot())) {
            reject(source, "Only the owner or an operator may " + verb + ".");
            return null;
        }
        return entry;
    }

    private int editSetup(ServerCommandSource source, Entry entry, java.util.function.UnaryOperator<WorkSetup> change, String description) {
        if (entry.metadataFault || entry.snapshot().lifecycle() == LifecycleState.REMOVED) return reject(source, "This companion's setup is unavailable.");
        if (!entry.setupWrite.isDone()) return reject(source, "The previous setup change is still being saved.");
        WorkSetup previous = entry.setup;
        WorkSetup changed;
        try {
            changed = change.apply(previous);
        } catch (IllegalArgumentException invalid) {
            return reject(source, invalid.getMessage());
        }
        entry.setup = changed; // Narrower or changed authority applies immediately; nothing autonomous uses it yet.
        entry.setupWrite = store.saveWorkSetup(changed, previous.revision());
        entry.setupWrite.whenComplete((saved, failure) -> server.execute(() -> {
            if (!running) return;
            if (failure != null) {
                entry.metadataFault = true;
                halt(entry, Actor.console(), "Setup save failed; companion stopped for storage inspection.");
                KindredMod.LOGGER.error("Work setup write failed for {}", entry.data.id(), failure);
                source.sendError(Text.literal("The setup could not be saved. The companion has stopped."));
            } else feedback(source, "Saved " + entry.data.displayName() + "'s " + description + ". " + changed.explanation());
        }));
        feedback(source, "Saving the companion's setup.");
        return 1;
    }

    private String storageStatus(Entry entry) {
        if (entry.body == null) return "body not loaded; not verified";
        return StorageTargets.explain(StorageTargets.validate(entry.body.getWorld(), entry.setup.storage()));
    }

    private static String point(WorkSetup.BlockPoint p) { return p.x() + "," + p.y() + "," + p.z(); }

    // ----- Block perception (F-24) -----

    /** Nine chunks at most around the body, five sections tall; the work area clips it further. */
    static final SectionPlanner.Range PERCEPTION_RANGE = SectionPlanner.DEFAULT;

    /** Blocks the companion is allowed to look for: its designated resource, its assigned container and an explicit look. */
    private static Set<String> interest(Entry entry) {
        Set<String> ids = new LinkedHashSet<>();
        if (entry.setup.patch() != null) ids.add(entry.setup.patch().resourceId());
        if (entry.setup.storage() != null) ids.add(entry.setup.storage().blockId());
        if (entry.lookBlock != null) ids.add(entry.lookBlock);
        return Set.copyOf(ids);
    }

    /**
     * Runs only while the body is under a current lease (follow or a skill) or an owner asked it to look. It
     * examines a bounded number of chunk sections per tick, skips unloaded and uninteresting sections without
     * touching their blocks, and keeps observed facts with timestamps. Nothing here grants permission.
     */
    private void tickPerception(Entry entry, CompanionEntity body) {
        if (entry.metadataFault || entry.reconciliationBlocked || entry.setup.area() == null) return;
        boolean working = entry.lease != null && entry.arbiter.isCurrent(entry.lease);
        if (!working && !entry.lookPending) return;
        ServerWorld world = (ServerWorld) body.getWorld();
        if (!world.getRegistryKey().getValue().toString().equals(entry.setup.dimension())) {
            if (entry.lookPending) finishLook(entry, entry.data.displayName() + " is not in its own dimension; nothing was observed.");
            return;
        }
        Set<String> interest = interest(entry);
        if (interest.isEmpty()) {
            if (entry.lookPending) finishLook(entry, "Nothing to look for: name a block, or assign a patch or storage first.");
            return;
        }
        if (entry.perception == null || entry.perceptionSource == null || entry.perceptionSource.world() != world) {
            entry.perception = new BlockPerception(entry.setup.dimension(), BlockPerception.DEFAULT, PlayerRuntime::now, PlayerRuntime::nanos);
            entry.perceptionSource = new ChunkSectionSource(world);
            entry.perceptionCenter = null;
        }
        BlockPos pos = body.getBlockPos();
        var here = new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ());
        SectionKey center = SectionKey.of(here);
        if (!center.equals(entry.perceptionCenter) || entry.perceptionSetupRevision != entry.setup.revision()
                || !interest.equals(entry.perception.interest())) {
            List<SectionKey> plan = SectionPlanner.plan(entry.setup.area(), here, PERCEPTION_RANGE,
                    world.getBottomSectionCoord(), world.getTopSectionCoord());
            entry.perception.retarget(plan, interest);
            entry.perceptionCenter = center;
            entry.perceptionSetupRevision = entry.setup.revision();
        }
        BlockPerception.Stats stats = entry.perception.tick(entry.perceptionSource);
        if (entry.lookPending && stats.passes() > entry.lookPassesBefore) finishLook(entry, describePerception(entry, here, stats));
    }

    private String describePerception(Entry entry, WorkSetup.BlockPoint from, BlockPerception.Stats stats) {
        BlockObservations seen = entry.perception.snapshot();
        BlockPerception.PassStats pass = stats.lastPass();
        StringBuilder text = new StringBuilder(entry.data.displayName()).append(" sees (pass ").append(seen.version())
                .append(" over ").append(entry.perception.plan().size()).append(" sections in ").append(pass.ticks()).append(" ticks: ")
                .append(pass.scanned()).append(" walked, ").append(pass.skippedByPalette()).append(" skipped by palette, ")
                .append(pass.empty()).append(" empty, ").append(pass.unloaded()).append(" unloaded; max ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", stats.maxTickNanos() / 1_000_000.0)).append(" ms in one tick): ");
        for (String id : seen.interest()) {
            text.append(id).append(' ').append(seen.exposedCount(id)).append(" exposed of ").append(seen.matchCount(id)).append(" seen");
            List<BlockObservation> nearest = seen.nearest(id, from, 1);
            if (!nearest.isEmpty()) {
                BlockObservation closest = nearest.get(0);
                text.append(", nearest ").append(point(closest.position())).append(" (")
                        .append((int) Math.ceil(Math.sqrt(closest.squaredDistanceTo(from)))).append(" blocks)");
            }
            text.append("; ");
        }
        return text.append("observed facts only, inside the work area and loaded chunks; buried blocks are counted but never located, and none of this is permission to break or open anything.").toString();
    }

    private void finishLook(Entry entry, String message) {
        entry.lookPending = false;
        UUID requester = entry.lookRequester;
        entry.lookRequester = null;
        entry.lookBlock = null;
        if (requester != null) reply(requester, message);
        else KindredMod.LOGGER.info(message);
    }

    int look(ServerCommandSource source, UUID requestedId, String blockId) {
        Entry entry = authorizedEntry(source, requestedId, "ask what this companion sees");
        if (entry == null) return 0;
        if (entry.metadataFault || entry.reconciliationBlocked) return reject(source, entry.explanation());
        if (entry.body == null || !entry.body.isAlive()) return reject(source, entry.explanation() + " Perception needs a loaded body.");
        if (entry.setup.area() == null) return reject(source, "No work area to look in: " + WorkSetup.explain(WorkSetup.Field.AREA) + ".");
        if (!entry.body.getWorld().getRegistryKey().getValue().toString().equals(entry.setup.dimension())) {
            return reject(source, "The body is not in its own dimension (" + entry.setup.dimension() + ").");
        }
        if (blockId != null) {
            Identifier id = Identifier.tryParse(blockId);
            if (id == null || !Registries.BLOCK.containsId(id)) return reject(source, "Unknown block " + blockId + ".");
        }
        if (entry.lookPending) return reject(source, "Already looking; the result arrives when the current pass completes.");
        entry.lookBlock = blockId;
        Set<String> interest = interest(entry);
        if (interest.isEmpty()) {
            entry.lookBlock = null;
            return reject(source, "Nothing to look for: name a block, or assign a patch or storage first.");
        }
        entry.lookPending = true;
        entry.lookRequester = source.getEntity() instanceof ServerPlayerEntity player ? player.getUuid() : null;
        entry.lookPassesBefore = entry.perception == null ? 0 : entry.perception.stats().passes();
        feedback(source, "Looking for " + String.join(", ", interest) + " in the loaded work-area chunks around the body (at most nine chunks, "
                + (16 * (2 * PERCEPTION_RANGE.verticalSections() + 1)) + " blocks of height). The result follows when the pass completes; it is an observation, not permission.");
        return 1;
    }

    int mode(ServerCommandSource source, UUID requestedId, boolean companionMode) {
        Entry entry = authorizedEntry(source, requestedId, "change this mode");
        if (entry == null) return 0;
        feedback(source, "Saving the mode. Traits, inventory and allowance stay as they are.");
        store.mode(entry.data.id()).whenComplete((current, failure) -> server.execute(() -> {
            if (!running || failure != null || current == null) {
                source.sendError(Text.literal("The mode could not be read."));
                return;
            }
            OperatingMode next = current.withMode(companionMode ? OperatingMode.Mode.COMPANION : OperatingMode.Mode.INDEPENDENT);
            store.saveMode(next, current.revision()).whenComplete((saved, saveFailure) -> server.execute(() -> {
                if (saveFailure != null) source.sendError(Text.literal("The mode could not be saved."));
                else {
                    entry.mode = next;
                    entry.data = entry.data.withRevisions(Math.incrementExact(entry.data.intentRevision()), entry.data.taskRevision());
                    if (entry.runner != null && runnerBusy(entry)) {
                        entry.runner.pause();
                    }
                    persist(entry);
                    String personalMsg = next.mode() == OperatingMode.Mode.INDEPENDENT
                            ? " Personal progression is unavailable until milestone progression is installed."
                            : "";
                    feedback(source, entry.data.displayName() + " is now " + next.mode().name().toLowerCase(java.util.Locale.ROOT)
                            + ". Scope is " + next.scope().name().toLowerCase(java.util.Locale.ROOT) + "."
                            + personalMsg + " Major boss fights stay excluded.");
                }
            }));
        }));
        return 1;
    }

    int scope(ServerCommandSource source, UUID requestedId, boolean homeScope) {
        Entry entry = authorizedEntry(source, requestedId, "change this scope");
        if (entry == null) return 0;
        feedback(source, "Saving the scope restriction.");
        store.mode(entry.data.id()).whenComplete((current, failure) -> server.execute(() -> {
            if (!running || failure != null || current == null) {
                source.sendError(Text.literal("The mode and scope could not be read."));
                return;
            }
            OperatingMode next = current.withScope(homeScope ? OperatingMode.Scope.HOME : OperatingMode.Scope.APPROVED);
            store.saveMode(next, current.revision()).whenComplete((saved, saveFailure) -> server.execute(() -> {
                if (saveFailure != null) source.sendError(Text.literal("The scope could not be saved."));
                else {
                    entry.mode = next;
                    feedback(source, entry.data.displayName() + " scope is now " + next.scope().name().toLowerCase(java.util.Locale.ROOT)
                            + ". Mode is " + next.mode().name().toLowerCase(java.util.Locale.ROOT) + ".");
                }
            }));
        }));
        return 1;
    }

    int addGoal(ServerCommandSource source, UUID requestedId, String description, String outcome, int priority) {
        Entry entry = authorizedEntry(source, requestedId, "add a project goal");
        if (entry == null) return 0;
        GoalRecord record = new GoalRecord(UUID.randomUUID(), entry.data.id(), GoalRecord.Origin.OWNER,
                description, outcome, priority, GoalRecord.Status.OPEN, 0);
        feedback(source, "Adding project goal for " + entry.data.displayName() + "...");
        store.addGoal(record).whenComplete((saved, failure) -> server.execute(() -> {
            if (failure != null || saved == null) source.sendError(Text.literal("Goal could not be saved."));
            else feedback(source, "Added goal " + saved.id() + ": " + saved.description() + " (priority " + saved.priority() + ").");
        }));
        return 1;
    }

    int listGoals(ServerCommandSource source, UUID requestedId) {
        Entry entry = authorizedEntry(source, requestedId, "list project goals");
        if (entry == null) return 0;
        store.goals(entry.data.id()).whenComplete((goals, failure) -> server.execute(() -> {
            if (failure != null || goals == null) {
                source.sendError(Text.literal("Goals could not be retrieved."));
                return;
            }
            if (goals.isEmpty()) {
                feedback(source, entry.data.displayName() + " has no saved goals.");
                return;
            }
            StringBuilder sb = new StringBuilder(entry.data.displayName() + " goals (" + goals.size() + "): ");
            for (GoalRecord g : goals) {
                sb.append("[").append(g.status().name().toLowerCase(java.util.Locale.ROOT))
                  .append("] ").append(g.description()).append(" -> ").append(g.outcome())
                  .append(" (pri: ").append(g.priority()).append("); ");
            }
            feedback(source, sb.toString());
        }));
        return 1;
    }

    int listMemories(ServerCommandSource source, UUID requestedId) {
        Entry entry = authorizedEntry(source, requestedId, "list memories");
        if (entry == null) return 0;
        store.memories(entry.data.id()).whenComplete((memories, failure) -> server.execute(() -> {
            if (failure != null || memories == null) {
                source.sendError(Text.literal("Memories could not be retrieved."));
                return;
            }
            if (memories.isEmpty()) {
                feedback(source, entry.data.displayName() + " has no recorded memories.");
                return;
            }
            StringBuilder sb = new StringBuilder(entry.data.displayName() + " memories (" + memories.size() + "): ");
            for (MemoryNote m : memories) {
                sb.append("[").append(m.kind().name().toLowerCase(java.util.Locale.ROOT))
                  .append("] (").append(m.id()).append(") ").append(m.text())
                  .append(" (from ").append(m.provenance()).append("); ");
            }
            feedback(source, sb.toString());
        }));
        return 1;
    }

    int addMemory(ServerCommandSource source, UUID requestedId, String kindName, String text) {
        Entry entry = authorizedEntry(source, requestedId, "record a memory");
        if (entry == null) return 0;
        MemoryNote.Kind kind;
        try {
            kind = MemoryNote.Kind.valueOf(kindName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return reject(source, "Kind must be PREFERENCE, FACT, or EPISODE.");
        }
        UUID actorId = source.getEntity() != null ? source.getEntity().getUuid() : new UUID(0, 0);
        MemoryNote note = new MemoryNote(UUID.randomUUID(), entry.data.id(), kind, text, "owner:" + actorId, 0);
        store.addMemory(note).whenComplete((saved, failure) -> server.execute(() -> {
            if (failure != null || saved == null) source.sendError(Text.literal("Memory could not be saved."));
            else feedback(source, "Recorded memory (" + saved.id() + "): " + saved.text() + ".");
        }));
        return 1;
    }

    int forgetMemory(ServerCommandSource source, UUID requestedId, UUID memoryId) {
        Entry entry = authorizedEntry(source, requestedId, "forget a memory");
        if (entry == null) return 0;
        if (memoryId == null) return reject(source, "Supply the memory UUID to forget.");
        store.forgetMemory(entry.data.id(), memoryId).whenComplete((success, failure) -> server.execute(() -> {
            if (failure != null || Boolean.FALSE.equals(success)) {
                source.sendError(Text.literal("Memory could not be deleted (not found or database error)."));
            } else {
                feedback(source, "Forgot memory " + memoryId + " for " + entry.data.displayName() + ".");
            }
        }));
        return 1;
    }

    int limitStatus(ServerCommandSource source, UUID requestedId) {
        Entry entry = authorizedEntry(source, requestedId, "view schedule limits");
        if (entry == null) return 0;
        store.limit(entry.data.id()).whenComplete((limit, failure) -> server.execute(() -> {
            if (failure != null || limit == null) {
                source.sendError(Text.literal("Activity limits could not be retrieved."));
                return;
            }
            long hours = limit.capMillis() / (3600 * 1000);
            long minutes = (limit.capMillis() % (3600 * 1000)) / (60 * 1000);
            feedback(source, entry.data.displayName() + " schedule limits: daily cap "
                    + hours + "h " + minutes + "m, offline work " + (limit.offlineWork() ? "enabled" : "disabled")
                    + ", timezone " + limit.timezone() + ".");
        }));
        return 1;
    }

    int setLimitCap(ServerCommandSource source, UUID requestedId, int hours) {
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator may modify daily activity caps.");
        Entry entry = authorizedEntry(source, requestedId, "change daily cap");
        if (entry == null) return 0;
        long capMs = (long) hours * 3600 * 1000;
        store.limit(entry.data.id()).whenComplete((limit, failure) -> server.execute(() -> {
            if (failure != null || limit == null) {
                source.sendError(Text.literal("Could not read current limit."));
                return;
            }
            ActivityLimit next = new ActivityLimit(limit.companionId(), capMs, limit.offlineWork(), limit.timezone(), limit.revision() + 1);
            store.saveLimit(next, limit.revision()).whenComplete((saved, saveFailure) -> server.execute(() -> {
                if (saveFailure != null) source.sendError(Text.literal("Failed to save daily cap."));
                else feedback(source, entry.data.displayName() + " daily activity cap set to " + hours + " hours.");
            }));
        }));
        return 1;
    }

    int setLimitTimezone(ServerCommandSource source, UUID requestedId, String zone) {
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator may change accounting timezones.");
        Entry entry = authorizedEntry(source, requestedId, "change accounting timezone");
        if (entry == null) return 0;
        try { java.time.ZoneId.of(zone); }
        catch (java.time.DateTimeException invalid) { return reject(source, "Unknown timezone: " + zone); }
        // Do not reset spent time by editing a zone before the period-transition protocol exists.
        return reject(source, "Timezone changes are unavailable until accounting-period transitions are implemented (F-11).");
    }

    int setLimitOffline(ServerCommandSource source, UUID requestedId, boolean offline) {
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator may toggle offline work permissions.");
        Entry entry = authorizedEntry(source, requestedId, "change offline work permission");
        if (entry == null) return 0;
        store.limit(entry.data.id()).whenComplete((limit, failure) -> server.execute(() -> {
            if (failure != null || limit == null) {
                source.sendError(Text.literal("Could not read current limit."));
                return;
            }
            ActivityLimit next = new ActivityLimit(limit.companionId(), limit.capMillis(), offline, limit.timezone(), limit.revision() + 1);
            store.saveLimit(next, limit.revision()).whenComplete((saved, saveFailure) -> server.execute(() -> {
                if (saveFailure != null) source.sendError(Text.literal("Failed to save offline work permission."));
                else feedback(source, entry.data.displayName() + " offline work is now " + (offline ? "enabled" : "disabled") + ".");
            }));
        }));
        return 1;
    }

    int journal(ServerCommandSource source, UUID requestedId) {
        Entry entry = authorizedEntry(source, requestedId, "read this journal");
        if (entry == null) return 0;
        feedback(source, "Reading the journal.");
        store.eventsSince(entry.data.id(), 0, 40).thenCombine(store.unresolvedActions(entry.data.id()), (events, unresolved) -> {
            int verified = (int) events.stream().filter(event -> event.type().equals("WORK_VERIFIED")).count();
            int failed = (int) events.stream().filter(event -> event.type().equals("WORK_BLOCKED")).count();
            int uncertain = unresolved.size();
            java.util.List<String> lines = events.stream()
                    .filter(event -> event.type().equals("WORK_VERIFIED") || event.type().equals("WORK_BLOCKED"))
                    .map(event -> event.detail()).limit(5).toList();
            return JournalSummary.render(entry.data.displayName(), verified, failed, uncertain, lines);
        }).whenComplete((text, failure) -> server.execute(() -> {
            if (failure != null || text == null) source.sendError(Text.literal("The journal could not be read."));
            else feedback(source, text + " Reading it does not start anything.");
        }));
        return 1;
    }

    void entityDied(CompanionEntity body) {
        requireThread();
        Entry entry = entries.get(body.companionId());
        if (entry == null || entry.body != body) return;
        halt(entry, Actor.console(), "Companion died; requires operator or owner recovery.");
        teamCoordinator.handlePlayerDeath(entry.data.id());
        entry.data = entry.data.withLifecycle(LifecycleState.FAULTED);
        entry.blocker = "Companion body was destroyed. Inventory dropped at death location. Run /companion recover to restore.";
        entry.body = null;
        persist(entry);
    }

    // ----- F-14 Suspend and Remove Lifecycle Controls -----

    int suspend(ServerCommandSource source, UUID requestedId) {
        requireThread();
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator may suspend a companion.");
        Entry entry = authorizedEntry(source, requestedId, "suspend this companion");
        if (entry == null) return 0;
        if (entry.snapshot().lifecycle() == LifecycleState.REMOVED) return reject(source, "A removed companion cannot be suspended.");
        halt(entry, Actor.console(), "Suspended by operator; requires /companion recover to resume.");
        entry.data = entry.data.withLifecycle(LifecycleState.FAULTED);
        persist(entry);
        feedback(source, entry.data.displayName() + " has been suspended by operator. All controls are locked until /companion recover.");
        return 1;
    }

    int recover(ServerCommandSource source, UUID requestedId) {
        requireThread();
        Entry entry = authorizedEntry(source, requestedId, "recover this companion");
        if (entry == null) return 0;
        if (entry.snapshot().lifecycle() == LifecycleState.REMOVED) return reject(source, "A removed companion cannot be recovered.");
        // A shared task held for it through a restart, its action uncertain, is free again for any player once an
        // operator has recovered it, and its reservation released.
        teamCoordinator.handlePlayerStop(entry.data.id());
        entry.metadataFault = false;
        entry.reconciliationBlocked = false;
        entry.data = entry.data.withLifecycle(LifecycleState.SLEEPING).withStopLatched(false);
        if (entry.body != null && entry.body.isAlive()) {
            entry.body.ownerStopped(false);
        } else {
            entry.body = null;
            entry.data = entry.data.withEntityId(null);
        }
        entry.blocker = "Recovered; sleeping and ready for controls.";
        persist(entry);
        feedback(source, entry.data.displayName() + " recovered. Lifecycle reset to sleeping.");
        return 1;
    }

    int requestRemoval(ServerCommandSource source, UUID requestedId) {
        requireThread();
        Entry entry = authorizedEntry(source, requestedId, "remove this companion");
        if (entry == null) return 0;
        if (entry.snapshot().lifecycle() == LifecycleState.REMOVED) return reject(source, "This companion has already been removed.");
        UUID actorId = source.getEntity() != null ? source.getEntity().getUuid() : new UUID(0, 0);
        UUID token = UUID.randomUUID();
        removalTokens.put(token, new RemovalToken(token, entry.data.id(), actorId, now() + 60_000));
        MutableText msg = Text.literal("To permanently remove " + entry.data.displayName() + " (")
                .append(formatMessageWithClickableUuids(entry.data.id().toString()))
                .append("), ")
                .append(Text.literal("[CLICK TO CONFIRM REMOVAL]")
                        .styled(style -> style
                                .withColor(Formatting.RED)
                                .withBold(true)
                                .withUnderline(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aiplayer confirm-remove " + token))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to permanently remove " + entry.data.displayName())))))
                .append(" or run ")
                .append(Text.literal("/aiplayer confirm-remove " + token)
                        .styled(style -> style
                                .withColor(Formatting.YELLOW)
                                .withUnderline(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/aiplayer confirm-remove " + token))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to paste command into chat")))))
                .append(" within 60 seconds. Active tasks and claims will be released; inventory dropped.");
        feedback(source, msg);
        return 1;
    }

    int confirmRemoval(ServerCommandSource source, UUID token) {
        requireThread();
        if (token == null) return reject(source, "Provide the confirmation token: /companion confirm-remove <token>");
        RemovalToken found = removalTokens.remove(token);
        long now = now();
        UUID actorId = source.getEntity() != null ? source.getEntity().getUuid() : new UUID(0, 0);
        if (found == null || !found.matches(token, found.companionId(), actorId, now)) {
            return reject(source, "Invalid or expired confirmation token. Request removal again via /companion remove.");
        }
        Entry entry = entries.get(found.companionId());
        if (entry == null) return reject(source, "Companion not found.");
        Actor actor = new Actor(actorId, source.hasPermissionLevel(2));
        halt(entry, actor, "Permanently removed.");
        if (entry.lease != null) claims.releaseTask(entry.lease.taskId());
        admission.release(entry.data.id());
        entry.data = entry.data.withLifecycle(LifecycleState.REMOVED);
        if (entry.body != null && entry.body.isAlive()) {
            for (int slot = 0; slot < entry.body.inventory().size(); slot++) {
                net.minecraft.item.ItemStack stack = entry.body.inventory().getStack(slot);
                if (!stack.isEmpty()) {
                    entry.body.dropStack(stack);
                    entry.body.inventory().setStack(slot, net.minecraft.item.ItemStack.EMPTY);
                }
            }
            entry.body.discard();
        }
        store.clearCheckpoint(entry.data.id());
        persist(entry);
        feedback(source, entry.data.displayName() + " (" + entry.data.id() + ") permanently removed. Claims and tickets released.");
        return 1;
    }

    int quirks(ServerCommandSource source, UUID requestedId) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading its saved records.");
        UUID player = source.getEntity() instanceof ServerPlayerEntity entity ? entity.getUuid() : null;
        if (player == null && !source.hasPermissionLevel(2)) return reject(source, "Owner or operator controls only.");
        Entry entry = requestedId == null ? byOwner(player) : entries.get(requestedId);
        if (entry == null) return reject(source, "No companion found.");
        if (!Authorization.mayControl(new Actor(player, source.hasPermissionLevel(2)), entry.snapshot())) {
            return reject(source, "Only the owner or an operator may inspect this profile.");
        }
        long now = now();
        Set<Mannerisms.Quirk> active = entry.mannerisms.active(entry.profile.personality(), now);
        StringBuilder text = new StringBuilder(entry.data.displayName() + "'s habits:");
        for (Mannerisms.Quirk quirk : Mannerisms.Quirk.values()) {
            text.append(' ').append(quirk.commandName()).append(' ')
                    .append(entry.mannerisms.profile().setting(quirk).name().toLowerCase(java.util.Locale.ROOT))
                    .append(active.contains(quirk) ? " (doing it)" : " (not doing it)");
            entry.mannerisms.accommodationRemaining(quirk, now).ifPresent(remaining ->
                    text.append(" [paused for ").append(Math.max(1, remaining / 60_000)).append(" more min]"));
            text.append(';');
        }
        text.append(" sprint-jumping, fidgets and glances only happen inside an authorized movement command.");
        feedback(source, text.toString());
        return 1;
    }

    int quirk(ServerCommandSource source, UUID requestedId, Mannerisms.Quirk quirk, Mannerisms.Setting setting) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading its saved records.");
        UUID player = source.getEntity() instanceof ServerPlayerEntity entity ? entity.getUuid() : null;
        if (player == null && !source.hasPermissionLevel(2)) return reject(source, "Owner or operator controls only.");
        Entry entry = requestedId == null ? byOwner(player) : entries.get(requestedId);
        if (entry == null) return reject(source, "No companion found.");
        Actor actor = new Actor(player, source.hasPermissionLevel(2));
        if (!Authorization.mayControl(actor, entry.snapshot())) return reject(source, "Only the owner or an operator may change these habits.");
        if (entry.metadataFault || entry.snapshot().lifecycle() == LifecycleState.REMOVED) return reject(source, "This companion's profile is unavailable.");
        if (!entry.mannerismWrite.isDone()) return reject(source, "The previous habit change is still being saved.");
        long expected = entry.mannerisms.profile().revision();
        Mannerisms changed = entry.mannerisms.edit(quirk, setting); // Takes effect at the next movement tick; clears any chat promise.
        entry.mannerismWrite = store.saveMannerisms(changed, expected);
        entry.mannerismWrite.whenComplete((saved, failure) -> server.execute(() -> {
            if (!running) return;
            if (failure != null) {
                entry.metadataFault = true;
                halt(entry, Actor.console(), "Habit save failed; companion stopped for storage inspection.");
                KindredMod.LOGGER.error("Mannerism write failed for {}", entry.data.id(), failure);
                source.sendError(Text.literal("The habit setting could not be saved. The companion has stopped."));
            } else {
                boolean doing = entry.mannerisms.active(entry.profile.personality(), now()).contains(quirk);
                feedback(source, "Saved " + entry.data.displayName() + "'s " + quirk.commandName() + " = "
                        + setting.name().toLowerCase(java.util.Locale.ROOT) + "; currently " + (doing ? "doing it" : "not doing it")
                        + ". Habits never change permissions, tasks, speed limits or allowance.");
            }
        }));
        feedback(source, "Saving the companion's habit setting.");
        return 1;
    }

    /** Bridges social chat and attention to the runtime's habit state; every effect is bounded by the core policies. */
    private final class HabitHooks implements CompanionSocials.Habits {
        @Override
        public java.util.Optional<String> feedback(ServerPlayerEntity speaker, CompanionSocials.Participant companion, String message, long now) {
            Entry entry = entries.get(companion.id());
            var quirk = QuirkFeedback.detect(message);
            if (entry == null || quirk.isEmpty()) return java.util.Optional.empty();
            Mannerisms.Quirk subject = quirk.orElseThrow();
            boolean owner = speaker.getUuid().equals(entry.data.ownerId());
            boolean active = entry.mannerisms.active(entry.profile.personality(), now).contains(subject);
            var response = QuirkFeedback.respond(subject, entry.profile.personality(),
                    owner ? QuirkFeedback.Speaker.OWNER : QuirkFeedback.Speaker.PLAYER, active);
            if (response.decision() != QuirkFeedback.Decision.ACCOMMODATE) return java.util.Optional.of(response.reply());
            String key = speaker.getUuid() + ":" + companion.id();
            Long last = accommodationWindows.get(key);
            if (last != null && now - last < QuirkFeedback.SPEAKER_WINDOW_MILLIS) {
                return java.util.Optional.of("I already gave that a rest for you not long ago. I'm going to be myself for a bit.");
            }
            if (!entry.mannerisms.accommodate(subject, now, response.accommodationMillis())) return java.util.Optional.of(response.reply());
            accommodationWindows.remove(key);
            accommodationWindows.put(key, now);
            while (accommodationWindows.size() > 512) accommodationWindows.remove(accommodationWindows.keySet().iterator().next());
            return java.util.Optional.of(response.reply());
        }

        @Override
        public AttentionPolicy.Phase phase(UUID companionId) {
            Entry entry = entries.get(companionId);
            if (entry == null || entry.lease == null || entry.arbiter == null || !entry.arbiter.isCurrent(entry.lease)) return null;
            if (entry.runner != null && runnerBusy(entry)) {
                return "wait".equals(entry.skillId) ? AttentionPolicy.Phase.WAITING : AttentionPolicy.Phase.WALKING_UNCOMMITTED;
            }
            if (!entry.following || entry.follow == null) return null;
            return entry.follow.waiting() ? AttentionPolicy.Phase.WAITING : AttentionPolicy.Phase.WALKING_UNCOMMITTED;
        }

        @Override
        public boolean gazeOwned(UUID companionId) {
            Entry entry = entries.get(companionId);
            if (entry == null) return true;
            // Navigation owns the gaze while moving; a settled follower or a waiting companion may look around.
            if (entry.runner != null && runnerBusy(entry)) return !"wait".equals(entry.skillId);
            return entry.follow == null || !entry.follow.waiting();
        }

        @Override
        public Set<Mannerisms.Quirk> activeQuirks(UUID companionId, long now) {
            Entry entry = entries.get(companionId);
            return entry == null ? Set.of() : entry.mannerisms.active(entry.profile.personality(), now);
        }

        @Override
        public void glance(UUID companionId, UUID subject, long durationMillis, long now) {
            Entry entry = entries.get(companionId);
            if (entry == null || entry.body == null) return;
            entry.glanceTarget = subject;
            entry.glanceUntilAge = entry.body.age + (int) Math.max(1, durationMillis / 50);
        }
    }

    int talk(ServerCommandSource source, UUID requestedId, String message) {
        requireThread();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) return reject(source, "Talk as a nearby player.");
        Entry entry = requestedId == null ? byOwner(player.getUuid()) : entries.get(requestedId);
        CompanionSocials.Participant participant = entry == null ? null : socialParticipant(entry.data.id());
        if (!socials.canTalk(player, participant)) return reject(source, "Conversation needs a visible companion within eight blocks and its owner's permission for public chat.");
        if (!socials.reply(player, participant, message)) return reject(source, "Give the conversation a few seconds before another message.");
        return 1;
    }

    /** Legacy command refuses until native combat skills are qualified. */
    int duel(ServerCommandSource source, UUID requestedId) {
        return reject(source, "Combat is unavailable until native player mechanics and arbiter-controlled combat skills qualify.");
    }

    /** Damage remains native; the old unleased retaliation controller is never dispatched. */
    void playerAttackedCompanion(ServerPlayerEntity attacker, CompanionEntity body) { requireThread(); }

    void chat(UUID senderId, String message) {
        requireThread();
        if (!ready || !running || message.length() > 256) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(senderId);
        if (player == null) return;
        // A word on what it asked of its team, of what is gone from a store (E-43).
        for (Entry entry : entries.values()) storageAnswered(entry, player, message);
        CompanionSocials.Participant matched = null;
        Entry spokenTo = null;
        String addressed = null;
        for (Entry entry : entries.values()) {
            var candidate = socialParticipant(entry.data.id());
            if (candidate == null || !socials.canTalk(player, candidate)) continue;
            var text = SocialDialogue.addressedMessage(candidate.name(), message);
            if (text.isEmpty()) continue;
            if (matched != null) return; // Ambiguous names need /companion talkto <UUID>; never trigger a chorus.
            matched = candidate;
            spokenTo = entry;
            addressed = text.orElseThrow();
        }
        if (matched != null) {
            // Spoken to by name: it looks at the speaker as it waits (E-33a).
            spokenTo.addressedBy = senderId;
            spokenTo.addressedAt = server.getTicks();
            socials.reply(player, matched, addressed);
        }
    }

    private CompanionSocials.Participant socialParticipant(UUID id) {
        Entry entry = entries.get(id);
        if (!running || !ready || entry == null || entry.metadataFault || entry.reconciliationBlocked
                || entry.snapshot().lifecycle() == LifecycleState.FAULTED || entry.snapshot().lifecycle() == LifecycleState.REMOVED) return null;
        // A native player takes part as itself whenever it is in the world and not stopped, working or not: it is a
        // player standing there, not a body that speaks only while a task holds it.
        if (entry.body == null) {
            var player = entry.nativeBody != null && entry.nativeBody.connected() ? entry.nativeBody.entity() : null;
            if (player == null || !player.isAlive() || entry.nativeState == null || entry.nativeState.phase() != StoredNativePlayer.Phase.ESTABLISHED) return null;
            return new CompanionSocials.Participant(entry.data.id(), entry.data.ownerId(), entry.data.displayName(), player, entry.profile,
                    !entry.snapshot().stopLatched());
        }
        if (!entry.body.isAlive()) return null;
        boolean working = entry.following || (entry.runner != null && runnerBusy(entry));
        return new CompanionSocials.Participant(entry.data.id(), entry.data.ownerId(), entry.data.displayName(),
                entry.body, entry.profile, working && !entry.snapshot().stopLatched()
                && entry.lease != null && entry.arbiter.isCurrent(entry.lease));
    }

    /**
     * The run divided between the players on it (E-40a): the native players with a goal in its world within
     * {@link #TEAM_RANGE}, none in a match. Before the portal is lit, the one with the best pickaxe (or with a portal
     * already under way) builds the team's one portal and the rest get iron, food and wood for the run and wait for it;
     * once a teammate's portal is lit, each takes it as its own. Each says its part as it takes it up, once. Only the
     * parts before the portal steer play yet; the later ones (the Nether, the End's kit, the stronghold) are neither
     * acted on nor said, so nothing is promised that is not done. A player alone is left its own route.
     */
    private void divideTheRun(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var team = new ArrayList<Entry>();
        team.add(entry);
        for (Entry other : entries.values()) {
            if (other == entry || other.goal == dev.kindred.core.survival.SurvivalGoal.NONE || other.campaign == null) continue;
            var mate = other.nativeBody != null && other.nativeBody.connected() ? other.nativeBody.entity() : null;
            if (mate == null || !mate.isAlive() || mate.getWorld() != player.getWorld() || mate.squaredDistanceTo(player) > TEAM_RANGE * TEAM_RANGE
                    || other.snapshot().stopLatched() || matches.running(mate.getUuid()).isPresent()) continue;
            team.add(other);
        }
        if (team.size() < 2 || matches.running(player.getUuid()).isPresent()) {
            entry.campaign.team(dev.kindred.core.survival.PortalRunPlanner.Team.ALONE);
            entry.runPart = null;
            return;
        }
        // A teammate's lit portal is the run's one portal.
        boolean lit = false;
        for (Entry member : team) {
            var mate = member.nativeBody.entity();
            if (member.campaign.portalBegun() && member.campaign.litPortal(mate).isPresent()) {
                lit = true;
                if (member != entry) member.campaign.portalSite().ifPresent(entry.campaign::adoptPortal);
            }
        }
        var players = new ArrayList<dev.kindred.core.team.RunDivision.Player>();
        for (Entry member : team) {
            var mate = member.nativeBody.entity();
            var kit = member(member.data.id(), member.data.displayName(), mate);
            players.add(new dev.kindred.core.team.RunDivision.Player(member.data.id(), member.data.displayName(), mate.getArmor(),
                    kit.kit().get(ShareOut.Slot.PICKAXE), mate.getInventory().count(net.minecraft.item.Items.GOLD_INGOT),
                    mate.getInventory().count(net.minecraft.item.Items.ENDER_EYE), member.campaign.portalBegun()));
        }
        var parts = dev.kindred.core.team.RunDivision.divide(players, new dev.kindred.core.team.RunDivision.Run(lit, 0, 0, 0, false, false));
        var part = parts.get(entry.data.id());
        var role = part.role();
        boolean steered = role == dev.kindred.core.team.RunDivision.Role.PORTAL || role == dev.kindred.core.team.RunDivision.Role.PROVISIONS;
        entry.campaign.team(new dev.kindred.core.survival.PortalRunPlanner.Team(role == dev.kindred.core.team.RunDivision.Role.PROVISIONS));
        if (steered && (entry.runPart == null || entry.runPart.role() != role)) {
            say(entry, part.says(), "");
            if (entry.partsTaken.size() < 16) entry.partsTaken.add(role + ": " + part.says());
            KindredMod.LOGGER.info("{} takes up its part of the run: {} ({})", entry.data.displayName(), role, part.why());
        }
        entry.runPart = steered ? part : null;
    }

    /** What a player last said aloud, for fixtures. */
    String lastSaid(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.lastSaid;
    }

    /** What a player's run wants it to have, for the value model's judgement of what it loots (E-42). */
    private java.util.Map<String, Integer> lootWanted(Entry entry) {
        if (entry.campaign == null || entry.nativeBody == null) return java.util.Map.of(dev.kindred.core.guide.Worth.FOOD, 6);
        return entry.campaign.lootWanted(entry.nativeBody.entity());
    }

    /** The parts of the team's run it has taken up, in order, each as said, for fixtures (E-40a). */
    List<String> partsTaken(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? List.of() : List.copyOf(entry.partsTaken);
    }

    /** The materials a find of which is shared out with the team (E-40a), as players share their ore. */
    private static final ShareOut.Material[] SHARED_FINDS = {ShareOut.Material.DIAMOND, ShareOut.Material.IRON, ShareOut.Material.GOLD};
    /** How far off a teammate may be to share a find with, and how long a hand-over is held for before it is let go. */
    static final double TEAM_RANGE = 48;
    static final long HAND_OVER_LEASE = 24_000;

    /**
     * A find shared out with the team as it comes (E-40a): when it holds more of a material than at its last reckoning,
     * what it holds unpromised is shared by {@link ShareOut} between it and its teammates (the other native players in its
     * world within {@link #TEAM_RANGE}, as all play together in co-op; none while it or they are in a match, whose own rules
     * hand out items), and if anyone else is better for some of it, the share-out
     * is saved as a project of hand-overs ({@link HandOvers}), each held by it with its material reserved, and it tells
     * its team what it found and who gets what. A first sight of what it holds is no find. Its hand-overs let go by a
     * stop or a lapsed hold are taken up again.
     */
    private void shareOutFinds(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        UUID self = entry.data.id();
        for (var task : teamCoordinator.listProjects().stream().flatMap(p -> p.tasks().values().stream()).toList()) {
            if (HandOvers.givenBy(task, self) && (task.state() == SharedTask.TaskState.OPEN || task.state() == SharedTask.TaskState.REVOKED)) {
                teamCoordinator.claimTask(task.id(), self, server.getTicks(), HAND_OVER_LEASE);
            }
        }
        for (var material : SHARED_FINDS) {
            String item = HandOvers.materialItem(material);
            int held = player.getInventory().count(Registries.ITEM.get(net.minecraft.util.Identifier.of(item)));
            Integer before = entry.heldFinds.put(material, held);
            String key = HandOvers.held(self, item);
            if (before == null || held != before) teamCoordinator.stockLedger().registerStock(key, held);
            if (before == null || held <= before || entry.sharing.contains(material)) continue;
            int free = held - teamCoordinator.stockLedger().reservedCount(key);
            if (free <= 0 || matches.running(player.getUuid()).isPresent()) continue;
            var members = new ArrayList<ShareOut.Member>();
            members.add(member(self, entry.data.displayName(), player));
            for (Entry other : entries.values()) {
                if (other == entry) continue;
                var mate = other.nativeBody != null && other.nativeBody.connected() ? other.nativeBody.entity() : null;
                if (mate == null || !mate.isAlive() || mate.getWorld() != player.getWorld() || mate.squaredDistanceTo(player) > TEAM_RANGE * TEAM_RANGE
                        || other.snapshot().stopLatched() || matches.running(mate.getUuid()).isPresent()) continue;
                members.add(member(other.data.id(), other.data.displayName(), mate));
            }
            var grants = members.size() < 2 ? List.<ShareOut.Grant>of() : ShareOut.share(members, material, free);
            KindredMod.LOGGER.info("{} found {}: holds {}, {} unpromised; {} teammates by; share-out {}", entry.data.displayName(), item, held, free,
                    members.size() - 1, grants);
            if (grants.stream().allMatch(g -> g.to().equals(self))) continue; // Nobody else is better for it: its own to use.
            var id = new ProjectId("share:" + self.toString().substring(0, 8) + ":" + material.name().toLowerCase(java.util.Locale.ROOT) + ":" + server.getTicks());
            var project = HandOvers.project(id, self, material, free, grants);
            entry.sharing.add(material);
            createSharedProject(project).whenComplete((saved, failure) -> {
                entry.sharing.remove(material);
                if (failure != null || !running || entries.get(self) != entry) return;
                for (var task : saved.tasks().values()) teamCoordinator.claimTask(task.id(), self, server.getTicks(), HAND_OVER_LEASE);
                entry.teamLine = saved.description();
                say(entry, saved.description(), "");
                entry.cachedRoutineCandidates = List.of();
                entry.routineRefreshPending = true;
            });
        }
    }

    /** A player as a share-out sees it: the armour it wears and the best sword, pickaxe and axe it has. */
    private static ShareOut.Member member(UUID id, String name, ServerPlayerEntity player) {
        var kit = new java.util.EnumMap<ShareOut.Slot, ShareOut.Material>(ShareOut.Slot.class);
        kit.put(ShareOut.Slot.HELMET, material(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD)));
        kit.put(ShareOut.Slot.CHESTPLATE, material(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST)));
        kit.put(ShareOut.Slot.LEGGINGS, material(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS)));
        kit.put(ShareOut.Slot.BOOTS, material(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET)));
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String path = Registries.ITEM.getId(stack.getItem()).getPath();
            ShareOut.Slot tool = path.endsWith("_pickaxe") ? ShareOut.Slot.PICKAXE : path.endsWith("_sword") ? ShareOut.Slot.SWORD
                    : path.endsWith("_axe") ? ShareOut.Slot.AXE : null;
            if (tool != null && material(stack).compareTo(kit.getOrDefault(tool, ShareOut.Material.NONE)) > 0) kit.put(tool, material(stack));
        }
        return new ShareOut.Member(id, name, kit, ShareOut.Work.GATHERING);
    }

    /** What a piece of kit is made of; wood and stone count as nothing better. */
    private static ShareOut.Material material(ItemStack stack) {
        if (stack.isEmpty()) return ShareOut.Material.NONE;
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("netherite_")) return ShareOut.Material.NETHERITE;
        if (path.startsWith("diamond_")) return ShareOut.Material.DIAMOND;
        if (path.startsWith("iron_")) return ShareOut.Material.IRON;
        if (path.startsWith("chainmail_")) return ShareOut.Material.CHAIN;
        if (path.startsWith("golden_")) return ShareOut.Material.GOLD;
        if (path.startsWith("leather_")) return ShareOut.Material.LEATHER;
        return ShareOut.Material.NONE;
    }

    /**
     * The next step of the pieces it promised from a find (E-40a): each made first at a table while it is by one
     * (sticks first for a tool or weapon), then carried to its teammate and tossed across from close by, each step an
     * ordinary recorded task; a piece for itself is done once made. Nothing to make one with here, or a teammate out of
     * its world, and it waits a minute; after six failed steps the hand-over is let go, its material free again.
     */
    private java.util.Optional<NativeTaskCandidate> handOverStep(Entry entry, NativePlayerBody body, SharedTask task) {
        long now = server.getTicks();
        if (now < entry.handOversWaitUntil) return java.util.Optional.empty();
        // A piece being tossed across is out of its pack but not yet handed over: nothing is planned until the toss ends.
        if ("native_toss".equals(entry.skillId) && runnerBusy(entry)) return java.util.Optional.empty();
        var player = body.entity();
        UUID self = entry.data.id();
        // Every piece it has promised and not yet handed over, in the order promised.
        var mine = teamCoordinator.listProjects().stream().flatMap(p -> p.tasks().values().stream())
                .filter(t -> HandOvers.givenBy(t, self) && self.equals(t.assignedPlayerId())
                        && (t.state() == SharedTask.TaskState.CLAIMED || t.state() == SharedTask.TaskState.IN_PROGRESS))
                .toList();
        // Its own pieces once made are done; worn counts as made.
        for (var t : mine) {
            var piece = HandOvers.Piece.of(t);
            Item made = Registries.ITEM.get(net.minecraft.util.Identifier.of(piece.piece()));
            if (piece.to().equals(self) && (player.getInventory().count(made) > 0
                    || java.util.stream.StreamSupport.stream(player.getArmorItems().spliterator(), false).anyMatch(s -> s.isOf(made)))) {
                teamCoordinator.completeTask(t.id(), self, "Made " + t.title());
                entry.cachedRoutineCandidates = List.of();
                entry.routineRefreshPending = true;
                return java.util.Optional.empty();
            }
        }
        // Each piece made first, while it is by its table: as many of each as it has promised and does not hold.
        var promised = new java.util.HashMap<Item, Integer>();
        SharedTask toMake = null;
        for (var t : mine) {
            Item made = Registries.ITEM.get(net.minecraft.util.Identifier.of(HandOvers.Piece.of(t).piece()));
            int wanted = promised.merge(made, 1, Integer::sum);
            if (toMake == null && player.getInventory().count(made) < wanted) toMake = t;
        }
        PortalRunCampaign.Task step = null;
        SharedTask stepTask = toMake != null ? toMake : task;
        if (toMake != null) {
            String piece = net.minecraft.util.Identifier.of(HandOvers.Piece.of(toMake).piece()).getPath();
            boolean handled = piece.endsWith("_sword") || piece.endsWith("_pickaxe") || piece.endsWith("_axe");
            String recipe = handled && player.getInventory().count(net.minecraft.item.Items.STICK) < 2 ? "stick" : piece;
            // At the table found as its routine was reckoned (the tick's scan budget is spent by now), else its own table.
            primeWorkstationCaches(entry, body);
            if (body.planCraft(recipe, entry.cachedCraftingTablePos).isPresent()) {
                step = new PortalRunCampaign.Task("native_craft", Map.of("recipe", recipe), "Make " + toMake.title());
            } else {
                if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
                entry.campaign.policy(edits);
                step = entry.campaign.craft(body, recipe).orElse(null);
            }
        } else {
            // All made: the next one carried over to its teammate and tossed across from close by.
            var piece = HandOvers.Piece.of(task);
            Item made = Registries.ITEM.get(net.minecraft.util.Identifier.of(piece.piece()));
            int slot = -1;
            for (int s = 0; s < 36; s++) if (player.getInventory().getStack(s).isOf(made)) { slot = s; break; }
            // The teammate by its identity, and its body in the world by the body's own id.
            Entry mateEntry = entries.get(piece.to());
            var mate = mateEntry != null && mateEntry.nativeBody != null && mateEntry.nativeBody.connected() ? mateEntry.nativeBody.entity() : null;
            if (slot >= 0 && mate != null && mate.isAlive() && mate.getWorld() == player.getWorld()) {
                String name = mateEntry.data.displayName();
                step = player.squaredDistanceTo(mate) <= 3 * 3 && player.canSee(mate)
                        ? new PortalRunCampaign.Task("native_toss", Map.of("slot", String.valueOf(slot), "count", "1", "target", mate.getUuid().toString()),
                                "Hand " + task.title())
                        : PortalRunCampaign.go(mate.getBlockPos(), 2.5, true, "To " + name + " with " + task.title());
            }
        }
        if (step == null) {
            // Nothing to make it with here, or its teammate out of its world: it waits a minute.
            entry.handOversWaitUntil = now + 1200;
            String why = "its teammate not in its world";
            if (toMake != null) {
                why = "no table by it to make " + net.minecraft.util.Identifier.of(HandOvers.Piece.of(toMake).piece()).getPath() + " at "
                        + player.getBlockPos().toShortString();
            }
            KindredMod.LOGGER.info("{} cannot go on with {} for now, {}: it waits a minute", entry.data.displayName(), stepTask.title(), why);
            return java.util.Optional.empty();
        }
        UUID stepId = UUID.nameUUIDFromBytes(("handover:" + stepTask.id() + ":" + step.skill() + ":" + new java.util.TreeMap<>(step.args()))
                .getBytes(StandardCharsets.UTF_8));
        entry.handOverStepId = stepId;
        entry.handOverTask = stepTask.id();
        entry.handOverStepSkill = step.skill();
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(stepId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 78.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        return java.util.Optional.of(new NativeTaskCandidate(candidate, step.skill(), step.args()));
    }

    /** A hand-over's step ended: the toss done is the hand-over done; a failed step counts against it (E-40a). */
    private void handOverStepEnded(Entry entry, SkillRunner.State state) {
        TaskId taskId = entry.handOverTask;
        String skill = entry.handOverStepSkill;
        entry.handOverStepId = null;
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
        if (taskId == null) return;
        var task = teamCoordinator.findTask(taskId).orElse(null);
        if (task == null || HandOvers.Piece.of(task) == null) return;
        if (state == SkillRunner.State.COMPLETED) {
            entry.handOverFailures = 0;
            if ("native_toss".equals(skill)) {
                teamCoordinator.completeTask(taskId, entry.data.id(), "Handed over " + task.title());
                KindredMod.LOGGER.info("{} handed over {}", entry.data.displayName(), task.title());
            }
        } else if (state == SkillRunner.State.BLOCKED && ++entry.handOverFailures >= 6) {
            entry.handOverFailures = 0;
            teamCoordinator.revokeTask(taskId, "Six steps of the hand-over failed");
            entry.handOversWaitUntil = server.getTicks() + 1200;
        }
    }

    // ------------------------------------------------------------------ raids in a match (E-44)

    /** A chest a player was seen using: where, whose (their name as seen), and when. */
    private record ChestUse(BlockPos pos, UUID owner, String ownerName, long tick) { }

    /**
     * What a player decided of an opponent's chest, until when it holds, why, whether the model said so, and whether it
     * has since raided it.
     */
    private record RaidDecision(dev.kindred.core.match.Raid.Choice choice, long until, String why, boolean byModel, boolean raided) { }

    /**
     * Chests it sees a player use, while it is in a match whose sides are opponents: one open beside them within their
     * reach, in its own sight. That is all it knows of whose a chest is; nothing else makes a chest anyone's. And in any
     * mode, whoever it sees open a store of its own (E-43).
     */
    private void noteChestUse(Entry entry, ServerPlayerEntity self) {
        if (self == null) return;
        boolean match = matches.running(self.getUuid()).filter(m -> m.mode().pvp).isPresent();
        var stores = ownStores(entry, self);
        if (!match && stores.isEmpty()) return;
        var world = self.getServerWorld();
        for (ServerPlayerEntity other : world.getPlayers()) {
            if (other == self || !other.isAlive() || other.isSpectator() || self.squaredDistanceTo(other) > 32 * 32 || !self.canSee(other)) continue;
            BlockPos at = other.getBlockPos();
            var near = new ArrayList<BlockPos>();
            if (match) {
                for (BlockPos pos : BlockPos.iterate(at.add(-4, -2, -4), at.add(4, 3, 4))) near.add(pos.toImmutable());
            } else {
                for (var store : stores) if (store.pos().getSquaredDistance(at) <= 36) near.add(store.pos());
            }
            for (BlockPos pos : near) {
                if (!world.isChunkLoaded(pos) || other.getEyePos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(pos)) > 4.5 * 4.5) continue;
                var state = world.getBlockState(pos);
                boolean open = state.isOf(net.minecraft.block.Blocks.BARREL) ? state.get(net.minecraft.block.BarrelBlock.OPEN)
                        : (state.isOf(net.minecraft.block.Blocks.CHEST) || state.isOf(net.minecraft.block.Blocks.TRAPPED_CHEST))
                        && net.minecraft.block.entity.ChestBlockEntity.getPlayersLookingInChestCount(world, pos) > 0;
                if (!open || !seesBlock(self, pos)) continue;
                // At a store of its own: who it saw there, so that what is gone from it has a name if anyone's.
                if (stores.stream().anyMatch(store -> store.pos().equals(pos))) {
                    boolean mate = teammateOf(entry, self, other);
                    var before = entry.seenAtStores.put(pos.asLong(), new SeenAtStore(other.getName().getString(), mate, server.getTicks()));
                    // Someone not on its side at its chest: it gives up a walk to go and look.
                    if (before == null && !mate) entry.sightingInterrupt = true;
                    entry.routineRefreshPending = true;
                    continue;
                }
                if (!match) continue;
                if (entry.chestsUsed.size() >= 32 && !entry.chestsUsed.containsKey(pos.asLong())) continue;
                var seen = entry.chestsUsed.put(pos.asLong(), new ChestUse(pos.toImmutable(), other.getUuid(), other.getName().getString(), server.getTicks()));
                if (seen == null || !seen.owner().equals(other.getUuid())) {
                    // Used again after it raided it: something new may be in it, and it thinks again.
                    entry.raids.computeIfPresent(pos.asLong(), (key, decided) -> decided.raided() ? null : decided);
                    entry.routineRefreshPending = true;
                }
            }
        }
    }

    private static boolean seesBlock(ServerPlayerEntity player, BlockPos pos) {
        var hit = player.getServerWorld().raycast(new net.minecraft.world.RaycastContext(player.getEyePos(), net.minecraft.util.math.Vec3d.ofCenter(pos),
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    /**
     * An opponent's chest it saw them use, in the match it is in (E-44): the model asked whether to raid it, or its own
     * rule without one; then, its answer RAID, walked to and raided. Nothing for a chest whose owner is not its
     * opponent in a running match, or that is gone.
     */
    private List<NativeTaskCandidate> raidCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var match = matches.running(player.getUuid()).filter(m -> m.mode().pvp).orElse(null);
        if (match == null || entry.chestsUsed.isEmpty()) return null;
        var world = player.getServerWorld();
        long now = server.getTicks();
        ChestUse target = null;
        for (ChestUse use : List.copyOf(entry.chestsUsed.values())) {
            if (!matches.opponents(player.getUuid(), use.owner())) continue;
            if (!world.isChunkLoaded(use.pos()) || !(world.getBlockEntity(use.pos()) instanceof net.minecraft.inventory.Inventory)) {
                entry.chestsUsed.remove(use.pos().asLong());
                continue;
            }
            int distance = (int) Math.round(Math.sqrt(player.getBlockPos().getSquaredDistance(use.pos())));
            if (distance > 64) continue;
            var decided = entry.raids.get(use.pos().asLong());
            if (decided == null || decided.until() <= now) {
                if (!entry.raidAsking) askRaid(entry, player, match, use, distance);
                continue;
            }
            // Not while an opponent it can see is at the chest: it raids what they left, not under their eyes.
            if (decided.raided() || opponentAt(player, use.pos())) continue;
            if (decided.choice() == dev.kindred.core.match.Raid.Choice.RAID && (target == null
                    || player.getBlockPos().getSquaredDistance(use.pos()) < player.getBlockPos().getSquaredDistance(target.pos()))) target = use;
        }
        if (target == null) return null;
        BlockPos pos = target.pos();
        UUID taskId = UUID.nameUUIDFromBytes(("raid:" + match.id() + ":" + pos.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var raid = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 93.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        var at = Map.of("x", String.valueOf(pos.getX()), "y", String.valueOf(pos.getY()), "z", String.valueOf(pos.getZ()));
        if (player.getEyePos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(pos)) <= 4.5 * 4.5) {
            var args = new HashMap<>(at);
            args.put("raid", target.owner().toString());
            return List.of(new NativeTaskCandidate(raid, "native_loot", Map.copyOf(args)));
        }
        var args = new HashMap<>(at);
        args.put("range", "2.5");
        return List.of(new NativeTaskCandidate(raid, "native_goto", Map.copyOf(args)));
    }

    /** An opponent of its, alive and in its sight, within six blocks of {@code pos}. */
    private boolean opponentAt(ServerPlayerEntity player, BlockPos pos) {
        for (ServerPlayerEntity other : player.getServerWorld().getPlayers()) {
            if (other != player && other.isAlive() && !other.isSpectator() && other.getBlockPos().getSquaredDistance(pos) <= 36
                    && matches.opponents(player.getUuid(), other.getUuid()) && player.canSee(other)) return true;
        }
        return false;
    }

    /** The question about an opponent's chest: to the model if one is set up, else, or with no usable answer, its own rule. */
    private void askRaid(Entry entry, ServerPlayerEntity player, dev.kindred.core.match.MatchRegistry.Match match, ChestUse use, int distance) {
        var facts = raidFacts(entry, player, match, use, distance);
        if (strategist.isEmpty()) {
            var choice = dev.kindred.core.match.Raid.local(facts);
            decideRaid(entry, use, choice, dev.kindred.core.match.Raid.why(choice, facts), false);
            return;
        }
        entry.raidAsking = true;
        var question = new dev.kindred.model.Strategist.Question(dev.kindred.core.match.Raid.SYSTEM, dev.kindred.core.match.Raid.question(facts),
                dev.kindred.core.match.Raid.choices(), 0);
        strategist.get().ask(entry.data.id(), entry.data.displayName(), question, server).whenComplete((answer, failure) -> {
            entry.raidAsking = false;
            var choice = answer == null ? java.util.Optional.<dev.kindred.core.match.Raid.Choice>empty()
                    : answer.flatMap(a -> a.choice()).flatMap(dev.kindred.core.match.Raid::parse);
            if (choice.isPresent()) {
                decideRaid(entry, use, choice.get(), answer.get().reason(), true);
            } else {
                var own = dev.kindred.core.match.Raid.local(facts);
                decideRaid(entry, use, own, dev.kindred.core.match.Raid.why(own, facts), false);
            }
        });
    }

    private dev.kindred.core.match.Raid.Facts raidFacts(Entry entry, ServerPlayerEntity player, dev.kindred.core.match.MatchRegistry.Match match, ChestUse use, int distance) {
        String side = match.mode() == dev.kindred.core.match.MatchRegistry.Mode.MANHUNT ? (match.runners().contains(player.getUuid()) ? "runner" : "hunter") : "duelist";
        String ownerSide = match.mode() == dev.kindred.core.match.MatchRegistry.Mode.MANHUNT ? (match.runners().contains(use.owner()) ? "runner" : "hunter") : "duelist";
        java.util.OptionalInt nearest = java.util.OptionalInt.empty();
        for (ServerPlayerEntity other : player.getServerWorld().getPlayers()) {
            if (other == player || !other.isAlive() || !matches.opponents(player.getUuid(), other.getUuid()) || !player.canSee(other)) continue;
            int away = (int) Math.round(Math.sqrt(player.squaredDistanceTo(other)));
            if (away <= 64 && (nearest.isEmpty() || away < nearest.getAsInt())) nearest = java.util.OptionalInt.of(away);
        }
        // What of note it holds, worth most first, and what its run wants, as it would say them.
        var holding = Valuables.holding(player, lootWanted(entry));
        var held = new ArrayList<java.util.Map.Entry<String, Double>>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            double worth = Valuables.judge(stack, holding).worth();
            if (worth >= 0.5) held.add(Map.entry(dev.kindred.core.team.Missing.items(Map.of(Valuables.id(stack), stack.getCount())), worth));
        }
        held.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        var holds = held.stream().limit(6).map(Map.Entry::getKey).toList();
        var wants = entry.campaign == null ? List.<String>of() : entry.campaign.lootWanted(player).keySet().stream()
                .filter(item -> item.startsWith("minecraft:")).map(item -> item.substring(10).replace('_', ' ')).sorted().limit(4).toList();
        return new dev.kindred.core.match.Raid.Facts(entry.data.displayName(), match.mode(), side, use.ownerName(), ownerSide,
                (int) ((server.getTicks() - use.tick()) / 20), distance, nearest, player.getHealth(), holds, wants);
    }

    private void decideRaid(Entry entry, ChestUse use, dev.kindred.core.match.Raid.Choice choice, String why, boolean byModel) {
        long now = server.getTicks();
        long until = now + switch (choice) {
            case RAID -> 2400; // Two minutes to get there and do it, then it thinks again.
            case LEAVE -> 12_000;
            case WATCH -> 2400;
        };
        entry.raids.put(use.pos().asLong(), new RaidDecision(choice, until, why, byModel, false));
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
        KindredMod.LOGGER.info("{} on {}'s chest at {}: {} ({}) - {}", entry.data.displayName(), use.ownerName(), use.pos().toShortString(), choice,
                byModel ? "the model's call" : "its own rule", why);
    }

    /** A raid done or not: what it took told to its own side only, and the chest let be until it sees it used again. */
    private void raidEnded(Entry entry, NativeLootAction loot, SkillRunner.State state) {
        var self = entry.nativeBody == null ? null : entry.nativeBody.entity();
        String owner = loot.raidOwner().toString();
        for (ChestUse use : List.copyOf(entry.chestsUsed.values())) {
            if (!use.owner().equals(loot.raidOwner())) continue;
            owner = use.ownerName();
            if (state == SkillRunner.State.COMPLETED && self != null && self.getBlockPos().getSquaredDistance(use.pos()) <= 36) {
                entry.chestsUsed.remove(use.pos().asLong());
                var decided = entry.raids.get(use.pos().asLong());
                entry.raids.put(use.pos().asLong(), decided == null ? new RaidDecision(dev.kindred.core.match.Raid.Choice.RAID, server.getTicks() + 12_000, "raided", false, true)
                        : new RaidDecision(decided.choice(), server.getTicks() + 12_000, decided.why(), decided.byModel(), true));
            }
        }
        if (state == SkillRunner.State.COMPLETED) sayToSide(entry, dev.kindred.core.match.Raid.told(owner, loot.took()));
    }

    /**
     * Said to its own side only (E-44): those it plays for and the players on its side of its match, never an opponent
     * of its in it, however near.
     */
    private void sayToSide(Entry entry, String text) {
        entry.lastSaid = text;
        entry.said(text);
        String line = "<" + entry.data.displayName() + "> " + text;
        KindredMod.LOGGER.info("{} (to its side)", line);
        var self = entry.nativeBody != null && entry.nativeBody.connected() ? entry.nativeBody.entity() : null;
        if (self == null) return;
        var match = matches.running(self.getUuid()).orElse(null);
        for (ServerPlayerEntity listener : server.getPlayerManager().getPlayerList()) {
            if (listener == self || aiPlayerOf(listener) != null || matches.opponents(self.getUuid(), listener.getUuid())) continue;
            boolean controls = listener.getUuid().equals(entry.data.ownerId()) || entry.nativeState != null
                    && entry.nativeState.access().mayControl(new Actor(listener.getUuid(), listener.hasPermissionLevel(2)));
            if (controls || match != null && match.participant(listener.getUuid())) listener.sendMessage(Text.literal(line), false);
        }
    }

    /** What it decided of an opponent's chest at {@code pos}, for fixtures: the choice and whether the model made it. */
    String raidDecision(UUID id, BlockPos pos) {
        Entry entry = entries.get(id);
        var decided = entry == null ? null : entry.raids.get(pos.asLong());
        return decided == null ? null : decided.choice() + (decided.byModel() ? " (the model's call)" : " (its own rule)");
    }

    // ------------------------------------------------------------------ hit hard by a player (E-41g, E-41h)

    /** A player that hit it hard: who, by name, what it does about it, until when, and the odds it saw. */
    private record Aggressor(UUID who, String name, dev.kindred.core.tactics.FightOdds.Answer answer, long until,
                             dev.kindred.core.tactics.FightOdds.Odds odds) { }

    /** How long a hard hit holds its answer, each hit after renewing it: ten seconds. */
    static final long AGGRESSION_HOLD = 200;
    /** Further than this off, out of its sight, and it has got away. */
    static final double GOT_AWAY = 24;

    /**
     * A hit on one of its players from another player (E-41g): the hardest each has dealt it noted; one of more than
     * three hearts, after armour, is lethal aggression, answered at once by its kit, the odds (E-41h) and
     * whether it may strike that player at all (D7): defended against, or got away from. It says so to those it plays
     * for, from what it took and saw.
     */
    private void hitByPlayer(Entry entry, ServerPlayerEntity hit, ServerPlayerEntity by, float taken) {
        if (!running || !ready || entries.get(entry.data.id()) != entry) return;
        long now = server.getTicks();
        if (taken > 0) entry.hardestHits.merge(by.getUuid(), (double) taken, Math::max);
        // Stopped, it decides nothing and says nothing: what the hit took is all it keeps.
        if (entry.snapshot().stopLatched()) return;
        var held = entry.aggressor;
        if (held != null && held.who().equals(by.getUuid())) {
            entry.aggressor = new Aggressor(held.who(), held.name(), held.answer(), now + AGGRESSION_HOLD, held.odds());
        }
        if (!dev.kindred.core.tactics.FightOdds.lethal(taken)) return;
        var odds = fightOdds(entry, hit, by);
        boolean mayStrike = matches.mayAttack(hit.getUuid(), by.getUuid());
        boolean equipped = Weapons.choose(hit, NativePlayerBody.believedHealth(hit, by), false)
                .filter(w -> w.weapon().kind() != dev.kindred.core.tactics.DamageModel.Kind.HAND).isPresent();
        boolean cornered = openWayFrom(entry, hit, by) == null;
        var answer = dev.kindred.core.tactics.FightOdds.answer(mayStrike, equipped, odds, cornered);
        boolean fresh = held == null || !held.who().equals(by.getUuid()) || held.answer() != answer;
        entry.aggressor = new Aggressor(by.getUuid(), by.getName().getString(), answer, now + AGGRESSION_HOLD, odds);
        entry.aggressionInterrupt = true;
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
        String hearts = String.format(java.util.Locale.ROOT, "%.1f hearts", taken / 2.0).replace(".0 ", " ");
        KindredMod.LOGGER.info("{} hit by {} for {} after armour: lethal aggression; {} (may strike {}, equipped {}, cornered {}; odds {} {}/{})",
                entry.data.displayName(), by.getName().getString(), hearts, answer, mayStrike, equipped, cornered, odds.verdict(),
                String.format(java.util.Locale.ROOT, "%.2f", odds.best()), String.format(java.util.Locale.ROOT, "%.2f", odds.worst()));
        if (fresh || now - entry.aggressionSaidAt > 200) {
            entry.aggressionSaidAt = now;
            String why = !mayStrike ? " I'm getting away." : !equipped ? " I've nothing to fight with: I'm getting away."
                    : " " + odds.words() + (answer == dev.kindred.core.tactics.FightOdds.Answer.DEFEND ? " I'm fighting back." : " I'm getting away.");
            say(entry, by.getName().getString() + " hit me hard, " + hearts + "." + why, "");
        }
    }

    /** The odds of its fight with {@code them} (E-41h): itself as it knows itself, them as it sees and believes them. */
    private dev.kindred.core.tactics.FightOdds.Odds fightOdds(Entry entry, ServerPlayerEntity self, ServerPlayerEntity them) {
        return dev.kindred.core.tactics.FightOdds.of(fightSide(entry, self, null), fightSide(entry, them, self));
    }

    /**
     * A side of a fight as {@code viewer} can know it: itself exactly ({@code viewer} null), its best weapon, its
     * Protection, its golden apples; another as seen (the armour it wears and the weapon in its hand, glints unread, no
     * Sharpness known) and believed (its health), with the hardest hit it has dealt the player.
     */
    private dev.kindred.core.tactics.FightOdds.Side fightSide(Entry entry, ServerPlayerEntity p, ServerPlayerEntity viewer) {
        boolean own = viewer == null;
        double health = own ? p.getHealth() + p.getAbsorptionAmount() : NativePlayerBody.believedHealth(viewer, p);
        int protection = 0;
        boolean armourGlints = false;
        for (var slot : new net.minecraft.entity.EquipmentSlot[] {net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
            var worn = p.getEquippedStack(slot);
            if (worn.isEmpty()) continue;
            if (!own) {
                armourGlints |= worn.hasGlint();
                continue;
            }
            for (var each : worn.getEnchantments().getEnchantmentEntries()) {
                if (each.getKey().matchesKey(net.minecraft.enchantment.Enchantments.PROTECTION)) protection += each.getIntValue();
            }
        }
        var hand = p.getMainHandStack();
        var weapon = own ? Weapons.choose(p, 20, true).map(Weapons.Held::weapon).orElse(dev.kindred.core.tactics.DamageModel.Weapon.HAND)
                : Weapons.of(hand).map(w -> new dev.kindred.core.tactics.DamageModel.Weapon(w.kind(), w.tier(), 0))
                        .orElse(dev.kindred.core.tactics.DamageModel.Weapon.HAND);
        boolean shield = p.getOffHandStack().isOf(Items.SHIELD);
        double healing = 0;
        if (own) {
            var inventory = p.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                var stack = inventory.getStack(slot);
                if (stack.isOf(Items.GOLDEN_APPLE)) healing += 6.0 * stack.getCount();
                if (stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) healing += 20.0 * stack.getCount();
            }
        }
        double hardest = own ? 0 : entry.hardestHits.getOrDefault(p.getUuid(), 0.0);
        return new dev.kindred.core.tactics.FightOdds.Side(Math.min(60, health), Math.min(30, p.getArmor()),
                Math.min(20, p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ARMOR_TOUGHNESS)), Math.min(20, protection),
                armourGlints, weapon, !own && hand.hasGlint(), own, shield, Math.min(200, healing), hardest);
    }

    /**
     * Its answer to a hard hit while it holds (E-41g): the attacker fought where it may be and the fight is winnable;
     * otherwise run from, at a sprint, away and never toward home, until it is far off and out of its sight. Once away,
     * or the attacker gone, it goes back to what it was doing: it never seeks them out after.
     */
    private List<NativeTaskCandidate> aggressionCandidates(Entry entry, NativePlayerBody body) {
        var held = entry.aggressor;
        if (held == null) return null;
        var player = body.entity();
        long now = server.getTicks();
        var attacker = server.getPlayerManager().getPlayer(held.who());
        boolean gone = attacker == null || !attacker.isAlive() || attacker.isSpectator() || attacker.getServerWorld() != player.getServerWorld();
        // Far off, and out of its sight or with no hit for a while: on open ground a player can be seen a long way.
        boolean away = !gone && player.distanceTo(attacker) > GOT_AWAY && (!player.canSee(attacker) || now > held.until());
        if (gone || away || now > held.until() && held.answer() == dev.kindred.core.tactics.FightOdds.Answer.DEFEND) {
            entry.aggressor = null;
            KindredMod.LOGGER.info("{} done with {}'s aggression: {}", entry.data.displayName(), held.name(), gone ? "they are gone" : away ? "got away" : "the fight is over");
            return null;
        }
        UUID taskId = aggressionTaskId(entry, held.who());
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 99.9,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        if (held.answer() == dev.kindred.core.tactics.FightOdds.Answer.DEFEND && matches.mayAttack(player.getUuid(), attacker.getUuid())) {
            int slot = Weapons.choose(player, NativePlayerBody.believedHealth(player, attacker), true).map(Weapons.Held::slot).orElse(-1);
            return List.of(new NativeTaskCandidate(candidate, "native_fight", Map.of("target", attacker.getUuid().toString(), "slot", String.valueOf(slot))));
        }
        BlockPos to = awayFrom(entry, player, attacker);
        return List.of(new NativeTaskCandidate(candidate, "native_goto", Map.of("x", String.valueOf(to.getX()), "y", String.valueOf(to.getY()),
                "z", String.valueOf(to.getZ()), "range", "3", "partial", "true", "sprint", "true")));
    }

    private static UUID aggressionTaskId(Entry entry, UUID who) {
        return UUID.nameUUIDFromBytes(("aggressor:" + entry.data.id() + ":" + who).getBytes(StandardCharsets.UTF_8));
    }

    /** Its answer to a hard hit, while it holds, for fixtures: "GET_AWAY from Rue", or null. */
    String aggression(UUID id) {
        Entry entry = entries.get(id);
        var held = entry == null ? null : entry.aggressor;
        return held == null ? null : held.answer() + " from " + held.name();
    }

    // ------------------------------------------------------------------ villagers (E-45)

    /** A villager it has looked at: its profession and level, and its offers as its screen showed them. */
    private record Villager(dev.kindred.core.guide.Trades.Profession profession, int level, List<dev.kindred.core.guide.Trades.Offer> offers,
                            long seenAt) { }

    /** How near a villager it has not looked at must be to be looked at in passing; and one worth trading with, to go to. */
    static final int LOOK_RANGE = 16, TRADE_RANGE = 48;
    /** A villager with nothing worth trading is let be until it has restocked: half a day. */
    static final long RESTOCK = 12000;

    private static dev.kindred.core.guide.Trades.Profession profession(net.minecraft.entity.passive.VillagerEntity villager) {
        var key = net.minecraft.registry.Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession());
        return dev.kindred.core.guide.Trades.Profession.of(key.toString());
    }

    /**
     * A villager by its way (E-45): one it has not looked at, close by, has its trading screen opened to see what it
     * offers, as a player does; one whose offers it knows and has something worth trading for, within reach of a walk,
     * is gone to and traded with as planned. Never a villager asleep, a child, one trading with someone, a nitwit or the
     * jobless; never in a match. Each villager is let be after: until it restocks when nothing it offers is worth it.
     */
    private List<NativeTaskCandidate> tradeCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        long now = server.getTicks();
        if (now < entry.tradeAt || matches.running(player.getUuid()).isPresent()) return null;
        var holding = Valuables.holding(player, lootWanted(entry));
        int emeralds = holding.held().getOrDefault(dev.kindred.core.guide.Trades.EMERALD, 0);
        net.minecraft.entity.passive.VillagerEntity chosen = null;
        boolean look = false;
        double nearest = Double.MAX_VALUE;
        for (var villager : player.getServerWorld().getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
                player.getBoundingBox().expand(TRADE_RANGE), v -> v.isAlive() && !v.isBaby() && !v.isSleeping() && !v.hasCustomer())) {
            var profession = profession(villager);
            if (profession == dev.kindred.core.guide.Trades.Profession.NONE || profession == dev.kindred.core.guide.Trades.Profession.NITWIT) continue;
            if (entry.villagerVisitAt.getOrDefault(villager.getUuid(), 0L) > now) continue;
            // One it is rerolling is only looked at, never traded with: a trade would lock it to its offers.
            if (entry.reroll != null && entry.reroll.villager.equals(villager.getUuid())) continue;
            double distance = player.squaredDistanceTo(villager);
            var known = entry.villagers.get(villager.getUuid());
            boolean unseen = known == null;
            if (unseen && (distance > LOOK_RANGE * LOOK_RANGE || !player.canSee(villager))) continue;
            if (!unseen && dev.kindred.core.guide.Trades.plan(known.offers(), holding, emeralds).empty()) {
                entry.villagerVisitAt.put(villager.getUuid(), now + RESTOCK);
                continue;
            }
            if (distance < nearest) {
                nearest = distance;
                chosen = villager;
                look = unseen;
            }
        }
        if (chosen == null) {
            entry.tradeAt = now + 200;
            return null;
        }
        UUID taskId = UUID.nameUUIDFromBytes(("trade:" + entry.data.id() + ":" + chosen.getUuid()).getBytes(StandardCharsets.UTF_8));
        entry.tradeTaskId = taskId;
        entry.tradeVillager = chosen.getUuid();
        var task = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 55.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        if (player.canInteractWithEntity(chosen, 0)) {
            var args = new HashMap<String, String>();
            args.put("target", chosen.getUuid().toString());
            if (look) args.put("look", "true");
            return List.of(new NativeTaskCandidate(task, "native_trade", Map.copyOf(args)));
        }
        BlockPos at = chosen.getBlockPos();
        return List.of(new NativeTaskCandidate(task, "native_goto", Map.of("x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()),
                "z", String.valueOf(at.getZ()), "range", "2", "partial", "true")));
    }

    /**
     * A look or a trade done (E-45): what it saw kept; what it traded said; a villager with something worth buying it
     * cannot yet pay for told of once; each villager let be a while after.
     */
    private void tradeEnded(Entry entry, NativeTradeAction trade, SkillRunner.State state) {
        long now = server.getTicks();
        UUID id = trade.villager();
        entry.routineRefreshPending = true;
        if (state != SkillRunner.State.COMPLETED) {
            entry.villagerVisitAt.put(id, now + 1200);
            return;
        }
        var player = entry.nativeBody == null ? null : entry.nativeBody.entity();
        var villager = player != null && player.getServerWorld().getEntity(id) instanceof net.minecraft.entity.passive.VillagerEntity v ? v : null;
        if (!trade.looked()) {
            if (!trade.made().isEmpty()) say(entry, dev.kindred.core.guide.Trades.told(trade.made()), "");
            // Its offers have moved on with the trades: looked at again next time.
            entry.villagers.remove(id);
            entry.villagerVisitAt.put(id, now + 2400);
            return;
        }
        if (entry.reroll != null && entry.reroll.villager.equals(id) && player != null) {
            rerollLooked(entry, player, trade.seen());
            return;
        }
        var profession = villager == null ? dev.kindred.core.guide.Trades.Profession.NONE : profession(villager);
        entry.villagers.put(id, new Villager(profession, villager == null ? 1 : villager.getVillagerData().getLevel(), trade.seen(), now));
        if (player == null) return;
        var holding = Valuables.holding(player, lootWanted(entry));
        int emeralds = holding.held().getOrDefault(dev.kindred.core.guide.Trades.EMERALD, 0);
        if (dev.kindred.core.guide.Trades.plan(trade.seen(), holding, emeralds).empty()) {
            entry.villagerVisitAt.put(id, now + RESTOCK);
            var best = dev.kindred.core.guide.Trades.best(trade.seen(), holding);
            if (best.isPresent() && entry.villagersTold.add(id)) {
                say(entry, "The " + profession.name().toLowerCase(java.util.Locale.ROOT) + " here sells "
                        + dev.kindred.core.guide.Trades.sells(best.get().offer()) + ": worth it, "
                        + (best.get().offer().open() ? "once I have what it takes." : "once it has restocked."), "");
            }
        }
    }

    /**
     * A villager kept brought home to its cell by minecart (E-45e): the rail line found ({@link
     * dev.kindred.core.construction.RailRoute}), the steps along it, and the cart. The hall's door is taken off for the
     * line and put back after; the rails it lays it takes up again; nothing owned is left but what holds the villager.
     */
    private static final class Bring {
        enum Phase { DOOR, LAY, CART, PUSH, ROLL, SHUT, FREE, FETCH, CLOSE, UNDO, TIDY, DOOR_BACK }

        final UUID villager;
        final String noun;
        final List<BlockPos> rails;
        final BlockPos cart, push, floor, slot, door, origin;
        final int cells;
        final String wood;
        final net.minecraft.item.Item jobBlock;
        Phase phase = Phase.DOOR;
        UUID cartId;
        /** Pushes given, counted as each ends; steps refused or blocked in a row; and, once it gives up, why. */
        int pushes, failed, doorTries;
        long since;
        String failure;
        final Set<UUID> taskIds = new HashSet<>();

        Bring(UUID villager, String noun, List<BlockPos> rails, BlockPos cart, BlockPos push, BlockPos floor, BlockPos slot, BlockPos door,
              BlockPos origin, int cells, String wood, net.minecraft.item.Item jobBlock) {
            this.villager = villager;
            this.noun = noun;
            this.rails = List.copyOf(rails);
            this.cart = cart;
            this.push = push;
            this.floor = floor;
            this.slot = slot;
            this.door = door;
            this.origin = origin;
            this.cells = cells;
            this.wood = wood;
            this.jobBlock = jobBlock;
        }
    }

    /** How far from its hall a villager kept may be to be brought by rail; and the plain blocks a cell's front is shut with a moment. */
    static final int BRING_RANGE = 48;
    private static final net.minecraft.item.Item[] SHUTTERS = {Items.DIRT, Items.COARSE_DIRT, Items.COBBLESTONE, Items.STONE, Items.COBBLED_DEEPSLATE};

    /**
     * The slot of a plain block to shut a cell's front with a moment that it can take back after (one a hand breaks and
     * keeps, or one it has a tool for: cobblestone broken by hand drops nothing), or -1.
     */
    private static int shutterSlot(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (var shutter : SHUTTERS) {
            int slot = slotOf(player, shutter);
            if (slot < 0) continue;
            var state = net.minecraft.block.Block.getBlockFromItem(shutter).getDefaultState();
            if (!state.isToolRequired()) return slot;
            for (int i = 0; i < 36; i++) if (inventory.getStack(i).isSuitableFor(state)) return slot;
        }
        return -1;
    }

    /**
     * A villager kept brought home (E-45e), as players bring one by minecart: with a trading hall of its own standing, a
     * villager it keeps (whom to keep, E-45c) within {@link #BRING_RANGE} of it and not yet in a cell, its trades locked
     * (it has traded: a librarian moved before it has would let its job go and its offers with it), and in hand the rails
     * the line takes, a minecart, the villager's job block and a plain block. It takes the hall's door off, lays the line
     * from two rails behind the villager to a free cell's floor, puts the cart down behind it and pushes it: rolling, the
     * cart takes the villager up and runs on into the cell, against its bed (pushed again where it stops short). It shuts
     * the cell's front with the plain block, breaks the cart through the opening above (the villager set down inside, on
     * its bed), takes the plain block back, steps into the front for the cart and the floor's rail, steps out and puts the
     * job block there, which shuts the villager in. Then it takes up its rails and puts the door back. Under the
     * server's AI world edits only, never in a match.
     */
    private List<NativeTaskCandidate> bringCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        long now = server.getTicks();
        if (!worldEditsEnabled() || matches.running(player.getUuid()).isPresent()) return null;
        var world = player.getServerWorld();
        if (entry.bring == null) {
            if (now < entry.bringAt) return null;
            // Not before the work it was at before a restart is read, as its base waits (F-13).
            if (entry.workRead != WorkRead.TAKEN || !basesLoaded(entry)) return null;
            entry.bringAt = now + 200;
            var hall = entry.bases.hall;
            if (hall == null || !hall.complete() || !hall.dimension().equals(world.getRegistryKey().getValue().toString())) return null;
            entry.bring = planBring(entry, body, hall);
            if (entry.bring == null) return null;
            KindredMod.LOGGER.info("{} bringing the {} {} home: {} rails from {} to its cell at {}", entry.data.displayName(), entry.bring.noun,
                    entry.bring.villager, entry.bring.rails.size(), entry.bring.cart.toShortString(), entry.bring.floor.toShortString());
        }
        var b = entry.bring;
        var villager = world.getEntity(b.villager) instanceof net.minecraft.entity.passive.VillagerEntity v && v.isAlive() ? v : null;
        if (villager == null && b.failure == null) return giveUp(entry, body, "The " + b.noun + " I was bringing home is gone.");
        if (b.failed >= 3 && b.failure == null) return giveUp(entry, body, "I couldn't bring the " + b.noun + " home: "
                + b.phase.name().toLowerCase(java.util.Locale.ROOT) + " went wrong three times over.");
        if (b.failed >= 3) return endBring(entry, b.failure + " I couldn't take everything back.", false);
        var task = bringTask(entry, "step");
        switch (b.phase) {
            case DOOR -> {
                if (!(world.getBlockState(b.door).getBlock() instanceof net.minecraft.block.DoorBlock)) {
                    b.phase = Bring.Phase.LAY;
                    return bringCandidates(entry, body);
                }
                if (!reaches(player, b.door)) return List.of(goNear(task, b.door.south()));
                return List.of(breakAt(task, b.door));
            }
            case LAY -> {
                // From the cell's floor back toward the villager, so that it ends by the cart's place.
                for (int i = b.rails.size() - 1; i >= 0; i--) {
                    var at = b.rails.get(i);
                    if (world.getBlockState(at).isIn(net.minecraft.registry.tag.BlockTags.RAILS)) continue;
                    var rail = bringTask(entry, "rail:" + i);
                    int slot = slotOf(player, Items.RAIL);
                    if (slot < 0) return giveUp(entry, body, "I ran out of rails bringing the " + b.noun + " home.");
                    if (slot >= 9) return List.of(new NativeTaskCandidate(rail, "native_swap", Map.of("source", String.valueOf(slot), "target", "8")));
                    if (!reaches(player, at)) return List.of(goNear(rail, at));
                    return List.of(new NativeTaskCandidate(rail, "native_place", Map.of("x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()),
                            "z", String.valueOf(at.getZ()), "slot", String.valueOf(slot), "side", "up")));
                }
                b.phase = Bring.Phase.CART;
                return bringCandidates(entry, body);
            }
            case CART -> {
                var carts = world.getEntitiesByClass(net.minecraft.entity.vehicle.AbstractMinecartEntity.class, new net.minecraft.util.math.Box(b.cart).expand(0.5),
                        c -> c.isAlive() && !c.hasPassengers());
                if (!carts.isEmpty()) {
                    b.cartId = carts.getFirst().getUuid();
                    b.phase = Bring.Phase.PUSH;
                    return bringCandidates(entry, body);
                }
                int slot = slotOf(player, Items.MINECART);
                if (slot < 0) return giveUp(entry, body, "I've no minecart to bring the " + b.noun + " home in.");
                if (slot >= 9) return List.of(new NativeTaskCandidate(task, "native_swap", Map.of("source", String.valueOf(slot), "target", "8")));
                // Put down from where the push starts, beside it: the rail's top is low to reach from further off.
                if (player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(b.push)) > 0.25) {
                    return List.of(new NativeTaskCandidate(bringTask(entry, "to the cart"), "native_goto", Map.of("x", String.valueOf(b.push.getX()),
                            "y", String.valueOf(b.push.getY()), "z", String.valueOf(b.push.getZ()), "range", "0", "centre", "true")));
                }
                return List.of(new NativeTaskCandidate(task, "native_cart", Map.of("mode", "place", "slot", String.valueOf(slot),
                        "x", String.valueOf(b.cart.getX()), "y", String.valueOf(b.cart.getY()), "z", String.valueOf(b.cart.getZ()))));
            }
            case PUSH -> {
                var cart = cartOf(world, b);
                if (cart == null) return giveUp(entry, body, "The cart for the " + b.noun + " is gone.");
                if (villager.getVehicle() == cart) {
                    b.phase = Bring.Phase.ROLL;
                    b.since = now;
                    return bringCandidates(entry, body);
                }
                if (cart.getVelocity().horizontalLength() > 0.01) return List.of(waitTask(task, "For the cart"));
                if (b.pushes >= 4) return giveUp(entry, body, "The cart wouldn't take the " + b.noun + " up.");
                // The cart rolled on past and stopped empty: back to its place, for another push.
                if (!cart.getBlockPos().equals(b.cart)) return giveUp(entry, body, "The cart rolled past the " + b.noun + " without it.");
                if (player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(b.push)) > 0.25) {
                    return List.of(new NativeTaskCandidate(bringTask(entry, "behind:" + b.pushes), "native_goto", Map.of("x", String.valueOf(b.push.getX()),
                            "y", String.valueOf(b.push.getY()), "z", String.valueOf(b.push.getZ()), "range", "0", "centre", "true")));
                }
                return List.of(new NativeTaskCandidate(bringTask(entry, "push:" + b.pushes), "native_push",
                        Map.of("target", cart.getUuid().toString(), "seconds", "10")));
            }
            case ROLL -> {
                var cart = cartOf(world, b);
                if (cart == null) return giveUp(entry, body, "The cart for the " + b.noun + " is gone.");
                if (villager.getVehicle() != cart) return giveUp(entry, body, "The " + b.noun + " got out of the cart.");
                double speed = cart.getVelocity().horizontalLength();
                boolean inFront = cart.getBoundingBox().intersects(new net.minecraft.util.math.Box(b.slot));
                if (cart.getBlockPos().equals(b.floor) && speed < 0.01 && !inFront) {
                    b.phase = Bring.Phase.SHUT;
                    return bringCandidates(entry, body);
                }
                // At rest on the cell's floor but still half in its front: nudged on in from the corridor, against the bed.
                if (cart.getBlockPos().equals(b.floor) && speed < 0.01) {
                    if (b.pushes >= 8) return giveUp(entry, body, "The cart with the " + b.noun + " stopped half in its cell.");
                    var by = b.slot.south();
                    if (player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(by)) > 0.25) return List.of(standAt(bringTask(entry, "to nudge"), by));
                    return List.of(new NativeTaskCandidate(bringTask(entry, "nudge:" + b.pushes), "native_push", Map.of("target", cart.getUuid().toString(),
                            "seconds", "5", "x", String.valueOf(b.slot.getX()), "y", String.valueOf(b.slot.getY()), "z", String.valueOf(b.slot.getZ()))));
                }
                if (speed > 0.01) {
                    b.since = now;
                    return List.of(waitTask(task, "For the cart to roll in"));
                }
                if (now - b.since < 40) return List.of(waitTask(task, "For the cart to roll in"));
                // Stopped short: pushed on again from behind it, along the line.
                int at = b.rails.indexOf(cart.getBlockPos());
                if (at < 1 || b.pushes >= 8) return giveUp(entry, body, "The cart with the " + b.noun + " stopped short of its cell.");
                var behind = b.rails.get(at - 1);
                var stand = behind.add(behind.getX() - cart.getBlockX(), 0, behind.getZ() - cart.getBlockZ());
                b.since = now;
                if (player.getBlockPos().getSquaredDistance(stand) > 1) {
                    return List.of(new NativeTaskCandidate(bringTask(entry, "after:" + b.pushes), "native_goto", Map.of("x", String.valueOf(stand.getX()),
                            "y", String.valueOf(stand.getY()), "z", String.valueOf(stand.getZ()), "range", "0", "centre", "true")));
                }
                return List.of(new NativeTaskCandidate(bringTask(entry, "again:" + b.pushes), "native_push",
                        Map.of("target", cart.getUuid().toString(), "seconds", "10")));
            }
            case SHUT -> {
                // Out of the front first, where a nudge may have left it.
                if (player.getBoundingBox().intersects(new net.minecraft.util.math.Box(b.slot))) return List.of(standAt(bringTask(entry, "out of the front"), b.slot.south()));
                var state = world.getBlockState(b.slot);
                if (state.isIn(net.minecraft.registry.tag.BlockTags.RAILS)) {
                    if (!body.miningEligible(b.slot)) return List.of(standAt(bringTask(entry, "by the front"), b.slot.south()));
                    return List.of(breakAt(bringTask(entry, "front rail"), b.slot));
                }
                if (isShutter(state)) {
                    b.phase = Bring.Phase.FREE;
                    return bringCandidates(entry, body);
                }
                int slot = shutterSlot(player);
                if (slot < 0) return giveUp(entry, body, "I've no block to shut the " + b.noun + "'s cell with a moment.");
                if (slot >= 9) return List.of(new NativeTaskCandidate(task, "native_swap", Map.of("source", String.valueOf(slot), "target", "8")));
                if (!reaches(player, b.slot)) return List.of(standAt(bringTask(entry, "by the front"), b.slot.south()));
                return List.of(new NativeTaskCandidate(bringTask(entry, "shut"), "native_place", Map.of("x", String.valueOf(b.slot.getX()),
                        "y", String.valueOf(b.slot.getY()), "z", String.valueOf(b.slot.getZ()), "slot", String.valueOf(slot), "side", "up")));
            }
            case FREE -> {
                var cart = cartOf(world, b);
                if (cart == null) {
                    b.phase = Bring.Phase.FETCH;
                    return bringCandidates(entry, body);
                }
                if (!player.canInteractWithEntity(cart, 0)) return List.of(standAt(bringTask(entry, "by the front"), b.slot.south()));
                return List.of(new NativeTaskCandidate(bringTask(entry, "break cart"), "native_cart", Map.of("mode", "break", "target", cart.getUuid().toString())));
            }
            case FETCH -> {
                var state = world.getBlockState(b.slot);
                if (isShutter(state)) {
                    if (!reaches(player, b.slot)) return List.of(standAt(bringTask(entry, "by the front"), b.slot.south()));
                    return List.of(breakAt(bringTask(entry, "unshut"), b.slot));
                }
                boolean cartLying = !world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(b.floor).expand(0.5, 1, 0.5),
                        item -> item.getStack().isOf(Items.MINECART)).isEmpty();
                boolean railLeft = world.getBlockState(b.floor).isIn(net.minecraft.registry.tag.BlockTags.RAILS);
                if (cartLying || railLeft) {
                    // Into the cell's front for them: the cart picked up where it lies, the floor's rail taken up.
                    if (!player.getBlockPos().equals(b.slot)) {
                        return List.of(new NativeTaskCandidate(bringTask(entry, "in"), "native_goto", Map.of("x", String.valueOf(b.slot.getX()),
                                "y", String.valueOf(b.slot.getY()), "z", String.valueOf(b.slot.getZ()), "range", "0", "centre", "true")));
                    }
                    if (railLeft) return List.of(breakAt(bringTask(entry, "floor rail"), b.floor));
                    return List.of(waitTask(task, "For the cart to be picked up"));
                }
                b.phase = Bring.Phase.CLOSE;
                return bringCandidates(entry, body);
            }
            case CLOSE -> {
                var state = world.getBlockState(b.slot);
                if (state.isOf(net.minecraft.block.Block.getBlockFromItem(b.jobBlock))) {
                    b.phase = Bring.Phase.TIDY;
                    return bringCandidates(entry, body);
                }
                // Out of the front, and its job block put there behind the villager.
                if (player.getBoundingBox().intersects(new net.minecraft.util.math.Box(b.slot))) {
                    var out = b.slot.south();
                    return List.of(new NativeTaskCandidate(bringTask(entry, "out"), "native_goto", Map.of("x", String.valueOf(out.getX()),
                            "y", String.valueOf(out.getY()), "z", String.valueOf(out.getZ()), "range", "0", "centre", "true")));
                }
                int slot = slotOf(player, b.jobBlock);
                if (slot < 0) return giveUp(entry, body, "I've lost the " + b.noun + "'s job block.");
                if (slot >= 9) return List.of(new NativeTaskCandidate(task, "native_swap", Map.of("source", String.valueOf(slot), "target", "8")));
                return List.of(new NativeTaskCandidate(bringTask(entry, "job block"), "native_place", Map.of("x", String.valueOf(b.slot.getX()),
                        "y", String.valueOf(b.slot.getY()), "z", String.valueOf(b.slot.getZ()), "slot", String.valueOf(slot), "side", "up")));
            }
            case UNDO -> {
                // Given up with the cart down: broken and taken back, whoever rides it set down where it is.
                var cart = cartOf(world, b);
                if (cart == null) {
                    var lying = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, player.getBoundingBox().expand(8),
                            item -> item.getStack().isOf(Items.MINECART));
                    if (!lying.isEmpty() && b.doorTries++ < 40) return List.of(goNear(bringTask(entry, "pick up cart"), lying.getFirst().getBlockPos()));
                    b.phase = Bring.Phase.TIDY;
                    return bringCandidates(entry, body);
                }
                if (!player.canInteractWithEntity(cart, 0)) return List.of(goNear(task, cart.getBlockPos()));
                return List.of(new NativeTaskCandidate(bringTask(entry, "undo cart"), "native_cart", Map.of("mode", "break", "target", cart.getUuid().toString())));
            }
            case TIDY -> {
                // Its rails taken up again, nearest first: all of them, when it gave up; else those the cell does not keep.
                BlockPos nearest = null;
                for (var at : b.rails) {
                    if (b.failure == null && (at.equals(b.floor) || at.equals(b.slot)) || !world.getBlockState(at).isIn(net.minecraft.registry.tag.BlockTags.RAILS)) continue;
                    if (nearest == null || player.getBlockPos().getSquaredDistance(at) < player.getBlockPos().getSquaredDistance(nearest)) nearest = at;
                }
                if (nearest != null) {
                    var take = bringTask(entry, "take up:" + nearest.asLong());
                    // Where the break would be allowed from, as the break itself judges it: onto the rail itself if not.
                    if (!body.miningEligible(nearest)) return List.of(new NativeTaskCandidate(take, "native_goto", Map.of("x", String.valueOf(nearest.getX()),
                            "y", String.valueOf(nearest.getY()), "z", String.valueOf(nearest.getZ()), "range", "0.5", "partial", "true")));
                    return List.of(breakAt(take, nearest));
                }
                b.phase = Bring.Phase.DOOR_BACK;
                return bringCandidates(entry, body);
            }
            case DOOR_BACK -> {
                if (world.getBlockState(b.door).getBlock() instanceof net.minecraft.block.DoorBlock) {
                    if (b.failure != null) return endBring(entry, b.failure, false);
                    entry.brought.add(b.villager);
                    return endBring(entry, "I've brought the " + b.noun + " home: it's in its cell in my trading hall.", true);
                }
                // The door put back as the hall's plan has it, by the build runner, which places a door as players do.
                var hall = dev.kindred.core.construction.TradingHall.of(b.cells, b.wood, "cobblestone");
                var door = new ArrayList<dev.kindred.core.construction.Blueprint.Block>();
                var rel = new dev.kindred.core.construction.Pos(b.door.getX() - b.origin.getX(), b.door.getY() - b.origin.getY(), b.door.getZ() - b.origin.getZ());
                for (var block : hall.blocks()) if (block.at().x() == rel.x() && block.at().z() == rel.z() && (block.at().y() == rel.y() || block.at().y() == rel.y() + 1)) door.add(block);
                var plan = new dev.kindred.core.construction.Blueprint("the trading hall's door", hall.source(), hall.creator(), hall.license(), door, List.of());
                if (++b.doorTries > 3 || startBuild(server.getCommandSource(), entry.data.id(), plan, b.origin, 0) != 1) {
                    if (b.failure != null) return endBring(entry, b.failure + " I couldn't put my hall's door back.", false);
                    entry.brought.add(b.villager);
                    return endBring(entry, "I've brought the " + b.noun + " home, but couldn't put my hall's door back.", true);
                }
                return null;
            }
        }
        return null;
    }

    /** The villager to bring and the line to bring it by, or null with why not noted. */
    private Bring planBring(Entry entry, NativePlayerBody body, PlayerMemoryStore.Hall hall) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos origin = new BlockPos(hall.x(), hall.y(), hall.z());
        var cells = dev.kindred.core.construction.TradingHall.cells(hall.cells());
        var holding = Valuables.holding(player, lootWanted(entry));
        var around = new net.minecraft.util.math.Box(origin).expand(BRING_RANGE);
        for (var villager : world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class, around, v -> v.isAlive() && !v.isBaby() && !v.hasVehicle())) {
            var id = villager.getUuid();
            if (entry.brought.contains(id) || inCell(villager, origin, cells)) continue;
            var known = entry.villagers.get(id);
            boolean kept = entry.rerollKept.contains(id) || known != null && dev.kindred.core.guide.Trades.worthKeeping(known.offers(), holding);
            if (!kept) continue;
            var profession = profession(villager);
            if (profession.jobBlock() == null) continue;
            String noun = profession.name().toLowerCase(java.util.Locale.ROOT);
            if (villager.getExperience() <= 0) {
                noteBring(entry, "The " + noun + " has never traded: moved, it would let its job go");
                continue;
            }
            dev.kindred.core.construction.TradingHall.Cell free = null;
            for (var cell : cells) {
                BlockPos floor = origin.add(cell.stand().x(), cell.stand().y(), cell.stand().z());
                BlockPos slot = origin.add(cell.jobBlock().x(), cell.jobBlock().y(), cell.jobBlock().z());
                if (world.getBlockState(slot).isAir() && world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
                        new net.minecraft.util.math.Box(floor).expand(0, 0, 2), v -> true).isEmpty()) {
                    free = cell;
                    break;
                }
            }
            if (free == null) {
                noteBring(entry, "No cell free in the trading hall");
                return null;
            }
            var route = dev.kindred.core.construction.RailRoute.plan(ground(world), villager.getBlockX(), villager.getBlockZ(),
                    new dev.kindred.core.construction.Pos(origin.getX(), origin.getY(), origin.getZ()), hall.cells(), free);
            if (route.isEmpty()) {
                noteBring(entry, "No rail line from the " + noun + " to its cell");
                continue;
            }
            var rails = route.get().rails().stream().map(p -> new BlockPos(p.x(), p.y(), p.z())).toList();
            var jobBlock = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(profession.jobBlock()));
            int railsHeld = 0, carts = 0, jobs = 0, shutters = shutterSlot(player) < 0 ? 0 : 1;
            var inventory = player.getInventory();
            for (int slot = 0; slot < 36; slot++) {
                var stack = inventory.getStack(slot);
                if (stack.isOf(Items.RAIL)) railsHeld += stack.getCount();
                if (stack.isOf(Items.MINECART)) carts += stack.getCount();
                if (stack.isOf(jobBlock)) jobs += stack.getCount();
            }
            int railsNeeded = (int) rails.stream().filter(at -> !world.getBlockState(at).isIn(net.minecraft.registry.tag.BlockTags.RAILS)).count();
            if (railsHeld < railsNeeded || carts < 1 || jobs < 1 || shutters < 1) {
                noteBring(entry, "To bring the " + noun + " home: " + railsNeeded + " rails (" + railsHeld + " held), a minecart (" + carts + "), a "
                        + profession.jobBlock().substring(10).replace('_', ' ') + " (" + jobs + ") and a plain block it can take back (" + shutters + ")");
                return null;
            }
            var r = route.get();
            BlockPos floor = origin.add(free.stand().x(), free.stand().y(), free.stand().z());
            BlockPos slot = origin.add(free.jobBlock().x(), free.jobBlock().y(), free.jobBlock().z());
            BlockPos door = origin.add(dev.kindred.core.construction.TradingHall.width(hall.cells()) / 2, 1, 7);
            noteBring(entry, "Bringing the " + noun + " home");
            return new Bring(id, noun, rails, new BlockPos(r.cart().x(), r.cart().y(), r.cart().z()), new BlockPos(r.push().x(), r.push().y(), r.push().z()),
                    floor, slot, door, origin, hall.cells(), hall.wood(), jobBlock);
        }
        return null;
    }

    /** Whether a villager stands in one of the hall's cells: on its floor or its bed. */
    private static boolean inCell(net.minecraft.entity.passive.VillagerEntity villager, BlockPos origin, List<dev.kindred.core.construction.TradingHall.Cell> cells) {
        var at = villager.getBlockPos();
        for (var cell : cells) {
            if (at.getX() == origin.getX() + cell.stand().x() && Math.abs(at.getY() - origin.getY() - 1) <= 1
                    && at.getZ() >= origin.getZ() + cell.stand().z() - 2 && at.getZ() <= origin.getZ() + cell.stand().z()) return true;
        }
        return false;
    }

    private static net.minecraft.entity.vehicle.AbstractMinecartEntity cartOf(net.minecraft.server.world.ServerWorld world, Bring b) {
        return b.cartId != null && world.getEntity(b.cartId) instanceof net.minecraft.entity.vehicle.AbstractMinecartEntity c && c.isAlive() ? c : null;
    }

    private static boolean isShutter(net.minecraft.block.BlockState state) {
        for (var shutter : SHUTTERS) if (state.isOf(net.minecraft.block.Block.getBlockFromItem(shutter))) return true;
        return false;
    }

    private static boolean reaches(ServerPlayerEntity player, BlockPos at) {
        return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(at)) <= 4.5 * 4.5;
    }

    /** To the middle of a block to stand on: the corridor before a cell's front, where its steps are reached from. */
    private static NativeTaskCandidate standAt(dev.kindred.core.TaskPreferences.Candidate task, BlockPos at) {
        return new NativeTaskCandidate(task, "native_goto", Map.of("x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()),
                "z", String.valueOf(at.getZ()), "range", "0", "centre", "true"));
    }


    private static NativeTaskCandidate breakAt(dev.kindred.core.TaskPreferences.Candidate task, BlockPos at) {
        return new NativeTaskCandidate(task, "native_break", Map.of("x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()),
                "z", String.valueOf(at.getZ()), "collect", "true"));
    }

    /**
     * Bringing it given up part way: what it put down taken back first (the cart broken and picked up, every rail taken
     * up, the door put back), then said why. Before anything is laid, it simply ends.
     */
    private List<NativeTaskCandidate> giveUp(Entry entry, NativePlayerBody body, String why) {
        var b = entry.bring;
        if (b.phase == Bring.Phase.DOOR) return endBring(entry, why, false);
        KindredMod.LOGGER.info("{} bringing: giving up ({}); taking back what it put down", entry.data.displayName(), why);
        b.failure = why;
        b.failed = 0;
        b.doorTries = 0;
        b.phase = Bring.Phase.UNDO;
        return bringCandidates(entry, body);
    }

    /** Bringing it ended: said, and a while before it looks again for one to bring. */
    private List<NativeTaskCandidate> endBring(Entry entry, String said, boolean done) {
        KindredMod.LOGGER.info("{} bringing: {}", entry.data.displayName(), said);
        say(entry, said, "");
        noteBring(entry, done ? "Brought home" : said);
        entry.bring = null;
        entry.bringAt = server.getTicks() + (done ? 200 : 6000);
        return null;
    }

    private static void noteBring(Entry entry, String why) {
        if (why.equals(entry.bringNote)) return;
        entry.bringNote = why;
        KindredMod.LOGGER.info("{} bringing: {}", entry.data.displayName(), why);
    }

    private static dev.kindred.core.TaskPreferences.Candidate bringTask(Entry entry, String kind) {
        var b = entry.bring;
        UUID id = UUID.nameUUIDFromBytes(("bring:" + entry.data.id() + ":" + b.villager + ":" + b.phase + ":" + kind).getBytes(StandardCharsets.UTF_8));
        b.taskIds.add(id);
        return new dev.kindred.core.TaskPreferences.Candidate(id, dev.kindred.core.TaskPreferences.Priority.PROJECT, 56.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
    }

    /** Fixtures: the villagers it has brought home, what it last noted, and the step it is at. */
    Set<UUID> brought(UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        return entry == null ? Set.of() : Set.copyOf(entry.brought);
    }

    String bringNote(UUID id) {
        requireThread();
        Entry entry = entries.get(id);
        return entry == null ? "" : entry.bringNote + (entry.bring == null ? "" : " [" + entry.bring.phase + "]");
    }

    /** A lectern of its own put down by an unemployed villager, and its offers rerolled by breaking and replacing it (E-45c). */
    private static final class Reroll {
        enum Phase { PLACE, TAKE_JOB, LOOK, BREAK, LOSE_JOB }

        final UUID villager;
        BlockPos lectern;
        /** Where it stood to begin with, clear of the lectern, to step back to. */
        BlockPos stand;
        Phase phase = Phase.PLACE;
        int tries, failed, nudges;
        long since;
        boolean givingUp;
        /** Each look's offers, in words, for fixtures. */
        final List<String> looks = new ArrayList<>();
        /** The ids of the steps it has given, to know its own refusals. */
        final Set<UUID> taskIds = new HashSet<>();

        Reroll(UUID villager, BlockPos lectern) {
            this.villager = villager;
            this.lectern = lectern.toImmutable();
        }
    }

    /** How long a villager is given to let the job go before it gives up on it; and to take the lectern up before a nudge. */
    static final long JOB_WAIT = 1200, JOB_NUDGE = 300;

    /**
     * A librarian of its own (E-45c), as players make one: by a villager with no job (not a nitwit), within eight
     * blocks and in sight, it puts down a lectern it holds; once the villager has taken it up, it looks at its offers,
     * never trading (a trade would lock them); not worth keeping (E-45a's whom to keep), it breaks the lectern, takes
     * it back, and puts it down again once the villager has let the job go, which rerolls its offers, as vanilla does
     * for a novice that never traded. It stops when the librarian is worth keeping, or gives up after its tries, the
     * lectern taken back. Under the server's AI world edits only, never in a match.
     */
    private List<NativeTaskCandidate> rerollCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        long now = server.getTicks();
        if (!worldEditsEnabled() || matches.running(player.getUuid()).isPresent()) return null;
        var world = player.getServerWorld();
        var r = entry.reroll;
        if (r == null) {
            if (now < entry.rerollAt || slotOf(player, Items.LECTERN) < 0) return null;
            net.minecraft.entity.passive.VillagerEntity jobless = null;
            for (var villager : world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class, player.getBoundingBox().expand(8),
                    v -> v.isAlive() && !v.isBaby() && !v.isSleeping() && profession(v) == dev.kindred.core.guide.Trades.Profession.NONE
                            && !entry.rerollGivenUp.contains(v.getUuid()) && player.canSee(v))) {
                if (jobless == null || player.squaredDistanceTo(villager) < player.squaredDistanceTo(jobless)) jobless = villager;
            }
            BlockPos spot = jobless == null ? null : lecternSpot(player, entry.setup, jobless.getBlockPos());
            if (spot == null) {
                entry.rerollAt = now + 600;
                return null;
            }
            r = entry.reroll = new Reroll(jobless.getUuid(), spot);
            r.stand = player.getBlockPos().toImmutable();
            KindredMod.LOGGER.info("{} reroll: a lectern for the villager {} at {}", entry.data.displayName(), r.villager, spot.toShortString());
        }
        var villager = world.getEntity(r.villager) instanceof net.minecraft.entity.passive.VillagerEntity v && v.isAlive() ? v : null;
        boolean standing = world.getBlockState(r.lectern).isOf(net.minecraft.block.Blocks.LECTERN);
        if (villager == null && !standing) {
            entry.reroll = null;
            return null;
        }
        if (villager == null || r.failed >= 3) {
            // The villager gone, or the same step refused three times over: the lectern taken back, and it is let be.
            r.givingUp = true;
            r.phase = Reroll.Phase.BREAK;
            if (r.failed >= 6) {
                entry.rerollGivenUp.add(r.villager);
                entry.reroll = null;
                return null;
            }
        }
        var task = rerollTask(entry, "step");
        boolean reach = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(r.lectern)) <= 4.5 * 4.5;
        var at = Map.of("x", String.valueOf(r.lectern.getX()), "y", String.valueOf(r.lectern.getY()), "z", String.valueOf(r.lectern.getZ()));
        switch (r.phase) {
            case PLACE -> {
                if (standing) {
                    r.phase = Reroll.Phase.TAKE_JOB;
                    r.since = now;
                    return List.of(waitTask(task, "For the villager to take up the lectern"));
                }
                int slot = slotOf(player, Items.LECTERN);
                if (slot < 0) {
                    // Just broken: the lectern lies where it fell a moment before it is picked up.
                    if (r.tries > 0 && now - r.since < 100) return List.of(waitTask(task, "For the lectern to be picked up"));
                    KindredMod.LOGGER.info("{} reroll: no lectern to put down ({} lying near, entities ticking there {}, the villager's age {}); let be",
                            entry.data.displayName(), world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, player.getBoundingBox().expand(8),
                                    item -> item.getStack().isOf(Items.LECTERN)).size(), world.shouldTickEntity(r.lectern), villager == null ? -1 : villager.age);
                    entry.reroll = null;
                    return null;
                }
                // Something in the way of where it goes (the villager, let go of its job, wandering onto it): another side.
                // Standing where it goes itself, as picking the lectern up took it there: another side clear of it, or
                // off it to the middle of a block beside (a body half a block over the edge of where it stood was still
                // in the way, and stepped back to where it already was without end).
                if (player.getBoundingBox().intersects(new net.minecraft.util.math.Box(r.lectern))) {
                    BlockPos other = villager == null ? null : lecternSpot(player, entry.setup, villager.getBlockPos());
                    if (other != null && !other.equals(r.lectern)) {
                        r.lectern = other;
                        return rerollCandidates(entry, body);
                    }
                    var off = offTheLectern(entry, world, r);
                    return off == null ? List.of(waitTask(task, "For room beside the villager")) : List.of(off);
                }
                if (!world.getOtherEntities(null, new net.minecraft.util.math.Box(r.lectern)).isEmpty()) {
                    BlockPos other = villager == null ? null : lecternSpot(player, entry.setup, villager.getBlockPos());
                    if (other == null || !world.getOtherEntities(null, new net.minecraft.util.math.Box(other)).isEmpty()) {
                        return List.of(waitTask(task, "For the spot to clear"));
                    }
                    r.lectern = other;
                    return rerollCandidates(entry, body);
                }
                if (slot >= 9) return List.of(new NativeTaskCandidate(task, "native_swap", Map.of("source", String.valueOf(slot), "target", "8")));
                if (!reach) return List.of(goNear(task, r.lectern));
                var args = new HashMap<>(at);
                args.put("slot", String.valueOf(slot));
                args.put("side", "up");
                return List.of(new NativeTaskCandidate(task, "native_place", Map.copyOf(args)));
            }
            case TAKE_JOB -> {
                if (villager != null && profession(villager) == dev.kindred.core.guide.Trades.Profession.LIBRARIAN) {
                    r.phase = Reroll.Phase.LOOK;
                } else if (!standing) {
                    r.phase = Reroll.Phase.PLACE;
                } else if (now - r.since > JOB_NUDGE && r.nudges < 2) {
                    // Not taken up: vanilla's villager may have given up on reaching it, for a while. Taken up and put
                    // down again, which a player does too.
                    r.nudges++;
                    r.phase = Reroll.Phase.BREAK;
                } else if (now - r.since > JOB_NUDGE) {
                    say(entry, "The villager won't take up my lectern; I've taken it back.", "");
                    r.givingUp = true;
                    r.phase = Reroll.Phase.BREAK;
                } else {
                    return List.of(waitTask(task, "For the villager to take up the lectern"));
                }
                return rerollCandidates(entry, body);
            }
            case LOOK -> {
                if (villager == null) return null;
                if (!player.canInteractWithEntity(villager, 0)) return List.of(goNear(task, villager.getBlockPos()));
                return List.of(new NativeTaskCandidate(task, "native_trade", Map.of("target", villager.getUuid().toString(), "look", "true")));
            }
            case BREAK -> {
                if (!standing) {
                    if (r.givingUp) {
                        entry.rerollGivenUp.add(r.villager);
                        entry.reroll = null;
                        entry.rerollAt = now + 1200;
                        return null;
                    }
                    r.phase = Reroll.Phase.LOSE_JOB;
                    r.since = now;
                    return List.of(waitTask(task, "For the villager to let the job go"));
                }
                if (!reach) return List.of(goNear(task, r.lectern));
                // Standing on it, as a small pen leaves a player stepping up onto it: off it first (a break of the block
                // underfoot is refused, and six refusals gave the reroll up with the lectern still in the ground).
                if (player.getBoundingBox().intersects(new net.minecraft.util.math.Box(r.lectern).stretch(0, 1, 0))) {
                    var off = offTheLectern(entry, world, r);
                    if (off != null) return List.of(off);
                }
                var args = new HashMap<>(at);
                args.put("collect", "true");
                return List.of(new NativeTaskCandidate(task, "native_break", Map.copyOf(args)));
            }
            case LOSE_JOB -> {
                if (villager != null && profession(villager) == dev.kindred.core.guide.Trades.Profession.NONE) {
                    r.tries++;
                    r.phase = Reroll.Phase.PLACE;
                    r.since = now;
                    return rerollCandidates(entry, body);
                }
                if (now - r.since > JOB_WAIT) {
                    // Locked to its offers after all: it keeps the job, and this villager is let be.
                    KindredMod.LOGGER.info("{} reroll: the villager {} kept its job ({}, experience {}, its job site {}, {} there, a site {}, customer {}, asleep {}, AI {}, entity {} age {} at {} riding {} removed {}, entities ticking there {} and at it {}, forced {}); let be",
                            entry.data.displayName(), r.villager, villager == null ? "gone" : villager.getVillagerData().getProfession(),
                            villager == null ? 0 : villager.getExperience(), villager == null ? "unknown"
                                    : villager.getBrain().getOptionalRegisteredMemory(net.minecraft.entity.ai.brain.MemoryModuleType.JOB_SITE)
                                            .map(site -> site.pos().toShortString()).orElse("none"),
                            world.getBlockState(r.lectern).getBlock(), world.getPointOfInterestStorage().getType(r.lectern).map(t -> t.getIdAsString()).orElse("none"),
                            villager != null && villager.hasCustomer(), villager != null && villager.isSleeping(), villager != null && !villager.isAiDisabled(),
                            villager == null ? -1 : villager.getId(), villager == null ? -1 : villager.age,
                            villager == null ? "-" : villager.getBlockPos().toShortString(), villager != null && villager.hasVehicle(),
                            villager == null ? "-" : String.valueOf(villager.getRemovalReason()), world.shouldTickEntity(r.lectern),
                            villager != null && world.shouldTickEntity(villager.getBlockPos()),
                            world.getForcedChunks().contains(new net.minecraft.util.math.ChunkPos(r.lectern).toLong()));
                    entry.rerollGivenUp.add(r.villager);
                    entry.reroll = null;
                    return null;
                }
                return List.of(waitTask(task, "For the villager to let the job go"));
            }
        }
        return null;
    }

    /**
     * The reroll's step as a task, its id its own for each phase, try and kind of step, so that a wait after a refused
     * placement is not the placement's id held back; the reroll keeps the last one given, to know its own refusals.
     */
    private static dev.kindred.core.TaskPreferences.Candidate rerollTask(Entry entry, String kind) {
        var r = entry.reroll;
        UUID id = UUID.nameUUIDFromBytes(("reroll:" + entry.data.id() + ":" + r.villager + ":" + r.phase + ":" + r.tries + ":" + kind)
                .getBytes(StandardCharsets.UTF_8));
        r.taskIds.add(id);
        return new dev.kindred.core.TaskPreferences.Candidate(id, dev.kindred.core.TaskPreferences.Priority.PROJECT, 56.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
    }

    private static NativeTaskCandidate waitTask(dev.kindred.core.TaskPreferences.Candidate task, String why) {
        return new NativeTaskCandidate(new dev.kindred.core.TaskPreferences.Candidate(UUID.nameUUIDFromBytes((task.id() + ":wait")
                .getBytes(StandardCharsets.UTF_8)), task.priority(), task.needScore(), task.affinities(), task.eligibility()),
                "native_wait", Map.of("seconds", "1"));
    }

    /**
     * A walk off the lectern's block to the middle of one beside it, clear of it: where it stood to begin with, else
     * any side of the lectern with room to stand; or null when there is none.
     */
    private static NativeTaskCandidate offTheLectern(Entry entry, net.minecraft.server.world.ServerWorld world, Reroll r) {
        var sides = new ArrayList<BlockPos>();
        sides.add(r.stand);
        for (Direction side : Direction.Type.HORIZONTAL) sides.add(r.lectern.offset(side));
        for (BlockPos at : sides) {
            if (at.equals(r.lectern) || !world.getBlockState(at).getCollisionShape(world, at).isEmpty()
                    || !world.getBlockState(at.up()).getCollisionShape(world, at.up()).isEmpty()
                    || !world.getBlockState(at.down()).isSolidBlock(world, at.down())) continue;
            if (!world.getOtherEntities(null, new net.minecraft.util.math.Box(at), e -> !(e instanceof ServerPlayerEntity)).isEmpty()) continue;
            return new NativeTaskCandidate(rerollTask(entry, "off:" + at.asLong()), "native_goto", Map.of("x", String.valueOf(at.getX()),
                    "y", String.valueOf(at.getY()), "z", String.valueOf(at.getZ()), "range", "0", "centre", "true", "partial", "true"));
        }
        return null;
    }

    private static NativeTaskCandidate goNear(dev.kindred.core.TaskPreferences.Candidate task, BlockPos at) {
        return new NativeTaskCandidate(task, "native_goto", Map.of("x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()),
                "z", String.valueOf(at.getZ()), "range", "2.5", "partial", "true"));
    }

    private static int slotOf(ServerPlayerEntity player, Item item) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) if (inventory.getStack(slot).isOf(item)) return slot;
        return -1;
    }

    /** A spot beside a villager to put a lectern down: open, on solid ground, within reach and allowed by the edit policy. */
    private BlockPos lecternSpot(ServerPlayerEntity player, WorkSetup setup, BlockPos villager) {
        var world = player.getServerWorld();
        for (Direction side : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            BlockPos pos = villager.offset(side);
            if (!world.isChunkLoaded(pos) || !world.getBlockState(pos).isReplaceable() || !world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) continue;
            if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > 4.5 * 4.5) continue;
            if (!world.getOtherEntities(null, new net.minecraft.util.math.Box(pos)).isEmpty()) continue;
            if (NativeEdits.decide(player, setup, EditPolicy.Kind.PLACE, pos, "minecraft:lectern", edits).allowed()) return pos.toImmutable();
        }
        return null;
    }

    /** A look at the librarian it is rerolling: kept when worth it, else the lectern broken for another roll, or given up. */
    private void rerollLooked(Entry entry, ServerPlayerEntity player, List<dev.kindred.core.guide.Trades.Offer> seen) {
        var r = entry.reroll;
        var holding = Valuables.holding(player, lootWanted(entry));
        var best = dev.kindred.core.guide.Trades.best(seen, holding);
        r.looks.add(best.map(d -> dev.kindred.core.guide.Trades.sells(d.offer())).orElse("nothing worth it")
                + " / " + seen.stream().map(o -> dev.kindred.core.guide.Trades.sells(o)).toList());
        entry.rerollLooks.add(seen.stream().map(o -> dev.kindred.core.guide.Trades.sells(o)).toList().toString());
        var verdict = dev.kindred.core.guide.Trades.reroll(seen, holding, r.tries);
        r.nudges = 0;
        var looked = player.getServerWorld().getEntity(r.villager);
        KindredMod.LOGGER.info("{} reroll {}: {}; {} (entity {} age {})", entry.data.displayName(), r.tries, r.looks.getLast(), verdict,
                looked == null ? -1 : looked.getId(), looked == null ? -1 : looked.age);
        switch (verdict) {
            case KEEP -> {
                say(entry, "This librarian sells " + best.map(d -> dev.kindred.core.guide.Trades.sells(d.offer())).orElse("what I wanted")
                        + ": I'll keep it.", "");
                entry.rerollKept.add(r.villager);
                entry.reroll = null;
            }
            case REROLL -> r.phase = Reroll.Phase.BREAK;
            case GIVE_UP -> {
                say(entry, "No book worth keeping from this librarian in " + (r.tries + 1) + " tries.", "");
                r.givingUp = true;
                r.phase = Reroll.Phase.BREAK;
            }
        }
    }

    /** A player's lectern reroll as it stands, for fixtures: its tries, its phase and each look; null with none. */
    String reroll(UUID id) {
        Entry entry = entries.get(id);
        var r = entry == null ? null : entry.reroll;
        return r == null ? null : r.phase + " after " + r.tries + " tries: " + r.looks;
    }

    /** Every look at a librarian it rerolled, its offers in words, oldest first, for fixtures. */
    List<String> rerollLooks(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? List.of() : List.copyOf(entry.rerollLooks);
    }

    /** Villagers it gave up rerolling, for fixtures. */
    int rerollsGivenUp(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? 0 : entry.rerollGivenUp.size();
    }

    /** Villagers it rerolled until worth keeping, for fixtures. */
    int rerollsKept(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? 0 : entry.rerollKept.size();
    }

    /** The villagers a player has looked at, for fixtures. */
    int villagersKnown(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? 0 : entry.villagers.size();
    }

    // ------------------------------------------------------------------ extended inventory (E-43)

    /** A store of its own: where, and whose, its own or its team's. */
    private record OwnStore(BlockPos pos, dev.kindred.core.team.Storage.Whose whose) { }

    /** Someone it saw open a store of its own: who, whether on its side, and when. */
    private record SeenAtStore(String name, boolean teammate, long tick) { }

    /** What it asked its team of what is gone from a store, and until when it waits for a word. */
    private record StorageAsk(dev.kindred.core.team.Storage.Look look, long until) { }

    /** How long it waits for a teammate's word on what is gone: thirty seconds. */
    static final long STORAGE_ASK_WAIT = 600;

    private static dev.kindred.core.team.Storage.Place storePlace(ServerPlayerEntity player, BlockPos pos) {
        return new dev.kindred.core.team.Storage.Place(player.getServerWorld().getRegistryKey().getValue().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Its stores: the storage its administrator assigned it, its team's when the same chest is a teammate's assigned
     * storage too, else its own; and the chests of the bases it built, its own. Reckoned again every ten seconds.
     */
    private List<OwnStore> ownStores(Entry entry, ServerPlayerEntity player) {
        long now = server.getTicks();
        if (entry.ownStores != null && now < entry.ownStoresAt) return entry.ownStores;
        var out = new ArrayList<OwnStore>();
        String dimension = player.getServerWorld().getRegistryKey().getValue().toString();
        var assigned = entry.setup == null || !dimension.equals(entry.setup.dimension()) ? null : entry.setup.storage();
        if (assigned != null) {
            boolean shared = false;
            for (Entry other : entries.values()) {
                if (other != entry && other.setup != null && other.setup.storage() != null && dimension.equals(other.setup.dimension())
                        && other.setup.storage().position().equals(assigned.position())) shared = true;
            }
            out.add(new OwnStore(new BlockPos(assigned.position().x(), assigned.position().y(), assigned.position().z()),
                    shared ? dev.kindred.core.team.Storage.Whose.TEAM : dev.kindred.core.team.Storage.Whose.OWN));
        }
        if (entry.bases != null) {
            for (var base : entry.bases.built) {
                // A base's chests are its stores once the base is built: not while they stand in walls still going up.
                if (!dimension.equals(base.dimension()) || !base.complete()) continue;
                for (var block : placedOf(base)) {
                    if (block.spec().block().equals("minecraft:chest")) out.add(new OwnStore(BuildRunner.blockPos(block.at()), dev.kindred.core.team.Storage.Whose.OWN));
                }
            }
        }
        entry.ownStores = List.copyOf(out);
        entry.ownStoresAt = now + 200;
        return entry.ownStores;
    }

    private OwnStore ownStore(Entry entry, ServerPlayerEntity player, BlockPos pos) {
        for (var store : ownStores(entry, player)) if (store.pos().equals(pos)) return store;
        return null;
    }

    /** Whether {@code other} is on its side: a player that controls it, or another AI player out of any match, as co-op is. */
    private boolean teammateOf(Entry entry, ServerPlayerEntity self, ServerPlayerEntity other) {
        if (matches.opponents(self.getUuid(), other.getUuid())) return false;
        if (aiPlayerOf(other) != null) return matches.running(other.getUuid()).isEmpty() && matches.running(self.getUuid()).isEmpty();
        return other.getUuid().equals(entry.data.ownerId()) || entry.nativeState != null
                && entry.nativeState.access().mayControl(new Actor(other.getUuid(), other.hasPermissionLevel(2)));
    }

    /** Its teammates sharing a store with it: those whose assigned storage the same chest is. */
    private List<Entry> sharers(Entry entry, ServerPlayerEntity player, BlockPos pos) {
        var out = new ArrayList<Entry>();
        String dimension = player.getServerWorld().getRegistryKey().getValue().toString();
        for (Entry other : entries.values()) {
            var storage = other == entry || other.setup == null ? null : other.setup.storage();
            if (storage != null && dimension.equals(other.setup.dimension()) && storage.position().equals(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))) {
                out.add(other);
            }
        }
        return out;
    }

    /** Whether it plays with others where it is: in a match, with a team (a shared store, or other AI players in co-op), or alone. */
    private dev.kindred.core.team.Missing.Mode storageMode(Entry entry, ServerPlayerEntity player, OwnStore store) {
        if (matches.running(player.getUuid()).filter(m -> m.mode().pvp).isPresent()) return dev.kindred.core.team.Missing.Mode.MATCH;
        if (store.whose() == dev.kindred.core.team.Storage.Whose.TEAM) return dev.kindred.core.team.Missing.Mode.TEAM;
        for (Entry other : entries.values()) {
            var mate = other == entry || other.nativeBody == null || !other.nativeBody.connected() ? null : other.nativeBody.entity();
            if (mate != null && mate.getWorld() == player.getWorld() && matches.running(mate.getUuid()).isEmpty()) return dev.kindred.core.team.Missing.Mode.TEAM;
        }
        return dev.kindred.core.team.Missing.Mode.ALONE;
    }

    /**
     * What it keeps for later put away (E-43): when a store of its own is within 24 blocks, out of any match, what the
     * value model says it keeps for later and has no use for as it goes is walked to its store and put in. A store it
     * no longer trusts, raided or robbed, it empties first and uses no more.
     */
    private List<NativeTaskCandidate> storeCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        long now = server.getTicks();
        if (!worldEditsEnabled()) return null;
        // Going to look, or moving the rest out, keeps its own short wait; putting away and fetching the longer one.
        entry.seenAtStores.values().removeIf(seen -> now - seen.tick() > 6000);
        boolean looking = entry.emptying != null || !entry.seenAtStores.isEmpty();
        if (looking ? now < entry.lookAt : now < entry.storeAt) return null;
        var world = player.getServerWorld();
        BlockPos target;
        Map<String, String> what;
        String skill = "native_store";
        var fetch = entry.emptying == null ? fetchFor(entry, player) : null;
        if (fetch != null) {
            // What its run wants that it has stored, fetched before it sets out to find more.
            target = fetch.getKey();
            what = fetch.getValue();
            skill = "native_take";
        } else if (entry.emptying != null) {
            target = entry.emptying;
            what = Map.of("take", "all");
        } else if ((target = sighted(entry, player)) != null) {
            // Someone it saw at a store of its own: it goes to see what is in it, in any mode.
            what = Map.of("look", "true");
        } else {
            if (matches.running(player.getUuid()).isPresent()) return null;
            var held = new HashMap<String, Integer>();
            var inventory = player.getInventory();
            for (int slot = 0; slot < 36; slot++) {
                var stack = inventory.getStack(slot);
                if (!stack.isEmpty()) held.merge(Valuables.id(stack), stack.getCount(), Integer::sum);
            }
            // What it took out a while ago it took for a reason: not put straight back.
            entry.recentlyTaken.values().removeIf(at -> now - at > 6000);
            held.keySet().removeAll(entry.recentlyTaken.keySet());
            var stow = dev.kindred.core.guide.Worth.stow(held, Valuables.holding(player, lootWanted(entry)),
                    entry.goal != dev.kindred.core.survival.SurvivalGoal.NONE);
            target = null;
            for (var store : ownStores(entry, player)) {
                if (entry.untrustedStores.contains(store.pos().asLong()) || !world.isChunkLoaded(store.pos())
                        || !(world.getBlockEntity(store.pos()) instanceof net.minecraft.inventory.Inventory)) continue;
                if (player.getBlockPos().getSquaredDistance(store.pos()) > 24 * 24) continue;
                if (target == null || player.getBlockPos().getSquaredDistance(store.pos()) < player.getBlockPos().getSquaredDistance(target)) target = store.pos();
            }
            if (stow.isEmpty() || target == null) {
                entry.storeAt = now + 600;
                return null;
            }
            what = Map.of("items", String.join(",", stow.keySet()));
        }
        UUID taskId = UUID.nameUUIDFromBytes(("store:" + entry.data.id() + ":" + target.asLong()).getBytes(StandardCharsets.UTF_8));
        entry.storeTaskId = taskId;
        var task = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 58.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        var args = new HashMap<String, String>(Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()), "z", String.valueOf(target.getZ())));
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) <= 4.5 * 4.5) {
            if (skill.equals("native_take")) args.clear();
            args.putAll(what);
            return List.of(new NativeTaskCandidate(task, skill, Map.copyOf(args)));
        }
        args.put("range", "2.5");
        return List.of(new NativeTaskCandidate(task, "native_goto", Map.copyOf(args)));
    }

    /** A store of its own it saw someone open while it was away, within 48 blocks: where it goes to look. */
    private BlockPos sighted(Entry entry, ServerPlayerEntity player) {
        BlockPos nearest = null;
        for (var store : ownStores(entry, player)) {
            if (!entry.seenAtStores.containsKey(store.pos().asLong()) || entry.untrustedStores.contains(store.pos().asLong())
                    || player.getBlockPos().getSquaredDistance(store.pos()) > 48 * 48) continue;
            if (nearest == null || player.getBlockPos().getSquaredDistance(store.pos()) < player.getBlockPos().getSquaredDistance(nearest)) nearest = store.pos();
        }
        return nearest;
    }

    /**
     * On a run, what the run wants that it knows it has in its assigned storage within 48 blocks, as many as the run
     * wants or the store holds: the store, and the take. None when the run wants nothing it has stored there.
     */
    private Map.Entry<BlockPos, Map<String, String>> fetchFor(Entry entry, ServerPlayerEntity player) {
        if (entry.goal == dev.kindred.core.survival.SurvivalGoal.NONE || entry.setup == null || entry.setup.storage() == null) return null;
        var assigned = entry.setup.storage().position();
        BlockPos pos = new BlockPos(assigned.x(), assigned.y(), assigned.z());
        if (ownStore(entry, player, pos) == null || entry.untrustedStores.contains(pos.asLong())
                || player.getBlockPos().getSquaredDistance(pos) > 48 * 48) return null;
        var known = entry.storage.store(storePlace(player, pos)).orElse(null);
        if (known == null) return null;
        for (var want : new java.util.TreeMap<>(lootWanted(entry)).entrySet()) {
            int have = known.count(want.getKey());
            if (!want.getKey().startsWith("minecraft:") || have <= 0 || want.getValue() <= 0) continue;
            return Map.entry(pos, Map.of("item", want.getKey(), "count", String.valueOf(Math.min(have, want.getValue()))));
        }
        return null;
    }

    /**
     * What it saw in a store of its own as it opened it, against what it knew (E-43): the counts set to what it saw, and
     * if anything it knew was there is not, what it does about it by how it plays, from what it counted and saw only.
     */
    private void lookedInto(Entry entry, ServerPlayerEntity player, OwnStore own, Map<String, Integer> seen) {
        long now = server.getTicks();
        var place = storePlace(player, own.pos());
        var look = entry.storage.looked(place, own.whose(), seen, now);
        var since = entry.seenAtStores.remove(own.pos().asLong());
        if (!look.isShort()) return;
        var holding = Valuables.holding(player, lootWanted(entry));
        double left = 0;
        for (var each : seen.entrySet()) {
            if (each.getValue() > 0) left += dev.kindred.core.guide.Worth.judge(new dev.kindred.core.guide.Worth.Stack(each.getKey(), each.getValue()), holding).worth();
        }
        var mode = storageMode(entry, player, own);
        var response = dev.kindred.core.team.Missing.respond(mode, look,
                since == null ? Optional.empty() : Optional.of(new dev.kindred.core.team.Missing.Seen(since.name(), since.teammate())), left);
        KindredMod.LOGGER.info("{} found {} gone from {} ({}{}): {}", entry.data.displayName(), dev.kindred.core.team.Missing.items(look.gone()),
                place.words(), mode, since == null ? ", nobody seen at it" : ", saw " + since.name() + " at it", response.say());
        if (response.say() != null) {
            if (response.to() == dev.kindred.core.team.Missing.Audience.TEAM) sayToTeam(entry, response.say());
            else if (response.to() == dev.kindred.core.team.Missing.Audience.OWN) say(entry, response.say(), "");
        }
        if (response.ask()) entry.storageAsk = new StorageAsk(look, now + STORAGE_ASK_WAIT);
        if (response.moveRest()) {
            entry.untrustedStores.add(own.pos().asLong());
            entry.emptying = own.pos();
        }
        if (store != null) {
            store.addMemory(new MemoryNote(UUID.randomUUID(), entry.data.id(), MemoryNote.Kind.FACT,
                    "Found " + dev.kindred.core.team.Missing.items(look.gone()) + " gone from " + place.words(), "storage_look", 0));
        }
    }

    /** A question of what is gone that nobody answered in time: the items reckoned lost, said as a count, nobody blamed. */
    private void storageAskLapsed(Entry entry) {
        var ask = entry.storageAsk;
        if (ask == null || server.getTicks() <= ask.until()) return;
        entry.storageAsk = null;
        var response = dev.kindred.core.team.Missing.unanswered(ask.look());
        if (response.say() != null) sayToTeam(entry, response.say());
    }

    /** A teammate owning up to what it asked of: their word taken. */
    private boolean storageAnswered(Entry entry, ServerPlayerEntity speaker, String message) {
        var ask = entry.storageAsk;
        if (ask == null || server.getTicks() > ask.until() || !dev.kindred.core.team.Missing.ownsUp(message)) return false;
        var self = entry.nativeBody != null && entry.nativeBody.connected() ? entry.nativeBody.entity() : null;
        if (self == null || !teammateOf(entry, self, speaker)) return false;
        entry.storageAsk = null;
        KindredMod.LOGGER.info("{} takes {}'s word for {}", entry.data.displayName(), speaker.getName().getString(),
                dev.kindred.core.team.Missing.items(ask.look().gone()));
        sayToTeam(entry, dev.kindred.core.team.Missing.thanked(speaker.getName().getString()));
        return true;
    }

    /** What it put in or took out of a store its team shares, told to its team; their counts change with it. */
    private void toldTeam(Entry entry, ServerPlayerEntity player, BlockPos pos, boolean put, Map<String, Integer> items) {
        if (items.isEmpty()) return;
        long now = server.getTicks();
        var place = storePlace(player, pos);
        sayToTeam(entry, dev.kindred.core.team.TeamTalk.stored(put, place, items));
        for (Entry mate : sharers(entry, player, pos)) {
            for (var each : items.entrySet()) {
                if (put) mate.storage.put(place, dev.kindred.core.team.Storage.Whose.TEAM, each.getKey(), each.getValue(), now);
                else mate.storage.took(place, each.getKey(), each.getValue(), now);
            }
        }
    }

    /** A store's use done: its look, its counts, and its team told; or not done, and storing let be a while. */
    private void storeEnded(Entry entry, NativeStoreAction stored, SkillRunner.State state) {
        long now = server.getTicks();
        var self = entry.nativeBody != null && entry.nativeBody.connected() ? entry.nativeBody.entity() : null;
        if (state != SkillRunner.State.COMPLETED || self == null) {
            if (state == SkillRunner.State.BLOCKED || state == SkillRunner.State.CANCELLED) {
                entry.storeAt = now + 1200;
                if (stored.takesOut()) entry.emptying = null;
            }
            return;
        }
        var own = ownStore(entry, self, stored.chest());
        if (own == null) own = new OwnStore(stored.chest(), dev.kindred.core.team.Storage.Whose.OWN);
        var place = storePlace(self, stored.chest());
        lookedInto(entry, self, own, stored.seen());
        for (var each : stored.moved().entrySet()) {
            if (stored.takesOut()) entry.storage.took(place, each.getKey(), each.getValue(), now);
            else entry.storage.put(place, own.whose(), each.getKey(), each.getValue(), now);
        }
        if (own.whose() == dev.kindred.core.team.Storage.Whose.TEAM) toldTeam(entry, self, stored.chest(), !stored.takesOut(), stored.moved());
        if (stored.takesOut()) entry.emptying = null;
        // Nothing to put in, or no room for it: let be a while; else straight on to the next.
        entry.storeAt = stored.moved().isEmpty() ? now + 2400 : now;
        entry.routineRefreshPending = true;
    }

    /** A take from its storage done (E-08c): its look, its count, and its team told when the store is theirs too. */
    private void takeEnded(Entry entry, NativeTakeAction take, SkillRunner.State state) {
        var self = entry.nativeBody != null && entry.nativeBody.connected() ? entry.nativeBody.entity() : null;
        if (state != SkillRunner.State.COMPLETED || self == null || take.chest() == null || take.taken() <= 0) {
            if (state == SkillRunner.State.BLOCKED) entry.storeAt = server.getTicks() + 1200;
            return;
        }
        var own = ownStore(entry, self, take.chest());
        if (own == null) return;
        lookedInto(entry, self, own, take.seen());
        entry.storage.took(storePlace(self, take.chest()), take.item(), take.taken(), server.getTicks());
        entry.recentlyTaken.put(take.item(), (long) server.getTicks());
        if (own.whose() == dev.kindred.core.team.Storage.Whose.TEAM) toldTeam(entry, self, take.chest(), false, Map.of(take.item(), take.taken()));
    }

    /**
     * Said to its team (E-43): to whoever controls it or a teammate of its, as players tell their own; never to an
     * opponent of its in a match.
     */
    private void sayToTeam(Entry entry, String text) {
        entry.lastSaid = text;
        entry.said(text);
        String line = "<" + entry.data.displayName() + "> " + text;
        KindredMod.LOGGER.info("{} (to its team)", line);
        var self = entry.nativeBody != null && entry.nativeBody.connected() ? entry.nativeBody.entity() : null;
        if (self == null) return;
        var told = new HashSet<UUID>();
        for (Entry mate : entries.values()) {
            var mateBody = mate.nativeBody != null && mate.nativeBody.connected() ? mate.nativeBody.entity() : null;
            if (mate != entry && (mateBody == null || !teammateOf(entry, self, mateBody))) continue;
            for (ServerPlayerEntity listener : server.getPlayerManager().getPlayerList()) {
                if (aiPlayerOf(listener) != null || matches.opponents(self.getUuid(), listener.getUuid()) || told.contains(listener.getUuid())) continue;
                boolean controls = listener.getUuid().equals(mate.data.ownerId()) || mate.nativeState != null
                        && mate.nativeState.access().mayControl(new Actor(listener.getUuid(), listener.hasPermissionLevel(2)));
                if (controls && told.add(listener.getUuid())) listener.sendMessage(Text.literal(line), false);
            }
        }
    }

    /** What it has stored, as it last counted, in words: its twelve most numerous items. */
    private static String storedWords(Entry entry) {
        var stored = entry.storage.extended(Map.of());
        if (stored.isEmpty()) return "";
        var most = new java.util.LinkedHashMap<String, Integer>();
        stored.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12).forEach(each -> most.put(each.getKey(), each.getValue()));
        return dev.kindred.core.team.Missing.items(most);
    }

    /** The last few things a player said, oldest first, for fixtures. */
    List<String> said(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? List.of() : List.copyOf(entry.saidLately);
    }

    /** How many of an item it has stored, as it knows it, for fixtures. */
    int storedCount(UUID id, String item) {
        Entry entry = entries.get(id);
        return entry == null ? 0 : entry.storage.stored(item);
    }

    // ------------------------------------------------------------------ named kit (E-16d)

    /** A name chosen for a piece, what the piece is ("diamond sword"), and why, for the log. */
    private record KitName(String name, String noun, String why, boolean byModel) { }

    private static boolean anvilAt(ServerWorld world, BlockPos pos) {
        return pos != null && world.isChunkLoaded(pos) && world.getBlockState(pos).isIn(net.minecraft.registry.tag.BlockTags.ANVIL);
    }

    /** Whether it has reached the enchanting stage: an enchanting table of its own, held or standing in a base it built. */
    private boolean enchantingStage(Entry entry, ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(Items.ENCHANTING_TABLE)) return true;
        if (entry.bases == null) return false;
        var world = player.getServerWorld();
        String dimension = world.getRegistryKey().getValue().toString();
        for (var base : entry.bases.built) {
            if (!dimension.equals(base.dimension())) continue;
            for (var block : placedOf(base)) {
                if (!block.spec().block().equals("minecraft:enchanting_table")) continue;
                BlockPos pos = BuildRunner.blockPos(block.at());
                if (world.isChunkLoaded(pos) && world.getBlockState(pos).isOf(Blocks.ENCHANTING_TABLE)) return true;
            }
        }
        return false;
    }

    /** The weapons and tools it holds, as the naming rules see them. */
    private static List<dev.kindred.core.KitNames.Piece> kitPieces(ServerPlayerEntity player) {
        var out = new ArrayList<dev.kindred.core.KitNames.Piece>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty() || dev.kindred.core.KitNames.Kind.of(Valuables.id(stack)).isEmpty()) continue;
            var enchanted = new HashMap<String, Integer>();
            for (var each : stack.getEnchantments().getEnchantmentEntries()) {
                each.getKey().getKey().ifPresent(key -> enchanted.put(key.getValue().toString(), each.getIntValue()));
            }
            var name = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
            out.add(new dev.kindred.core.KitNames.Piece(slot, Valuables.id(stack), enchanted,
                    name == null ? Optional.empty() : Optional.of(name.getString()),
                    stack.getOrDefault(net.minecraft.component.DataComponentTypes.REPAIR_COST, 0)));
        }
        return out;
    }

    /** The names it has given and those on what it holds: none of them is given again. */
    private static Set<String> kitNamesTaken(Entry entry, ServerPlayerEntity player) {
        var taken = new HashSet<>(entry.kitNamesGiven);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var name = inventory.getStack(slot).get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
            if (name != null) taken.add(name.getString());
        }
        return taken;
    }

    private static String pieceKey(dev.kindred.core.KitNames.Piece piece) {
        return piece.slot() + ":" + piece.item() + ":" + new java.util.TreeMap<>(piece.enchantments());
    }

    /**
     * Its best kit named (E-16d) once it has reached the enchanting stage, out of any match, with the levels to pay: a
     * name chosen for the first piece to name (the model's, asked once, else its own); then its anvil reached and the
     * piece named there. With no anvil, one it holds is put down beside it, or one is made of iron it can spare (three
     * blocks of it and four ingots) at a crafting table near it. Nothing when there is no anvil to be had: it fetches no
     * iron for a name.
     */
    private List<NativeTaskCandidate> kitNameCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        long now = server.getTicks();
        if (now < entry.kitNamesAt || !worldEditsEnabled() || matches.running(player.getUuid()).isPresent()) return null;
        if (!enchantingStage(entry, player)) {
            entry.kitNamesAt = now + 1200;
            return null;
        }
        var toName = dev.kindred.core.KitNames.affordable(dev.kindred.core.KitNames.toName(kitPieces(player)), player.experienceLevel);
        if (toName.isEmpty()) {
            entry.kitNamesAt = now + 1200;
            return null;
        }
        var piece = toName.getFirst();
        String key = pieceKey(piece);
        var chosen = entry.kitNames.get(key);
        if (chosen == null) {
            chooseKitName(entry, player, piece, key);
            chosen = entry.kitNames.get(key);
            // The model is asked: it thinks of a name a moment, and goes on when the answer comes.
            if (chosen == null) return entry.kitNameAsking ? List.of() : null;
        }
        var world = player.getServerWorld();
        UUID taskId = UUID.nameUUIDFromBytes(("kit_name:" + entry.data.id() + ":" + key).getBytes(StandardCharsets.UTF_8));
        entry.kitTaskId = taskId;
        var task = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 60.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        if (!anvilAt(world, entry.anvil)) entry.anvil = null;
        if (entry.anvil != null) {
            BlockPos anvil = entry.anvil;
            var at = Map.of("x", String.valueOf(anvil.getX()), "y", String.valueOf(anvil.getY()), "z", String.valueOf(anvil.getZ()));
            if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(anvil)) <= 4.5 * 4.5) {
                var args = new HashMap<>(at);
                args.put("slot", String.valueOf(piece.slot()));
                args.put("name", chosen.name());
                return List.of(new NativeTaskCandidate(task, "native_rename", Map.copyOf(args)));
            }
            // Named when it is by its anvil again, not a trip for it.
            if (player.getBlockPos().getSquaredDistance(anvil) > 48 * 48) return null;
            var args = new HashMap<>(at);
            args.put("range", "2.5");
            return List.of(new NativeTaskCandidate(task, "native_goto", Map.copyOf(args)));
        }
        var inventory = player.getInventory();
        int held = -1;
        for (int slot = 0; slot < 36 && held < 0; slot++) if (inventory.getStack(slot).isIn(net.minecraft.registry.tag.ItemTags.ANVIL)) held = slot;
        if (held >= 9) {
            int free = 8;
            for (int slot = 0; slot < 9; slot++) if (inventory.getStack(slot).isEmpty()) { free = slot; break; }
            return List.of(new NativeTaskCandidate(task, "native_swap", Map.of("source", String.valueOf(held), "target", String.valueOf(free))));
        }
        if (held >= 0) {
            BlockPos spot = anvilSpot(player, entry.setup);
            if (spot == null) {
                entry.kitNamesAt = now + 600;
                return null;
            }
            entry.anvil = spot.toImmutable(); // Its own once put down; forgotten if it is not there.
            return List.of(new NativeTaskCandidate(task, "native_place", Map.of("slot", String.valueOf(held), "x", String.valueOf(spot.getX()),
                    "y", String.valueOf(spot.getY()), "z", String.valueOf(spot.getZ()), "side", "up")));
        }
        int ingots = 0, blocks = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isOf(Items.IRON_INGOT)) ingots += stack.getCount();
            if (stack.isOf(Items.IRON_BLOCK)) blocks += stack.getCount();
        }
        int spare = blocks * 9 + ingots - lootWanted(entry).getOrDefault("minecraft:iron_ingot", 0);
        if (spare < 31) {
            entry.kitNamesAt = now + 2400;
            return null;
        }
        if (locateWorkstationBlock(world, BlockPos.ofFloored(player.getEyePos()), Blocks.CRAFTING_TABLE, 4.5) == null) {
            BlockPos table = locateWorkstationBlock(world, player.getBlockPos(), Blocks.CRAFTING_TABLE, 12);
            if (table == null) {
                entry.kitNamesAt = now + 2400;
                return null;
            }
            return List.of(new NativeTaskCandidate(task, "native_goto", Map.of("x", String.valueOf(table.getX()), "y", String.valueOf(table.getY()),
                    "z", String.valueOf(table.getZ()), "range", "2.5")));
        }
        String recipe = blocks < 3 ? "iron_block" : ingots < 4 ? "iron_ingot_from_iron_block" : "anvil";
        return List.of(new NativeTaskCandidate(task, "native_craft", Map.of("recipe", recipe)));
    }

    /**
     * Where to put its anvil down: on solid ground beside it, open, within reach, and allowed by its work area's rules,
     * or the server's roaming rules with none.
     */
    private BlockPos anvilSpot(ServerPlayerEntity player, WorkSetup setup) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (Direction side : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            for (BlockPos pos : List.of(feet.offset(side), feet.offset(side).offset(side.rotateYClockwise()))) {
                if (!world.isChunkLoaded(pos) || !world.isInBuildLimit(pos) || !world.getBlockState(pos).isReplaceable()
                        || !world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) continue;
                if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > 16.0) continue;
                if (NativeEdits.decide(player, setup, EditPolicy.Kind.PLACE, pos, "minecraft:anvil", edits).allowed()) return pos.toImmutable();
            }
        }
        return null;
    }

    /**
     * A name for {@code piece}: the model's, if one is set up, answers, and its name passes the naming rules; else its
     * own, in keeping with its temper.
     */
    private void chooseKitName(Entry entry, ServerPlayerEntity player, dev.kindred.core.KitNames.Piece piece, String key) {
        if (entry.kitNameAsking) return;
        var taken = kitNamesTaken(entry, player);
        var personality = entry.profile.personality();
        String noun = dev.kindred.core.KitNames.noun(piece);
        Runnable own = () -> dev.kindred.core.KitNames.own(piece, entry.data.id(), personality, taken).ifPresentOrElse(
                name -> kitNameChosen(entry, key, new KitName(name.text(), noun, "its own, in keeping with its temper", false)),
                () -> entry.kitNamesAt = server.getTicks() + 24_000);
        if (strategist.isEmpty()) {
            own.run();
            return;
        }
        var named = new HashMap<String, String>();
        for (var held : kitPieces(player)) held.name().ifPresent(name -> named.put(name, dev.kindred.core.KitNames.noun(held)));
        var question = new dev.kindred.model.Strategist.Question(dev.kindred.core.KitNames.SYSTEM,
                dev.kindred.core.KitNames.question(entry.data.displayName(), personality, piece, named), List.of(), dev.kindred.core.KitNames.MAX_NAME);
        entry.kitNameAsking = true;
        strategist.get().ask(entry.data.id(), entry.data.displayName(), question, server).whenComplete((answer, failure) -> {
            entry.kitNameAsking = false;
            var given = answer == null ? Optional.<dev.kindred.core.NameForge.Name>empty()
                    : answer.flatMap(dev.kindred.model.Strategist.Answer::text).flatMap(text -> dev.kindred.core.KitNames.fromModel(text, taken));
            if (given.isPresent()) {
                kitNameChosen(entry, key, new KitName(given.get().text(), noun, answer.get().reason(), true));
            } else {
                answer.flatMap(dev.kindred.model.Strategist.Answer::text).ifPresent(text -> KindredMod.LOGGER.info(
                        "{} strategist: \"{}\" breaks the naming rules or is a name it has used; its own instead", entry.data.displayName(), text));
                own.run();
            }
            entry.routineRefreshPending = true;
        });
    }

    private void kitNameChosen(Entry entry, String key, KitName chosen) {
        entry.kitNames.put(key, chosen);
        KindredMod.LOGGER.info("{} chose {} for its {} ({}) - {}", entry.data.displayName(), chosen.name(), chosen.noun(),
                chosen.byModel() ? "the model's name" : "its own name", chosen.why());
    }

    /** A rename done: said, remembered, and the next piece looked to; or not done, and its kit let be a while. */
    private void renameEnded(Entry entry, NativeRenameAction rename, SkillRunner.State state) {
        long now = server.getTicks();
        if (state != SkillRunner.State.COMPLETED) {
            if (state == SkillRunner.State.BLOCKED || state == SkillRunner.State.CANCELLED) entry.kitNamesAt = now + 1200;
            return;
        }
        String noun = "kit";
        for (var it = entry.kitNames.values().iterator(); it.hasNext(); ) {
            var chosen = it.next();
            if (!chosen.name().equals(rename.name())) continue;
            noun = chosen.noun();
            it.remove();
        }
        entry.kitNamesGiven.add(rename.name());
        entry.kitNamesAt = now + 20;
        String text = "I named my " + noun + " " + rename.name() + ".";
        say(entry, text, "");
        if (store != null) {
            store.addMemory(new MemoryNote(UUID.randomUUID(), entry.data.id(), MemoryNote.Kind.FACT,
                    "Named its " + noun + " " + rename.name() + " at an anvil", "kit_name", 0));
        }
    }

    /** The name it gave the piece now in {@code slot}, for fixtures: what the piece is called, or null. */
    String kitName(UUID id, int slot) {
        Entry entry = entries.get(id);
        if (entry == null || entry.nativeBody == null || !entry.nativeBody.connected()) return null;
        var name = entry.nativeBody.entity().getInventory().getStack(slot).get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
        return name == null ? null : name.getString();
    }

    /** A strategist given in place of the environment's, for fixtures (E-44, E-16d). */
    void strategistForFixtures(dev.kindred.model.Strategist given) {
        strategist.ifPresent(StrategistBridge::close);
        strategist = Optional.of(StrategistBridge.of(given, metadataDirectory));
    }

    /** What it last told its team of a find, for fixtures (E-40a). */
    String teamLine(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.teamLine;
    }

    /**
     * A new shared project: saved to the store, then known to the coordinator on the server thread, its every change
     * saved after it (E-11). Completes once the coordinator knows it.
     */
    java.util.concurrent.CompletableFuture<SharedProject> createSharedProject(SharedProject project) {
        requireThread();
        var known = new java.util.concurrent.CompletableFuture<SharedProject>();
        store.createProject(project).whenComplete((saved, failure) -> server.execute(() -> {
            if (failure != null) { known.completeExceptionally(failure); return; }
            storedProjects.add(saved.id());
            teamCoordinator.registerProject(saved);
            known.complete(saved);
        }));
        return known;
    }

    /** A shared project as the store holds it now. */
    java.util.concurrent.CompletableFuture<Optional<SharedProject>> savedSharedProject(ProjectId id) {
        return store.findProject(id);
    }

    /** How many of a shared task's output its player holds now; none for a task with no output or a player out of its body. */
    private static int produced(Entry entry, SharedTask task) {
        String item = task.arguments().get("produces_item");
        if (item == null || entry.nativeBody == null || !entry.nativeBody.connected()) return 0;
        var kind = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(item));
        return entry.nativeBody.entity().getInventory().count(kind);
    }

    /**
     * Saves a change to a shared project through the store's one ordered writer, as the coordinator made it: the
     * project, each task that changed, and each reservation they hold or held (E-11). A project the store does not hold
     * is left in memory.
     */
    private void saveTeamChange(SharedProject before, SharedProject after, List<StockReservation> reservations) {
        if (store == null || !storedProjects.contains(after.id())) return;
        java.util.function.BiConsumer<Object, Throwable> logged = (ignored, failure) -> {
            if (failure != null) KindredMod.LOGGER.warn("Shared project {} change not saved: {}", after.id(), failure.toString());
        };
        store.saveProject(after, before.revision()).whenComplete(logged);
        for (SharedTask task : after.tasks().values()) {
            SharedTask was = before.tasks().get(task.id());
            if (task.equals(was)) continue;
            store.saveTask(task, was == null ? 0 : was.revision()).whenComplete(logged);
        }
        for (StockReservation reservation : reservations) store.saveReservation(reservation).whenComplete(logged);
    }

    /** The AI player a body belongs to, either body; null for a person or anything else. */
    private UUID aiPlayerOf(net.minecraft.entity.Entity entity) {
        if (entity instanceof CompanionEntity companion) return companion.companionId();
        if (!(entity instanceof ServerPlayerEntity player)) return null;
        for (Entry entry : entries.values()) {
            if (entry.nativeBody != null && entry.nativeBody.entity() == player) return entry.data.id();
        }
        return null;
    }

    /**
     * What a player says of its own doing (a portal lit, a build done): to whoever may control it, with {@code where}
     * it is; to others only when it talks in public, within earshot, without the place, and never to an opponent in a
     * match it is in. Not the whole server: where its portal or its base is is its own to tell. The log keeps it all.
     */
    private void say(Entry entry, String text, String where) {
        entry.lastSaid = text;
        entry.said(text);
        String line = "<" + entry.data.displayName() + "> " + text;
        String placed = where.isEmpty() ? line : line + " (" + where + ")";
        KindredMod.LOGGER.info("{}", placed);
        var self = entry.nativeBody != null && entry.nativeBody.connected() ? entry.nativeBody.entity() : null;
        for (ServerPlayerEntity listener : server.getPlayerManager().getPlayerList()) {
            if (listener == self || aiPlayerOf(listener) != null) continue;
            boolean controls = listener.getUuid().equals(entry.data.ownerId()) || entry.nativeState != null
                    && entry.nativeState.access().mayControl(new Actor(listener.getUuid(), listener.hasPermissionLevel(2)));
            if (controls) {
                listener.sendMessage(Text.literal(placed), false);
            } else if (entry.profile.publicConversation() && self != null && listener.getWorld() == self.getWorld()
                    && listener.squaredDistanceTo(self) <= 64.0 * 64.0 && !matches.mayAttack(self.getUuid(), listener.getUuid())) {
                listener.sendMessage(Text.literal(line), false);
            }
        }
    }

    private void handlePeerConference(CompanionSocials.Participant speaker, CompanionSocials.Participant peer, PeerProgressDialogue.Exchange exchange) {
        if (store != null) {
            store.addMemory(new MemoryNote(UUID.randomUUID(), speaker.id(), MemoryNote.Kind.FACT,
                    exchange.memoryFactInitiator(), "peer_dialogue:" + peer.id(), 0));
            store.addMemory(new MemoryNote(UUID.randomUUID(), peer.id(), MemoryNote.Kind.FACT,
                    exchange.memoryFactPeer(), "peer_dialogue:" + speaker.id(), 0));
            store.appendEvent(speaker.id(), java.time.Instant.now(), "PEER_CONFERENCE",
                    "Conferred with " + peer.name() + " on " + exchange.topic());
            store.appendEvent(peer.id(), java.time.Instant.now(), "PEER_CONFERENCE",
                    "Conferred with " + speaker.name() + " on " + exchange.topic());
        }

        if (speaker.body() != null && speaker.body().getWorld() instanceof ServerWorld serverWorld) {
            String quote = exchange.openingRemark();
            if (quote.length() > 60) quote = quote.substring(0, 57) + "...";
            Text whisper = Text.literal("§7[Nearby] " + speaker.name() + " confers with " + peer.name() + ": \"" + quote + "\"");
            for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                if (player.squaredDistanceTo(speaker.body()) <= 36.0 || (peer.body() != null && player.squaredDistanceTo(peer.body()) <= 36.0)) {
                    player.sendMessage(whisper, false);
                }
            }
        }
    }

    private PeerProgressDialogue.PeerState peerState(UUID id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.body == null || !entry.body.isAlive()) return null;
        int logs = countLogs(entry.body);
        boolean hasStorage = entry.setup != null && entry.setup.storage() != null;
        boolean hasPatch = entry.setup != null && entry.setup.patch() != null;
        OperatingMode.Mode mode = entry.mode != null ? entry.mode.mode() : OperatingMode.Mode.COMPANION;
        return new PeerProgressDialogue.PeerState(
                entry.data.id(),
                entry.data.displayName(),
                mode,
                entry.profile.personality(),
                entry.skillId,
                logs,
                hasStorage,
                hasPatch
        );
    }

    private int countLogs(CompanionEntity body) {
        if (body == null) return 0;
        int count = 0;
        for (int i = 0; i < body.inventory().size(); i++) {
            net.minecraft.item.ItemStack stack = body.inventory().getStack(i);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().contains("log")) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void mapAndSpawn(Entry entry, UUID owner, SpawnLocation location) {
        // A null durable mapping proves no initial body has been dispatched; mapped absence is ambiguous.
        entry.data = entry.data.withEntityId(entry.data.id());
        long generation = ++entry.generation;
        persist(entry).whenComplete((mappedRecord, failure) -> server.execute(() -> {
            creating.remove(owner);
            if (!running || generation != entry.generation) return;
            if (failure != null) {
                entry.metadataFault = true;
                entry.blocker = "Body mapping could not be saved; operator inspection required.";
                reply(owner, entry.explanation());
                return;
            }
            spawnInitial(entry, owner, location);
        }));
    }

    private void spawnInitial(Entry entry, UUID ownerId, SpawnLocation location) {
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerId);
        if (owner == null || !owner.getWorld().getRegistryKey().getValue().toString().equals(entry.data.dimension())) {
            entry.blocker = "Initial spawn deferred after owner departure; mapped body needs operator reconciliation.";
            reply(ownerId, entry.blocker);
            return;
        }
        ServerWorld world = owner.getServerWorld();
        for (ServerWorld candidate : server.getWorlds()) {
            Entity existing = candidate.getEntity(entry.data.id());
            if (existing != null) {
                entry.blocker = "An entity already has this UUID; operator reconciliation required.";
                return;
            }
        }
        CompanionEntity body = KindredMod.COMPANION.create(world);
        if (body == null) { entry.blocker = "Native entity creation failed."; return; }
        body.setUuid(entry.data.id());
        body.bind(entry.data.id(), ownerId);
        body.refreshPositionAndAngles(location.x(), location.y(), location.z(), location.yaw(), 0);
        body.setCustomName(Text.literal(entry.data.displayName()));
        body.setCustomNameVisible(true);
        if (!eligibleSpawn(world, body) || body.squaredDistanceTo(owner) > 48 * 48) {
            entry.blocker = "Initial spawn position is obstructed; operator reconciliation required.";
            reply(ownerId, entry.blocker);
            return;
        }
        if (!world.spawnEntity(body)) {
            entry.blocker = "Native spawn was rejected; mapped identity retained for reconciliation.";
            reply(ownerId, entry.blocker);
            return;
        }
        if (entry.body == null) loaded(body);
        reply(ownerId, entry.data.displayName() + " is here, sleeping. ID: " + entry.data.id()
                + ". Try /companion follow, /companion stay, or /companion stop.");
    }

    private void halt(Entry entry, Actor actor, String reason) {
        ++entry.generation;
        entry.pendingConnection = null;
        entry.nativePending = false;
        if (entry.arbiter != null) {
            entry.arbiter.stop(actor);
            // Synchronous: the runner marks itself cancelled now, so no late tick or callback can drive the body.
            if (entry.runner != null) entry.runner.abort(reason);
            entry.data = entry.arbiter.snapshot();
        } else {
            Authorization.requireControl(actor, entry.data);
            entry.data = entry.data.withRevisions(Math.incrementExact(entry.data.intentRevision()),
                    entry.data.taskRevision()).withStopLatched(true).withLifecycle(LifecycleState.PAUSED);
        }
        if (entry.body != null) {
            entry.body.haltControls();
            entry.body.ownerStopped(true);
        }
        if (entry.nativeBody != null) entry.nativeBody.stopInputs();
        if (entry.activity != null) entry.activity.stop();
        closeNativeAction(entry);
        entry.following = false;
        entry.preparations.clear();
        if (entry.lease != null) claims.releaseTask(entry.lease.taskId());
        admission.release(entry.data.id());
        teamCoordinator.handlePlayerStop(entry.data.id());
        entry.currentTaskId = null;
        entry.glanceTarget = null; // Nothing is in progress to pause; a stop callback must not restart gaze.
        socials.reset(entry.data.id());
        entry.lease = null;
        entry.blocker = reason;
        if (store != null) persist(entry);
    }

    private CompletableFuture<StoredCompanion> persist(Entry entry) {
        Companion snapshot = entry.snapshot();
        entry.data = snapshot;
        SqliteCompanionStore database = store;
        // Serialize this companion's optimistic writes; a failed chain never silently overwrites a newer record.
        entry.writes = entry.writes.thenCompose(previous -> database.save(snapshot, previous.revision()));
        entry.writes.whenComplete((saved, failure) -> {
            if (failure != null) server.execute(() -> {
                if (!running) return;
                entry.metadataFault = true;
                entry.blocker = "Metadata write failed; companion stopped pending operator inspection.";
                if (entry.arbiter != null && !entry.snapshot().stopLatched()) entry.arbiter.stop(Actor.console());
                entry.following = false;
                if (entry.body != null) { entry.body.haltControls(); entry.body.ownerStopped(true); }
                KindredMod.LOGGER.error("Companion {} metadata write failed", snapshot.id(), failure);
            });
        });
        return entry.writes;
    }

    void stop() {
        requireThread();
        ready = false;
        // A race ends with the server: each AI runner's own pace back before anything is saved (M-03a).
        for (var raced : racePace.entrySet()) {
            Entry entry = entries.get(raced.getKey());
            if (entry == null) continue;
            entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(raced.getValue());
            if (entry.goal == raceGoal.get(raced.getKey())) entry.goal = dev.kindred.core.survival.SurvivalGoal.NONE;
        }
        racePace.clear();
        raceGoal.clear();
        // A build battle's creative mode goes with it: blocks cleared, game modes back, before anything is saved.
        for (var builder : new ArrayList<>(modeBefore.entrySet())) {
            var player = server.getPlayerManager().getPlayer(builder.getKey());
            if (player == null) continue;
            Entry builderEntry = entryForPlayer(builder.getKey());
            if (builderEntry != null && builderEntry.nativeBody != null) builderEntry.nativeBody.allowCreative(false);
            player.getInventory().clear();
            player.changeGameMode(builder.getValue());
        }
        modeBefore.clear();
        // A hunt's compasses go with the hunt (D6).
        for (var match : matches.list()) {
            if (match.mode() != dev.kindred.core.match.MatchRegistry.Mode.MANHUNT) continue;
            for (UUID invited : match.invited()) {
                var player = server.getPlayerManager().getPlayer(invited);
                if (player != null) HuntCompass.takeBack(player, match.id());
            }
        }
        for (Entry entry : entries.values()) {
            entry.pendingConnection = null;
            entry.nativePending = false;
            if (entry.arbiter != null) halt(entry, Actor.console(), "Server stopped; manual resume required.");
            if (entry.nativeBody != null) entry.nativeBody.disconnect();
            closeNativeActivity(entry);
            closeNativeAction(entry);
            ++entry.generation;
        }
        running = false;
        matches.stopAll();
        presenter.clear();
        strategist.ifPresent(StrategistBridge::close);
        memories.close();
        CompletableFuture<Void> writes = CompletableFuture.allOf(entries.values().stream()
                .flatMap(e -> java.util.stream.Stream.of(e.writes, e.profileWrite, e.mannerismWrite, e.setupWrite, e.activityFlush, e.nativeActionFlush))
                .toArray(CompletableFuture[]::new)).handle((ignored, failure) -> null);
        shutdown = writes.thenCompose(ignored -> startup.handle((loaded, failure) ->
                loaded == null ? CompletableFuture.<Void>completedFuture(null) : loaded.store().closeAsync())
                .thenCompose(future -> future));
    }

    void finishShutdown() {
        // There are no game ticks after SERVER_STOPPED; bound the final IO flush.
        try { shutdown.get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { KindredMod.LOGGER.error("Kindred shutdown flush incomplete; reconcile on startup", failure); }
    }

    LedgerCheckState ledgerStateForTest(UUID id) {
        Entry entry = entries.get(id);
        return entry != null ? entry.ledgerState : null;
    }

    ConnectionToken pendingConnectionForTest(UUID id) {
        Entry entry = entries.get(id);
        return entry != null ? entry.pendingConnection : null;
    }

    private boolean eligibleFollow(CompanionEntity body, ServerPlayerEntity owner) {
        return owner != null && owner.isAlive() && !owner.isSpectator() && body.isAlive()
                && body.getWorld() == owner.getWorld() && body.squaredDistanceTo(owner) <= 48 * 48
                && body.getWorld().isChunkLoaded(body.getBlockPos())
                && owner.getWorld().isChunkLoaded(owner.getBlockPos());
    }

    private SpawnLocation findSpawn(ServerPlayerEntity owner) {
        ServerWorld world = owner.getServerWorld();
        CompanionEntity probe = KindredMod.COMPANION.create(world);
        if (probe == null) return null;
        for (int[] offset : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            double x = owner.getBlockX() + offset[0] + 0.5;
            double z = owner.getBlockZ() + offset[1] + 0.5;
            probe.refreshPositionAndAngles(x, owner.getY(), z, owner.getYaw(), 0);
            if (eligibleSpawn(world, probe)) return new SpawnLocation(x, owner.getY(), z, owner.getYaw());
        }
        return null;
    }

    private boolean eligibleSpawn(ServerWorld world, CompanionEntity body) {
        BlockPos position = body.getBlockPos();
        return world.isChunkLoaded(position) && world.isSpaceEmpty(body)
                && world.getWorldBorder().contains(body.getBoundingBox())
                && world.getFluidState(position).isEmpty()
                && world.getBlockState(position.down()).isSolidBlock(world, position.down());
    }

    private Entry byOwner(UUID owner) {
        if (owner == null) return null;
        return entries.values().stream().filter(entry -> entry.data.ownerId().equals(owner)).findFirst().orElse(null);
    }

    private void reply(UUID owner, String message) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(owner);
        if (player != null) player.sendMessage(Text.literal(message), false);
    }

    private static int reject(ServerCommandSource source, String message) {
        source.sendError(Text.literal(message));
        return 0;
    }

    private static final Pattern UUID_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    public static Text formatMessageWithClickableUuids(String message) {
        if (message == null) return Text.empty();
        Matcher matcher = UUID_PATTERN.matcher(message);
        if (!matcher.find()) {
            return Text.literal(message);
        }
        MutableText root = Text.empty();
        int lastIndex = 0;
        matcher.reset();
        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                root.append(Text.literal(message.substring(lastIndex, matcher.start())));
            }
            String uuidStr = matcher.group();
            root.append(Text.literal(uuidStr).styled(style -> style
                    .withColor(Formatting.AQUA)
                    .withUnderline(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuidStr))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy UUID (" + uuidStr + ") to clipboard")))));
            lastIndex = matcher.end();
        }
        if (lastIndex < message.length()) {
            root.append(Text.literal(message.substring(lastIndex)));
        }
        return root;
    }

    private static void feedback(ServerCommandSource source, String message) {
        source.sendFeedback(() -> formatMessageWithClickableUuids(message), false);
    }

    private static void feedback(ServerCommandSource source, Text message) {
        source.sendFeedback(() -> message, false);
    }

    private void requireThread() {
        if (!server.isOnThread()) throw new IllegalStateException("Kindred world access requires the server thread");
    }

    private static UUID loadWorldId(Path directory) {
        try {
            Files.createDirectories(directory);
            Path identity = directory.resolve("world.uuid");
            if (Files.exists(identity)) return UUID.fromString(Files.readString(identity).trim());
            UUID id = UUID.randomUUID();
            Files.writeString(identity, id.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return id;
        } catch (IOException failure) { throw new CompletionException(failure); }
    }

    private record Bootstrap(UUID worldId, EditPolicy.ServerPolicy edits, boolean dragonFight, boolean bases) { }
    private record LoadedRegistry(UUID worldId, EditPolicy.ServerPolicy edits, boolean dragonFight, boolean bases, SqliteCompanionStore store, java.util.List<StoredCompanion> records,
                                  java.util.List<SocialProfile> profiles, java.util.List<Mannerisms> mannerisms,
                                  java.util.List<WorkSetup> setups, java.util.List<StoredNativePlayer> nativePlayers,
                                  StartupLedgerSnapshot ledgerSnapshot,
                                  Map<UUID, OperatingMode> modes) { }
    private record SpawnLocation(double x, double y, double z, float yaw) { }

    /** Test seam: keep autonomous selection from replacing the state a fixture is inspecting. */
    void holdAutonomyForTest(UUID id, int ticks) {
        Entry entry = entries.get(id);
        if (entry != null) entry.manualHoldUntilTick = server.getTicks() + ticks;
    }

    void setEditsForTest(EditPolicy.ServerPolicy policy) {
        this.edits = policy;
    }

    void setDragonFightForTest(boolean allowed) {
        this.dragonFight = allowed;
    }

    ActionLease leaseForTest(UUID id) {
        Entry entry = entries.get(id);
        return entry != null ? entry.lease : null;
    }

    boolean isLeaseCurrentForTest(UUID id, ActionLease lease) {
        Entry entry = entries.get(id);
        return entry != null && entry.arbiter != null && entry.arbiter.isCurrent(lease);
    }

    AttentionSnapshot nativeAttentionStatus(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.attentionSnapshot;
    }

    SchedulerDiagnostics schedulerDiagnostics() {
        return attentionScheduler.diagnostics();
    }

    public dev.kindred.core.tactics.TacticalDecision nativeTacticalDiagnostics(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.lastTacticalDecision;
    }

    public dev.kindred.core.tactics.TacticalObservation nativeTacticalObservation(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.lastTacticalObservation;
    }

    public ProgressionState nativeProgressionState(UUID id) {
        Entry entry = entries.get(id);
        if (entry == null) return null;
        if (entry.nativeBody != null && entry.nativeBody.connected()) {
            var progObs = observeProgression(entry, entry.nativeBody);
            entry.lastProgressionObservation = progObs;
            entry.lastProgressionState = entry.progressionGraph.evaluate(progObs, entry.progressionHistory);
            for (var snapEntry : entry.lastProgressionState.snapshots().entrySet()) {
                if (snapEntry.getValue().historicallyAchieved() && !entry.progressionHistory.containsKey(snapEntry.getKey())) {
                    entry.progressionHistory.put(snapEntry.getKey(), (long) server.getTicks());
                }
            }
        }
        return entry.lastProgressionState;
    }

    public Map<MilestoneId, Long> nativeProgressionHistory(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? Map.of() : java.util.Collections.unmodifiableMap(entry.progressionHistory);
    }

    public void setFoodTaskAuthorizedForTest(UUID id, boolean authorized) {
        Entry entry = entries.get(id);
        if (entry != null) entry.foodTaskAuthorized = authorized;
    }

    public void setShelterProtectionAuthorizedForTest(UUID id, boolean authorized) {
        Entry entry = entries.get(id);
        if (entry != null) entry.shelterProtectionAuthorized = authorized;
    }

    int evaluateCandidatesCountForTest(UUID id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.nativeBody == null) return 0;
        return evaluateNativeCandidates(entry, entry.nativeBody).size();
    }

    /**
     * The duel a player in a running match fights (M-00a): its opponent, with the weapon that deals the most over the
     * fight, or a walk toward it when it is far. Null when the player is in no running match.
     */
    private List<NativeTaskCandidate> duelCandidates(NativePlayerBody body) {
        var player = body.entity();
        var match = matches.running(player.getUuid()).filter(m -> m.mode() == dev.kindred.core.match.MatchRegistry.Mode.DUEL).orElse(null);
        if (match == null) return null;
        var candidates = new java.util.ArrayList<NativeTaskCandidate>();
        UUID opponentId = dev.kindred.core.match.MatchRegistry.other(match, player.getUuid());
        var opponent = opponentId == null ? null : server.getPlayerManager().getPlayer(opponentId);
        if (opponent == null || !opponent.isAlive() || opponent.getServerWorld() != player.getServerWorld()) return candidates;
        UUID taskId = UUID.nameUUIDFromBytes(("duel:" + match.id() + ":" + player.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 99.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        if (player.squaredDistanceTo(opponent) > 20 * 20) {
            BlockPos at = opponent.getBlockPos();
            candidates.add(new NativeTaskCandidate(candidate, "native_goto", Map.of("x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()),
                    "z", String.valueOf(at.getZ()))));
            return candidates;
        }
        int slot = Weapons.choose(player, NativePlayerBody.believedHealth(player, opponent), true).map(Weapons.Held::slot).orElse(-1);
        candidates.add(new NativeTaskCandidate(candidate, "native_fight", Map.of("target", opponentId.toString(), "slot", String.valueOf(slot))));
        return candidates;
    }

    /** A match's player by name or id: an AI player (its body's player id) or a human online. */
    UUID matchPlayerId(String raw) {
        requireThread();
        UUID bot = resolveBotId(raw);
        if (bot != null) {
            Entry entry = entries.get(bot);
            return entry != null && entry.nativeBody != null && entry.nativeBody.entity() != null ? entry.nativeBody.entity().getUuid() : null;
        }
        var human = server.getPlayerManager().getPlayer(raw);
        return human != null ? human.getUuid() : null;
    }

    /** The entry whose body is player {@code playerId}, if it is a native player here. */
    private Entry entryForPlayer(UUID playerId) {
        for (Entry entry : entries.values()) {
            if (entry.nativeBody != null && entry.nativeBody.entity() != null && entry.nativeBody.entity().getUuid().equals(playerId)) return entry;
        }
        return null;
    }

    private String playerName(UUID playerId) {
        var player = server.getPlayerManager().getPlayer(playerId);
        return player != null ? player.getName().getString() : playerId.toString().substring(0, 8);
    }

    /** Tells every invited player where its match stands. */
    private void announce(dev.kindred.core.match.MatchRegistry.Match match) {
        boolean race = match.mode() == dev.kindred.core.match.MatchRegistry.Mode.SPEEDRUN;
        String what = switch (match.state()) {
            case LOBBY -> "waiting for every player to join";
            case COUNTDOWN -> "starting in three seconds";
            case RUNNING -> switch (match.mode()) {
                case SPEEDRUN -> "go!";
                case MANHUNT -> "the hunt is on!";
                case BUILDER -> "build!";
                case DUEL -> "fight!";
            };
            case VOTING -> "time is up; vote on each build, one to five (/aiplayer match vote <player> <score>)";
            case ENDED -> match.winner() != null ? playerName(match.winner()) + " wins (" + match.result() + ")" : match.result();
        };
        String line = match.mode().label() + " " + who(match) + ": " + what;
        KindredMod.LOGGER.info(line);
        presenter.changed(match, this::playerName);
        boolean hunt = match.mode() == dev.kindred.core.match.MatchRegistry.Mode.MANHUNT;
        if ((race || hunt) && match.state() == dev.kindred.core.match.MatchRegistry.State.RUNNING && !races.containsKey(match.id())
                && !hunts.contains(match.id())) startRace(match);
        if (race || hunt) leaveRace(match);
        takeBackCompasses(match);
        if (match.mode() == dev.kindred.core.match.MatchRegistry.Mode.BUILDER) {
            if (match.state() == dev.kindred.core.match.MatchRegistry.State.RUNNING && buildsBegun.add(match.id())) startBuilding(match);
            if (match.state() == dev.kindred.core.match.MatchRegistry.State.VOTING) startVoting(match);
            leaveBuilding(match);
        }
        for (UUID invited : match.invited()) {
            var player = server.getPlayerManager().getPlayer(invited);
            if (player != null) player.sendMessage(Text.literal(line), false);
            // An AI player in it chooses again at once: the fight begins, or what it was doing goes on.
            Entry entry = entryForPlayer(invited);
            if (entry != null) {
                entry.cachedRoutineCandidates = List.of();
                entry.routineRefreshPending = true;
                // The fight is what the administrator entered it for: a hold left by an earlier command gives way.
                if (match.state() == dev.kindred.core.match.MatchRegistry.State.RUNNING) entry.manualHoldUntilTick = Long.MIN_VALUE;
            }
        }
    }

    /**
     * A duel proposed between two players (M-00a). An AI player is entered by whoever administers it; a human must join
     * it itself (/aiplayer match join), so no human is ever entered into a fight by anyone else (D7).
     */
    int proposeDuel(ServerCommandSource source, UUID first, UUID second) {
        requireThread();
        // The server's own rule comes first: with PvP off, no player can hurt another, in a match or not.
        if (!server.isPvpEnabled()) return reject(source, "PvP is off on this server (pvp=false in server.properties); no duel can be fought.");
        var invited = List.of(first, second);
        for (UUID player : invited) {
            Entry entry = entryForPlayer(player);
            if (entry == null && server.getPlayerManager().getPlayer(player) == null) return reject(source, "No such player online.");
            if (entry != null && !nativeMayControl(source, entry)) return reject(source, "Administrative access to " + entry.data.displayName() + " denied.");
        }
        var result = matches.propose(dev.kindred.core.match.MatchRegistry.Mode.DUEL, invited);
        if (!result.accepted()) return reject(source, result.reason());
        var match = enter(result.match().orElseThrow());
        feedback(source, match.mode().label() + " " + dev.kindred.core.match.MatchRegistry.shortId(match.id()) + " proposed: " + who(match)
                + "; " + match.joined().size() + " of " + match.invited().size() + " joined.");
        return 1;
    }

    /**
     * The AI players invited to a new match entered by their administrator, who proposed it; each human invited told how
     * to join. Joining is the human's own choice: a click runs the join as that human, and nothing else enters it (D7).
     */
    private dev.kindred.core.match.MatchRegistry.Match enter(dev.kindred.core.match.MatchRegistry.Match match) {
        for (UUID player : match.invited()) {
            if (entryForPlayer(player) != null) {
                match = matches.join(match.id(), player).match().orElse(match);
                continue;
            }
            var human = server.getPlayerManager().getPlayer(player);
            if (human == null) continue;
            String others = match.invited().stream().filter(other -> !other.equals(player)).map(this::playerName)
                    .collect(java.util.stream.Collectors.joining(", "));
            String what = switch (match.mode()) {
                case DUEL -> "a duel against " + others;
                case SPEEDRUN -> "a speedrun race" + (others.isEmpty() ? "" : " against " + others);
                case MANHUNT -> "a manhunt, as " + (match.runners().contains(player) ? "a runner" : "a hunter") + " (" + who(match)
                        + "); once you join, the other side may attack you";
                case BUILDER -> "a build battle against " + others + "; you build in creative on your own plot, then everyone votes";
            };
            String join = "/aiplayer match join " + dev.kindred.core.match.MatchRegistry.shortId(match.id());
            human.sendMessage(Text.literal("You are invited to " + what + ". To play in it: ").append(Text.literal("[Join]")
                    .styled(style -> style.withColor(net.minecraft.util.Formatting.GREEN)
                            .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, join))
                            .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal(join))))), false);
        }
        return match;
    }

    /** A match's players as its announcements name them. */
    private String who(dev.kindred.core.match.MatchRegistry.Match match) {
        if (match.mode() == dev.kindred.core.match.MatchRegistry.Mode.MANHUNT) {
            return match.invited().stream().filter(match.runners()::contains).map(this::playerName).collect(java.util.stream.Collectors.joining(", "))
                    + " hunted by " + match.invited().stream().filter(p -> !match.runners().contains(p)).map(this::playerName)
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        return match.invited().stream().map(this::playerName).collect(java.util.stream.Collectors.joining(
                match.mode() == dev.kindred.core.match.MatchRegistry.Mode.DUEL ? " against " : ", "));
    }

    /**
     * A speedrun race proposed for one to eight runners (M-03a): from a fresh start to the dragon, timed by the game's
     * ticks, with splits at vanilla's own advancements. Every runner starts with nothing: an empty inventory and none
     * of the splits' advancements, the same rule for humans and AIs. An AI runner is entered by its administrator and
     * needs the server's leave for AI world edits and the dragon fight; a human joins for itself.
     */
    int proposeSpeedrun(ServerCommandSource source, List<UUID> runners) {
        requireThread();
        for (UUID runner : runners) {
            Entry entry = entryForPlayer(runner);
            var player = server.getPlayerManager().getPlayer(runner);
            if (player == null) return reject(source, "No such player online.");
            if (entry != null) {
                if (!nativeMayControl(source, entry)) return reject(source, "Administrative access to " + entry.data.displayName() + " denied.");
                if (!worldEditsEnabled()) return reject(source, "AI world edits are disabled on this server; an AI runner needs them (/aiplayer edits on).");
                if (!dragonFight) return reject(source, "The dragon fight is off on this server; an AI runner needs it (/aiplayer dragonfight on).");
            }
            if (!player.getInventory().isEmpty()) return reject(source, playerName(runner) + " is not at a fresh start: it carries items.");
            for (var split : dev.kindred.core.match.SpeedrunSplits.ANY_PERCENT) {
                if (reached(player, split)) return reject(source, playerName(runner) + " is not at a fresh start: it has already reached " + split.label() + ".");
            }
        }
        var result = matches.propose(dev.kindred.core.match.MatchRegistry.Mode.SPEEDRUN, runners);
        if (!result.accepted()) return reject(source, result.reason());
        var match = enter(result.match().orElseThrow());
        feedback(source, "Speedrun " + dev.kindred.core.match.MatchRegistry.shortId(match.id()) + " proposed: " + who(match)
                + "; " + match.joined().size() + " of " + match.invited().size() + " joined.");
        return 1;
    }

    /**
     * A manhunt proposed (M-02a): runners make for the dragon while hunters hunt them, two to eight players in all.
     * Everyone starts with nothing, as in a race. An AI player in it is entered by its administrator and needs the
     * server's leave for AI world edits; an AI runner, the dragon fight's too. A human joins for itself, and only then is
     * it fair game (D7).
     */
    int proposeManhunt(ServerCommandSource source, List<UUID> runners, List<UUID> hunters) {
        requireThread();
        if (!server.isPvpEnabled()) return reject(source, "PvP is off on this server (pvp=false in server.properties); no manhunt can be played.");
        var everyone = new ArrayList<UUID>(runners);
        everyone.addAll(hunters);
        for (UUID player : everyone) {
            Entry entry = entryForPlayer(player);
            var online = server.getPlayerManager().getPlayer(player);
            if (online == null) return reject(source, "No such player online.");
            if (entry != null) {
                if (!nativeMayControl(source, entry)) return reject(source, "Administrative access to " + entry.data.displayName() + " denied.");
                if (!worldEditsEnabled()) return reject(source, "AI world edits are disabled on this server; an AI player in a manhunt needs them (/aiplayer edits on).");
                if (runners.contains(player) && !dragonFight) return reject(source, "The dragon fight is off on this server; an AI runner needs it (/aiplayer dragonfight on).");
            }
            if (!online.getInventory().isEmpty()) return reject(source, playerName(player) + " is not at a fresh start: it carries items.");
        }
        var result = matches.proposeManhunt(runners, hunters);
        if (!result.accepted()) return reject(source, result.reason());
        var match = enter(result.match().orElseThrow());
        feedback(source, "Manhunt " + dev.kindred.core.match.MatchRegistry.shortId(match.id()) + " proposed: " + who(match)
                + "; " + match.joined().size() + " of " + match.invited().size() + " joined.");
        return 1;
    }

    /**
     * A build battle proposed (M-04a): one plot each, laid out in a row from where the operator stands, for two to eight
     * builders. Each arrives with nothing and leaves with nothing: building is in creative, the only mode where blocks
     * are free, and whatever creative gave is cleared at the end. An AI builder needs the server's leave for AI world
     * edits. Operators only, as the plots are laid on the ground: open ground, level and clear sixteen blocks up.
     */
    int proposeBuildBattle(ServerCommandSource source, List<UUID> builders, int buildTicks, dev.kindred.core.match.BuildBattle.Theme theme) {
        requireThread();
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator lays out a build battle's plots.");
        for (UUID builder : builders) {
            Entry entry = entryForPlayer(builder);
            var online = server.getPlayerManager().getPlayer(builder);
            if (online == null) return reject(source, "No such player online.");
            if (entry != null) {
                if (!nativeMayControl(source, entry)) return reject(source, "Administrative access to " + entry.data.displayName() + " denied.");
                if (!worldEditsEnabled()) return reject(source, "AI world edits are disabled on this server; an AI builder needs them (/aiplayer edits on).");
            }
            if (!online.getInventory().isEmpty()) return reject(source, playerName(builder) + " must arrive with nothing: it carries items.");
        }
        var world = source.getWorld();
        BlockPos feet = BlockPos.ofFloored(source.getPosition());
        var start = new dev.kindred.core.construction.Pos(feet.getX() + 2, feet.getY() - 1, feet.getZ() + 2);
        var plots = dev.kindred.core.match.BuildBattle.layout(builders, start);
        for (var plot : plots) {
            var o = plot.origin();
            for (int x = 0; x < dev.kindred.core.match.BuildBattle.PLOT; x++) for (int z = 0; z < dev.kindred.core.match.BuildBattle.PLOT; z++) {
                BlockPos floor = new BlockPos(o.x() + x, o.y(), o.z() + z);
                if (!world.getBlockState(floor).isSideSolidFullSquare(world, floor, net.minecraft.util.math.Direction.UP)) {
                    return reject(source, "The plots need level ground: " + floor.toShortString() + " is not solid. Stand on open, level ground.");
                }
                for (int y = 1; y <= 16; y++) {
                    var state = world.getBlockState(floor.up(y));
                    if (!state.isAir() && !state.isReplaceable()) return reject(source, "The plots need open ground: " + floor.up(y).toShortString() + " is in the way.");
                }
            }
        }
        var result = matches.proposeBuild(builders, buildTicks);
        if (!result.accepted()) return reject(source, result.reason());
        // The plots, marked out: white concrete, a grey edge, plants cleared off them.
        for (var plot : plots) {
            var o = plot.origin();
            for (int x = 0; x < dev.kindred.core.match.BuildBattle.PLOT; x++) for (int z = 0; z < dev.kindred.core.match.BuildBattle.PLOT; z++) {
                boolean edge = x == 0 || z == 0 || x == dev.kindred.core.match.BuildBattle.PLOT - 1 || z == dev.kindred.core.match.BuildBattle.PLOT - 1;
                BlockPos floor = new BlockPos(o.x() + x, o.y(), o.z() + z);
                world.setBlockState(floor, (edge ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.WHITE_CONCRETE).getDefaultState());
                for (int y = 1; y <= 16; y++) if (!world.getBlockState(floor.up(y)).isAir()) world.setBlockState(floor.up(y), Blocks.AIR.getDefaultState());
            }
        }
        var match = enter(result.match().orElseThrow());
        battles.put(match.id(), new dev.kindred.core.match.BuildBattle(theme != null ? theme : dev.kindred.core.match.BuildBattle.pick(new java.util.Random()), plots));
        feedback(source, "Build battle " + dev.kindred.core.match.MatchRegistry.shortId(match.id()) + " proposed: " + who(match) + ", "
                + buildTicks / 20 + " seconds to build; " + match.joined().size() + " of " + match.invited().size() + " joined.");
        return 1;
    }

    /**
     * The build begins: the theme announced, every builder in creative on its plot, and each AI builder at work on its
     * design for the theme, stocked with what the design takes (creative's free blocks, in this mode only).
     */
    private void startBuilding(dev.kindred.core.match.MatchRegistry.Match match) {
        var battle = battles.get(match.id());
        if (battle == null) return;
        int seconds = matches.limit(match.id()) / 20;
        for (UUID builder : match.joined()) {
            var player = server.getPlayerManager().getPlayer(builder);
            if (player == null) continue;
            modeBefore.putIfAbsent(builder, player.interactionManager.getGameMode());
            Entry builderEntry = entryForPlayer(builder);
            if (builderEntry != null && builderEntry.nativeBody != null) builderEntry.nativeBody.allowCreative(true);
            player.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
            presenter.show(builder, new dev.kindred.core.match.MatchPresentation.Title(battle.theme().title(), "Build it on your plot in "
                    + seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60), dev.kindred.core.match.MatchPresentation.Cue.START));
            Entry entry = entryForPlayer(builder);
            var plot = battle.plot(builder).orElse(null);
            if (entry == null || plot == null) continue;
            var design = dev.kindred.core.match.BuildBattle.design(battle.theme(), new java.util.Random(match.id().getLeastSignificantBits() ^ builder.getMostSignificantBits()));
            var site = plot.site(design);
            stockFor(player, design);
            startBuild(server.getCommandSource(), entry.data.id(), design, new BlockPos(site.x(), site.y(), site.z()), 0);
            KindredMod.LOGGER.info("Build battle {}: {} builds {} at {}", dev.kindred.core.match.MatchRegistry.shortId(match.id()), playerName(builder),
                    design.name(), site);
        }
    }

    /** An AI builder's inventory filled with what its design takes, and scaffolding: free in creative, in Builder only. */
    private static void stockFor(ServerPlayerEntity player, dev.kindred.core.construction.Blueprint design) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (var b : design.blocks()) counts.merge(dev.kindred.core.construction.Placement.item(b.spec()), 1, Integer::sum);
        counts.merge("minecraft:dirt", 64, Integer::sum);
        var inventory = player.getInventory();
        int slot = 0;
        for (var e : counts.entrySet()) {
            var item = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(e.getKey()));
            for (int left = e.getValue(); left > 0 && slot < 36; left -= 64) inventory.setStack(slot++, new ItemStack(item, Math.min(64, left)));
        }
    }

    /** The blocks standing on a plot, over its floor, by kind: what a voter sees of a build. */
    private Map<dev.kindred.core.construction.Pos, String> plotBlocks(net.minecraft.server.world.ServerWorld world, dev.kindred.core.match.BuildBattle.Plot plot) {
        var out = new HashMap<dev.kindred.core.construction.Pos, String>();
        var o = plot.origin();
        for (int x = 0; x < dev.kindred.core.match.BuildBattle.PLOT; x++) for (int z = 0; z < dev.kindred.core.match.BuildBattle.PLOT; z++) for (int y = 1; y <= 24; y++) {
            var state = world.getBlockState(new BlockPos(o.x() + x, o.y() + y, o.z() + z));
            if (!state.isAir()) out.put(new dev.kindred.core.construction.Pos(x, y, z), net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString());
        }
        return out;
    }

    /**
     * The build's time is up: building stops, every builder's creative blocks are cleared and it looks on in adventure
     * mode while each votes. An AI builder votes at once, as the judge scores what stands on each other plot; a human is
     * offered the votes to click.
     */
    private void startVoting(dev.kindred.core.match.MatchRegistry.Match match) {
        var battle = battles.get(match.id());
        if (battle == null) return;
        for (UUID builder : match.joined()) {
            var player = server.getPlayerManager().getPlayer(builder);
            if (player == null) continue;
            Entry entry = entryForPlayer(builder);
            if (entry != null && entry.build != null) startBuild(server.getCommandSource(), entry.data.id(), null, null, 0);
            if (entry != null && entry.nativeBody != null) entry.nativeBody.allowCreative(false);
            player.getInventory().clear();
            player.changeGameMode(net.minecraft.world.GameMode.ADVENTURE);
            if (entry != null) {
                for (var plot : battle.plots()) {
                    if (plot.builder().equals(builder)) continue;
                    int score = dev.kindred.core.match.BuildBattle.judge(plotBlocks(player.getServerWorld(), plot), battle.theme());
                    battle.vote(builder, plot.builder(), score);
                    KindredMod.LOGGER.info("Build battle {}: {} gives {}'s build {}", dev.kindred.core.match.MatchRegistry.shortId(match.id()), playerName(builder),
                            playerName(plot.builder()), score);
                }
                continue;
            }
            for (var plot : battle.plots()) {
                if (plot.builder().equals(builder)) continue;
                String name = playerName(plot.builder());
                var line = Text.literal("Vote on " + name + "'s build: ");
                for (int score = 1; score <= 5; score++) {
                    String vote = "/aiplayer match vote " + name + " " + score;
                    line.append(Text.literal("[" + score + "] ").styled(style -> style.withColor(net.minecraft.util.Formatting.GREEN)
                            .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, vote))));
                }
                player.sendMessage(line, false);
            }
        }
    }

    /** A builder's vote on another's build, one to five, while the round is voted on. */
    int voteBuild(ServerCommandSource source, UUID builder, int score) {
        requireThread();
        if (!(source.getEntity() instanceof ServerPlayerEntity voter)) return reject(source, "Only a builder votes, for itself.");
        var match = matches.active(voter.getUuid()).filter(m -> m.mode() == dev.kindred.core.match.MatchRegistry.Mode.BUILDER).orElse(null);
        if (match == null || match.state() != dev.kindred.core.match.MatchRegistry.State.VOTING) return reject(source, "No build battle of yours is being voted on.");
        var battle = battles.get(match.id());
        var refused = battle == null ? java.util.Optional.of("No such round") : battle.vote(voter.getUuid(), builder, score);
        if (refused.isPresent()) return reject(source, refused.get() + ".");
        feedback(source, "Voted " + score + " for " + playerName(builder) + "'s build.");
        return 1;
    }

    /** Once a second: a vote everyone has finished, or whose time is up, decided. */
    private void pollBuildBattles() {
        for (var match : matches.list()) {
            if (match.mode() != dev.kindred.core.match.MatchRegistry.Mode.BUILDER || match.state() != dev.kindred.core.match.MatchRegistry.State.VOTING) continue;
            var battle = battles.get(match.id());
            if (battle == null) continue;
            boolean all = match.joined().stream().filter(match::playing).allMatch(battle::votedAll);
            if (!all && server.getTicks() < match.endsAt()) continue;
            var winners = battle.winners();
            matches.decide(match.id(), winners, winners.isEmpty() ? "Nobody voted" : "the best mean vote").match().ifPresent(this::announce);
        }
    }

    /**
     * Builders out of their build battle (it ended, or they left it): creative's blocks cleared and their own game mode
     * back, and each AI builder's build stopped. At the end, fireworks over the winners' plots.
     */
    private void leaveBuilding(dev.kindred.core.match.MatchRegistry.Match match) {
        boolean over = match.state() == dev.kindred.core.match.MatchRegistry.State.ENDED;
        for (UUID builder : match.invited()) {
            if (!over && match.playing(builder)) continue;
            var mode = modeBefore.remove(builder);
            if (mode == null) continue;
            var player = server.getPlayerManager().getPlayer(builder);
            if (player == null) continue;
            Entry entry = entryForPlayer(builder);
            if (entry != null && entry.build != null) startBuild(server.getCommandSource(), entry.data.id(), null, null, 0);
            if (entry != null && entry.nativeBody != null) entry.nativeBody.allowCreative(false);
            player.getInventory().clear();
            player.changeGameMode(mode);
        }
        var battle = battles.get(match.id());
        if (!over || battle == null) return;
        var world = server.getOverworld();
        for (UUID winner : match.winners()) {
            battle.plot(winner).ifPresent(plot -> {
                var rocket = new ItemStack(Items.FIREWORK_ROCKET);
                rocket.set(net.minecraft.component.DataComponentTypes.FIREWORKS, new net.minecraft.component.type.FireworksComponent(1, List.of(
                        new net.minecraft.component.type.FireworkExplosionComponent(net.minecraft.component.type.FireworkExplosionComponent.Type.LARGE_BALL,
                                it.unimi.dsi.fastutil.ints.IntList.of(0xFFD700, 0xFFFFFF), it.unimi.dsi.fastutil.ints.IntList.of(), true, true))));
                var o = plot.origin();
                for (int i = 0; i < 3; i++) {
                    world.spawnEntity(new net.minecraft.entity.projectile.FireworkRocketEntity(world, o.x() + 3.5 + i * 4, o.y() + 1.5, o.z() + 7.5, rocket.copy()));
                }
            });
        }
        battles.remove(match.id());
        buildsBegun.remove(match.id());
    }

    /**
     * Whether {@code player} may break or place at {@code pos} with a build battle on (M-04a): while it builds, a builder
     * only on its own plot; nobody on another's plot, in the battle or not. Anywhere else, as ever.
     */
    boolean mayEditAt(ServerPlayerEntity player, BlockPos pos) {
        if (!running) return true;
        var at = new dev.kindred.core.construction.Pos(pos.getX(), pos.getY(), pos.getZ());
        for (var match : matches.list()) {
            if (match.mode() != dev.kindred.core.match.MatchRegistry.Mode.BUILDER || match.state() == dev.kindred.core.match.MatchRegistry.State.ENDED) continue;
            var battle = battles.get(match.id());
            if (battle == null) continue;
            var own = battle.plot(player.getUuid()).filter(p -> match.playing(player.getUuid()));
            boolean onAPlot = battle.plots().stream().anyMatch(p -> p.contains(at));
            if (onAPlot && (own.isEmpty() || !own.get().contains(at) || match.state() != dev.kindred.core.match.MatchRegistry.State.RUNNING)) return false;
            // A human builder keeps to its plot while it builds; an AI builder's scaffold may stand in the gap beside it.
            if (own.isPresent() && match.state() == dev.kindred.core.match.MatchRegistry.State.RUNNING && !onAPlot && entryForPlayer(player.getUuid()) == null) return false;
        }
        return true;
    }

    /** Whether vanilla has given {@code player} the split's advancement. */
    private boolean reached(ServerPlayerEntity player, dev.kindred.core.match.SpeedrunSplits.Split split) {
        var advancement = server.getAdvancementLoader().get(net.minecraft.util.Identifier.of(split.advancement()));
        return advancement != null && player.getAdvancementTracker().getProgress(advancement).isDone();
    }

    /**
     * A race or a hunt begins: each AI runner set to the dragon goal, at speedrun pace in a race and at a reliable pace in
     * a hunt; each AI hunter to the run's own route, which earns its tools and the iron for its kit (M-02a, M-02b). Their
     * own pace is kept to give back.
     */
    private void startRace(dev.kindred.core.match.MatchRegistry.Match match) {
        boolean hunt = match.mode() == dev.kindred.core.match.MatchRegistry.Mode.MANHUNT;
        if (hunt) hunts.add(match.id());
        else races.put(match.id(), new dev.kindred.core.match.SpeedrunSplits(server.getTicks()));
        for (UUID player : match.joined()) {
            Entry entry = entryForPlayer(player);
            if (entry == null) continue;
            racePace.put(entry.data.id(), entry.runtimeSettings.playstyleProfile());
            var goal = match.runners().contains(player) ? dev.kindred.core.survival.SurvivalGoal.DRAGON : dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL;
            // A race's runner goes at speedrun pace; a manhunt's beats the game quickly but reliably, at its own pace (M-02b).
            if (match.runners().contains(player)) {
                entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(hunt ? PlaystyleProfile.OPTIMAL_SURVIVAL : PlaystyleProfile.SPEEDRUN_ANY_PERCENT);
            }
            raceGoal.put(entry.data.id(), goal);
            beginGoal(entry, goal);
            if (entry.snapshot().stopLatched()) nativeLifecycle(server.getCommandSource(), "resume", entry.data.id());
        }
        if (hunt) {
            // The one item the hunt hands out, announced before it is, the same for every hunter (D6).
            String rule = "Rule: every hunter holds a tracking compass. It points at the nearest runner in the hunter's world, or where a "
                    + "runner last left it; it vanishes when its hunter dies and is given again, and every one is taken back when the hunt ends.";
            for (UUID invited : match.invited()) {
                var player = server.getPlayerManager().getPlayer(invited);
                if (player != null) player.sendMessage(Text.literal(rule).formatted(net.minecraft.util.Formatting.GOLD), false);
            }
            pollHunts();
        }
    }

    /** An AI runner out of its race (it ended, or the runner dropped out): the race's goal ends and its own pace is back. */
    private void leaveRace(dev.kindred.core.match.MatchRegistry.Match match) {
        for (UUID runner : match.joined()) {
            if (match.state() != dev.kindred.core.match.MatchRegistry.State.ENDED && match.playing(runner)) continue;
            Entry entry = entryForPlayer(runner);
            if (entry == null) continue;
            var pace = racePace.remove(entry.data.id());
            if (pace == null) continue;
            if (entry.goal == raceGoal.remove(entry.data.id())) {
                entry.goal = dev.kindred.core.survival.SurvivalGoal.NONE;
                entry.goalProgress = "";
                if (entry.nativeBody != null) entry.nativeBody.setPortalTravelAuthorized(false);
                entry.cachedRoutineCandidates = List.of();
                entry.routineRefreshPending = true;
            }
            entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(pace);
        }
    }

    /** The compasses a hunt handed out, taken back from each player out of it, all of them once it is over (D6). */
    private void takeBackCompasses(dev.kindred.core.match.MatchRegistry.Match match) {
        if (match.mode() != dev.kindred.core.match.MatchRegistry.Mode.MANHUNT) return;
        boolean over = match.state() == dev.kindred.core.match.MatchRegistry.State.ENDED;
        for (UUID invited : match.invited()) {
            if (!over && match.playing(invited)) continue;
            var player = server.getPlayerManager().getPlayer(invited);
            if (player != null && HuntCompass.takeBack(player, match.id()) > 0) {
                KindredMod.LOGGER.info("Manhunt {}: the tracking compass taken back from {}", dev.kindred.core.match.MatchRegistry.shortId(match.id()), playerName(invited));
            }
        }
        if (over) {
            hunts.remove(match.id());
            for (UUID runner : match.runners()) trails.remove(runner);
        }
    }

    /**
     * Once a second, each running hunt's compasses: every runner's place noted in its world, and every hunter's compass
     * set to the nearest runner in the hunter's world, or failing one, the nearest place a runner last left it. A hunter
     * with none (a new one, or one on its feet after a death took it) is handed one.
     */
    private void pollHunts() {
        for (var match : matches.list()) {
            if (match.mode() != dev.kindred.core.match.MatchRegistry.Mode.MANHUNT || match.state() != dev.kindred.core.match.MatchRegistry.State.RUNNING) continue;
            var runners = new ArrayList<ServerPlayerEntity>();
            for (UUID runner : match.runners()) {
                var player = match.playing(runner) ? server.getPlayerManager().getPlayer(runner) : null;
                if (player == null || !player.isAlive()) continue;
                runners.add(player);
                trails.computeIfAbsent(runner, id -> new HashMap<>()).put(player.getWorld().getRegistryKey(), player.getBlockPos());
            }
            for (UUID hunterId : match.joined()) {
                if (match.runners().contains(hunterId) || !match.playing(hunterId)) continue;
                var hunter = server.getPlayerManager().getPlayer(hunterId);
                if (hunter == null) continue;
                var world = hunter.getWorld().getRegistryKey();
                BlockPos at = runners.stream().filter(r -> r.getWorld().getRegistryKey() == world)
                        .min(java.util.Comparator.comparingDouble(hunter::squaredDistanceTo)).map(ServerPlayerEntity::getBlockPos)
                        .orElseGet(() -> match.runners().stream().filter(match::playing).map(trails::get).filter(java.util.Objects::nonNull)
                                .map(trail -> trail.get(world)).filter(java.util.Objects::nonNull)
                                .min(java.util.Comparator.comparingDouble(pos -> pos.getSquaredDistance(hunter.getPos()))).orElse(null));
                var target = at == null ? java.util.Optional.<net.minecraft.util.math.GlobalPos>empty() : java.util.Optional.of(net.minecraft.util.math.GlobalPos.create(world, at));
                if (HuntCompass.point(hunter, match.id(), target) > 0) {
                    KindredMod.LOGGER.info("Manhunt {}: a tracking compass handed to {}", dev.kindred.core.match.MatchRegistry.shortId(match.id()), playerName(hunterId));
                }
            }
        }
    }

    /** A hunter's kit, ready for the hunt: a sword of iron or better and a shield (M-02a). */
    static boolean huntReady(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        boolean sword = false, shield = player.getOffHandStack().isOf(Items.SHIELD);
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            sword |= FollowUps.ironOrBetter(stack);
            shield |= stack.isOf(Items.SHIELD);
        }
        return sword && shield;
    }

    /** A manhunt's hunter still making its kit, whose follow-ups ask for the kit first. */
    private boolean gearingHunter(NativePlayerBody body) {
        var player = body.entity();
        if (player == null) return false;
        return matches.running(player.getUuid()).filter(m -> m.hunter(player.getUuid())).isPresent() && !huntReady(player);
    }

    /**
     * What a hunter in a running manhunt does (M-02a): a runner it can see within a chase's reach is fought, kit or none,
     * as the chance comes. Kitted, it follows its compass's needle, as a player does, never told where the runner is.
     * Null when the player hunts nobody, or is still making its kit with no runner in sight (its goal's own work).
     */
    private List<NativeTaskCandidate> huntCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var match = matches.running(player.getUuid()).filter(m -> m.hunter(player.getUuid())).orElse(null);
        if (match == null) return null;
        UUID taskId = UUID.nameUUIDFromBytes(("hunt:" + match.id() + ":" + player.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ServerPlayerEntity prey = null;
        for (UUID runner : match.runners()) {
            var target = match.playing(runner) ? server.getPlayerManager().getPlayer(runner) : null;
            if (target == null || !target.isAlive() || target.getServerWorld() != player.getServerWorld() || player.squaredDistanceTo(target) > 24 * 24
                    || !player.canSee(target)) continue;
            if (prey == null || player.squaredDistanceTo(target) < player.squaredDistanceTo(prey)) prey = target;
        }
        if (prey != null) {
            var fight = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 99.0,
                    dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
            int slot = Weapons.choose(player, NativePlayerBody.believedHealth(player, prey), true).map(Weapons.Held::slot).orElse(-1);
            return List.of(new NativeTaskCandidate(fight, "native_fight", Map.of("target", prey.getUuid().toString(), "slot", String.valueOf(slot))));
        }
        // A runner's chest it saw them use, raided at the model's call or its own rule's (E-44), before it gears up.
        var raid = raidCandidates(entry, body);
        if (raid != null) return raid;
        if (!huntReady(player)) return null;
        // Gearing up as the hunt goes on (M-02b): iron armour it carries put on, and the next piece made from the iron in
        // hand, before the needle is followed on.
        var wear = equipCandidate(entry, body, dev.kindred.core.TaskPreferences.Priority.PROJECT, 92.0);
        if (wear.isPresent()) return List.of(wear.get());
        var gear = hunterArmour(entry, body);
        if (gear != null) return gear;
        var bearing = HuntCompass.bearing(player, match.id());
        if (bearing.isEmpty()) return null;
        BlockPos toward = HuntCompass.along(player.getBlockPos(), bearing.get(), 16);
        var follow = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.PROJECT, 90.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        return List.of(new NativeTaskCandidate(follow, "native_goto", Map.of("x", String.valueOf(toward.getX()), "y", String.valueOf(toward.getY()),
                "z", String.valueOf(toward.getZ()), "range", "3", "partial", "true", "sprint", "true")));
    }

    /** Iron armour a hunter makes, best protection for its iron first: the piece, its ingots. */
    private static final List<Map.Entry<net.minecraft.item.Item, Integer>> HUNTER_ARMOUR = List.of(
            Map.entry(Items.IRON_CHESTPLATE, 8), Map.entry(Items.IRON_LEGGINGS, 7), Map.entry(Items.IRON_HELMET, 5), Map.entry(Items.IRON_BOOTS, 4));

    /**
     * The next piece of iron armour a kitted hunter can make from the iron ingots it holds, at a crafting table, or
     * null when it has the iron for none it lacks. A slot already holding iron or better is not made again.
     */
    private List<NativeTaskCandidate> hunterArmour(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        int iron = player.getInventory().count(Items.IRON_INGOT);
        for (var piece : HUNTER_ARMOUR) {
            var slot = player.getPreferredEquipmentSlot(new ItemStack(piece.getKey()));
            var worn = player.getEquippedStack(slot);
            boolean covered = worn.getItem() instanceof net.minecraft.item.ArmorItem armour
                    && armour.getProtection() >= ((net.minecraft.item.ArmorItem) piece.getKey()).getProtection();
            if (covered || player.getInventory().count(piece.getKey()) > 0 || iron < piece.getValue()) continue;
            if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
            var task = entry.campaign.craftAtTable(body, net.minecraft.registry.Registries.ITEM.getId(piece.getKey()).getPath());
            if (task.isEmpty()) return null;
            var t = task.get();
            UUID id = UUID.nameUUIDFromBytes(("gear:" + entry.data.id() + ":" + t.skill() + ":" + new java.util.TreeMap<>(t.args()))
                    .getBytes(StandardCharsets.UTF_8));
            entry.campaignTasks.clear();
            entry.campaignTasks.put(id, t);
            var candidate = new dev.kindred.core.TaskPreferences.Candidate(id, dev.kindred.core.TaskPreferences.Priority.PROJECT, 91.0,
                    dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
            return List.of(new NativeTaskCandidate(candidate, t.skill(), t.args()));
        }
        return null;
    }

    /** How many times each runner has moved off from a hunter in sight, for its log and fixtures. */
    private final Map<UUID, Integer> evasions = new HashMap<>();
    private final Map<UUID, BlockPos> evadingTo = new HashMap<>();

    int evasions(UUID player) {
        requireThread();
        return evasions.getOrDefault(player, 0);
    }

    /**
     * How near a hunter a runner sees before it moves off, how near it is caught and fights back, and how far off a
     * home it still keeps its hunters from.
     */
    private static final double EVADE = 20, CAUGHT = 3.2, HOME_KEPT = 160;

    /**
     * The home a chased player keeps its pursuers from (M-02c): its first base in this world, else the bed its spawn
     * is set by; none when it has neither here.
     */
    private static BlockPos homeToKeep(Entry entry, ServerPlayerEntity player) {
        var world = player.getServerWorld();
        String dimension = world.getRegistryKey().getValue().toString();
        if (entry.bases != null) {
            for (var base : entry.bases.built) {
                if (!base.dimension().equals(dimension)) continue;
                var span = footprint(base);
                return new BlockPos((span.minX() + span.maxX()) / 2, base.y(), (span.minZ() + span.maxZ()) / 2);
            }
        }
        var spawn = player.getSpawnPointPosition();
        return spawn != null && player.getSpawnPointDimension() == world.getRegistryKey() ? spawn : null;
    }

    /**
     * An AI runner in a running manhunt, a hunter in sight (M-02b): it keeps its distance, at a run, away from the
     * nearest hunter it can see but never toward its home while another way is open (M-02c), and fights only once
     * caught. Running weighs more than eating, so it eats once clear, at a safe distance. Null when no hunter is in
     * sight, or the player runs from nobody; its goal goes on.
     */
    private List<NativeTaskCandidate> evadeCandidates(Entry entry, NativePlayerBody body) {
        var player = body.entity();
        var match = matches.running(player.getUuid())
                .filter(m -> m.mode() == dev.kindred.core.match.MatchRegistry.Mode.MANHUNT && m.runners().contains(player.getUuid())).orElse(null);
        if (match == null) return null;
        ServerPlayerEntity hunter = null;
        for (UUID other : match.joined()) {
            if (!match.hunter(other) || !match.playing(other)) continue;
            var seen = server.getPlayerManager().getPlayer(other);
            if (seen == null || !seen.isAlive() || seen.getServerWorld() != player.getServerWorld() || player.squaredDistanceTo(seen) > EVADE * EVADE
                    || !player.canSee(seen)) continue;
            if (hunter == null || player.squaredDistanceTo(seen) < player.squaredDistanceTo(hunter)) hunter = seen;
        }
        if (hunter == null) {
            if (evadingTo.remove(player.getUuid()) != null) KindredMod.LOGGER.info("{} is clear of its hunters", player.getName().getString());
            return null;
        }
        UUID taskId = UUID.nameUUIDFromBytes(("evade:" + match.id() + ":" + player.getUuid()).getBytes(StandardCharsets.UTF_8));
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId, dev.kindred.core.TaskPreferences.Priority.URGENT, 98.0,
                dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        if (player.distanceTo(hunter) <= CAUGHT) {
            // Caught: no getting away from a hunter at arm's length, so it fights.
            int slot = Weapons.choose(player, NativePlayerBody.believedHealth(player, hunter), true).map(Weapons.Held::slot).orElse(-1);
            return List.of(new NativeTaskCandidate(candidate, "native_fight", Map.of("target", hunter.getUuid().toString(), "slot", String.valueOf(slot))));
        }
        BlockPos target = awayFrom(entry, player, hunter);
        var last = evadingTo.get(player.getUuid());
        if (last == null || last.getSquaredDistance(target) > 16) {
            evadingTo.put(player.getUuid(), target.toImmutable());
            evasions.merge(player.getUuid(), 1, Integer::sum);
            KindredMod.LOGGER.info("{} keeps away from {}, {} blocks off: toward {}", player.getName().getString(), hunter.getName().getString(),
                    String.format("%.1f", player.distanceTo(hunter)), target.toShortString());
        }
        return List.of(new NativeTaskCandidate(candidate, "native_goto", Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()),
                "z", String.valueOf(target.getZ()), "range", "3", "partial", "true", "sprint", "true")));
    }

    /**
     * Where it runs to from {@code threat}: sixteen blocks away from it, or at an angle where the way straight off is
     * shut, never toward its own home, its base or its bed while another way is open (M-02c); straight away when no way
     * is open.
     */
    private BlockPos awayFrom(Entry entry, ServerPlayerEntity player, ServerPlayerEntity threat) {
        BlockPos open = openWayFrom(entry, player, threat);
        if (open != null) return open;
        Vec3d away = awayDirection(player, threat);
        BlockPos feet = player.getBlockPos();
        return BlockPos.ofFloored(feet.getX() + 0.5 + away.x * 16, feet.getY(), feet.getZ() + 0.5 + away.z * 16);
    }

    private static Vec3d awayDirection(ServerPlayerEntity player, ServerPlayerEntity threat) {
        Vec3d away = player.getPos().subtract(threat.getPos()).multiply(1, 0, 1);
        if (away.lengthSquared() < 1e-4) away = new Vec3d(1, 0, 0);
        return away.normalize();
    }

    /** A way off from {@code threat} with ground to stand on, as {@link #awayFrom} picks it; null when none is open. */
    private BlockPos openWayFrom(Entry entry, ServerPlayerEntity player, ServerPlayerEntity threat) {
        Vec3d away = awayDirection(player, threat);
        var navigator = new NativeNavigator(player.getServerWorld());
        BlockPos feet = player.getBlockPos();
        // Never toward its own home, its base or the bed it sleeps in: a chased player does not lead its pursuers there
        // (M-02c). Only when every way off is toward home does it run that way all the same.
        BlockPos home = homeToKeep(entry, player);
        double homeX = home == null ? 0 : home.getX() + 0.5 - player.getX(), homeZ = home == null ? 0 : home.getZ() + 0.5 - player.getZ();
        if (homeX * homeX + homeZ * homeZ > HOME_KEPT * HOME_KEPT) homeX = homeZ = 0;
        for (double turn : dev.kindred.core.match.EvasionCourse.turns(away.x, away.z, homeX, homeZ)) {
            Vec3d way = away.rotateY((float) Math.toRadians(turn));
            BlockPos at = BlockPos.ofFloored(feet.getX() + 0.5 + way.x * 16, feet.getY(), feet.getZ() + 0.5 + way.z * 16);
            for (int dy : new int[] {0, 1, -1, 2, -2}) {
                if (navigator.standable(at.up(dy))) return at.up(dy).toImmutable();
            }
        }
        return null;
    }

    /** Once a second: each running race's runners checked for the splits vanilla has given them since. */
    private void pollRaces() {
        for (var match : matches.list()) {
            if (match.mode() != dev.kindred.core.match.MatchRegistry.Mode.SPEEDRUN || match.state() != dev.kindred.core.match.MatchRegistry.State.RUNNING) continue;
            var splits = races.get(match.id());
            if (splits == null) continue;
            for (UUID runner : match.joined()) {
                if (!match.playing(runner)) continue;
                var player = server.getPlayerManager().getPlayer(runner);
                if (player == null) continue;
                for (var split : dev.kindred.core.match.SpeedrunSplits.ANY_PERCENT) {
                    if (!reached(player, split)) continue;
                    var reached = splits.reach(runner, split, server.getTicks()).orElse(null);
                    if (reached == null) continue;
                    presenter.split(match, reached, this::playerName);
                    KindredMod.LOGGER.info("Speedrun {}: {} {} at {}", dev.kindred.core.match.MatchRegistry.shortId(match.id()), playerName(runner),
                            split.label(), dev.kindred.core.match.SpeedrunSplits.time(reached.ticks()));
                    if (reached.finish()) {
                        matches.finished(runner).ifPresent(this::announce);
                        return;
                    }
                }
            }
        }
    }

    /** How far the leading runner of a race is, as a share of the splits; a whole bar for anything else. */
    private float raceProgress(UUID matchId) {
        var splits = races.get(matchId);
        var match = matches.get(matchId).orElse(null);
        if (splits == null || match == null) return 1f;
        int most = 0;
        for (UUID runner : match.joined()) most = Math.max(most, splits.of(runner).size());
        return most / (float) dev.kindred.core.match.SpeedrunSplits.ANY_PERCENT.size();
    }

    /** A race's splits as reached so far, for a fixture or a status line. */
    List<dev.kindred.core.match.SpeedrunSplits.Reached> raceSplits(UUID matchId, UUID runner) {
        requireThread();
        var splits = races.get(matchId);
        return splits == null ? List.of() : splits.of(runner);
    }

    /** The player running the command joins a match it was invited to. Only a player can join, and only for itself. */
    int joinMatch(ServerCommandSource source, UUID matchId) {
        requireThread();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) return reject(source, "Only a player can join a match, for itself.");
        var result = matches.join(matchId, player.getUuid());
        if (!result.accepted()) return reject(source, result.reason());
        feedback(source, "Joined the " + result.match().orElseThrow().mode().label().toLowerCase(java.util.Locale.ROOT) + ".");
        return 1;
    }

    /** Starts a match whose every invited player has joined: an operator, or a joined player. */
    int startMatch(ServerCommandSource source, UUID matchId) {
        requireThread();
        var match = matches.get(matchId).orElse(null);
        if (match == null) return reject(source, "No such match.");
        boolean participant = source.getEntity() instanceof ServerPlayerEntity player && match.participant(player.getUuid());
        if (!participant && !source.hasPermissionLevel(2)) return reject(source, "Only an operator or a player in the match can start it.");
        if (match.mode().pvp && !server.isPvpEnabled()) return reject(source, "PvP is off on this server (pvp=false in server.properties); no duel can be fought.");
        var result = matches.start(matchId, server.getTicks());
        if (!result.accepted()) return reject(source, result.reason());
        announce(result.match().orElseThrow());
        return 1;
    }

    /** Stops a match: an operator stops it outright; a player in it forfeits. */
    int stopMatch(ServerCommandSource source, UUID matchId) {
        requireThread();
        var match = matches.get(matchId).orElse(null);
        if (match == null) return reject(source, "No such match.");
        dev.kindred.core.match.MatchRegistry.Result result;
        if (source.hasPermissionLevel(2)) {
            result = matches.stop(matchId);
        } else if (source.getEntity() instanceof ServerPlayerEntity player && match.participant(player.getUuid())) {
            result = matches.leave(matchId, player.getUuid());
        } else {
            return reject(source, "Only an operator or a player in the match can stop it.");
        }
        if (!result.accepted()) return reject(source, result.reason());
        announce(result.match().orElseThrow());
        return 1;
    }

    /** A player fell: a running duel it was in ends, the other the winner. */
    void playerFell(UUID playerId) {
        requireThread();
        matches.fallen(playerId).ifPresent(this::announce);
    }

    /** A player left the game: a duel it was in is forfeited, or called off if it had not started. */
    void playerLeft(UUID playerId) {
        requireThread();
        matches.departed(playerId).ifPresent(this::announce);
    }

    /** A match by its id or the start of it, as typed. */
    java.util.Optional<dev.kindred.core.match.MatchRegistry.Match> findMatch(String raw) {
        requireThread();
        return matches.find(raw);
    }

    MatchPresenter presenter() {
        requireThread();
        return presenter;
    }

    List<dev.kindred.core.match.MatchRegistry.Match> matches() {
        requireThread();
        return matches.list();
    }

    /** The gates on a native player's next decision, in a line, for a fixture that finds it idle. */
    String nativeStatusLine(UUID id) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null) return "no such player";
        var body = entry.nativeBody;
        String ticking = body == null ? "body=none" : "bodyTicks=" + body.ticksRun() + " age=" + body.entity().age + " removed=" + body.entity().isRemoved()
                + " listed=" + (server.getPlayerManager().getPlayer(body.entity().getUuid()) == body.entity()) + " connected=" + body.connected()
                // Whether the world it is in ticks it: a world ticks an entity only where its chunk is simulated.
                + " world=" + body.entity().getServerWorld().getRegistryKey().getValue().getPath()
                + " inWorld=" + body.entity().getServerWorld().getPlayers().contains(body.entity())
                + " lookup=" + (body.entity().getServerWorld().getEntity(body.entity().getUuid()) == body.entity())
                + " simulated=" + body.entity().getServerWorld().shouldTickEntity(body.entity().getBlockPos())
                + " forced=" + body.entity().getServerWorld().getForcedChunks().contains(body.entity().getChunkPos().toLong());
        return ticking + " phase=" + (entry.nativeState == null ? "none" : entry.nativeState.phase()) + " stop=" + entry.snapshot().stopLatched()
                + " runner=" + (entry.runner == null ? "none" : entry.runner.state()) + " busy=" + (entry.runner != null && runnerBusy(entry))
                + " workPending=" + entry.nativeWorkPending + " setupPending=" + entry.nativeSetupPending + " actionPending=" + entry.nativeActionPending
                + " activity=" + (entry.activity == null ? "none" : entry.activity.phase()) + " flushes=" + entry.activityFlush.isDone() + "/"
                + entry.nativeActionFlush.isDone() + " cancelling=" + (entry.arbiter != null && entry.arbiter.cancellationPending())
                + " hold=" + (entry.manualHoldUntilTick == Long.MIN_VALUE ? "none" : String.valueOf(entry.manualHoldUntilTick - server.getTicks())) + " edits=" + edits.autonomousEditsEnabled() + "/"
                + edits.protectionCompatibilityKnown() + " playstyle=" + entry.runtimeSettings.playstyleProfile() + " routine="
                + entry.cachedRoutineCandidates.size() + "/" + entry.routineRefreshPending + " build=" + (entry.build == null ? "none" : entry.build.progress())
                + " blocker=" + entry.blocker;
    }

    int cachedRoutineCandidatesCountForTest(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? 0 : entry.cachedRoutineCandidates.size();
    }

    long inventoryFingerprintForTest(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? 0L : entry.lastInventoryFingerprint;
    }

    int blocksScannedThisTickForTest() {
        return blocksScannedThisTick;
    }

    private final class Entry implements ChoreSkills.Gate {
        private Companion data;
        /** The campaign whose memory was read, whether the read is in, and the leg's revision last written. */
        private PortalRunCampaign memoryCampaign;
        private boolean memoryLoaded;
        private int memorySaved = -1;
        private final dev.kindred.core.PlayerDecisionLoop mind;
        private String mindStatus = "Awaiting local decision";
        private CompanionEntity body;
        private NativePlayerBody nativeBody;
        private StoredNativePlayer nativeState;
        private UUID currentTaskId;
        private UUID waterEscapeTaskId;
        private long waterEscapeRetryTick;
        /** Walks of its own that found no way, since it came into play: a measure of how often it sets out in vain. */
        private int walksFailed;
        /** The shore its way out of deep water makes for, and those it could not reach, until when each is passed over. */
        private BlockPos waterEscapeShore;
        private final Map<Long, Long> unreachedShores = new HashMap<>();
        /** Where it looks while it waits, and who last spoke to it, when (server ticks) (E-33a). */
        private dev.kindred.core.Gaze gaze;
        private Personality gazePersonality;
        private UUID addressedBy;
        private long addressedAt = Long.MIN_VALUE / 2;
        /** What it held of each material shared out at its last reckoning: more since is a find to share (E-40a). */
        private final Map<ShareOut.Material, Integer> heldFinds = new java.util.EnumMap<>(ShareOut.Material.class);
        /** Materials whose share-out is being saved, not to be reckoned again meanwhile. */
        private final java.util.Set<ShareOut.Material> sharing = java.util.EnumSet.noneOf(ShareOut.Material.class);
        /** The hand-over step dispatched and the task it is a step of; until when its hand-overs wait; what it told its team. */
        private UUID handOverStepId;
        private TaskId handOverTask;
        private String handOverStepSkill;
        private long handOversWaitUntil;
        private int handOverFailures;
        private String teamLine;
        /** What it last said aloud, for fixtures. */
        private String lastSaid;
        /** Chests it saw a player use, by place (E-44); what it decided of each opponent's, and until when; a question out. */
        private final Map<Long, ChestUse> chestsUsed = new HashMap<>();
        private final Map<Long, RaidDecision> raids = new HashMap<>();
        private boolean raidAsking;
        /**
         * Named kit (E-16d): the name chosen for each piece, by the piece; whether the model has a question of its out;
         * the names it has given; the anvil it put down; and when it looks to its kit again.
         */
        private final Map<String, KitName> kitNames = new HashMap<>();
        private boolean kitNameAsking;
        private final Set<String> kitNamesGiven = new HashSet<>();
        private BlockPos anvil;
        private long kitNamesAt;
        /**
         * Its extended inventory (E-43): what it has stored, as it knows it; its stores, as last reckoned; who it saw at
         * one of them, and when; a question to its team of what is gone, and until when it waits for a word; the stores
         * it no longer trusts, and one it is emptying; and when it looks to put things away again.
         */
        private final dev.kindred.core.team.Storage storage = new dev.kindred.core.team.Storage();
        /** The last few things it said, oldest first, for fixtures. */
        private final java.util.ArrayDeque<String> saidLately = new java.util.ArrayDeque<>();

        private void said(String text) {
            if (saidLately.size() >= 16) saidLately.removeFirst();
            saidLately.addLast(text);
        }
        private List<OwnStore> ownStores;
        private long ownStoresAt, storeAt;
        private final Map<Long, SeenAtStore> seenAtStores = new HashMap<>();
        private StorageAsk storageAsk;
        private final Set<Long> untrustedStores = new HashSet<>();
        private BlockPos emptying;
        /** What it took out of a store of its own lately, and when: not put straight back. */
        private final Map<String, Long> recentlyTaken = new HashMap<>();
        /** Whether it saw someone not on its side at a store of its own, and has not yet gone to look. */
        private boolean sightingInterrupt;
        /** When it next looks over its storage to tidy it. */
        private long organizeAt;
        /** The task of its latest walk to or use of a store, and to its anvil: let be a while if it could not get there. */
        private UUID storeTaskId, kitTaskId;
        private long lookAt;
        /** Villagers it has looked at, their offers as it saw them (E-45); when it next visits each; the one it is going to. */
        private final Map<UUID, Villager> villagers = new HashMap<>();
        private final Map<UUID, Long> villagerVisitAt = new HashMap<>();
        private final Set<UUID> villagersTold = new HashSet<>();
        private UUID tradeTaskId, tradeVillager;
        private long tradeAt;
        /** The lectern reroll under way (E-45c), the villagers kept and given up on, and when it next looks for one. */
        private Reroll reroll;
        /** A kept villager being brought to its cell (E-45e), when it may look again for one to bring, those brought, and why not. */
        private Bring bring;
        private long bringAt;
        private final Set<UUID> brought = new HashSet<>();
        private String bringNote = "";
        private final Set<UUID> rerollKept = new HashSet<>(), rerollGivenUp = new HashSet<>();
        private final List<String> rerollLooks = new ArrayList<>();
        private long rerollAt;
        /**
         * Hit hard by a player (E-41g): who, and what it does about it, until when; whether what it is doing gives way to
         * it; the hardest hit each player has dealt it, after armour; and when it last said so.
         */
        private Aggressor aggressor;
        private boolean aggressionInterrupt;
        private final Map<UUID, Double> hardestHits = new HashMap<>();
        private long aggressionSaidAt = -1_000_000;
        /** Its part of the team's run as it last took it up, said once (E-40a), and each it has taken up. */
        private dev.kindred.core.team.RunDivision.Part runPart;
        private final List<String> partsTaken = new ArrayList<>();
        /** The time spoken to that it has stopped to look for, and the pause's task (E-33a). */
        private long attendedAt = Long.MIN_VALUE / 2;
        private UUID attentionTaskId;
        private boolean awaitingRespawn;
        private boolean campaignFightInterrupt;
        private boolean endPortalAnnounced, dragonAnnounced;
        private UUID tacticalRetreatTaskId;
        private long lastTacticalRetreatTick = Long.MIN_VALUE / 2;
        private UUID barricadeTaskId;
        private long lastBarricadeTick = Long.MIN_VALUE / 2;
        private boolean nativePending;
        private ConnectionToken pendingConnection;
        private LedgerCheckState ledgerState = LedgerCheckState.PENDING;
        private boolean nativeSetupPending;
        private boolean nativeWorkPending;
        private PaidActivitySession activity;
        private dev.kindred.core.SkillBody nativeTask;
        private ActionRecord lastNativeAction;
        private boolean nativeActionPending;
        private CompletableFuture<?> nativeActionFlush = CompletableFuture.completedFuture(null);
        private CompletableFuture<?> activityFlush = CompletableFuture.completedFuture(null);
        private ActionArbiter arbiter;
        private ActionLease lease;
        private boolean following;
        private boolean metadataFault;
        private boolean reconciliationBlocked;
        private boolean hazardInterrupted;
        private FollowBehavior follow;
        private SocialProfile profile;
        private CompletableFuture<SocialProfile> profileWrite;
        private MannerismState mannerisms;
        private CompletableFuture<Mannerisms> mannerismWrite;
        private WorkSetup setup;
        private CompletableFuture<WorkSetup> setupWrite;
        private SkillRunner runner;
        private final SprintStride stride = new SprintStride();
        private final java.util.Map<String, CompletableFuture<ActionRecord>> preparations = new java.util.HashMap<>();

        @Override
        public boolean prepared(String key, String kind, String preconditions) {
            CompletableFuture<ActionRecord> future = preparations.get(key);
            if (future == null) {
                if (lease == null) return false;
                var grant = claims.acquire(lease.taskId(), lease.sessionEpoch(), java.util.List.of(key), System.currentTimeMillis(), 60_000);
                if (grant.isEmpty()) return false;
                preparations.put(key, store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, kind, preconditions)));
                return false;
            }
            return future.isDone() && !future.isCompletedExceptionally();
        }

        @Override
        public void committed(String key, String outcome) {
            CompletableFuture<ActionRecord> future = preparations.remove(key);
            if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
                ActionRecord prep = future.join();
                store.transitionAction(prep.dispatched("dispatched"), prep.revision())
                        .thenCompose(disp -> store.transitionAction(disp.verified("verified", outcome), disp.revision()))
                        .whenComplete((saved, err) -> {
                            if (err != null) KindredMod.LOGGER.error("Failed to commit action record for {}", key, err);
                        });
            }
            if (lease != null) claims.releaseKey(lease.taskId(), key);
        }

        @Override
        public void rolledBack(String key, String reason) {
            CompletableFuture<ActionRecord> future = preparations.remove(key);
            if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
                ActionRecord prep = future.join();
                store.transitionAction(prep.failed(null, reason), prep.revision())
                        .whenComplete((saved, err) -> {
                            if (err != null) KindredMod.LOGGER.error("Failed to fail action record for {}", key, err);
                        });
            }
            if (lease != null) claims.releaseKey(lease.taskId(), key);
        }
        private SkillRunner.State lastRunnerState = SkillRunner.State.IDLE;
        private String skillId;
        /** The running skill's arguments, as it was started with them. */
        private Map<String, String> skillArgs = Map.of();
        private UUID glanceTarget;
        private int glanceUntilAge;
        private BlockPerception perception;
        private ChunkSectionSource perceptionSource;
        private SectionKey perceptionCenter;
        private long perceptionSetupRevision = -1;
        private String lookBlock;
        private UUID lookRequester;
        private boolean lookPending;
        private long lookPassesBefore;
        private long generation;
        private String blocker = "Body is not loaded; load its saved area. Missing bodies require reconciliation.";
        private CompletableFuture<StoredCompanion> writes;
        private OperatingMode mode;
        private final AttentionStateMachine attention;
        private AttentionSnapshot attentionSnapshot;
        private List<NativeTaskCandidate> cachedRoutineCandidates = List.of();
        private boolean routineRefreshPending;
        private long lastInventoryFingerprint = -1;
        private WorkSetup lastSetupSnapshot = null;
        private BlockPos cachedCraftingTablePos = null;
        private BlockPos cachedFurnacePos = null;
        private int patchScanCursor = 0;
        private int patchApproachCursor = 0;
        private BlockPos cachedMiningTarget = null;
        private boolean cachedMiningIsStone = false;
        private BlockPos cachedApproachTarget = null;
        private BlockPos lastPlayerPosForScan = null;
        private Long lastAttributedAttackSequence = null;
        final dev.kindred.core.tactics.StandardCombatPredictor tacticalPredictor = new dev.kindred.core.tactics.StandardCombatPredictor();
        dev.kindred.core.tactics.TacticalObservation lastTacticalObservation = null;
        dev.kindred.core.tactics.TacticalDecision lastTacticalDecision = null;
        long lastTacticalRefreshTick = -1L;
        long cachedWeaponFingerprint = Long.MIN_VALUE;
        int cachedWeaponHeldSlot = -1;
        List<dev.kindred.core.tactics.WeaponProfile> cachedWeapons = List.of();
        CanonicalRoster.RuntimeSettings runtimeSettings = CanonicalRoster.RuntimeSettings.defaultsForNewPlayer();
        BlockPos idleAnchorPos = null;
        UUID lastGazeHumanId = null;
        long idleNextWanderTick = 0L;
        BlockPos portalFrameInterior = null;
        long manualHoldUntilTick = Long.MIN_VALUE;
        dev.kindred.core.survival.SurvivalGoal goal = dev.kindred.core.survival.SurvivalGoal.NONE;
        String goalProgress = "";
        PortalRunCampaign campaign;
        final Map<UUID, PortalRunCampaign.Task> campaignTasks = new HashMap<>();
        PortalRunCampaign.Task activeCampaignTask;
        /** A build the player is on (E-08b), ahead of its standing goal until it is done. */
        BuildRunner build;
        final Map<UUID, PortalRunCampaign.Task> buildTasks = new HashMap<>();
        /** How much of each shared task's output it held when it took the task up (E-11). */
        final Map<TaskId, Integer> teamBaselines = new HashMap<>();
        PortalRunCampaign.Task activeBuildTask;
        /** A ranch in progress (M-01a): the pen, the pair led in and bred. Its build and its steps run as the build's do. */
        RanchRunner ranch;
        /** A field being watered, tilled and sown (M-06a's farm ward). Its steps run as the build's do. */
        FieldRunner field;
        /**
         * The work an operator set it to, as kept for a restart (F-13), and the scaffold last kept with it; whether
         * what it was at before the restart has been read and taken up, and what was read.
         */
        PlayerMemoryStore.Work keptWork;
        Set<dev.kindred.core.construction.Pos> keptScaffold = Set.of();
        WorkRead workRead = WorkRead.UNREAD;
        PlayerMemoryStore.Work savedWork;
        /**
         * The player's own bases (M-01c), once read; the build that is its base's; when it next looks to its base, how
         * often its base's build has ended short, and what it last decided.
         */
        PlayerMemoryStore.Bases bases;
        boolean basesLoading;
        BuildRunner baseBuild;
        /** The base the build in hand repairs (E-31a), if it is a repair. */
        PlayerMemoryStore.Base baseRepair;
        /** The seed block it was given (E-29a), until its bases are read; and a fixture's fixed style for its bases. */
        String seedBlock;
        dev.kindred.core.construction.BuildStyle fixedBaseStyle;
        /**
         * Life first (F-25c9): whether it is out getting food to last, until when a look for food that came to nothing
         * holds, the walks it has taken to look, when it may try its bed again after a sleep that did not take, the
         * life task in hand, and what it last said of it.
         */
        boolean gettingFood;
        long foodSoughtUntil;
        int foodLooks;
        long sleepRetryAt;
        UUID lifeTaskId;
        PortalRunCampaign.Task lifeTask;
        String lifeNote = "";
        long baseCheckAt;
        int baseTries;
        String baseNote = "";
        /** Its trading hall's build while it is at it (E-45d), and the times it ended short. */
        BuildRunner hallBuild;
        int hallTries;
        boolean goalAnnounced;
        String loggedGoalProgress = "";
        boolean foodTaskAuthorized = false;
        boolean shelterProtectionAuthorized = false;
        final MilestoneGraph progressionGraph = MilestoneGraph.canonical();
        final Map<MilestoneId, Long> progressionHistory = new HashMap<>();
        ProgressionObservation lastProgressionObservation = null;
        ProgressionState lastProgressionState = null;
        private ExpeditionState expeditionState = null;

        private Entry(StoredCompanion record) {
            data = record.companion();
            mind = new dev.kindred.core.PlayerDecisionLoop(data.playerIdentity());
            attention = new AttentionStateMachine(runtimeSession, data.id());
            attentionSnapshot = attention.snapshot(0);
            mode = OperatingMode.defaults(data.id());
            profile = SocialProfile.defaults(data.id());
            profileWrite = CompletableFuture.completedFuture(profile);
            mannerisms = new MannerismState(Mannerisms.defaults(data.id()));
            mannerismWrite = CompletableFuture.completedFuture(mannerisms.profile());
            setup = WorkSetup.empty(data.id(), data.dimension());
            setupWrite = CompletableFuture.completedFuture(setup);
            if (data.lifecycle() != LifecycleState.FAULTED && data.lifecycle() != LifecycleState.REMOVED) {
                data = data.withLifecycle(data.stopLatched() ? LifecycleState.PAUSED : LifecycleState.SLEEPING);
            }
            writes = CompletableFuture.completedFuture(record);
        }

        private Companion snapshot() { return arbiter == null ? data : arbiter.snapshot(); }

        private String explanation() {
            if (metadataFault) return "Metadata write failed. Controls remain blocked until the storage problem is fixed and the server restarts.";
            if (reconciliationBlocked) return nativeState != null ? blocker + " Native state requires reconciliation; no replacement items are created."
                    : "Conflicting native bodies were detected. Controls remain blocked for operator reconciliation; no items were deleted.";
            if (snapshot().lifecycle() == LifecycleState.FAULTED) return "Faulted; operator recovery is required before controls can resume.";
            return blocker + " Player decision: " + mindStatus + ".";
        }
    }

    private void appendPlayerQolCandidates(Entry entry, NativePlayerBody body, List<NativeTaskCandidate> candidates) {
        if (entry.runtimeSettings.collaborationMode() != CollaborationMode.COOP_TEAM) {
            return;
        }
        var bot = body.entity();
        var world = bot.getServerWorld();
        int botCookedFood = 0;
        String primaryFoodId = null;
        double bestFood = Double.NEGATIVE_INFINITY;
        int botTorches = 0;
        for (int s = 0; s < 36; s++) {
            var stack = bot.getInventory().getStack(s);
            if (stack.isEmpty()) continue;
            if (stack.isOf(Items.TORCH)) {
                botTorches += stack.getCount();
            }
            if (isEligibleFood(stack, true, true) && !stack.getItem().toString().contains("raw")) {
                botCookedFood += stack.getCount();
                double score = foodQualityScore(stack, true);
                if (score > bestFood) {
                    bestFood = score;
                    primaryFoodId = Registries.ITEM.getId(stack.getItem()).toString();
                }
            }
        }
        BlockPos storagePos = entry.setup != null && entry.setup.storage() != null
                ? new BlockPos(entry.setup.storage().position().x(), entry.setup.storage().position().y(), entry.setup.storage().position().z())
                : null;
        for (ServerPlayerEntity human : world.getPlayers()) {
            if (human.getUuid().equals(bot.getUuid())) continue;
            if (human.squaredDistanceTo(bot) > 256.0) continue;
            boolean humanAtChest = storagePos != null
                    && human.currentScreenHandler != human.playerScreenHandler
                    && human.getBlockPos().getSquaredDistance(storagePos) <= 9;
            int blockLight = world.getLightLevel(net.minecraft.world.LightType.BLOCK, human.getBlockPos());
            var assists = ChestOrganizationPolicy.evaluatePlayerQolAssistance(
                    human.getUuid(),
                    human.getName().getString(),
                    human.getHungerManager().getFoodLevel(),
                    human.getHealth(),
                    blockLight,
                    humanAtChest,
                    botCookedFood,
                    primaryFoodId,
                    botTorches
            );
            for (var assist : assists) {
                if (assist.kind() == ChestOrganizationPolicy.PlayerQolAssistance.AssistKind.YIELD_CHEST_ACCESS) {
                    human.sendMessage(Text.literal(assist.chatCallout()), false);
                    continue;
                }
                if (assist.kind() == ChestOrganizationPolicy.PlayerQolAssistance.AssistKind.TOSS_EMERGENCY_FOOD
                        || assist.kind() == ChestOrganizationPolicy.PlayerQolAssistance.AssistKind.TOSS_TORCHES_IN_DARK) {
                    int slot = findInventorySlotWithItem(bot, assist.itemId(), assist.count());
                    if (slot >= 0) {
                        UUID taskId = UUID.nameUUIDFromBytes(("qol_toss:" + entry.data.id() + ":" + human.getUuid() + ":" + assist.itemId()).getBytes(StandardCharsets.UTF_8));
                        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId,
                                dev.kindred.core.TaskPreferences.Priority.ROUTINE, 58.0,
                                dev.kindred.core.TaskPreferences.Affinities.none(),
                                new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                        candidates.add(new NativeTaskCandidate(candidate, "native_toss", Map.of(
                                "slot", String.valueOf(slot),
                                "count", String.valueOf(assist.count()),
                                "target", human.getUuid().toString())));
                        human.sendMessage(Text.literal(assist.chatCallout()), false);
                    }
                }
            }
        }
    }

    private static int findInventorySlotWithItem(ServerPlayerEntity player, String itemId, int minCount) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return -1;
        Item item = Registries.ITEM.get(id);
        for (int s = 0; s < 36; s++) {
            var stack = player.getInventory().getStack(s);
            if (!stack.isEmpty() && stack.isOf(item) && stack.getCount() >= minCount) {
                return s;
            }
        }
        return -1;
    }

    private void appendHotbarOrganizationCandidates(Entry entry, ServerPlayerEntity player, List<NativeTaskCandidate> candidates) {
        if (entry.attentionSnapshot == null) return;
        AttentionState state = entry.attentionSnapshot.state();
        if (state != AttentionState.ROUTINE && state != AttentionState.RECOVERY) {
            return;
        }
        proposeHotbarSwap(entry, player, candidates, 0, stack -> stack.getItem() instanceof net.minecraft.item.SwordItem, 62.0, "hotbar_weapon_0");
        proposeHotbarSwap(entry, player, candidates, 1, stack -> stack.getItem() instanceof net.minecraft.item.PickaxeItem, 61.5, "hotbar_pick_1");
        proposeHotbarSwap(entry, player, candidates, 2, stack -> stack.getItem() instanceof net.minecraft.item.AxeItem
                || stack.getItem() instanceof net.minecraft.item.HoeItem, 61.0, "hotbar_axe_2");
        proposeHotbarSwap(entry, player, candidates, 7, stack -> stack.getItem() instanceof net.minecraft.item.BlockItem, 60.5, "hotbar_blocks_7");
        var hotbarFood = player.getInventory().getStack(8);
        if (!isEligibleFood(hotbarFood, true, true)) {
            int invFood = -1;
            double best = Double.NEGATIVE_INFINITY;
            for (int s = 9; s < 36; s++) {
                var stack = player.getInventory().getStack(s);
                if (isEligibleFood(stack, true, true)) {
                    double score = foodQualityScore(stack, player.getHealth() < player.getMaxHealth());
                    if (score > best) {
                        best = score;
                        invFood = s;
                    }
                }
            }
            if (invFood >= 0) {
                UUID taskId = UUID.nameUUIDFromBytes(("hotbar_food_8:" + entry.data.id() + ":" + invFood).getBytes(StandardCharsets.UTF_8));
                var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId,
                        dev.kindred.core.TaskPreferences.Priority.ROUTINE, 60.0,
                        dev.kindred.core.TaskPreferences.Affinities.none(),
                        new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                        "source", String.valueOf(invFood),
                        "target", "8")));
            }
        }
    }

    private void proposeHotbarSwap(Entry entry, ServerPlayerEntity player, List<NativeTaskCandidate> candidates,
                                   int hotbarSlot, java.util.function.Predicate<ItemStack> desiredHotbar,
                                   double need, String label) {
        if (desiredHotbar.test(player.getInventory().getStack(hotbarSlot))) {
            return;
        }
        int source = -1;
        int bestTier = -1;
        for (int s = 9; s < 36; s++) {
            var stack = player.getInventory().getStack(s);
            if (desiredHotbar.test(stack)) {
                int tier = toolTier(stack.getItem());
                if (tier >= bestTier) {
                    bestTier = tier;
                    source = s;
                }
            }
        }
        if (source < 0) return;
        UUID taskId = UUID.nameUUIDFromBytes((label + ":" + entry.data.id() + ":" + source).getBytes(StandardCharsets.UTF_8));
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId,
                dev.kindred.core.TaskPreferences.Priority.ROUTINE, need,
                dev.kindred.core.TaskPreferences.Affinities.none(),
                new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        candidates.add(new NativeTaskCandidate(candidate, "native_swap", Map.of(
                "source", String.valueOf(source),
                "target", String.valueOf(hotbarSlot))));
    }

    private void appendChestOrganizeCandidate(Entry entry, NativePlayerBody body, List<NativeTaskCandidate> candidates) {
        if (entry.setup == null || entry.setup.storage() == null || server.getTicks() < entry.organizeAt) return;
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos chestPos = new BlockPos(
                entry.setup.storage().position().x(),
                entry.setup.storage().position().y(),
                entry.setup.storage().position().z());
        if (!world.isChunkLoaded(chestPos)) return;
        if (player.getEyePos().squaredDistanceTo(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5) > 20.25) {
            return;
        }
        for (ServerPlayerEntity human : world.getPlayers()) {
            if (human.getUuid().equals(player.getUuid())) continue;
            if (human.currentScreenHandler != human.playerScreenHandler
                    && human.getBlockPos().getSquaredDistance(chestPos) <= 9) {
                return;
            }
        }
        UUID taskId = UUID.nameUUIDFromBytes(("organize_chest:" + entry.data.id() + ":" + chestPos.asLong()).getBytes(StandardCharsets.UTF_8));
        var candidate = new dev.kindred.core.TaskPreferences.Candidate(taskId,
                dev.kindred.core.TaskPreferences.Priority.ROUTINE, 57.0,
                new dev.kindred.core.TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.6),
                new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
        candidates.add(new NativeTaskCandidate(candidate, "native_organize_chest", Map.of(
                "x", String.valueOf(chestPos.getX()),
                "y", String.valueOf(chestPos.getY()),
                "z", String.valueOf(chestPos.getZ()),
                "max_moves", "4")));
    }

    private static int toolTier(net.minecraft.item.Item item) {
        if (item == Items.NETHERITE_PICKAXE || item == Items.NETHERITE_AXE) return 6;
        if (item == Items.DIAMOND_PICKAXE || item == Items.DIAMOND_AXE) return 5;
        if (item == Items.IRON_PICKAXE || item == Items.IRON_AXE) return 4;
        if (item == Items.STONE_PICKAXE || item == Items.STONE_AXE) return 3;
        if (item == Items.GOLDEN_PICKAXE || item == Items.GOLDEN_AXE) return 2;
        if (item == Items.WOODEN_PICKAXE || item == Items.WOODEN_AXE) return 1;
        return 0;
    }

    private static double foodQualityScore(ItemStack stack, boolean injured) {
        var food = stack.get(DataComponentTypes.FOOD);
        if (food == null) return 0.0;
        return food.saturation() * (injured ? 3.0f : 1.5f) + food.nutrition();
    }

    private static boolean isEligibleFood(ItemStack stack, boolean canEatVanilla, boolean needsHealth) {
        if (stack.isEmpty() || !stack.contains(DataComponentTypes.FOOD)) return false;
        if (stack.isOf(Items.CHORUS_FRUIT)
                || stack.isOf(Items.ROTTEN_FLESH)
                || stack.isOf(Items.SPIDER_EYE)
                || stack.isOf(Items.POISONOUS_POTATO)
                || stack.isOf(Items.PUFFERFISH)
                || stack.isOf(Items.CHICKEN)
                || stack.isOf(Items.SUSPICIOUS_STEW)) {
            return false;
        }
        var food = stack.get(DataComponentTypes.FOOD);
        if (food == null) return false;
        if (canEatVanilla) return true;
        return needsHealth && food.canAlwaysEat();
    }

    /**
     * Idle observation is presentation only: gaze and glances. It never moves the body; any movement goes through
     * the arbiter as an ordinary task, so it cannot race a pending or running skill. Not while stopped.
     */
    private void applyIdleObservation(Entry entry, NativePlayerBody body, long tick) {
        var bot = body.entity();
        if (bot == null || runnerBusy(entry) || entry.nativeWorkPending
                || (entry.activity != null && entry.activity.phase() != PaidActivitySession.Phase.IDLE)) {
            return;
        }
        if (entry.idleAnchorPos == null) {
            entry.idleAnchorPos = bot.getBlockPos().toImmutable();
        }

        // Hurt, it stops holding anyone's eye. What it says of the hurt, if anything, is its social lane's, from the
        // game's own record of what hurt it: blaming whoever stood nearby for a fall or a burn was no way to talk.
        if (bot.hurtTime > 0) {
            entry.lastGazeHumanId = null;
            bot.setSneaking(false);
            return;
        }

        var gaze = detectGazingHuman(entry, bot);
        var input = new IdleObservationController.IdleObservationInput(
                bot.getX(), bot.getY(), bot.getZ(),
                entry.idleAnchorPos.getX() + 0.5, entry.idleAnchorPos.getY(), entry.idleAnchorPos.getZ() + 0.5,
                entry.runtimeSettings.idleRadiusBlocks(),
                gaze,
                Optional.empty(),
                Optional.empty(),
                tick
        );
        var plan = IdleObservationController.evaluate(input);

        if (plan.subState() == IdleObservationController.IdleSubState.EYE_CONTACT_LOCKED) {
            entry.lastGazeHumanId = gaze.map(IdleObservationController.HumanGazeObservation::humanId).orElse(null);
            plan.lookAtEyeTarget().ifPresent(target -> {
                Vec3d eye = bot.getEyePos();
                Vec3d look = new Vec3d(target[0], target[1], target[2]).subtract(eye).normalize();
                float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
                float pitch = (float) Math.toDegrees(-Math.asin(Math.clamp(look.y, -1.0, 1.0)));
                bot.setYaw(yaw);
                bot.setPitch(pitch);
                bot.setHeadYaw(yaw);
            });
            bot.setSneaking(plan.shouldSneakPulse());
            return;
        }

        if (entry.lastGazeHumanId != null) {
            entry.lastGazeHumanId = null;
            bot.setSneaking(false);
            bot.setPitch(0.0f);
        }

        // Occasional idle glance around; purely a head turn.
        if (tick >= entry.idleNextWanderTick) {
            long seed = tick + bot.getId() * 31L;
            float glanceYaw = bot.getYaw() + (float) ((Math.abs(seed) % 60L) - 30.0);
            bot.setHeadYaw(glanceYaw);
            bot.setPitch((float) ((Math.abs(seed) % 20L) - 10.0));
            entry.idleNextWanderTick = tick + 30L + Math.abs(seed % 40L);
        }
    }

    private Optional<IdleObservationController.HumanGazeObservation> detectGazingHuman(Entry entry, ServerPlayerEntity bot) {
        var world = bot.getServerWorld();
        Vec3d botEyes = bot.getEyePos();
        for (ServerPlayerEntity human : world.getPlayers()) {
            if (human == bot || entries.containsKey(human.getUuid()) || human.isSpectator() || !human.isAlive()) {
                continue;
            }
            double distSq = human.squaredDistanceTo(bot);
            if (distSq > IdleObservationController.EYE_CONTACT_MAX_DISTANCE_SQ || distSq < 0.01) {
                continue;
            }
            if (!human.canSee(bot)) {
                continue;
            }
            Vec3d humanEyes = human.getEyePos();
            Vec3d toBot = botEyes.subtract(humanEyes).normalize();
            Vec3d humanLook = human.getRotationVec(1.0F).normalize();
            double dot = humanLook.dotProduct(toBot);
            if (dot >= IdleObservationController.EYE_CONTACT_DOT_THRESHOLD) {
                return Optional.of(new IdleObservationController.HumanGazeObservation(
                        human.getUuid(),
                        human.getName().getString(),
                        humanEyes.x, humanEyes.y, humanEyes.z,
                        dot,
                        distSq,
                        human.isSneaking()
                ));
            }
        }
        return Optional.empty();
    }

    CanonicalRoster.RuntimeSettings runtimeSettings(UUID botId) {
        Entry entry = entries.get(botId);
        return entry != null ? entry.runtimeSettings : CanonicalRoster.RuntimeSettings.defaultsForNewPlayer();
    }

    Optional<UUID> firstConnectedBotId() {
        for (Entry entry : entries.values()) {
            if (entry.nativeBody != null && entry.nativeBody.connected()) {
                return Optional.of(entry.data.id());
            }
        }
        return Optional.empty();
    }

    Optional<UUID> canonicalBotIdForControlSlot(int slot) {
        String name = switch (slot) {
            case 2 -> CanonicalRoster.KINDRED_NAME;
            case 4 -> CanonicalRoster.SOL_NAME;
            case 6 -> CanonicalRoster.VESP_NAME;
            default -> null;
        };
        if (name == null) return Optional.empty();
        for (Entry entry : entries.values()) {
            if (name.equalsIgnoreCase(entry.data.displayName()) && entry.nativeBody != null && entry.nativeBody.connected()) {
                return Optional.of(entry.data.id());
            }
        }
        return Optional.empty();
    }

    private boolean mayAdjust(ServerCommandSource source, Entry entry) {
        if (entry.nativeState != null) return nativeMayControl(source, entry);
        UUID player = source.getEntity() instanceof ServerPlayerEntity entity ? entity.getUuid() : null;
        return Authorization.mayControl(new Actor(player, source.hasPermissionLevel(2)), entry.snapshot());
    }

    // ----- Player panel (keybind GUI) -----

    record PanelBot(UUID id, String name, boolean connected, String phase, boolean stopped, boolean busy,
                    String playstyle, String collaboration, dev.kindred.core.survival.SurvivalGoal goal, String status,
                    String goalProgress, float health, int food, BlockPos position, String dimension, String task,
                    UUID bodyId, String skin) { }

    /** Native players this actor may control, as plain values for the panel. */
    List<PanelBot> panelBots(Actor actor) {
        requireThread();
        if (actor == null || !ready || !running) return List.of();
        var bots = new ArrayList<PanelBot>();
        for (Entry entry : entries.values()) {
            if (entry.nativeState == null || entry.snapshot().lifecycle() == LifecycleState.REMOVED
                    || !entry.nativeState.access().mayControl(actor)) continue;
            boolean connected = entry.nativeBody != null && entry.nativeBody.connected();
            var player = connected ? entry.nativeBody.entity() : null;
            boolean busy = entry.runner != null && runnerBusy(entry);
            bots.add(new PanelBot(entry.data.id(), entry.data.displayName(), connected, entry.nativeState.phase().name(),
                    entry.snapshot().stopLatched(), busy, entry.runtimeSettings.playstyleProfile().name(),
                    entry.runtimeSettings.collaborationMode().name(), entry.goal, entry.explanation(), entry.goalProgress,
                    player != null ? player.getHealth() : 0f, player != null ? player.getHungerManager().getFoodLevel() : 0,
                    player != null ? player.getBlockPos().toImmutable() : null,
                    player != null ? player.getServerWorld().getRegistryKey().getValue().getPath() : entry.data.dimension(),
                    busy ? entry.skillId : "", entry.data.entityId(), KindredSkins.choiceOf(entry.data.entityId())));
        }
        bots.sort(java.util.Comparator.comparing(PanelBot::name, String.CASE_INSENSITIVE_ORDER));
        return bots;
    }

    boolean worldEditsEnabled() {
        return edits.autonomousEditsEnabled() && edits.protectionCompatibilityKnown();
    }

    /** Records the server's standing edit policy (decision D4) in edits.properties; operators only. */
    int setWorldEdits(ServerCommandSource source, boolean enabled) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading saved records.");
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator may change the server's AI edit policy.");
        EditPolicy.ServerPolicy next = new EditPolicy.ServerPolicy(enabled, true);
        edits = next; // Revocation applies to the next mutation check immediately.
        Path file = metadataDirectory.resolve("edits.properties");
        String content = policyFile(enabled, dragonFight, bases);
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(metadataDirectory);
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((ignored, failure) -> server.execute(() -> {
            if (failure != null) {
                KindredMod.LOGGER.error("Kindred edit policy could not be saved", failure);
                reject(source, "The edit policy applies now but could not be saved; it resets on restart.");
            }
        }));
        feedback(source, enabled ? "AI world edits enabled: AI players may mine and place blocks under their tasks' checks."
                : "AI world edits disabled: AI players will not break or place blocks.");
        return 1;
    }

    private static String policyFile(boolean edits, boolean dragon, boolean bases) {
        return "# Kindred server edit policy (decision D4). autonomousEdits lets AI players break and place blocks;\n"
                + "# dragonFight lets them fight the Ender Dragon; bases lets them choose, site and build their own bases.\n"
                + "autonomousEdits=" + edits + "\nprotectionKnown=true\ndragonFight=" + dragon + "\nbases=" + bases + "\n";
    }

    boolean dragonFightAllowed() { return dragonFight; }

    /** Records whether AI players may fight the Ender Dragon (E-39a), in edits.properties; operators only. */
    int setDragonFight(ServerCommandSource source, boolean allowed) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading saved records.");
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator may change whether AI players fight the dragon.");
        dragonFight = allowed;
        Path file = metadataDirectory.resolve("edits.properties");
        String content = policyFile(edits.autonomousEditsEnabled(), allowed, bases);
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(metadataDirectory);
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((ignored, failure) -> server.execute(() -> {
            if (failure != null) {
                KindredMod.LOGGER.error("Kindred dragon fight setting could not be saved", failure);
                reject(source, "The setting applies now but could not be saved; it resets on restart.");
            }
        }));
        feedback(source, allowed ? "The dragon fight is on: an AI player with the goal may go into the End and fight the Ender Dragon."
                : "The dragon fight is off: AI players stop at the End portal.");
        return 1;
    }

    boolean basesAllowed() { return bases; }

    /**
     * Records whether AI players may choose, site and build their own bases as they go (M-01c), in edits.properties;
     * operators only. Their builds are still world edits: AI world edits must be on as well.
     */
    int setBases(ServerCommandSource source, boolean allowed) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading saved records.");
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator may change whether AI players build their own bases.");
        bases = allowed;
        Path file = metadataDirectory.resolve("edits.properties");
        String content = policyFile(edits.autonomousEditsEnabled(), dragonFight, allowed);
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(metadataDirectory);
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((ignored, failure) -> server.execute(() -> {
            if (failure != null) {
                KindredMod.LOGGER.error("Kindred bases setting could not be saved", failure);
                reject(source, "The setting applies now but could not be saved; it resets on restart.");
            }
        }));
        feedback(source, allowed ? "Bases are on: an AI player with no goal of its own chooses, sites and builds its base as it goes, in three stages."
                : "Bases are off: AI players build only what they are asked to.");
        return 1;
    }

    /** Sets a standing goal and starts autonomous work toward it. */
    int startGoal(ServerCommandSource source, UUID id, dev.kindred.core.survival.SurvivalGoal goal) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (goal != dev.kindred.core.survival.SurvivalGoal.NONE && !worldEditsEnabled()) {
            return reject(source, "AI world edits are disabled on this server; an operator must enable them first.");
        }
        if (goal == dev.kindred.core.survival.SurvivalGoal.DRAGON && !dragonFight) {
            return reject(source, "The dragon fight is off on this server; an operator must turn it on first (/aiplayer dragonfight on).");
        }
        beginGoal(entry, goal);
        if (entry.snapshot().stopLatched()) return nativeLifecycle(source, "resume", id);
        feedback(source, entry.data.displayName() + ": " + goal.label() + ".");
        return 1;
    }

    /** The goal set and work toward it begun at once, whoever asked: a command, or a race starting (M-03a). */
    private void beginGoal(Entry entry, dev.kindred.core.survival.SurvivalGoal goal) {
        entry.goal = goal;
        // Portal travel is the goal's to allow, a step at a time (campaignCandidates); a new goal starts without it.
        if (entry.nativeBody != null) entry.nativeBody.setPortalTravelAuthorized(false);
        entry.goalProgress = goal == dev.kindred.core.survival.SurvivalGoal.NONE ? "" : "Starting: " + goal.label();
        if (entry.runtimeSettings.playstyleProfile().isIdleObserving()) {
            entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(PlaystyleProfile.OPTIMAL_SURVIVAL);
        }
        entry.manualHoldUntilTick = Long.MIN_VALUE;
        entry.cachedRoutineCandidates = List.of();
        entry.routineRefreshPending = true;
    }

    /** Hand a strategist's advice to a native player's running goal, as the strategist bridge does with a reply (F-19b). */
    boolean adviseGoal(UUID id, dev.kindred.core.survival.StrategyAdvice advice) {
        requireThread();
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.goal == dev.kindred.core.survival.SurvivalGoal.NONE) return false;
        if (entry.campaign == null) entry.campaign = new PortalRunCampaign();
        entry.campaign.advise(advice, server.getTicks());
        return true;
    }

    /** Daily activity cap for a native player; operators only. */
    int nativeSetCap(ServerCommandSource source, UUID id, int hours) {
        requireThread();
        if (!source.hasPermissionLevel(2)) return reject(source, "Only an operator may modify daily activity caps.");
        Entry entry = id != null ? entries.get(id) : null;
        if (entry == null || entry.nativeState == null || !nativeMayControl(source, entry)) {
            return reject(source, "Native player unavailable or administrative access denied.");
        }
        if (hours < 1 || hours > 24) return reject(source, "The daily cap is 1 to 24 hours.");
        long capMs = (long) hours * 3600 * 1000;
        store.limit(entry.data.id()).whenComplete((limit, failure) -> server.execute(() -> {
            if (failure != null || limit == null) { reject(source, "Could not read the current activity limit."); return; }
            ActivityLimit next = new ActivityLimit(limit.companionId(), capMs, limit.offlineWork(), limit.timezone(), limit.revision() + 1);
            store.saveLimit(next, limit.revision()).whenComplete((saved, saveFailure) -> server.execute(() -> {
                if (saveFailure != null) reject(source, "Failed to save the daily cap.");
                else feedback(source, entry.data.displayName() + " daily activity cap set to " + hours + " hours.");
            }));
        }));
        return 1;
    }

    /** Current daily caps in milliseconds, read off-thread; missing entries mean the read failed. */
    CompletableFuture<Map<UUID, Long>> nativeCaps(java.util.Collection<UUID> ids) {
        requireThread();
        SqliteCompanionStore database = store;
        if (database == null) return CompletableFuture.completedFuture(Map.of());
        Map<UUID, Long> caps = new java.util.concurrent.ConcurrentHashMap<>();
        CompletableFuture<?>[] reads = ids.stream().map(id -> database.limit(id)
                .thenAccept(limit -> caps.put(id, limit.capMillis()))
                .exceptionally(ignored -> null)).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(reads).thenApply(ignored -> Map.copyOf(caps));
    }

    int openControlGui(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Control deck requires a player executor."));
            return 0;
        }
        player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new KindredControlScreenHandler(syncId, inv, this, player),
                Text.literal("Kindred Control Deck")));
        return 1;
    }

    int togglePlaystyleForBot(ServerCommandSource source, UUID botId) {
        Entry entry = entries.get(botId);
        if (entry == null) return 0;
        if (!mayAdjust(source, entry)) return reject(source, "Only an operator or this player's controller may change its settings.");
        PlaystyleProfile current = entry.runtimeSettings.playstyleProfile();
        PlaystyleProfile next = current == PlaystyleProfile.SPEEDRUN_ANY_PERCENT
                ? PlaystyleProfile.OPTIMAL_SURVIVAL
                : PlaystyleProfile.SPEEDRUN_ANY_PERCENT;
        if (current.isIdleObserving()) {
            next = PlaystyleProfile.OPTIMAL_SURVIVAL;
        }
        entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(next);
        source.sendFeedback(() -> Text.literal("Playstyle for " + entry.data.displayName() + ": "
                + entry.runtimeSettings.playstyleProfile()), false);
        return 1;
    }

    int toggleCollaborationForBot(ServerCommandSource source, UUID botId) {
        Entry entry = entries.get(botId);
        if (entry == null) return 0;
        if (!mayAdjust(source, entry)) return reject(source, "Only an operator or this player's controller may change its settings.");
        entry.runtimeSettings = entry.runtimeSettings.toggleCollaboration();
        if (entry.runtimeSettings.collaborationMode() == CollaborationMode.SOLO_INDEPENDENT) {
            teamCoordinator.handlePlayerStop(botId);
        }
        source.sendFeedback(() -> Text.literal("Collaboration for " + entry.data.displayName() + ": "
                + entry.runtimeSettings.collaborationMode()), false);
        return 1;
    }

    int adjustIdleRadiusForBot(ServerCommandSource source, UUID botId, int delta) {
        Entry entry = entries.get(botId);
        if (entry == null) return 0;
        if (!mayAdjust(source, entry)) return reject(source, "Only an operator or this player's controller may change its settings.");
        entry.runtimeSettings = entry.runtimeSettings.withIdleRadiusBlocks(entry.runtimeSettings.idleRadiusBlocks() + delta);
        source.sendFeedback(() -> Text.literal("Idle radius for " + entry.data.displayName() + ": "
                + entry.runtimeSettings.idleRadiusBlocks() + " blocks"), false);
        return 1;
    }

    int setPlaystyleMode(ServerCommandSource source, UUID botId, PlaystyleProfile profile) {
        Entry entry = botId != null ? entries.get(botId) : null;
        if (entry == null && botId == null) {
            entry = firstConnectedBotId().map(entries::get).orElse(null);
        }
        if (entry == null) {
            source.sendError(Text.literal("No AI player found for mode change."));
            return 0;
        }
        if (!mayAdjust(source, entry)) return reject(source, "Only an operator or this player's controller may change its settings.");
        entry.runtimeSettings = entry.runtimeSettings.withPlaystyle(profile);
        String name = entry.data.displayName();
        source.sendFeedback(() -> Text.literal(name + " playstyle: " + profile), false);
        return 1;
    }

    /**
     * Dresses an AI player in a skin from the skins folder, or its own again for {@code default}. Only an operator
     * or the player's controller may, as for its other settings; the skin is presentation only.
     */
    int setSkin(ServerCommandSource source, UUID botId, String key) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading saved records.");
        Entry entry = botId != null ? entries.get(botId) : null;
        if (entry == null || entry.nativeState == null || entry.data.entityId() == null) {
            return reject(source, "No native AI player found for a skin change.");
        }
        if (!mayAdjust(source, entry)) return reject(source, "Only an operator or this player's controller may change its skin.");
        KindredSkins.choose(server, entry.data.entityId(), entry.data.displayName(), key,
                message -> source.sendFeedback(() -> Text.literal(message), false),
                message -> source.sendError(Text.literal(message)));
        return 1;
    }

    UUID findBotIdByName(String name) {
        requireThread();
        if (name == null || name.isBlank()) return null;
        String clean = name.trim();
        for (Entry entry : entries.values()) {
            if (entry.data.displayName().equalsIgnoreCase(clean)
                    && entry.nativeBody != null && entry.nativeBody.connected()) {
                return entry.data.id();
            }
        }
        for (Entry entry : entries.values()) {
            if (entry.data.displayName().equalsIgnoreCase(clean)
                    && entry.snapshot().lifecycle() != LifecycleState.REMOVED) {
                return entry.data.id();
            }
        }
        return null;
    }

    List<String> availableBotNames() {
        requireThread();
        return entries.values().stream()
                .filter(e -> e.snapshot().lifecycle() != LifecycleState.REMOVED)
                .map(e -> e.data.displayName())
                .distinct()
                .toList();
    }

    UUID resolveBotId(String target) {
        requireThread();
        if (target == null || target.isBlank()) return null;
        UUID byName = findBotIdByName(target);
        if (byName != null) return byName;
        try {
            return UUID.fromString(target.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    UUID defaultBotId() {
        requireThread();
        var connected = entries.values().stream()
                .filter(e -> e.nativeBody != null && e.nativeBody.connected())
                .toList();
        if (connected.size() == 1) {
            return connected.get(0).data.id();
        }
        var active = entries.values().stream()
                .filter(e -> e.snapshot().lifecycle() != LifecycleState.REMOVED)
                .toList();
        if (active.size() == 1) {
            return active.get(0).data.id();
        }
        return null;
    }

    int spawnCanonicalRoster(ServerCommandSource source, String specificName) {
        requireThread();
        if (!ready || !running) return reject(source, "Kindred is loading saved records.");
        if (specificName != null && !specificName.isBlank()) {
            var preset = dev.kindred.core.CanonicalRoster.TRIO.stream()
                    .filter(p -> p.name().equalsIgnoreCase(specificName.trim()))
                    .findFirst()
                    .orElse(null);
            if (preset == null) {
                return reject(source, "Unknown canonical player '" + specificName + "'. Use Kindred, Sol, or Vesp.");
            }
            // One player of each name, as with the whole roster: a second "Kindred" would be another player with the
            // same name (a staged run asked for one while the first was alive in the Overworld).
            // A saved one not in the world is reconnected (main's 4cb2b33).
            var existing = rosterEntry(preset.name());
            if (existing != null) {
                if (existing.nativeBody != null && existing.nativeBody.connected()) {
                    feedback(source, preset.name() + " is already active in the world.");
                    return 1;
                }
                feedback(source, "Reconnecting saved " + preset.name() + "...");
                connectNative(source, existing, false);
                return 1;
            }
            return createNative(source, preset.name());
        }
        int spawned = 0;
        for (var preset : dev.kindred.core.CanonicalRoster.TRIO) {
            var existing = rosterEntry(preset.name());
            if (existing == null) {
                createNative(source, preset.name());
                spawned++;
            } else if (existing.nativeBody == null || !existing.nativeBody.connected()) {
                feedback(source, "Reconnecting saved " + preset.name() + "...");
                connectNative(source, existing, false);
                spawned++;
            }
        }
        if (spawned == 0) {
            feedback(source, "All canonical players (Kindred, Sol, Vesp) are already active in the world.");
        }
        return 1;
    }

    private Entry rosterEntry(String name) {
        return entries.values().stream().filter(e -> e.data.displayName().equalsIgnoreCase(name)
                && e.snapshot().lifecycle() != LifecycleState.REMOVED).findFirst().orElse(null);
    }

    private static final class NativeBody implements BodyAdapter {
        private final CompanionEntity entity;
        private NativeBody(CompanionEntity entity) { this.entity = entity; }
        @Override public void start(ActionLease lease) { entity.haltControls(); }
        @Override public void cancel(ActionLease lease) {
            entity.haltControls();
        }
        @Override public boolean isSettled(ActionLease lease) {
            return entity.getNavigation().isIdle() && !entity.getMoveControl().isMoving() && !entity.isUsingItem();
        }
    }
}
