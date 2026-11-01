package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: in a real test world, a player beside a small pool with empty bottles, nether
 * wart, magma cream, blaze powder and a brewing stand is checked to fill its bottles with water, put the
 * stand down and brew three potions of fire resistance, using up exactly those ingredients and saying it did
 * so.
 *
 * Technical: Brewing (E-16b): a player holding three glass bottles, nether wart, magma cream, blaze powder
 * and a brewing stand, by a pool, fills its bottles at the water, puts its stand down beside it, makes the
 * water awkward with the wart (the stand fuelled with the powder), waits the brew out, takes the awkward
 * potions, brews them into fire resistance with the cream and takes those: three potions of fire resistance,
 * the wart, the cream and the powder spent, and it says so.
 */
public final class NativeBrewGameTests implements FabricGameTest {
    private static int count(net.minecraft.inventory.Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    private static int potions(net.minecraft.inventory.Inventory inventory, String potion) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (stack.isOf(Items.POTION) && contents != null && contents.potion().flatMap(p -> p.getKey()).map(k -> k.getValue().toString()).orElse("").equals(potion)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "brew", tickLimit = 4000)
    public void itFillsBottlesAtThePoolPutsItsStandDownAndBrewsFireResistance(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3900);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 10; z++) {
                        world.setBlockState(o.add(x, 0, z), Blocks.STONE.getDefaultState());
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // A pool of four still water in the floor, east of where it stands.
                    for (int x = 7; x <= 8; x++) for (int z = 5; z <= 6; z++) world.setBlockState(o.add(x, 1, z), Blocks.WATER.getDefaultState());
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos at = o.add(4, 2, 5);
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.GLASS_BOTTLE, 3));
                    inventory.setStack(1, new ItemStack(Items.NETHER_WART));
                    inventory.setStack(2, new ItemStack(Items.MAGMA_CREAM));
                    inventory.setStack(3, new ItemStack(Items.BLAZE_POWDER));
                    inventory.setStack(4, new ItemStack(Items.BREWING_STAND));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    if (context.getWorld().getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Brewing fixture: water {}, awkward {}, fire {}, bottles {}; {}", potions(inventory, "minecraft:water"),
                                potions(inventory, "minecraft:awkward"), potions(inventory, "minecraft:fire_resistance"), count(inventory, Items.GLASS_BOTTLE),
                                f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(potions(inventory, "minecraft:fire_resistance") == 3, "Three potions of fire resistance: " + inventory.main);
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(count(inventory, Items.NETHER_WART) == 0 && count(inventory, Items.MAGMA_CREAM) == 0
                            && count(inventory, Items.BLAZE_POWDER) == 0 && count(inventory, Items.GLASS_BOTTLE) == 0
                            && count(inventory, Items.BREWING_STAND) == 0 && count(inventory, Items.BREAD) == 8, "Spent as it brewed, nothing else: " + inventory.main);
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.equals("I brewed three potions of fire resistance.")), "Said: " + f.runtime.said(f.id));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
