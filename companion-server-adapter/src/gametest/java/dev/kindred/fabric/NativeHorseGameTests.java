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

    /**
     * A horse of its own (M-01a): with its cabin standing, nothing more to build yet and no animals to keep, it sees a
     * wild horse, tames it by getting on it until it bonds, saddles it with the saddle it found, and keeps it with its
     * bases. Staged as the own-ranch fixtures are: the ground laid, its cabin set and recorded as its own, stone tools
     * given, the saddle in its pack as though found, a horse put in sight. Nothing tells it to take a horse.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "ownHorse", tickLimit = 6000)
    public void withItsCabinStandingItTamesAWildHorseItSeesAndSaddlesIt(TestContext context) {
        ownHorse(context, false);
    }

    /**
     * The same with each step's durable record slow to be kept, as on a busy runner: on #82's CI (run 42566194927) the
     * saddle's swap, planned while it waited on its record, was counted as tried four times and the saddle given up
     * ("its saddle could not be brought to hand") as the swap completed; its mounts likewise were counted 12 for 3 rides.
     * Staged: the record of each swap and ride kept 1.5 seconds late.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "ownHorseSlow", tickLimit = 6000)
    public void withItsStepsSlowToBeRecordedItStillSaddlesTheHorseItTames(TestContext context) {
        ownHorse(context, true);
    }

    private static void ownHorse(TestContext context, boolean slow) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> !slow ? writer : new dev.kindred.core.ActionAdmission.Writer() {
            @Override public java.util.concurrent.CompletableFuture<dev.kindred.core.ActionRecord> prepare(dev.kindred.core.ActionRecord intent) {
                if (!"native_swap".equals(intent.kind()) && !"native_ride".equals(intent.kind())) return writer.prepare(intent);
                return writer.prepare(intent).thenCompose(kept -> java.util.concurrent.CompletableFuture.supplyAsync(() -> kept,
                        java.util.concurrent.CompletableFuture.delayedExecutor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)));
            }
            @Override public java.util.concurrent.CompletableFuture<dev.kindred.core.ActionRecord> transition(dev.kindred.core.ActionRecord next, long expectedRevision) {
                return writer.transition(next, expectedRevision);
            }
        }, true, 5500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        BlockPos cabinAt = home.add(-3, 0, -3);
        UUID[] horse = new UUID[1];
        // The mounts made, seen tick by tick, and the most its taming counted: a mount counted that was not made is a try
        // lost (24 counted for 4 made on #87's CI, run 42584555878, the horse given up unbonded) (F-22c38).
        int[] made = {0, 0};
        boolean[] on = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wrangler"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, trunks);
                    world.setTimeOfDay(1000);
                    var cabin = dev.kindred.core.construction.Houses.of(dev.kindred.core.construction.Houses.Stage.CABIN, "oak", "cobblestone");
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) {
                        try {
                            world.setBlockState(BuildRunner.blockPos(b.at()), net.minecraft.command.argument.BlockArgumentParser.block(
                                    net.minecraft.registry.Registries.BLOCK.getReadOnlyWrapper(), b.spec().toString(), false).blockState());
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                            throw new RuntimeException(failure);
                        }
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    ItemStack[] held = {new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_LOG, 14),
                            new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    // Staged: a saddle in its pack, as though found in a chest.
                    inventory.setStack(20, new ItemStack(Items.SADDLE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = home.add(8, 0, 0);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    // Staged: a wild horse in sight, east of its cabin.
                    horse[0] = context.spawnEntity(EntityType.HORSE, home.add(12, 0, 4).subtract(context.getAbsolutePos(BlockPos.ORIGIN))).getUuid();
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.stageBaseForTest(f.id,
                        new PlayerMemoryStore.Base("cabin", "minecraft:overworld", cabinAt.getX(), cabinAt.getY(), cabinAt.getZ(), true)), "Its cabin recorded"))
                .createAndAdd(() -> context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on"))
                .createAndAdd(() -> {
                    var wild = (HorseEntity) context.getWorld().getEntity(horse[0]);
                    boolean riding = wild != null && f.player().getVehicle() == wild;
                    if (riding && !on[0]) made[0]++;
                    on[0] = riding;
                    made[1] = Math.max(made[1], f.runtime.stableMountsForTest(f.id));
                    var kept = f.runtime.ownHorse(f.id);
                    context.assertTrue(kept.isPresent(), "A horse of its own kept: " + f.runtime.ranchNote(f.id) + " / " + f.runtime.baseNote(f.id)
                            + (wild == null ? "" : "; tame " + wild.isTame() + ", temper " + wild.getTemper()) + "; mounts counted " + made[1] + ", made " + made[0]);
                })
                .createAndAdd(() -> context.assertTrue(made[1] <= made[0] + 1, "Each mount counted made: counted " + made[1] + ", made " + made[0]))
                .createAndAdd(() -> {
                    var player = f.player();
                    var kept = (HorseEntity) context.getWorld().getEntity(horse[0]);
                    context.assertTrue(f.runtime.ownHorse(f.id).orElseThrow().equals(horse[0].toString()), "The one it saw");
                    context.assertTrue(kept.isTame() && player.getUuid().equals(kept.getOwnerUuid()), "Tamed to it");
                    context.assertTrue(kept.isSaddled() && player.getInventory().count(Items.SADDLE) == 0, "Saddled with its one saddle");
                    context.assertTrue(!player.hasVehicle(), "Off it");
                    KindredMod.LOGGER.info("Own horse: {}, tame {}, saddled {}; {}", horse[0], kept.isTame(), kept.isSaddled(), f.runtime.ranchNote(f.id));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
