package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Extended inventory (E-43): a player puts what it keeps for later in a store of its own and counts it with what it
 * holds. Alone, when it opens its chest again and something it put there is gone, with nobody seen at it, it reckons
 * the thing gone and says so to those it plays for, blaming nobody. Sharing a chest with a teammate, it tells the team
 * what it put in, and the teammate's count changes with it; when something is gone, it asks its team, and with no word
 * in time reckons it gone, blaming nobody.
 */
public final class NativeStorageGameTests implements FabricGameTest {
    private static final BlockPos HAL = new BlockPos(3, 2, 3), CHEST = new BlockPos(5, 2, 3);

    private static int count(net.minecraft.inventory.Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    private static ChestBlockEntity chest(TestContext context) {
        return (ChestBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(CHEST));
    }

    private static void take(ChestBlockEntity chest, Item item) {
        for (int slot = 0; slot < chest.size(); slot++) if (chest.getStack(slot).isOf(item)) chest.setStack(slot, ItemStack.EMPTY);
        chest.markDirty();
    }

    private static void ground(TestContext context) {
        var world = context.getWorld();
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        for (int x = -2; x <= 10; x++) for (int z = -2; z <= 10; z++) {
            world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
            for (int y = 2; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        world.setBlockState(context.getAbsolutePos(CHEST), Blocks.CHEST.getDefaultState());
        world.setTimeOfDay(1000);
    }

    /** Hal holding what it keeps for later (a saddle, a name tag, an enchanted book) and what it keeps on it. */
    private static void kit(ServerPlayerEntity player, TestContext context) {
        BlockPos at = context.getAbsolutePos(HAL);
        player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
        inventory.setStack(1, new ItemStack(Items.BREAD, 8));
        inventory.setStack(2, new ItemStack(Items.DIRT, 16));
        inventory.setStack(3, new ItemStack(Items.SADDLE));
        inventory.setStack(4, new ItemStack(Items.NAME_TAG));
        inventory.setStack(5, new ItemStack(Items.ENCHANTED_BOOK));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
    }

    private static String where(TestContext context) {
        BlockPos at = context.getAbsolutePos(CHEST);
        return at.getX() + ", " + at.getY() + ", " + at.getZ();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "storeAlone", tickLimit = 3000)
    public void aloneItFindsWhatIsGoneFromItsChestAndSaysSoBlamingNobody(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    ground(context);
                    kit(f.player(), context);
                    context.assertTrue(f.runtime.nativeStorage(f.source, f.id, context.getAbsolutePos(CHEST)) == 1, "Its storage assigned");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Resumed, playing"))
                .createAndAdd(() -> {
                    var inside = chest(context);
                    var held = f.player().getInventory();
                    context.assertTrue(count(inside, Items.SADDLE) == 1 && count(inside, Items.NAME_TAG) == 1 && count(inside, Items.ENCHANTED_BOOK) == 1,
                            "What it keeps for later put away / " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(count(held, Items.BREAD) == 8 && count(held, Items.DIRT) == 16 && count(held, Items.STONE_PICKAXE) == 1,
                            "Its food, blocks and pickaxe kept on it");
                    context.assertTrue(f.runtime.storedCount(f.id, "minecraft:saddle") == 1, "Counted as stored");
                    // While it is away from the chest's contents, an unseen hand takes the saddle; it comes by more to put away.
                    take(inside, Items.SADDLE);
                    held.setStack(6, new ItemStack(Items.RAIL, 7));
                })
                .createAndAdd(() -> {
                    context.assertTrue(count(chest(context), Items.RAIL) == 7, "The rails put away too / " + f.runtime.nativeLastResult(f.id));
                    String expected = "A saddle is gone from my chest at " + where(context) + ".";
                    context.assertTrue(f.runtime.said(f.id).contains(expected), "Said what is gone, blaming nobody: " + f.runtime.said(f.id));
                    context.assertTrue(f.runtime.storedCount(f.id, "minecraft:saddle") == 0 && f.runtime.storedCount(f.id, "minecraft:rail") == 7,
                            "Its counts as it saw them");
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "storeTeam", tickLimit = 3600)
    public void sharingAChestItTellsItsTeamWhatItPutInAndAsksBeforeReckoningAnythingGone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3500);
        UUID[] wren = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) if (!status.id().equals(f.id)) wren[0] = status.id();
                    ground(context);
                    kit(f.player(), context);
                    // Wren nearby with nothing to put away; one chest assigned to both, the team's.
                    var mate = f.source.getServer().getPlayerManager().getPlayer(f.runtime.nativeStatuses(Actor.console()).stream()
                            .filter(s -> s.id().equals(wren[0])).findFirst().orElseThrow().nativeId());
                    BlockPos at = context.getAbsolutePos(new BlockPos(3, 2, 6));
                    mate.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    mate.getInventory().clear();
                    context.assertTrue(f.runtime.nativeStorage(f.source, f.id, context.getAbsolutePos(CHEST)) == 1, "Hal's storage");
                    context.assertTrue(f.runtime.nativeStorage(f.source, wren[0], context.getAbsolutePos(CHEST)) == 1, "Wren's, the same chest");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing; Wren keeps still"))
                .createAndAdd(() -> {
                    var inside = chest(context);
                    context.assertTrue(count(inside, Items.SADDLE) == 1 && count(inside, Items.NAME_TAG) == 1, "Put away / " + f.runtime.nativeLastResult(f.id));
                    String told = "I put an enchanted book, a name tag and a saddle in our chest at " + where(context) + ".";
                    context.assertTrue(f.runtime.said(f.id).contains(told), "Told its team: " + f.runtime.said(f.id));
                    context.assertTrue(f.runtime.storedCount(wren[0], "minecraft:saddle") == 1 && f.runtime.storedCount(wren[0], "minecraft:name_tag") == 1,
                            "Wren's count changed with it");
                    take(inside, Items.NAME_TAG);
                    f.player().getInventory().setStack(6, new ItemStack(Items.RAIL, 7));
                })
                .createAndAdd(() -> {
                    String asked = "Did anyone take a name tag from our chest at " + where(context) + "?";
                    context.assertTrue(f.runtime.said(f.id).contains(asked), "Asked its team first: " + f.runtime.said(f.id));
                    context.assertTrue(f.runtime.said(f.id).contains("I put 7 rails in our chest at " + where(context) + "."), "And told what it put in");
                    context.assertTrue(f.runtime.storedCount(wren[0], "minecraft:rail") == 7, "Wren counts the rails");
                })
                .createAndAdd(() -> {
                    String reckoned = "Nobody took them, so a name tag is gone from our chest at " + where(context) + ".";
                    context.assertTrue(f.runtime.said(f.id).contains(reckoned), "With no word in time, reckoned gone, nobody blamed: " + f.runtime.said(f.id));
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
