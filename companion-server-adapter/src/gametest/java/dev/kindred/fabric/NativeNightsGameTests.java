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
