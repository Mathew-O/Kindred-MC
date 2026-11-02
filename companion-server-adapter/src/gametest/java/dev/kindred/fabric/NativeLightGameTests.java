package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

/**
 * Lighting (E-06): a player given a work area of open grass twenty-one blocks square, with nothing there to light it,
 * two coal, a stick and four planks, makes torches (from its stick, then from sticks of its planks) and puts them on
 * the floor until no floor cell of the area has block light 0, where hostile mobs spawn; every torch inside the area,
 * no more coal spent than torches it made, and it says so.
 */
public final class NativeLightGameTests implements FabricGameTest {
    /** The area's floor cells no block light reaches. */
    private static int dark(TestContext context, BlockPos min, BlockPos max) {
        var world = context.getWorld();
        int count = 0;
        for (int x = min.getX(); x <= max.getX(); x++) for (int z = min.getZ(); z <= max.getZ(); z++) {
            BlockPos cell = new BlockPos(x, min.getY(), z);
            if (!world.getBlockState(cell).isReplaceable() || !world.getBlockState(cell.down()).isSideSolidFullSquare(world, cell.down(), Direction.UP)) continue;
            if (world.getLightLevel(LightType.BLOCK, cell) == 0) count++;
        }
        return count;
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "light", tickLimit = 6000)
    public void givenAWorkAreaItMakesTorchesAndLightsEveryFloorOfIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5800);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        BlockPos min = o.add(0, 7, 0), max = o.add(20, 7, 20);
        int[] darkAtStart = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(32, 24, 32));
                    for (int cx = (o.getX() - 12) >> 4; cx <= (o.getX() + 32) >> 4; cx++) {
                        for (int cz = (o.getZ() - 12) >> 4; cz <= (o.getZ() + 32) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    for (int x = -12; x <= 32; x++) for (int z = -12; z <= 32; z++) {
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.COAL, 2));
                    inventory.setStack(1, new ItemStack(Items.STICK, 1));
                    inventory.setStack(2, new ItemStack(Items.OAK_PLANKS, 4));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 10.5, o.getY() + 7, o.getZ() + 10.5, 0, 0);
                    // Its work area: the grass, twenty-one square.
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, min.down(), max.down(), "minecraft:grass_block") == 1, "Area given");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Area saved"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    darkAtStart[0] = dark(context, min, max);
                    context.assertTrue(darkAtStart[0] > 400, "The area dark: " + darkAtStart[0]);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    int left = dark(context, min, max);
                    if (context.getWorld().getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Light fixture: {} of {} dark, {}; {}", left, darkAtStart[0], f.player().getInventory().main.subList(0, 9),
                                f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(left == 0, "The area lit: " + left + " dark");
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("I lit the work area:")), "Said: " + f.runtime.said(f.id));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int torches = 0, outside = 0;
                    for (int x = -12; x <= 32; x++) for (int z = -12; z <= 32; z++) {
                        if (!world.getBlockState(o.add(x, 7, z)).isOf(Blocks.TORCH)) continue;
                        torches++;
                        if (x < 0 || x > 20 || z < 0 || z > 20) outside++;
                    }
                    var inventory = f.player().getInventory();
                    int coalSpent = 2 - inventory.count(Items.COAL), held = inventory.count(Items.TORCH);
                    context.assertTrue(torches >= 1 && outside == 0, "Torches in the area only: " + torches + ", " + outside + " outside");
                    context.assertTrue(torches + held <= 4 * coalSpent && coalSpent >= 1, "Torches made of its coal: " + torches + " down, " + held + " held, " + coalSpent + " coal");
                    context.assertTrue(inventory.count(Items.BREAD) == 8, "Nothing else spent: " + inventory.main);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Light fixture: the area lit with {} torches, {} coal spent", torches, coalSpent);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
