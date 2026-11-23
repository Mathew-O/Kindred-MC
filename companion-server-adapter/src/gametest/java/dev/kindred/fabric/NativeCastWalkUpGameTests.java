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
 * The cast's walk up to its lava, after a fill refused from where it stands, does not come back from that same cell
 * (F-25c4dr). In E-40 iteration 30's first batch (RUNNER 2's read), "Cast: walk up to the lava" was issued again and again
 * from one cell. That walk is a goto of range 1.5 toward the source. A source one across and one down is √2 from the feet,
 * so the walk counts as arrived where it began and completes without a step. The cell it was refused from stays
 * {@code castFailedFrom}: only a fill or a cast that completes clears it. So the next tick issues the same walk up, and the
 * fill is never tried again.
 */
public final class NativeCastWalkUpGameTests implements FabricGameTest {
    /** How long it has to fill its bucket with lava; and the most walks up that end where they began allowed meanwhile. */
    private static final int BUDGET = 1600, MOST_IN_PLACE = 2;
    /** The most of a failure's text kept: a failed test's text goes onto one lectern page, which holds 1,024 characters. */
    private static final int MESSAGE_MOST = 900;

    private static String within(String message) {
        return message.length() <= MESSAGE_MOST ? message : message.substring(0, MESSAGE_MOST - 3) + "...";
    }

    /**
     * In plain terms: an AI player whose bucket would not take the lava from where it stands, though the lava is right beside
     * its feet, does something else about it, such as open the rock over the lava, rather than stand there "walking up" to
     * it without a step.
     *
     * Technical: F-25c4dr. STAGED:
     * - a room of stone with no sky, five across and three high, lit by glowstone;
     * - one lava source in its floor, one east of its feet and one down; stone round it but for its top;
     * - the cast's kit: an iron pickaxe, a stone sword, an iron chestplate, bread, cobblestone, sticks, flint and steel, a
     *   water bucket and an empty bucket;
     * - the Nether portal goal, which casts its frame with no diamond pickaxe;
     * - the source seen by the campaign's own sweep from where it stands, while its top was open;
     * - then a block of stone set on the source's top, as though dropped there after it was seen. Its bucket's sight line to
     *   the lava is then shut, so the fill is refused from where it stands, the lava within 1.5 of its feet.
     *
     * Fails if more than {@value #MOST_IN_PLACE} walks up to the lava complete with its feet where they began, or if within
     * {@value #BUDGET} ticks it has no bucket of lava. Passes with its bucket of lava, alive.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "castWalkUp", tickLimit = BUDGET + 800)
    public void aWalkUpToLavaBesideItsFeetDoesNotComeBackFromWhereItBegan(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 22, 40));
        BlockPos lavaAt = o.add(1, -1, 0);
        int[] inPlace = {0}, walkUps = {0}, watched = {0};
        BlockPos[] lastEnd = {null};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "WalkUp"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-4, -3, -4), o.add(4, 4, 4));
                    for (BlockPos cell : BlockPos.iterate(o.add(-4, -3, -4), o.add(4, 4, 4))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
                    for (BlockPos cell : BlockPos.iterate(o.add(-2, 0, -2), o.add(2, 2, 2))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
                    for (int[] g : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) world.setBlockState(o.add(g[0], 2, g[1]), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(lavaAt, Blocks.LAVA.getDefaultState());
                    world.setTimeOfDay(6000);
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
                    // Facing east, down at the lava beside its feet.
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 60);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isOnGround() && player.getBlockPos().equals(o), "In its room: at " + player.getBlockPos().subtract(o).toShortString());
                    // The source seen as a player sees it from where it stands, while its top is open.
                    var knowledge = f.runtime.campaignForTest(f.id).knowledge();
                    for (int i = 0; i < 400 && knowledge.count(WorldKnowledge.Kind.LAVA) == 0; i++) knowledge.scan(player);
                    context.assertTrue(knowledge.count(WorldKnowledge.Kind.LAVA) > 0, "It saw the lava beside its feet");
                    // STAGED: then its top shut, by a block of stone set over it.
                    context.getWorld().setBlockState(lavaAt.up(), Blocks.STONE.getDefaultState());
                    f.runtime.observeRun(new RunObserver() {
                        @Override
                        public void ended(String p, String skill, String state, String label) {
                            // Where its feet were when its last task before this one ended: a walk up that ends there began there.
                            BlockPos feet = f.player() == null ? null : f.player().getBlockPos().toImmutable();
                            if ("Cast: walk up to the lava".equals(label)) {
                                walkUps[0]++;
                                if ("COMPLETED".equals(state) && feet != null && feet.equals(lastEnd[0])) inPlace[0]++;
                            }
                            lastEnd[0] = feet;
                        }
                    });
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), within("The player died; " + seen));
                    String progress = f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                    if (seen.length() < 700 && !seen.toString().endsWith(progress + "; ")) seen.append(progress).append("; ");
                    if (inPlace[0] > MOST_IN_PLACE) {
                        throw new net.minecraft.test.GameTestException(within("Its walk up to the lava completed where it began " + inPlace[0] + " times ("
                                + walkUps[0] + " walks up) in " + watched[0] + " ticks, at " + player.getBlockPos().subtract(o).toShortString() + ": " + seen));
                    }
                    boolean lava = player.getInventory().count(Items.LAVA_BUCKET) > 0;
                    context.assertTrue(++watched[0] <= BUDGET || lava, within("No bucket of lava in " + BUDGET + " ticks (" + walkUps[0] + " walks up, "
                            + inPlace[0] + " where they began); at " + player.getBlockPos().subtract(o).toShortString() + ": " + seen));
                    context.assertTrue(lava, "Not yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cast walk up: {} walks up, {} where they began, lava in hand after {} ticks; {}", walkUps[0], inPlace[0], watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
