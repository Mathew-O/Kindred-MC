package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: on the way to cast its portal frame, a player that puts its crafting table down to make a new pickaxe
 * takes the table with it when it goes on looking for lava, so that the next worn-out pickaxe is made where it is.
 *
 * Technical: F-25c4cw. The goal picks its own workstations up when it leaves to gather, its plan's step one of
 * {@code PortalRunCampaign.TRAVEL}; the cast route's steps (CAST_PORTAL, FILL_LAVA) were not among them. In E-40's
 * iteration 16 (`e40a16-922f4fd`) on 303 Kindred, its pickaxe worn out at Y=-54 on the cast route, put a table down,
 * made a stone pickaxe, branch-mined fifty blocks on for lava and wore that one out too; with no table and no planks
 * it climbed for logs from Y=-54. Staged: a chamber deep in a block of stone with no lava in it, and the cast route's
 * kit as though made, its pickaxe gone and a table, cobblestone and sticks to make another.
 */
public final class NativeCastTableGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "castTable", tickLimit = 2400)
    public void onTheCastRouteItsTableIsTakenAlongWhenItGoesOnForLava(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] table = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Tabletaker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    // A chamber in a block of stone, no lava anywhere in it: the cast's lava is to be looked for.
                    FixtureGround.keep(context, p.add(-6, -3, -6), p.add(6, 11, 6));
                    for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -3; y <= 11; y++) {
                        boolean chamber = Math.abs(x) <= 2 && Math.abs(z) <= 1 && y >= 0 && y <= 1;
                        world.setBlockState(p.add(x, y, z), (y <= 8 && !chamber ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    // The cast route's kit as though made, its pickaxe worn out: a table, cobblestone and sticks to make
                    // the next one, as 303's Kindred had.
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(2, new ItemStack(Items.BUCKET));
                    inventory.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 32));
                    inventory.setStack(6, new ItemStack(Items.STICK, 16));
                    inventory.setStack(7, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(9, new ItemStack(Items.COBBLESTONE, 64));
                    f.player().equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    // Its table put down and a pickaxe made at it.
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.STONE_PICKAXE) + ItemMoves.count(inventory, Items.IRON_PICKAXE) > 0,
                            "A pickaxe made: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    table[0] = BlockPos.streamOutwards(f.player().getBlockPos(), 6, 3, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)).findFirst().map(BlockPos::toImmutable).orElse(null);
                    context.assertTrue(table[0] != null, "Its table put down to make it");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    boolean standing = context.getWorld().getBlockState(table[0]).isOf(Blocks.CRAFTING_TABLE);
                    boolean carried = ItemMoves.count(player.getInventory(), Items.CRAFTING_TABLE) == 1;
                    // Gone on without it: a dozen blocks from the table with the table still standing.
                    context.assertFalse(standing && player.getBlockPos().getSquaredDistance(table[0]) > 12 * 12,
                            "Gone on for lava with its table left behind at " + table[0].toShortString() + ": " + goal);
                    context.assertTrue(!standing && carried, "Its table still to be taken along: " + goal);
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
