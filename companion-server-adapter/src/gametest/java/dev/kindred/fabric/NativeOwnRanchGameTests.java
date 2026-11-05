package dev.kindred.fabric;

import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * In plain terms: a player with its cabin standing and nothing more to build yet sees a pair of cows nearby, holds wheat
 * and wood, and starts a ranch of its own: it picks flat ground by its cabin, builds the pen from its own wood, leads the
 * pair in, breeds them, and remembers the ranch as done.
 *
 * Technical: M-01a, the ranch chosen by the player itself. Staged, as the fixture says: the ground laid; the cabin's
 * blocks set and recorded as the player's own complete cabin; its stone tools, logs and wheat given, as though earned;
 * two cows put in sight. Nothing tells it to ranch: the bases' leave is on, as an operator gives it.
 */
public final class NativeOwnRanchGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "ownRanch", tickLimit = 9000)
    public void withItsCabinStandingItKeepsAPairOfCowsItHasSeen(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        BlockPos cabinAt = home.add(-3, 0, -3);
        java.util.UUID[] cows = new java.util.UUID[2];
        long[] doneAt = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Drover"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, trunks);
                    world.setTimeOfDay(1000);
                    // Staged: its cabin standing, block for block on the first plan in oak.
                    var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) {
                        try {
                            world.setBlockState(BuildRunner.blockPos(b.at()),
                                    BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), b.spec().toString(), false).blockState());
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                            throw new RuntimeException(failure);
                        }
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // Stone tools (so a house waits for iron), fourteen logs and eight wheat, as though cut and grown.
                    ItemStack[] held = {new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_LOG, 14),
                            new ItemStack(Items.WHEAT, 8), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = home.add(8, 0, 0);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    // Staged: a pair of cows in sight, east of its cabin.
                    BlockPos rel = home.add(12, 0, 4).subtract(context.getAbsolutePos(BlockPos.ORIGIN));
                    cows[0] = context.spawnEntity(EntityType.COW, rel).getUuid();
                    cows[1] = context.spawnEntity(EntityType.COW, rel.add(1, 0, 2)).getUuid();
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.stageBaseForTest(f.id,
                        new PlayerMemoryStore.Base("cabin", "minecraft:overworld", cabinAt.getX(), cabinAt.getY(), cabinAt.getZ(), true)), "Its cabin recorded"))
                .createAndAdd(() -> context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on"))
                .createAndAdd(() -> {
                    var pen = f.runtime.ownRanch(f.id);
                    context.assertTrue(pen.isPresent() && pen.get().kind().equals("COW") && f.runtime.ranchStatus(f.id).isPresent(),
                            "A cow ranch of its own begun: " + pen + " / " + f.runtime.ranchNote(f.id) + " / " + f.runtime.baseNote(f.id));
                    // By its cabin: the pen's middle within a site's reach of the cabin's middle.
                    var p = pen.get();
                    context.assertTrue(Math.abs(p.x() + 3 - (cabinAt.getX() + 3)) <= 24 && Math.abs(p.z() + 3 - (cabinAt.getZ() + 3)) <= 24,
                            "By its cabin: " + p);
                })
                .createAndAdd(() -> {
                    var pen = f.runtime.ownRanch(f.id).orElseThrow();
                    context.assertTrue(pen.complete() && f.runtime.ranchStatus(f.id).isEmpty(), "Ranch run: " + f.runtime.ranchStatus(f.id).orElse("")
                            + " / " + f.runtime.buildStatus(f.id).orElse(""));
                    doneAt[0] = context.getWorld().getTime();
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var pen = f.runtime.ownRanch(f.id).orElseThrow();
                    BlockPos post = new BlockPos(pen.x(), pen.y(), pen.z());
                    int fences = 0;
                    for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
                        if (x != 0 && x != 6 && z != 0 && z != 6) continue;
                        if (world.getBlockState(post.add(x, 0, z)).isOf(Blocks.OAK_FENCE)) fences++;
                    }
                    context.assertTrue(fences == 23 && world.getBlockState(post.add(3, 0, 6)).isOf(Blocks.OAK_FENCE_GATE), "Its pen built: " + fences + " fences");
                    var penned = world.getEntitiesByClass(CowEntity.class, new Box(post).expand(8),
                            c -> c.getBlockX() > post.getX() && c.getBlockX() < post.getX() + 6 && c.getBlockZ() > post.getZ() && c.getBlockZ() < post.getZ() + 6);
                    long adults = penned.stream().filter(c -> !c.isBaby()).count(), calves = penned.stream().filter(CowEntity::isBaby).count();
                    KindredMod.LOGGER.info("Own ranch: {} at {}, {}, {}; {} fences; {} cows and {} calves penned", pen.kind(), pen.x(), pen.y(), pen.z(),
                            fences, adults, calves);
                    context.assertTrue(adults == 2 && calves == 1, "The pair penned and a calf: " + adults + " and " + calves);
                    context.assertTrue(f.player().getInventory().count(Items.WHEAT) == 6, "Two wheat fed: " + f.player().getInventory().count(Items.WHEAT));
                })
                // Ten seconds on, no second ranch begun.
                .createAndAdd(() -> {
                    context.assertTrue(context.getWorld().getTime() - doneAt[0] >= 200, "Ten seconds on");
                    context.assertTrue(f.runtime.ranchStatus(f.id).isEmpty(), "Not begun twice: " + f.runtime.ranchStatus(f.id).orElse(""));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
