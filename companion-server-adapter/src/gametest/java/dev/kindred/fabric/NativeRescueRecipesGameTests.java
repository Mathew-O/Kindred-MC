package dev.kindred.fabric;

import dev.kindred.core.rescue.RescueSignature;
import dev.kindred.core.rescue.RescueSlice;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * In plain terms: a way out that worked becomes a rule. A player stuck in the pit is got out by the adviser's plan once,
 * and the server keeps what worked; put in the same kind of pit again, it gets out by that alone, the adviser not asked.
 * A pit that differs (a glass ceiling low over it) is another kind of scene, and the adviser is asked.
 *
 * Technical: F-29d. The pit as F-29's fixtures have it, staged twenty blocks up, its floor placed so that the walk north
 * off the rim crosses into ground the player has not stood in (the watchdog's progress) and the first pillar does not.
 * The second pit is 64 blocks east, the same in every feature. A stub strategist gives the plan and records each ask.
 */
public final class NativeRescueRecipesGameTests implements FabricGameTest {
    private static final List<String> OUT = List.of("pillar 3", "walk north 2");

    /** The pit, its floor at {@code b}, the sky open over it; {@code ceiling} glass three over the floor if asked. */
    private static void pit(ServerWorld world, BlockPos b, boolean ceiling) {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            for (int y = -1; y <= 2; y++) world.setBlockState(b.add(x, y, z), Blocks.OBSIDIAN.getDefaultState());
            for (int y = 3; y <= 6; y++) world.setBlockState(b.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        for (int y = 0; y <= 2; y++) world.setBlockState(b.up(y), Blocks.AIR.getDefaultState());
        if (ceiling) world.setBlockState(b.up(3), Blocks.GLASS.getDefaultState());
    }

    /**
     * Twenty blocks over the player, east {@code east}: its height a multiple of eight and four, so the pillar's three
     * stay in the same eight-block cell of ground; its z a multiple of eight, so the first block north is new ground.
     */
    private static BlockPos base(NativePaidSkillsGameTests.Fixture f, int east) {
        BlockPos p = f.player().getBlockPos().up(20).east(east);
        return new BlockPos(p.getX(), (p.getY() & ~7) + 4, p.getZ() & ~7);
    }

    private static void into(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos b, boolean ceiling) {
        pit(context.getWorld(), b, ceiling);
        context.getWorld().setTimeOfDay(1000);
        var player = f.player();
        player.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
        player.setVelocity(Vec3d.ZERO);
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.GLASS, 8));
        inventory.setStack(1, new ItemStack(Items.BREAD, 5));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
    }

    private static List<String> lines(NativePaidSkillsGameTests.Fixture f) {
        return f.runtime.rescuesForFixtures(f.id);
    }

    /**
     * Once the stub's plan gets it out, the plan's steps are kept as a recipe for the pit; in the second pit the recipe is
     * tried first and gets it out, and the stub is asked nothing more.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueRecipe", tickLimit = 6000)
    public void aRescueThatWorkedBecomesARecipeAndTheSameSceneIsEscapedByItWithNoModelCall(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var stub = new NativeRescueRoundsGameTests.Rounds(List.of(OUT));
        BlockPos[] first = new BlockPos[1], second = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Learner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    first[0] = base(f, 0);
                    second[0] = base(f, 64);
                    into(context, f, first[0], false);
                    f.runtime.strategistForFixtures(stub);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(first[0]), "In the first pit"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> context.assertTrue(lines(f).stream().anyMatch(l -> l.startsWith("rescue recipe kept: ")), "A recipe kept: " + lines(f)))
                .createAndAdd(() -> {
                    context.assertTrue(stub.reports.size() == 1, "Asked once for the first pit: " + stub.reports.size());
                    context.assertTrue(lines(f).contains("rescue spell 1: out after 1 round"), "The first out by the plan: " + lines(f));
                    var book = f.runtime.recipesForFixtures().all();
                    context.assertTrue(book.size() == 1 && book.getFirst().steps().equals(OUT), "The plan kept as it ran: " + book);
                    into(context, f, second[0], false);
                })
                .createAndAdd(() -> context.assertTrue(lines(f).stream().anyMatch(l -> l.equals("rescue spell 2: out by its recipe")),
                        "The second pit escaped by the recipe: " + lines(f)))
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Rescue recipe: {}; book {}", lines(f), f.runtime.recipesForFixtures().all());
                    context.assertTrue(stub.reports.size() == 1, "No model call for the second pit: " + stub.reports.size());
                    context.assertTrue(lines(f).stream().anyMatch(l -> l.startsWith("rescue recipe: spell 2, pillar 3, walk north 2")), "Tried first: " + lines(f));
                    context.assertTrue(lines(f).stream().noneMatch(l -> l.startsWith("rescue asked: spell 2")), "Spell 2 never asked: " + lines(f));
                    var book = f.runtime.recipesForFixtures().all();
                    context.assertTrue(book.size() == 1 && book.getFirst().weight() == 2, "It gained weight: " + book);
                    BlockPos at = f.player().getBlockPos();
                    context.assertTrue(at.getY() >= second[0].getY() + 3 && at.getZ() <= second[0].getZ() - 1, "On the second ledge: " + at.subtract(second[0]).toShortString());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The book holds the pit's recipe; the pit here has glass three over its floor, room for one block overhead, not
     * four: another kind of scene. No recipe is tried and the model is asked.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueRecipeDiffers", tickLimit = 4000)
    public void aSceneThatDiffersInAFeatureDoesNotMatchAndTheModelIsAsked(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var stub = new NativeRescueRoundsGameTests.Rounds(List.of(List.of("wait 1")));
        BlockPos[] b = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Other"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // The pit's signature, as the first fixture's player sees it there (and RescueRecipesTest has it).
                    var pit = new RescueSignature("NSEW", 4, RescueSlice.Kind.SOLID, false, false, false, true, false, true, true, true, 0);
                    f.runtime.recipesForFixtures().learned(pit, OUT, "fixture");
                    b[0] = base(f, 0);
                    into(context, f, b[0], true);
                    f.runtime.strategistForFixtures(stub);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(b[0]), "In the low pit"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> context.assertTrue(!stub.reports.isEmpty(), "The model asked"))
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Rescue recipe differs: {}", lines(f));
                    context.assertTrue(lines(f).stream().noneMatch(l -> l.startsWith("rescue recipe: ")), "No recipe tried: " + lines(f));
                    context.assertTrue(stub.reports.getFirst().contains("- up: 1 block clear over its head, then glass"), "The low ceiling: " + stub.reports.getFirst());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
