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
 * In plain terms: a GameTest: in a real test world, a player is checked to keep spare things in its own chest
 * and count them as its own. Alone, when something it stored goes missing it says so without blaming anyone;
 * sharing a chest with a teammate, it tells the team what it put in, asks the team before deciding anything
 * is gone, and a teammate's take is told and counted by both; and in a match, when it sees an opponent at its
 * chest it goes to look, tells its own side who it was and moves what is left.
 *
 * Technical: Extended inventory (E-43): a player puts what it keeps for later in a store of its own and
 * counts it with what it holds. Alone, when it opens its chest again and something it put there is gone, with
 * nobody seen at it, it reckons the thing gone and says so to those it plays for, blaming nobody. Sharing a
 * chest with a teammate, it tells the team what it put in, and the teammate's count changes with it; when
 * something is gone, it asks its team, and with no word in time reckons it gone, blaming nobody. A teammate's
 * take is told and counted by both, and kept, not put straight back. In a match, a player that sees an
 * opponent open its chest goes to look, names them to its own side, and with enough left moves the rest.
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

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        var status = f.runtime.nativeStatuses(Actor.console()).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        return f.source.getServer().getPlayerManager().getPlayer(status.nativeId());
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "storeMateTake", tickLimit = 3000)
    public void aTeammatesTakeFromTheSharedChestIsToldAndCountedByBoth(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
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
                    f.player().getInventory().setStack(6, new ItemStack(Items.IRON_INGOT, 6));
                    var mate = player(f, wren[0]);
                    BlockPos at = context.getAbsolutePos(new BlockPos(5, 2, 5));
                    mate.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    mate.getInventory().clear();
                    context.assertTrue(f.runtime.nativeStorage(f.source, f.id, context.getAbsolutePos(CHEST)) == 1, "Hal's storage");
                    context.assertTrue(f.runtime.nativeStorage(f.source, wren[0], context.getAbsolutePos(CHEST)) == 1, "Wren's, the same chest");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"))
                .createAndAdd(() -> {
                    context.assertTrue(count(chest(context), Items.IRON_INGOT) == 6, "Hal's iron put away / " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(f.runtime.storedCount(wren[0], "minecraft:iron_ingot") == 6, "Wren counts it");
                })
                // Hal keeps still now, not at the chest while Wren uses it; Wren plays.
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Hal held"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", wren[0]) == 1, "Wren playing"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, wren[0], "native_take",
                        java.util.Map.of("item", "minecraft:iron_ingot", "count", "3")) == 1, "Wren takes three, as a build would"))
                .createAndAdd(() -> {
                    String told = "I took 3 iron ingots from our chest at " + where(context) + ".";
                    context.assertTrue(f.runtime.said(wren[0]).contains(told), "Wren told the team: " + f.runtime.said(wren[0]));
                    context.assertTrue(f.runtime.storedCount(f.id, "minecraft:iron_ingot") == 3, "Hal's count changed with it: "
                            + f.runtime.storedCount(f.id, "minecraft:iron_ingot"));
                })
                .createAndAdd(() -> {
                    context.assertTrue(count(player(f, wren[0]).getInventory(), Items.IRON_INGOT) == 3 && count(chest(context), Items.IRON_INGOT) == 3,
                            "What Wren took it keeps, not straight back");
                    context.assertTrue(f.runtime.said(f.id).stream().noneMatch(line -> line.startsWith("Did anyone take")), "Nothing asked: it was told");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "storeMatch", tickLimit = 4000)
    public void inAMatchItGoesToLookWhenItSeesAnOpponentAtItsChestNamesThemToItsSideAndMovesTheRest(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3900);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] hal = new UUID[1];
        BlockPos far = new BlockPos(5, 2, 30);
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) if (!status.id().equals(f.id)) hal[0] = status.id();
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -2; x <= 12; x++) for (int z = -2; z <= 36; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(context.getAbsolutePos(far), Blocks.CHEST.getDefaultState());
                    world.setTimeOfDay(1000);
                    // Rue by its chest with what it keeps for later, nothing else; Hal kept out of the way.
                    var rue = f.player();
                    BlockPos at = context.getAbsolutePos(new BlockPos(5, 2, 28));
                    rue.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    var inventory = rue.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.SADDLE));
                    inventory.setStack(1, new ItemStack(Items.ENCHANTED_BOOK));
                    inventory.setStack(2, new ItemStack(Items.ENCHANTED_BOOK));
                    inventory.setStack(3, new ItemStack(Items.NAME_TAG));
                    inventory.setStack(4, new ItemStack(Items.LEAD));
                    rue.setHealth(20);
                    rue.getHungerManager().setFoodLevel(20);
                    var other = player(f, hal[0]);
                    BlockPos away = context.getAbsolutePos(new BlockPos(5, 2, 2)).add(0, 0, -200);
                    other.refreshPositionAndAngles(away.getX() + 0.5, away.getY() + 1, away.getZ() + 0.5, 0, 0);
                    other.getInventory().clear();
                    context.assertTrue(f.runtime.nativeStorage(f.source, f.id, context.getAbsolutePos(far)) == 1, "Rue's storage");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Rue playing"))
                .createAndAdd(() -> context.assertTrue(f.player().getInventory().isEmpty() && count(chest(context, far), Items.ENCHANTED_BOOK) == 2,
                        "Put away, all of it / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                    // A manhunt from a fresh start, Rue running, Hal hunting.
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(f.nativeId), java.util.List.of(player(f, hal[0]).getUuid())) == 1, "Proposed");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startMatch(f.source, f.runtime.matches().getFirst().id()) == 1, "Started"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == dev.kindred.core.match.MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", hal[0]) == 1, "Hal held");
                    // Rue walked off from its chest; Hal comes to it, opens it in Rue's sight, out of its fleeing distance.
                    BlockPos off = context.getAbsolutePos(new BlockPos(5, 2, 4));
                    f.player().refreshPositionAndAngles(off.getX() + 0.5, off.getY(), off.getZ() + 0.5, 0, 0);
                    BlockPos by = context.getAbsolutePos(far.add(0, 0, 1));
                    var other = player(f, hal[0]);
                    other.refreshPositionAndAngles(by.getX() + 0.5, by.getY(), by.getZ() + 0.5, 180, 0);
                })
                .createAndAdd(() -> {
                    var other = player(f, hal[0]);
                    context.assertTrue(f.player().canSee(other) && f.player().squaredDistanceTo(other) > 20 * 20, "Rue sees Hal at its chest, out of fleeing reach");
                    other.openHandledScreen(chest(context, far));
                    take(chest(context, far), Items.SADDLE);
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 40, "Hal at the chest for two seconds"))
                .createAndAdd(() -> {
                    var other = player(f, hal[0]);
                    other.closeHandledScreen();
                    BlockPos away = context.getAbsolutePos(new BlockPos(5, 2, 2)).add(0, 0, -200);
                    other.refreshPositionAndAngles(away.getX() + 0.5, away.getY() + 1, away.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    String saw = "I saw Hal take a saddle from my chest at " + where(context, far) + ". I'm moving the rest.";
                    context.assertTrue(f.runtime.said(f.id).contains(saw), "Named who it saw, to its own side: " + f.runtime.said(f.id));
                })
                .createAndAdd(() -> {
                    var held = f.player().getInventory();
                    context.assertTrue(count(chest(context, far), Items.ENCHANTED_BOOK) == 0 && count(held, Items.ENCHANTED_BOOK) == 2
                            && count(held, Items.NAME_TAG) == 1 && count(held, Items.LEAD) == 1, "The rest moved: taken with it / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static ChestBlockEntity chest(TestContext context, BlockPos pos) {
        return (ChestBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(pos));
    }

    private static String where(TestContext context, BlockPos pos) {
        BlockPos at = context.getAbsolutePos(pos);
        return at.getX() + ", " + at.getY() + ", " + at.getZ();
    }
}
