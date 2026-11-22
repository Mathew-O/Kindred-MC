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

/**
 * A block readied for the night's seal and a torch readied to light the way do not swap each other out of one hotbar slot
 * (F-25c4dk). With no hotbar slot free, both went to slot 8 ({@code swapToHotbar}: the first empty slot, else 8), each
 * evicting the other, and neither was placed: in step 8's consoles (`claude/corpus-data-e40a25s-1121`), 303 off Sol
 * alternated 4,560 times, 505 on Echo 1,412.
 */
public final class NativeHotbarSwapGameTests implements FabricGameTest {
    private static final int FIELD = 14;
    /** The most changes between the two readying steps it may make. */
    private static final int ALTERNATIONS_MOST = 4;
    /** Ticks from digging in by which its shelter is sealed. */
    private static final int SEALED_BY = 400;

    /**
     * In plain terms: at night, with every hotbar slot holding a tool, weapon or food and its blocks and torches in its
     * backpack, it seals a gap in its night shelter; readying a torch does not undo the block it readied.
     *
     * Technical: F-25c4dk. STAGED: night held, as NativeNightMeanwhileGameTests holds it; stone ground under dirt; no wood,
     * and three iron ore on the ground's top in sight, so its route's next step is on the surface and waits for daylight. The hotbar is full, all of it kept to hand (stone
     * pickaxe, sword, axe, shovel and hoe, bread, a water bucket, shears, flint and steel); cobblestone and torches only in
     * the backpack. It digs in and seals itself; then, staged, a one-cell gap opened in its shelter's side at its feet over a
     * one-block drop (a way in to seal, dark, with no floor for a torch), and its blocks and torches put back in the backpack with the hotbar
     * full as before. Fails if the goal's step changes between readying a block for the seal and readying a torch more
     * than {@value #ALTERNATIONS_MOST} times, or if the gap is not sealed within {@value #SEALED_BY} ticks.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "hotbarSwap", tickLimit = 2000)
    public void withAFullHotbarItSealsItsShelterAndTheTorchDoesNotUndoIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int surface = o.getY() + 7;
        int[] opened = new int[1], ticks = new int[1], alternations = new int[1];
        BlockPos[] gap = new BlockPos[1];
        String[] lastKind = {""}, last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nightjar"))
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
                    // Iron ore on the ground's top in sight, so its iron is a walk out under the sky, held till daylight,
                    // whatever stage it plans: not a cave or a branch mine to go on with below ground at night.
                    for (int k = 0; k < 3; k++) world.setBlockState(new BlockPos(o.getX() + 8 + k, surface, o.getZ() + 8), Blocks.IRON_ORE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, surface, o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    // A full hotbar, every slot something kept to hand.
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.STONE_AXE));
                    inventory.setStack(3, new ItemStack(Items.STONE_SHOVEL));
                    inventory.setStack(4, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(5, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(6, new ItemStack(Items.SHEARS));
                    inventory.setStack(7, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(8, new ItemStack(Items.STONE_HOE));
                    // Its blocks and torches only in the backpack.
                    inventory.setStack(9, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(10, new ItemStack(Items.TORCH, 16));
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
                    context.assertTrue(player != null, "Its body is gone");
                    ticks[0]++;
                    BlockPos feet = player.getBlockPos();
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (gap[0] == null) {
                        // First it digs in and seals its shelter itself; once it waits for daylight in it, the staging.
                        if (!f.runtime.dugInForTheNightForTest(f.id) || !goal.toLowerCase().contains("wait for daylight")
                                || !player.isOnGround()) {
                            context.assertTrue(false, "Not yet dug in and sealed: dug in " + f.runtime.dugInForTheNightForTest(f.id)
                                    + ", on the ground " + player.isOnGround() + ", at " + feet.subtract(o).toShortString() + ": " + goal);
                        }
                        gap[0] = openASideGap(world, feet);
                        context.assertTrue(gap[0] != null, "No side of solid rock to open a gap on, at " + feet.subtract(o).toShortString());
                        // Its blocks and torches back in the backpack, the hotbar full again: as it was, all kept to hand.
                        var inventory = player.getInventory();
                        for (int s = 0; s < 36; s++) {
                            var stack = inventory.getStack(s);
                            if (stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.DIRT) || stack.isOf(Items.TORCH)) inventory.setStack(s, ItemStack.EMPTY);
                        }
                        inventory.setStack(8, new ItemStack(Items.STONE_HOE));
                        inventory.setStack(9, new ItemStack(Items.COBBLESTONE, 32));
                        inventory.setStack(10, new ItemStack(Items.TORCH, 16));
                        opened[0] = ticks[0];
                        seen.append("[a gap opened at ").append(gap[0].subtract(o).toShortString()).append(", tick ").append(ticks[0]).append("] ");
                    }
                    String kind = goal.contains("Seal the shelter") && goal.contains("ready a block") ? "block"
                            : goal.contains("Ready a torch") ? "torch" : "";
                    if (!kind.isEmpty()) {
                        if (!lastKind[0].isEmpty() && !kind.equals(lastKind[0])) alternations[0]++;
                        lastKind[0] = kind;
                    }
                    if (!goal.equals(last[0]) && seen.length() < 700) {
                        seen.append(ticks[0]).append(": ").append(goal.replaceAll("^[^:]*: ", "")).append(" @ ")
                                .append(feet.subtract(o).toShortString()).append("; ");
                    }
                    last[0] = goal;
                    boolean sealed = !world.getBlockState(gap[0]).isAir();
                    String state = "the gap opened at tick " + opened[0] + ", now " + ticks[0] + ", alternations " + alternations[0]
                            + ", sealed " + sealed + ": " + seen;
                    context.assertTrue(alternations[0] <= ALTERNATIONS_MOST, "The block and the torch swap each other out: " + state);
                    context.assertTrue(sealed || ticks[0] - opened[0] <= SEALED_BY, "Not sealed in " + SEALED_BY + " ticks: " + state);
                    context.assertTrue(sealed, "Sealed again");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Hotbar swap: the gap opened at {}, sealed by {}, alternations {}; {}", opened[0], ticks[0], alternations[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A gap in its shelter's side at its feet, onto a cell with a one-block drop under it and rock all round: a way in to
     * seal, no cave to explore, and no floor there for a torch. Its torches near are taken away and the rest of its cell walled in, so
     * that it is dark there with nowhere to set a torch. Null when no side is solid rock for three.
     */
    private static BlockPos openASideGap(net.minecraft.server.world.ServerWorld world, BlockPos feet) {
        for (Direction side : Direction.Type.HORIZONTAL) {
            boolean rock = true;
            for (int k = 1; k <= 3 && rock; k++) {
                BlockPos c = feet.offset(side, k);
                rock = world.getBlockState(c).isOf(Blocks.STONE) && world.getBlockState(c.up()).isOf(Blocks.STONE)
                        && world.getBlockState(c.down()).isOf(Blocks.STONE);
            }
            if (!rock) continue;
            // Dark: no torch it set near its shelter is left. Its cell walled in all round but for the gap, so the gap is
            // its one way in and there is no cell beside it with a floor for a torch.
            for (BlockPos c : BlockPos.iterate(feet.add(-6, -4, -6), feet.add(6, 4, 6))) {
                if (world.getBlockState(c).isOf(Blocks.TORCH) || world.getBlockState(c).isOf(Blocks.WALL_TORCH)) world.setBlockState(c, Blocks.AIR.getDefaultState());
            }
            for (BlockPos c : BlockPos.iterate(feet.add(-1, -1, -1), feet.add(1, 2, 1))) {
                if (c.equals(feet) || c.equals(feet.up())) continue;
                world.setBlockState(c, Blocks.STONE.getDefaultState());
            }
            BlockPos gap = feet.offset(side).toImmutable();
            world.setBlockState(gap, Blocks.AIR.getDefaultState());
            world.setBlockState(gap.down(), Blocks.AIR.getDefaultState());
            return gap;
        }
        return null;
    }
}
