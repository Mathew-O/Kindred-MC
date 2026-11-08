package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
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

/** Back to its own portal frame to light it when no walk leads there: it digs the way (F-25c4by). */
public final class NativeFrameReturnGameTests implements FabricGameTest {
    /**
     * Its frame standing deep below ground and its flint and steel made some way off, in a pocket over the tunnel it
     * came along with no walk back down: it digs its way back to the frame and lights it. In E-40's iteration 11
     * (`e40a11-744c636`) Kindred on 404 cast its frame at Y=-54, tunnelled 23 blocks east at the frame's level to gravel,
     * set gravel down and dug it until it dropped flint, the gravel it set down lifting it three blocks over its tunnel,
     * and made its flint and steel there. Every walk back to the frame was refused ("No walkable route", 189 times), and
     * the fallbacks climbed it about for the rest of the run; the portal was never lit. Staged: a block of stone with a
     * lit room in it, a complete unlit frame of obsidian in the room taken as the player's own (as if it had cast it), a
     * tunnel two high leading twelve blocks east from the room, and the player in a lit pocket three blocks over the
     * tunnel's far end, walled in, with the portal stage's kit and its flint and steel; daylight held.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "frameReturn", tickLimit = 4000)
    public void itsFlintAndSteelMadeWhereNoWalkLeadsBackItDigsBackToItsFrameAndLightsIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        // The frame along north-south with its opening facing east, two blocks west of its stand, as a site is chosen.
        BlockPos stand = o.add(-8, 0, 0);
        var site = new PortalRunCampaign.PortalSite(stand.offset(Direction.WEST, 2).offset(Direction.SOUTH, 1), Direction.NORTH, Direction.WEST, stand);
        BlockPos pocket = o.add(4, 3, 0);
        boolean[] died = new boolean[1];
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wayfinder"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-16, -2, -8), o.add(16, 14, 8));
                    FixtureGround.simulated(context, o.add(-16, 0, -8), o.add(16, 0, 8));
                    for (int x = -16; x <= 16; x++) for (int z = -8; z <= 8; z++) for (int y = -2; y <= 14; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 10 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The room round the frame, lit from its ceiling.
                    for (int x = -12; x <= -7; x++) for (int z = -3; z <= 2; z++) for (int y = 0; y <= 5; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(o.add(-9, 6, -2), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(o.add(-11, 6, 1), Blocks.GLOWSTONE.getDefaultState());
                    // The frame: obsidian round a two by three opening, cobblestone at its corners, unlit.
                    for (int across = 0; across <= 3; across++) for (int up = 0; up <= 4; up++) {
                        boolean edge = across == 0 || across == 3 || up == 0 || up == 4;
                        boolean corner = (across == 0 || across == 3) && (up == 0 || up == 4);
                        if (edge) world.setBlockState(site.at(across, up), (corner ? Blocks.COBBLESTONE : Blocks.OBSIDIAN).getDefaultState());
                    }
                    // The tunnel east from the room at the frame's level, lit, and the pocket over its far end.
                    for (int x = -6; x <= 4; x++) for (int y = 0; y <= 1; y++) world.setBlockState(o.add(x, y, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(o.add(-3, 2, 0), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(o.add(2, 2, 0), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(pocket, Blocks.AIR.getDefaultState());
                    world.setBlockState(pocket.up(), Blocks.AIR.getDefaultState());
                    world.setBlockState(pocket.up(2), Blocks.GLOWSTONE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(pocket.getX() + 0.5, pocket.getY(), pocket.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // As the cast route leaves it once its flint and steel is made.
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(6, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(7, new ItemStack(Items.BUCKET));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(pocket), "In the pocket"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    f.runtime.campaignForTest(f.id).portalSiteForTest(site);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    if (player == null || !player.isAlive()) died[0] = true;
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 200 == 0 && player != null) {
                        seen.append(watched[0]).append(": ").append(progress).append(" @ ").append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    }
                    context.assertTrue(!died[0], "The player died before its portal was lit; " + progress);
                    context.assertTrue(world.getBlockState(site.at(1, 1)).isOf(Blocks.NETHER_PORTAL), "Its portal not lit yet: " + seen + " now "
                            + progress + "; " + f.runtime.nativeActivityStatus(Actor.console(), f.id));
                })
                .createAndAdd(() -> {
                    long portalBlocks = BlockPos.streamOutwards(site.at(1, 2), 2, 2, 2).filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.NETHER_PORTAL)).count();
                    context.assertTrue(portalBlocks == 6, "Its own frame lit: " + portalBlocks + " portal blocks");
                    KindredMod.LOGGER.info("Frame return: lit after {} ticks, the player at {}", watched[0], f.player().getBlockPos().subtract(o).toShortString());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
