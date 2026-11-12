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
