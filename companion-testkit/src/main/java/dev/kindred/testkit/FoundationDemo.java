package dev.kindred.testkit;

import dev.kindred.core.ActionArbiter;
import dev.kindred.core.ActionLease;
import dev.kindred.core.Actor;
import dev.kindred.core.BodyAdapter;
import dev.kindred.core.Companion;
import dev.kindred.core.ControlRejectedException;
import dev.kindred.core.ProposalFence;
import dev.kindred.persistence.CreationRequest;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.persistence.StoredCompanion;
import dev.kindred.scheduler.AccountingPeriod;
import dev.kindred.scheduler.BudgetState;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In plain terms: a quick self-check you can run without Minecraft: it creates a player in a throwaway
 * database, sets aside play time, and checks that stopping a player really stops it and that after a restart
 * the player is the same one, still stopped, with its play time counted correctly.
 *
 * Technical: Executable contract demonstration, deliberately without a Minecraft world or inference provider.
 */
public final class FoundationDemo {
    static final Instant NOW = Instant.parse("2026-10-13T18:00:00Z");
    static final AccountingPeriod PERIOD = AccountingPeriod.containing(NOW, ZoneId.of("America/New_York"));
    static final CreationRequest REQUEST = new CreationRequest(id("creation"), id("owner"), "Kindred",
            id("test-world-identity"), "minecraft:overworld");

    private FoundationDemo() { }

