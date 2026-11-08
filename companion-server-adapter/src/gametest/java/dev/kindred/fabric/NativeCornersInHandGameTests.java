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

/** The corners already set in its frame are not cobblestone in hand: with no pickaxe it makes a wooden one (F-25c4ci). */
public final class NativeCornersInHandGameTests implements FabricGameTest {
    /**
     * With no pickaxe and logs in hand, and the four corners of its frame already set, it makes a wooden pickaxe. In E-40's iteration 13 (`e40a13-fe2ba3b`) on 8675309 Kindred died in lava with its frame
     * begun, and came back with nothing. The goal counts corners set in the frame as cobblestone toward the four it needs,
     * so with no pickaxe it set out to make a stone one "with cobblestone already in hand". At its table it had none:
     * "cannot make stone_pickaxe at the table in reach: not the makings", 52 times, with seven crafting tables made and
     * seven stuck spells "waiting for a feasible step". Staged: a block of stone, the frame's four corners of cobblestone
     * set two blocks east of the player and its site taken as the player's own; the player with four oak logs (a table's
     * and a pickaxe's worth) and food, no pickaxe, no crafting table and no cobblestone; daylight held. Passes once it
     * holds a pickaxe, within 600 ticks.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "cornersInHand", tickLimit = 1200)
    public void theCornersSetInItsFrameAreNotCobblestoneInHand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        BlockPos stand = o.add(4, 0, 0);
        var site = new PortalRunCampaign.PortalSite(stand.offset(Direction.WEST, 2).offset(Direction.SOUTH, 1), Direction.NORTH, Direction.WEST, stand);
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Cornered"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-10, -3, -10), o.add(10, 8, 10));
                    FixtureGround.simulated(context, o.add(-10, -3, -10), o.add(10, -3, 10));
                    for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) for (int y = -3; y <= 8; y++) {
                        world.setBlockState(o.add(x, y, z), (y < 0 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The frame begun: its four corners of cobblestone, nothing else of it yet.
                    for (int[] corner : new int[][]{{0, 0}, {3, 0}, {0, 4}, {3, 4}}) {
                        world.setBlockState(site.at(corner[0], corner[1]), Blocks.COBBLESTONE.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // As a player back from a death has it once it has cut a few logs: no pickaxe, no cobblestone.
                    inventory.setStack(0, new ItemStack(Items.OAK_LOG, 4));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In place"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    f.runtime.campaignForTest(f.id).portalSiteForTest(site);
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died or is gone; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 100 == 0) seen.append(watched[0]).append(": ").append(progress).append("; ");
                    context.assertTrue(watched[0] <= 600, "No pickaxe after 600 ticks: " + seen + " now " + progress);
                    var inventory = player.getInventory();
                    boolean pickaxe = false;
                    for (int i = 0; i < inventory.size(); i++) pickaxe |= inventory.getStack(i).getItem() instanceof net.minecraft.item.PickaxeItem;
                    context.assertTrue(pickaxe, "No pickaxe yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Corners in hand: a pickaxe after {} ticks; {}", watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
