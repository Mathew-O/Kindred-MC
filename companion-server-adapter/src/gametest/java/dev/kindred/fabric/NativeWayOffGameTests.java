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
 * In plain terms: a GameTest: a badly hurt player backing off from a zombie, or running from a creeper, in a stone tunnel
 * goes off down the tunnel, rather than try to walk straight through the rock behind it and stay where it is. On open
 * ground it backs straight away as before; and with a wall straight behind it and a way round on either side, as good as
 * each other, it goes round one side and keeps to it.
 *
 * Technical: F-25c4dh5. The straight back-off and the creeper run aimed at a raw point ten or twelve blocks straight away,
 * with no look at whether the ground that way can be walked. The walk is partial: refused when no step from the start comes
 * any nearer that point, ended as soon as it can come no nearer. In E-40's iteration 29, 51 such walks were refused (34 of
 * them with the point inside rock, 33 of all of them after expanding 30 nodes or fewer), and in the parked F-22c104a's gate
 * 53 back-offs ended within two blocks as "completed". Staged: a tunnel one wide and two high, east to west, with a short
 * alcove off it to the south; the player at the meeting, five health, an iron sword; the zombie in the alcove, a little east
 * of south. Straight away is north, into the rock; the tunnel west is the way off that still gains on it. On main the walk
 * aims at a point in the rock and ends at the nearest cell to it, a block or so down the tunnel; here the first back-off
 * (and the first run from a creeper) must take it at least four blocks before it ends. The control: the same on level
 * stone, open to the sky. The swing case: open stone with a wall three wide just north of the player and the zombie a
 * touch west of south, so straight away is into the wall and the ways round east and west gain nearly alike: once it is
 * a block and a half to one side it never comes back past where it stood.
 */
public final class NativeWayOffGameTests implements FabricGameTest {
    private static final int MID = 30;

