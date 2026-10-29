package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: in a real test world, a player given ancient debris, gold, coal, an upgrade
 * template, a furnace, a crafting table and a smithing table is checked to smelt the debris, make a netherite
 * ingot and upgrade its enchanted diamond sword to netherite, keeping the enchantment, using up exactly those
 * materials and saying it did so.
 *
 * Technical: Netherite (E-16c): a player holding a diamond sword enchanted with Sharpness III, an upgrade
 * template, four ancient debris, four gold ingots, coal, and a furnace, a crafting table and a smithing
 * table, smelts the debris into scrap in a furnace it puts down, makes the scrap and gold into an ingot at a
 * table it puts down, puts its smithing table down and upgrades the sword: a netherite sword, its Sharpness
 * III kept, the template and the ingot spent, and it says so.
 */
public final class NativeNetheriteGameTests implements FabricGameTest {
    private static int count(net.minecraft.inventory.Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "netherite", tickLimit = 4500)
    public void itSmeltsDebrisMakesAnIngotAndUpgradesItsSwordKeepingItsEnchantment(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 4400);
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
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos at = o.add(4, 2, 4);
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    var sword = new ItemStack(Items.DIAMOND_SWORD);
                    sword.addEnchantment(world.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.SHARPNESS).orElseThrow(), 3);
                    inventory.setStack(0, sword);
                    inventory.setStack(1, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
                    inventory.setStack(2, new ItemStack(Items.ANCIENT_DEBRIS, 4));
                    inventory.setStack(3, new ItemStack(Items.GOLD_INGOT, 4));
                    inventory.setStack(4, new ItemStack(Items.COAL, 8));
                    inventory.setStack(5, new ItemStack(Items.FURNACE));
                    inventory.setStack(6, new ItemStack(Items.SMITHING_TABLE));
                    inventory.setStack(7, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(8, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    if (context.getWorld().getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Netherite fixture: debris {}, scrap {}, gold {}, ingots {}; {}", count(inventory, Items.ANCIENT_DEBRIS),
                                count(inventory, Items.NETHERITE_SCRAP), count(inventory, Items.GOLD_INGOT), count(inventory, Items.NETHERITE_INGOT),
                                f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(count(inventory, Items.NETHERITE_SWORD) == 1, "A netherite sword: " + inventory.main);
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    ItemStack sword = ItemStack.EMPTY;
                    for (int slot = 0; slot < 36; slot++) if (inventory.getStack(slot).isOf(Items.NETHERITE_SWORD)) sword = inventory.getStack(slot);
                    var sharpness = context.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.SHARPNESS).orElseThrow();
                    context.assertTrue(sword.getEnchantments().getLevel(sharpness) == 3, "Its Sharpness III kept: " + sword.getEnchantments());
                    context.assertTrue(count(inventory, Items.DIAMOND_SWORD) == 0 && count(inventory, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) == 0
                            && count(inventory, Items.NETHERITE_INGOT) == 0 && count(inventory, Items.ANCIENT_DEBRIS) == 0
                            && count(inventory, Items.GOLD_INGOT) == 0 && count(inventory, Items.NETHERITE_SCRAP) == 0
                            && count(inventory, Items.BREAD) == 8, "Spent as it was made, nothing else: " + inventory.main);
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.equals("I upgraded my diamond sword to netherite.")), "Said: " + f.runtime.said(f.id));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
