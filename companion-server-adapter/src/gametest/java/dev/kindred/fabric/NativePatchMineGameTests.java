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

/**
 * Mining an approved patch (E-03): a player given a patch of six iron ore, open to the air, mines every block of it
 * with the pickaxe that harvests iron (its stone one, not its wooden one) and picks up the raw iron each drops; then
 * says it mined the patch out. With only a wooden pickaxe it mines nothing, says why, and a mine ordered with it is
 * refused, as nothing would drop.
 */
public final class NativePatchMineGameTests implements FabricGameTest {
    private static void scene(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos o, boolean stone) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-6, 0, -6), o.add(18, 16, 18));
        for (int x = -6; x <= 18; x++) for (int z = -6; z <= 18; z++) {
            for (int y = 0; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
            for (int y = 7; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        // A face of iron ore three wide and two high, standing on the stone floor, open to the air on every side.
        for (int x = 5; x <= 7; x++) for (int y = 7; y <= 8; y++) world.setBlockState(o.add(x, y, 8), Blocks.IRON_ORE.getDefaultState());
        world.setTimeOfDay(1000);
        var player = f.player();
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
        if (stone) inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
        inventory.setStack(2, new ItemStack(Items.BREAD, 8));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        player.refreshPositionAndAngles(o.getX() + 6.5, o.getY() + 7, o.getZ() + 4.5, 0, 0);
        context.assertTrue(f.runtime.nativePatch(f.source, f.id, o.add(5, 7, 8), o.add(7, 8, 8), "minecraft:iron_ore") == 1, "Patch given");
    }

    private static int ore(TestContext context, BlockPos o) {
        int count = 0;
        for (int x = 5; x <= 7; x++) for (int y = 7; y <= 8; y++) if (context.getWorld().getBlockState(o.add(x, y, 8)).isOf(Blocks.IRON_ORE)) count++;
        return count;
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "patchMine", tickLimit = 4000)
    public void itMinesItsPatchOfIronWithThePickaxeThatHarvestsItAndPicksUpTheRawIron(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3800);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> scene(context, f, o, true))
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch saved"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    if (context.getWorld().getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Patch fixture: {} ore left, {} raw iron; {}", ore(context, o), inventory.count(Items.RAW_IRON), f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(ore(context, o) == 0 && inventory.count(Items.RAW_IRON) == 6, "Mined out, six raw iron: " + ore(context, o) + " left, " + inventory.main);
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.equals("I mined out the patch: 6 iron ore.")), "Said: " + f.runtime.said(f.id));
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    var wooden = inventory.getStack(0);
                    int stoneWear = -1;
                    for (int slot = 0; slot < 36; slot++) if (inventory.getStack(slot).isOf(Items.STONE_PICKAXE)) stoneWear = inventory.getStack(slot).getDamage();
                    context.assertTrue(wooden.isOf(Items.WOODEN_PICKAXE) && wooden.getDamage() == 0, "The wooden pickaxe unused: " + wooden);
                    context.assertTrue(stoneWear == 6, "The stone pickaxe worn six: " + stoneWear);
                    context.assertTrue(inventory.count(Items.BREAD) == 8, "Nothing else spent: " + inventory.main);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "patchMineTier", tickLimit = 1600)
    public void withOnlyAWoodenPickaxeItLeavesTheIronAndAMineWithItIsRefused(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        long[] since = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> scene(context, f, o, false))
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch saved"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    // Ordered to mine with the wooden pickaxe: refused before anything is done.
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, o.add(6, 7, 8), 0) == 0, "A mine with the wooden pickaxe refused");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    if (since[0] < 0) since[0] = world.getTime();
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.equals("None of my pickaxes can mine iron ore.")), "Said: " + f.runtime.said(f.id));
                    context.assertTrue(world.getTime() - since[0] >= 300, "A while on");
                })
                .createAndAdd(() -> {
                    context.assertTrue(ore(context, o) == 6 && f.player().getInventory().getStack(0).getDamage() == 0, "Nothing mined: " + ore(context, o) + " ore");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
