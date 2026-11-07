package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** A frame cast on snowy ground: the snow in its cells is cleared by hand, and the site is not given up (F-25c4bz). */
public final class NativeSnowSiteGameTests implements FabricGameTest {
    /**
     * On ground under a layer of snow, the cast clears the snow from the frame's cells with a bare hand, as a player
     * does, and casts its frame there. A snow layer needs a shovel to drop anything, and the cast took a block no tool it
     * carried could harvest for one it could not open: in E-40's iteration 11 (`e40a11-744c636`) Sol on 707, its lava in
     * hand and two corners set, gave its frame site up for a layer of snow in a cell ("cannot open 168, 64, 43", the
     * snow), chose the same site again and gave it up again, until the night, and never cast a frame in the run. Staged:
     * a field of stone 21 square, walled round, open to the sky, daylight held, a layer of snow over its floor; a lava
     * pool in its floor at the north end; the player by the pool with the cast route's kit (an iron pickaxe, cobblestone
     * for the corners and moulds, a bucket and a water bucket) and flint and steel, and no shovel. Passes once a lit
     * portal stands.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "snowSite", tickLimit = 9000)
    public void onSnowyGroundTheCastClearsTheSnowByHandAndCastsItsFrame(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8900);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 22, 30)).toImmutable();
        boolean[] died = new boolean[1];
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Snowfoot"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-10, -3, -10), o.add(10, 8, 10));
                    var snow = Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 1);
                    for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        boolean edge = Math.abs(x) == 10 || Math.abs(z) == 10;
                        for (int y = 0; y <= 8; y++) world.setBlockState(o.add(x, y, z), edge && y <= 3 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        if (!edge) world.setBlockState(o.add(x, 0, z), snow);
                    }
                    for (int x = -3; x <= 3; x++) for (int z = -8; z <= -7; z++) world.setBlockState(o.add(x, -1, z), Blocks.LAVA.getDefaultState());
                    for (int x = -3; x <= 3; x++) for (int z = -8; z <= -7; z++) world.setBlockState(o.add(x, 0, z), Blocks.AIR.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() - 4.5, 180, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o.add(0, 0, -5)), "By the pool, on the snow"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.BUCKET));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    if (player != null && player.isDead()) died[0] = true;
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    context.assertTrue(player != null, "Its time out with no lit portal: " + seen + " now " + progress);
                    if (watched[0]++ % 400 == 0) {
                        seen.append(watched[0]).append(": ").append(progress).append(" @ ").append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    }
                    context.assertTrue(!died[0], "The player died before its portal was lit; " + progress);
                    long portalBlocks = BlockPos.streamOutwards(o, 10, 8, 10).filter(pos -> world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)).count();
                    context.assertTrue(portalBlocks == 6, "No lit portal yet: " + seen + " now " + progress + "; " + f.runtime.nativeActivityStatus(Actor.console(), f.id));
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Snow site: portal lit after {} ticks, the player at {}", watched[0], f.player().getBlockPos().subtract(o).toShortString());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
