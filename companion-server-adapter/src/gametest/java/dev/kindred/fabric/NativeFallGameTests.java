package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A long fall broken with a water bucket (E-07d), as a player knocked off a pillar would break it: the native player,
 * idle, is put twenty-two blocks above stone, falls, pours its water on the block it is about to land on, lands in it
 * unhurt and scoops it back up. The control falls twelve blocks with no bucket and is hurt as vanilla reckons it. Both
 * drop only after vanilla's sixty ticks of grace for a player that has just joined, in which it takes no fall damage.
 */
public final class NativeFallGameTests implements FabricGameTest {
    /** Ticks after joining past which vanilla's join invulnerability (sixty ticks) is over. */
    private static final int JOINED = 80;

    private static void shaft(TestContext context) {
        var world = context.getWorld();
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
            for (int y = 2; y <= 28; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "fallClutch", tickLimit = 600)
    public void aLongFallIsBrokenWithWaterAndTheWaterTakenBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] dropped = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Faller"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                // Vanilla spares a player that has just joined from harm for sixty ticks: the fall comes after that.
                .createAndAdd(() -> context.assertTrue(f.player().age > JOINED, "Past the join's sixty ticks of grace: " + f.player().age))
                .createAndAdd(() -> {
                    shaft(context);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 4));
                    inventory.setStack(3, new ItemStack(Items.WATER_BUCKET));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    player.refreshPositionAndAngles(o.getX() + 3.5, o.getY() + 24, o.getZ() + 3.5, 0, 0);
                    player.fallDistance = 0;
                    dropped[0] = true;
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(player.isOnGround() && activity.work().startsWith("Completed native_clutch"),
                            "Down and the clutch done: " + activity + " " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    KindredMod.LOGGER.info("Faller: landed at {} with health {}; {}", player.getBlockPos().toShortString(), player.getHealth(), f.runtime.nativeLastResult(f.id));
                    context.assertTrue(player.isAlive() && player.getHealth() >= 19.5, "Unhurt by a twenty-two block fall: " + player.getHealth());
                    context.assertTrue(player.getInventory().count(Items.WATER_BUCKET) == 1 && player.getInventory().count(Items.BUCKET) == 0,
                            "The water taken back into its bucket");
                    boolean left = false;
                    for (BlockPos pos : BlockPos.iterate(o.add(0, 2, 0), o.add(7, 3, 7))) left |= context.getWorld().getFluidState(pos).isIn(FluidTags.WATER);
                    context.assertFalse(left, "No water left behind");
                    context.assertTrue(record != null && record.kind().equals("native_clutch") && record.state() == ActionRecord.State.VERIFIED,
                            "A verified clutch: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "fallBare", tickLimit = 600)
    public void withoutABucketAFallHurtsAsVanillaSays(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        float[] fell = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Tumbler"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                // Vanilla spares a player that has just joined from harm for sixty ticks: the fall comes after that.
                .createAndAdd(() -> context.assertTrue(f.player().age > JOINED, "Past the join's sixty ticks of grace: " + f.player().age))
                .createAndAdd(() -> {
                    shaft(context);
                    var player = f.player();
                    player.getInventory().clear();
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    player.refreshPositionAndAngles(o.getX() + 3.5, o.getY() + 14, o.getZ() + 3.5, 0, 0);
                    player.fallDistance = 0;
                })
                .createAndAdd(() -> {
                    fell[0] = Math.max(fell[0], f.player().fallDistance);
                    context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == context.getAbsolutePos(BlockPos.ORIGIN).getY() + 2, "Landed");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    // Vanilla's point a block past three, of the fall it counted (from where the drop began to move).
                    int hurt = dev.kindred.core.tactics.FallClutch.damage(fell[0]);
                    KindredMod.LOGGER.info("Tumbler: a fall of {} blocks left health {}, {} points as vanilla reckons it",
                            String.format(java.util.Locale.ROOT, "%.2f", fell[0]), player.getHealth(), hurt);
                    context.assertTrue(fell[0] > 10 && hurt >= 7, "A long fall counted: " + fell[0]);
                    context.assertTrue(Math.abs(player.getHealth() - (20 - hurt)) < 0.5, "Hurt as vanilla reckons a fall: " + player.getHealth() + " for " + hurt);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Stranded on a pillar's top in the End's way (E-39f, E-07d) with a water bucket and no pearls: it steps off the
     * edge onto end stone sixteen blocks down and breaks the fall with water, keeping its pearls for the eyes. The
     * route's drop starts the fall; the clutch takes over from it mid-air.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "fallStepOff", tickLimit = 900)
    public void offAPillarTheFallIsBrokenWithWaterNotAPearl(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] landing = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stepper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> context.assertTrue(f.player().age > JOINED, "Past the join's sixty ticks of grace: " + f.player().age))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 12; x++) for (int z = -8; z <= 12; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.END_STONE.getDefaultState());
                        for (int y = 2; y <= 22; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) for (int y = 2; y <= 17; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.OBSIDIAN.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(2, new ItemStack(Items.WATER_BUCKET));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    player.refreshPositionAndAngles(o.getX() + 3.5, o.getY() + 18, o.getZ() + 3.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == context.getAbsolutePos(BlockPos.ORIGIN).getY() + 18, "On the pillar's top"))
                .createAndAdd(() -> {
                    var off = EndFightLeg.stepOff(f.player(), pos -> false);
                    context.assertTrue(off.isPresent(), "End stone to step down onto");
                    landing[0] = off.get().getKey();
                    KindredMod.LOGGER.info("Stepper: off the pillar to {}, {} blocks down", landing[0].toShortString(), off.get().getValue());
                    context.assertTrue(f.runtime.nativeGotoDropping(f.source, f.id, landing[0], 0.8, off.get().getValue()) == 1, "Step off requested");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(player.isOnGround() && player.getBlockY() == context.getAbsolutePos(BlockPos.ORIGIN).getY() + 2
                            && activity.work().startsWith("Completed native_clutch"), "Down by the clutch: " + activity);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    KindredMod.LOGGER.info("Stepper: down at {} with health {}; {}", player.getBlockPos().toShortString(), player.getHealth(), f.runtime.nativeLastResult(f.id));
                    context.assertTrue(player.getHealth() >= 19.5, "Unhurt by a sixteen block drop: " + player.getHealth());
                    context.assertTrue(player.getInventory().count(Items.WATER_BUCKET) == 1, "The water taken back into its bucket");
                    context.assertTrue(record != null && record.kind().equals("native_clutch") && record.state() == ActionRecord.State.VERIFIED,
                            "A verified clutch: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
