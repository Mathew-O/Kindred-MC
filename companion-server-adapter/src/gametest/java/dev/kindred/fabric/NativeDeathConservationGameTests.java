package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.AfterBatch;
import net.minecraft.test.BeforeBatch;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F-07b: a native player's real stacks across death and vanilla respawn. Without keepInventory every stack, with its
 * components, drops once and nothing is restored; with it, every stack comes back to its own slot and nothing drops.
 */
public final class NativeDeathConservationGameTests implements FabricGameTest {
    private static final String KEEP = "keepInventory";
    private static boolean keptBefore;

    /** Where each stack was held: inventory slots 0..35, then the worn chest and the offhand. */
    private record Held(Map<Integer, ItemStack> inventory, ItemStack chest, ItemStack offhand) {
        List<ItemStack> all() {
            var stacks = new ArrayList<>(inventory.values());
            stacks.add(chest);
            stacks.add(offhand);
            return stacks;
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void everyStackItDiesWithDropsOnceWithItsComponents(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos spawn = context.getAbsolutePos(new BlockPos(1, 2, 6));
        ServerPlayerEntity[] dead = new ServerPlayerEntity[1];
        Box[] dropArea = new Box[1];
        Held[] held = new Held[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ashes"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().setSpawnPoint(context.getWorld().getRegistryKey(), spawn, 0, true, false);
                    held[0] = equip(context, f.player());
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    dead[0] = f.player();
                    dropArea[0] = dead[0].getBoundingBox().expand(8, 4, 8);
                    dead[0].kill();
                    context.assertFalse(dead[0].isAlive(), "Killed");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned as a fresh body");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh.getInventory().isEmpty(), "Nothing restored into the respawned body, worn or held");
                    context.assertTrue(dead[0].getInventory().isEmpty(), "Nothing kept on the dead body either");
                    var drops = context.getWorld().getEntitiesByClass(ItemEntity.class, dropArea[0], drop -> true);
                    for (ItemStack stack : held[0].all()) {
                        int found = drops.stream().filter(drop -> ItemStack.areItemsAndComponentsEqual(drop.getStack(), stack))
                                .mapToInt(drop -> drop.getStack().getCount()).sum();
                        context.assertTrue(found == stack.getCount(), "Dropped once, exactly: " + stack + " (" + stack.getComponentChanges() + "), found " + found);
                    }
                    int total = drops.stream().mapToInt(drop -> drop.getStack().getCount()).sum();
                    int expected = held[0].all().stream().mapToInt(ItemStack::getCount).sum();
                    context.assertTrue(total == expected, "Nothing else dropped and nothing copied: " + total + " of " + expected);
                    var status = f.runtime.nativeStatuses(dev.kindred.core.Actor.console()).getFirst();
                    context.assertTrue(status.connected() && !status.stopped(), "Still connected and not halted: " + status);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @BeforeBatch(batchId = KEEP)
    public void keepInventory(ServerWorld world) {
        var rule = world.getGameRules().get(GameRules.KEEP_INVENTORY);
        keptBefore = rule.get();
        rule.set(true, world.getServer());
    }

    /** Runs when the batch ends however its test ended, so no other test dies with the rule left on. */
    @AfterBatch(batchId = KEEP)
    public void restoreInventoryRule(ServerWorld world) {
        world.getGameRules().get(GameRules.KEEP_INVENTORY).set(keptBefore, world.getServer());
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = KEEP, tickLimit = 400)
    public void withKeepInventoryEveryStackComesBackToItsOwnSlot(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos spawn = context.getAbsolutePos(new BlockPos(1, 2, 6));
        ServerPlayerEntity[] dead = new ServerPlayerEntity[1];
        Box[] dropArea = new Box[1];
        Held[] held = new Held[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> context.assertTrue(context.getWorld().getGameRules().getBoolean(GameRules.KEEP_INVENTORY), "keepInventory on for this batch"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Keeper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().setSpawnPoint(context.getWorld().getRegistryKey(), spawn, 0, true, false);
                    held[0] = equip(context, f.player());
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    dead[0] = f.player();
                    dropArea[0] = dead[0].getBoundingBox().expand(8, 4, 8);
                    dead[0].kill();
                    context.assertFalse(dead[0].isAlive(), "Killed");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned as a fresh body");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    for (var kept : held[0].inventory().entrySet()) {
                        var now = fresh.getInventory().getStack(kept.getKey());
                        context.assertTrue(same(now, kept.getValue()), "Slot " + kept.getKey() + " kept exactly: " + now + " for " + kept.getValue());
                    }
                    context.assertTrue(same(fresh.getEquippedStack(EquipmentSlot.CHEST), held[0].chest()), "Armour still worn, exactly");
                    context.assertTrue(same(fresh.getEquippedStack(EquipmentSlot.OFFHAND), held[0].offhand()), "Offhand kept, exactly");
                    int other = 0;
                    for (int slot = 0; slot < 36; slot++) {
                        if (!held[0].inventory().containsKey(slot) && !fresh.getInventory().getStack(slot).isEmpty()) other++;
                    }
                    context.assertTrue(other == 0, "Nothing added to other slots");
                    context.assertTrue(context.getWorld().getEntitiesByClass(ItemEntity.class, dropArea[0], drop -> true).isEmpty(),
                            "Nothing dropped, so nothing exists twice");
                    var status = f.runtime.nativeStatuses(dev.kindred.core.Actor.console()).getFirst();
                    context.assertTrue(status.connected() && !status.stopped(), "Still connected and not halted: " + status);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A worn, a damaged, a named, an enchanted, an offhand and a stacked item; returns copies of what was held. */
    private static Held equip(TestContext context, ServerPlayerEntity player) {
        var inventory = player.getInventory();
        inventory.clear();
        var pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.setDamage(77);
        pickaxe.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Old Faithful"));
        var efficiency = context.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.EFFICIENCY).orElseThrow();
        pickaxe.addEnchantment(efficiency, 3);
        inventory.setStack(0, pickaxe);
        inventory.setStack(5, new ItemStack(Items.COBBLESTONE, 37));
        inventory.setStack(30, new ItemStack(Items.BREAD, 16));
        var chest = new ItemStack(Items.IRON_CHESTPLATE);
        chest.setDamage(10);
        player.equipStack(EquipmentSlot.CHEST, chest);
        var shield = new ItemStack(Items.SHIELD);
        shield.setDamage(3);
        player.equipStack(EquipmentSlot.OFFHAND, shield);
        return new Held(Map.of(0, pickaxe.copy(), 5, new ItemStack(Items.COBBLESTONE, 37), 30, new ItemStack(Items.BREAD, 16)),
                chest.copy(), shield.copy());
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return ItemStack.areItemsAndComponentsEqual(a, b) && a.getCount() == b.getCount();
    }
}
