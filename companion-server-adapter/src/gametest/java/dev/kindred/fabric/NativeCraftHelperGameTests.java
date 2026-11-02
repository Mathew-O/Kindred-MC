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
}