    /** Runs the self-check in a temporary database. */
    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: FoundationDemo [new-demo-database-path]");
        }
        Path database = args.length == 1 ? Path.of(args[0]).toAbsolutePath()
                : Files.createTempDirectory("kindred-contract-demo-").resolve("companions.sqlite");
        run(database, System.out);
    }

    /** All waits here are on a standalone CLI thread, never the server tick thread. */
    public static DemoResult run(Path database, PrintStream output) throws IOException {
        Path absoluteDatabase = database.toAbsolutePath().normalize();
        if (Files.exists(absoluteDatabase)) {
            throw new IllegalArgumentException("Choose a new demo database path; existing files are left untouched: "
                    + absoluteDatabase);
        }
        output.println("Kindred MC contract demo; no Minecraft world/no native acceptance proof.");
        output.println("Real SQLite metadata; simulated body and fixed clock. No world items are created or changed.");
        output.println("Demo database: " + absoluteDatabase);

        UUID companionId;
        ProposalFence oldProposal;
        SqliteCompanionStore store = SqliteCompanionStore.open(absoluteDatabase, 100).join();
        try {
            StoredCompanion created = store.create(REQUEST).join();
            companionId = created.companion().id();
            check(store.create(REQUEST).join().companion().id().equals(companionId),
                    "Creation operation must return the same identity");
            CreationRequest repeatedCommand = new CreationRequest(id("second-creation-command"), REQUEST.ownerId(),
                    REQUEST.displayName(), REQUEST.worldId(), REQUEST.dimension());
            check(store.create(repeatedCommand).join().companion().id().equals(companionId),
                    "Default owner quota must return the existing companion");
            output.println("PASS: creation retry and repeated owner command preserve one companion identity.");

            BudgetState empty = store.openBudget(companionId, PERIOD).join();
            BudgetState reserved = empty.reserve(id("reservation"), Duration.ofSeconds(5), Duration.ofMinutes(2),
                    NOW, PERIOD.endExclusive());
            store.saveBudget(reserved, empty.sequence()).join();
            check(reserved.outstanding().orElseThrow().reservedMillis() == 5_000,
                    "Five seconds must be durably prepaid before the simulated action");

            SimulatedBody body = new SimulatedBody();
            ActionArbiter arbiter = new ActionArbiter(created.companion(), id("session-before-restart"), body,
                    Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L, Duration.ofMillis(500));
            ActionLease lease = arbiter.acquire(Actor.player(REQUEST.ownerId()), id("task"), id("action"),
                    Duration.ofSeconds(5));
            Companion beforeUnauthorizedStop = arbiter.snapshot();
            boolean unauthorizedRejected = false;
            try {
                arbiter.stop(Actor.player(id("another-player")));
            } catch (SecurityException expected) {
                unauthorizedRejected = true;
            }
            check(unauthorizedRejected && arbiter.snapshot().equals(beforeUnauthorizedStop)
                            && arbiter.isCurrent(lease),
                    "Unauthorized stop must have no side effects");
            output.println("PASS: another player's stop is rejected without changing body authority.");

            oldProposal = new ProposalFence(arbiter.sessionEpoch(), arbiter.snapshot().intentRevision(),
                    arbiter.snapshot().taskRevision(), NOW.plusSeconds(15));
            check(arbiter.accepts(oldProposal), "Current proposal should pass its fence before stop");
            AtomicInteger callbacks = new AtomicInteger();
            check(arbiter.executeIfCurrent(lease, callbacks::incrementAndGet),
                    "Current simulated callback should pass before stop");
            arbiter.stop(Actor.player(REQUEST.ownerId()));
            check(body.starts == 1 && body.cancellations == 1, "Hard stop must synchronously cancel the body");
            check(!arbiter.executeIfCurrent(lease, callbacks::incrementAndGet) && callbacks.get() == 1,
                    "A stale callback cannot execute after stop");
            check(!arbiter.accepts(oldProposal), "Pending proposal must be invalid after stop");
            requireStopped(arbiter);
            store.save(arbiter.snapshot(), created.revision()).join();
            output.println("PASS: owner stop cancels immediately; stale callback/proposal and autonomous restart are blocked.");

            output.println("Restart simulation: close SQLite with the five-second reservation intentionally unresolved.");
            output.println("This simulates a crash accounting boundary; it does not crash Minecraft or perform real work.");
        } finally {
            store.closeAsync().join();
        }

        SqliteCompanionStore reopened = SqliteCompanionStore.open(absoluteDatabase, 100).join();
        try {
            StoredCompanion restored = reopened.create(REQUEST).join();
            check(restored.companion().id().equals(companionId), "Restart must preserve companion identity");
            check(restored.companion().stopLatched(), "Restart must preserve explicit stop");
            BudgetState recovered = reopened.findBudget(companionId, PERIOD.startInclusive()).join().orElseThrow();
            check(recovered.chargedMillis() == 5_000 && recovered.observedActiveMillis() == 0
                            && recovered.outstanding().isEmpty(),
                    "Startup must conservatively charge the unresolved slice without inventing observed activity");
            ActionArbiter restartedArbiter = new ActionArbiter(restored.companion(), id("session-after-restart"),
                    new SimulatedBody(), Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC), () -> 0L,
                    Duration.ofMillis(500));
            requireStopped(restartedArbiter);
            check(!restartedArbiter.accepts(oldProposal), "A previous session proposal cannot be reused");
            output.println("PASS: reopening preserves identity and the owner stop latch.");
            output.println("PASS: charged allowance = 5000 ms; observed activity = 0 ms; remaining two-minute allowance = "
                    + recovered.remainingAllowance(Duration.ofMinutes(2)).toMillis() + " ms.");
            output.println("Contract demo passed. Native world behavior and release acceptance remain unverified.");
            return new DemoResult(companionId, absoluteDatabase, recovered.chargedMillis(), recovered.observedActiveMillis());
        } finally {
            reopened.closeAsync().join();
        }
    }

    public record DemoResult(UUID companionId, Path database, long chargedMillis, long observedActiveMillis) { }

    private static void requireStopped(ActionArbiter arbiter) {
        try {
            arbiter.acquireAutonomous(id("forbidden-autonomous-task"), id("forbidden-autonomous-action"), Duration.ofSeconds(1));
            throw new IllegalStateException("A stopped companion regained authority");
        } catch (ControlRejectedException expected) {
            check(expected.reason() == ControlRejectedException.Reason.STOPPED,
                    "Autonomous work must be blocked specifically by the stop latch");
        }
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new IllegalStateException("Contract demo failed: " + failure);
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("kindred-contract-demo:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static final class SimulatedBody implements BodyAdapter {
        private int starts;
        private int cancellations;

        @Override public void start(ActionLease lease) { starts++; }
        @Override public void cancel(ActionLease lease) { cancellations++; }
        @Override public boolean isSettled(ActionLease lease) { return cancellations > 0; }
    }
}
