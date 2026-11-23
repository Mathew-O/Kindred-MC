package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In plain terms: an AI player standing on the corner of a block over the Nether's lava, its middle over the lava but
 * its feet well clear of it, gets off the edge instead of standing there for good with every task called off.
 *
 * Technical: F-25c6d. E-40c4's Nether freezes (37 to 130 game minutes, a third of the batch's Nether time) took no
 * harm and no meal, so the body was never in lava, fire or magma, and a stale observation's creeper is ruled out
 * ({@link NativeTacticalRefreshGameTests}). The hazard test reads the block under the player's middle; vanilla's own
 * damage reads the block holding it up. STAGED in the Nether, a closed netherrack room: one block below its feet a three
 * by three of lava sources, but for one corner cell of netherrack; the player set so its box rests on that corner (its
 * middle over the lava, the lava's top below its feet). All four cells beside it, at its level, have lava for a floor.
 * A goal runs, so a task is in hand.
 */
public final class NativeHazardEdgeGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hazardEdge", tickLimit = 1400)
    public void onABlocksCornerOverTheLavaItGetsOffTheEdgeRatherThanFreeze(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1300);
        String name = "Edgewise";
        List<String> lines = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("hazardEdge", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (line.startsWith(name + " hazard spell")) lines.add(line);
            }
        };
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        ServerWorld nether = context.getWorld().getServer().getWorld(World.NETHER);
        BlockPos above = context.getAbsolutePos(new BlockPos(3, 3, 3));
        // The player's feet cell; the pool's level is the one below it.
        BlockPos feet = new BlockPos(above.getX(), 70, above.getZ());
        int[] watched = {0};
        int[] off = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, nether, feet.add(-6, -3, -6), feet.add(6, 4, 6));
                    for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -3; dy <= 4; dy++) {
                        boolean shell = Math.abs(dx) == 6 || Math.abs(dz) == 6 || dy <= -2 || dy == 4;
                        // The room's floor at the pool's level is netherrack, the pool cut into it.
                        boolean floor = dy == -1;
                        nether.setBlockState(feet.add(dx, dy, dz), shell || floor ? Blocks.NETHERRACK.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        boolean corner = dx == 1 && dz == 1;
                        nether.setBlockState(feet.add(dx, -1, dz), corner ? Blocks.NETHERRACK.getDefaultState() : Blocks.LAVA.getDefaultState());
                    }
                    int cx = feet.getX() >> 4, cz = feet.getZ() >> 4;
                    nether.setChunkForced(cx, cz, true);
                    FixtureGround.whenDone(context, () -> nether.setChunkForced(cx, cz, false));
                    var body = f.runtime.nativeBody(f.id).orElseThrow();
                    body.setPortalTravelAuthorized(true);
                    nether.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.PORTAL, new net.minecraft.util.math.ChunkPos(feet), 3, feet);
                    // STAGED: on the corner block's edge, its middle over the pool's middle.
                    context.assertTrue(body.entity().teleport(nether, feet.getX() + 0.75, feet.getY(), feet.getZ() + 0.75, java.util.Set.of(), 0, 0),
                            "STAGED onto the corner's edge in the Nether");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getServerWorld() == nether && f.player().isOnGround(), "Standing in the Nether"))
                .createAndAdd(() -> {
                    f.runtime.syncNativeDimensionAfterTransit(f.id);
                    var player = f.player();
                    context.assertTrue(player.getBlockPos().equals(feet), "Its middle over the pool's middle: " + player.getBlockPos().toShortString());
                    context.assertTrue(!player.isInLava() && !player.isOnFire(), "Clear of the lava, not burning");
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.NETHERRACK, 32));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    // Off the edge: no longer with its middle over the pool, on its own feet, and not in the lava.
                    if (!player.getBlockPos().equals(feet) && player.isOnGround() && !player.isInLava()) off[0]++;
                    context.assertTrue(!player.isInLava(), "Never into the lava");
                    context.assertTrue(++watched[0] >= 600 || off[0] >= 20, "Watching: " + watched[0] + " ticks, " + off[0] + " off the edge; " + lines);
                })
                .createAndAdd(() -> {
                    // On main the hazard interrupt's line says what froze it ("saw lava under it (0, -1, 0)", the four sides
                    // "floor lava not a full top"); with the corner's cell offered, the retreat is taken before any task is
                    // cut, so no spell begins. Either way, the lines are in the failure's words.
                    context.assertTrue(off[0] >= 20, "Off the edge within 600 ticks (" + off[0] + " ticks off), not frozen: " + lines);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }
}
