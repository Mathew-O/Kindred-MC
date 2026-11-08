package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/** Told by a teammate where it found trees, a player with no wood in a desert walks there for its logs (F-25c4cc). */
public final class NativeToldTreesGameTests implements FabricGameTest {
    private static final int FAR = 300;

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID nativeId) {
        return f.source.getServer().getPlayerManager().getPlayer(nativeId);
    }

    private static String progress(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.runtime.panelBots(Actor.console()).stream().filter(b -> b.id().equals(id)).findFirst().map(PlayerRuntime.PanelBot::goalProgress).orElse("");
    }

    /**
     * Two players on the Nether portal goal, three hundred blocks apart along a strip of desert: Cutter beside a stand of
     * oaks, Dune at the far end with no tree within its sight or its exploring's reach round where it started. Cutter cuts
     * its first logs and tells the team where; Dune walks there and cuts its own. In E-40's iteration 12
     * (`e40a12-66a7c0a`) on the desert seed 31102026, Echo cut its first logs 150 blocks from spawn in the first minute,
     * and Kindred looked for trees for the whole run and never made a table. Staged: a strip of sand nine wide on stone,
     * open to the sky, daylight held; eight oaks at its far end; both players with bread and nothing else. Passes once
     * Dune holds a log it cut.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "toldTrees", tickLimit = 4000)
    public void toldWhereATeammateFoundTreesAPlayerWithNoWoodWalksThereForItsLogs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3900);
        UUID[] ids = new UUID[2], players = new UUID[2]; // Dune, Cutter
        BlockPos[] at = new BlockPos[1];
        boolean[] walkedThere = new boolean[1];
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Dune"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Dune allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Cutter"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Dune") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var dune = player(f, players[0]);
                    BlockPos p = at[0] = dune.getBlockPos().add(0, 0, 0).toImmutable();
                    FixtureGround.keep(context, p.add(-8, -2, -5), p.add(FAR + 12, 12, 5));
                    FixtureGround.simulated(context, p.add(-8, 0, -5), p.add(FAR + 12, 0, 5));
                    for (BlockPos c : BlockPos.iterate(p.add(-8, -2, -5), p.add(FAR + 12, 12, 5))) {
                        int dy = c.getY() - p.getY();
                        world.setBlockState(c, (dy == -2 ? Blocks.STONE : dy == -1 ? Blocks.SAND : Blocks.AIR).getDefaultState());
                    }
                    // A stand of eight oaks at the far end, five logs high under a crown of leaves.
                    for (int t = 0; t < 8; t++) {
                        BlockPos foot = p.add(FAR + (t % 4) * 3, 0, t < 4 ? -3 : 3);
                        world.setBlockState(foot.down(), Blocks.DIRT.getDefaultState());
                        for (int y = 0; y < 5; y++) world.setBlockState(foot.up(y), Blocks.OAK_LOG.getDefaultState());
                        for (BlockPos c : BlockPos.iterate(foot.add(-1, 3, -1), foot.add(1, 6, 1))) {
                            if (world.getBlockState(c).isAir()) world.setBlockState(c, Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
                        }
                    }
                    var cutter = player(f, players[1]);
                    dune.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    dune.setVelocity(Vec3d.ZERO);
                    cutter.refreshPositionAndAngles(p.getX() + FAR - 4 + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    cutter.setVelocity(Vec3d.ZERO);
                    for (var who : new ServerPlayerEntity[] {dune, cutter}) {
                        var inventory = who.getInventory();
                        inventory.clear();
                        inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                        who.setHealth(20);
                        who.getHungerManager().setFoodLevel(20);
                    }
                })
                .createAndAdd(() -> context.assertTrue(player(f, players[0]).isOnGround() && player(f, players[1]).isOnGround(), "Both on the sand"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, ids[0], SurvivalGoal.NETHER_PORTAL) == 1, "Dune's goal accepted");
                    context.assertTrue(f.runtime.startGoal(f.source, ids[1], SurvivalGoal.NETHER_PORTAL) == 1, "Cutter's goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var dune = player(f, players[0]);
                    context.assertTrue(dune != null, "Its time out with no wood: " + seen);
                    String doing = progress(f, ids[0]);
                    walkedThere[0] |= doing.contains("Walk to the trees Cutter found");
                    if (watched[0]++ % 300 == 0) seen.append(watched[0]).append(": ").append(doing).append(" @ ").append(dune.getBlockPos().subtract(at[0]).toShortString()).append("; ");
                    var inventory = dune.getInventory();
                    boolean logs = false;
                    for (int slot = 0; slot < inventory.size(); slot++) logs |= inventory.getStack(slot).isIn(ItemTags.LOGS) || inventory.getStack(slot).isIn(ItemTags.PLANKS);
                    context.assertTrue(logs, "Dune has no wood: " + seen + " now " + doing + "; Cutter: " + progress(f, ids[1]));
                })
                .createAndAdd(() -> {
                    context.assertTrue(walkedThere[0], "Its wood from the trees it was told of: " + seen);
                    KindredMod.LOGGER.info("Told trees: Dune has wood after {} ticks, at {}", watched[0], player(f, players[0]).getBlockPos().subtract(at[0]).toShortString());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
