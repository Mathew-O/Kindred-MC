package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Respawned with nothing in a cave, the route's own climb comes before flint on the way (F-25c4di). In E-40 rung c
 * (`e40c4-b23acc0`, #334) on 303 Kindred, 23 deaths from tick 194,201 to 237,417: each time it came back at its bed at
 * 314, 51, 172, underground in a cave of mobs, with nothing, and its first steps were "on the way, for what comes later -
 * Walk to gravel at 306, 51, 159" (no walkable route), then "Tunnel for gravel: advance" and "step down" for hundreds of
 * steps, deeper into the caves (the console is on {@code claude/corpus-data-e40c4-1115}).
 */
public final class NativeRespawnCaveGameTests implements FabricGameTest {
    /** How long it has to take the climb, and to show it does not tunnel for gravel. */
    private static final int BUDGET = 1200;

    /**
     * Its pocket at {@code o}, two high under rock, open east along a ledge to a chasm four wide and eleven deep; across it
     * a ledge with two blocks of gravel nine and ten east of its feet, in sight and with no walkable route to them; rock all
     * round and over it, no sky.
     */
    private static void cave(TestContext context, BlockPos o) {
        var world = context.getWorld();
        for (int x = -6; x <= 14; x++) for (int z = -4; z <= 4; z++) for (int y = -12; y <= 5; y++) {
            world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
        }
        for (int x = 0; x <= 3; x++) for (int y = 0; y <= 1; y++) world.setBlockState(o.add(x, y, 0), Blocks.AIR.getDefaultState());
        for (int x = 4; x <= 7; x++) for (int z = -2; z <= 2; z++) for (int y = -11; y <= 2; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        for (int x = 8; x <= 10; x++) for (int y = 0; y <= 1; y++) world.setBlockState(o.add(x, y, 0), Blocks.AIR.getDefaultState());
        world.setBlockState(o.add(9, 0, 0), Blocks.GRAVEL.getDefaultState());
        world.setBlockState(o.add(10, 0, 0), Blocks.GRAVEL.getDefaultState());
    }

    /**
     * In plain terms: a player that has just come back to life in a cave full of danger, with nothing on it, makes for the
     * surface, rather than tunnel off through the cave after a bit of gravel it saw for later.
     *
     * Technical: F-25c4di, e40c4's seed 303 Kindred (above). STAGED: its pocket under rock with a ledge, a chasm and, across
     * it, gravel within twelve with no route to it (above); the player with nothing, no armour, as respawned; daylight held;
     * the dragon goal, as the run's (rung c, goal=dragon; flint is wanted for the End's arrows), the fight on for this
     * fixture's runtime; the gravel seen by the campaign's own sweep from where it stands. Fails as soon as the goal's step is
     * "on the way, for what comes later - Tunnel for gravel" (or an explore for gravel), the run's loop. Passes once its step
     * is the climb toward the surface, within {@value #BUDGET} ticks, alive.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "respawnCave", tickLimit = BUDGET + 800)
    public void respawnedWithNothingInACaveItClimbsRatherThanTunnelForGravelOnTheWay(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 14, 40));
        int[] watched = new int[1];
        String[] last = {""};
        boolean[] walkedForGravel = {false};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Respawned"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, o.add(-6, -12, -4), o.add(14, 5, 4));
                    cave(context, o);
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    player.getInventory().clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // Facing east, along its ledge and across the chasm to the gravel.
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 10);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isOnGround() && player.getBlockPos().equals(o), "In its pocket: at " + player.getBlockPos().subtract(o).toShortString());
                    // The gravel seen as a player sees it from where it stands: the campaign's own sweep, sight lines and all.
                    var knowledge = f.runtime.campaignForTest(f.id).knowledge();
                    for (int i = 0; i < 400 && knowledge.count(WorldKnowledge.Kind.GRAVEL) == 0; i++) knowledge.scan(player);
                    context.assertTrue(knowledge.count(WorldKnowledge.Kind.GRAVEL) > 0, "It saw the gravel across the chasm");
                })
                .createAndAdd(() -> {
                    // The run's goal, the dragon (rung c, goal=dragon): flint is wanted for the End's arrows.
                    f.runtime.setDragonFightForTest(true);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.DRAGON) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!progress.equals(last[0])) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    last[0] = progress;
                    watched[0]++;
                    if (progress.contains("Walk to gravel")) walkedForGravel[0] = true;
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    if (progress.contains("for what comes later") && (progress.contains("Tunnel for gravel") || progress.contains("gravel") && progress.contains("Explore"))) {
                        throw new net.minecraft.test.GameTestException("On the way, it tunnels off for gravel: " + tail);
                    }
                    context.assertTrue(watched[0] <= BUDGET, "No climb toward the surface after " + BUDGET + " ticks: " + tail);
                    context.assertTrue(progress.contains("Climb toward the surface"), "Not climbing yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Respawn cave: the climb toward the surface after {} ticks, a walk to the gravel tried {}; {}", watched[0],
                            walkedForGravel[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
