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

/** Gravel in a finished frame's opening is cleared before the portal is lit, not lit at again and again (F-25c4co). */
public final class NativeObstructedPortalGameTests implements FabricGameTest {
    /**
     * Its frame finished, two gravel in the bottom row of its opening: it clears them and lights the portal. In E-40's
     * iteration 15 (`e40a15-dbf4e5c`) on 31102026 Kindred finished its frame at Y=-54, dug gravel beside it for flint,
     * and two gravel lay in the opening's bottom row when it tried to light it. Each try was refused, "Portal opening
     * position is obstructed", three times; the watchdog moved it on, it walked back, and tried again. It walked back to
     * its frame 604 times and lit no portal on the seed, one of the three without one in that iteration. Staged: a
     * block of stone, the frame along north and south with its opening facing east, its ten cells of obsidian and its
     * corners of cobblestone, as if it had cast them; gravel in the two cells of the opening's bottom row; the row in
     * front of it open five high and lit, the stand at the end of a tunnel two high; the frame taken as the player's
     * own. The player on its stand with the cast route's kit and its flint and steel made, so that its next step is
     * the lighting; daylight held. Passes once the opening holds a portal, within 1,200 ticks.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "obstructedPortal", tickLimit = 1600)
    public void gravelInAFinishedFramesOpeningIsClearedAndThePortalLit(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        BlockPos stand = o.add(-8, 0, 0);
        var site = new PortalRunCampaign.PortalSite(stand.offset(Direction.WEST, 2).offset(Direction.SOUTH, 1), Direction.NORTH, Direction.WEST, stand);
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Lighter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-16, -2, -8), o.add(4, 12, 8));
                    FixtureGround.simulated(context, o.add(-16, -2, -8), o.add(4, -2, 8));
                    for (int x = -16; x <= 4; x++) for (int z = -8; z <= 8; z++) for (int y = -2; y <= 12; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 10 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The row in front of the frame, open five high.
                    for (int across = 0; across <= 3; across++) for (int up = 0; up <= 4; up++) {
                        world.setBlockState(site.front(across, up), Blocks.AIR.getDefaultState());
                    }
                    // The frame finished: its ten cells of obsidian, its corners of cobblestone, its opening open.
                    for (int across = 0; across <= 3; across++) for (int up = 0; up <= 4; up++) {
                        boolean frame = across == 0 || across == 3 || up == 0 || up == 4;
                        boolean corner = (across == 0 || across == 3) && (up == 0 || up == 4);
                        world.setBlockState(site.at(across, up), (corner ? Blocks.COBBLESTONE : frame ? Blocks.OBSIDIAN : Blocks.AIR).getDefaultState());
                    }
                    // The gravel that lay in the opening's bottom row on 31102026.
                    world.setBlockState(site.at(1, 1), Blocks.GRAVEL.getDefaultState());
                    world.setBlockState(site.at(2, 1), Blocks.GRAVEL.getDefaultState());
                    // The tunnel it came along, two high, ending on the stand; lit.
                    for (int x = -8; x <= -3; x++) for (int y = 0; y <= 1; y++) world.setBlockState(o.add(x, y, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(site.front(1, 5), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(o.add(-5, 2, 0), Blocks.GLOWSTONE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // The cast route's kit after the cast, its flint and steel made.
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(2, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.BUCKET));
                    inventory.setStack(6, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(7, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(8, new ItemStack(Items.STONE_SHOVEL));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(stand), "On its stand: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    f.runtime.campaignForTest(f.id).portalSiteForTest(site);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 100 == 0) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    context.assertTrue(watched[0] <= 1200, "No portal lit after 1,200 ticks: " + seen + " now " + progress);
                    context.assertTrue(world.getBlockState(site.at(1, 2)).isOf(Blocks.NETHER_PORTAL), "Not lit yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Obstructed portal: lit after {} ticks; {}", watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
