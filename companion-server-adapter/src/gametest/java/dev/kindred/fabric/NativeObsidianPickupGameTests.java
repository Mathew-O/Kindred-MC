package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** The obsidian picked up from its poured water's flow when a choice to take the water back is not taken (F-25c4cs). */
public final class NativeObsidianPickupGameTests implements FabricGameTest {
    /**
     * The scene of {@code NativePortalCampaignGameTests.goalMakesObsidianFromLavaWithPouredWaterAndRecoversTheWater}: a
     * lava source recessed below its shore, a diamond pickaxe and a water bucket. In a class run of that fixture (batch
     * 23's bench, 8 November) Smith mined the obsidian from the shore and walked back to its stand. As that walk ended, its
     * goal chose to take the water back, and the runtime, still on the walk, did not take that step; the goal had given up
     * the obsidian site as it chose it. With no site, the drop's settle-wait ran out in the water's flow, the bucket was
     * refilled from it, and Smith branch mined away with the obsidian left in the draining water. Here that choice is
     * forced: on the first tick it stands on its stand after the mining, the fixture asks for the player's next tasks once,
     * as the runtime's own decision does, and runs none of them. Passes once its water is back in the bucket with the site
     * kept, no wait for the drop and no refill by the bucket's own upkeep before it, and the obsidian is in hand before any
     * walk to explore. The obsidian alone does not tell them apart: a player that swims through the water after mining
     * picks it up as it passes, as two of three did without the fix.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "obsidianPickup", tickLimit = 1500)
    public void anUntakenStepToTakeTheWaterBackLeavesNoObsidianBehind(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos lava = context.getAbsolutePos(new BlockPos(5, 1, 3));
        BlockPos shore = context.getAbsolutePos(new BlockPos(4, 2, 3));
        BlockPos stand = context.getAbsolutePos(new BlockPos(3, 2, 3));
        String[] asked = {null};
        String[] lostSite = {null};
        boolean[] waterBack = {false};
        boolean[] exploredFirst = {false};
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        Runnable watch = () -> {
            var bots = f.runtime.panelBots(Actor.console());
            String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
            if (progress != null && !progress.equals(last[0])) {
                last[0] = progress;
                if (seen.length() < 3000) seen.append(progress.replaceAll("^.* - ", "")).append("; ");
                if (progress.contains("Explore") && ItemMoves.count(f.player().getInventory(), Items.OBSIDIAN) == 0) exploredFirst[0] = true;
            }
            // From the untaken step until its water is back in the bucket: a wait for the drop, or a refill by the bucket's
            // own upkeep, is a goal with the obsidian site given up while its water still stands.
            if (asked[0] != null && !waterBack[0]) {
                if (ItemMoves.count(f.player().getInventory(), Items.WATER_BUCKET) == 1) waterBack[0] = true;
                else if (lostSite[0] == null && progress != null && (progress.contains("Wait for the drop to settle")
                        || progress.contains("Fill a bucket at") || progress.contains("Walk to water"))) lostSite[0] = progress.replaceAll("^.* - ", "");
            }
        };
        java.util.function.Supplier<String> tail = () -> seen.length() > 700 ? "..." + seen.substring(seen.length() - 700) : seen.toString();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Walls keep the poured water inside this test's area.
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    context.setBlockState(5, 0, 3, Blocks.STONE);
                    context.setBlockState(5, 1, 3, Blocks.LAVA);
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) context.setBlockState(x, 7, z, Blocks.STONE);
                    f.runtime.createNative(f.source, "Smith");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing on the shore"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(2, new ItemStack(Items.IRON_INGOT)); // Flint and steel's iron is already in hand.
                    inventory.setStack(3, new ItemStack(Items.BREAD, 5));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    watch.run();
                    var world = context.getWorld();
                    boolean mined = !world.getBlockState(lava).isOf(Blocks.OBSIDIAN) && !world.getFluidState(lava).isIn(FluidTags.LAVA);
                    boolean standing = world.getFluidState(shore).isStill() && world.getFluidState(shore).isIn(FluidTags.WATER);
                    if (asked[0] == null && mined && standing && f.player().getBlockPos().equals(stand)) {
                        // Staged: the player's next tasks asked for once, as its decision asks, and none of them run.
                        asked[0] = f.runtime.nativeCandidates(Actor.console(), f.id).stream()
                                .map(c -> c.skill() + " " + c.arguments().getOrDefault("mode", "")).toList().toString();
                    }
                    context.assertTrue(asked[0] != null, "On its stand, the obsidian mined, its water still poured: " + tail.get());
                })
                .createAndAdd(() -> context.assertTrue(asked[0].contains("native_bucket fill"),
                        "The step asked for and not taken was to take the water back: " + asked[0] + "; " + tail.get()))
                .createAndAdd(() -> {
                    watch.run();
                    context.assertTrue(lostSite[0] == null, "The site given up with its water still standing: " + lostSite[0] + "; " + tail.get());
                    context.assertTrue(waterBack[0], "Its water back in the bucket: " + tail.get());
                })
                .createAndAdd(() -> {
                    watch.run();
                    context.assertFalse(exploredFirst[0], "Went exploring with its obsidian left in the water: " + tail.get());
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.OBSIDIAN) == 1, "The obsidian mined and picked up: " + tail.get());
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.WATER_BUCKET) == 1 && ItemMoves.count(inventory, Items.BUCKET) == 0,
                            "The water was taken back into the bucket: " + tail.get());
                    var world = context.getWorld();
                    context.assertFalse(world.getFluidState(lava).isIn(FluidTags.LAVA), "No lava left at the source");
                    context.assertFalse(world.getFluidState(shore).isStill() && world.getFluidState(shore).isIn(FluidTags.WATER),
                            "No water source left on the shore");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Obsidian pickup: asked {}; {}", asked[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
