package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Walks to pick up drops it cannot reach do not strand it; walks that find no way from where it stands still do (F-22c73). */
public final class NativeStrandedPickupGameTests implements FabricGameTest {
    /**
     * A player with no wood on open stone, an oak in the open twenty south, and three of its walks to pick up a drop just
     * refused from where it stands ("No walkable route"), as its campaign hears of them. Its trip for logs goes: in
     * NativeBaseGameTests' stone-tools cabin (four of about twelve PR runs on 12 November) three or four refused walks to
     * its own cobblestone, in the pit it had mined for stone, counted toward its stranding (three refused walks from about
     * one place within a minute), and stranded, its gather offered no walk: its trip for logs found none, a tree twenty off
     * usable, till the test's end. Staged: the refusals, handed to its campaign as its own walks' outcomes, with the reason
     * the navigator gave them. Fails once it is stranded with no wood; passes once it holds wood.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "strandedPickup", tickLimit = 2400)
    public void refusedWalksToDropsItCannotReachDoNotStrandItsTripForLogs(TestContext context) {
        scene(context, "Pick up cobblestone", false);
    }

    /**
     * The same, with three refused walks for logs from where it stands: a walk that finds no way from here still strands
     * it, as before: the stranding rule kept. Fails unless it is stranded at once after them.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "strandedWalks", tickLimit = 1200)
    public void refusedWalksThatFindNoWayFromHereStillStrandIt(TestContext context) {
        scene(context, "Walk to logs at", true);
    }

    private static void scene(TestContext context, String walk, boolean strands) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        BlockPos[] at = new BlockPos[1];
        String[] strandedAt = {null};
        int[] watched = new int[1];
        boolean[] told = new boolean[1];
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Strand"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // In the middle of a structure as wide as the field, so that copies side by side keep apart.
                    BlockPos p = at[0] = context.getAbsolutePos(new BlockPos(60, 2, 60)).toImmutable();
                    FixtureGround.keep(context, p.add(-10, -2, -10), p.add(10, 8, 26));
                    FixtureGround.simulated(context, p.add(-10, 0, -10), p.add(10, 0, 26));
                    for (BlockPos c : BlockPos.iterate(p.add(-10, -2, -10), p.add(10, 8, 26))) {
                        world.setBlockState(c, (c.getY() < p.getY() ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The oak, twenty south, in the open.
                    BlockPos oak = p.add(0, 0, 20);
                    world.setBlockState(oak.down(), Blocks.DIRT.getDefaultState());
                    for (int y = 0; y < 4; y++) world.setBlockState(oak.up(y), Blocks.OAK_LOG.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the stone"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    // Its campaign has made its first plan, and knows where the player stands.
                    var campaign = f.runtime.campaignForTest(f.id);
                    context.assertTrue(campaign != null && campaign.lastTick() > 0, "Its campaign under way");
                    // Three of its walks refused from here, as the navigator refuses them: one a cell off each, as its
                    // cobblestone lay in the pit (-1, 0, 0; 0, -1, -1; 1, -1, 0 from where it stood).
                    BlockPos feet = f.player().getBlockPos();
                    long tick = campaign.lastTick();
                    BlockPos[] cells = {feet.add(-1, 0, 0), feet.add(0, -1, -1), feet.add(1, -1, 0)};
                    for (BlockPos cell : cells) {
                        String label = walk.startsWith("Walk to logs") ? walk + " " + cell.toShortString() : walk;
                        campaign.taskFinished(PortalRunCampaign.go(cell, 0.6, true, label), false, tick,
                                "No walkable route toward " + cell.toShortString());
                    }
                    told[0] = true;
                    if (strands) context.assertTrue(campaign.stranded(), "Stranded by three walks that found no way from here");
                })
                .createAndAdd(() -> {
                    if (strands) return;
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null, "Its body: " + seen);
                    String doing = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!doing.equals(last[0]) && seen.length() < 2500) {
                        seen.append(watched[0]).append(": ").append(doing.replaceAll("^.* - ", "")).append(" @ ")
                                .append(player.getBlockPos().subtract(at[0]).toShortString()).append("; ");
                    }
                    last[0] = doing;
                    watched[0]++;
                    var inventory = player.getInventory();
                    boolean wood = false;
                    for (int slot = 0; slot < inventory.size(); slot++) wood |= inventory.getStack(slot).isIn(ItemTags.LOGS) || inventory.getStack(slot).isIn(ItemTags.PLANKS);
                    var campaign = f.runtime.campaignForTest(f.id);
                    if (strandedAt[0] == null && !wood && campaign.stranded()) strandedAt[0] = watched[0] + ": " + doing;
                    context.assertTrue(strandedAt[0] == null, "Stranded by its refused walks to drops, with no wood, at " + strandedAt[0] + ": " + seen);
                    context.assertTrue(wood, "No wood yet: " + seen + " now " + doing);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told[0], "Its refused walks told");
                    KindredMod.LOGGER.info("Stranded {}: {} ticks; {}", walk, watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
