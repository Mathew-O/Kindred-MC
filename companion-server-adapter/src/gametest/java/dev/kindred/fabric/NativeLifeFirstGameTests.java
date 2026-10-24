package dev.kindred.fabric;

import dev.kindred.core.construction.Generators;
import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * One weighted list, life first (F-25c9). A player with its own base:
 * <ul>
 *   <li>at night leaves its build for its own bed and sleeps there, which sets its spawn. Alone in the world, its
 *   sleep passes the night, as any player's does. It places nothing while it sleeps, and builds on in the morning;</li>
 *   <li>hungry with too little to last, hunts before it builds, until it has a full bar and a reserve in its body and
 *   its pack, then builds its base.</li>
 * </ul>
 */
public final class NativeLifeFirstGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "life-night", tickLimit = 30000)
    public void atNightItLeavesItsBuildForItsOwnBedThenBuildsOnInTheMorning(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 29500);
        var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
        var hut = Generators.hut(5, 5, 3);
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos[] trunks = {o.add(40, 7, 4), o.add(40, 7, 40)};
        BlockPos home = o.add(16, 7, 16), hutAt = o.add(14, 7, 30);
        BlockPos[] bed = {null};
        int[] placedAtSleep = {-1};
        boolean[] noted = {false}, placedAsleep = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, trunks);
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The cabin's bill and a hut's, as though gathered.
                    ItemStack[] held = {
                            new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_PLANKS, 64),
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 40), new ItemStack(Items.OAK_LOG, 24),
                            new ItemStack(Items.OAK_STAIRS, 64), new ItemStack(Items.OAK_SLAB, 32), new ItemStack(Items.OAK_DOOR, 2),
                            new ItemStack(Items.RED_BED, 1), new ItemStack(Items.CRAFTING_TABLE, 2), new ItemStack(Items.CHEST, 2),
                            new ItemStack(Items.TORCH, 4), new ItemStack(Items.FURNACE, 1), new ItemStack(Items.COBBLESTONE, 64),
                            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                })
                .createAndAdd(() -> {
                    var base = f.runtime.currentBase(f.id);
                    context.assertTrue(base.isPresent() && base.get().complete() && f.runtime.baseNote(f.id).startsWith("Iron tools first"),
                            "Its cabin built: " + base + " / " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.baseNote(f.id));
                    var origin = new BlockPos(base.get().x(), base.get().y(), base.get().z());
                    for (var b : cabin.placed(BuildRunner.pos(origin), 0)) {
                        if (b.spec().block().endsWith("_bed") && "head".equals(b.spec().property("part"))) bed[0] = BuildRunner.blockPos(b.at());
                    }
                    context.assertTrue(bed[0] != null && context.getWorld().getBlockState(bed[0]).isOf(net.minecraft.block.Blocks.RED_BED), "Its bed");
                    // A build of its own besides, begun by day.
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hut, hutAt, 0) == 1, "Hut begun");
                })
                .createAndAdd(() -> {
                    int placed = placed(context, hut, hutAt);
                    context.assertTrue(placed >= 12, "The hut under way: " + placed + " / " + f.runtime.buildStatus(f.id).orElse(""));
                    // Night falls with the hut half built.
                    context.getWorld().setTimeOfDay(14000);
                })
                .createAndAdd(() -> {
                    var player = body(context, f);
                    if (f.runtime.lifeNote(f.id).startsWith("Night")) noted[0] = true;
                    context.assertTrue(player.isSleeping() && player.getSleepingPosition().filter(bed[0]::equals).isPresent(),
                            "Asleep in its own bed: " + player.getSleepingPosition() + " for " + bed[0] + " / " + f.runtime.lifeNote(f.id)
                                    + " / " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(bed[0].equals(player.getSpawnPointPosition())
                            && player.getSpawnPointDimension() == net.minecraft.world.World.OVERWORLD, "Its spawn at its bed: " + player.getSpawnPointPosition());
                    placedAtSleep[0] = placed(context, hut, hutAt);
                    KindredMod.LOGGER.info("Life first: asleep in its own bed at {} with the hut {} placed", bed[0].toShortString(), placedAtSleep[0]);
                })
                .createAndAdd(() -> {
                    // The build waits while it sleeps; its sleep passes the night.
                    var player = body(context, f);
                    if (player.isSleeping() && placed(context, hut, hutAt) != placedAtSleep[0]) placedAsleep[0] = true;
                    context.assertTrue(!player.isSleeping() && context.getWorld().isDay(), "Morning: " + context.getWorld().getTimeOfDay());
                    KindredMod.LOGGER.info("Life first: awake at {} with the hut {} placed", context.getWorld().getTimeOfDay() % 24000, placed(context, hut, hutAt));
                })
                .createAndAdd(() -> {
                    context.assertTrue(!placedAsleep[0], "Nothing placed asleep");
                    context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "The hut finished: " + f.runtime.buildStatus(f.id).orElse(""));
                    int placed = placed(context, hut, hutAt), total = hut.placed(BuildRunner.pos(hutAt), 0).size();
                    context.assertTrue(placed == total, "The hut exact: " + placed + " of " + total);
                    context.assertTrue(noted[0], "It said why: night");
                    KindredMod.LOGGER.info("Life first: the hut built on in the morning, {} blocks", total);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "life-food", tickLimit = 30000)
    public void hungryWithTooLittleToLastItHuntsFirstThenBuildsItsBase(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 29500);
        var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos[] trunks = {o.add(40, 7, 4), o.add(40, 7, 40)};
        BlockPos home = o.add(16, 7, 16);
        int[] stockAtBase = {-1};
        boolean[] noted = {false};
        Box field = new Box(o.add(-12, 0, -12).toCenterPos(), o.add(44, 24, 44).toCenterPos());
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, trunks);
                    world.setTimeOfDay(1000);
                    // The field is kept ticking by the ground's own rule: out of ticking ground the cows freeze, their hurt
                    // time never runs out, and every blow after the first is shrugged off.
                    // Eight cows grazing in sight, to the south.
                    for (int i = 0; i < 8; i++) {
                        var cow = EntityType.COW.create(world, null, o.add(8 + 2 * i, 7, 28 + (i % 3) * 2), SpawnReason.COMMAND, false, false);
                        context.assertTrue(cow != null && world.spawnEntity(cow), "A cow");
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The cabin's bill as though gathered, a sword, and one loaf: hungry, with too little to last.
                    ItemStack[] held = {
                            new ItemStack(Items.STONE_SWORD), new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE),
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 40), new ItemStack(Items.OAK_LOG, 12),
                            new ItemStack(Items.OAK_STAIRS, 42), new ItemStack(Items.OAK_SLAB, 7), new ItemStack(Items.OAK_DOOR, 1),
                            new ItemStack(Items.RED_BED, 1), new ItemStack(Items.CRAFTING_TABLE, 2), new ItemStack(Items.CHEST, 2),
                            new ItemStack(Items.TORCH, 1), new ItemStack(Items.FURNACE, 1), new ItemStack(Items.COBBLESTONE, 64),
                            new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 1)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(8);
                    player.getHungerManager().setSaturationLevel(0);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                })
                .createAndAdd(() -> {
                    if (f.runtime.lifeNote(f.id).startsWith("Hungry")) noted[0] = true;
                    // Its base is begun only once it has food to last: what it holds then, body and pack.
                    if (stockAtBase[0] < 0 && f.runtime.currentBase(f.id).isPresent()) stockAtBase[0] = stock(body(context, f));
                    var base = f.runtime.currentBase(f.id);
                    context.assertTrue(base.isPresent() && base.get().complete(), "Its cabin built: " + base + " / "
                            + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.lifeNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                    NativeBaseGameTests.exact(context, cabin, new BlockPos(base.get().x(), base.get().y(), base.get().z()));
                })
                .createAndAdd(() -> {
                    int cows = context.getWorld().getEntitiesByClass(CowEntity.class, field, CowEntity::isAlive).size();
                    KindredMod.LOGGER.info("Life first: {} cows hunted; {} food in its body and pack when it began its base", 8 - cows, stockAtBase[0]);
                    context.assertTrue(noted[0], "It said why: hungry");
                    context.assertTrue(cows < 8, "It hunted: " + cows + " cows left");
                    context.assertTrue(stockAtBase[0] >= dev.kindred.core.survival.LifeFirst.FULL + dev.kindred.core.survival.LifeFirst.RESERVE,
                            "Food to last before the base: " + stockAtBase[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The player's body, or a failure: after the fixture's own time runs out its body is gone. */
    private static net.minecraft.server.network.ServerPlayerEntity body(TestContext context, NativePaidSkillsGameTests.Fixture f) {
        var player = f.player();
        context.assertTrue(player != null, "Its body");
        return player;
    }

    private static int placed(TestContext context, dev.kindred.core.construction.Blueprint blueprint, BlockPos origin) {
        var world = context.getWorld();
        int placed = 0;
        for (var b : blueprint.placed(BuildRunner.pos(origin), 0)) {
            if (BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at()))).sameAs(b.spec())) placed++;
        }
        return placed;
    }

    /** Its hunger and the food points it carries, as the life list counts them (chicken and the like aside). */
    private static int stock(net.minecraft.server.network.ServerPlayerEntity player) {
        int points = player.getHungerManager().getFoodLevel();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            var food = stack.get(DataComponentTypes.FOOD);
            if (food != null && !stack.isOf(Items.CHICKEN) && !stack.isOf(Items.ROTTEN_FLESH)) points += food.nutrition() * stack.getCount();
        }
        return points;
    }
}
