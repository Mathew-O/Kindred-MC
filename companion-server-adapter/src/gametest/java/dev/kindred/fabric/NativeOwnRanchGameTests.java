package dev.kindred.fabric;

import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ChickenEntity;
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
                    var where = new StringBuilder();
                    for (var cow : world.getEntitiesByClass(CowEntity.class, new Box(post).expand(48), c -> true)) {
                        where.append(cow.getUuid().equals(cows[0]) || cow.getUuid().equals(cows[1]) ? "pair " : "other ").append(cow.getBlockPos().subtract(post).toShortString())
                                .append(cow.isBaby() ? " young" : "").append(cow.isInLove() ? " in love" : "").append("; ");
                    }
                    context.assertTrue(adults == 2 && calves == 1, "The pair penned and a calf: " + adults + " and " + calves + "; cows from the pen's post: " + where);
                    context.assertTrue(held(f, Items.WHEAT) == 6, "Two wheat fed: " + held(f, Items.WHEAT));
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

    /**
     * Its food earned in play (M-01a): a pair of chickens in sight and no seeds at all, grass about. It breaks the grass
     * for seeds, as players do, then keeps the chickens: the pen built, the pair led in and bred with the seeds it
     * earned. Staged as the cow fixture is, with no seeds given and grass set about the field.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "ownRanchSeeds", tickLimit = 12000)
    public void withNoSeedsItEarnsThemFromTheGrassThenKeepsAPairOfChickens(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 11500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        BlockPos cabinAt = home.add(-3, 0, -3);
        boolean[] seeding = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Henwife"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, trunks);
                    world.setTimeOfDay(1000);
                    var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) {
                        try {
                            world.setBlockState(BuildRunner.blockPos(b.at()),
                                    BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), b.spec().toString(), false).blockState());
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                            throw new RuntimeException(failure);
                        }
                    }
                    // Staged: tufts of grass about the field east and south of the cabin, as grass grows on a plain.
                    for (int x = 6; x <= 14; x++) for (int z = -6; z <= 10; z++) {
                        if ((x + z) % 2 != 0) continue;
                        BlockPos at = home.add(x, 0, z);
                        if (world.getBlockState(at.down()).isOf(Blocks.GRASS_BLOCK) && world.getBlockState(at).isAir()) {
                            world.setBlockState(at, Blocks.SHORT_GRASS.getDefaultState());
                        }
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // Stone tools, fourteen logs and no seeds at all.
                    ItemStack[] held = {new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_LOG, 14),
                            new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = home.add(8, 0, 0);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    BlockPos rel = home.add(12, 0, 4).subtract(context.getAbsolutePos(BlockPos.ORIGIN));
                    context.spawnEntity(EntityType.CHICKEN, rel);
                    context.spawnEntity(EntityType.CHICKEN, rel.add(1, 0, 2));
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.stageBaseForTest(f.id,
                        new PlayerMemoryStore.Base("cabin", "minecraft:overworld", cabinAt.getX(), cabinAt.getY(), cabinAt.getZ(), true)), "Its cabin recorded"))
                .createAndAdd(() -> context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on"))
                .createAndAdd(() -> {
                    if (f.runtime.ranchNote(f.id).contains("seeds first")) seeding[0] = true;
                    var pen = f.runtime.ownRanch(f.id);
                    context.assertTrue(pen.isPresent() && pen.get().kind().equals("CHICKEN"),
                            "A chicken ranch of its own begun: " + pen + " / " + f.runtime.ranchNote(f.id) + " / " + f.runtime.baseNote(f.id)
                                    + " / seeds " + (f.player() == null ? "-" : f.player().getInventory().count(Items.WHEAT_SEEDS)));
                    context.assertTrue(seeding[0], "Seeds earned first, from the grass: " + f.runtime.ranchNote(f.id));
                })
                .createAndAdd(() -> {
                    var pen = f.runtime.ownRanch(f.id).orElseThrow();
                    context.assertTrue(pen.complete() && f.runtime.ranchStatus(f.id).isEmpty(), "Ranch run: " + f.runtime.ranchStatus(f.id).orElse(""));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var pen = f.runtime.ownRanch(f.id).orElseThrow();
                    BlockPos post = new BlockPos(pen.x(), pen.y(), pen.z());
                    var penned = world.getEntitiesByClass(ChickenEntity.class, new Box(post).expand(8),
                            c -> c.getBlockX() > post.getX() && c.getBlockX() < post.getX() + 6 && c.getBlockZ() > post.getZ() && c.getBlockZ() < post.getZ() + 6);
                    long adults = penned.stream().filter(c -> !c.isBaby()).count(), chicks = penned.stream().filter(ChickenEntity::isBaby).count();
                    KindredMod.LOGGER.info("Own ranch with earned seeds: {} chickens and {} chicks penned; {} seeds left", adults, chicks,
                            held(f, Items.WHEAT_SEEDS));
                    // Bred with two seeds, and it was given none: the seeds were earned.
                    context.assertTrue(adults == 2 && chicks == 1, "The pair penned and a chick: " + adults + " and " + chicks);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The cows' wheat grown (M-01a): a pair of cows in sight and no wheat, seeds or hoe; grass about and logs held. It
     * sites a wheat plot by its cabin, makes a wooden hoe, tills, breaks grass for seeds, sows, and leaves the plot to
     * grow; ripe, it harvests it and sows again, then keeps the cows, bred with the wheat it grew. Staged as the cow
     * fixture is, with no wheat or seeds given, grass set about the field, and the wheat ripened by the fixture once
     * sown, as days passing ripen it.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "ownRanchWheat", tickLimit = 16000)
    public void withNoWheatItGrowsItThenKeepsAPairOfCows(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 15500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        BlockPos cabinAt = home.add(-3, 0, -3);
        boolean[] ripened = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Granger"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, trunks);
                    world.setTimeOfDay(1000);
                    var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) {
                        try {
                            world.setBlockState(BuildRunner.blockPos(b.at()),
                                    BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), b.spec().toString(), false).blockState());
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                            throw new RuntimeException(failure);
                        }
                    }
                    // Staged: tufts of grass about the field east and south of the cabin.
                    for (int x = 6; x <= 14; x++) for (int z = -6; z <= 10; z++) {
                        if ((x + z) % 2 != 0) continue;
                        BlockPos at = home.add(x, 0, z);
                        if (world.getBlockState(at.down()).isOf(Blocks.GRASS_BLOCK) && world.getBlockState(at).isAir()) {
                            world.setBlockState(at, Blocks.SHORT_GRASS.getDefaultState());
                        }
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // Stone tools, eighteen logs (a hoe, a table, the pen); no wheat, seeds or hoe.
                    ItemStack[] held = {new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_LOG, 18),
                            new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = home.add(8, 0, 0);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    BlockPos rel = home.add(12, 0, 4).subtract(context.getAbsolutePos(BlockPos.ORIGIN));
                    context.spawnEntity(EntityType.COW, rel);
                    context.spawnEntity(EntityType.COW, rel.add(1, 0, 2));
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.stageBaseForTest(f.id,
                        new PlayerMemoryStore.Base("cabin", "minecraft:overworld", cabinAt.getX(), cabinAt.getY(), cabinAt.getZ(), true)), "Its cabin recorded"))
                .createAndAdd(() -> context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on"))
                // Its plot sited, tilled and sown with seeds it broke from the grass, with a hoe it made.
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var plot = f.runtime.plot(f.id);
                    int sown = 0;
                    if (plot.isPresent()) {
                        BlockPos corner = new BlockPos(plot.get().x(), plot.get().y(), plot.get().z());
                        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                            BlockPos at = corner.add(dx, 0, dz);
                            if (world.getBlockState(at).isOf(Blocks.FARMLAND) && world.getBlockState(at.up()).isOf(Blocks.WHEAT)) sown++;
                        }
                    }
                    context.assertTrue(sown == 4, "Its plot sown: " + plot + ", " + sown + " of 4 / " + f.runtime.ranchNote(f.id) + " / "
                            + (f.player() == null ? "-" : "hoe " + f.player().getInventory().count(Items.WOODEN_HOE) + ", seeds " + f.player().getInventory().count(Items.WHEAT_SEEDS)
                            + ", planks " + f.player().getInventory().count(Items.OAK_PLANKS) + ", sticks " + f.player().getInventory().count(Items.STICK)
                            + ", tables held " + f.player().getInventory().count(Items.CRAFTING_TABLE) + ", tables about " + tablesAbout(world, f.player().getBlockPos())
                            + " at " + f.player().getBlockPos().toShortString()));
                    context.assertTrue(f.runtime.ownRanch(f.id).isEmpty(), "No ranch before the wheat: " + f.runtime.ownRanch(f.id));
                })
                .createAndAdd(() -> {
                    // Staged: days pass, and the wheat ripens.
                    var world = context.getWorld();
                    var plot = f.runtime.plot(f.id).orElseThrow();
                    BlockPos corner = new BlockPos(plot.x(), plot.y(), plot.z());
                    for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                        BlockPos crop = corner.add(dx, 1, dz);
                        if (world.getBlockState(crop).isOf(Blocks.WHEAT)) world.setBlockState(crop, ((net.minecraft.block.CropBlock) Blocks.WHEAT).withAge(7));
                    }
                    ripened[0] = true;
                })
                .createAndAdd(() -> {
                    var pen = f.runtime.ownRanch(f.id);
                    context.assertTrue(pen.isPresent() && pen.get().kind().equals("COW") && pen.get().complete(),
                            "A cow ranch of its own, run, after the harvest: " + pen + " / " + f.runtime.ranchNote(f.id) + " / " + f.runtime.ranchStatus(f.id).orElse(""));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var pen = f.runtime.ownRanch(f.id).orElseThrow();
                    BlockPos post = new BlockPos(pen.x(), pen.y(), pen.z());
                    var penned = world.getEntitiesByClass(CowEntity.class, new Box(post).expand(8),
                            c -> c.getBlockX() > post.getX() && c.getBlockX() < post.getX() + 6 && c.getBlockZ() > post.getZ() && c.getBlockZ() < post.getZ() + 6);
                    long adults = penned.stream().filter(c -> !c.isBaby()).count(), calves = penned.stream().filter(CowEntity::isBaby).count();
                    var plot = f.runtime.plot(f.id).orElseThrow();
                    BlockPos corner = new BlockPos(plot.x(), plot.y(), plot.z());
                    int resown = 0;
                    for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) if (world.getBlockState(corner.add(dx, 1, dz)).isOf(Blocks.WHEAT)) resown++;
                    KindredMod.LOGGER.info("Own ranch with grown wheat: {} cows and {} calves penned; plot sown again {} of 4", adults, calves, resown);
                    context.assertTrue(adults == 2 && calves == 1, "The pair penned and a calf, fed wheat it grew: " + adults + " and " + calves);
                    context.assertTrue(resown == 4, "The plot sown again after the harvest: " + resown);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A pen per kind (M-01a): its cow ranch already run, the cows penned, and a pair of sheep in sight. It does not keep
     * cows twice: it sites a second pen by its cabin, clear of the first, builds it from its own wood and keeps the sheep,
     * bred with its wheat. Staged as the cow fixture is, and its cow pen laid down and recorded as run, its cows in it.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "ownRanchPens", tickLimit = 9000)
    public void withItsCowsPennedItKeepsAPairOfSheepInAPenOfTheirOwn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        BlockPos cabinAt = home.add(-3, 0, -3);
        BlockPos cowPen = o.add(2, 7, 26);
        java.util.UUID[] sheep = new java.util.UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Shepherd"))
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
                    // Staged: its cow pen, seven by seven with the gate shut on its south side, and two cows in it.
                    for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
                        if (x != 0 && x != 6 && z != 0 && z != 6) continue;
                        world.setBlockState(cowPen.add(x, 0, z), x == 3 && z == 6 ? Blocks.OAK_FENCE_GATE.getDefaultState() : Blocks.OAK_FENCE.getDefaultState());
                    }
                    BlockPos inPen = cowPen.add(2, 0, 2).subtract(context.getAbsolutePos(BlockPos.ORIGIN));
                    context.spawnEntity(EntityType.COW, inPen);
                    context.spawnEntity(EntityType.COW, inPen.add(2, 0, 2));
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    ItemStack[] held = {new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_LOG, 14),
                            new ItemStack(Items.WHEAT, 8), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = home.add(8, 0, 0);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    // Staged: a pair of sheep in sight, east of its cabin.
                    BlockPos rel = home.add(12, 0, 4).subtract(context.getAbsolutePos(BlockPos.ORIGIN));
                    sheep[0] = context.spawnEntity(EntityType.SHEEP, rel).getUuid();
                    sheep[1] = context.spawnEntity(EntityType.SHEEP, rel.add(1, 0, 2)).getUuid();
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.stageBaseForTest(f.id,
                        new PlayerMemoryStore.Base("cabin", "minecraft:overworld", cabinAt.getX(), cabinAt.getY(), cabinAt.getZ(), true)), "Its cabin recorded"))
                .createAndAdd(() -> context.assertTrue(f.runtime.stagePenForTest(f.id,
                        new PlayerMemoryStore.Pen("minecraft:overworld", cowPen.getX(), cowPen.getY(), cowPen.getZ(), "COW", true)), "Its cow pen recorded as run"))
                .createAndAdd(() -> context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on"))
                .createAndAdd(() -> {
                    var pens = f.runtime.ownPens(f.id);
                    context.assertTrue(pens.size() == 2 && f.runtime.ranchStatus(f.id).isPresent(),
                            "A second pen begun: " + pens + " / " + f.runtime.ranchNote(f.id) + " / " + f.runtime.baseNote(f.id));
                    var second = pens.get(1);
                    context.assertTrue(second.kind().equals("SHEEP"), "For the sheep, not cows twice: " + second);
                    // Clear of the cow pen, and by its cabin.
                    context.assertTrue(second.x() + 6 < cowPen.getX() || second.x() > cowPen.getX() + 6 || second.z() + 6 < cowPen.getZ() || second.z() > cowPen.getZ() + 6,
                            "Clear of the cow pen: " + second);
                    context.assertTrue(Math.abs(second.x() + 3 - (cabinAt.getX() + 3)) <= 24 && Math.abs(second.z() + 3 - (cabinAt.getZ() + 3)) <= 24, "By its cabin: " + second);
                })
                .createAndAdd(() -> {
                    var pens = f.runtime.ownPens(f.id);
                    context.assertTrue(pens.size() == 2 && pens.get(1).complete() && f.runtime.ranchStatus(f.id).isEmpty(),
                            "Sheep ranch run: " + f.runtime.ranchStatus(f.id).orElse("") + " / " + pens);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var pens = f.runtime.ownPens(f.id);
                    var pen = pens.get(1);
                    BlockPos post = new BlockPos(pen.x(), pen.y(), pen.z());
                    int fences = 0;
                    for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
                        if (x != 0 && x != 6 && z != 0 && z != 6) continue;
                        if (world.getBlockState(post.add(x, 0, z)).isOf(Blocks.OAK_FENCE)) fences++;
                    }
                    context.assertTrue(fences == 23 && world.getBlockState(post.add(3, 0, 6)).isOf(Blocks.OAK_FENCE_GATE), "Its sheep pen built: " + fences + " fences");
                    var penned = world.getEntitiesByClass(net.minecraft.entity.passive.SheepEntity.class, new Box(post).expand(8),
                            c -> c.getBlockX() > post.getX() && c.getBlockX() < post.getX() + 6 && c.getBlockZ() > post.getZ() && c.getBlockZ() < post.getZ() + 6);
                    long adults = penned.stream().filter(c -> !c.isBaby()).count(), lambs = penned.stream().filter(c -> c.isBaby()).count();
                    KindredMod.LOGGER.info("Own pens: {}; {} sheep and {} lambs penned", pens, adults, lambs);
                    context.assertTrue(adults == 2 && lambs == 1, "The sheep penned and a lamb: " + adults + " and " + lambs);
                    context.assertTrue(pens.get(0).kind().equals("COW") && pens.get(0).complete(), "Its cow pen as it was: " + pens.get(0));
                    long cows = world.getEntitiesByClass(CowEntity.class, new Box(cowPen).expand(8), c -> c.getBlockX() > cowPen.getX()
                            && c.getBlockX() < cowPen.getX() + 6 && c.getBlockZ() > cowPen.getZ() && c.getBlockZ() < cowPen.getZ() + 6).size();
                    context.assertTrue(cows == 2, "Its cows still penned: " + cows);
                    context.assertTrue(held(f, Items.WHEAT) == 6, "Two wheat fed: " + held(f, Items.WHEAT));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    private static String tablesAbout(net.minecraft.server.world.ServerWorld world, BlockPos feet) {
        var found = new StringBuilder();
        for (BlockPos pos : BlockPos.iterate(feet.add(-8, -3, -8), feet.add(8, 3, 8))) {
            if (world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) found.append(pos.subtract(feet).toShortString()).append(' ');
        }
        return found.length() == 0 ? "none" : found.toString().trim();
    }

    /** How many of {@code item} the fixture's player holds; -1 once it is gone (its runtime stopped). */
    private static int held(NativePaidSkillsGameTests.Fixture f, net.minecraft.item.Item item) {
        return f.player() == null ? -1 : f.player().getInventory().count(item);
    }
}
