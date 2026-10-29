package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.Gaze;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * A native player looks at what matters while it waits (E-33a): at a monster once it is in plain sight, quickly but
 * never in one snap; at what it waits on; back at a player close by who looks at it, for a moment, and then back to
 * its waiting; and never into an enderman's eyes. Each fixture samples the head every tick.
 */
public final class NativeGazeGameTests implements FabricGameTest {

    /** The head's heading and pitch, tick by tick, and the most it turned in one tick. */
    private static final class Head {
        float yaw = Float.NaN, pitch;
        float worstTurn;
        int ticks;

        void sample(ServerPlayerEntity player) {
            float y = player.getHeadYaw(), p = player.getPitch();
            if (!Float.isNaN(yaw)) worstTurn = Math.max(worstTurn, Math.max(Math.abs(MathHelper.wrapDegrees(y - yaw)), Math.abs(p - pitch)));
            yaw = y;
            pitch = p;
            ticks++;
        }
    }

    private static float toward(ServerPlayerEntity player, Vec3d at) {
        Vec3d eye = player.getEyePos();
        return (float) Math.toDegrees(Math.atan2(-(at.x - eye.x), at.z - eye.z));
    }

    private static float off(float a, float b) { return Math.abs(MathHelper.wrapDegrees(a - b)); }

    /** Nothing running: no task yet ({@code UNLOADED}) or the last one settled ({@code IDLE}). */
    private static boolean idle(NativePaidSkillsGameTests.Fixture f) {
        String phase = f.runtime.nativeActivityStatus(Actor.console(), f.id).phase();
        return phase.equals("IDLE") || phase.equals("UNLOADED");
    }

    private static void faceSouth(ServerPlayerEntity player) {
        player.setYaw(0);
        player.setHeadYaw(0);
        player.setPitch(0);
    }

