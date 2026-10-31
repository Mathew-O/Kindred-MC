package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * In plain terms: a player with no adviser learns from its own deaths. Twice dead hungry, it keeps more food; three
 * deaths below ground in a day, it digs its own way rather than go into a cave. And the two habits only its own play
 * teaches work: torches as it goes, and the water bucket kept to hand.
 *
 * Technical: E-46. Deaths are staged: the player is killed by the fixture, with its food set first, and respawns at a
 * spawn point the fixture gives it in its own area. Its drops are cleared after each death, so a walk back for them
 * gives it nothing. The lessons are then read from its memory and its running goal as play reads them. The fixtures for
 * the two new habits stage the habit through the goal's advice, which the strategist and the lessons share.
 */
public final class NativeLessonsGameTests implements FabricGameTest {
    /**
     * Killed twice with three food, a day apart at most: its memory keeps "Twice dead hungry; carry more food." with
     * {@code MORE_FOOD}, marked as from its own play, and its goal keeps the habit. Then, stocked with the six bread that
     * are its usual stock for the work below, at the iron stage with a cow in sight, it hunts the cow before going on:
     * the next provisioning wants three more. (Advised nothing, the route goes on to iron with that stock:
     * {@code goalHuntsFirstWhenAdvisedFoodFirst}'s scene.)
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lessonFood", tickLimit = 2400)
    public void twoHungryDeathsTeachMoreFoodAndTheNextProvisioningWantsThreeMore(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] home = new BlockPos[1];
        ServerPlayerEntity[] dead = new ServerPlayerEntity[1];
        int[] waited = new int[1];
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hungry"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    home[0] = f.player().getBlockPos().toImmutable();
                    f.player().setSpawnPoint(context.getWorld().getRegistryKey(), home[0], 0, true, false);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 40, "Its memory read"));
        int[] alive = new int[1];
        for (int life = 0; life < 2; life++) {
            runner.createAndAdd(() -> die(context, f, dead, 3))
                    .createAndAdd(() -> respawned(context, f, dead, home[0], alive))
                    .createAndAdd(() -> context.assertTrue(++alive[0] >= 40, "A moment alive"));
        }
        runner.createAndAdd(() -> {
                    var notes = f.runtime.notesForFixtures(f.id);
                    context.assertTrue(notes.contains("[habit MORE_FOOD, from its own play] Twice dead hungry; carry more food."),
                            "The lesson in its memory: " + notes);
                    context.assertTrue(f.runtime.habitsForFixtures(f.id).contains(PlanPreferences.Habit.MORE_FOOD),
                            "The habit in its goal: " + f.runtime.habitsForFixtures(f.id));
                    context.assertTrue(f.runtime.habitsForFixtures(f.id).equals(java.util.Set.of(PlanPreferences.Habit.MORE_FOOD)),
                            "Nothing else learned from two deaths: " + f.runtime.habitsForFixtures(f.id));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = home[0];
                    var cow = net.minecraft.entity.EntityType.COW.create(world);
                    cow.refreshPositionAndAngles(p.getX() + 3.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    cow.setPersistent();
                    cow.setAiDisabled(true);
                    world.spawnEntity(cow);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 6));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.BEEF) >= 1,
                            "It hunted the cow for the food it learned to want: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Killed three times in a day, well fed and by day, below y 40 (the fixtures' ground is far below it): its memory
     * keeps "The caves took three lives today; dig for ore instead." with {@code NO_CAVES}. Then in the cave scene of
     * {@code goalKeepsAHabitOfLeavingCavesAlone}, a cave like the one {@code goalExploresACaveAndMinesIronItCouldNotSeeAtFirst}
     * explores for its iron, it digs its own way and leaves the cave alone, as that fixture does with the strategist's habit.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lessonCaves", tickLimit = 3000)
    public void aDayOfThreeDeathsBelowGroundTeachesNoCavesAndTheNextOrePlanDigsItsOwnWay(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] home = new BlockPos[1];
        ServerPlayerEntity[] dead = new ServerPlayerEntity[1];
        int[] waited = new int[1];
        int[] settle = new int[1];
        boolean[] explored = {false};
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Caver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    home[0] = f.player().getBlockPos().toImmutable();
                    f.player().setSpawnPoint(context.getWorld().getRegistryKey(), home[0], 0, true, false);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 40, "Its memory read"));
        int[] alive = new int[1];
        for (int life = 0; life < 3; life++) {
            runner.createAndAdd(() -> die(context, f, dead, 20))
                    .createAndAdd(() -> respawned(context, f, dead, home[0], alive))
                    .createAndAdd(() -> context.assertTrue(++alive[0] >= 40, "A moment alive"));
        }
        runner.createAndAdd(() -> {
                    var notes = f.runtime.notesForFixtures(f.id);
                    context.assertTrue(notes.contains("[habit NO_CAVES, from its own play] The caves took three lives today; dig for ore instead."),
                            "The lesson in its memory: " + notes);
                    context.assertTrue(f.runtime.habitsForFixtures(f.id).equals(java.util.Set.of(PlanPreferences.Habit.NO_CAVES)),
                            "The habit in its goal, and no other: " + f.runtime.habitsForFixtures(f.id));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = home[0];
                    Direction alongX = (p.getX() & 3) >= 2 ? Direction.EAST : Direction.WEST;
                    Direction alongZ = (p.getZ() & 3) >= 2 ? Direction.SOUTH : Direction.NORTH;
                    for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 3; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.STONE.getDefaultState());
                    }
                    var passage = new java.util.ArrayList<BlockPos>();
                    for (int i = 0; i <= 2; i++) passage.add(p.offset(alongX, i));
                    for (int i = 1; i <= 2; i++) passage.add(p.offset(alongX, 2).offset(alongZ, i));
                    for (BlockPos cell : passage) {
                        world.setBlockState(cell, Blocks.AIR.getDefaultState());
                        world.setBlockState(cell.up(), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(p.offset(alongX, 2).offset(alongZ, 3), Blocks.IRON_ORE.getDefaultState());
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(5, new ItemStack(Items.COAL, 4));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                })
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    explored[0] |= progress.contains("Explore the cave");
                    context.assertTrue(progress.contains("Branch mine") || progress.contains("Dig down"), "It digs its own way for iron: " + progress);
                })
                .createAndAdd(() -> {
                    context.assertFalse(explored[0], "It kept to its lesson and left the cave alone");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Below ground in its tunnel, half lit by a torch nine blocks off (light 5 where it stands), with four torches of its
     * own: having the torch habit, it places one there from its own stock. Without the habit it waits for the dark
     * (light 1) before it lights the way, about every thirteen blocks.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lessonTorch", tickLimit = 900)
    public void theTorchHabitLightsItsTunnelFromItsOwnTorchesBeforeItIsDark(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Torcher"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-2, -1, -2), p.add(12, 3, 2));
                    for (BlockPos cell : BlockPos.iterate(p.add(-2, -1, -2), p.add(12, 3, 2))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
                    for (int x = 0; x <= 10; x++) {
                        world.setBlockState(p.east(x), Blocks.AIR.getDefaultState());
                        world.setBlockState(p.east(x).up(), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(p.east(9), Blocks.TORCH.getDefaultState());
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, -90, 0);
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    int light = context.getWorld().getLightLevel(net.minecraft.world.LightType.BLOCK, at[0]);
                    context.assertTrue(light > 1 && light <= PortalRunCampaign.TORCH_AS_YOU_GO_LIGHT, "Half lit where it stands: " + light);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(5, new ItemStack(Items.TORCH, 4));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    var advice = dev.kindred.core.survival.StrategyAdvice.validated("PRESS_ON", java.util.List.of(),
                            "Something spawned in my own tunnel; torches as I go.", "", java.util.List.of(), null, "TORCH_AS_YOU_GO", null).orElseThrow();
                    context.assertTrue(f.runtime.adviseGoal(f.id, advice), "The habit kept");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    long placed = java.util.stream.StreamSupport.stream(BlockPos.iterate(at[0].add(-1, 0, -1), at[0].add(8, 2, 1)).spliterator(), false)
                            .filter(cell -> world.getBlockState(cell).isOf(Blocks.TORCH) || world.getBlockState(cell).isOf(Blocks.WALL_TORCH)).count();
                    context.assertTrue(placed >= 1 && ItemMoves.count(f.player().getInventory(), Items.TORCH) == 3,
                            "A torch of its own placed in the half-lit tunnel (" + placed + " placed, "
                                    + ItemMoves.count(f.player().getInventory(), Items.TORCH) + " left): "
                                    + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A water bucket carried in the inventory outside the hotbar, where a fall's clutch cannot reach it: having the
     * bucket habit, it moves it into the hotbar. Without the habit it stays where it is.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lessonBucket", tickLimit = 600)
    public void theBucketHabitKeepsTheWaterBucketInTheHotbar(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bucketeer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(20, new ItemStack(Items.WATER_BUCKET));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    var advice = dev.kindred.core.survival.StrategyAdvice.validated("PRESS_ON", java.util.List.of(),
                            "Falls have cost two lives; keep the bucket to hand.", "", java.util.List.of(), null, "BUCKET_ON_HOTBAR", null).orElseThrow();
                    context.assertTrue(f.runtime.adviseGoal(f.id, advice), "The habit kept");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    boolean toHand = java.util.stream.IntStream.range(0, 9).anyMatch(slot -> inventory.getStack(slot).isOf(Items.WATER_BUCKET));
                    context.assertTrue(toHand, "The water bucket in the hotbar: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.WATER_BUCKET) == 1, "Still one water bucket");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Its food set, then killed by the fixture, as {@code NativeRespawnGameTests} does. */
    private static void die(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity[] dead, int food) {
        var player = f.player();
        context.assertTrue(player != null && player.isAlive(), "Alive before it dies");
        context.getWorld().setTimeOfDay(1000);
        player.getHungerManager().setFoodLevel(food);
        dead[0] = player;
        player.kill();
        context.assertFalse(player.isAlive(), "Killed");
    }

    /** Respawned at its spawn point in this test's area, and its drops cleared; the count of its moments alive started again. */
    private static void respawned(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity[] dead, BlockPos home, int[] alive) {
        var fresh = f.player();
        context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned");
        for (ItemEntity drop : context.getWorld().getEntitiesByClass(ItemEntity.class, new Box(home).expand(10, 6, 10), drop -> true)) drop.discard();
        context.assertTrue(fresh.getBlockPos().getSquaredDistance(home) <= 4, "At its spawn point in this area: " + fresh.getBlockPos());
        alive[0] = 0;
    }
}
