package dev.kindred.fabric;

import dev.kindred.core.construction.BillOfMaterials;
import dev.kindred.core.construction.BuildSite;
import dev.kindred.core.construction.BuildStyle;
import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.TreeMap;

/**
 * A build grown from a seed block (E-29a). Given a cherry log as its seed, a player with stone tools grows its cabin in
 * cherry, on the plan and facing its world draws, not the first plan every world would get, and builds it exact. With
 * no seed it grows it in the wood it has to hand.
 */
public final class NativeSeedBuildGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "seed-build", tickLimit = 30000)
    public void givenACherrySeedItGrowsACherryCabinOfItsWorldsOwnPlanAndBuildsItExact(TestContext context) {
        grows(context, "cherry", "minecraft:cherry_log", null);
    }

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "seed-build-at-hand", tickLimit = 30000)
    public void withNoSeedItGrowsItsCabinInTheWoodItHasToHand(TestContext context) {
        grows(context, "spruce", null, null);
    }

    // Every cabin shape built, whichever a world draws: each fixed here, turned a different way.
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "seed-shape-9x9", tickLimit = 30000)
    public void aNineByNineCabinIsBuiltUnderItsFlatRoof(TestContext context) {
        grows(context, "birch", null, new BuildStyle("", "birch", "cobblestone", 3, 1));
    }

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "seed-shape-9x7", tickLimit = 30000)
    public void aNineBySevenCabinIsBuiltWithItsRidgeAlongItsLength(TestContext context) {
        grows(context, "oak", null, new BuildStyle("", "oak", "cobblestone", 1, 2));
    }

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "seed-shape-7x9", tickLimit = 30000)
    public void aSevenByNineCabinIsBuiltWithTwoWindowsASide(TestContext context) {
        grows(context, "spruce", null, new BuildStyle("", "spruce", "cobblestone", 18, 3));
    }

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "seed-shape-mirrored", tickLimit = 30000)
    public void aCabinLaidOutTheOtherWayAboutIsBuilt(TestContext context) {
        grows(context, "dark_oak", null, new BuildStyle("", "dark_oak", "cobblestone", 28, 0));
    }

    /**
     * A player holding all any cabin in {@code wood} takes grows its cabin from {@code seed}, or with none, and builds
     * it; or builds it in {@code fixed}, when given, as its world might have drawn.
     */
    private static void grows(TestContext context, String wood, String seed, BuildStyle fixed) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 29500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(40, 7, 4), o.add(40, 7, 40)};
        BlockPos home = o.add(16, 7, 16);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, trunks);
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // As though gathered: as much of each block as any cabin's plan in its wood takes, so whichever plan
                    // its world draws it can build without a trip; stone tools, a spare table, dirt to scaffold, bread.
                    var most = new TreeMap<String, Integer>();
                    for (int v = 1; v <= BuildStyle.VARIANTS; v++) {
                        var plan = Houses.of(Houses.Stage.CABIN, new BuildStyle("", wood, "cobblestone", v, 0));
                        BillOfMaterials.of(plan.blocks(), new BuildSite(Map.of()), Map.of()).needed().forEach((item, n) -> most.merge(item, n, Math::max));
                    }
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(slot++, new ItemStack(Items.STONE_AXE));
                    for (var e : most.entrySet()) {
                        var item = Registries.ITEM.get(Identifier.of(e.getKey()));
                        for (int left = e.getValue(); left > 0; left -= item.getMaxCount()) {
                            inventory.setStack(slot++, new ItemStack(item, Math.min(left, item.getMaxCount())));
                        }
                    }
                    inventory.setStack(slot++, new ItemStack(Items.CRAFTING_TABLE, 1));
                    inventory.setStack(slot++, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(slot, new ItemStack(Items.BREAD, 16));
                    KindredMod.LOGGER.info("Seed build: Mason holds the most any {} cabin takes: {}", wood, most);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    if (seed != null) context.assertTrue(f.runtime.setSeedBlock(f.source, f.id, seed) == 1, "Seeded");
                    if (fixed != null) f.runtime.fixBaseStyle(f.id, fixed);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                })
                .createAndAdd(() -> {
                    var base = f.runtime.currentBase(f.id);
                    context.assertTrue(base.isPresent() && base.get().complete() && f.runtime.baseNote(f.id).startsWith("Iron tools first"),
                            "Its cabin built: " + base + " / " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.baseNote(f.id)
                                    + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var base = f.runtime.currentBase(f.id).orElseThrow();
                    context.assertTrue(wood.equals(base.wood()) && java.util.Objects.equals(seed, base.seed()),
                            "Grown in " + wood + (seed == null ? ", the wood it had to hand: " : " from its seed: ") + base);
                    context.assertTrue(base.variant() >= 1, "Its world's own plan, not the first every world gets: " + base);
                    if (fixed != null) context.assertTrue(base.variant() == fixed.variant() && base.turns() == fixed.turns(), "The plan given: " + base);
                    var style = new BuildStyle(base.seed(), base.wood(), "cobblestone", base.variant(), base.turns());
                    var plan = Houses.of(Houses.Stage.CABIN, style);
                    var world = context.getWorld();
                    int wrong = 0, ofWood = 0;
                    var report = new StringBuilder();
                    for (var b : plan.placed(new dev.kindred.core.construction.Pos(base.x(), base.y(), base.z()), base.turns())) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                        if (got.block().startsWith("minecraft:" + wood + "_")) ofWood++;
                    }
                    context.assertTrue(wrong == 0, wrong + " blocks not in their state: " + report);
                    var shape = Houses.shape(Houses.Stage.CABIN, base.variant());
                    KindredMod.LOGGER.info("Seed build: a {} cabin, variant {} ({}), turned {}, {} blocks, {} of them {}", wood, base.variant(), shape,
                            base.turns(), plan.blocks().size(), ofWood, wood);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
