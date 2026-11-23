package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In plain terms: does an AI player keep acting on an old look round itself (a creeper about to blow up beside it in
 * the Overworld) after it has gone into the Nether, or on one that could not be renewed there?
 *
 * Technical: F-25c6d's stale-observation hypothesis (the lead, 2026-11-23). E-40c4's Nether freezes lasted 100 minutes
 * with no harm, which points away from lava, fire and magma and toward the hazard interrupt's creeper trigger, read
 * from {@code lastTacticalObservation}. Its refresh swallowed failures, so a failing refresh would keep the last one.
 * Two questions, each its own test: after a transit, is the observation the Nether's (its dimension, its age, the
 * creeper gone)? And does the refresh fail for an ordinary Nether player among piglins? Both STAGED: the lit creeper is
 * summoned and ignited; the transit is a fixture teleport (dimension bookkeeping, not earned portal travel).
 */
public final class NativeTacticalRefreshGameTests implements FabricGameTest {

    private static List<String> capture(TestContext context, String name) {
        List<String> lines = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("tacticalRefresh-" + name, null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (line.startsWith(name + ": the tactical observation could not be refreshed") || line.startsWith(name + " hazard spell")) lines.add(line);
            }
        };
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        return lines;
    }

    /** A closed netherrack room at {@code at}, kept and put back, its chunk held loaded for the test. */
    private static void netherRoom(TestContext context, ServerWorld nether, BlockPos at) {
        FixtureGround.keep(context, nether, at.add(-5, -2, -5), at.add(5, 4, 5));
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) for (int dy = -2; dy <= 4; dy++) {
            boolean shell = Math.abs(dx) == 5 || Math.abs(dz) == 5 || dy <= -1 || dy == 4;
            nether.setBlockState(at.add(dx, dy, dz), shell ? Blocks.NETHERRACK.getDefaultState() : Blocks.AIR.getDefaultState());
        }
        int cx = at.getX() >> 4, cz = at.getZ() >> 4;
        nether.setChunkForced(cx, cz, true);
        FixtureGround.whenDone(context, () -> nether.setChunkForced(cx, cz, false));
    }

    private static boolean litCreeper(dev.kindred.core.tactics.TacticalObservation obs) {
        return obs != null && obs.observedMobs().stream().anyMatch(m -> m.isCreeper()
                && (m.isIgnited() || (m.fuseTicksRemaining() >= 0 && m.fuseTicksRemaining() <= 25)));
    }

    /** A lit creeper seen in the Overworld, then through to the Nether: its observation renewed there, the creeper gone. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tacticalAfterTransit", tickLimit = 800)
    public void afterATransitTheObservationIsTheNethersAndALitCreeperLeftBehindIsNotInIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 700);
        String name = "Crosser";
        var lines = capture(context, name);
        ServerWorld nether = context.getWorld().getServer().getWorld(World.NETHER);
        BlockPos above = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos room = new BlockPos(above.getX(), 70, above.getZ());
        int[] watched = {0};
        long[] arrived = {-1};
        String[] seen = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // STAGED: a creeper beside it, lit, so the next observation holds a lit creeper.
                    var creeper = EntityType.CREEPER.create(context.getWorld());
                    var p = f.player().getPos();
                    creeper.refreshPositionAndAngles(p.x + 2, p.y, p.z, 0, 0);
                    creeper.setAiDisabled(true);
                    creeper.ignite();
                    context.getWorld().spawnEntity(creeper);
                    FixtureGround.whenDone(context, creeper::discard);
                })
                .createAndAdd(() -> context.assertTrue(litCreeper(f.runtime.tacticalObservationForTest(f.id)), "The lit creeper in its observation"))
                .createAndAdd(() -> {
                    var obs = f.runtime.tacticalObservationForTest(f.id);
                    context.assertTrue(obs.dimension().endsWith("overworld"), "Seen in the Overworld: " + obs.dimension());
                    netherRoom(context, nether, room);
                    f.runtime.nativeBody(f.id).orElseThrow().setPortalTravelAuthorized(true);
                    var body = f.runtime.nativeBody(f.id).orElseThrow();
                    nether.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.PORTAL, new net.minecraft.util.math.ChunkPos(room), 3, room);
                    context.assertTrue(body.entity().teleport(nether, room.getX() + 0.5, room.getY(), room.getZ() + 0.5, java.util.Set.of(), 0, 0),
                            "STAGED transit to the Nether");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getServerWorld() == nether, "In the Nether"))
                .createAndAdd(() -> {
                    f.runtime.syncNativeDimensionAfterTransit(f.id);
                    arrived[0] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> {
                    var obs = f.runtime.tacticalObservationForTest(f.id);
                    long now = context.getWorld().getServer().getTicks();
                    seen[0] = obs == null ? "none" : obs.dimension() + ", " + (now - obs.tick()) + " ticks old, lit creeper " + litCreeper(obs);
                    context.assertTrue(++watched[0] >= 100, "Watched in the Nether: " + seen[0]);
                })
                .createAndAdd(() -> {
                    var obs = f.runtime.tacticalObservationForTest(f.id);
                    long now = context.getWorld().getServer().getTicks();
                    context.assertTrue(lines.stream().noneMatch(l -> l.contains("could not be refreshed")), "No refresh failure: " + lines);
                    context.assertTrue(obs != null && obs.dimension().endsWith("the_nether"), "The observation is the Nether's: " + seen[0]);
                    context.assertTrue(now - obs.tick() <= 20, "And fresh: " + seen[0]);
                    context.assertTrue(!litCreeper(obs), "The creeper left behind is not in it: " + seen[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /** An ordinary Nether player among piglins and a hoglin for some hundreds of ticks: the refresh never fails. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tacticalInTheNether", tickLimit = 1000)
    public void inTheNetherAmongPiglinsTheObservationIsRenewedWithoutFailing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 900);
        String name = "Netherer";
        var lines = capture(context, name);
        ServerWorld nether = context.getWorld().getServer().getWorld(World.NETHER);
        BlockPos above = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos room = new BlockPos(above.getX(), 70, above.getZ());
        int[] watched = {0}, renewed = {0};
        long[] lastTick = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    netherRoom(context, nether, room);
                    f.runtime.nativeBody(f.id).orElseThrow().setPortalTravelAuthorized(true);
                    nether.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.PORTAL, new net.minecraft.util.math.ChunkPos(room), 3, room);
                    context.assertTrue(f.runtime.nativeBody(f.id).orElseThrow().entity().teleport(nether, room.getX() + 0.5, room.getY(), room.getZ() + 0.5,
                            java.util.Set.of(), 0, 0), "STAGED transit to the Nether");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getServerWorld() == nether, "In the Nether"))
                .createAndAdd(() -> {
                    f.runtime.syncNativeDimensionAfterTransit(f.id);
                    // Piglins and a hoglin about, AI on, at the room's far side.
                    for (var type : List.of(EntityType.PIGLIN, EntityType.PIGLIN, EntityType.HOGLIN)) {
                        var mob = type.create(nether);
                        mob.refreshPositionAndAngles(room.getX() + 3.5, room.getY(), room.getZ() + 3.5, 0, 0);
                        if (mob instanceof net.minecraft.entity.mob.PiglinEntity piglin) piglin.setImmuneToZombification(true);
                        if (mob instanceof net.minecraft.entity.mob.HoglinEntity hoglin) hoglin.setImmuneToZombification(true);
                        nether.spawnEntity(mob);
                        FixtureGround.whenDone(context, mob::discard);
                    }
                })
                .createAndAdd(() -> {
                    var obs = f.runtime.tacticalObservationForTest(f.id);
                    if (obs != null && obs.tick() != lastTick[0]) { renewed[0]++; lastTick[0] = obs.tick(); }
                    f.player().setHealth(20);
                    context.assertTrue(++watched[0] >= 400, "Watched " + watched[0] + " ticks");
                })
                .createAndAdd(() -> {
                    var obs = f.runtime.tacticalObservationForTest(f.id);
                    context.assertTrue(lines.stream().noneMatch(l -> l.contains("could not be refreshed")), "No refresh failure: " + lines);
                    context.assertTrue(obs != null && obs.dimension().endsWith("the_nether"), "The Nether's: " + (obs == null ? "none" : obs.dimension()));
                    context.assertTrue(renewed[0] >= 300, "Renewed nearly every tick: " + renewed[0] + " of " + watched[0]);
                    context.assertTrue(obs.observedMobs().stream().anyMatch(m -> m.entityType().contains("piglin") || m.entityType().contains("hoglin")),
                            "The piglins seen: " + obs.observedMobs().stream().map(m -> m.entityType()).toList());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }
}
