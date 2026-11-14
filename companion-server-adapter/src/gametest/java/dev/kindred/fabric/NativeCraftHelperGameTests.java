package dev.kindred.fabric;

import dev.kindred.core.ActionArbiter;
import dev.kindred.core.Companion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: a GameTest: a player standing beside a crafting table, asked to make something it has not the makings
 * of (sticks with no planks, a stone pickaxe with no stone), is given nothing to do for it, not a walk to the table it is
 * already at. In the evaluation of 2 November, Kindred walked to the table beside it 927 times in a run's last 19 game
 * minutes so (seed 20261030), and the hand-over did the same in one seed of most arms before (E-40a).
 *
 * Technical: F-25c4av. {@link PortalRunCampaign#craft} with a table in reach and a recipe it cannot plan returned "Walk to
 * the crafting table", which ends at once; it now returns nothing, so the goal and the hand-over go on to what they do
 * when a step cannot be made (the hand-over waits its minute and lets go after six; the goal's next look). The control:
 * with the makings, the same call crafts. A bare native body, as {@link NativePlayerLifecycleGameTests} makes one; the
 * items are staged.
 */
public final class NativeCraftHelperGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void atTheTableWithoutTheMakingsThereIsNothingToDoNotAWalkToIt(TestContext context) {
        Companion identity = Companion.create(UUID.randomUUID(), UUID.randomUUID(), "Crafter", UUID.randomUUID(),
                context.getWorld().getRegistryKey().getValue().toString()).withEntityId(UUID.randomUUID());
        for (int x = 1; x < 7; x++) for (int z = 1; z < 7; z++) {
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y < 6; y++) context.setBlockState(x, y, z, Blocks.AIR);
        }
        context.setBlockState(3, 2, 2, Blocks.CRAFTING_TABLE); // Beside it, within reach.
        NativePlayerBody body = new NativePlayerBody(context.getWorld(), identity, context.getAbsolute(new Vec3d(2.5, 2, 2.5)),
                (lease, intent, target) -> false);
        body.bind(new ActionArbiter(identity, body));
        body.connect(Optional.empty(), false);
        context.runAtTick(5, () -> {
            try {
                var inventory = body.entity().getInventory();
                inventory.clear();
                inventory.setStack(0, new ItemStack(Items.IRON_INGOT, 2)); // A sword's iron, and no stick or plank to make one.
                // Its own table, as one it put down is (the goal and the hand-over use their own), seen where it stands.
                var campaign = new PortalRunCampaign();
                var table = context.getAbsolutePos(new net.minecraft.util.math.BlockPos(3, 2, 2));
                campaign.placedWorkstation(table);
                campaign.knowledge().look(body.entity(), table, 2, 1, WorldKnowledge.Kind.CRAFTING_TABLE);
                var sticks = campaign.craft(body, "stick");
                context.assertTrue(sticks.isEmpty(), "No planks: nothing to do for sticks, not " + sticks.map(PortalRunCampaign.Task::label));
                var pickaxe = campaign.craft(body, "stone_pickaxe");
                context.assertTrue(pickaxe.isEmpty(), "No stone: nothing to do for a stone pickaxe, not " + pickaxe.map(PortalRunCampaign.Task::label));
                // The control: with the makings, the same call crafts at the table.
                inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 3));
                inventory.setStack(2, new ItemStack(Items.STICK, 2));
                var made = campaign.craft(body, "stone_pickaxe");
                context.assertTrue(made.isPresent() && made.get().skill().equals("native_craft"), "With the makings, it crafts: " + made);
                context.complete();
            } finally {
                body.disconnect();
            }
        });
    }

    /**
     * A table the goal has in reach is one the dispatch has in reach too (E-07e; CI on #261, run 43245862450). With five
     * gold ingots, the player's eye 4.0 from its own table's middle on a diagonal: the goal asks to craft the golden
     * helmet there, and the dispatch's check finds the same table in reach, so the craft is not dropped as no longer
     * executable. Measured from the block the eye is in, the table was 4.6 off and not found, the scan with its budget
     * of 128 blocks a tick ran out before it, and the goal, the table in its reach, asked again and never walked nearer:
     * a golden helmet never made from five ingots, 1,200 ticks of nothing started.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void aTableTheGoalHasInReachOnADiagonalIsInReachOfTheDispatch(TestContext context) {
        Companion identity = Companion.create(UUID.randomUUID(), UUID.randomUUID(), "Goldsmith", UUID.randomUUID(),
                context.getWorld().getRegistryKey().getValue().toString()).withEntityId(UUID.randomUUID());
        for (int x = 1; x < 8; x++) for (int z = 1; z < 8; z++) {
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y < 6; y++) context.setBlockState(x, y, z, Blocks.AIR);
        }
        // Two across, four along and a block down from the block its eye is in: the root of 21 off, past 4.5; from the eye
        // itself, near that block's corner toward the table, 4.0 to the table's middle.
        context.setBlockState(4, 2, 6, Blocks.CRAFTING_TABLE);
        NativePlayerBody body = new NativePlayerBody(context.getWorld(), identity, context.getAbsolute(new Vec3d(2.95, 2, 2.95)),
                (lease, intent, target) -> false);
        body.bind(new ActionArbiter(identity, body));
        body.connect(Optional.empty(), false);
        context.runAtTick(5, () -> {
            try {
                var player = body.entity();
                var inventory = player.getInventory();
                inventory.clear();
                inventory.setStack(0, new ItemStack(Items.GOLD_INGOT, 5));
                var campaign = new PortalRunCampaign();
                var table = context.getAbsolutePos(new net.minecraft.util.math.BlockPos(4, 2, 6));
                campaign.placedWorkstation(table);
                campaign.knowledge().look(player, table, 2, 1, WorldKnowledge.Kind.CRAFTING_TABLE);
                Vec3d eye = player.getEyePos();
                double fromEye = eye.distanceTo(Vec3d.ofCenter(table));
                context.assertTrue(fromEye > 3.9 && fromEye < 4.4, "The table in the goal's reach, from the eye: " + fromEye);
                var asked = campaign.craft(body, "golden_helmet");
                context.assertTrue(asked.isPresent() && asked.get().skill().equals("native_craft"), "The goal asks for the craft at the table: " + asked.map(PortalRunCampaign.Task::label));
                var found = PlayerRuntime.workstationInReach(player.getServerWorld(), eye, Blocks.CRAFTING_TABLE, 4.5);
                context.assertTrue(table.equals(found), "The dispatch finds the same table in reach: " + found + ", the table at " + table.toShortString());
                context.assertTrue(body.planCraft("golden_helmet", found).isPresent(), "The craft planned at it");
                context.complete();
            } finally {
                body.disconnect();
            }
        });
    }
}
