package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The follow-up queue in play (F-25c7): with the End goal under way, what the End fight will need is taken as the
 * chance comes, before the route's own next step. Here the player stands by its lit portal, stocked for the Nether,
 * in a yard with cobwebs and chickens: it breaks the cobwebs with its sword for string, hunts the chickens for
 * feathers, sets down the crafting table it carries, and makes a bow and arrows, all from what it gathered and the
 * sticks and flint it had. Nothing is handed over that it makes itself.
 */
public final class NativeFollowUpGameTests implements FabricGameTest {
    /** The yard south of the portal: x -4..11, z 6..14 inside walls, fenced off from the portal along z = 5. */
    private static void yard(TestContext context) {
        var world = context.getWorld();
        for (int x = -4; x <= 11; x++) for (int z = 5; z <= 15; z++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
            for (int y = 2; y <= 6; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
        }
        for (int x = -4; x <= 11; x++) for (int z = 5; z <= 15; z++) {
            boolean wall = x == -4 || x == 11 || z == 15;
            if (wall) for (int y = 2; y <= 4; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.STONE.getDefaultState());
            else if (z == 5) world.setBlockState(context.getAbsolutePos(new BlockPos(x, 2, z)), Blocks.OAK_FENCE.getDefaultState());
        }
    }

    /** The test server ticks no mob outside the test's own chunks (it sets no simulation distance); a live one does. */
    private static void forceChunks(ServerWorld world, BlockPos o, boolean forced) {
        for (int x = o.getX() - 16; x <= o.getX() + 32; x += 16) for (int z = o.getZ() - 16; z <= o.getZ() + 32; z += 16) {
            world.setChunkForced(x >> 4, z >> 4, forced);
        }
    }

    private static int count(net.minecraft.server.network.ServerPlayerEntity player, net.minecraft.item.Item item) {
        return player.getInventory().count(item);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "followUps", tickLimit = 2400)
    public void theEndFightsBowAndArrowsAreMadeOnTheWayFromWhatItGathers(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        List<ChickenEntity> chickens = new ArrayList<>();
        BlockPos[] webs = {new BlockPos(-3, 2, 14), new BlockPos(-2, 2, 14), new BlockPos(-3, 2, 13)};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Fletcher"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    forceChunks(world, context.getAbsolutePos(BlockPos.ORIGIN), true);
                    NativeStrongholdGameTests.litPortal(context);
                    yard(context);
                    for (BlockPos web : webs) world.setBlockState(context.getAbsolutePos(web), Blocks.COBWEB.getDefaultState());
                    for (int i = 0; i < 5; i++) {
                        var chicken = EntityType.CHICKEN.create(world);
                        BlockPos at = context.getAbsolutePos(new BlockPos(6 + i % 3, 2, 9 + i / 3 * 2));
                        chicken.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                        chicken.setPersistent();
                        world.spawnEntity(chicken);
                        chickens.add(chicken);
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(3, 2, 8));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180, 0);
                    // Stocked for the Nether as the plan asks, with sticks, one flint and a crafting table of its own;
                    // no string, no feathers, no bow and no arrows.
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.STICK, 4));
                    inventory.setStack(6, new ItemStack(Items.FLINT, 1));
                    inventory.setStack(7, new ItemStack(Items.CRAFTING_TABLE, 1));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(count(player, Items.BOW) == 1 && count(player, Items.ARROW) >= 4,
                            "A bow and arrows made: bow " + count(player, Items.BOW) + ", arrows " + count(player, Items.ARROW)
                                    + ", string " + count(player, Items.STRING) + ", feathers " + count(player, Items.FEATHER)
                                    + ", sticks " + count(player, Items.STICK) + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    int websLeft = 0;
                    for (BlockPos web : webs) if (world.getBlockState(context.getAbsolutePos(web)).isOf(Blocks.COBWEB)) websLeft++;
                    long killed = chickens.stream().filter(chicken -> !chicken.isAlive()).count();
                    int feathers = count(player, Items.FEATHER);
                    KindredMod.LOGGER.info("Fletcher: cobwebs broken {}, chickens hunted {}, feathers kept {}, bow {}, arrows {}, string {}, sticks {}, flint {}",
                            webs.length - websLeft, killed, feathers, count(player, Items.BOW), count(player, Items.ARROW),
                            count(player, Items.STRING), count(player, Items.STICK), count(player, Items.FLINT));
                    // The bow's three string came from the three cobwebs, broken with the sword.
                    context.assertTrue(websLeft == 0 && count(player, Items.STRING) == 0, "Three cobwebs broken for the bow's string: " + websLeft + " left");
                    // One flint made one craft of four arrows; the sticks went into the bow (three) and the arrows (one).
                    context.assertTrue(count(player, Items.ARROW) == 4 && count(player, Items.FLINT) == 0 && count(player, Items.STICK) == 0,
                            "Exactly one craft of arrows from the one flint and the four sticks");
                    // A feather for the arrows came from the chickens hunted; none appeared from nowhere.
                    context.assertTrue(killed >= 1 && feathers + 1 <= 2 * killed, "Feathers from the chickens: " + killed + " hunted, " + feathers + " kept");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    forceChunks(world, context.getAbsolutePos(BlockPos.ORIGIN), false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * At the stronghold's spot (remembered from earlier throws), with the End fight's gear in hand but four blocks
     * short, and a shield the strategist queued: before digging down it crafts the shield from its iron and planks at
     * the table it carries and digs stone for the blocks, then goes down. Nothing is handed over that it makes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "followUps", tickLimit = 2400)
    public void whatTheEndFightStillLacksIsGatheredBeforeGoingDownToTheStronghold(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] provisioned = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Provisioner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeStrongholdGameTests.litPortal(context);
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(3, 2, 6));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180, 0);
                    // Where the eyes' flights crossed, pinned, as a restart would recall it.
                    var memory = new PlayerMemoryStore.Memory();
                    memory.stronghold = new double[] {stand.getX() + 0.5, stand.getZ() + 0.5};
                    memory.pinned = true;
                    try {
                        var folder = f.directory.resolve("memory");
                        java.nio.file.Files.createDirectories(folder);
                        java.nio.file.Files.writeString(folder.resolve(f.id + ".json"), new com.google.gson.Gson().toJson(memory));
                    } catch (java.io.IOException failed) {
                        throw new AssertionError("Memory fixture could not be written", failed);
                    }
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 60));
                    inventory.setStack(5, new ItemStack(Items.BOW));
                    inventory.setStack(6, new ItemStack(Items.ARROW, 32));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.ENDER_EYE, 10));
                    inventory.setStack(9, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(10, new ItemStack(Items.IRON_INGOT));
                    inventory.setStack(11, new ItemStack(Items.OAK_PLANKS, 6));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                    // A follow-up from the strategist, as its reply would carry it.
                    var advice = dev.kindred.core.survival.StrategyAdvice.validated("PRESS_ON", List.of(), "", "Skeletons in strongholds", List.of(),
                            "NONE", "NONE", "NONE", dev.kindred.core.guide.Want.parse("SHIELD", 1, "END_FIGHT", "skeletons").stream().toList()).orElseThrow();
                    context.assertTrue(f.runtime.adviseGoal(f.id, advice), "Advice taken");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (progress.startsWith("End: Provision")) provisioned[0] = true;
                    context.assertTrue(progress.startsWith("End: Descend"), "Going down at last: " + progress);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    KindredMod.LOGGER.info("Provisioner: provisioned first {}, blocks {}, shield {}, iron {}, planks {}, then {}", provisioned[0],
                            count(player, Items.COBBLESTONE), count(player, Items.SHIELD), count(player, Items.IRON_INGOT), count(player, Items.OAK_PLANKS), progress);
                    context.assertTrue(provisioned[0], "It gathered before going down");
                    context.assertTrue(count(player, Items.COBBLESTONE) >= 64, "Blocks for the caged crystals: " + count(player, Items.COBBLESTONE));
                    context.assertTrue(count(player, Items.SHIELD) == 1 && count(player, Items.IRON_INGOT) == 0 && count(player, Items.OAK_PLANKS) == 0,
                            "The queued shield made from its own iron and planks");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
