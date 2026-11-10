package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a frame block cast from lava and water whose poured water is then lost under gravel coming down into it
 * counts as cast, with the water gone: the player goes on with its frame, fetching more water, instead of being stopped
 * for good with an outcome no one can settle.
 *
 * Technical: F-25c4cx. {@code NativeCastAction.settle} verified a cast only with both buckets back as they were, and
 * anything else that had changed the buckets was uncertain, which stops the player for reconciliation. In E-40's
 * iteration 16 (`e40a16-922f4fd`) on 31102026 Kindred's first cast hardened (obsidian at -2, -49, 68) and its water,
 * poured into the cell it had cleared above, was lost under the gravel that came down into it (gravel at -2, -48, 68 and
 * -47 in the kept world); the outcome was uncertain and it was stopped for the rest of the run. On 404 Sol's was stopped
 * so too, the obsidian made and its water left standing ("Taking the water back was denied").
 */
public final class NativeCastWaterLostGameTests implements FabricGameTest {
    /**
     * The scene of {@code NativePortalCampaignGameTests.underThickRockTheFramesTopIsCastWithItsWaterTakenBack}: a cast
     * frame's room under rock five thick, lava in its floor and the cast route's kit. Staged: the tick the first frame
     * cell is obsidian with its poured water standing over it, the water's cell is set to gravel, as the gravel came down
     * into it on 31102026. A water source in the floor of the room's open end, for its buckets to be filled again.
     * Passes once that cast is verified, with nothing uncertain, and the player goes on to cast another frame cell.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "castWaterLost", tickLimit = 6000)
    public void aCastWhoseWaterIsLostUnderGravelCountsAndTheFrameGoesOn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
        BlockPos[] lost = new BlockPos[1];
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        Runnable watch = () -> {
            var bots = f.runtime.panelBots(Actor.console());
            String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
            if (progress != null && !progress.equals(last[0])) {
                last[0] = progress;
                if (seen.length() < 3000) seen.append(progress.replaceAll("^.* - ", "")).append("; ");
            }
            if (lost[0] != null || feet[0] == null) return;
            var world = context.getWorld();
            for (BlockPos pos : BlockPos.iterate(feet[0].add(-6, -1, -7), feet[0].add(6, 4, 6))) {
                if (world.getBlockState(pos).isOf(Blocks.OBSIDIAN) && world.getFluidState(pos.up()).isIn(FluidTags.WATER)
                        && world.getFluidState(pos.up()).isStill()) {
                    lost[0] = pos.up().toImmutable();
                    // Staged: the water poured over the first cast lost under gravel, as on 31102026.
                    world.setBlockState(lost[0], Blocks.GRAVEL.getDefaultState());
                    return;
                }
            }
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Waterlost"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    FixtureGround.keep(context, p.add(-12, -2, -7), p.add(6, 8, 6));
                    for (int x = -12; x <= 6; x++) for (int z = -7; z <= 6; z++) {
                        boolean edge = x >= -6 && (Math.abs(x) == 6 || z == -7 || z == 6);
                        boolean rock = x >= -6;
                        world.setBlockState(p.add(x, -2, z), Blocks.STONE.getDefaultState());
                        world.setBlockState(p.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 8; y++) {
                            boolean solid = edge && y <= 3 || rock && y >= 4;
                            world.setBlockState(p.add(x, y, z), solid ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                    }
                    for (int[] g : new int[][] {{-5, -6}, {5, -6}, {-5, 5}, {5, 5}}) world.setBlockState(p.add(g[0], 4, g[1]), Blocks.GLOWSTONE.getDefaultState());
                    for (int x = -3; x <= 3; x++) for (int z = 3; z <= 4; z++) world.setBlockState(p.add(x, -1, z), Blocks.LAVA.getDefaultState());
                    // A water source set into the floor of the open end, for the buckets to be filled again.
                    world.setBlockState(p.add(-10, -1, 0), Blocks.WATER.getDefaultState());
                    f.player().refreshPositionAndAngles(p.getX() - 3.5, p.getY(), p.getZ() + 2.5, 0, 0);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.STICK, 4));
                    inventory.setStack(6, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.BUCKET));
                    feet[0] = p;
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    watch.run();
                    context.assertTrue(lost[0] != null, "A frame cell cast, its water still standing over it: " + seen);
                })
                .createAndAdd(() -> {
                    watch.run();
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertFalse(record != null && record.state() == ActionRecord.State.UNCERTAIN,
                            "The cast whose water was lost left uncertain: " + record + "; " + seen);
                    // Gone on: another frame cell cast, with water it fetched again.
                    long cast = 0;
                    for (BlockPos pos : BlockPos.iterate(feet[0].add(-6, -1, -7), feet[0].add(6, 4, 6))) {
                        if (context.getWorld().getBlockState(pos).isOf(Blocks.OBSIDIAN)) cast++;
                    }
                    context.assertTrue(cast >= 2, "Another frame cell cast after the water was lost at " + lost[0].toShortString() + ": " + seen);
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
