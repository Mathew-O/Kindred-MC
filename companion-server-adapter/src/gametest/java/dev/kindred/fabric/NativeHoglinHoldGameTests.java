package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In plain terms: an AI player that has climbed up out of a hoglin's reach does not stay up there for good when the
 * hoglin keeps watching it from a few blocks off and never comes closer; it gets on with its work, and when the
 * hoglin then comes for it, it deals with it without bouncing up and down a pillar.
 *
 * Technical: F-25c6f. In e40c5 two Nether players spent the run's end treed above a hoglin (31102026 Echo: some 109 of
 * its 161 Nether minutes, the last 273 waits in a row to the end). {@code PortalRunCampaign.defend} waited while a
 * hoglin was after the player and it was out of reach, struck from above only within 3 blocks, and gave up
 * (TREED_GIVE_UP) only once no hoglin was after it. STAGED: the player armed, set on a pillar of cobblestone two blocks
 * high (out of reach); a hoglin five blocks off on the ground, after the player, its AI held so it never closes in
 * (said so: a hoglin's own wandering would make the hold a matter of chance). The Nether-portal goal under way. Once
 * the wait has been given up, the hoglin's AI is let go (STAGED) and 3,600 ticks watched.
 */
public final class NativeHoglinHoldGameTests implements FabricGameTest {
    /** The bound the treed wait is held to: TREED_GIVE_UP and a minute over. */
    private static final int BOUND = (int) PortalRunCampaign.TREED_GIVE_UP + 1200;
    /** Watched after the hoglin is let go. */
    private static final int AFTER = 3600;
    /** No ping-pong: at most one new treed spell each TREED_TICKS over the watch. */
    private static final int RETREES = AFTER / (int) PortalRunCampaign.TREED_TICKS;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hoglinHold", tickLimit = 7400)
    public void treedAboveAHoglinThatKeepsWatchingFromAfarItDoesNotWaitForGood(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 7300);
        String name = "Held";
        List<String> treed = new CopyOnWriteArrayList<>(), givenUp = new CopyOnWriteArrayList<>(), fights = new CopyOnWriteArrayList<>(),
                deaths = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("hoglinHold", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (line.startsWith(name + " treed spell at ") && !line.contains(" still on after ")) treed.add(line);
                if (line.startsWith(name + " treed spell given up ")) givenUp.add(line);
                if (line.startsWith(name + " fight starts with hoglin")) fights.add(line);
                if (line.startsWith(name + " died at ")) deaths.add(line);
            }
        };
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        HoglinEntity[] hoglin = new HoglinEntity[1];
        long[] start = {-1}, released = {-1};
        int[] treedBefore = {0};
        boolean[] logged = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(-6, 1, -6)), context.getAbsolutePos(new BlockPos(12, 8, 14)));
                    for (int x = -6; x <= 12; x++) for (int z = -6; z <= 14; z++) {
                        world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
                    }
                    // STAGED: its pillar, two of cobblestone, as its own pillar-up would leave it.
                    world.setBlockState(context.getAbsolutePos(new BlockPos(3, 2, 3)), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(context.getAbsolutePos(new BlockPos(3, 3, 3)), Blocks.COBBLESTONE.getDefaultState());
                    var player = f.player();
                    BlockPos top = context.getAbsolutePos(new BlockPos(3, 4, 3));
                    player.refreshPositionAndAngles(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    hoglin[0] = EntityType.HOGLIN.create(world);
                    BlockPos at = context.getAbsolutePos(new BlockPos(3, 2, 8));
                    hoglin[0].refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
                    hoglin[0].setImmuneToZombification(true); // Out of the Nether it would turn into a zoglin.
                    hoglin[0].setPersistent();
                    hoglin[0].setAiDisabled(true); // STAGED: it stays five blocks off, after the player, and never closes in.
                    world.spawnEntity(hoglin[0]);
                    hoglin[0].getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET, player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isOnGround() && PortalRunCampaign.outOfReach(player, hoglin[0]), "Up out of its reach on the pillar");
                    context.assertTrue(hoglin[0].getTarget() == player && player.canSee(hoglin[0]),
                            "The hoglin after it and in sight: target " + hoglin[0].getTarget() + ", in sight " + player.canSee(hoglin[0]));
                    context.assertTrue(player.distanceTo(hoglin[0]) > 4 && player.distanceTo(hoglin[0]) < 6.5, "Four to six blocks off: " + player.distanceTo(hoglin[0]));
                    start[0] = context.getWorld().getTime();
                })
                .createAndAdd(() -> {
                    // Held up there by a hoglin that never comes: the wait is given up within the bound.
                    int now = (int) (context.getWorld().getTime() - start[0]);
                    context.assertTrue(hoglin[0].isAlive() && hoglin[0].getTarget() == f.player(), "The hoglin still after it");
                    context.assertTrue(!givenUp.isEmpty() || now > BOUND, "Waiting: " + now);
                    context.assertTrue(!givenUp.isEmpty(), "The treed wait given up by tick " + BOUND + " (TREED_GIVE_UP and a minute): "
                            + treed.size() + " spell(s), none given up");
                    KindredMod.LOGGER.info("Held: given up at tick {}: {}", now, givenUp.getFirst());
                    // STAGED: the hoglin let go; what the player does next is its own.
                    hoglin[0].setAiDisabled(false);
                    hoglin[0].getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET, f.player());
                    released[0] = context.getWorld().getTime();
                    treedBefore[0] = treed.size();
                })
                .createAndAdd(() -> {
                    long now = context.getWorld().getTime() - released[0];
                    context.assertTrue(deaths.isEmpty(), "Never killed: " + deaths);
                    context.assertTrue(now >= AFTER, "Watched " + AFTER + " ticks after the hoglin was let go: " + now);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    int retrees = treed.size() - treedBefore[0];
                    if (!logged[0]) {
                        logged[0] = true;
                        KindredMod.LOGGER.info("Held: after the hoglin was let go, {} re-tree(s), {} give-up(s) in all, {} fight(s) with it, {} death(s); "
                                        + "hoglin alive {}, {} away; player health {}, at {}",
                                retrees, givenUp.size(), fights.size(), deaths.size(), hoglin[0].isAlive(),
                                hoglin[0].isAlive() ? String.format(java.util.Locale.ROOT, "%.1f", player.distanceTo(hoglin[0])) : "-",
                                player.getHealth(), player.getBlockPos().toShortString());
                    }
                    context.assertTrue(deaths.isEmpty(), "Never killed: " + deaths);
                    context.assertTrue(retrees <= RETREES, "No ping-pong: " + retrees + " re-tree(s) in " + AFTER + " ticks, at most " + RETREES);
                    hoglin[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
