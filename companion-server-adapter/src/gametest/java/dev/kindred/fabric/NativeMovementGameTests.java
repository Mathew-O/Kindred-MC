package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Real paid runtime movement, no fixture teleport or manually advanced entity ticks. */
public final class NativeMovementGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void paidNativeWalkArrivesAndStopPreventsContinuedTravel(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        Vec3d[] positions = new Vec3d[2];
        int[] stoppedAge = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Walker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Native physics settled on the fixture floor"))
                .createAndAdd(() -> {
                    positions[0] = f.player().getPos();
                    context.assertTrue(f.runtime.nativeWalk(f.source.withLevel(0), f.id, "east", 2) == 0, "Foreign request denied");
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "east", 4) == 0, "Oversized route denied");
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "up", 2) == 0, "Unsupported direction denied");
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "east", 2) == 1, "Short corridor requested");
                    context.assertTrue(f.player().getPos().equals(positions[0]), "Request never teleports the body");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_walk"), "Native movement completes and settles");
                    Vec3d delta = f.player().getPos().subtract(positions[0]);
                    context.assertTrue(delta.x > 1.8 && delta.x < 2.3 && Math.abs(delta.z) < 0.15 && Math.abs(delta.y) < 0.1, "Actual native arrival within tolerance");
                    context.assertTrue(activity.chargedMillis() > 0 && activity.chargedMillis() <= 5000, "One paid slice bounds the walk");
                    positions[0] = f.player().getPos();
                    f.runtime.nativeWalk(f.source, f.id, "west", 2);
                })
                .createAndAdd(() -> context.assertTrue(f.player().getX() < positions[0].x - 0.15, "Return walk physically started"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    positions[1] = f.player().getPos(); stoppedAge[0] = f.player().age;
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "west", 2) == 0, "Stopped movement cannot restart");
                })
                .createAndAdd(() -> context.assertTrue(f.player().age >= stoppedAge[0] + 20, "Observe native momentum settle without new inputs"))
                .createAndAdd(() -> {
                    context.assertTrue(f.player().getPos().distanceTo(positions[1]) < 0.5, "Stop permits only residual native momentum, no continued walk");
                    context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Stop remains latched");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 260)
    public void unsafeCorridorsRejectAndDynamicWallsCancelBeforeCollision(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos[] wall = new BlockPos[1];
        Vec3d[] start = new Vec3d[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Careful"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos ahead = f.player().getBlockPos().east();
                    world.setBlockState(ahead, Blocks.STONE.getDefaultState());
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "east", 2) == 0, "Solid obstruction rejected before paid admission");
                    world.setBlockState(ahead, Blocks.AIR.getDefaultState());
                    world.setBlockState(ahead.down(), Blocks.AIR.getDefaultState());
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "east", 2) == 0, "Unsupported edge rejected");
                    world.setBlockState(ahead.down(), Blocks.MAGMA_BLOCK.getDefaultState());
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "east", 2) == 0, "Dangerous ground rejected");
                    world.setBlockState(ahead.down(), Blocks.STONE.getDefaultState());
                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("UNLOADED"), "Rejected corridors reserve no activity");
                    start[0] = f.player().getPos();
                    wall[0] = f.player().getBlockPos().west(2);
                    context.assertTrue(f.runtime.nativeWalk(f.source, f.id, "west", 3) == 1, "Clear route accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getX() < start[0].x - 0.15, "Native motion started"))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(wall[0], Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(wall[0].up(), Blocks.STONE.getDefaultState());
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Blocked:"), "Changed corridor cancels through shared runner and settles");
                    context.assertTrue(f.player().getX() > wall[0].getX() + 1.3, "Body stops ahead of newly blocked passage");
                    context.assertTrue(context.getWorld().getBlockState(wall[0]).isOf(Blocks.STONE), "Movement never mines the obstruction");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nativeTraverseStepUp", tickLimit = 420)
    public void paidNativeTraverseStepsUpOneBlockWithJump(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), false);
        Vec3d[] start = new Vec3d[1];

        f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> f.runtime.createNative(f.source, "StepClimber"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    start[0] = f.player().getPos();
                    BlockPos p = f.player().getBlockPos();
                    context.getWorld().setBlockState(p.east(), Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(p.east(2), Blocks.STONE.getDefaultState());
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 2) == 1, "Traverse with step-up accepted");
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_traverse"),
                            "Traverse completed and settled: " + activity.work() + "; position=" + f.player().getPos()
                                    + "; start=" + start[0] + "; velocity=" + f.player().getVelocity());
                    Vec3d delta = f.player().getPos().subtract(start[0]);
                    context.assertTrue(delta.x > 1.8 && delta.x < 2.3, "Horizontal advance within tolerance: dx=" + delta.x);
                    context.assertTrue(delta.y > 0.8 && delta.y < 1.2, "Elevated arrival onto step: dy=" + delta.y);
                    context.assertTrue(Math.abs(delta.z) < 0.2, "Yaw alignment maintained: dz=" + delta.z);
                    context.assertTrue(f.player().isOnGround(), "Landed on ground on top of step");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nativeTraverseStepDown", tickLimit = 620)
    public void paidNativeTraverseStepsDownSafely(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), false);
        Vec3d[] elevated = new Vec3d[1];

        f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> f.runtime.createNative(f.source, "StepDowner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    BlockPos p = f.player().getBlockPos();
                    context.getWorld().setBlockState(p.east(), Blocks.STONE.getDefaultState());
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 1) == 1, "Climb onto elevated block");
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_traverse"), "On elevated block");
                    elevated[0] = f.player().getPos();
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 1) == 1, "Step down onto floor"))
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_traverse"),
                            "Stepped down: " + activity + "; position=" + f.player().getPos() + "; elevated=" + elevated[0]);
                    Vec3d stepDownDelta = f.player().getPos().subtract(elevated[0]);
                    context.assertTrue(stepDownDelta.x > 0.8 && stepDownDelta.x < 1.55, "Horizontal advance on drop: dx=" + stepDownDelta.x);
                    context.assertTrue(stepDownDelta.y < -0.8 && stepDownDelta.y > -1.2, "Vertical drop of 1 block: dy=" + stepDownDelta.y);
                    context.assertTrue(f.player().isOnGround(), "Landed on floor safely");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void paidNativeTraverseRejectsImpassableTerrain(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "SafetyChecker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos ahead = f.player().getBlockPos().east();

                    // 1. Two-block high wall (cannot jump 2 blocks)
                    world.setBlockState(ahead, Blocks.STONE.getDefaultState());
                    world.setBlockState(ahead.up(), Blocks.STONE.getDefaultState());
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 2) == 0, "Two-block high wall rejected before admission");

                    // 2. Clear wall, create low ceiling (headroom blocked when jumping)
                    world.setBlockState(ahead.up(), Blocks.AIR.getDefaultState());
                    BlockPos ceiling = f.player().getBlockPos().up(2);
                    world.setBlockState(ceiling, Blocks.STONE.getDefaultState());
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 2) == 0, "Low ceiling blocking jump headroom rejected");
                    world.setBlockState(ceiling, Blocks.AIR.getDefaultState());

                    // 3. Clear ahead, create dangerous floor (magma block)
                    world.setBlockState(ahead, Blocks.AIR.getDefaultState());
                    world.setBlockState(ahead.down(), Blocks.MAGMA_BLOCK.getDefaultState());
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 2) == 0, "Dangerous magma floor rejected");
                    world.setBlockState(ahead.down(), Blocks.STONE.getDefaultState());

                    // 4. Cliff drop > 3 blocks (e.g. 4-block drop)
                    for (int dy = 0; dy <= 4; dy++) {
                        world.setBlockState(ahead.down(dy), Blocks.AIR.getDefaultState());
                    }
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 2) == 0, "4-block cliff drop rejected as unsafe");
                    world.setBlockState(ahead.down(), Blocks.STONE.getDefaultState());

                    // 5. Oversized route, unsupported direction, foreign source
                    context.assertTrue(f.runtime.nativeTraverse(f.source.withLevel(0), f.id, "east", 2) == 0, "Foreign source denied");
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 4) == 0, "Oversized route denied");
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "up", 2) == 0, "Unsupported direction denied");

                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("UNLOADED"), "Rejected traversal reserves no activity");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void dynamicWallDuringTraverseCancelsAndSettles(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos[] wall = new BlockPos[1];
        Vec3d[] start = new Vec3d[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "DynamicWatcher"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    start[0] = f.player().getPos();
                    wall[0] = f.player().getBlockPos().west(2);
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "west", 3) == 1, "Clear 3-block route accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getX() < start[0].x - 0.15, "Native traverse motion started"))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(wall[0], Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(wall[0].up(), Blocks.STONE.getDefaultState());
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Blocked:"), "Changed corridor cancels and settles");
                    context.assertTrue(f.player().getX() > wall[0].getX() + 1.2, "Body stops ahead of newly blocked passage");
                    context.assertTrue(context.getWorld().getBlockState(wall[0]).isOf(Blocks.STONE), "Movement never mines the obstruction");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
