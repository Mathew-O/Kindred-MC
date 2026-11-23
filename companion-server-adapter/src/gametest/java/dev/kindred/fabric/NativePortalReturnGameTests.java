package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * In plain terms: an AI player goes into the Nether through a portal and comes back out through the portal over there,
 * as a person does; standing in the portal it has just come through does not send it straight back.
 *
 * Technical: F-25b5. After a portal crossing vanilla holds a server player in {@code inTeleportationState} until its
 * client accepts the teleport, and {@code ServerPlayerEntity.tickPortalCooldown} counts the portal cooldown down only
 * outside it. A native body has no client and accepted a teleport only at its join, so after its first crossing its
 * cooldown never ran down and it could never go through a portal again (found by F-25c6e's fixture). STAGED: both ends'
 * frames laid and lit at Y=100, as {@code NativeNetherGameTests}' restart fixture lays them, so the only portals near
 * either scaled point are these; the walks are the body's own paid steps into and out of each portal.
 */
public final class NativePortalReturnGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "portalReturn", tickLimit = 2400)
    public void throughToTheNetherAndBackOutThroughThePortalThere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        ServerWorld overworld = context.getWorld();
        ServerWorld nether = overworld.getServer().getWorld(World.NETHER);
        int lift = 100 - context.getAbsolutePos(new BlockPos(3, 3, 3)).getY();
        java.util.function.Function<BlockPos, BlockPos> at = local -> context.getAbsolutePos(local).up(lift);
        BlockPos interior = at.apply(new BlockPos(3, 3, 3));
        BlockPos netherPortal = new BlockPos(Math.floorDiv(interior.getX(), 8), interior.getY(), Math.floorDiv(interior.getZ(), 8));
        int[] waited = {0};
        boolean[] cooledBeforeLeaving = {false};
        boolean[] backTooSoon = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Roundtrip"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // The Overworld end: a floor at the sill's level, a frame along X in the Z=3 plane, lit.
                    FixtureGround.keep(context, at.apply(new BlockPos(0, 1, 0)), at.apply(new BlockPos(7, 7, 7)));
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        overworld.setBlockState(at.apply(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 7; y++) overworld.setBlockState(at.apply(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
                        overworld.setBlockState(at.apply(new BlockPos(x, 2, z)), Blocks.STONE.getDefaultState());
                    }
                    for (int x : new int[] {3, 4}) {
                        overworld.setBlockState(at.apply(new BlockPos(x, 2, 3)), Blocks.OBSIDIAN.getDefaultState());
                        overworld.setBlockState(at.apply(new BlockPos(x, 6, 3)), Blocks.OBSIDIAN.getDefaultState());
                    }
                    for (int y = 3; y <= 5; y++) {
                        overworld.setBlockState(at.apply(new BlockPos(2, y, 3)), Blocks.OBSIDIAN.getDefaultState());
                        overworld.setBlockState(at.apply(new BlockPos(5, y, 3)), Blocks.OBSIDIAN.getDefaultState());
                    }
                    net.minecraft.world.dimension.NetherPortal.getNewPortal(overworld, interior, net.minecraft.util.math.Direction.Axis.X)
                            .ifPresent(net.minecraft.world.dimension.NetherPortal::createPortal);
                    context.assertTrue(overworld.getBlockState(interior).isOf(Blocks.NETHER_PORTAL), "The Overworld end lit");
                    // The Nether end: a closed netherrack room with its own frame lit at the scaled point.
                    FixtureGround.keep(context, nether, netherPortal.add(-4, -2, -4), netherPortal.add(5, 4, 4));
                    for (int dx = -4; dx <= 5; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -2; dy <= 4; dy++) {
                        boolean shell = dx == -4 || dx == 5 || Math.abs(dz) == 4 || dy == -2 || dy == 4 || dy == -1;
                        nether.setBlockState(netherPortal.add(dx, dy, dz), (shell ? Blocks.NETHERRACK : Blocks.AIR).getDefaultState());
                    }
                    for (int dx = -1; dx <= 2; dx++) for (int dy = -1; dy <= 3; dy++) {
                        if (dx == -1 || dx == 2 || dy == -1 || dy == 3) nether.setBlockState(netherPortal.add(dx, dy, 0), Blocks.OBSIDIAN.getDefaultState());
                    }
                    net.minecraft.world.dimension.NetherPortal.getNewPortal(nether, netherPortal, net.minecraft.util.math.Direction.Axis.X)
                            .ifPresent(net.minecraft.world.dimension.NetherPortal::createPortal);
                    context.assertTrue(nether.getBlockState(netherPortal).isOf(Blocks.NETHER_PORTAL), "The Nether end lit");
                    for (int cx = (netherPortal.getX() - 4) >> 4; cx <= (netherPortal.getX() + 5) >> 4; cx++) {
                        for (int cz = (netherPortal.getZ() - 4) >> 4; cz <= (netherPortal.getZ() + 4) >> 4; cz++) {
                            int x = cx, z = cz;
                            nether.setChunkForced(x, z, true);
                            FixtureGround.whenDone(context, () -> nether.setChunkForced(x, z, false));
                        }
                    }
                    FixtureGround.whenDone(context, () -> {
                        nether.setBlockState(netherPortal, Blocks.AIR.getDefaultState());
                        overworld.setBlockState(interior, Blocks.AIR.getDefaultState());
                    });
                    // STAGED: on the sill's floor, a block north of the frame.
                    f.player().setPosition(interior.getX() + 0.5, interior.getY(), interior.getZ() - 0.5);
                    f.player().getHungerManager().setFoodLevel(17);
                })
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.runtime.holdAutonomyForTest(f.id, 2200);
                    f.runtime.nativeBody(f.id).orElseThrow().setPortalTravelAuthorized(true);
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "south", 1) == 1, "The walk into the Overworld end admitted");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getServerWorld() == nether, "Through to the Nether"))
                .createAndAdd(() -> {
                    // The cooldown still holds on arrival: standing in the portal it came through does not send it back.
                    var player = f.player();
                    context.assertTrue(player.hasPortalCooldown(), "A cooldown on arrival, as vanilla gives it");
                    backTooSoon[0] |= player.getServerWorld() != nether;
                    context.assertTrue(++waited[0] >= 40, "Standing in the arrival portal: " + waited[0] + " ticks");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!backTooSoon[0] && f.player().getServerWorld() == nether, "Not sent straight back by the portal it came through");
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "north", 2) == 1, "The walk out of the Nether end admitted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    boolean inPortal = nether.getBlockState(player.getBlockPos()).isOf(Blocks.NETHER_PORTAL);
                    context.assertTrue(!inPortal && !f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("ACTIVE"),
                            "Out of the portal, its walk done: " + player.getBlockPos().toShortString());
                })
                .createAndAdd(() -> {
                    // Out of the portal, the cooldown runs down, as a person's does once its client has the new world.
                    cooledBeforeLeaving[0] = !f.player().hasPortalCooldown();
                    context.assertTrue(cooledBeforeLeaving[0], "The cooldown run down out of the portal (it never did with no client)");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "south", 2) == 1, "The walk back into the Nether end admitted"))
                .createAndAdd(() -> context.assertTrue(f.player().getServerWorld() == overworld,
                        "Back out in the Overworld through the portal there: in " + f.player().getServerWorld().getRegistryKey().getValue()
                                + " at " + f.player().getBlockPos().toShortString() + ", cooldown " + f.player().hasPortalCooldown()))
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.connected(), "Still connected after both crossings: " + status);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
