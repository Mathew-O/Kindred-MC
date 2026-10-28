package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActivityLimit;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.scheduler.AccountingPeriod;
import dev.kindred.scheduler.BudgetState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * F-11e: native requests charged to the period F-11d chooses; a zone edit and a clock behind the last period.
 * F-11f: activity hours; a request outside them waits, and a running skill stops at their end.
 */
public final class NativeAccountingGameTests implements FabricGameTest {
    private static final String KIRITIMATI = "Pacific/Kiritimati";

    private record Kept(ActivityLimit limit, AccountingPeriod latest, BudgetState budget) { }
    private record Behind(Optional<BudgetState> today, Optional<AccountingPeriod> latest) { }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 420)
    public void aZoneEditKeepsTheCurrentPeriodAndItsSpentTime(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        long[] charged = new long[1];
        CompletableFuture<?>[] read = new CompletableFuture<?>[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ledger"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "Wait requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_wait"), "First wait charged and settled: " + activity.work());
                    charged[0] = activity.chargedMillis();
                    context.assertTrue(charged[0] > 0, "Time was charged to today's period");
                    context.assertTrue(f.runtime.setLimitTimezone(f.source.withLevel(0), f.id, KIRITIMATI) == 0, "Only an operator edits the zone");
                    context.assertTrue(f.runtime.setLimitTimezone(f.source, f.id, "Mars/Olympus_Mons") == 0, "An unknown zone is refused");
                    context.assertTrue(f.runtime.setLimitTimezone(f.source, f.id, KIRITIMATI) == 1, "A native player's zone may be edited");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "Wait requested after the edit"))
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_wait"), "Second wait settled: " + activity.work());
                    // Charged on top of the first: the edit kept today's period rather than opening a fresh one.
                    context.assertTrue(activity.chargedMillis() > charged[0], "Spent time kept across the zone edit: " + activity.chargedMillis() + " after " + charged[0]);
                    charged[0] = activity.chargedMillis();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Writer closed"))
                .createAndAdd(() -> read[0] = withStore(f, store -> store.limit(f.id).thenCompose(limit ->
                        store.latestPeriod(f.id).thenCompose(latest -> store.findBudget(f.id, latest.orElseThrow().startInclusive())
                                .thenApply(budget -> new Kept(limit, latest.orElseThrow(), budget.orElseThrow()))))))
                .createAndAdd(() -> {
                    context.assertTrue(read[0].isDone() && !read[0].isCompletedExceptionally(), "Saved state read back");
                    Kept saved = (Kept) read[0].join();
                    context.assertTrue(saved.limit().timezone().equals(KIRITIMATI), "The new zone is saved");
                    context.assertTrue(saved.latest().zone().getId().equals("UTC"), "Today's period is still the one kept in UTC: " + saved.latest());
                    context.assertTrue(saved.budget().chargedMillis() == charged[0], "Both waits charged to it");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 420)
    public void aClockBehindTheLastPeriodWaitsWithoutAFault(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        AccountingPeriod tomorrow = AccountingPeriod.on(LocalDate.now(ZoneOffset.UTC).plusDays(1), ZoneOffset.UTC);
        AccountingPeriod today = AccountingPeriod.on(LocalDate.now(ZoneOffset.UTC), ZoneOffset.UTC);
        CompletableFuture<?>[] io = new CompletableFuture<?>[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Behind"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Writer closed"))
                // A period already kept for tomorrow: the state a wall clock stepping back over midnight leaves.
                .createAndAdd(() -> io[0] = withStore(f, store -> store.openBudget(f.id, tomorrow)))
                .createAndAdd(() -> context.assertTrue(io[0].isDone() && !io[0].isCompletedExceptionally(), "Later period saved"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Reopened metadata ready"))
                .createAndAdd(() -> f.runtime.nativeLifecycle(f.source, "connect", f.id))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "Native body connected"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "Wait requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().startsWith("No activity allowance until") && activity.work().contains("clock is behind"),
                            "No period is charged, and the player is told when one opens: " + activity.work());
                    context.assertTrue(!f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Not halted for reconciliation");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 0, "Refused at once until then, without asking the store again");
                    context.assertTrue(f.runtime.setLimitTimezone(f.source, f.id, "Asia/Tokyo") == 1, "Zone edit accepted");
                })
                // The edit clears the wait; the store is asked again and the clock is still behind.
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "Asked again after the edit"))
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().startsWith("No activity allowance until") && !f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(),
                            "Still no period, still no fault: " + activity.work());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Final cleanup"))
                .createAndAdd(() -> io[1] = withStore(f, store -> store.findBudget(f.id, today.startInclusive()).thenCompose(written ->
                        store.latestPeriod(f.id).thenApply(latest -> new Behind(written, latest)))))
                .createAndAdd(() -> {
                    context.assertTrue(io[1].isDone() && !io[1].isCompletedExceptionally(), "Saved state read back");
                    Behind saved = (Behind) io[1].join();
                    context.assertTrue(saved.today().isEmpty(), "No earlier period was opened behind the later one");
                    context.assertTrue(saved.latest().equals(Optional.of(tomorrow)), "The later period is still the latest");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 420)
    public void aRequestOutsideItsHoursWaitsUntilTheyOpen(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        // Hours that open two hours from now (UTC, the fixture's zone): now is outside them, even across midnight.
        LocalTime later = LocalTime.now(ZoneOffset.UTC).plusHours(2).withNano(0);
        var hours = new ActivityLimit.Window(later, later.plusHours(1));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hours"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.setLimitWindow(f.source.withLevel(0), f.id, Optional.of(hours)) == 0, "Only an operator sets hours");
                    context.assertTrue(f.runtime.setLimitWindow(f.source, f.id, "25.00", "26") == 0, "Hours that are no time of day are refused");
                    context.assertTrue(f.runtime.setLimitWindow(f.source, f.id, "9", "9.00") == 0, "Hours that begin as they end are refused");
                    context.assertTrue(f.runtime.setLimitWindow(f.source, f.id, Optional.of(hours)) == 1, "Hours set");
                })
                .createAndAdd(() -> {
                    var limit = f.runtime.activityLimitForTest(f.id);
                    context.assertTrue(limit != null && limit.window().equals(Optional.of(hours)), "Hours saved");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "Wait requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().startsWith("Outside its activity hours") && activity.work().contains("they open"),
                            "Outside its hours the player is told when they open: " + activity.work());
                    context.assertTrue(!f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Not halted");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 0, "Refused at once until then");
                    context.assertTrue(f.runtime.setLimitWindow(f.source, f.id, null, null) == 1, "Hours cleared");
                })
                .createAndAdd(() -> {
                    var limit = f.runtime.activityLimitForTest(f.id);
                    context.assertTrue(limit != null && limit.window().isEmpty(), "All day saved");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "Wait requested all day");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_wait"), "All day, the wait runs: " + activity.work());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A break longer than the hours left: it stops at their end rather than running on, its block left whole. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "realTimeHours", tickLimit = 800)
    public void aRunningSkillStopsWhenItsHoursEnd(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos obsidian = context.getAbsolutePos(new BlockPos(5, 2, 3));
        long[] started = new long[1];
        long[] lastTick = new long[1];
        double[] ran = new double[1];
        boolean[] breaking = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Closing"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(obsidian, Blocks.OBSIDIAN.getDefaultState());
                    f.player().getInventory().setStack(15, new ItemStack(Items.DIAMOND_PICKAXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    // Hours that began a minute ago and end four seconds from now; the break takes 9.4 seconds.
                    LocalTime now = LocalTime.now(ZoneOffset.UTC);
                    var closing = new ActivityLimit.Window(now.minusMinutes(1), now.plusSeconds(4));
                    context.assertTrue(f.runtime.setLimitWindow(f.source, f.id, Optional.of(closing)) == 1, "Hours set");
                })
                .createAndAdd(() -> {
                    var limit = f.runtime.activityLimitForTest(f.id);
                    context.assertTrue(limit != null && limit.window().isPresent(), "Hours saved");
                    started[0] = lastTick[0] = System.nanoTime();
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, obsidian) == 1, "Obsidian break requested");
                })
                .createAndAdd(() -> {
                    // No faster than a live server: each tick takes at least 50 ms of wall-clock time. Parked again after
                    // an early return: every server.execute from the IO thread unparks the server thread.
                    long until = lastTick[0] + 50_000_000L;
                    for (long left; (left = until - System.nanoTime()) > 0; ) java.util.concurrent.locks.LockSupport.parkNanos(left);
                    lastTick[0] = System.nanoTime();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (activity.work().startsWith("Running native_break")) breaking[0] = true;
                    context.assertTrue(breaking[0] && activity.phase().equals("IDLE") && !activity.work().startsWith("Running"), "Broke, then settled: " + activity);
                    ran[0] = (System.nanoTime() - started[0]) / 1e9;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(!activity.work().startsWith("Completed"), "The break did not run on past the hours: " + activity.work());
                    context.assertTrue(ran[0] < 8, "It stopped well before a full break: " + ran[0] + " s");
                    context.assertTrue(activity.chargedMillis() <= 5_000, "No more charged than the hours left: " + activity.chargedMillis() + " ms");
                    context.assertTrue(context.getWorld().getBlockState(obsidian).isOf(Blocks.OBSIDIAN), "The obsidian is left whole");
                    context.assertTrue(!f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Not halted");
                    // Asked again once the hours are over: dispatched and refused, or refused at once if already refused.
                    f.runtime.nativeBreak(f.source, f.id, obsidian);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().startsWith("Outside its activity hours"), "Refused outside its hours: " + activity.work());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Opens the fixture's store while its runtime is stopped, applies one piece of work, and closes it again. */
    private static <T> CompletableFuture<T> withStore(NativePaidSkillsGameTests.Fixture f, Function<SqliteCompanionStore, CompletableFuture<T>> work) {
        return SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store ->
                work.apply(store).handle((value, error) -> store.closeAsync().thenApply(ignored -> {
                    if (error != null) throw new CompletionException(error);
                    return value;
                })).thenCompose(future -> future));
    }
}
