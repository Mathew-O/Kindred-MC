package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Fetch and deliver (E-01): asked to bring supplies to another player, a player hands over what it holds and fetches
 * the rest from its assigned chest, carries them over and tosses them across, exactly as many as were asked for and the
 * items as they were; stopped part way and resumed, it hands over no more than it was asked for.
 */
public final class NativeDeliveryGameTests implements FabricGameTest {
    private static ServerPlayerEntity body(TestContext context, UUID nativeId) {
        return context.getWorld().getServer().getPlayerManager().getPlayer(nativeId);
    }

    /** Hal and Wren on a stone floor, Wren eight blocks off; Hal's chest at (2, 2, 2) with what {@code stored} holds. */
    private static void delivery(TestContext context, int tickLimit, ItemStack[] held, ItemStack[] stored, String item, int count,
                                 java.util.function.BiConsumer<NativePaidSkillsGameTests.Fixture, ServerPlayerEntity> check, boolean interrupt) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, tickLimit - 200);
        UUID[] wren = new UUID[2];
        BlockPos chest = context.getAbsolutePos(new BlockPos(2, 2, 2));
        boolean[] stopped = {false};
        var runner = context.createTimedTaskRunner();
        runner.createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> {
                    var found = f.runtime.nativeStatuses(Actor.console()).stream().filter(s -> s.name().equals("Wren")).findFirst();
                    context.assertTrue(found.isPresent() && found.get().phase() == StoredNativePlayer.Phase.ESTABLISHED, "Wren established");
                    var status = found.get();
                    context.assertTrue(body(context, status.nativeId()) != null, "Wren in the world");
                    wren[0] = status.id();
                    wren[1] = status.nativeId();
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -2; x <= 12; x++) for (int z = -2; z <= 12; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(chest, Blocks.CHEST.getDefaultState());
                    var box = (ChestBlockEntity) world.getBlockEntity(chest);
                    for (int i = 0; i < stored.length; i++) box.setStack(i, stored[i].copy());
                    var hal = f.player();
                    hal.getInventory().clear();
                    for (int i = 0; i < held.length; i++) hal.getInventory().setStack(i, held[i].copy());
                    hal.getInventory().setStack(8, new ItemStack(Items.BREAD, 4));
                    hal.refreshPositionAndAngles(o.getX() + 3.5, o.getY() + 2, o.getZ() + 4.5, 0, 0);
                    var receiver = body(context, wren[1]);
                    receiver.getInventory().clear();
                    receiver.refreshPositionAndAngles(o.getX() + 10.5, o.getY() + 2, o.getZ() + 9.5, 0, 0);
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.nativeStorage(f.source, f.id, chest) == 1, "Storage given");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Storage saved"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeDeliver(f.source, f.id, item, count, wren[1]) == 1, "Asked to bring " + count + " " + item);
                });
        if (interrupt) {
            // Stopped once the first stack is handed over, and resumed.
            runner.createAndAdd(() -> {
                var status = f.runtime.deliveryStatus(f.id);
                context.assertTrue(status != null && status[0] > 0, "Part handed over: " + java.util.Arrays.toString(status));
                f.runtime.nativeLifecycle(f.source, "stop", f.id);
                stopped[0] = true;
            }).createAndAdd(() -> {
                var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                context.assertTrue(activity.phase().equals("IDLE"), "Settled: " + activity);
                f.runtime.nativeLifecycle(f.source, "resume", f.id);
            });
        }
        runner.createAndAdd(() -> {
                    var receiver = body(context, wren[1]);
                    if (context.getWorld().getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Delivery fixture: {} has {}; Hal {}; {}", "Wren", receiver.getInventory().main.subList(0, 4),
                                f.player().getInventory().main.subList(0, 9), f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(f.runtime.deliveryStatus(f.id) == null, "Delivered: " + java.util.Arrays.toString(f.runtime.deliveryStatus(f.id)));
                })
                .createAndAdd(() -> {
                    check.accept(f, body(context, wren[1]));
                    var box = (ChestBlockEntity) context.getWorld().getBlockEntity(chest);
                    KindredMod.LOGGER.info("Delivery fixture: done; the chest holds {}", box.getStack(0));
                    context.assertTrue(f.player().getInventory().count(Items.BREAD) >= 4 || item.equals("minecraft:bread"), "Its own bread kept");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deliver", tickLimit = 2400)
    public void askedForTenIronItBringsTheFiveItHoldsAndFiveFromItsChest(TestContext context) {
        BlockPos chest = context.getAbsolutePos(new BlockPos(2, 2, 2));
        delivery(context, 2400, new ItemStack[]{new ItemStack(Items.IRON_INGOT, 5)}, new ItemStack[]{new ItemStack(Items.IRON_INGOT, 8)},
                "minecraft:iron_ingot", 10, (f, wren) -> {
                    context.assertTrue(wren.getInventory().count(Items.IRON_INGOT) == 10, "Wren has ten iron: " + wren.getInventory().main);
                    context.assertTrue(f.player().getInventory().count(Items.IRON_INGOT) == 0, "Hal none left: " + f.player().getInventory().main);
                    var box = (ChestBlockEntity) context.getWorld().getBlockEntity(chest);
                    int left = 0;
                    for (int i = 0; i < box.size(); i++) if (box.getStack(i).isOf(Items.IRON_INGOT)) left += box.getStack(i).getCount();
                    context.assertTrue(left == 3, "Three left in its chest: " + left);
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.equals("I brought you 10 iron ingots.")), "Said: " + f.runtime.said(f.id));
                }, false);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deliverKit", tickLimit = 2400)
    public void anEnchantedNamedSwordArrivesAsItWas(TestContext context) {
        var sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Gleamfang"));
        sword.setDamage(17);
        var registry = context.getWorld().getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
        sword.addEnchantment(registry.getEntry(registry.get(net.minecraft.enchantment.Enchantments.SHARPNESS)), 3);
        delivery(context, 2400, new ItemStack[0], new ItemStack[]{sword}, "minecraft:diamond_sword", 1, (f, wren) -> {
            ItemStack got = ItemStack.EMPTY;
            for (int i = 0; i < wren.getInventory().size(); i++) if (wren.getInventory().getStack(i).isOf(Items.DIAMOND_SWORD)) got = wren.getInventory().getStack(i);
            context.assertTrue(ItemStack.areItemsAndComponentsEqual(got, sword), "The same sword: " + got.getComponents() + " for " + sword.getComponents());
        }, false);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deliverStopped", tickLimit = 3000)
    public void stoppedPartWayAndResumedItHandsOverNoMoreThanAskedFor(TestContext context) {
        delivery(context, 3000, new ItemStack[]{new ItemStack(Items.COOKED_BEEF, 12), new ItemStack(Items.COOKED_BEEF, 8)}, new ItemStack[0],
                "minecraft:cooked_beef", 20, (f, wren) -> {
                    context.assertTrue(wren.getInventory().count(Items.COOKED_BEEF) == 20, "Wren has twenty: " + wren.getInventory().main);
                    context.assertTrue(f.player().getInventory().count(Items.COOKED_BEEF) == 0, "Hal none: " + f.player().getInventory().main);
                }, true);
    }
}
