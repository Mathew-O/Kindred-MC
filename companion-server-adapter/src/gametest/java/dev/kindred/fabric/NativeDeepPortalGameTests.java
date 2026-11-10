package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * In plain terms: a GameTest of a deep portal (MOVEMENT): the team's portal was cast down at the lava's level, a
 * hundred down and about a hundred and thirty across, and the player stands on the surface above. The only way down is
 * the stair its caster dug, and the tunnel on from its foot. The player walks there and down the stair, a leg at a time, and goes through, rather than asking again
 * and again for one walk all the way that is refused.
 *
 * Technical: e40c2-d640518 (#122): on 4 of 10 seeds the team's portal was lit and no one walked into it. 707's "Walk into
 * the portal" was refused 555 times ("Goto target is too far for one route"); 8675309, 606 and 20261030 had "No walkable
 * route" toward portals cast near Y -50 with the players on the surface. Staged: a sealed block of stone raised clear of
 * the world's floor, the chamber and the frame lit in it, a one-wide stair from the surface down to it, the portal handed
 * over as the player's own (as a cast or a teammate's adopted one is), and the kit the End goal's crossing fixture gives.
 */
public final class NativeDeepPortalGameTests implements FabricGameTest {

    /**
     * A hundred down and a hundred and thirty across, by the stair and the tunnel alone, farther than one route goes:
     * walked to and through, into the Nether.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deepPortal", tickLimit = 6000)
    public void aLitPortalFarOffAndDeepIsWalkedToByItsStairAndEntered(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] origin = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Delver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // Raised clear of the world's floor, so fifty down is still above it.
                    BlockPos o = origin[0] = f.player().getBlockPos().up(105).toImmutable();
                    int floor = o.getY() - 100; // the stair's foot, the tunnel and the chamber
                    for (int x = -3; x <= 136; x++) for (int z = -3; z <= 3; z++) {
                        for (int y = floor - 1; y <= o.getY() - 1; y++) world.setBlockState(new BlockPos(o.getX() + x, y, o.getZ() + z), Blocks.STONE.getDefaultState());
                        for (int y = o.getY(); y <= o.getY() + 3; y++) world.setBlockState(new BlockPos(o.getX() + x, y, o.getZ() + z), Blocks.AIR.getDefaultState());
                    }
                    // The caster's stair: one wide, a step down for each step east, from x 5 to 104.
                    for (int i = 0; i < 100; i++) {
                        int feet = o.getY() - 1 - i;
                        for (int y = feet; y <= feet + 2; y++) world.setBlockState(o.add(5 + i, y - o.getY(), 0), Blocks.AIR.getDefaultState());
                    }
                    // The tunnel on from the stair's foot, two high, then the chamber and the frame across it at x 130, lit.
                    for (int x = 105; x <= 124; x++) for (int y = floor; y <= floor + 1; y++) world.setBlockState(new BlockPos(o.getX() + x, y, o.getZ()), Blocks.AIR.getDefaultState());
                    for (int x = 125; x <= 132; x++) for (int z = -2; z <= 2; z++) for (int y = floor; y <= floor + 5; y++) {
                        world.setBlockState(new BlockPos(o.getX() + x, y, o.getZ() + z), Blocks.AIR.getDefaultState());
                    }
                    BlockPos frame = new BlockPos(o.getX() + 130, floor, o.getZ() - 2);
                    for (int a = 0; a <= 3; a++) {
                        world.setBlockState(frame.south(a), Blocks.OBSIDIAN.getDefaultState());
                        world.setBlockState(frame.south(a).up(4), Blocks.OBSIDIAN.getDefaultState());
                    }
                    for (int up = 1; up <= 3; up++) {
                        world.setBlockState(frame.up(up), Blocks.OBSIDIAN.getDefaultState());
                        world.setBlockState(frame.south(3).up(up), Blocks.OBSIDIAN.getDefaultState());
                    }
                    var portal = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, Direction.Axis.Z);
                    for (int a = 1; a <= 2; a++) for (int up = 1; up <= 3; up++) {
                        world.setBlockState(frame.south(a).up(up), portal, net.minecraft.block.Block.FORCE_STATE | net.minecraft.block.Block.NOTIFY_LISTENERS);
                    }
                    var site = new PortalRunCampaign.PortalSite(frame, Direction.SOUTH, Direction.EAST, frame.south(1).west(1));
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    player.getInventory().clear();
                    NativeStrongholdGameTests.stockedForTheNether(player);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    f.runtime.campaignForTest(f.id).portalSiteForTest(site);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(origin[0]), "On the surface"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player.getServerWorld().getRegistryKey() == World.NETHER,
                            "Through the deep portal to the Nether: at " + player.getBlockPos().subtract(origin[0]).toShortString() + " - "
                                    + f.runtime.panelBots(Actor.console()).getFirst().goalProgress() + "; "
                                    + f.runtime.nativeActivityStatus(Actor.console(), f.id).work());
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
