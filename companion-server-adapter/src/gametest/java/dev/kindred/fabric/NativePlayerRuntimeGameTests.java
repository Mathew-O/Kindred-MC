package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Vec3d;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that the main runtime
 * starts, saves and stops AI players correctly, even when saving goes wrong.
 *
 * Technical: Real runtime/SQLite/native players. IO fault injection controls only immutable native-save
 * bytes.
 */
public final class NativePlayerRuntimeGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void runtimeCreatesThreePlayersAndReopensStoppedNativeInventories(TestContext context) {
        Fixture f = new Fixture(context);
        UUID[] ids = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Fixture runtime opens asynchronously"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.createNative(f.source, "Ada") == 1, "First native creation accepted");
                    context.assertTrue(f.runtime.createNative(f.source, "Ben") == 1, "Second native creation accepted");
                    context.assertTrue(f.runtime.createNative(f.source, "Cy") == 1, "Third native creation accepted");
                    context.assertTrue(f.runtime.createNative(f.source, "Excess") == 0, "Pending allocations count toward the three-player limit");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 3 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()), "Three bodies require verified native-file establishment");
                })
                .createAndAdd(() -> {
                    var first = f.runtime.nativeStatuses(Actor.console()).stream().filter(s -> s.name().equals("Ada")).findFirst().orElseThrow();
                    ids[0] = first.id(); ids[1] = first.nativeId();
                    var player = context.getWorld().getServer().getPlayerManager().getPlayer(first.nativeId());
                    player.getInventory().setStack(5, new ItemStack(Items.APPLE, 7)); // Fixture resources only.
                    context.assertTrue(f.runtime.nativeStatuses(Actor.player(UUID.randomUUID())).isEmpty(), "No unauthorized identity listing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source.withLevel(0), "stop", ids[0]) == 0, "Unauthorized controls denied");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[0]) == 1, "Controller stop accepted");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "disconnect", ids[0]) == 1, "Native disconnect saves physical state");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", ids[0]) == 1, "Established native identity reconnects");
                })
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).stream().filter(s -> s.id().equals(ids[0])).findFirst().orElseThrow();
                    context.assertTrue(status.connected() && status.stopped(), "Native reconnect preserves stop latch");
                    var player = context.getWorld().getServer().getPlayerManager().getPlayer(ids[1]);
                    context.assertTrue(player.getInventory().getStack(5).getCount() == 7, "Native reconnect preserves exact food count");
                })
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Metadata writer closes without blocking a tick"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Reopened runtime loads schema-7 identity metadata"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 3 && statuses.stream().allMatch(s -> !s.connected() && s.stopped()), "Restart never auto-spawns or clears stop");
                    f.runtime.nativeLifecycle(f.source, "connect", ids[0]);
                })
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).stream().filter(s -> s.id().equals(ids[0])).findFirst().orElseThrow();
                    context.assertTrue(status.connected() && status.stopped(), "Explicit restored connection remains stopped");
                    context.assertTrue(context.getWorld().getServer().getPlayerManager().getPlayer(ids[1]).getInventory().getStack(5).getCount() == 7, "Items survive actual metadata close/reopen and native file load");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Final fixture cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 260)
    public void stopFencesDelayedNativeLoadsAndMissingSaveBlocksReplacement(TestContext context) {
        Fixture f = new Fixture(context);
        UUID[] ids = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Fixture ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Fenced"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED, "Initial native save established");
                })
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    ids[0] = status.id(); ids[1] = status.nativeId();
                    f.runtime.nativeLifecycle(f.source, "disconnect", ids[0]);
                    f.reader.holdNext = true;
                    f.runtime.nativeLifecycle(f.source, "connect", ids[0]);
                    f.runtime.nativeLifecycle(f.source, "stop", ids[0]);
                })
                .createAndAdd(() -> context.assertTrue(f.reader.actualHeld.isDone(), "Real bytes read in worker"))
                .createAndAdd(() -> f.reader.held.complete(f.reader.actualHeld.getNow(Optional.empty())))
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(!status.connected() && status.stopped(), "Stopped generation prevents late native registration");
                    context.assertTrue(context.getWorld().getServer().getPlayerManager().getPlayer(ids[1]) == null, "No body appeared after stopped callback");
                    f.reader.missingNext = true;
                    f.runtime.nativeLifecycle(f.source, "connect", ids[0]);
                })
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.phase() == StoredNativePlayer.Phase.BLOCKED && !status.connected(), "Missing-save fault blocks established identity");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", ids[0]) == 0, "Blocked identity cannot blindly recreate items");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Fixture closes"))
                .completeIfSuccessful();
    }

    /** E-10c: a world saved before Vesp became Echo keeps one player; the starter Echo is that saved Vesp. */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 240)
    public void starterEchoReconnectsAPlayerSavedAsVespInsteadOfMakingASecond(TestContext context) {
        Fixture f = new Fixture(context);
        UUID[] vesp = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Fixture ready"))
                .createAndAdd(() -> context.assertTrue(f.runtime.createNative(f.source, "Vesp") == 1, "A world from before the rename has Vesp"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED
                            && statuses.getFirst().connected(), "Vesp established and in the world");
                    vesp[0] = statuses.getFirst().id();
                    context.assertTrue(f.runtime.canonicalBotIdForControlSlot(6).equals(Optional.of(vesp[0])), "Echo's control-deck slot selects the saved Vesp");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "disconnect", vesp[0]) == 1, "Vesp disconnected");
                })
                .createAndAdd(() -> context.assertFalse(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "Vesp is offline"))
                .createAndAdd(() -> context.assertTrue(f.runtime.spawnCanonicalRoster(f.source, "Echo") == 1, "Spawning Echo accepted"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1, "No second player was made for Echo");
                    context.assertTrue(statuses.getFirst().id().equals(vesp[0]) && statuses.getFirst().connected(), "The saved Vesp came back as Echo");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "disconnect", vesp[0]) == 1, "Vesp disconnected again");
                })
                .createAndAdd(() -> context.assertFalse(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "Vesp is offline again"))
                .createAndAdd(() -> context.assertTrue(f.runtime.spawnCanonicalRoster(f.source, null) == 1, "Spawning the whole starter roster accepted"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 3 && statuses.stream().allMatch(s -> s.connected()), "Three starters in the world, got " + statuses.size());
                    context.assertTrue(statuses.stream().map(s -> s.name()).sorted().toList().equals(java.util.List.of("Kindred", "Sol", "Vesp")),
                            "Kindred and Sol made; Echo is the saved Vesp, not a fourth player");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Fixture closes"))
                .completeIfSuccessful();
    }

    /** A personality's nine traits, for comparing two whatever their revisions. */
    private static java.util.List<Integer> traits(dev.kindred.core.Personality personality) {
        return java.util.Arrays.stream(dev.kindred.core.Personality.Trait.values()).map(personality::value).toList();
    }

    /**
     * C13: a starter made from the roster begins with its own preset personality (Sol's, not the defaults), saved: read
     * back after a restart. A saved player's personality is its own from then on: a trait its operator changed stays
     * changed across the restart, and spawning the starter again reconnects it without resetting anything.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void aStarterBeginsWithItsPresetPersonalityKeptAcrossARestartAndNeverReset(TestContext context) {
        Fixture f = new Fixture(context);
        UUID[] sol = {null};
        var preset = dev.kindred.core.CanonicalRoster.SOL.defaultPersonality();
        var changed = new java.util.ArrayList<>(traits(preset));
        changed.set(dev.kindred.core.Personality.Trait.CURIOSITY.ordinal(), 10);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> context.assertTrue(f.runtime.spawnCanonicalRoster(f.source, "Sol") == 1, "Sol spawned from the roster"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED
                            && statuses.getFirst().connected(), "Sol established");
                    sol[0] = statuses.getFirst().id();
                })
                .createAndAdd(() -> context.assertTrue(traits(f.runtime.personalityForFixtures(sol[0])).equals(traits(preset)),
                        "Sol begins with Sol's personality, not the defaults: " + f.runtime.personalityForFixtures(sol[0])))
                // Its operator changes a trait once the first save is in (a profile change waits for the last).
                .createAndAdd(() -> context.assertTrue(f.runtime.socialSetting(f.source, sol[0], null, false,
                        dev.kindred.core.Personality.Trait.CURIOSITY, 10) == 1, "Curiosity set to 10"))
                .createAndAdd(() -> context.assertTrue(f.runtime.socialSetting(f.source, sol[0], "ambient", true, null, 0) == 1,
                        "And saved: the next change is let in"))
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Closed"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Reopened"))
                .createAndAdd(() -> context.assertTrue(traits(f.runtime.personalityForFixtures(sol[0])).equals(changed),
                        "Read back after the restart, the change kept: " + f.runtime.personalityForFixtures(sol[0])))
                .createAndAdd(() -> context.assertTrue(f.runtime.spawnCanonicalRoster(f.source, "Sol") == 1, "Sol spawned again"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "The saved Sol reconnected"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Still one Sol");
                    context.assertTrue(traits(f.runtime.personalityForFixtures(sol[0])).equals(changed),
                            "Never reset to the preset: " + f.runtime.personalityForFixtures(sol[0]));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static final class Fixture {
        PlayerRuntime runtime;
        final Path directory;
        final ServerCommandSource source;
        final ControlledReader reader = new ControlledReader();
        Fixture(TestContext context) {
            for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                context.setBlockState(x, 1, z, Blocks.STONE);
                for (int y = 2; y <= 5; y++) context.setBlockState(x, y, z, Blocks.AIR);
            }
            var server = context.getWorld().getServer();
            directory = server.getSavePath(WorldSavePath.ROOT).resolve("kindred-native-fixture-" + UUID.randomUUID());
            source = server.getCommandSource().withWorld(context.getWorld()).withPosition(context.getAbsolute(new Vec3d(3.5, 2, 3.5)));
            runtime = new PlayerRuntime(server, directory, reader);
            runtime.start();
            context.runAtTick(250, () -> runtime.stop()); // Failure cleanup in the disposable test world.
        }
    }

    private static final class ControlledReader implements NativePlayerSaves.Reader {
        boolean holdNext;
        boolean missingNext;
        CompletableFuture<Optional<NativePlayerSaves.Bytes>> held;
        CompletableFuture<Optional<NativePlayerSaves.Bytes>> actualHeld;
        @Override public CompletableFuture<Optional<NativePlayerSaves.Bytes>> read(Path directory, UUID id) {
            if (missingNext) { missingNext = false; return CompletableFuture.completedFuture(Optional.empty()); }
            var actual = NativePlayerSaves.read(directory, id);
            if (!holdNext) return actual;
            holdNext = false;
            actualHeld = actual;
            held = new CompletableFuture<>();
            return held;
        }
    }
}
