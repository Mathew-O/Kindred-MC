package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashSet;
import java.util.Set;

/** Logs in sight that no walk can reach are given up as a stand, not tried one at a time (F-22c67). */
public final class NativeUnreachableLogsGameTests implements FabricGameTest {
    /**
     * A player with no wood on open sand, a stand of twelve oaks in sight to the north behind a barrier wall it can see
     * through and cannot cross, and one oak in the open thirty blocks south. It tries a few of the fenced logs, then gives
     * up the stand and cuts the open oak. In the corpus gate for F-22c66 (`diag-factions303-079bdc2-303-sol-102920`, logs
     * past its scene's fence) a player walked for fifteen fenced logs one after another, each "No walkable route", each a
     * new log and so never set aside, its walks for water between them taking it far enough each time that it was never
     * stranded. Fails once it has walked for more than three of the fenced logs before it holds wood.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "unreachableLogs", tickLimit = 4000)
    public void logsInSightBehindAWallItCannotCrossAreGivenUpAsAStand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3900);
        BlockPos[] at = new BlockPos[1];
        Set<String> fenced = new LinkedHashSet<>();
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Fenced"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // In the middle of a structure as wide as the field, so that copies side by side keep apart.
                    BlockPos p = at[0] = context.getAbsolutePos(new BlockPos(60, 2, 60)).toImmutable();
                    FixtureGround.keep(context, p.add(-20, -2, -26), p.add(20, 10, 36));
                    FixtureGround.simulated(context, p.add(-20, 0, -26), p.add(20, 0, 36));
                    for (BlockPos c : BlockPos.iterate(p.add(-20, -2, -26), p.add(20, 10, 36))) {
                        int dy = c.getY() - p.getY();
                        world.setBlockState(c, (dy == -2 ? Blocks.STONE : dy == -1 ? Blocks.SAND : Blocks.AIR).getDefaultState());
                    }
                    // The fenced stand: a barrier box from 8 to 24 north, 14 either side, 8 high and roofed.
                    for (BlockPos c : BlockPos.iterate(p.add(-14, 0, -24), p.add(14, 8, -8))) {
                        boolean wall = c.getX() == p.getX() - 14 || c.getX() == p.getX() + 14 || c.getZ() == p.getZ() - 24
                                || c.getZ() == p.getZ() - 8 || c.getY() == p.getY() + 8;
                        if (wall) world.setBlockState(c, Blocks.BARRIER.getDefaultState());
                    }
                    for (int t = 0; t < 12; t++) {
                        BlockPos foot = p.add(-10 + (t % 4) * 6, 0, -11 - (t / 4) * 5);
                        world.setBlockState(foot.down(), Blocks.DIRT.getDefaultState());
                        for (int y = 0; y < 3; y++) world.setBlockState(foot.up(y), Blocks.OAK_LOG.getDefaultState());
                    }
                    // The open oak, thirty south.
                    BlockPos open = p.add(0, 0, 30);
                    world.setBlockState(open.down(), Blocks.DIRT.getDefaultState());
                    for (int y = 0; y < 4; y++) world.setBlockState(open.up(y), Blocks.OAK_LOG.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 180, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the sand"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null, "Its body: " + seen);
                    String doing = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    int i = doing.indexOf("Walk to logs at ");
                    if (i >= 0) {
                        String[] xyz = doing.substring(i + "Walk to logs at ".length()).split(",\\s*");
                        try {
                            int z = Integer.parseInt(xyz[2].replaceAll("[^-0-9].*$", "").trim()) - at[0].getZ();
                            if (z < -8) fenced.add(String.join(",", xyz[0].trim(), xyz[1].trim(), String.valueOf(z)));
                        } catch (RuntimeException unparsed) {
                            // Not a position: nothing to count.
                        }
                    }
                    if (watched[0]++ % 200 == 0) seen.append(watched[0]).append(": ").append(doing).append(" @ ")
                            .append(player.getBlockPos().subtract(at[0]).toShortString()).append("; ");
                    context.assertTrue(fenced.size() <= 3, "Walked for " + fenced.size() + " fenced logs: " + fenced + "; " + seen);
                    var inventory = player.getInventory();
                    boolean wood = false;
                    for (int slot = 0; slot < inventory.size(); slot++) wood |= inventory.getStack(slot).isIn(ItemTags.LOGS) || inventory.getStack(slot).isIn(ItemTags.PLANKS);
                    context.assertTrue(wood, "No wood yet: " + fenced.size() + " fenced logs walked for " + fenced + "; " + seen + " now " + doing);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Unreachable logs: wood after {} ticks, {} fenced logs walked for {}", watched[0], fenced.size(), fenced);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
