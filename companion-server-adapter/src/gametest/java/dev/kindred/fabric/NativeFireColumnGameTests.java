package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
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
 * In plain terms: an AI player standing in fire on top of a one-block-wide column, with nothing beside it to step onto,
 * gets out of the fire rather than standing in it with every task called off.
 *
 * Technical: F-25c6f. e40c5's 31102026 Echo stood 423 ticks in "fire at its feet (0, 0, 0); feet fire, floor
 * nether_wart_block", all four side cells "floor air not a full top", with 140 tasks cancelled for an urgent retreat
 * and none run; unhurtable then (F-25b5), it took no harm. STAGED in the Overworld, so no cross-world teleport puts the
 * body in vanilla's teleportation state (unhurtable on main): a column of nether_wart_block three high on stone, air on
 * all sides and corners down to the stone, the player on its top in a fire, alight, a goal running so a task is in hand.
 * A drop of three from the column's top costs nothing (vanilla's fall damage starts past three blocks).
 */
public final class NativeFireColumnGameTests implements FabricGameTest {
    /** How long it may stand in the fire: about what a player takes to see it and step off. */
    private static final int FIRE_BOUND = 100;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "fireColumn", tickLimit = 1200)
    public void onFireAtopAOneWideColumnWithNoCellBesideItItGetsOutOfTheFire(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        String name = "Kindling";
        List<String> spells = new CopyOnWriteArrayList<>();
        List<String> cancels = new CopyOnWriteArrayList<>();
        List<String> retreats = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("fireColumn", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (!line.startsWith(name + " ")) return;
                if (line.startsWith(name + " hazard spell")) spells.add(line);
                if (line.contains("urgent retreat required")) cancels.add(line);
                if (line.contains("native_retreat")) retreats.add(line);
            }
        };
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        BlockPos[] top = new BlockPos[1];
        int[] ticks = {0}, inFire = {0}, lastInFire = {-1};
        float[] lowest = {20};
        boolean[] died = {false}, logged = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(-3, 1, -3)), context.getAbsolutePos(new BlockPos(9, 9, 9)));
                    for (int x = -3; x <= 9; x++) for (int z = -3; z <= 9; z++) {
                        world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 9; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
                    }
                    for (int y = 2; y <= 4; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(3, y, 3)), Blocks.NETHER_WART_BLOCK.getDefaultState());
                    top[0] = context.getAbsolutePos(new BlockPos(3, 5, 3));
                    var player = f.player();
                    player.refreshPositionAndAngles(top[0].getX() + 0.5, top[0].getY(), top[0].getZ() + 0.5, 0, 0);
                    world.setBlockState(top[0], Blocks.FIRE.getDefaultState());
                    player.setFireTicks(160);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    // Watched each tick for 600: in the fire's cell with the fire there, its health, alive.
                    var player = f.player();
                    var world = context.getWorld();
                    boolean burning = player.getBlockPos().equals(top[0]) && world.getBlockState(top[0]).isOf(Blocks.FIRE);
                    if (burning) { inFire[0]++; lastInFire[0] = ticks[0]; }
                    lowest[0] = Math.min(lowest[0], player.getHealth());
                    died[0] |= !player.isAlive();
                    context.assertTrue(++ticks[0] >= 600 || died[0], "Watched 600 ticks: " + ticks[0]);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    if (!logged[0]) {
                        logged[0] = true;
                        KindredMod.LOGGER.info("Kindling: died {}, alive {}, health {} (lowest {}), {} ticks in the fire (the last at {}), at {}, fire there {}; "
                                    + "{} spell line(s), {} urgent cancels, {} retreat line(s); first spell: {}",
                            died[0], player.isAlive(), player.getHealth(), lowest[0], inFire[0], lastInFire[0], player.getBlockPos().toShortString(),
                            context.getWorld().getBlockState(top[0]).isOf(Blocks.FIRE), spells.size(), cancels.size(), retreats.size(),
                            spells.isEmpty() ? "none" : spells.getFirst());
                    }
                    context.assertTrue(!died[0], "Never killed: " + inFire[0] + " ticks in the fire, lowest health " + lowest[0]);
                    context.assertTrue(inFire[0] <= FIRE_BOUND, "Out of the fire within " + FIRE_BOUND + " ticks: " + inFire[0] + " ticks in it, "
                            + cancels.size() + " urgent cancels, " + retreats.size() + " retreat line(s); " + (spells.isEmpty() ? "no spell" : spells.getFirst()));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
