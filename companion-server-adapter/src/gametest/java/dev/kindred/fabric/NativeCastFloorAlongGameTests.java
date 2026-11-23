package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * At its frame's floor with no walk to a stand, the cast digs along to its stand rather than climbing away (F-25c4bx2). In
 * E-40 iteration 30 (main `63b97ac9`), batch 1's 505 Kindred refilled its lava at y −54, and F-25c4dm1's dig back brought it
 * up to its frame's floor, y −51, at (31, −51, −132). There "Cast: step closer" was refused "No walkable route" to all five
 * stands, three to six blocks along through rock, and the goal climbed away ("no way on from here - Climb to find another
 * way") to y −47, its lava in hand. Across the iteration's two batches, 18 such climb-aways at a frame site took 782 s of
 * climbing; iteration 29's took 397 s.
 */
public final class NativeCastFloorAlongGameTests implements FabricGameTest {
    /** How long it has to cast its frame's next cell. */
    private static final int BUDGET = 1600;
    /** The most of a failure's text kept: a failed test's text goes onto one lectern page, which holds 1,024 characters. */
    private static final int MESSAGE_MOST = 900;

    private static String within(String message) {
        return message.length() <= MESSAGE_MOST ? message : message.substring(0, MESSAGE_MOST - 3) + "...";
    }

    /**
     * In plain terms: an AI player back at the level of its half-built portal frame, with solid rock between it and every
     * spot it could pour from, digs its way along to the frame and pours the next block, rather than climbing off to look
     * for another way.
     *
     * Technical: F-25c4bx2. STAGED as `NativeCastFloorGameTests` (F-25c4dm1): a block of stone, the frame along north and
     * south facing west, its bottom corners cobblestone and three of its ten obsidian cast as if it had cast them, the row in
     * front of it open five high and lit, and the row behind it open two high; the frame taken as the player's own. The
     * player not under the floor but at its level: in a pocket one wide and two high, four blocks east of the row behind,
     * through stone, so every stand is reached only through rock and no walk leads out. The cast route's kit, its lava
     * refilled; daylight held. Fails at once, and stays failed, if its goal climbs to find another way or gives the site
     * up; fails if within {@value #BUDGET} ticks the next cell is not obsidian. Passes once it is.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "castFloorAlong", tickLimit = BUDGET + 400)
    public void atItsFramesFloorWithEveryStandThroughRockItDigsAlongAndCasts(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 300);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        BlockPos stand = o.add(-8, 0, 0);
        var site = new PortalRunCampaign.PortalSite(stand.offset(Direction.WEST, 2).offset(Direction.SOUTH, 1), Direction.NORTH, Direction.WEST, stand);
        BlockPos pocket = o.add(-4, 0, 0);
        BlockPos next = site.at(3, 1);
        int[] watched = new int[1];
        String[] verdict = {null};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Along"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-16, -6, -8), o.add(4, 12, 8));
                    FixtureGround.simulated(context, o.add(-16, -6, -8), o.add(4, -6, 8));
                    for (int x = -16; x <= 4; x++) for (int z = -8; z <= 8; z++) for (int y = -6; y <= 12; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 10 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The row in front of the frame, open five high, the frame's opening and top row, and the row behind
                    // the front, its stands, open two high.
                    for (int across = 0; across <= 3; across++) {
                        for (int up = 0; up <= 4; up++) world.setBlockState(site.front(across, up), Blocks.AIR.getDefaultState());
                        for (int up = 0; up <= 1; up++) world.setBlockState(site.front(across, up).offset(Direction.EAST), Blocks.AIR.getDefaultState());
                    }
                    for (int across = 0; across <= 3; across++) for (int up = 1; up <= 4; up++) {
                        world.setBlockState(site.at(across, up), Blocks.AIR.getDefaultState());
                    }
                    // Three of the ten cast, the bottom corners set.
                    world.setBlockState(site.at(0, 0), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(site.at(3, 0), Blocks.COBBLESTONE.getDefaultState());
                    for (int[] cast : new int[][]{{1, 0}, {2, 0}, {0, 1}}) {
                        world.setBlockState(site.at(cast[0], cast[1]), Blocks.OBSIDIAN.getDefaultState());
                    }
                    // Its pocket at the floor's level, one wide and two high, four east of the row behind through stone.
                    world.setBlockState(pocket, Blocks.AIR.getDefaultState());
                    world.setBlockState(pocket.up(), Blocks.AIR.getDefaultState());
                    world.setBlockState(site.front(1, 5), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(site.front(2, 5), Blocks.GLOWSTONE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(pocket.getX() + 0.5, pocket.getY(), pocket.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // The cast route's kit, its lava refilled for the next cell and a bucket of water.
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(6, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(7, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(8, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(9, new ItemStack(Items.IRON_INGOT, 3));
                    inventory.setStack(10, new ItemStack(Items.FLINT));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(pocket), "In its pocket: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    f.runtime.campaignForTest(f.id).portalSiteForTest(site);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), within("The player died; " + seen));
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    String at = player.getBlockPos().subtract(o).toShortString();
                    if (watched[0]++ % 50 == 0) seen.append(watched[0]).append(": ").append(progress.replaceAll("^.* - ", "")).append(" @ ").append(at).append("; ");
                    // Kept: a step retried each tick would pass on a later tick.
                    if (verdict[0] == null && progress.contains("Climb to find another way")) {
                        verdict[0] = within("It climbed away from its frame at " + at + " after " + watched[0] + " ticks: " + seen + " now " + progress);
                    }
                    if (verdict[0] == null && f.runtime.campaignForTest(f.id).portalSiteForTest() == null) {
                        verdict[0] = within("It gave the frame site up at " + at + " after " + watched[0] + " ticks: " + seen + " now " + progress);
                    }
                    context.assertTrue(verdict[0] == null, String.valueOf(verdict[0]));
                    context.assertTrue(watched[0] <= BUDGET || world.getBlockState(next).isOf(Blocks.OBSIDIAN),
                            within("The next cell not cast after " + BUDGET + " ticks: " + seen + " now " + progress + " @ " + at));
                    context.assertTrue(world.getBlockState(next).isOf(Blocks.OBSIDIAN), "Not cast yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cast floor along: the next cell cast after {} ticks; {}", watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
