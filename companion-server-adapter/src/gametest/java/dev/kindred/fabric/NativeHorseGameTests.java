package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import java.util.Map;
import java.util.UUID;

/**
 * In plain terms: an AI player tames a wild horse the way a person does, by getting on it again and again until it
 * stops throwing it off; puts on it a saddle it has (as though found in a chest); and rides it across a field.
 *
 * Technical: M-01a, horses: the mechanics, each through the player's own skills (`native_ride`, `native_saddle`) on a
 * real horse, its taming by vanilla's own bonding goal. Staged, as the fixture says: a field of grass walled in, a horse
 * put beside the player, a saddle in its inventory as though found; the skills asked for by the fixture, one at a time.
 * The player's own choice to keep a horse is a later slice.
 */
public final class NativeHorseGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "horse", tickLimit = 6000)
    public void aWildHorseIsTamedByMountingItThenSaddledAndRidden(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5800);
        BlockPos o = context.getAbsolutePos(new BlockPos(4, 0, 4));
        UUID[] horse = new UUID[1];
        int[] mounts = {0};
        long[] lastAsk = {-1000};
        double[] startX = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-4, 0, -4), o.add(44, 12, 24));
                    for (int x = -4; x <= 44; x++) for (int z = -4; z <= 24; z++) {
                        boolean edge = x == -4 || x == 44 || z == -4 || z == 24;
                        world.setBlockState(o.add(x, 1, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 2; y <= 12; y++) {
                            world.setBlockState(o.add(x, y, z), edge && y <= 3 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                    }
                    f.runtime.createNative(f.source, "Rider");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 4.5, o.getY() + 2, o.getZ() + 10.5, -90, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    // Staged: a saddle, as though found in a chest; the first hotbar slot left empty to mount with.
                    inventory.setStack(1, new ItemStack(Items.SADDLE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    var wild = context.spawnEntity(EntityType.HORSE, new BlockPos(4 + 7, 2, 4 + 10));
                    horse[0] = wild.getUuid();
                })
                .createAndAdd(() -> {
                    // On again and again until it bonds, walked back up to it when it wanders off.
                    var player = f.player();
                    var wild = (HorseEntity) context.getWorld().getEntity(horse[0]);
                    context.assertTrue(wild != null && wild.isAlive(), "The horse there");
                    if (!wild.isTame() && !player.hasVehicle() && context.getTick() - lastAsk[0] >= 30) {
                        lastAsk[0] = context.getTick();
                        if (player.distanceTo(wild) > 2.5) {
                            f.runtime.nativeTaskForFixtures(f.source, f.id, "native_goto", Map.of("x", String.valueOf(wild.getBlockX()),
                                    "y", String.valueOf(wild.getBlockY()), "z", String.valueOf(wild.getBlockZ()), "range", "2"));
                        } else {
                            mounts[0]++;
                            f.runtime.nativeTaskForFixtures(f.source, f.id, "native_ride", Map.of("op", "mount", "target", horse[0].toString(), "slot", "0"));
                        }
                    }
                    context.assertTrue(wild.isTame(), "Tamed by mounting: " + mounts[0] + " mounts so far, temper " + wild.getTemper());
                    KindredMod.LOGGER.info("Horse tamed after {} mounts", mounts[0]);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    if (player.hasVehicle() && context.getTick() - lastAsk[0] >= 10) {
                        lastAsk[0] = context.getTick();
                        f.runtime.nativeTaskForFixtures(f.source, f.id, "native_ride", Map.of("op", "off"));
                    }
                    context.assertTrue(!player.hasVehicle(), "Off it to saddle it");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var tame = (HorseEntity) context.getWorld().getEntity(horse[0]);
                    if (!tame.isSaddled() && context.getTick() - lastAsk[0] >= 30) {
                        lastAsk[0] = context.getTick();
                        if (player.distanceTo(tame) > 2.5) {
                            f.runtime.nativeTaskForFixtures(f.source, f.id, "native_goto", Map.of("x", String.valueOf(tame.getBlockX()),
                                    "y", String.valueOf(tame.getBlockY()), "z", String.valueOf(tame.getBlockZ()), "range", "2"));
                        } else {
                            f.runtime.nativeTaskForFixtures(f.source, f.id, "native_saddle", Map.of("target", horse[0].toString(), "slot", "1"));
                        }
                    }
                    context.assertTrue(tame.isSaddled(), "Saddled");
                    context.assertTrue(player.getInventory().count(Items.SADDLE) == 0, "Its one saddle on the horse: " + player.getInventory().count(Items.SADDLE));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var tame = (HorseEntity) context.getWorld().getEntity(horse[0]);
                    if (!player.hasVehicle() && context.getTick() - lastAsk[0] >= 30) {
                        lastAsk[0] = context.getTick();
                        if (player.distanceTo(tame) > 2.5) {
                            f.runtime.nativeTaskForFixtures(f.source, f.id, "native_goto", Map.of("x", String.valueOf(tame.getBlockX()),
                                    "y", String.valueOf(tame.getBlockY()), "z", String.valueOf(tame.getBlockZ()), "range", "2"));
                        } else {
                            f.runtime.nativeTaskForFixtures(f.source, f.id, "native_ride", Map.of("op", "mount", "target", horse[0].toString(), "slot", "0"));
                        }
                    }
                    context.assertTrue(player.getVehicle() == tame, "On its own horse");
                    lastAsk[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    // A second on, the mount done with: off across the field.
                    var tame = (HorseEntity) context.getWorld().getEntity(horse[0]);
                    context.assertTrue(context.getTick() - lastAsk[0] >= 20, "A second on");
                    startX[0] = tame.getX();
                    lastAsk[0] = context.getTick();
                    context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, f.id, "native_ride", Map.of("op", "go", "x", String.valueOf(o.getX() + 38.5),
                            "z", String.valueOf(o.getZ() + 10.5), "range", "2", "seconds", "30")) == 1, "The ride taken up");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var tame = (HorseEntity) context.getWorld().getEntity(horse[0]);
                    context.assertTrue(player.getVehicle() == tame, "Still riding it");
                    context.assertTrue(tame.getX() >= o.getX() + 36, "Ridden across the field: " + String.format(java.util.Locale.ROOT, "%.1f", tame.getX() - o.getX())
                            + " from the field's edge, " + String.format(java.util.Locale.ROOT, "%.1f", tame.getX() - startX[0]) + " ridden");
                    KindredMod.LOGGER.info("Horse ridden {} blocks in {} ticks", String.format(java.util.Locale.ROOT, "%.1f", tame.getX() - startX[0]),
                            context.getTick() - lastAsk[0]);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
