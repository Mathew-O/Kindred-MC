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

/** A frame half cast is not given up for a block it cannot see from its stand: it goes where it can (F-25c4cn). */
public final class NativeHalfCastFrameGameTests implements FabricGameTest {
    /**
     * Six of its frame's ten obsidian cast, with rock still in a top corner over the next side cell to cast: it opens that
     * corner from the row in front of the frame, and casts the side cell. In E-40's iteration 14 (`e40a14-80b7150`) on
     * 31102026 Echo had cast six of its frame's ten when the next side cell's cast needed the corner over it open; the
     * corner held the natural deepslate, out of sight from its stand, and it gave the frame up ("cannot open"). It then
     * tried frame sites overlapping its own obsidian, gave those up, and walked "Cast: step closer" 2,144 times; no
     * portal was lit on the seed. Staged: a block of stone, the frame along north and south with its opening facing
     * east, its corners at the bottom of cobblestone, the two bottom cells and the four lower side cells of obsidian, as
     * if it had cast them; the top corners left stone; the row in front of it open five high and lit, the stand at the
     * end of a tunnel two high, with the stone over it; the frame taken as the player's own. The player on its stand with
     * the cast route's kit, a bucket each of lava and water, and the iron and flint for its flint and steel, so that its
     * next step is the cast; daylight held. Passes once the side cell under the first
     * top corner is obsidian, within 1,200 ticks.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "halfCastFrame", tickLimit = 1600)
    public void aFrameHalfCastIsNotGivenUpForACornerOutOfSightFromItsStand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        BlockPos stand = o.add(-8, 0, 0);
        var site = new PortalRunCampaign.PortalSite(stand.offset(Direction.WEST, 2).offset(Direction.SOUTH, 1), Direction.NORTH, Direction.WEST, stand);
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Caster"))
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
                    // The row in front of the frame, open five high, and the frame's opening and top row.
                    for (int across = 0; across <= 3; across++) for (int up = 0; up <= 4; up++) {
                        world.setBlockState(site.front(across, up), Blocks.AIR.getDefaultState());
                    }
                    for (int across = 1; across <= 2; across++) for (int up = 1; up <= 4; up++) {
                        world.setBlockState(site.at(across, up), Blocks.AIR.getDefaultState());
                    }
                    // The six cast, the bottom corners set; the side cells under the top corners still open, the top
                    // corners left the stone they were.
                    world.setBlockState(site.at(0, 0), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(site.at(3, 0), Blocks.COBBLESTONE.getDefaultState());
                    for (int[] cast : new int[][]{{1, 0}, {2, 0}, {0, 1}, {0, 2}, {3, 1}, {3, 2}}) {
                        world.setBlockState(site.at(cast[0], cast[1]), Blocks.OBSIDIAN.getDefaultState());
                    }
                    world.setBlockState(site.at(0, 3), Blocks.AIR.getDefaultState());
                    world.setBlockState(site.at(3, 3), Blocks.AIR.getDefaultState());
                    // The tunnel it came along, two high, ending on the stand, the stone left over it; lit.
                    for (int x = -8; x <= -3; x++) for (int y = 0; y <= 1; y++) world.setBlockState(o.add(x, y, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(site.front(1, 5), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(o.add(-5, 2, 0), Blocks.GLOWSTONE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // The cast route's kit, a bucket of lava filled for the next cell and one of water.
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(6, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(7, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(8, new ItemStack(Items.IRON_CHESTPLATE));
                    // The iron and flint for its flint and steel already in hand, so that its next step is the cast, as Echo's was.
                    inventory.setStack(9, new ItemStack(Items.IRON_INGOT, 3));
                    inventory.setStack(10, new ItemStack(Items.FLINT));
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
                    context.assertTrue(watched[0] <= 1200, "The side cell under the top corner not cast after 1,200 ticks: " + seen + " now " + progress);
                    context.assertTrue(world.getBlockState(site.at(0, 3)).isOf(Blocks.OBSIDIAN), "Not cast yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Half-cast frame: the side cell cast after {} ticks; {}", watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
