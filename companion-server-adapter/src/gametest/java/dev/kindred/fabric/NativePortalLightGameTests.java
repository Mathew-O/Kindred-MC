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

/** Lighting the portal once its frame stands: from within reach of the frame's opening (F-25c4bh). */
public class NativePortalLightGameTests implements FabricGameTest {
    /**
     * Its frame cast, then off to gravel for flint some way off: it comes back to the frame to light it, rather than try
     * from where it made its flint and steel (F-25c4bh). On 505 in E-40's iteration 4 (`e40a4-c237a6e`) Sol cast its frame
     * at Y=-47, walked to gravel at 39, -54, 57, dug it until it dropped flint, made its flint and steel there and tried
     * to light the portal from there: 192 times refused as out of reach. Then it climbed away and never lit it. Only the
     * cast route stands its frame before its flint; the mined route makes its flint and steel first. Staged: a field of
     * stone 21 square, walled round, open to the sky, daylight held; a lava pool in its floor at the north end and three
     * gravel in its south-east corner, twelve or more blocks from any room for a frame by the pool; the player by the
     * pool with the cast route's kit (an iron pickaxe, cobblestone for the corners and moulds, a bucket and a water
     * bucket) and an iron ingot for the flint and steel, but no flint. Passes once a lit portal stands. On `5a48c43` it
     * tried to light it from the gravel, was refused as out of reach, wandered off and died.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "portalFlintAway", tickLimit = 9000)
    public void itsFlintFoundAwayFromItsFrameItComesBackToLightThePortal(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8900);
        BlockPos[] origin = new BlockPos[1];
        boolean[] died = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Lamplighter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos o = origin[0] = context.getAbsolutePos(new BlockPos(30, 22, 30)).toImmutable();
                    for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        boolean edge = Math.abs(x) == 10 || Math.abs(z) == 10;
                        for (int y = 0; y <= 8; y++) world.setBlockState(o.add(x, y, z), edge && y <= 3 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    for (int x = -3; x <= 3; x++) for (int z = -8; z <= -7; z++) world.setBlockState(o.add(x, -1, z), Blocks.LAVA.getDefaultState());
                    for (BlockPos g : java.util.List.of(o.add(8, 0, 8), o.add(7, 0, 8), o.add(8, 0, 7))) world.setBlockState(g, Blocks.GRAVEL.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() - 4.5, 180, 0);
                    player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(origin[0].add(0, 0, -5)), "By the pool"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.STICK, 4));
                    inventory.setStack(6, new ItemStack(Items.IRON_INGOT));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.BUCKET));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    var player = f.player();
                    if (player == null || !player.isAlive()) died[0] = true;
                    context.assertTrue(!died[0], "The player died before its portal was lit; " + progress);
                    long portalBlocks = BlockPos.streamOutwards(origin[0], 10, 8, 10)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.NETHER_PORTAL)).count();
                    context.assertTrue(portalBlocks == 6, "No lit portal yet: at " + player.getBlockPos().subtract(origin[0]).toShortString()
                            + "; " + progress + "; " + f.runtime.nativeActivityStatus(Actor.console(), f.id));
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.FLINT_AND_STEEL) == 1, "Its own flint and steel, made from flint it dug");
                    KindredMod.LOGGER.info("Flint away: portal lit, the player at {} from the field's middle", f.player().getBlockPos().subtract(origin[0]).toShortString());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
