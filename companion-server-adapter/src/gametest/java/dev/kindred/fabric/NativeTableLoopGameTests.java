package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a player on its way to iron with the wool for a bed and logs but no planks cuts planks and makes its
 * bed at its table, or sets no table down at all; it does not put its table down and pick it straight back up.
 *
 * Technical: F-25c7a, the take-and-place loop of seed 303 Sol (p157 before, 6a6d882, the stuck spell at 76840): on the
 * portal goal's iron step, a travel step, its third white wool brought the End fight's bed up as a follow-up;
 * {@code craftAtTable("white_bed")} set its carried table down, the bed could not be made there ("not the makings": no
 * planks), and "take workstations along" picked the table up, 35 times in that spell. STAGED: Sol's moment as near as
 * its log tells, on the field: the portal goal, a stone pickaxe and sword, bread, cobblestone, three white wool, one oak
 * log and no planks (one log, four planks, is under a shield's six, so the route wants no spare sticks from it), the crafting table it carries, an empty bucket and eight sticks (so the route's step is the water's,
 * a travel step); nothing else given, and the bed, if made, made by it. Passes if
 * within 600 ticks it holds a white bed, or 600 ticks pass with its table set down at most once; fails at three
 * placements, the loop.
 */
public final class NativeTableLoopGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "tableLoop", tickLimit = 1600)
    public void stagedOnTheWayWithWoolAndLogsButNoPlanksItMakesItsBedOrSetsNoTableDownNotATableLoop(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        int[] placed = {0}, pickedUp = {0}, tables = {-1}, pickedBeforeBed = {-1};
        long[] since = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Sol"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // Put back at the end: it may dig for its iron.
                    FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(-16, -24, -16)), context.getAbsolutePos(new BlockPos(32, 12, 32)));
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(8, 2, 8));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(4, new ItemStack(Items.WHITE_WOOL, 3));
                    inventory.setStack(5, new ItemStack(Items.OAK_LOG, 1));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE, 1));
                    // Its route on a travel step, as Sol's was ("Fill a bucket with water"): an empty bucket to fill, and sticks
                    // enough, and one log only, so that no "Craft planks for spare sticks" comes first. Without them the first version's route
                    // crafted planks for sticks, a craft step on which the pickup never runs, and the bed was made: it
                    // passed 3 of 3 on main and showed nothing.
                    inventory.setStack(7, new ItemStack(Items.BUCKET, 1));
                    inventory.setStack(8, new ItemStack(Items.STICK, 8));
                    var held = new StringBuilder();
                    for (int s = 0; s < 36; s++) {
                        var stack = inventory.getStack(s);
                        if (!stack.isEmpty()) held.append(stack.getCount()).append(' ').append(net.minecraft.registry.Registries.ITEM.getId(stack.getItem())).append("; ");
                    }
                    KindredMod.LOGGER.info("Table loop: Sol holds {}", held);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    since[0] = world.getTime();
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    int now = player.getInventory().count(Items.CRAFTING_TABLE);
                    if (tables[0] >= 0 && now < tables[0]) placed[0]++;
                    if (tables[0] >= 0 && now > tables[0]) pickedUp[0]++;
                    tables[0] = now;
                    String progress = f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                    // The loop, failed at once: the table set down and taken up again, three times.
                    if (placed[0] >= 3) {
                        throw new net.minecraft.test.GameTestException("The table set down " + placed[0] + " times and picked up " + pickedUp[0]
                                + ", planks " + player.getInventory().count(Items.OAK_PLANKS) + ", white beds " + player.getInventory().count(Items.WHITE_BED)
                                + " - " + progress);
                    }
                    boolean bed = player.getInventory().count(Items.WHITE_BED) > 0;
                    if (bed && pickedBeforeBed[0] < 0) pickedBeforeBed[0] = pickedUp[0];
                    context.assertTrue(bed || world.getTime() - since[0] >= 600, "Its bed made, or 600 ticks with no loop: " + (world.getTime() - since[0])
                            + " ticks, the table set down " + placed[0] + " times - " + progress);
                })
                // F-25c7b: the craft before the pickup, and the table taken along after it, none left standing.
                .createAndAdd(() -> {
                    var player = f.player();
                    if (player.getInventory().count(Items.WHITE_BED) == 0) return;
                    context.assertTrue(pickedBeforeBed[0] == 0, "The table picked up " + pickedBeforeBed[0] + " times before its bed was made");
                    int carried = player.getInventory().count(Items.CRAFTING_TABLE);
                    int standing = tablesStanding(context);
                    context.assertTrue(carried == 1 && standing == 0, "The table taken along after the bed: " + carried + " carried, " + standing + " standing");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    KindredMod.LOGGER.info("Table loop: set down {} times, picked up {}; planks {}, white beds {}, wool {}, logs {}",
                            placed[0], pickedUp[0], player.getInventory().count(Items.OAK_PLANKS), player.getInventory().count(Items.WHITE_BED),
                            player.getInventory().count(Items.WHITE_WOOL), player.getInventory().count(Items.OAK_LOG));
                    context.assertTrue(placed[0] <= 1 || player.getInventory().count(Items.WHITE_BED) > 0,
                            "No bed and the table set down " + placed[0] + " times");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** Crafting tables standing within the fixture's ground. */
    static int tablesStanding(TestContext context) {
        var world = context.getWorld();
        int n = 0;
        for (BlockPos b : BlockPos.iterate(context.getAbsolutePos(new BlockPos(-16, -24, -16)), context.getAbsolutePos(new BlockPos(32, 12, 32)))) {
            if (world.getBlockState(b).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) n++;
        }
        return n;
    }

    /**
     * In plain terms: a table kept down for a bed is taken along again once the bed can no longer be made there.
     *
     * Technical: F-25c7b's expiry. STAGED: as above, but with the bed's makings held outright (three white wool and
     * three oak planks, no logs), and once its table is down the fixture takes the wool from its pack, as though spent
     * elsewhere. The hold ({@code tableHeldFor}) ends with the makings, and "take workstations along" picks the table up
     * as before. Passes if the table is carried again, none standing, within 200 ticks of the wool going, and no bed made.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "tableHoldExpiry", tickLimit = 1600)
    public void stagedItsTableKeptDownForABedIsTakenAlongOnceTheBedCanNoLongerBeMade(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        long[] woolGone = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Sol"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(-16, -24, -16)), context.getAbsolutePos(new BlockPos(32, 12, 32)));
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(8, 2, 8));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(4, new ItemStack(Items.WHITE_WOOL, 3));
                    inventory.setStack(5, new ItemStack(Items.OAK_PLANKS, 3));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE, 1));
                    inventory.setStack(7, new ItemStack(Items.BUCKET, 1));
                    inventory.setStack(8, new ItemStack(Items.STICK, 8));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var inventory = f.player().getInventory();
                    // The table down: the wool taken, as though spent elsewhere.
                    context.assertTrue(inventory.count(Items.CRAFTING_TABLE) == 0 || inventory.count(Items.WHITE_BED) > 0, "Its table not down yet");
                    context.assertTrue(inventory.count(Items.WHITE_BED) == 0, "The bed made before the wool could be taken: the staging missed");
                    for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(Items.WHITE_WOOL)) inventory.setStack(s, ItemStack.EMPTY);
                    woolGone[0] = context.getWorld().getTime();
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    long waited = context.getWorld().getTime() - woolGone[0];
                    int carried = player.getInventory().count(Items.CRAFTING_TABLE), standing = tablesStanding(context);
                    if (waited > 200) {
                        throw new net.minecraft.test.GameTestException("Its table not taken along " + waited + " ticks after the wool went: "
                                + carried + " carried, " + standing + " standing");
                    }
                    context.assertTrue(carried == 1 && standing == 0, "Taken along: " + carried + " carried, " + standing + " standing, " + waited + " ticks");
                    KindredMod.LOGGER.info("Table hold expiry: taken along {} ticks after the wool went; white beds {}", waited, player.getInventory().count(Items.WHITE_BED));
                    context.assertTrue(player.getInventory().count(Items.WHITE_BED) == 0, "A bed made without the wool");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
