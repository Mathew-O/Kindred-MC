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
 * In plain terms: a GameTest: in a real test world, a hungry player with no food and no animals in sight,
 * working towards a Nether portal, is checked to walk to a nearby pond and fish, either with the rod it holds
 * or with one it first makes from string and sticks. The test checks it caught something, the rod shows wear
 * from the catch, and its line is reeled in once it stops.
 *
 * Technical: Fishing (E-05): a player on its run, hungry with no food and no animal in sight, a pond a few
 * blocks off, walks to the water, casts, waits the bite out and reels in, with the rod it holds or one it
 * makes of string and sticks. What it caught is in its inventory as the fishing loot gives it, the rod worn
 * by its catch, and its line reeled in once it stops.
 */
public final class NativeFishGameTests implements FabricGameTest {
    private static int caught(net.minecraft.entity.player.PlayerInventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (!stack.isEmpty() && !stack.isOf(Items.FISHING_ROD)) total += stack.getCount();
        }
        return total;
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "fish", tickLimit = 7000)
    public void hungryWithNoAnimalInSightItFishesAtThePondWithItsRod(TestContext context) {
        fish(context, false);
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "fishRod", tickLimit = 7000)
    public void withNoRodItMakesOneOfStringAndSticksAndFishes(TestContext context) {
        fish(context, true);
    }

    /**
     * Builds a field with a pond, leaves the player hungry with a rod (or string and sticks to make one) and
     * checks that it fishes.
     */
    private static void fish(TestContext context, boolean makesRod) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 6800);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        int[] wear = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(28, 24, 24));
                    for (int cx = (o.getX() - 12) >> 4; cx <= (o.getX() + 28) >> 4; cx++) {
                        for (int cz = (o.getZ() - 12) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    for (int x = -12; x <= 28; x++) for (int z = -12; z <= 24; z++) {
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // A pond five by five and two deep, ten blocks east of where it stands, open to the sky.
                    for (int x = 14; x <= 18; x++) for (int z = 2; z <= 6; z++) for (int y = 5; y <= 6; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.WATER.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    if (makesRod) {
                        inventory.setStack(0, new ItemStack(Items.STRING, 2));
                        inventory.setStack(1, new ItemStack(Items.STICK, 3));
                        inventory.setStack(2, new ItemStack(Items.CRAFTING_TABLE));
                    } else {
                        inventory.setStack(0, new ItemStack(Items.FISHING_ROD));
                    }
                    inventory.selectedSlot = 0;
                    player.setHealth(20);
                    // Hungry, and nothing to eat.
                    player.getHungerManager().setFoodLevel(8);
                    player.getHungerManager().setSaturationLevel(0);
                    player.refreshPositionAndAngles(o.getX() + 4.5, o.getY() + 7, o.getZ() + 4.5, 0, 0);
                    // On its run: out of food, its goal hunts, and with no animal in sight it fishes.
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    if (context.getWorld().getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Fishing fixture: at {}, line {}, caught {}, food {}; {}", player.getBlockPos().toShortString(),
                                player.fishHook == null ? "in" : "out at " + player.fishHook.getBlockPos().toShortString(), caught(player.getInventory()),
                                player.getHungerManager().getFoodLevel(), f.runtime.nativeLastResult(f.id));
                    }
                    var inventory = player.getInventory();
                    int rods = 0;
                    for (int slot = 0; slot < inventory.size(); slot++) {
                        if (inventory.getStack(slot).isOf(Items.FISHING_ROD)) {
                            rods++;
                            wear[0] = inventory.getStack(slot).getDamage();
                        }
                    }
                    // What it caught, besides what it made the rod of and the table it put down.
                    int other = caught(inventory) - inventory.count(Items.STRING) - inventory.count(Items.STICK) - inventory.count(Items.CRAFTING_TABLE);
                    context.assertTrue(rods == 1 && (other >= 1 || player.getHungerManager().getFoodLevel() > 8),
                            "Something caught: " + inventory.main);
                    if (makesRod) {
                        context.assertTrue(inventory.count(Items.STRING) == 0 && inventory.count(Items.STICK) == 0, "Its rod made of its string and sticks: " + inventory.main);
                    }
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    // A catch reeled in wears the rod one; a rod that never wore never caught anything.
                    context.assertTrue(wear[0] >= 1, "The rod worn by its catch: " + wear[0] + " / " + player.getInventory().main);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.shutdownComplete(), "Cleanup");
                    context.assertTrue(f.player() == null || f.player().fishHook == null, "Its line reeled in");
                })
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
