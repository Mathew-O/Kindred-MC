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
 * In plain terms: when an AI player's task is cut short because it stands somewhere dangerous, the log says what was
 * dangerous, what it stood on, and why each way off was turned down, once each time it happens.
 *
 * Technical: F-25c6d's diagnostic, log only. E-40c4's Nether players froze 37 to 130 game minutes with every task
 * cancelled ("urgent retreat required") and no retreat offered, and the console did not say which block or trigger.
 * Staged: a magma block in a shaft one block wide, stone on its four sides, so the hazard retreat has no cell; a goal
 * running so a task is in hand.
 */
public final class NativeHazardSpellLogGameTests implements FabricGameTest {
    private static final int MID = 8;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hazardSpellLog", tickLimit = 900)
    public void aTaskCancelledForAHazardLogsWhatItSawAndEachWayOffRefusedOnceASpell(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        String name = "Singed";
        List<String> lines = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("hazardSpellLog", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (line.startsWith(name + " hazard spell at ")) lines.add(line);
            }
        };
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        BlockPos[] at = new BlockPos[1];
        int[] after = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(MID - 6, 0, MID - 6)), context.getAbsolutePos(new BlockPos(MID + 6, 8, MID + 6)));
                    for (int x = MID - 6; x <= MID + 6; x++) for (int z = MID - 6; z <= MID + 6; z++) {
                        context.setBlockState(x, 0, z, Blocks.STONE);
                        context.setBlockState(x, 1, z, Blocks.STONE);
                        for (int y = 2; y <= 8; y++) context.setBlockState(x, y, z, Blocks.AIR);
                    }
                    f.runtime.createNative(f.source, name);
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // The shaft: magma at its foot, stone two high on its four sides, open above.
                    context.setBlockState(MID, 1, MID, Blocks.MAGMA_BLOCK);
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        context.setBlockState(MID + d[0], 2, MID + d[1], Blocks.STONE);
                        context.setBlockState(MID + d[0], 3, MID + d[1], Blocks.STONE);
                    }
                    at[0] = context.getAbsolutePos(new BlockPos(MID, 2, MID));
                    f.player().refreshPositionAndAngles(at[0].getX() + 0.5, at[0].getY(), at[0].getZ() + 0.5, 0, 0);
                    f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    f.player().setHealth(20);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(!lines.isEmpty(), "A hazard spell logged"))
                .createAndAdd(() -> {
                    // A while on in the same spell: still one line.
                    f.player().setHealth(20);
                    context.assertTrue(++after[0] >= 60, "The spell going on");
                })
                .createAndAdd(() -> {
                    String line = lines.getFirst();
                    context.assertTrue(line.contains("at " + at[0].toShortString() + " in overworld"), "Its place: " + line);
                    context.assertTrue(line.contains("trigger hazard"), "The trigger: " + line);
                    context.assertTrue(line.contains("saw magma_block under it (0, -1, 0)"), "What the hazard test saw: " + line);
                    context.assertTrue(line.contains("floor magma_block"), "The floor: " + line);
                    for (String dir : new String[] {"north", "south", "east", "west"}) {
                        context.assertTrue(line.contains(dir + " no room (stone, stone above)"), "The " + dir + " cell refused for want of room: " + line);
                    }
                    context.assertTrue(line.contains("lava escape: not in lava"), "No lava escape: " + line);
                    context.assertTrue(lines.size() == 1 || lines.stream().skip(1).allMatch(l -> !l.contains("at " + at[0].toShortString())),
                            "Once a spell, not once a cancel: " + lines.size() + " lines at its spot");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }
}
