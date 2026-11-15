package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * Woken with nothing at the bed it carries, just after a death, with a mob about, it takes the bed up (F-25c4di1). In E-40
 * iteration 23 (`e40a23-3115b1c`, #352) on 101 Sol, its bed set down underground at −377, 31, −143, it died 12 times, each
 * 15 to 50 blocks from the bed in the caves round it, and each time woke at −376, 31, −145 with nothing and went out into
 * them again. It never took the bed up: that is a morning chore, put off while a hostile mob is within 16, as one always
 * was down there (RUNNER 2's {@code claude/corpus-data-e40a23-1115}). Taken up, the bed is back in its pack, and by
 * vanilla's own rule its next death wakes it elsewhere.
 */
public final class NativeWakeAtBedGameTests implements FabricGameTest {
    /** How long it has, woken at its bed, to take it up. */
    private static final int WAKE = 400;

    /**
     * In plain terms: an AI player that has just died and woken up at the bed it set down underground, with a monster
     * about and nothing on it, picks the bed up, so the next time it dies it does not come back to the same spot.
     *
     * Technical: F-25c4di1, as e40a23's 101 Sol (above). STAGED: stone underfoot and air over it; no home bed (home out of
     * reach); a stone pickaxe, 32 cobblestone and a white bed in its pack; night, its own play, no goal. It digs in, seals
     * its pocket and sets its own bed down there and sleeps in it by its own play, which sets its spawn there. Then the
     * fixture kills it (the fixture's death, not play), puts a husk (a zombie that does not burn by day) with its AI off on the ground four blocks off (a
     * hostile within 16, as there always was in the run; easy difficulty for the test, so it is not taken away), and holds
     * daylight. It wakes at its bed with nothing. Passes if,
     * within {@value #WAKE} ticks of waking, its bed is taken up and back in its pack; then, killed again by the fixture,
     * it does not wake in its pocket.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "wakeAtBed", tickLimit = 6000)
    public void wokenWithNothingAtItsBedAfterADeathItTakesTheBedUp(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5800);
        BlockPos[] bed = {null}, pocket = {null};
        ServerPlayerEntity[] dead = {null};
        HuskEntity[] husk = {null};
        int[] woken = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Waker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos feet = context.getAbsolutePos(new BlockPos(8, 2, 8));
                    NativeNightsGameTests.pad(context, feet);
                    player.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    player.getInventory().setStack(20, new ItemStack(Items.WHITE_BED));
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    context.getWorld().setTimeOfDay(13_000);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Its own play resumed");
                })
                .createAndAdd(() -> {
                    // Its own play: dug in, sealed, its bed down and asleep in it.
                    var player = f.player();
                    context.assertTrue(player != null && player.isSleeping(), "Asleep in the bed it carries: " + (player == null ? "" : player.getBlockPos().toShortString())
                            + " / " + f.runtime.lifeNote(f.id));
                    BlockPos slept = player.getSleepingPosition().orElseThrow();
                    var state = context.getWorld().getBlockState(slept);
                    context.assertTrue(state.isOf(net.minecraft.block.Blocks.WHITE_BED), "Its own white bed");
                    bed[0] = slept.toImmutable();
                    pocket[0] = player.getBlockPos().toImmutable();
                    BlockPos spawn = player.getSpawnPointPosition();
                    context.assertTrue(spawn != null && (spawn.equals(slept) || context.getWorld().getBlockState(spawn).isIn(BlockTags.BEDS)),
                            "Its spawn at the bed it carries: " + spawn);
                })
                .createAndAdd(() -> {
                    // The fixture's death, and the cave's mob: a husk (a zombie that does not burn in the held daylight), its AI off, on the ground four blocks off. Not on peaceful,
                    // where it would be taken away: easy for this test, the server's own difficulty put back as it ends.
                    var world = context.getWorld();
                    var server = world.getServer();
                    var difficulty = server.getSaveProperties().getDifficulty();
                    FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
                    server.setDifficulty(net.minecraft.world.Difficulty.EASY, true);
                    var made = EntityType.HUSK.create(world);
                    BlockPos at = context.getAbsolutePos(new BlockPos(12, 2, 8));
                    made.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    made.setAiDisabled(true);
                    made.setPersistent();
                    world.spawnEntity(made);
                    husk[0] = made;
                    dead[0] = f.player();
                    dead[0].kill();
                    context.assertFalse(dead[0].isAlive(), "Killed");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned");
                    context.getWorld().setTimeOfDay(1_000);
                    context.assertTrue(fresh.getBlockPos().getSquaredDistance(bed[0]) <= 3 * 3, "Woken at its bed: " + fresh.getBlockPos().toShortString()
                            + ", the bed at " + bed[0].toShortString());
                    context.assertTrue(fresh.getInventory().count(Items.WHITE_BED) == 0, "Woken with nothing");
                })
                .createAndAdd(() -> {
                    // Daylight held; the husk still there.
                    context.getWorld().setTimeOfDay(1_000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died");
                    context.assertTrue(husk[0].isAlive(), "The husk is gone");
                    boolean down = context.getWorld().getBlockState(bed[0]).isIn(BlockTags.BEDS);
                    context.assertTrue(++woken[0] <= WAKE || !down, "Its bed still down " + WAKE + " ticks after waking by it: " + f.runtime.lifeNote(f.id)
                            + " / " + f.runtime.bedChoreForTest(f.id));
                    context.assertTrue(!down, "Bed still down");
                    context.assertTrue(player.getInventory().count(Items.WHITE_BED) == 1, "The bed not yet back in its pack");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Wake at bed: its bed taken up {} ticks after waking; {}", woken[0], f.runtime.bedChoreForTest(f.id));
                    dead[0] = f.player();
                    dead[0].kill();
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned again");
                    context.assertTrue(fresh.getBlockPos().getSquaredDistance(pocket[0]) > 4 * 4, "Woken in its pocket again: " + fresh.getBlockPos().toShortString());
                    husk[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
