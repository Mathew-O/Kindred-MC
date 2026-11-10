package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a player hurt and out of food below ground, at the end of a tunnel with no way back, puts a block up
 * between itself and a zombie after it, and keeps it there while the zombie is about; it climbs on for food some other
 * way or once the zombie is gone, rather than digging its own cover out at once and letting the zombie in.
 *
 * Technical: F-25c4cy. In ReplayGameTests' echo-73670 on #83's CI (run 42583923691) the player, at four health with no
 * food and the way off refused, took cover from a zombie; with the zombie out of sight the goal's next step was the
 * hungry climb, which dug that same block ("Climb toward the surface for food: clear" the cell "Take cover from zombie"
 * had just filled), three times over, and the zombie killed it. Staged: the end of a cut, a step up from it into a passage
 * two high, the cut's other sides obsidian but for a niche one deep, stone over the player's head and obsidian over that, so
 * that from where it stands its only staircase up is the step toward the zombie, whose landing is where its cover goes, as
 * the replay's was (from the niche another goes up into the stone beside the passage);
 * the player hurt and hungry with no food, cobblestone and a stone pickaxe, a zombie set on it from five blocks off along the
 * passage. Once the cover has held for half a minute the zombie is removed, as though it gave up.
 */
public final class NativeCoverClimbGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "coverClimb", tickLimit = 2400)
    public void itKeepsItsCoverFromAZombieAndClimbsForFoodOnceItIsGone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        // Its cover: the first of the cut's cells toward the zombie (x 2 to 5 along z 3, y 2 to 4) filled with cobblestone.
        BlockPos[] cover = new BlockPos[1];
        long[] coveredAt = {-1};
        boolean[] gone = {false};
        int[] startY = {0};
        // Once dug out with the zombie about, the test has failed, whatever comes after.
        String[] failed = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Covered"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    // The player's cell at x 1 (y 2 and 3) at the end of the cut, a step up east (x 2, y 2) into a passage two
                    // high (x 2 to 7, y 3 and 4) where the zombie is. Round the player's cell obsidian, which its stone
                    // pickaxe cannot break, but a niche one deep to the north, the stone over its head, and obsidian over
                    // that: from where it stands its only staircase up is the step east, whose landing is where its cover
                    // from the zombie goes, as the replay's was. From the niche, another goes up east into the stone.
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 6; z++) for (int y = 1; y <= 6; y++) {
                        boolean open = x == 1 && (z == 3 || z == 2) && (y == 2 || y == 3)
                                || x >= 2 && z == 3 && (y == 3 || y == 4);
                        boolean hard = x <= 1 && (y <= 3 || y == 5);
                        context.setBlockState(x, y, z, open ? Blocks.AIR : hard ? Blocks.OBSIDIAN : Blocks.STONE);
                    }
                    var at = context.getAbsolute(new net.minecraft.util.math.Vec3d(1.5, 2, 3.5));
                    player.refreshPositionAndAngles(at.x, at.y, at.z, 90, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.selectedSlot = 0;
                    // Hurt and out of food, in daylight: the goal's hungry climb is what comes after any fight.
                    player.setHealth(4);
                    player.getHungerManager().setFoodLevel(8);
                    context.getWorld().setTimeOfDay(1000);
                    startY[0] = player.getBlockY();
                    zombie[0] = context.spawnMob(net.minecraft.entity.EntityType.ZOMBIE, new net.minecraft.util.math.Vec3d(6.5, 3, 3.5));
                    zombie[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    context.assertTrue(failed[0] == null, failed[0] == null ? "" : failed[0]);
                    context.assertTrue(player != null && player.isAlive(), "Its body, alive; cover " + cover[0] + ", "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    if (cover[0] == null) {
                        for (int x = 2; x <= 5 && cover[0] == null; x++) for (int y = 3; y <= 4 && cover[0] == null; y++) {
                            BlockPos cell = context.getAbsolutePos(new BlockPos(x, y, 3));
                            if (world.getBlockState(cell).isOf(Blocks.COBBLESTONE)) {
                                cover[0] = cell;
                                coveredAt[0] = world.getTime();
                            }
                        }
                    }
                    if (cover[0] != null && !gone[0]) {
                        boolean standing = !world.getBlockState(cover[0]).isAir();
                        boolean near = zombie[0].isAlive() && zombie[0].squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(cover[0])) <= 6 * 6;
                        if (!standing && near) {
                            failed[0] = "Its cover at " + cover[0].toShortString() + " dug out with the zombie "
                                    + String.format("%.1f", Math.sqrt(zombie[0].squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(cover[0]))))
                                    + " blocks off; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                            context.assertTrue(false, failed[0]);
                        }
                        // Held for half a minute: the zombie removed, as though it gave up (staged).
                        if (world.getTime() - coveredAt[0] >= 600) {
                            zombie[0].discard();
                            gone[0] = true;
                        }
                    }
                    context.assertTrue(gone[0], "Cover " + (cover[0] == null ? "not yet taken" : "held at " + cover[0].toShortString()) + "; "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    // With the zombie gone, on with the climb for food: up from where it stood.
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Its body, alive");
                    context.assertTrue(player.getBlockY() > startY[0], "Climbing on once the zombie is gone: at " + player.getBlockPos().toShortString()
                            + "; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