    /**
     * A husk four blocks east, behind a wall: in a wait it is not looked at. The wall taken down, it is turned to within
     * a second and a half, no turn of the head over thirty degrees in a tick.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gaze", tickLimit = 400)
    public void waitingItTurnsToAMonsterOnlyOnceItIsInSight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        HuskEntity[] husk = {null};
        var head = new Head();
        boolean[] lookedAtHidden = {false};
        int[] start = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Watcher"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    for (int y = 2; y <= 4; y++) for (int z = 1; z <= 5; z++) context.setBlockState(6, y, z, Blocks.STONE);
                    husk[0] = context.spawnMob(EntityType.HUSK, new Vec3d(7.5, 2, 3.5));
                    husk[0].setAiDisabled(true);
                    husk[0].setPersistent();
                    faceSouth(f.player());
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 4) == 1, "Wait started");
                })
                .createAndAdd(() -> {
                    head.sample(f.player());
                    if (off(head.yaw, toward(f.player(), husk[0].getEyePos())) < 15) lookedAtHidden[0] = true;
                    context.assertTrue(head.ticks >= 40, "Two seconds with the husk behind the wall");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!lookedAtHidden[0], "A husk behind a wall is not looked at");
                    for (int y = 2; y <= 4; y++) for (int z = 1; z <= 5; z++) context.setBlockState(6, y, z, Blocks.AIR);
                    start[0] = head.ticks;
                })
                .createAndAdd(() -> {
                    head.sample(f.player());
                    context.assertTrue(off(head.yaw, toward(f.player(), husk[0].getEyePos())) < 10,
                            "Turned to the husk once in sight: head " + head.yaw + ", toward " + toward(f.player(), husk[0].getEyePos()));
                })
                .createAndAdd(() -> {
                    int took = head.ticks - start[0];
                    context.assertTrue(took <= 30, "Within a second and a half of the wall going: " + took + " ticks");
                    context.assertTrue(took >= 3, "Not in one snap: " + took + " ticks");
                    context.assertTrue(head.worstTurn <= Gaze.THREAT_TURN + 0.01f, "No turn over thirty degrees in a tick: " + head.worstTurn);
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(act.work().contains("native_wait"), "Still its wait, not a fight or a retreat: " + act.work());
                    husk[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Waiting by a furnace it looks at the furnace. A player three blocks off looking at it is looked back at within a
     * second and a half, for a moment (under four seconds), then the furnace again; and not that player again so soon,
     * though it goes on looking.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gaze", tickLimit = 600)
    public void itLooksAtWhatItWaitsOnAndBackAtAPlayerLookingAtItForAMoment(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ServerPlayerEntity[] other = {null};
        var head = new Head();
        BlockPos furnace = new BlockPos(3, 2, 7);
        int[] mark = {0};
        boolean[] lookedAgain = {false};
        Runnable keepWaiting = () -> {
            var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
            if (act.phase().equals("IDLE")) f.runtime.nativeWaitAt(f.source, f.id, 4, context.getAbsolutePos(furnace));
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Smelter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.setBlockState(furnace, Blocks.FURNACE);
                    // Facing away from the furnace, north.
                    f.player().setYaw(180);
                    f.player().setHeadYaw(180);
                    context.assertTrue(f.runtime.nativeWaitAt(f.source, f.id, 4, context.getAbsolutePos(furnace)) == 1, "Wait by the furnace started");
                })
                .createAndAdd(() -> {
                    keepWaiting.run();
                    head.sample(f.player());
                    Vec3d at = Vec3d.ofCenter(context.getAbsolutePos(furnace));
                    context.assertTrue(off(head.yaw, toward(f.player(), at)) < 8 && head.pitch > 5,
                            "Looking at the furnace: head " + head.yaw + " " + head.pitch + ", toward " + toward(f.player(), at));
                })
                .createAndAdd(() -> {
                    // Another player, three blocks east, looking at its face.
                    other[0] = MockPlayers.creative(context);
                    Vec3d stand = context.getAbsolute(new Vec3d(6.5, 2, 3.5));
                    other[0].refreshPositionAndAngles(stand.x, stand.y, stand.z, 90, 0);
                    other[0].setHeadYaw(90);
                    mark[0] = head.ticks;
                })
                .createAndAdd(() -> {
                    keepWaiting.run();
                    head.sample(f.player());
                    context.assertTrue(off(head.yaw, toward(f.player(), other[0].getEyePos())) < 10,
                            "Looked back at the player looking at it: head " + head.yaw + ", toward " + toward(f.player(), other[0].getEyePos()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(head.ticks - mark[0] <= 30, "Within a second and a half: " + (head.ticks - mark[0]));
                    mark[0] = head.ticks;
                })
                .createAndAdd(() -> {
                    keepWaiting.run();
                    head.sample(f.player());
                    Vec3d at = Vec3d.ofCenter(context.getAbsolutePos(furnace));
                    context.assertTrue(off(head.yaw, toward(f.player(), at)) < 8, "Back to the furnace: head " + head.yaw);
                })
                .createAndAdd(() -> {
                    int held = head.ticks - mark[0];
                    context.assertTrue(held >= 20 && held <= 80, "A moment's look, not a stare: " + held + " ticks");
                    mark[0] = head.ticks;
                })
                .createAndAdd(() -> {
                    keepWaiting.run();
                    head.sample(f.player());
                    if (off(head.yaw, toward(f.player(), other[0].getEyePos())) < 10) lookedAgain[0] = true;
                    context.assertTrue(head.ticks - mark[0] >= 60, "Three seconds more, still looked at");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!lookedAgain[0], "Not looked back at again so soon");
                    context.assertTrue(head.worstTurn <= Gaze.PERSON_TURN + 0.01f, "No turn over twelve degrees in a tick: " + head.worstTurn);
                    context.getWorld().getServer().getPlayerManager().remove(other[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Spoken to by name by a player it can see, an idle player stops a moment and looks at the speaker (E-33a); spoken
     * to from behind a wall, it neither hears nor turns. The pause is its own short wait, chosen between tasks.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gaze", tickLimit = 500)
    public void spokenToByNameItStopsAMomentToLookAtTheSpeaker(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ServerPlayerEntity[] other = {null};
        var head = new Head();
        boolean[] pausedUnseen = {false};
        int[] mark = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Listener"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.socialSetting(f.source, f.id, "public", true, null, 0) == 1, "Open to talk with anyone");
                    for (int y = 2; y <= 4; y++) for (int z = 1; z <= 5; z++) context.setBlockState(6, y, z, Blocks.STONE);
                    other[0] = MockPlayers.creative(context);
                    Vec3d stand = context.getAbsolute(new Vec3d(7.5, 2, 3.5));
                    other[0].refreshPositionAndAngles(stand.x, stand.y, stand.z, 90, 0);
                    faceSouth(f.player());
                })
                // Settled after the resume, before a word is said: nothing run yet, or the last thing done.
                .createAndAdd(() -> context.assertTrue(idle(f), "Idle"))
                .createAndAdd(() -> {
                    f.runtime.chat(other[0].getUuid(), "Listener, are you there?");
                    mark[0] = head.ticks;
                })
                .createAndAdd(() -> {
                    head.sample(f.player());
                    if (!idle(f)) pausedUnseen[0] = true;
                    context.assertTrue(head.ticks - mark[0] >= 60, "Three seconds after a word from behind the wall");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!pausedUnseen[0], "Spoken to from behind a wall, nothing stops");
                    context.assertTrue(off(head.yaw, toward(f.player(), other[0].getEyePos())) > 30, "Nor did it turn that way: " + head.yaw);
                    for (int y = 2; y <= 4; y++) for (int z = 1; z <= 5; z++) context.setBlockState(6, y, z, Blocks.AIR);
                })
                .createAndAdd(() -> {
                    f.runtime.chat(other[0].getUuid(), "Listener, over here");
                    mark[0] = head.ticks;
                })
                .createAndAdd(() -> {
                    head.sample(f.player());
                    context.assertTrue(off(head.yaw, toward(f.player(), other[0].getEyePos())) < 10,
                            "Looked at the speaker: head " + head.yaw + ", toward " + toward(f.player(), other[0].getEyePos()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(head.ticks - mark[0] <= 40, "Within two seconds of being spoken to: " + (head.ticks - mark[0]));
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(act.work().contains("native_wait"), "Its own short pause: " + act.work());
                    context.assertTrue(head.worstTurn <= Gaze.SPEAKER_TURN + 0.01f, "No turn over fifteen degrees in a tick: " + head.worstTurn);
                    context.getWorld().getServer().getPlayerManager().remove(other[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * What it waits on lies just past an enderman's head: it turns that way but stops short of the enderman's eyes, as
     * vanilla reckons a stare, all through the wait. The control is the turn itself: its view comes within twenty
     * degrees of those eyes, so only the guard keeps it from them.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gaze", tickLimit = 400)
    public void itNeverLooksAnEndermanInTheEyes(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        EndermanEntity[] enderman = {null};
        var head = new Head();
        boolean[] stared = {false};
        double[] closest = {180};
        BlockPos past = new BlockPos(3, 5, 9);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Calm"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    enderman[0] = context.spawnMob(EntityType.ENDERMAN, new Vec3d(3.5, 2, 5.5));
                    enderman[0].setAiDisabled(true);
                    enderman[0].setPersistent();
                    f.player().setYaw(180);
                    f.player().setHeadYaw(180);
                    f.player().setPitch(0);
                    context.assertTrue(f.runtime.nativeWaitAt(f.source, f.id, 4, context.getAbsolutePos(past)) == 1, "Wait started");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    head.sample(player);
                    // Vanilla's EndermanEntity.isPlayerStaring, without the pumpkin.
                    Vec3d view = player.getRotationVec(1f).normalize();
                    Vec3d to = new Vec3d(enderman[0].getX() - player.getX(), enderman[0].getEyeY() - player.getEyeY(), enderman[0].getZ() - player.getZ());
                    double d = to.length();
                    double dot = view.dotProduct(to.normalize());
                    if (dot > 1.0 - 0.025 / d && player.canSee(enderman[0])) stared[0] = true;
                    closest[0] = Math.min(closest[0], Math.toDegrees(Math.acos(Math.min(1, dot))));
                    context.assertTrue(head.ticks >= 70, "Through the wait");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!stared[0], "Never stared at the enderman");
                    context.assertTrue(closest[0] < 20, "Turned toward what lies past it: within " + closest[0] + " degrees of its eyes");
                    context.assertTrue(enderman[0].getTarget() == null, "The enderman is calm");
                    enderman[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