    /** Solid stone round a tunnel at y 2 and 3 from x MID-14 to MID (and east to MID+{@code east}), and an alcove south. */
    private static void tunnel(TestContext context, int east) {
        FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(MID - 20, 0, MID - 14)), context.getAbsolutePos(new BlockPos(MID + 16, 6, MID + 8)));
        for (int x = MID - 20; x <= MID + 16; x++) for (int z = MID - 14; z <= MID + 8; z++) for (int y = 0; y <= 6; y++)
            context.setBlockState(x, y, z, Blocks.STONE);
        for (int x = MID - 14; x <= MID + east; x++) for (int y = 2; y <= 3; y++) context.setBlockState(x, y, MID, Blocks.AIR);
        for (int z = MID + 1; z <= MID + 3; z++) for (int y = 2; y <= 3; y++) context.setBlockState(MID, y, z, Blocks.AIR);
    }

    /** Level stone open to the sky, for the control. */
    private static void open(TestContext context) {
        FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(MID - 20, 0, MID - 14)), context.getAbsolutePos(new BlockPos(MID + 16, 8, MID + 8)));
        for (int x = MID - 20; x <= MID + 16; x++) for (int z = MID - 14; z <= MID + 8; z++) {
            context.setBlockState(x, 0, z, Blocks.STONE);
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y <= 8; y++) context.setBlockState(x, y, z, Blocks.AIR);
        }
    }

    /** Level stone with a wall three wide and two high just north of where the player stands. */
    private static void walled(TestContext context) {
        open(context);
        for (int x = MID - 1; x <= MID + 1; x++) for (int y = 2; y <= 3; y++) context.setBlockState(x, y, MID - 1, Blocks.STONE);
    }

    private enum Ground { TUNNEL, OPEN, WALLED, LAVA }

    private enum Foe { ZOMBIE, CREEPER, SKELETON }

    @GameTest(templateName = FixtureGround.WIDE, batchId = "wayOffTunnel", tickLimit = 600)
    public void inATunnelWithTheRockStraightBehindItItBacksOffDownTheTunnel(TestContext context) {
        run(context, "Tunneled", Ground.TUNNEL, Foe.ZOMBIE);
    }

    @GameTest(templateName = FixtureGround.WIDE, batchId = "wayOffOpen", tickLimit = 600)
    public void onOpenGroundItBacksStraightAwayAsBefore(TestContext context) {
        run(context, "Openfield", Ground.OPEN, Foe.ZOMBIE);
    }

    @GameTest(templateName = FixtureGround.WIDE, batchId = "wayOffCreeper", tickLimit = 600)
    public void inATunnelARunFromACreeperGoesDownTheTunnel(TestContext context) {
        run(context, "Creeperrun", Ground.TUNNEL, Foe.CREEPER);
    }

    @GameTest(templateName = FixtureGround.WIDE, batchId = "wayOffSwing", tickLimit = 600)
    public void withAWallBehindAndAWayRoundEitherSideItKeepsToOne(TestContext context) {
        run(context, "Swingless", Ground.WALLED, Foe.ZOMBIE);
    }

    /**
     * Against a shooter it does not turn: a walk down the tunnel in its line of fire, as e40c1-eed2dc5-8675309-echo-173225
     * died twice in its gate, is worse than main's way, the straight back-off refused and cover sought again. There its
     * cover was blocked ("produced no effect or was obstructed") before it backed off from the skeleton; with a block to
     * hand here it only takes cover, so it is staged with none: the tunnel, a skeleton in the alcove, no block for cover.
     * It backs off from the skeleton, and for five seconds no heading is turned for ground.
     */
    @GameTest(templateName = FixtureGround.WIDE, batchId = "wayOffShooter", tickLimit = 600)
    public void inATunnelWithASkeletonAfterItItDoesNotTurnItsBackOff(TestContext context) {
        run(context, "Shotat", Ground.TUNNEL, Foe.SKELETON);
    }

    /**
     * A guard on the walkable check: a way off across lava is no way off. The tunnel west has lava in its floor four blocks
     * on, within the cells the check reads, so of the ways that gain none can be walked: it turns no heading for ground, as
     * on main. The check reads lava, fire and magma through the route finder's own {@code standable} and {@code passable}.
     * Whether it burns later, in the fight that follows, is not this check's to decide: logged, not asserted.
     */
    @GameTest(templateName = FixtureGround.WIDE, batchId = "wayOffLava", tickLimit = 600)
    public void inATunnelWithLavaInTheWayOffItTurnsNoHeadingAcrossIt(TestContext context) {
        run(context, "Lavawise", Ground.LAVA, Foe.ZOMBIE);
    }

    /**
     * A turned back-off gets it off the wall but not across the cave: in f29e-on-505-echo-43893 a turned heading carried it
     * sixteen to twenty blocks with the zombie keeping pace and striking, where straight away ended at the wall in three or
     * four and it dug in; both of the branch's deaths there, in ten runs alone, came after such a walk. On the tunnel's
     * ground, the first turned back-off ends four to five and a half blocks from where it stood.
     */
    @GameTest(templateName = FixtureGround.WIDE, batchId = "wayOffShort", tickLimit = 600)
    public void inATunnelATurnedBackOffEndsWithinSixBlocks(TestContext context) {
        run(context, "Shortway", Ground.TUNNEL, Foe.ZOMBIE);
    }

    private static void run(TestContext context, String name, Ground ground, Foe foe) {
        boolean creeper = foe == Foe.CREEPER;
        // The turned back-off's own reach: off the wall, not across the cave.
        boolean capped = name.equals("Shortway");
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        Vec3d[] start = {null};
        long[] since = {-1};
        double[] farthest = {0};
        StringBuilder steps = new StringBuilder();
        String[] last = {""};
        // Where the first straight way off ended: how far from where it stood, or -1 while it has not.
        double[] firstEnd = {-1};
        // The side it went round the wall, once a block and a half out: -1 west, 1 east; and whether it came back past.
        int[] side = {0};
        boolean[] swung = {false};
        boolean[] burned = {false};
        String label = creeper ? "[Run from creeper]" : "[Back off from zombie]";
        List<String> ends = new CopyOnWriteArrayList<>();
        List<String> turned = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("wayOff" + name, null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (line.startsWith(name + " native_goto ") && line.endsWith(label) && !line.startsWith(name + " native_goto DISPATCHED")) ends.add(line);
                if (line.startsWith(name + " heading turned for ground")) turned.add(line);
            }
        };
        // On the root's config, so the Kindred logger's own console output is left as it is.
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    switch (ground) {
                        case TUNNEL -> tunnel(context, 1);
                        case OPEN -> open(context);
                        case WALLED -> walled(context);
                        case LAVA -> {
                            tunnel(context, 1);
                            context.setBlockState(MID - 4, 1, MID, Blocks.LAVA);
                        }
                    }
                    f.runtime.createNative(f.source, name);
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var at = context.getAbsolutePos(new BlockPos(MID, 2, MID));
                    f.player().refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.player().setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround()
                        && f.player().getBlockPos().equals(context.getAbsolutePos(new BlockPos(MID, 2, MID))), "Standing where the tunnel meets the alcove"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 4));
                    player.setHealth(5);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    context.getWorld().setTimeOfDay(14000);
                    // In the alcove a little east of south: straight away from it is north, into the rock. By the wall, a
                    // touch west of south: straight away is into the wall, the ways round it nearly alike.
                    double dx = ground == Ground.WALLED ? -0.05 : 0.3;
                    Vec3d at = context.getRelative(new Vec3d(player.getX() + dx, player.getY(), player.getZ() + 2.5));
                    if (creeper) {
                        var c = context.spawnEntity(net.minecraft.entity.EntityType.CREEPER, at);
                        c.setPersistent();
                        c.setTarget(player);
                    } else if (foe == Foe.SKELETON) {
                        var s = context.spawnEntity(net.minecraft.entity.EntityType.SKELETON, at);
                        s.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                        s.setPersistent();
                        s.setTarget(player);
                    } else {
                        var z = context.spawnEntity(net.minecraft.entity.EntityType.ZOMBIE, at);
                        z.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                        z.setPersistent();
                        z.setTarget(player);
                    }
                    start[0] = player.getPos();
                    since[0] = context.getTick();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.getWorld().setTimeOfDay(14000);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!progress.equals(last[0])) {
                        last[0] = progress;
                        steps.append(" @").append(context.getTick() - since[0]).append(' ').append(progress)
                                .append(String.format(java.util.Locale.ROOT, " (%.1f off)", horizontal(player.getPos(), start[0]))).append(';');
                    }
                    double off = horizontal(player.getPos(), start[0]);
                    farthest[0] = Math.max(farthest[0], off);
                    if (firstEnd[0] < 0 && !ends.isEmpty()) firstEnd[0] = off;
                    double dx = player.getX() - start[0].x;
                    if (side[0] == 0 && Math.abs(dx) >= 1.5) side[0] = dx < 0 ? -1 : 1;
                    if (side[0] != 0 && dx * side[0] < -0.5) swung[0] = true;
                    String told = String.format(java.util.Locale.ROOT, "farthest %.1f, first way off ended %.1f off; its steps:", farthest[0], firstEnd[0]) + steps + " " + ends;
                    if (ground == Ground.LAVA) {
                        // Five seconds on, no heading turned across the lava.
                        burned[0] |= player.isOnFire() || player.isInLava();
                        context.assertTrue(turned.isEmpty(), "No turn across lava; turned " + turned + "; its steps:" + steps);
                        context.assertTrue(context.getTick() - since[0] >= 100, "Five seconds; its steps:" + steps);
                    } else if (foe == Foe.SKELETON) {
                        // It backs off from it, and five seconds on no heading has been turned for ground.
                        context.assertTrue(turned.isEmpty(), "No turn against a shooter; turned " + turned + "; its steps:" + steps);
                        context.assertTrue(steps.indexOf("Back off from skeleton") >= 0 && context.getTick() - since[0] >= 100,
                                "Backed off from it, five seconds; its steps:" + steps);
                    } else if (ground == Ground.WALLED) {
                        // Eight seconds by the wall, round one side of it.
                        context.assertTrue(context.getTick() - since[0] >= 160 && side[0] != 0 && farthest[0] >= 5, "Round one side; " + told);
                    } else {
                        // The first straight way off takes it at least four blocks before it ends: down the tunnel, not a step.
                        context.assertTrue(firstEnd[0] >= 4 && farthest[0] >= 5, "Four blocks on the first way off; " + told);
                        if (capped) context.assertTrue(firstEnd[0] <= 5.5, "The turned way off ended within five and a half blocks; " + told);
                    }
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    if (ground == Ground.WALLED) context.assertTrue(!swung[0], "Kept to the side it took, " + (side[0] < 0 ? "west" : "east")
                            + "; its steps:" + steps + " " + ends);
                    if (ground == Ground.LAVA) KindredMod.LOGGER.info("{}: burned in the five seconds: {}", name, burned[0]);
                    if (ground == Ground.TUNNEL && foe != Foe.SKELETON) context.assertTrue(player.getX() < start[0].x - 4, "Down the tunnel west, at x " + String.format(java.util.Locale.ROOT, "%.1f", player.getX() - start[0].x)
                            + " from where it stood; its steps:" + steps);
                    KindredMod.LOGGER.info("{}: {} blocks off, the first way off ended {} off; its steps:{}", name, String.format(java.util.Locale.ROOT, "%.1f", farthest[0]),
                            String.format(java.util.Locale.ROOT, "%.1f", firstEnd[0]), steps);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static double horizontal(Vec3d a, Vec3d b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
