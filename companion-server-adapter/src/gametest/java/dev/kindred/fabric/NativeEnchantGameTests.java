package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * Enchanting (E-16a): a player with thirty levels, lapis, a diamond sword and an enchanting table of its own puts its
 * table down inside a ring of fifteen bookshelves where it stands, opens it, and takes the best offer it can pay for on
 * its sword: the sword enchanted, the lapis and the levels spent alike (the button's number and one), nothing else
 * changed, and it says what it gained. Short of the levels, it does not go to the table.
 */
public final class NativeEnchantGameTests implements FabricGameTest {
    private static int count(net.minecraft.inventory.Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    /**
     * Hal on stone inside a ring of fifteen bookshelves about the spot north of it, open to the south, holding a diamond
     * sword, nine lapis, an enchanting table and bread, at {@code levels} levels, playing; then {@code checks}.
     */
    private static void scene(TestContext context, NativePaidSkillsGameTests.Fixture f, int levels, Runnable checks) {
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 10; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // Fifteen bookshelves two out round the table's spot, the middle of the south side left open.
                    BlockPos centre = o.add(5, 2, 5);
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != 2 || dx == 0 && dz == 2) continue;
                        world.setBlockState(centre.add(dx, 0, dz), Blocks.BOOKSHELF.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos at = centre.south();
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.DIAMOND_SWORD));
                    inventory.setStack(1, new ItemStack(Items.LAPIS_LAZULI, 9));
                    inventory.setStack(2, new ItemStack(Items.ENCHANTING_TABLE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.selectedSlot = 0;
                    player.setExperienceLevel(levels);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(checks)
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "enchant", tickLimit = 2400)
    public void itPutsItsTableDownInItsBookshelvesAndEnchantsItsSwordWithTheBestOfferItCanPayFor(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        String[] first = {null};
        scene(context, f, 30, () -> {
            var player = f.player();
            var inventory = player.getInventory();
            var sword = inventory.getStack(0);
            context.assertTrue(sword.isOf(Items.DIAMOND_SWORD) && sword.hasEnchantments(), "The sword enchanted: " + sword + " / " + f.runtime.nativeLastResult(f.id));
            var world = context.getWorld();
            BlockPos table = context.getAbsolutePos(new BlockPos(5, 2, 5));
            int spent = 30 - player.experienceLevel, lapis = count(inventory, Items.LAPIS_LAZULI);
            KindredMod.LOGGER.info("Enchanting: the sword {}, {} levels spent, {} lapis left", sword.getEnchantments(), spent, lapis);
            if (first[0] == null && !world.getBlockState(table).isOf(Blocks.ENCHANTING_TABLE)) first[0] = "Its table put down in the ring: " + world.getBlockState(table);
            if (first[0] == null && !(spent >= 1 && spent <= 3 && lapis == 9 - spent)) first[0] = "The lapis and levels spent alike: " + spent + " levels, " + lapis + " lapis left";
            if (first[0] == null && !(count(inventory, Items.BREAD) == 8 && count(inventory, Items.ENCHANTING_TABLE) == 0)) first[0] = "Nothing else changed: " + inventory.main;
            context.assertTrue(first[0] == null, first[0]);
            // Said once the action is finished, a tick or so after the sword shows it.
            context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("I enchanted my diamond sword with")),
                    "Said what it gained: " + f.runtime.said(f.id));
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "enchantShort", tickLimit = 1200)
    public void shortOfThirtyLevelsItLeavesItsTableInHand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        long[] since = {-1};
        scene(context, f, 20, () -> {
            var world = context.getWorld();
            if (since[0] < 0) since[0] = world.getTime();
            var inventory = f.player().getInventory();
            context.assertTrue(!inventory.getStack(0).hasEnchantments() && count(inventory, Items.ENCHANTING_TABLE) == 1
                    && f.player().experienceLevel == 20 && count(inventory, Items.LAPIS_LAZULI) == 9, "Nothing enchanted or put down: " + inventory.main);
            context.assertTrue(world.getTime() - since[0] >= 300, "A while looked at");
        });
    }
}
