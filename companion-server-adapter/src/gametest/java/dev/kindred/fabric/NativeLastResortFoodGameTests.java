package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** Rotten flesh and raw chicken as food of last resort (F-25c4bs). */
public class NativeLastResortFoodGameTests implements FabricGameTest {
    /**
     * Hurt, its bar under 18 so that it does not heal, and nothing to eat but rotten flesh: it eats the flesh. In E-40's
     * iteration 8 (`e40a8-aed3e82`) 20 of the 23 deaths came with the bar under 18; Echo on 101 died at two health and two
     * hunger holding four rotten flesh, and Echo on 404 at four health and six hunger holding a raw chicken, neither ever
     * eaten (F-25c4bs). Staged: health 8 of 20, hunger 14, no saturation; four rotten flesh in its pack and a stone pickaxe
     * in hand, nothing else. Passes once one of the four is eaten and its bar has risen.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lastResortFood", tickLimit = 400)
    public void hurtWithNothingElseToEatItEatsRottenFlesh(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Scavenger"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.setHealth(8.0f);
                    player.getHungerManager().setFoodLevel(14);
                    player.getHungerManager().setSaturationLevel(0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(14, new ItemStack(Items.ROTTEN_FLESH, 4));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(count(f, Items.ROTTEN_FLESH) == 3 && f.player().getHungerManager().getFoodLevel() > 14,
                        "One rotten flesh eaten: " + count(f, Items.ROTTEN_FLESH) + " left, hunger " + f.player().getHungerManager().getFoodLevel()))
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * With bread held as well, the bread is eaten, not the rotten flesh: the flesh is for when there is nothing better
     * (F-25c4bs). Staged: as above, with two bread in its pack beside the flesh. Passes once a bread is eaten with all
     * four rotten flesh still held.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lastResortFoodBread", tickLimit = 400)
    public void hurtWithBreadAndRottenFleshItEatsTheBread(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Picky"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.setHealth(8.0f);
                    player.getHungerManager().setFoodLevel(14);
                    player.getHungerManager().setSaturationLevel(0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(14, new ItemStack(Items.ROTTEN_FLESH, 4));
                    inventory.setStack(15, new ItemStack(Items.BREAD, 2));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(count(f, Items.BREAD) == 1,
                        "One bread eaten: " + count(f, Items.BREAD) + " left, " + count(f, Items.ROTTEN_FLESH) + " rotten flesh"))
                .createAndAdd(() -> {
                    context.assertTrue(count(f, Items.ROTTEN_FLESH) == 4, "The rotten flesh kept: " + count(f, Items.ROTTEN_FLESH));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static int count(NativePaidSkillsGameTests.Fixture f, Item item) {
        return ItemMoves.count(f.player().getInventory(), item);
    }
}
