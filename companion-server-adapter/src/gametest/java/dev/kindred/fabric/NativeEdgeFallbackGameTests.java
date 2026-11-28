package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In plain terms: a badly hurt AI player in a cave passage with a skeleton shooting at it still backs off from the
 * skeleton when nowhere nearby is well away from a drop; it does not stand still because no spot is perfect.
 *
 * Technical: F-25c6h's fallback. In e40a16 922f4fd 707 Kindred 14534 (3 of 3 on 81f63bfd, 0 of 3 on main), a back-off
 * asked to end clear of edges within 1.6 found no such stand within its search in a cave, ended "No route" and
 * completed without moving, again and again, and a skeleton shot the player dead. STAGED: a roofed passage one wide,
 * east to west, a stone wall along its south side and beyond the wall a pit eight deep the whole passage long, so every
 * stand in it is safe (E-49) and none is clear of edges; the player at its middle, five health, an iron chestplate, an
 * iron sword and bread, no blocks; a skeleton in the passage eight blocks east, after it; night, NORMAL difficulty.
 */
public final class NativeEdgeFallbackGameTests implements FabricGameTest {
    private static final int MID = 30;
    /** How far along the passage a back-off must take it, counted only while a back-off is its step (a blow or a fight moves it too). */
    private static final double AWAY = 3;
    private static final int WATCH = 600;

    @GameTest(templateName = FixtureGround.WIDE, batchId = "edgeFallback", tickLimit = 900)
    public void hurtInACaveWithNoStandClearOfEdgesItStillBacksOffFromASkeleton(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        String name = "Ledged";
        List<String> fallbacks = new CopyOnWriteArrayList<>(), noRoutes = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("edgeFallback", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (line.startsWith(name + " walk toward ") && line.contains("ending on a safe stand")) fallbacks.add(line);
                if (line.startsWith("No route from ")) noRoutes.add(line);
            }
        };
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        long[] since = {-1};
        int[] backedOffAt = {-1};
        double[] startX = {0}, furthest = {0}, stepFrom = {Double.NaN};
        boolean[] died = {false}, logged = {false};
        StringBuilder steps = new StringBuilder();
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(MID - 16, 0, MID - 3)), context.getAbsolutePos(new BlockPos(MID + 12, 10, MID + 4)));
                    for (int x = MID - 16; x <= MID + 12; x++) for (int z = MID - 3; z <= MID + 4; z++) for (int y = 0; y <= 10; y++)
                        context.setBlockState(x, y, z, Blocks.STONE);
                    // The passage: floor at 6, feet at 7, roof at 9.
                    for (int x = MID - 14; x <= MID + 10; x++) for (int y = 7; y <= 8; y++) context.setBlockState(x, y, MID, Blocks.AIR);
                    // Beyond the wall at MID + 1, a pit eight deep along it: two cells off every stand, deeper than a safe drop.
                    for (int x = MID - 14; x <= MID + 10; x++) for (int y = 1; y <= 8; y++) context.setBlockState(x, y, MID + 2, Blocks.AIR);
                    f.runtime.createNative(f.source, name);
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var at = context.getAbsolutePos(new BlockPos(MID, 7, MID));
                    f.player().refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 90, 0);
                    f.player().setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround()
                        && f.player().getBlockPos().equals(context.getAbsolutePos(new BlockPos(MID, 7, MID))), "Standing in the passage"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 4));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    player.setHealth(5);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    context.getWorld().setTimeOfDay(14000);
                    startX[0] = player.getX();
                    var s = context.spawnEntity(net.minecraft.entity.EntityType.SKELETON, new Vec3d(MID + 8.5, 7, MID + 0.5));
                    s.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                    s.setPersistent();
                    s.setTarget(player);
                    since[0] = context.getTick();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.getWorld().setTimeOfDay(14000);
                    int now = (int) (context.getTick() - since[0]);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!progress.equals(last[0])) {
                        last[0] = progress;
                        steps.append(" @").append(now).append(' ').append(progress)
                                .append(String.format(java.util.Locale.ROOT, " (x %+.1f)", player.getX() - startX[0])).append(';');
                    }
                    boolean backingOff = progress.contains("Back off from skeleton");
                    if (backingOff && backedOffAt[0] < 0) backedOffAt[0] = now;
                    if (!backingOff) stepFrom[0] = Double.NaN;
                    else if (Double.isNaN(stepFrom[0])) stepFrom[0] = player.getX();
                    else furthest[0] = Math.max(furthest[0], Math.abs(player.getX() - stepFrom[0]));
                    died[0] |= !player.isAlive();
                    context.assertTrue(now >= WATCH || died[0], "Watched " + WATCH + " ticks: " + now);
                })
                .createAndAdd(() -> {
                    if (!logged[0]) {
                        logged[0] = true;
                        KindredMod.LOGGER.info("Ledged: first back-off at {}, at most {} blocks along the passage in a back-off step; died {}; {} fallback line(s), "
                                + "{} no-route line(s); its steps:{}", backedOffAt[0], String.format(java.util.Locale.ROOT, "%.1f", furthest[0]),
                                died[0], fallbacks.size(), noRoutes.size(), steps);
                    }
                    context.assertTrue(backedOffAt[0] >= 0, "A back-off from the skeleton tried; its steps:" + steps);
                    context.assertTrue(furthest[0] >= AWAY, "A back-off took it " + AWAY + " blocks along the passage: "
                            + String.format(java.util.Locale.ROOT, "%.1f", furthest[0]) + "; " + noRoutes.size() + " no-route line(s); its steps:" + steps);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
