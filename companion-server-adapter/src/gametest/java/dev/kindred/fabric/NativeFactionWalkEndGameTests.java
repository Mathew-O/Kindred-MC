package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.Territory;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a faction walk to a chunk across water ends on ground the player can see along the chunk's near edge,
 * not only on the column straight ahead of it (M-06m2). Three blocks south of a causeway, the column straight ahead is
 * open sea; the walk ends on the causeway, and the player walks it. Straight ahead on the causeway, the end is as before;
 * with no land it can see in the chunk at all, as before.
 *
 * Technical: on main `c7b19178` (REFACTOR's runs, 1 in 10) `NativeFactionOpenWaterGameTests.aCausewayThroughTheSeaItSawIsStillWalked`
 * failed so: Ada three south of the island's middle, the causeway three wide, her walk's end the column straight east
 * ({@code FactionsPlayer.legEnd} clamps her own z into the chunk), no ground seen there, so her own height over the sea:
 * "the goal standable false, on air", refused twice. STAGED: the open-water test's island and sea, with or without its
 * causeway east ({@link NativeFactionOpenWaterGameTests#island}); Ada set on the island's east edge, facing east, at the
 * case's z; the factions as that test founds them, Blue's land the chunk east; her move set to scout it. Read: where her
 * walk would end, as the walk is asked ({@code factionDestinationForTest}); in the off-line case, then, that she walks it.
 */
public final class NativeFactionWalkEndGameTests implements FabricGameTest {
    private static final String W = Territory.OVERWORLD;
    /** How far south of the island's middle the off-line cases stand: off the causeway's line, as on the failing run. */
    private static final int OFF_LINE = -3;
    /** How long after its move is set it may take to get into Blue's land east, along the causeway. */
    private static final int WALK_TICKS = 1500;

    /** Three south of the causeway's line: its walk ends on the causeway it sees, and it walks it. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionWalkEndOffLine", tickLimit = 2400)
    public void offTheCausewaysLineItsWalkEndsOnTheCausewayItSees(TestContext context) {
        run(context, true, OFF_LINE);
    }

    /** The control: on the causeway's line, its walk's end straight ahead, on the causeway, as before. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionWalkEndOnLine", tickLimit = 600)
    public void onTheCausewaysLineItsWalkEndsStraightAheadAsBefore(TestContext context) {
        run(context, true, 0);
    }

    /** The control: no causeway, no land it sees in the chunk east; its walk's end as before, over the sea. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionWalkEndNoLand", tickLimit = 600)
    public void withNoLandItSeesInTheChunkItsWalkEndsAsBefore(TestContext context) {
        run(context, false, OFF_LINE);
    }

    private static void run(TestContext context, boolean causeway, int dz) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var server = context.getWorld().getServer();
        var world = context.getWorld();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        BlockPos[] isle = new BlockPos[1];
        long[] set = {-1};
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // The island's middle four blocks into its chunk from the west, so the chunk east begins eight past its edge.
                    BlockPos up = f.player().getBlockPos().up(30);
                    BlockPos o = new BlockPos((up.getX() & ~15) + 4, up.getY(), (up.getZ() & ~15) + 8);
                    NativeFactionOpenWaterGameTests.island(context, o, false, causeway);
                    isle[0] = o;
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.getInventory().clear();
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    NativeFactionOpenWaterGameTests.factions(context, f, ben, o);
                })
                .createAndAdd(() -> {
                    var o = isle[0];
                    var player = f.player();
                    // On the island's east edge at the case's z, facing east, as the walk is asked.
                    player.refreshPositionAndAngles(o.getX() + 3.5, o.getY(), o.getZ() + dz + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var east = Territory.Chunk.at(W, o.getX(), o.getZ()).offset(1, 0);
                    f.runtime.factionMoveSetForTest(f.id, new FactionPlay.Choice(FactionPlay.Kind.SCOUT, east.key(), 0, "the fixture's scout of Blue's land east"));
                    BlockPos end = f.runtime.factionDestinationForTest(f.id);
                    context.assertTrue(end != null, "A walk's end east");
                    boolean ground = standable(world, end);
                    KindredMod.LOGGER.info("Walk-end fixture ({}, {} off the line): from {}, its walk's end {} ({} from the island's middle), ground there {}",
                            causeway ? "causeway" : "no land", dz, player.getBlockPos().toShortString(), end.toShortString(),
                            end.subtract(o).toShortString(), ground);
                    context.assertTrue(Territory.Chunk.at(W, end.getX(), end.getZ()).equals(east), "Its walk's end in Blue's chunk east: " + end.toShortString());
                    if (!causeway) {
                        context.assertTrue(!ground && end.getZ() == o.getZ() + dz && end.getY() == player.getBlockY(),
                                "No land it sees there: its walk's end as before, straight ahead at its own height: " + end.subtract(o).toShortString());
                    } else if (dz == 0) {
                        context.assertTrue(ground && end.getZ() == o.getZ(), "On the causeway's line: its walk's end straight ahead, on it: " + end.subtract(o).toShortString());
                    } else {
                        context.assertTrue(ground && Math.abs(end.getZ() - o.getZ()) <= 1,
                                "Off the causeway's line: its walk's end on the causeway it sees, not over the sea: " + end.subtract(o).toShortString()
                                        + ", ground there " + ground);
                    }
                    set[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    if (!causeway || dz == 0) return;
                    // It walks it: into Blue's land east, along the causeway, within the bound.
                    var player = f.player();
                    var east = Territory.Chunk.at(W, isle[0].getX(), isle[0].getZ()).offset(1, 0);
                    boolean there = Territory.Chunk.at(W, player.getBlockX(), player.getBlockZ()).equals(east);
                    long since = server.getTicks() - set[0];
                    if (!there) {
                        context.assertTrue(since <= WALK_TICKS, "Into Blue's land east within " + WALK_TICKS + " ticks: at "
                                + player.getBlockPos().subtract(isle[0]).toShortString() + " from the island's middle; walks ended " + f.runtime.factionWalksEndedForTest(f.id));
                        throw new net.minecraft.test.GameTestException("Not there yet");
                    }
                    KindredMod.LOGGER.info("Walk-end fixture: in Blue's land east {} ticks after its move was set, at {} from the island's middle",
                            since, player.getBlockPos().subtract(isle[0]).toShortString());
                })
                .completeIfSuccessful();
    }

    /** Solid ground under it and room for a player, out of any fluid: as {@code FactionsPlayer.standable} asks. */
    private static boolean standable(net.minecraft.server.world.ServerWorld world, BlockPos feet) {
        return world.getBlockState(feet.down()).isSideSolidFullSquare(world, feet.down(), net.minecraft.util.math.Direction.UP)
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                && world.getFluidState(feet).isEmpty();
    }
}
