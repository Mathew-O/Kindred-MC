package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * In plain terms: a GameTest of what an AI player's body would hear (E-71f's first step, diagnostic only). The sounds a
 * server sends a person's connection are sent to the body's too; this records which arrive and in what form, and nothing
 * acts on them.
 *
 * Technical: a native body, standing still (no autonomy: the fixture's edits are off), on a walled stone pad in the open.
 * Around it, staged and stated: a creeper primed four blocks behind it (discarded before its fuse runs out), a skeleton
 * six blocks off (a helmet on, so not burning in the sun) shooting at an armor stand away from it, a small explosion thirteen blocks off that breaks nothing, a
 * block broken three blocks off, a cow walked across the pad, and a lava source left to flow. {@code NativePlayerBody}'s
 * diagnostic tap records each sound-carrying packet its connection is sent (the sounds, a sound from an entity, world
 * events, explosions, inside bundles too) with its kind, place, volume and pitch; the fixture logs them all and checks
 * the ones the server is known to send. What does not arrive is logged as such, not failed: the lava's own ambient
 * sounds, for one, are made by the client and never sent.
 */
public final class NativeHearingGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hearing", tickLimit = 900)
    public void whatTheServerSendsItsConnectionIsTheSoundsAroundItLoggedAndNotActedOn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false);
        BlockPos[] origin = new BlockPos[1];
        CreeperEntity[] creeper = new CreeperEntity[1];
        CowEntity[] cow = new CowEntity[1];
        int[] at = new int[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Listener"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    chunks(world, o.add(-24, 0, -24), o.add(24, 0, 24), true);
                    for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        boolean edge = Math.abs(x) == 14 || Math.abs(z) == 14;
                        for (int y = 0; y <= 5; y++) world.setBlockState(o.add(x, y, z), edge && y <= 1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0); // Facing south (+z).
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(origin[0]), "On the pad"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = origin[0];
                    f.runtime.nativeBody(f.id).orElseThrow().listenForTest(true);
                    at[0] = world.getServer().getTicks();
                    // Behind it: a creeper primed four blocks back.
                    creeper[0] = spawn(world, EntityType.CREEPER, o.add(0, 0, -4));
                    creeper[0].setAiDisabled(true);
                    creeper[0].ignite();
                    // Six blocks east: a skeleton with a bow, shooting at an armor stand to the south-east, away from it.
                    SkeletonEntity skeleton = spawn(world, EntityType.SKELETON, o.add(6, 0, 0));
                    skeleton.setAiDisabled(true);
                    skeleton.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                    skeleton.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // Not burning in the sun.
                    ArmorStandEntity stand = spawn(world, EntityType.ARMOR_STAND, o.add(10, 0, 8));
                    skeleton.shootAt(stand, 1.0f);
                    // A block broken three blocks off, as a pickaxe would break it.
                    BlockPos block = o.add(3, 0, 3);
                    world.setBlockState(block, Blocks.STONE.getDefaultState());
                    world.breakBlock(block, false);
                    // Thirteen blocks off: a small explosion that breaks nothing and reaches nobody.
                    world.createExplosion(null, o.getX() - 10 + 0.5, o.getY(), o.getZ() + 8.5, 1.0f, World.ExplosionSourceType.NONE);
                    // A cow walked across the pad, and a lava source in a corner left to flow.
                    cow[0] = spawn(world, EntityType.COW, o.add(-8, 0, -8));
                    cow[0].getNavigation().startMovingTo(o.getX() - 8 + 0.5, o.getY(), o.getZ() + 8.5, 1.2);
                    world.setBlockState(o.add(-10, 0, 10), Blocks.LAVA.getDefaultState());
                })
                .createAndAdd(() -> {
                    // Its fuse is 30 ticks: taken away after its priming has been sent, before it goes off.
                    context.assertTrue(context.getWorld().getServer().getTicks() >= at[0] + 10, "The fuse lit");
                    creeper[0].discard();
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getServer().getTicks() >= at[0] + 200, "Ten seconds of listening"))
                .createAndAdd(() -> {
                    var body = f.runtime.nativeBody(f.id).orElseThrow();
                    List<NativePlayerBody.Heard> heard = body.heardForTest();
                    body.listenForTest(false);
                    BlockPos o = origin[0];
                    var self = f.player().getPos();
                    for (var h : heard) {
                        double d = Double.isNaN(h.x()) ? Double.NaN : Math.sqrt(self.squaredDistanceTo(h.x(), h.y(), h.z()));
                        KindredMod.LOGGER.info("E-71f heard: +{} ticks, {} {} ({}) at {}, {}, {} ({} blocks), volume {}, pitch {}",
                                h.tick() - at[0], h.packet(), h.sound(), h.category(), round(h.x() - o.getX()), round(h.y() - o.getY()),
                                round(h.z() - o.getZ()), round(d), h.volume(), h.pitch());
                    }
                    KindredMod.LOGGER.info("E-71f heard {} packets in all; lava: {}; cow steps: {}", heard.size(),
                            heard.stream().filter(h -> h.sound().contains("lava")).count(), heard.stream().filter(h -> h.sound().equals("minecraft:entity.cow.step")).count());
                    context.assertTrue(heard.stream().anyMatch(h -> h.sound().equals("minecraft:entity.creeper.primed")
                            && Math.abs(h.z() - (o.getZ() - 4 + 0.5)) < 1.5), "The creeper's priming, from behind it: " + heard);
                    context.assertTrue(heard.stream().anyMatch(h -> h.sound().equals("minecraft:entity.skeleton.shoot")), "The skeleton's shot: " + heard);
                    context.assertTrue(heard.stream().anyMatch(h -> h.packet().equals("explosion")), "The explosion: " + heard);
                    context.assertTrue(heard.stream().anyMatch(h -> h.packet().equals("world event") && h.sound().equals("2001")), "The block broken (world event 2001): " + heard);
                    context.assertTrue(f.player().isAlive() && f.player().getHealth() == f.player().getMaxHealth(), "It came to no harm: " + f.player().getHealth());
                    chunks(context.getWorld(), o.add(-24, 0, -24), o.add(24, 0, 24), false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * E-71f's observations: what it hears is kept as kind, bearing (from where it faced as it heard it; its idle gaze turns it,
     * so the fixture keeps where it faced each tick) and band only. A creeper primed three blocks behind it is a hiss,
     * from where the creeper is, near. Water running ten blocks off is heard by the client's own rule. A
     * pool of lava under a stone lid makes no sound at all, as for a person; with the lid lifted it is heard, from where
     * it lies. Nothing it does changes for what it hears: its autonomy is off, and the step back its survival reflex takes from
     * the creeper it sees is sight, as before.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hearingObserved", tickLimit = 2000)
    public void whatItHearsIsKindBearingAndBandAndSealedLavaIsSilent(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false);
        BlockPos[] origin = new BlockPos[1];
        CreeperEntity[] creeper = new CreeperEntity[1];
        int[] at = new int[3];
        List<dev.kindred.core.hearing.HeardSound> phase = new java.util.ArrayList<>();
        // Where it stood and faced each tick: its idle gaze turns it, and its survival reflex steps it back from the creeper
        // it sees (sight, not hearing: nothing reads what it hears); a sound's bearing is from where it faced as it heard it.
        java.util.Map<Long, double[]> stance = new java.util.concurrent.ConcurrentHashMap<>();
        context.runAtEveryTick(() -> {
            var player = f.nativeId == null ? null : f.player();
            if (player != null) stance.put((long) context.getWorld().getServer().getTicks(), new double[] {player.getYaw(), player.getX(), player.getZ()});
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Listener"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    chunks(world, o.add(-24, 0, -24), o.add(24, 0, 24), true);
                    for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        boolean edge = Math.abs(x) == 14 || Math.abs(z) == 14;
                        for (int y = 0; y <= 5; y++) world.setBlockState(o.add(x, y, z), edge && y <= 1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    // Behind it and to its left (facing south, its left is east): a 3x3 pool of lava under a stone lid, walled in.
                    for (int x = 3; x <= 7; x++) for (int z = -7; z <= -3; z++) world.setBlockState(o.add(x, 0, z), Blocks.STONE.getDefaultState());
                    for (int x = 4; x <= 6; x++) for (int z = -6; z <= -4; z++) {
                        world.setBlockState(o.add(x, 0, z), Blocks.LAVA.getDefaultState());
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0); // Facing south (+z).
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(origin[0]), "On the pad"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    at[0] = world.getServer().getTicks();
                    f.runtime.hearingAmbience(true); // Off by default (KINDRED_HEARING_AMBIENCE); on for this test.
                    f.runtime.heardLatelyForTest(f.id); // Whatever came before is not this test's.
                    creeper[0] = spawn(world, EntityType.CREEPER, origin[0].add(0, 0, -3));
                    creeper[0].setAiDisabled(true);
                    creeper[0].ignite();
                })
                .createAndAdd(() -> {
                    var heard = f.runtime.heardLatelyForTest(f.id).getKey();
                    context.assertTrue(heard.stream().anyMatch(h -> h.kind() == dev.kindred.core.hearing.HeardSound.Kind.CREEPER_HISS),
                            "The hiss heard: " + heard);
                    var hiss = heard.stream().filter(h -> h.kind() == dev.kindred.core.hearing.HeardSound.Kind.CREEPER_HISS).findFirst().orElseThrow();
                    context.assertTrue(hiss.band() == dev.kindred.core.hearing.HeardSound.Band.NEAR
                            && bearingsFrom(stance, hiss.tick(), List.of(new double[] {origin[0].getX() + 0.5, origin[0].getZ() - 2.5})).contains(hiss.bearing()),
                            "A hiss, from where the creeper stood behind it, near: " + hiss);
                    creeper[0].discard();
                    // Water running from a source ten blocks off, ahead and to its right (west); the lava still sealed.
                    context.getWorld().setBlockState(origin[0].add(-8, 0, 6), Blocks.WATER.getDefaultState());
                    at[1] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> {
                    var lately = f.runtime.heardLatelyForTest(f.id).getKey();
                    for (var h : lately) if (h.tick() >= at[1] && !phase.contains(h)) phase.add(h);
                    context.assertTrue(context.getWorld().getServer().getTicks() >= at[1] + 300, "Fifteen seconds with the lava sealed");
                })
                .createAndAdd(() -> {
                    var cost = f.runtime.heardLatelyForTest(f.id).getValue();
                    KindredMod.LOGGER.info("E-71f observed, the lava sealed: {} (listening {} us a tick)", phase, String.format(java.util.Locale.ROOT, "%.1f", cost));
                    context.assertTrue(phase.stream().anyMatch(h -> h.kind() == dev.kindred.core.hearing.HeardSound.Kind.WATER), "The running water heard: " + phase);
                    context.assertTrue(phase.stream().noneMatch(h -> h.kind() == dev.kindred.core.hearing.HeardSound.Kind.LAVA), "Sealed lava silent: " + phase);
                    // The lid lifted: lava with air above it.
                    for (int x = 4; x <= 6; x++) for (int z = -6; z <= -4; z++) context.getWorld().setBlockState(origin[0].add(x, 1, z), Blocks.AIR.getDefaultState());
                    phase.clear();
                    at[2] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> {
                    var lately = f.runtime.heardLatelyForTest(f.id).getKey();
                    for (var h : lately) if (h.tick() >= at[2] && !phase.contains(h)) phase.add(h);
                    context.assertTrue(phase.stream().anyMatch(h -> h.kind() == dev.kindred.core.hearing.HeardSound.Kind.LAVA)
                            || context.getWorld().getServer().getTicks() >= at[2] + 800, "Listening for the open lava");
                })
                .createAndAdd(() -> {
                    var lava = phase.stream().filter(h -> h.kind() == dev.kindred.core.hearing.HeardSound.Kind.LAVA).toList();
                    KindredMod.LOGGER.info("E-71f observed, the lava open: {} lava of {} heard after {} ticks", lava.size(), phase.size(),
                            context.getWorld().getServer().getTicks() - at[2]);
                    context.assertTrue(!lava.isEmpty(), "The open lava heard: " + phase);
                    var pool = new java.util.ArrayList<double[]>();
                    for (int x = 4; x <= 6; x++) for (int z = -6; z <= -4; z++) pool.add(new double[] {origin[0].getX() + x + 0.5, origin[0].getZ() + z + 0.5});
                    context.assertTrue(lava.stream().allMatch(h -> bearingsFrom(stance, h.tick(), pool).contains(h.bearing())
                                    && h.band() != dev.kindred.core.hearing.HeardSound.Band.FAR),
                            "From where the pool lies, within eight blocks: " + lava);
                    context.assertTrue(f.player().isAlive() && f.player().getHealth() == f.player().getMaxHealth(), "Unharmed: health " + f.player().getHealth());
                    chunks(context.getWorld(), origin[0].add(-24, 0, -24), origin[0].add(24, 0, 24), false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The bearings a sound from any of {@code cells} (world x, z) could have been heard at, from where it stood and faced
     * the tick before, the tick of, or the tick after hearing it.
     */
    private static java.util.Set<dev.kindred.core.hearing.HeardSound.Bearing> bearingsFrom(java.util.Map<Long, double[]> stance, long tick, List<double[]> cells) {
        var out = java.util.EnumSet.noneOf(dev.kindred.core.hearing.HeardSound.Bearing.class);
        for (long t = tick - 1; t <= tick + 1; t++) {
            double[] s = stance.get(t);
            if (s == null) continue;
            for (double[] c : cells) out.add(dev.kindred.core.hearing.Hearing.bearing(c[0] - s[1], c[1] - s[2], (float) s[0]));
        }
        return out;
    }

    private static <T extends net.minecraft.entity.Entity> T spawn(ServerWorld world, EntityType<T> type, BlockPos at) {
        T entity = type.create(world);
        if (entity == null) throw new IllegalStateException("No " + type);
        entity.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        if (entity instanceof net.minecraft.entity.mob.MobEntity mob) mob.setPersistent();
        world.spawnEntity(entity);
        return entity;
    }

    private static String round(double v) { return Double.isNaN(v) ? "?" : String.format(java.util.Locale.ROOT, "%.1f", v); }

    private static void chunks(ServerWorld world, BlockPos min, BlockPos max, boolean forced) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) world.setChunkForced(cx, cz, forced);
        }
    }
}
