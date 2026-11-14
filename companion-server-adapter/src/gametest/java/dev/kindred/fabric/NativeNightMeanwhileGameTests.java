package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Dug in for the night, it takes no mining step meanwhile from tonight's shelter (F-22c72). */
public final class NativeNightMeanwhileGameTests implements FabricGameTest {
    private static final int FIELD = 14;

    /**
     * Night on open ground, with a stone pickaxe and no wood: it digs in for the night. Below ground, the route's next
     * step logs from the surface, the night's "meanwhile" (B7) gave it the route's iron instead, "Mine iron ore while
     * the night passes", from the shelter it had just dug and before it had sealed it. In NativeFactionShelterGameTests
     * (CI, 12 November: #265's third run, #271's re-run, #272 twice, #273) that step's walk on into a cave it had seen
     * went up its own stair and over the ground, out of its shelter: it dug in again twelve blocks off, and was dug in
     * 25 to 149 ticks of the night. A walk whose end is out under the sky is held while it is dug in (F-25c4cd); a walk
     * to a cave cell below the ground's top is not, whichever way it goes there. Staged: once it is dug in, a dark
     * passage nine blocks long opened from the cell it stands in, a cave it sees. Fails once it takes a meanwhile step
     * while dug in for the night, or is out of its shelter for 200 ticks after digging in; passes once it has stayed dug
     * in for 1,500 ticks with none.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "nightMeanwhile", tickLimit = 3600)
    public void dugInForTheNightItTakesNoMiningStepMeanwhileFromItsShelter(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int surface = o.getY() + 7;
        int[] dugIn = new int[1], out = new int[1], meanwhile = new int[1];
        boolean[] opened = new boolean[1];
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nightowl"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos low = o.add(-FIELD, 0, -FIELD), high = o.add(FIELD, 13, FIELD);
                    FixtureGround.keep(context, low, high);
                    for (BlockPos c : BlockPos.iterate(low, high)) {
                        var state = c.getY() < surface - 1 ? Blocks.STONE : c.getY() == surface - 1 ? Blocks.DIRT : Blocks.AIR;
                        world.setBlockState(c, state.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, surface, o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 32));
                    // A shield: its walk into a cave does not wait on the mobs it hears through the rock.
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    world.setTimeOfDay(14000);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == surface, "On the ground"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(14000);
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    boolean in = f.runtime.dugInForTheNightForTest(f.id);
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!goal.equals(last[0]) && seen.length() < 3000) {
                        seen.append(dugIn[0] + out[0]).append(": ").append(goal.replaceAll("^[^:]*: ", "")).append(" @ ")
                                .append(feet.subtract(o).toShortString()).append(in ? " in" : "").append("; ");
                    }
                    last[0] = goal;
                    if (!opened[0] && in && player.isOnGround() && feet.getY() <= surface - 3) {
                        // A cave beside its shelter: nine blocks of passage, two high, from the cell it stands in, the
                        // way that is solid rock for all nine; roofed, so dark at its far end.
                        for (Direction side : Direction.Type.HORIZONTAL) {
                            boolean rock = true;
                            for (int k = 1; k <= 9 && rock; k++) {
                                BlockPos c = feet.offset(side, k);
                                rock = world.getBlockState(c).isOf(Blocks.STONE) && world.getBlockState(c.up()).isOf(Blocks.STONE);
                            }
                            if (!rock) continue;
                            for (int k = 1; k <= 9; k++) {
                                world.setBlockState(feet.offset(side, k), Blocks.AIR.getDefaultState());
                                world.setBlockState(feet.offset(side, k).up(), Blocks.AIR.getDefaultState());
                            }
                            opened[0] = true;
                            seen.append("[a cave opened ").append(side.asString()).append(" from ").append(feet.subtract(o).toShortString()).append("] ");
                            break;
                        }
                        context.assertTrue(opened[0], "No side of solid rock to open a cave on, at " + feet.subtract(o).toShortString());
                    }
                    if (opened[0]) {
                        if (in) dugIn[0]++;
                        else out[0]++;
                        if (in && goal.contains("while the night passes")) meanwhile[0]++;
                    }
                    String state = "dug in " + dugIn[0] + ", out " + out[0] + ", a meanwhile step while dug in " + meanwhile[0] + " ticks: " + seen;
                    context.assertTrue(meanwhile[0] == 0, "A mining step meanwhile from its night's shelter: " + state);
                    context.assertTrue(out[0] < 200, "Out of its night shelter after digging in: " + state);
                    context.assertTrue(dugIn[0] >= 1500, "Dug in for the night: " + dugIn[0]);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Night meanwhile: dug in {}, out {}, a meanwhile step in it {}; {}", dugIn[0], out[0], meanwhile[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
