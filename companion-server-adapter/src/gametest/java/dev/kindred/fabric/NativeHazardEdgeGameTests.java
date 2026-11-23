package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * In plain terms: an AI player standing on the corner of a block over the Nether's lava, its middle over the lava but
 * its feet well clear of it, gets off the edge instead of standing there for good with every task called off; a way off
 * beside it is still the one taken; and it is never sent through a gap between two blocks it cannot pass.
 *
 * Technical: F-25c6d. E-40c4's Nether freezes (37 to 130 game minutes, a third of the batch's Nether time) took no
 * harm and no meal, so the body was never in lava, fire or magma, and a stale observation's creeper is ruled out
 * ({@link NativeTacticalRefreshGameTests}). The hazard test reads the block under the player's middle and stays as
 * sensitive as it is (a middle over the lava sea is one careless step from death); the retreat's other half is fixed:
 * the corners' cells, the one holding it up among them, are offered when no side's is. All STAGED in the Nether, a
 * closed netherrack room, the player set by a fixture teleport, a goal running so a task is in hand.
 */
public final class NativeHazardEdgeGameTests implements FabricGameTest {

    /** What a test stages: the fixture, the Nether, the player's feet cell, its hazard spell lines, and the runner to add to. */
    private record Stage(NativePaidSkillsGameTests.Fixture f, ServerWorld nether, BlockPos feet, List<String> lines, TimedTaskRunner runner) { }

