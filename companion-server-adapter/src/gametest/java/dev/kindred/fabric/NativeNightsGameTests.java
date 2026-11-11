package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * In plain terms: a GameTest: in a real test world, a person lies down in a bed at night while an AI player is up, a
 * zombie on it. The AI player cannot be in bed, so it is left out of the count of players the night waits for, and the
 * person's night passes as though they were alone. With the operator's {@code aiPlayersCountForSleep} on, the AI player
 * counts as vanilla counts everyone, and the night waits for it.
 *
 * Technical: E-70a, the sleep count ({@code SleepCountMixin}, {@link SleepCount}). The AI player's autonomy is off, so
 * nothing takes it to bed; Resistance keeps it standing through the zombie's blows (setup, not play). The person is a
 * {@link MockPlayers#survival} player, a human to Kindred.
 */
public final class NativeNightsGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "nights-count", tickLimit = 1600)
    public void anAiPlayerInAFightDoesNotHoldAPersonsNight(TestContext context) {
        count(context, false);
    }

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "nights-count-on", tickLimit = 1600)
    public void withAiPlayersCountedAnAiPlayerUpHoldsTheNightAsVanillaWould(TestContext context) {
        count(context, true);
    }

    private static void count(TestContext context, boolean counted) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 8000, counted ? "aiPlayersCountForSleep=true\n" : "");
        BlockPos bed = new BlockPos(30, 2, 30);
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        long[] laidDownAt = {-1};
        ticked(context, person, 1600);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wakeful"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.assertTrue(SleepCount.aiPlayersCount() == counted, "The operator's switch read: " + SleepCount.aiPlayersCount());
                    var ai = f.player();
                    ai.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 120, 4, false, false));
                    var zombie = EntityType.ZOMBIE.create(context.getWorld());
                    var at = ai.getBlockPos().east(2);
                    zombie.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    zombie.initialize(context.getWorld(), context.getWorld().getLocalDifficulty(at), SpawnReason.COMMAND, null);
                    zombie.setTarget(ai);
                    zombie.setPersistent();
                    context.getWorld().spawnEntity(zombie);
                    placeBed(context, bed, Direction.SOUTH);
                    context.getWorld().setTimeOfDay(14_000);
                    person[0] = MockPlayers.survival(context, "Night-owl", bed.west());
                })
                .createAndAdd(() -> {
                    var lay = person[0].trySleep(context.getAbsolutePos(bed));
                    context.assertTrue(lay.right().isPresent() && person[0].isSleeping(), "The person lies down: " + lay.left());
                    laidDownAt[0] = context.getWorld().getTimeOfDay();
                })
                .createAndAdd(() -> {
                    var ai = f.player();
                    context.assertTrue(ai != null && !ai.isSleeping(), "The AI player is up");
                    long time = context.getWorld().getTimeOfDay() % 24_000L;
                    if (counted) {
                        context.assertTrue(person[0].isSleeping() && person[0].getSleepTimer() >= 100 && time >= 14_000,
                                "The night waits for the AI player, as vanilla's count does: " + time);
                    } else {
                        context.assertTrue(time < 12_000, "The person's night passed without the AI player: " + time + " (" + context.getWorld().getTimeOfDay() + ")");
                    }
                })
                .createAndAdd(() -> {
                    if (person[0].isSleeping()) person[0].wakeUp();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Home in reach at the end of the afternoon (E-70a): its home bed, the bed its spawn is set at, some sixty blocks off.
     * It turns for home in time and is asleep there at the first moment a bed can be slept in, not some while after.
     * Before, a player without work of its own went to no bed at all.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "nights-dusk", tickLimit = 3000)
    public void homeInReachItIsInBedAtTheFirstSleepingTick(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8000);
        BlockPos foot = new BlockPos(5, 2, 8), start = new BlockPos(66, 2, 6);
        long[] asleepAt = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Homebody"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    floor(context, 0, 75, 0, 14);
                    placeBed(context, foot, Direction.SOUTH);
                    var player = f.player();
                    // Its home bed: the bed it last slept in (setup, as a night before this one would have left it).
                    player.setSpawnPoint(context.getWorld().getRegistryKey(), context.getAbsolutePos(foot.south()), 0, true, false);
                    var at = context.getAbsolutePos(start);
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setTimeOfDay(11_000);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Its own play resumed");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    long time = context.getWorld().getTimeOfDay() % 24_000L;
                    context.assertTrue(!player.isSleeping() || time >= dev.kindred.core.survival.Nights.SLEEP_FROM, "Not asleep before a bed can be slept in");
                    context.assertTrue(player.isSleeping() && player.getSleepingPosition().filter(context.getAbsolutePos(foot.south())::equals).isPresent(),
                            "Asleep in its home bed: " + player.getSleepingPosition() + " at " + time + " from " + player.getBlockPos().toShortString()
                                    + " / " + f.runtime.lifeNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                    asleepAt[0] = time;
                })
                .createAndAdd(() -> {
                    long late = asleepAt[0] - dev.kindred.core.survival.Nights.SLEEP_FROM;
                    KindredMod.LOGGER.info("Nights: asleep in its home bed at {}, {} ticks after a bed can first be slept in", asleepAt[0], late);
                    context.assertTrue(late >= 0 && late <= 40, "In bed at the first sleeping tick: " + late + " ticks after");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A person lies down at night while an AI player is on its way home to its bed, a walk longer than vanilla's five
     * seconds before a night passes (E-70a). It counts toward the person's night while on its way, so the night passes
     * with both asleep, not with it left out and still walking. Before, a player without work of its own went to no bed,
     * and the night either waited for it or passed with it awake.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "nights-person", tickLimit = 2400)
    public void whenAPersonLiesDownTheAiPlayerGoesToBedAndTheNightPasses(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8000);
        BlockPos foot = new BlockPos(5, 2, 8), personsFoot = new BlockPos(40, 2, 8), start = new BlockPos(70, 2, 4);
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        boolean[] bothAsleep = {false};
        ticked(context, person, 2400);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Companionable"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    floor(context, 0, 75, 0, 14);
                    placeBed(context, foot, Direction.SOUTH);
                    placeBed(context, personsFoot, Direction.SOUTH);
                    var player = f.player();
                    player.setSpawnPoint(context.getWorld().getRegistryKey(), context.getAbsolutePos(foot.south()), 0, true, false);
                    var at = context.getAbsolutePos(start);
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    context.getWorld().setTimeOfDay(14_000);
                    person[0] = MockPlayers.survival(context, "Sleepy", personsFoot.west());
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Its own play resumed");
                })
                .createAndAdd(() -> {
                    // On its way home, still far from its bed.
                    var player = f.player();
                    context.assertTrue(player.getX() < context.getAbsolute(new net.minecraft.util.math.Vec3d(66, 2, 4)).x,
                            "On its way home: " + player.getBlockPos().toShortString() + " / " + f.runtime.lifeNote(f.id));
                })
                .createAndAdd(() -> {
                    var lay = person[0].trySleep(context.getAbsolutePos(personsFoot));
                    context.assertTrue(lay.right().isPresent() && person[0].isSleeping(), "The person lies down: " + lay.left()
                            + " with the AI player at " + f.player().getBlockPos().toShortString());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    long time = context.getWorld().getTimeOfDay() % 24_000L;
                    if (player != null && player.isSleeping() && person[0].isSleeping() && time >= 13_000) bothAsleep[0] = true;
                    context.assertTrue(time < 12_000, "The night passed: " + time + " / " + (player == null ? "" : player.getBlockPos().toShortString())
                            + " / " + f.runtime.lifeNote(f.id));
                })
                .createAndAdd(() -> {
                    context.assertTrue(bothAsleep[0], "The AI player was in bed with the person before the night passed: " + f.runtime.lifeNote(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Home out of reach with a bed in its pack (E-70a): some nine hundred blocks from its home bed at nightfall, it digs in,
     * seals its pocket, sets its own bed down there and sleeps in it, which moves where it wakes after a death to that
     * bed, as vanilla moves it. In the morning it takes the bed back up. Brought home again by day, it uses its home bed,
     * which sets where it wakes back there. Before, a player without work of its own spent the night where it stood.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "nights-carried", tickLimit = 6000)
    public void homeOutOfReachItSleepsInTheBedItCarriesTakesItBackAndSetsHomeAgain(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8000);
        BlockPos homeFoot = new BlockPos(5, 2, 2);
        BlockPos[] away = {null}, slept = {null};
        long[] sleptAt = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wanderer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Ground from its home bed to where it comes home to, inside the test's own structure (cleared after).
                    for (int x = 0; x <= 30; x++) for (int z = 0; z <= 8; z++) {
                        context.setBlockState(x, 1, z, Blocks.STONE);
                        for (int y = 2; y <= 5; y++) context.setBlockState(x, y, z, Blocks.AIR);
                    }
                    placeBed(context, homeFoot, Direction.SOUTH);
                    var player = f.player();
                    player.setSpawnPoint(context.getWorld().getRegistryKey(), context.getAbsolutePos(homeFoot.south()), 0, true, false);
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.COBBLESTONE, 32));
                    player.getInventory().setStack(20, new net.minecraft.item.ItemStack(net.minecraft.item.Items.WHITE_BED));
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    context.getWorld().setTimeOfDay(1_000);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Its own play resumed");
                })
                .createAndAdd(() -> context.assertTrue(context.getAbsolutePos(homeFoot.south()).equals(f.runtime.homeBed(f.id)),
                        "Its home bed known: " + f.runtime.homeBed(f.id)))
                .createAndAdd(() -> {
                    // Away from home (the fixture's move, not play): nine hundred blocks off on ground it can dig, at dusk.
                    var player = f.player();
                    away[0] = context.getAbsolutePos(new BlockPos(3, 2, 3)).add(900, 0, 0);
                    pad(context, away[0]);
                    player.refreshPositionAndAngles(away[0].getX() + 0.5, away[0].getY(), away[0].getZ() + 0.5, 0, 0);
                    context.getWorld().setTimeOfDay(13_000);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isSleeping(), "Asleep in the bed it carries: " + (player == null ? "" : player.getBlockPos().toShortString())
                            + " / " + f.runtime.lifeNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                    slept[0] = player.getSleepingPosition().orElseThrow();
                    sleptAt[0] = context.getWorld().getTimeOfDay();
                    var world = context.getWorld();
                    context.assertTrue(world.getBlockState(slept[0]).isOf(net.minecraft.block.Blocks.WHITE_BED), "Its own white bed");
                    context.assertTrue(slept[0].getY() < away[0].getY() && Math.abs(slept[0].getX() - away[0].getX()) <= 6, "In its pocket, below ground: " + slept[0].toShortString());
                    for (Direction side : Direction.values()) {
                        // The pocket sealed: nothing walks in. Every cell round the bed is ground, the bed, or its own pocket.
                        BlockPos next = player.getBlockPos().offset(side);
                        context.assertTrue(world.getFluidState(next).isEmpty(), "No water or lava by its bed");
                    }
                    BlockPos spawn = player.getSpawnPointPosition();
                    context.assertTrue(spawn != null && (spawn.equals(slept[0]) || world.getBlockState(spawn).isOf(net.minecraft.block.Blocks.WHITE_BED)),
                            "Its spawn at the bed it carries: " + spawn);
                    KindredMod.LOGGER.info("Nights: asleep in the bed it carries at {} from {}", slept[0].toShortString(), sleptAt[0] % 24_000);
                })
                .createAndAdd(() -> {
                    // Alone in the world, its sleep passes the night; then the bed back up.
                    var player = f.player();
                    var world = context.getWorld();
                    context.assertTrue(world.isDay() && !player.isSleeping(), "Morning: " + world.getTimeOfDay() % 24_000);
                    context.assertTrue(!world.getBlockState(slept[0]).isIn(net.minecraft.registry.tag.BlockTags.BEDS), "The bed taken up: " + f.runtime.lifeNote(f.id));
                    context.assertTrue(player.getInventory().count(net.minecraft.item.Items.WHITE_BED) == 1, "The bed back in its pack: "
                            + player.getInventory().count(net.minecraft.item.Items.WHITE_BED));
                })
                .createAndAdd(() -> {
                    // Home again (the fixture's move): twenty blocks from its home bed, by day.
                    var player = f.player();
                    var at = context.getAbsolutePos(new BlockPos(25, 2, 3));
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos home = context.getAbsolutePos(homeFoot.south());
                    context.assertTrue(home.equals(player.getSpawnPointPosition()), "Its home bed set again: " + player.getSpawnPointPosition()
                            + " / " + f.runtime.lifeNote(f.id) + " / " + player.getBlockPos().toShortString());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * No bed at all, home out of reach, on a goal's way at nightfall (E-70a): the night is the hole-up it always was, dug
     * in and bounded, and a bed is wanted first thing in the morning. With nights off, it is not wanted at all.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "nights-nobed", tickLimit = 2400)
    public void noBedItHolesUpAndWantsABedFirstThing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8000);
        BlockPos[] at = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bedless"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    at[0] = context.getAbsolutePos(new BlockPos(60, 2, 60));
                    pad(context, at[0]);
                    player.refreshPositionAndAngles(at[0].getX() + 0.5, at[0].getY(), at[0].getZ() + 0.5, 0, 0);
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.COBBLESTONE, 32));
                    player.getInventory().setStack(2, new net.minecraft.item.ItemStack(net.minecraft.item.Items.BREAD, 16));
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    context.getWorld().setTimeOfDay(13_500);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getBlockY() < at[0].getY(), "Dug in for the night: " + player.getBlockPos().toShortString() + " / " + f.runtime.goalStepForTest(f.id));
                    context.assertTrue("urgent".equals(f.runtime.bedWant(f.id)), "A bed wanted first thing: " + f.runtime.bedWant(f.id));
                    KindredMod.LOGGER.info("Nights: no bed, dug in at {}; its bed want {}", player.getBlockPos().toShortString(), f.runtime.bedWant(f.id));
                    f.runtime.nights(false);
                })
                .createAndAdd(() -> context.assertTrue("none".equals(f.runtime.bedWant(f.id)), "With nights off, no bed wanted: " + f.runtime.bedWant(f.id)))
                .createAndAdd(() -> {
                    f.runtime.nights(true);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A builder with no goal, at night, with a bed in its build's bill (E-70a): it builds on, and does not dig in for the
     * night with the bed. Work of its own keeps the night life-first gives it (F-25c9); the bed in its pack is the one
     * its build sets down. Before, on #216's first head, it took that bed for one it carried with home out of reach, dug
     * in beside its build and built nothing ("No way to reach minecraft:oak_planks", the house fixtures run after these).
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "nights-builder", tickLimit = 19500)
    public void aBuilderAtNightWithABedInItsBillBuildsOnAndDoesNotDigIn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 19500);
        var cabin = dev.kindred.core.construction.Houses.of(dev.kindred.core.construction.Houses.Stage.CABIN, "oak", "cobblestone");
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos origin = o.add(4, 2, 4);
        int[] lowest = {Integer.MAX_VALUE};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (int x = -8; x <= 24; x++) for (int z = -8; z <= 24; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The cabin's bill, as the house fixture gives it: its bed among it.
                    net.minecraft.item.ItemStack[] bill = {
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_PLANKS, 64), new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_PLANKS, 34),
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.COBBLESTONE, 54), new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_LOG, 12),
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_STAIRS, 42), new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_SLAB, 7),
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_DOOR, 1), new net.minecraft.item.ItemStack(net.minecraft.item.Items.RED_BED, 1),
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.CRAFTING_TABLE, 1), new net.minecraft.item.ItemStack(net.minecraft.item.Items.FURNACE, 1),
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.CHEST, 2), new net.minecraft.item.ItemStack(net.minecraft.item.Items.TORCH, 1),
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIRT, 64), new net.minecraft.item.ItemStack(net.minecraft.item.Items.BREAD, 8)};
                    for (int slot = 0; slot < bill.length; slot++) inventory.setStack(slot, bill[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(origin.getX() + 3.5, origin.getY(), origin.getZ() + 10.5, 180, 0);
                    // Night, as the suite's earlier fixtures can leave it.
                    world.setWeather(12_000, 0, false, false);
                    world.setTimeOfDay(14_000);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, cabin, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body: " + f.runtime.lifeNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                    lowest[0] = Math.min(lowest[0], player.getBlockY());
                    context.assertTrue(lowest[0] >= origin.getY() - 1, "Never dug in below its build: down to " + lowest[0] + " / " + f.runtime.lifeNote(f.id));
                    context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("")
                            + " / " + f.runtime.lifeNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    context.assertTrue(lowest[0] >= origin.getY() - 1, "Never dug in below its build: down to " + lowest[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** Stone from five below {@code feet} (absolute) to just under it, eleven across, and air above: ground to dig in, put back as the test ends. */
    static void pad(TestContext context, BlockPos feet) {
        var world = context.getWorld();
        FixtureGround.keep(context, feet.add(-5, -5, -5), feet.add(5, 3, 5));
        for (BlockPos cell : BlockPos.iterate(feet.add(-5, -5, -5), feet.add(5, 3, 5))) {
            world.setBlockState(cell, cell.getY() < feet.getY() ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
        }
    }

    /** Stone underfoot and air above from x0 to x1, z0 to z1 (relative), put back as the test ends. */
    static void floor(TestContext context, int x0, int x1, int z0, int z1) {
        FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(x0, 1, z0)), context.getAbsolutePos(new BlockPos(x1, 5, z1)));
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y <= 5; y++) context.setBlockState(x, y, z, Blocks.AIR);
        }
    }

    /**
     * {@code person} ticked as a player each test tick, as its client's connection would tick it: a mock player's
     * embedded connection is not ticked by the server, so its sleep timer would never run.
     */
    static void ticked(TestContext context, ServerPlayerEntity[] person, int ticks) {
        for (int t = 1; t < ticks; t++) {
            context.runAtTick(t, () -> {
                var p = person[0];
                if (p != null && !p.isRemoved() && context.getWorld().getServer().getPlayerManager().getPlayer(p.getUuid()) == p) p.playerTick();
            });
        }
    }

    /** A bed whose foot is at {@code foot} (relative), its head toward {@code facing}, on stone. */
    static void placeBed(TestContext context, BlockPos foot, Direction facing) {
        BlockPos head = foot.offset(facing);
        context.setBlockState(foot.down(), Blocks.STONE);
        context.setBlockState(head.down(), Blocks.STONE);
        context.setBlockState(foot, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, facing).with(BedBlock.PART, BedPart.FOOT));
        context.setBlockState(head, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, facing).with(BedBlock.PART, BedPart.HEAD));
    }
}
