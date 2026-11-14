package dev.kindred.fabric;

import dev.kindred.core.record.WorldEvent;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Territory;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In plain terms: a faction player without a wooden tool makes one from the trees by it before it sets out on a faction
 * walk. The walk is kept, not dropped, and goes on once the tool is made. On Factions seeds-10 (`3286df0`) faction walks
 * took players hundreds of blocks from the trees by their spawn before their first wooden tool: on seed 101 Echo and Sol
 * 250 blocks west into water, 114 and 39 walks each before it, and Echo never made one (M-06i1a).
 *
 * Technical: staged, not a live run. Its faction (Red, its own chunk claimed) and its walk (a scout of a chunk four
 * chunks east) are set before its goal begins, so the walk is there first every run, as on those seeds. A trunk stands
 * four blocks off on open ground. Its staged inventory, item by item: nothing (cleared); none of the wood is given.
 * Asserted, every tick until it holds a wooden tool: it is never more than 24 blocks from where it stood; then, the walk
 * still its move. DEFEND is not staged here: it is never held back (the rule leaves it out by kind).
 */
public final class NativeFactionWoodFirstGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionWoodFirst", tickLimit = 3600)
    public void withoutAWoodenToolItsFactionWalkWaitsAndItMakesOneByItsTrees(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3500);
        var server = context.getWorld().getServer();
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos stand = o.add(16, 7, 16), tree = o.add(20, 7, 16);
        var tools = Set.of(Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SWORD, Items.WOODEN_SHOVEL, Items.WOODEN_HOE);
        Territory.Chunk[] home = new Territory.Chunk[1];
        double[] farthest = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, new BlockPos[] {tree});
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    player.getInventory().clear();
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    home[0] = Territory.Chunk.at(W, stand.getX(), stand.getZ());
                    f.runtime.factionsWorldForTest(WIDE);
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, player.getUuid(), "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                    f.runtime.factions().clockForTest(0);
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, player.getUuid(), red, home[0].key(), Map.of()));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.recordWritten(), "Its land written"))
                .createAndAdd(() -> {
                    // Its scout four chunks east, chosen before its goal is asked anything: staged, so that the walk is there
                    // first every run.
                    f.runtime.factionMoveSetForTest(f.id, new FactionPlay.Choice(FactionPlay.Kind.SCOUT, home[0].offset(4, 0).key(), 0,
                            "staged: a chunk four east"));
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    farthest[0] = Math.max(farthest[0], Math.hypot(player.getX() - stand.getX() - 0.5, player.getZ() - stand.getZ() - 0.5));
                    var inventory = player.getInventory();
                    boolean holds = false;
                    for (int slot = 0; slot < inventory.size() && !holds; slot++) holds = tools.contains(inventory.getStack(slot).getItem());
                    context.assertTrue(holds, "A wooden tool earned: " + f.runtime.nativeLastResult(f.id) + ", at " + player.getBlockPos().toShortString()
                            + ", move " + f.runtime.factionMoveForTest(f.id));
                })
                .createAndAdd(() -> {
                    context.assertTrue(farthest[0] <= 24, "Never more than 24 blocks from where it stood before its wooden tool: "
                            + String.format("%.1f", farthest[0]));
                    var move = f.runtime.factionMoveForTest(f.id);
                    context.assertTrue(move != null && move.kind() == FactionPlay.Kind.SCOUT, "Its walk kept: " + move);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