    /**
     * A closed netherrack room in the Nether round its feet cell; {@code ground} lays the cells about the feet; the
     * player set at the feet cell plus ({@code ox}, {@code oz}), and a goal begun.
     */
    private static Stage stage(TestContext context, String name, BiConsumer<ServerWorld, BlockPos> ground, double ox, double oz) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1300);
        List<String> lines = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("hazardEdge-" + name, null, null, true, Property.EMPTY_ARRAY) {
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
        BlockPos feet = new BlockPos(above.getX(), 70, above.getZ());
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, nether, feet.add(-6, -3, -6), feet.add(6, 4, 6));
                    for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -3; dy <= 4; dy++) {
                        boolean shell = Math.abs(dx) == 6 || Math.abs(dz) == 6 || dy <= -1 || dy == 4;
                        nether.setBlockState(feet.add(dx, dy, dz), shell ? Blocks.NETHERRACK.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    ground.accept(nether, feet);
                    int cx = feet.getX() >> 4, cz = feet.getZ() >> 4;
                    nether.setChunkForced(cx, cz, true);
                    FixtureGround.whenDone(context, () -> nether.setChunkForced(cx, cz, false));
                    var body = f.runtime.nativeBody(f.id).orElseThrow();
                    body.setPortalTravelAuthorized(true);
                    nether.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.PORTAL, new net.minecraft.util.math.ChunkPos(feet), 3, feet);
                    context.assertTrue(body.entity().teleport(nether, feet.getX() + ox, feet.getY(), feet.getZ() + oz, java.util.Set.of(), 0, 0),
                            "STAGED into the Nether");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getServerWorld() == nether && f.player().isOnGround(), "Standing in the Nether"))
                .createAndAdd(() -> {
                    f.runtime.syncNativeDimensionAfterTransit(f.id);
                    var player = f.player();
                    context.assertTrue(player.getBlockPos().equals(feet), "Its middle over the staged cell: " + player.getBlockPos().toShortString());
                    context.assertTrue(!player.isInLava(), "Clear of the lava");
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.NETHERRACK, 32));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                });
        return new Stage(f, nether, feet, lines, runner);
    }

    /** One below the feet, a three by three of lava but for the cells given, netherrack. */
    private static BiConsumer<ServerWorld, BlockPos> lavaBut(int[]... solid) {
        return (nether, feet) -> {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) nether.setBlockState(feet.add(dx, -1, dz), Blocks.LAVA.getDefaultState());
            for (int[] c : solid) nether.setBlockState(feet.add(c[0], -1, c[1]), Blocks.NETHERRACK.getDefaultState());
        };
    }

    private static void finish(TestContext context, Stage s) {
        s.runner()
                .createAndAdd(() -> s.f().runtime.stop())
                .createAndAdd(() -> context.assertTrue(s.f().runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /**
     * Its box on the south-east corner's block, its middle over the lava, all four sides' floors lava. On main every
     * task is cut and nothing begins: the hazard spell's line says "saw lava under it (0, -1, 0)" and each side "floor
     * lava not a full top", then `candidates=0`. With the corners offered it steps onto the corner block's cell, the
     * block holding it up, within a few ticks: on solid ground, never in the lava, and any spell it began is over.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hazardEdge", tickLimit = 1400)
    public void onABlocksCornerOverTheLavaItGetsOffTheEdgeRatherThanFreeze(TestContext context) {
        var s = stage(context, "Edgewise", lavaBut(new int[] {1, 1}), 0.75, 0.75);
        BlockPos corner = s.feet().add(1, 0, 1);
        int[] watched = {0};
        boolean[] lava = {false};
        s.runner()
                .createAndAdd(() -> {
                    var player = s.f().player();
                    lava[0] |= player.isInLava();
                    s.f().player().setHealth(20);
                    boolean onIt = player.getBlockPos().equals(corner) && player.isOnGround();
                    context.assertTrue(onIt || ++watched[0] >= 200, "Watching for the corner's cell: " + watched[0] + " ticks; " + s.lines());
                })
                .createAndAdd(() -> {
                    var player = s.f().player();
                    context.assertTrue(player.getBlockPos().equals(corner) && player.isOnGround(),
                            "On the corner block's cell within 200 ticks, not frozen at " + player.getBlockPos().toShortString() + ": " + s.lines());
                    long begun = s.lines().stream().filter(l -> l.contains(" hazard spell at ")).count();
                    long over = s.lines().stream().filter(l -> l.contains(" hazard spell over at ")).count();
                    context.assertTrue(begun == over, "Any spell begun is over: " + s.lines());
                    context.assertTrue(!lava[0], "Never into the lava");
                });
        finish(context, s);
    }

    /**
     * As above, but the east side's floor is netherrack too: the four sides' retreat, as before, takes the east cell, not
     * the corner's.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hazardEdgeSide", tickLimit = 1400)
    public void withASidesCellToStepToTheSidesRetreatIsStillTheOneTaken(TestContext context) {
        var s = stage(context, "Sideways", lavaBut(new int[] {1, 1}, new int[] {1, 0}), 0.75, 0.75);
        BlockPos east = s.feet().east();
        BlockPos[] first = {null};
        int[] watched = {0};
        s.runner()
                .createAndAdd(() -> {
                    var player = s.f().player();
                    s.f().player().setHealth(20);
                    if (first[0] == null && !player.getBlockPos().equals(s.feet())) first[0] = player.getBlockPos().toImmutable();
                    context.assertTrue(first[0] != null || ++watched[0] >= 200, "Watching for its first cell off the edge: " + watched[0]);
                })
                .createAndAdd(() -> context.assertTrue(east.equals(first[0]),
                        "The east side's cell first, as the four sides' retreat takes it: " + (first[0] == null ? "none" : first[0].toShortString()) + "; " + s.lines()));
        finish(context, s);
    }

    /**
     * A corner's cell free but walled off: the player on magma (the hazard, sneaking or not, its health kept up), its
     * four sides walled two high, the south-east corner's cell open with a netherrack floor, so the only way there is
     * between the east and south walls, which a body cannot pass. It is not sent through: the line says "southeast no
     * way past the corner", and it is never in that cell.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hazardEdgeGap", tickLimit = 1400)
    public void aCornersCellBetweenTwoWallsIsNeverSteppedToThroughTheGap(TestContext context) {
        BiConsumer<ServerWorld, BlockPos> ground = (nether, feet) -> {
            nether.setBlockState(feet.down(), Blocks.MAGMA_BLOCK.getDefaultState());
            for (int[] c : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {-1, -1}, {1, -1}, {-1, 1}}) {
                nether.setBlockState(feet.add(c[0], 0, c[1]), Blocks.NETHERRACK.getDefaultState());
                nether.setBlockState(feet.add(c[0], 1, c[1]), Blocks.NETHERRACK.getDefaultState());
            }
        };
        var s = stage(context, "Gapped", ground, 0.5, 0.5);
        BlockPos corner = s.feet().add(1, 0, 1);
        int[] watched = {0};
        boolean[] through = {false};
        s.runner()
                .createAndAdd(() -> {
                    var player = s.f().player();
                    s.f().player().setHealth(20);
                    through[0] |= player.getBlockPos().equals(corner);
                    context.assertTrue(++watched[0] >= 200, "Watching: " + watched[0] + " ticks");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!through[0], "Never through the gap into the corner's cell");
                    context.assertTrue(s.lines().stream().anyMatch(l -> l.contains("southeast no way past the corner")),
                            "The corner refused for want of a way past: " + s.lines());
                });
        finish(context, s);
    }
}
