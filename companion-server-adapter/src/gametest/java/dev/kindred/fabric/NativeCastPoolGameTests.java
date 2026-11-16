package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The cast's lava, in a pool no walk reaches, is dug toward from ground the staircase can take, never walked for again and
 * again (F-25c4dj). In E-40 iteration 23 (`e40a23-3115b1c`, #352) on 505 Sol, digging toward the pool it had seen at y −55,
 * it came out over a cave at y −40; its staircase gave nothing, and the cast's walk to the lava was offered every tick,
 * each a different source of the pool: 781 refusals in 30 s and 565 in 17 s ("No walkable route toward −52, −55, 20"),
 * from 08:47:50 (RUNNER 2's {@code claude/corpus-data-e40a23-1115}).
 */
public final class NativeCastPoolGameTests implements FabricGameTest {
    /** How long it has to fill its bucket with lava; and the most refused walks to the lava allowed meanwhile. */
    private static final int BUDGET = 2400, MOST_REFUSED = 3;

    /**
     * Rock round {@code o} with no sky: its alcove (its feet's cell and the one over them), walled but for the east, where it
     * opens over a cavern; the cavern runs under the alcove on every side, so its floor is a shelf one block thick, and a
     * drop of thirteen is under every step a staircase from it would take; on the cavern's floor, east of it and fourteen
     * below its feet, a pool of lava five across, in sight from the alcove. Glowstone in the cavern, so it is lit.
     */
    private static void cavern(TestContext context, BlockPos o) {
        cavern(context, o, 0);
    }

    /** As {@link #cavern(TestContext, BlockPos)}, the pool moved {@code poolSouth} blocks south (z+). */
    private static void cavern(TestContext context, BlockPos o, int poolSouth) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-12, -18, -12), o.add(14, 5, 12));
        for (BlockPos cell : BlockPos.iterate(o.add(-12, -18, -12), o.add(14, 5, 12))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
        for (BlockPos cell : BlockPos.iterate(o.add(-8, -14, -8), o.add(10, -2, 8))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
        for (BlockPos cell : BlockPos.iterate(o.add(3, -15, -2 + poolSouth), o.add(7, -15, 2 + poolSouth))) world.setBlockState(cell, Blocks.LAVA.getDefaultState());
        for (int[] g : new int[][] {{-8, -8}, {10, -8}, {-8, 8}, {10, 8}}) world.setBlockState(o.add(g[0], -2, g[1]), Blocks.GLOWSTONE.getDefaultState());
        world.setBlockState(o, Blocks.AIR.getDefaultState());
        world.setBlockState(o.up(), Blocks.AIR.getDefaultState());
        // The alcove's east side open over the cavern: east of it, from the shelf's level to its head, all air.
        for (BlockPos cell : BlockPos.iterate(o.add(1, -1, -2), o.add(10, 1, 2))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
    }

    /**
     * In plain terms: an AI player that needs a bucket of lava from a pool it can see far below, with no way to walk down
     * to it, digs its way toward the pool from somewhere it can, rather than try to walk there over and over every split
     * second.
     *
     * Technical: F-25c4dj, as e40a23's 505 Sol (above). STAGED: its alcove on a shelf over a cavern, open east over it, the
     * pool on the cavern's floor east of it and fourteen below (above); the cast's kit (an iron pickaxe, a stone sword, an iron
     * chestplate, bread, cobblestone, sticks, flint and steel, a water bucket and an empty bucket); the Nether portal goal,
     * which casts its frame with no diamond pickaxe; the pool seen by the campaign's own sweep from where it stands. Fails if
     * more than {@value #MOST_REFUSED} walks to the lava are refused, or if within {@value #BUDGET} ticks it has no bucket of
     * lava. Passes with its bucket of lava, alive. (The lead's bar was lava or a dig toward it; with the fix it fills its
     * bucket after some 1,050 ticks, so the bar is the lava.)
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "castPool", tickLimit = BUDGET + 800)
    public void aPoolNoWalkReachesIsDugTowardNotWalkedForEveryTick(TestContext context) {
        cast(context, 0);
    }

    /**
     * In plain terms: the same, with the pool a block further south, so that the lava it first sets out for lies on the
     * south side of the alcove's line; it still digs its way down to it in time.
     *
     * Technical: F-25c4dj1. In this class's test above on #394's CI (run 43842018263), its dig set out for the pool's
     * source at (+5, −15, +1) from the alcove, one of two as near, and tunnelled along its own level south and east over the
     * cavern for 315 steps with no lava in 2,400 ticks; alone it set out for (−1) and filled its bucket in some 1,050. The
     * tie is broken by where the suite places the test. STAGED as the test above, the pool moved one block south, so the
     * source south of the line is the one it sets out for. Fails and passes as the test above.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "castPoolSouth", tickLimit = BUDGET + 800)
    public void aPoolNoWalkReachesSouthOfItsLineIsDugTowardInTime(TestContext context) {
        cast(context, 2);
    }

    private void cast(TestContext context, int poolSouth) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 22, 40));
        int[] refused = {0}, dug = {0}, watched = {0};
        StringBuilder seen = new StringBuilder();
        // Where its feet stood, relative to the alcove, each time they moved: for a failure's message.
        java.util.ArrayDeque<String> moves = new java.util.ArrayDeque<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Caster"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    cavern(context, o, poolSouth);
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.STICK, 4));
                    inventory.setStack(6, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.BUCKET));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // Facing east, out over the cavern and down at the pool.
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 60);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isOnGround() && player.getBlockPos().equals(o), "In its alcove: at " + player.getBlockPos().subtract(o).toShortString());
                    // The pool seen as a player sees it from where it stands: the campaign's own sweep, sight lines and all.
                    var knowledge = f.runtime.campaignForTest(f.id).knowledge();
                    for (int i = 0; i < 400 && knowledge.count(WorldKnowledge.Kind.LAVA) == 0; i++) knowledge.scan(player);
                    context.assertTrue(knowledge.count(WorldKnowledge.Kind.LAVA) > 0, "It saw the pool from its alcove");
                    f.runtime.observeRun(new RunObserver() {
                        @Override
                        public void ended(String p, String skill, String state, String label) {
                            if (label == null) return;
                            if ("native_goto".equals(skill) && "BLOCKED".equals(state) && label.equals("Cast: walk to the lava")) refused[0]++;
                            if ("COMPLETED".equals(state) && label.startsWith("Cast: dig toward the lava")) dug[0]++;
                        }
                    });
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    String progress = f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                    if (seen.length() < 1200 && !seen.toString().endsWith(progress + "; ")) seen.append(progress).append("; ");
                    if (refused[0] > MOST_REFUSED) {
                        throw new net.minecraft.test.GameTestException("Its walk to the lava refused " + refused[0] + " times in " + watched[0] + " ticks: " + seen);
                    }
                    String at = player.getBlockPos().subtract(o).toShortString() + " @" + watched[0];
                    if (moves.isEmpty() || !moves.peekLast().startsWith(player.getBlockPos().subtract(o).toShortString() + " @")) {
                        moves.addLast(at);
                        if (moves.size() > 40) moves.removeFirst();
                    }
                    boolean lava = player.getInventory().count(Items.LAVA_BUCKET) > 0;
                    context.assertTrue(++watched[0] <= BUDGET || lava, "No bucket of lava in " + BUDGET + " ticks (" + dug[0] + " dig steps toward it); its feet's last moves "
                            + moves + ": " + seen);
                    context.assertTrue(lava, "Not yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cast pool (pool " + poolSouth + " south): {} refused walks, {} dig steps toward the lava, lava in hand {}, after {} ticks; {}", refused[0], dug[0],
                            f.player().getInventory().count(Items.LAVA_BUCKET) > 0, watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
