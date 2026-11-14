package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.BuildBattle;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * In plain terms: a build battle's arena is put back as it was once the podium has been shown (M-06k3a), so the
 * builds' free blocks never leave Builder. Until then no block in it can be broken, by anyone but a builder building,
 * so nothing drops; a builder in creative throws nothing out. Put back at the announced moment, at a stop, and at
 * the next start after a crash; its snapshot is forgotten only once the server has saved the restored blocks.
 */
public final class NativeArenaRestoreGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "arenaRestoreEnd", tickLimit = 2000)
    public void afterThePodiumTheArenaIsPutBackAndUntilThenNothingInItCanBeBrokenForADrop(TestContext context) {
        var b = new Battle(context);
        boolean[] once = {false};
        BlockPos[] built = new BlockPos[1];
        b.start(context.createTimedTaskRunner())
                .createAndAdd(() -> {
                    if (once[0]) return;
                    once[0] = true;
                    var mira = b.mira[0];
                    // Thrown out of creative: comes to nothing.
                    var thrown = mira.dropItem(new ItemStack(Items.DIAMOND_BLOCK, 64), false, false);
                    context.assertTrue(thrown == null, "A builder in creative throws nothing out");
                    // Mira builds: a column of planks on her plot.
                    built[0] = b.plotSpot(mira.getUuid(), 6, 1, 6);
                    for (int y = 0; y < 3; y++) context.getWorld().setBlockState(built[0].up(y), Blocks.OAK_PLANKS.getDefaultState());
                    b.f.runtime.arenaShownTicksForTest(100);
                    b.stopped[0] = b.f.runtime.stopMatch(b.operator(), b.match[0]);
                })
                .createAndAdd(() -> context.assertTrue(b.stopped[0] == 1 && b.over(), "The match over, the podium shown"))
                .createAndAdd(() -> {
                    // Between the podium and the restore: Mira, back in survival, breaks a plank of her build.
                    var mira = b.mira[0];
                    context.assertTrue(mira.interactionManager.getGameMode() == net.minecraft.world.GameMode.SURVIVAL, "Mira in survival");
                    context.assertTrue(b.f.runtime.arenaPendingForTest(b.match[0]), "The arena not yet put back");
                    boolean broke = mira.interactionManager.tryBreakBlock(built[0].up(2));
                    context.assertTrue(!broke && context.getWorld().getBlockState(built[0].up(2)).isOf(Blocks.OAK_PLANKS), "The plank stays");
                    context.assertTrue(items(context, built[0]) == 0, "Nothing dropped");
                })
                .createAndAdd(() -> context.assertTrue(!b.f.runtime.arenaPendingForTest(b.match[0]), "The arena put back after the podium"))
                .createAndAdd(() -> {
                    b.asBefore();
                    context.assertTrue(context.getWorld().getBlockState(built[0]).isAir(), "The build gone");
                    context.assertTrue(items(context, built[0]) == 0, "Nothing dropped by the restore");
                    context.assertTrue(Files.exists(b.snapshotFile()), "The snapshot kept until the restored blocks are saved");
                    context.getWorld().getServer().saveAll(true, false, false);
                })
                .createAndAdd(() -> context.assertTrue(!Files.exists(b.snapshotFile()), "Forgotten once saved"))
                .createAndAdd(b::end)
                .createAndAdd(() -> context.assertTrue(b.f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "arenaRestoreStop", tickLimit = 1600)
    public void whenTheServerStopsMidBattleTheArenaIsPutBackAtOnce(TestContext context) {
        var b = new Battle(context);
        b.start(context.createTimedTaskRunner())
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(b.plotSpot(b.mira[0].getUuid(), 4, 1, 4), Blocks.GOLD_BLOCK.getDefaultState());
                    b.f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(b.f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> {
                    b.asBefore();
                    context.getWorld().getServer().getPlayerManager().remove(b.mira[0]);
                    context.getWorld().getServer().saveAll(true, false, false);
                })
                .createAndAdd(() -> context.assertTrue(!Files.exists(b.snapshotFile()), "Forgotten once saved"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "arenaRestoreCrash", tickLimit = 1200)
    public void anArenaACrashLeftStandingIsPutBackAtTheNextStart(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        var server = context.getWorld().getServer();
        UUID match = UUID.randomUUID();
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        var box = new BlockBox(o.getX() + 1, o.getY() + 1, o.getZ() + 1, o.getX() + 6, o.getY() + 5, o.getZ() + 6);
        Map<BlockPos, BlockState> before = new HashMap<>();
        @SuppressWarnings("unchecked") java.util.concurrent.CompletableFuture<Void>[] written = new java.util.concurrent.CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.stop();
                    var world = context.getWorld();
                    for (int x = box.getMinX(); x <= box.getMaxX(); x++) for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) for (int y = box.getMinY(); y <= box.getMaxY(); y++) {
                        var at = new BlockPos(x, y, z);
                        before.put(at, world.getBlockState(at));
                    }
                    // What a crash mid-battle leaves: the snapshot on disk, and the arena laid out and built on.
                    written[0] = new ArenaSnapshots(f.directory).write(match, ArenaSnapshots.capture(world, box));
                    for (int x = box.getMinX(); x <= box.getMaxX(); x++) for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                        world.setBlockState(new BlockPos(x, box.getMinY(), z), Blocks.WHITE_CONCRETE.getDefaultState());
                        world.setBlockState(new BlockPos(x, box.getMinY() + 1, z), Blocks.OAK_PLANKS.getDefaultState());
                    }
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete() && written[0].isDone() && !written[0].isCompletedExceptionally(), "The crash staged"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Started again"))
                .createAndAdd(() -> {
                    // Waits for the start to read the snapshot back and put the arena back. A fixture's runtime is ticked
                    // through its bodies, and this one has none: ticked here, as the server ticks its own.
                    f.runtime.onServerTick(server);
                    var world = context.getWorld();
                    for (var e : before.entrySet()) context.assertTrue(world.getBlockState(e.getKey()).equals(e.getValue()), "As before at " + e.getKey().toShortString()
                            + ": " + world.getBlockState(e.getKey()) + " for " + e.getValue());
                    context.assertTrue(!f.runtime.arenaPendingForTest(match), "Nothing left to put back");
                    context.assertTrue(items(context, o.add(3, 2, 3)) == 0, "Nothing dropped");
                })
                .createAndAdd(() -> server.saveAll(true, false, false))
                .createAndAdd(() -> context.assertTrue(!Files.exists(new ArenaSnapshots(f.directory).file(match)), "Forgotten once saved"))
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static int items(TestContext context, BlockPos near) {
        return context.getWorld().getEntitiesByClass(ItemEntity.class, new Box(near).expand(24), e -> true).size();
    }

    /** A build battle: Wren, an AI builder, and Mira, a person; what the arena held before it was laid out, kept to compare. */
    private static final class Battle {
        final NativePaidSkillsGameTests.Fixture f;
        final TestContext context;
        final UUID[] wren = new UUID[1], match = new UUID[1];
        final ServerPlayerEntity[] mira = new ServerPlayerEntity[1];
        final int[] proposed = new int[1], started = new int[1], stopped = new int[1];
        final Map<BlockPos, BlockState> before = new HashMap<>();
        final BlockPos o;

        Battle(TestContext context) {
            this.context = context;
            this.f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1900);
            this.o = context.getAbsolutePos(BlockPos.ORIGIN);
        }

        TimedTaskRunner start(TimedTaskRunner runner) {
            return runner
                    .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                    .createAndAdd(() -> {
                        var world = context.getWorld();
                        for (int x = -10; x <= 50; x++) for (int z = -10; z <= 30; z++) {
                            world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                            for (int y = 2; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                        }
                        f.runtime.createNative(f.source, "Wren");
                    })
                    .createAndAdd(() -> {
                        var statuses = f.runtime.nativeStatuses(Actor.console());
                        context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(), "Wren established");
                        wren[0] = statuses.getFirst().nativeId();
                    })
                    .createAndAdd(() -> {
                        if (mira[0] == null) {
                            var world = context.getWorld();
                            context.getWorld().getServer().getPlayerManager().getPlayer(wren[0]).getInventory().clear();
                            mira[0] = MockPlayers.survival(context, "Mira", new BlockPos(1, 2, 1));
                            mira[0].getInventory().clear();
                            for (int x = -6; x <= 46; x++) for (int z = -6; z <= 26; z++) for (int y = 1; y <= 4; y++) {
                                var at = o.add(x, y, z);
                                before.put(at, world.getBlockState(at));
                            }
                            proposed[0] = f.runtime.proposeBuildBattle(operator(), java.util.List.of(mira[0].getUuid(), wren[0]), 20 * 60, BuildBattle.Theme.COTTAGE);
                            if (proposed[0] == 1) {
                                match[0] = f.runtime.matches().getFirst().id();
                                f.runtime.joinMatch(mira[0].getCommandSource(), match[0]);
                                started[0] = f.runtime.startMatch(mira[0].getCommandSource(), match[0]);
                            }
                        }
                        context.assertTrue(proposed[0] == 1 && started[0] == 1, "Proposed and started");
                    })
                    .createAndAdd(() -> {
                        context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Building");
                        context.assertTrue(mira[0].interactionManager.getGameMode() == net.minecraft.world.GameMode.CREATIVE, "Mira in creative");
                        context.assertTrue(Files.exists(snapshotFile()), "The arena as it was, on disk");
                    });
        }

        net.minecraft.server.command.ServerCommandSource operator() { return f.source.withPosition(net.minecraft.util.math.Vec3d.of(o.add(0, 2, 0))); }

        java.nio.file.Path snapshotFile() { return new ArenaSnapshots(f.directory).file(match[0]); }

        boolean over() { return f.runtime.matches().stream().noneMatch(m -> m.id().equals(match[0]) && m.state() != MatchRegistry.State.ENDED); }

        BlockPos plotSpot(UUID builder, int dx, int dy, int dz) {
            int index = builder.equals(mira[0].getUuid()) ? 0 : 1;
            return o.add(2 + index * (BuildBattle.PLOT + BuildBattle.GAP) + dx, 1 + dy, 2 + dz);
        }

        /** Every block noted before the battle laid out its arena, as it was. */
        void asBefore() {
            var world = context.getWorld();
            int differ = 0;
            BlockPos first = null;
            for (var e : before.entrySet()) {
                if (!world.getBlockState(e.getKey()).equals(e.getValue())) {
                    differ++;
                    if (first == null) first = e.getKey();
                }
            }
            context.assertTrue(differ == 0, "The arena as it was: " + differ + " blocks differ" + (first == null ? "" : ", first " + first.toShortString()
                    + " " + world.getBlockState(first) + " for " + before.get(first)));
        }

        void end() {
            context.getWorld().getServer().getPlayerManager().remove(mira[0]);
            f.runtime.stop();
        }
    }
}
