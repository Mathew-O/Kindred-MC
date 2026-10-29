package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActivityLimit;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Real runtime, native food and durable accounting. Resources are fixtures, not earned survival. */
public final class NativePaidSkillsGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 380)
    public void nativeEatingSettlesAndSpentAllowanceSurvivesReopen(TestContext context) {
        Fixture f = new Fixture(context);
        long[] charged = new long[1];
        CompletableFuture<?>[] maintenance = new CompletableFuture<?>[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hungry"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getHungerManager().setFoodLevel(8);
                    f.player().getHungerManager().setSaturationLevel(0);
                    f.player().getInventory().setStack(2, new ItemStack(Items.APPLE, 2));
                    context.assertTrue(f.runtime.nativeSkill(f.source.withLevel(0), f.id, "native_eat", 2) == 0, "Foreign source denied");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_eat", 2) == 1, "Food requested");
                    context.assertTrue(!f.player().isUsingItem() && f.player().getInventory().getStack(2).getCount() == 2, "No item use before asynchronous paid acknowledgement");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 0, "Pending admission is exclusive");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_eat"), "Verified food completion and durable settlement");
                    context.assertTrue(f.player().getInventory().getStack(2).getCount() == 1 && f.player().getHungerManager().getFoodLevel() >= 12, "Exactly one owned apple consumed by native mechanics");
                    context.assertTrue(activity.chargedMillis() > 0 && activity.chargedMillis() <= 5000
                            && activity.observedMillis() == activity.chargedMillis(), "Actual activity charged within one prepaid slice");
                    charged[0] = activity.chargedMillis();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Writer closes after settlement"))
                .createAndAdd(() -> {
                    // The runtime writer is closed; use the same store implementation to lower the fixture cap.
                    maintenance[0] = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store ->
                            store.limit(f.id).thenCompose(limit -> store.saveLimit(new ActivityLimit(f.id, 1, false, limit.timezone(), limit.revision() + 1), limit.revision()))
                                    .handle((saved, error) -> store.closeAsync().thenApply(ignored -> {
                                        if (error != null) throw new java.util.concurrent.CompletionException(error);
                                        return saved;
                                    })).thenCompose(future -> future));
                })
                .createAndAdd(() -> context.assertTrue(maintenance[0].isDone() && !maintenance[0].isCompletedExceptionally(), "Fixture cap persisted without a concurrent writer"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Reopened metadata ready"))
                .createAndAdd(() -> f.runtime.nativeLifecycle(f.source, "connect", f.id))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "Native save restored"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.player().getInventory().getStack(2).getCount() == 1, "Consumed food remains consumed across reopen");
                    f.runtime.nativeSkill(f.source, f.id, "native_wait", 1);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.chargedMillis() == charged[0] && activity.phase().equals("IDLE"), "Spent allowance retained; lowered exhausted cap grants no work");
                    context.assertTrue(!activity.work().startsWith("Running"), "Exhausted budget never starts a lease");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Final cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 260)
    public void nativeFoodStopAndPaidWaitDoNotReplay(TestContext context) {
        Fixture f = new Fixture(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stopped"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getHungerManager().setFoodLevel(8);
                    f.player().getInventory().setStack(1, new ItemStack(Items.APPLE, 2));
                    f.runtime.nativeSkill(f.source, f.id, "native_eat", 1);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isUsingItem(), "Native timed item use started after paid admission"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    context.assertTrue(!f.player().isUsingItem(), "Stop clears native use synchronously");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 0, "Stopped player cannot start a task");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("IDLE"), "Cancelled activity settles"))
                .createAndAdd(() -> {
                    context.assertTrue(f.player().getInventory().getStack(1).getCount() == 2, "Cancelled use consumes nothing");
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.runtime.nativeSkill(f.source, f.id, "native_wait", 1);
                })
                .createAndAdd(() -> {
                    var status = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(status.phase().equals("IDLE") && status.work().startsWith("Completed native_wait"), "Wait runs through the shared paid runner and settles");
                    context.assertTrue(f.player().getInventory().getStack(1).getCount() == 2, "Wait and late callbacks never replay cancelled food");
                    f.runtime.nativeSkill(f.source, f.id, "native_eat", 8); // Empty owned slot.
                })
                .createAndAdd(() -> {
                    var status = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(status.phase().equals("IDLE") && status.work().startsWith("Blocked:"), "Ineligible food blocks without retry");
                    context.assertTrue(f.player().getInventory().getStack(1).getCount() == 2, "Failure preserves inventory");
                    f.runtime.nativeSkill(f.source, f.id, "native_wait", 4);
                    f.runtime.stop(); // Pending budget preparation must not require another game tick to close.
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Shutdown flushes pending activity without reactivating it"))
                .completeIfSuccessful();
    }

    static final class Fixture {
        PlayerRuntime runtime;
        final Path directory;
        final ServerCommandSource source;
        UUID id, nativeId;
        Fixture(TestContext context) {
            this(context, writer -> writer);
        }
        Fixture(TestContext context, java.util.function.UnaryOperator<dev.kindred.core.ActionAdmission.Writer> actionWriter) {
            this(context, actionWriter, false);
        }
        /** Default stop tick: above typical {@code tickLimit} so tests opt in to early shutdown explicitly. */
        private static final int DEFAULT_STOP_AT_TICK = 8000;

        Fixture(TestContext context, java.util.function.UnaryOperator<dev.kindred.core.ActionAdmission.Writer> actionWriter, boolean enableEdits) {
            this(context, actionWriter, enableEdits, DEFAULT_STOP_AT_TICK);
        }
        Fixture(TestContext context, java.util.function.UnaryOperator<dev.kindred.core.ActionAdmission.Writer> actionWriter, boolean enableEdits, int stopAtTick) {
            for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                context.setBlockState(x, 1, z, Blocks.STONE);
                for (int y = 2; y <= 5; y++) context.setBlockState(x, y, z, Blocks.AIR);
            }
            var server = context.getWorld().getServer();
            directory = server.getSavePath(WorldSavePath.ROOT).resolve("kindred-paid-fixture-" + UUID.randomUUID());
            source = server.getCommandSource().withWorld(context.getWorld()).withPosition(context.getAbsolute(new Vec3d(3.5, 2, 3.5)));
            runtime = new PlayerRuntime(server, directory, NativePlayerSaves::read, actionWriter);
            if (enableEdits) {
                try {
                    java.nio.file.Files.createDirectories(directory);
                    java.nio.file.Files.writeString(directory.resolve("edits.properties"),
                            "autonomousEdits=true\nprotectionKnown=true\n");
                } catch (java.io.IOException error) {
                    throw new RuntimeException("Fixture edit policy could not be saved", error);
                }
            }
            runtime.start();
            // Never schedule stop below DEFAULT: per-test values near tickLimit used to fire mid-retry and flake the suite.
            int scheduledStop = Math.max(stopAtTick, DEFAULT_STOP_AT_TICK);
            context.runAtTick(scheduledStop, () -> runtime.stop());
            stopWhenDone(context);
        }

        /**
         * A test that ends, passed or failed, before it stops its own runtime leaves its player at work through the rest
         * of the suite (nothing ticks the test's own scheduled stop once it is done): stop it as the test ends.
         */
        private void stopWhenDone(TestContext context) {
            FixtureGround.whenDone(context, this::stopIfRunning);
        }

        private void stopIfRunning() {
            if (runtime.running()) {
                KindredMod.LOGGER.info("Fixture runtime still running as its test ended; stopping it.");
                runtime.stop();
            }
        }
        void established(TestContext context) {
            var statuses = runtime.nativeStatuses(Actor.console());
            context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(), "Native identity established");
            id = statuses.getFirst().id(); nativeId = statuses.getFirst().nativeId();
        }

        /** Starts a timed runner after startup padding; prefer over immediate {@link #awaitReady}. */
        TimedTaskRunner runnerAwaitingReady(TestContext context, String message) {
            TimedTaskRunner runner = context.createTimedTaskRunner();
            runner.expectMinDuration(48);
            runner.createAndAdd(() -> context.assertTrue(runtime.isReady(), message));
            return runner;
        }

        /** GameTest helper: assert runtime metadata is loaded (edit policy when enabled). */
        void awaitReady(TestContext context) {
            context.assertTrue(runtime.isReady(), "Runtime ready");
        }

        ServerPlayerEntity player() { return source.getServer().getPlayerManager().getPlayer(nativeId); }
    }
}
