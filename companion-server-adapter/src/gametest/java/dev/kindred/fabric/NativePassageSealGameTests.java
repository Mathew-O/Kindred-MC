package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a player at the dead end of a dark tunnel underground, with zombies coming down it, seals the tunnel
 * with two blocks when two or more come and waits behind them, taking no hits, and goes on once they have gone; with one
 * zombie it fights it as before and seals nothing.
 *
 * Technical: F-25c4dc. In the kept replay kindred-43985-707 Kindred, at 17.5 health with three zombies after it in a
 * dead end underground, met them one at a time and was killed. Staged: a stone tunnel one wide and two high along x at
 * z 1 (x 1 to 11, y 2 and 3) closed at both ends, stone all round and over it, so it is dark at any hour; daytime is set
 * on purpose. The player at its west end with a stone sword in hand and cobblestone, at full health and fed, the goal
 * begun; zombies set on it from the tunnel's far end. With two, once the seal has held for half a minute they are removed,
 * as though they gave up.
 */
public final class NativePassageSealGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passageSeal", tickLimit = 2400)
    public void twoZombiesDownADarkTunnelAreSealedOutAndWaitedOut(TestContext context) {
        run(context, 2);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passageSeal", tickLimit = 2400)
    public void oneZombieDownADarkTunnelIsFoughtNotSealedOut(TestContext context) {
        run(context, 1);
    }

    private static void run(TestContext context, int count) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        ZombieEntity[] zombies = new ZombieEntity[count];
        boolean[] sealSeen = {false};
        long[] sealedAt = {-1};
        boolean[] gone = {false};
        String[] failed = {null};
        float[] lowest = {20};
        BlockPos foot = new BlockPos(2, 2, 1), head = new BlockPos(2, 3, 1);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, count == 2 ? "Sealer" : "Fighter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    for (int x = 0; x <= 12; x++) for (int z = 0; z <= 2; z++) for (int y = 1; y <= 5; y++) {
                        boolean open = z == 1 && x >= 1 && x <= 11 && (y == 2 || y == 3);
                        context.setBlockState(x, y, z, open ? Blocks.AIR : Blocks.STONE);
                    }
                    var at = context.getAbsolute(new Vec3d(1.5, 2, 1.5));
                    player.refreshPositionAndAngles(at.x, at.y, at.z, -90, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
                    inventory.selectedSlot = 0;
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setTimeOfDay(1000);
                    for (int i = 0; i < count; i++) {
                        zombies[i] = context.spawnMob(EntityType.ZOMBIE, new Vec3d(10.5 - i, 2, 1.5));
                        zombies[i].setBaby(false);
                        zombies[i].setTarget(player);
                    }
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    context.assertTrue(failed[0] == null, failed[0] == null ? "" : failed[0]);
                    context.assertTrue(player != null && player.isAlive(), "Its body, alive; " + progress(f));
                    lowest[0] = Math.min(lowest[0], player.getHealth());
                    String progress = progress(f);
                    if (progress.contains("seal the passage")) sealSeen[0] = true;
                    boolean sealed = world.getBlockState(context.getAbsolutePos(foot)).isOf(Blocks.COBBLESTONE)
                            && world.getBlockState(context.getAbsolutePos(head)).isOf(Blocks.COBBLESTONE);
                    if (count == 1) {
                        if (sealSeen[0]) {
                            failed[0] = "One zombie sealed out rather than fought: " + progress;
                            context.assertTrue(false, failed[0]);
                        }
                        context.assertTrue(!zombies[0].isAlive(), "The zombie fought and killed; health " + player.getHealth() + "; " + progress);
                        return;
                    }
                    if (lowest[0] < 20) {
                        failed[0] = "Hit by a zombie, at " + lowest[0] + " health; sealed " + sealed + "; " + progress;
                        context.assertTrue(false, failed[0]);
                    }
                    if (sealed && sealedAt[0] < 0) sealedAt[0] = world.getTime();
                    if (sealedAt[0] >= 0 && !sealed) {
                        failed[0] = "Its seal opened with the zombies about: " + progress;
                        context.assertTrue(false, failed[0]);
                    }
                    // Held for half a minute: the zombies removed, as though they gave up (staged).
                    if (sealedAt[0] >= 0 && world.getTime() - sealedAt[0] >= 600) {
                        for (ZombieEntity zombie : zombies) zombie.discard();
                        gone[0] = true;
                    }
                    context.assertTrue(gone[0], "Sealed " + sealed + ", the seal seen " + sealSeen[0] + "; " + progress);
                })
                .createAndAdd(() -> {
                    // With them gone, the seal is let go and the goal goes on: no longer waiting behind it.
                    if (count == 2) context.assertTrue(!progress(f).contains("wait behind the seal"), "Let go of the seal: " + progress(f));
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static String progress(NativePaidSkillsGameTests.Fixture f) {
        return f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
    }
}
