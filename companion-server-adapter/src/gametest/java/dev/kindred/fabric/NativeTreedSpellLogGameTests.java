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
 * In plain terms: when an AI player climbs up out of a hoglin's reach and waits there, the log says where the hoglin
 * was (how far, how far below, whether it was after the player) once when that starts, and how long it lasted when
 * it ends.
 *
 * Technical: F-25c6f's line, log only. In e40c5 two Nether players spent the run's end treed above a hoglin, waiting it
 * out (31102026 Echo: 3,261 two-second waits over its stay), and the wait's line named no hoglin. STAGED as
 * {@code NativeEdgeFightGameTests.aHoglinAfterThePlayerIsLeftBelowNotFought}: open ground, the Nether-portal goal under
 * way, a hoglin after the player from ten blocks off; it pillars up and waits. Then the hoglin is taken away (STAGED),
 * and the spell ends.
 */
public final class NativeTreedSpellLogGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "treedSpellLog", tickLimit = 1600)
    public void treedAboveAHoglinItLogsWhereTheHoglinIsOnceASpellAndHowLongItLasted(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        String name = "Treed";
        List<String> started = new CopyOnWriteArrayList<>();
        List<String> over = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("treedSpellLog", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (line.startsWith(name + " treed spell at ") && !line.contains(" still on after ")) started.add(line);
                if (line.startsWith(name + " treed spell over at ")) over.add(line);
            }
        };
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        HoglinEntity[] hoglin = new HoglinEntity[1];
        int[] upFor = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(-6, 1, -6)), context.getAbsolutePos(new BlockPos(12, 8, 16)));
                    for (int x = -6; x <= 12; x++) for (int z = -6; z <= 16; z++) {
                        world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(3, 2, 3));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, -90, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    hoglin[0] = EntityType.HOGLIN.create(world);
                    BlockPos at = context.getAbsolutePos(new BlockPos(3, 2, 13));
                    hoglin[0].refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
                    hoglin[0].setImmuneToZombification(true); // Out of the Nether it would turn into a zoglin.
                    hoglin[0].setPersistent();
                    world.spawnEntity(hoglin[0]);
                    hoglin[0].getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET, player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(!started.isEmpty(), "A treed spell logged"))
                .createAndAdd(() -> {
                    String line = started.getFirst();
                    context.assertTrue(line.matches(name + " treed spell at -?\\d+, -?\\d+, -?\\d+ in overworld: hoglin \\d+\\.\\d away \\(\\d+\\.\\d across\\), "
                            + "-?\\d+\\.\\d below, (after it|near, not after it), (in|out of) strike reach; armed (yes|no)"), "The line's shape: " + line);
                    context.assertTrue(line.contains("armed yes"), "Its sword counted: " + line);
                    // Kept up a while: one line for the spell, however many waits.
                    context.assertTrue(++upFor[0] >= 200, "Up a while: " + upFor[0]);
                })
                .createAndAdd(() -> {
                    context.assertTrue(started.size() == 1, "Once a spell, not once a wait: " + started);
                    context.assertTrue(over.isEmpty(), "Not over while the hoglin is there: " + over);
                    hoglin[0].discard(); // STAGED: the hoglin taken away; the spell ends at the next decision.
                })
                .createAndAdd(() -> context.assertTrue(!over.isEmpty(), "The spell's end logged"))
                .createAndAdd(() -> {
                    String line = over.getFirst();
                    context.assertTrue(line.matches(name + " treed spell over at -?\\d+, -?\\d+, -?\\d+ after \\d+ ticks, \\d+ wait decision\\(s\\), \\d+ strike\\(s\\); last: hoglin .*"),
                            "The end's shape: " + line);
                    int ticks = Integer.parseInt(line.replaceAll(".* after (\\d+) ticks.*", "$1"));
                    int waits = Integer.parseInt(line.replaceAll(".* (\\d+) wait decision\\(s\\).*", "$1"));
                    context.assertTrue(ticks >= 200 && waits >= 3, "Its length and its waits: " + line);
                    KindredMod.LOGGER.info("Treed: {} | {}", started.getFirst(), line);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
