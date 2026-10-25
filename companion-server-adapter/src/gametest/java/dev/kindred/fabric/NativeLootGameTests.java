package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.guide.Worth;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTables;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Valuables never walked past (E-42): a structure's container nobody has opened is looted of what is worth taking to
 * the player, as the value model judges it, and what is not worth a slot is left in it; a ruined portal's gold blocks
 * are mined and kept for later; storage someone has opened is never touched. The loot is the world's own, rolled from
 * a fixed seed when the player opens the container.
 */
public final class NativeLootGameTests implements FabricGameTest {
    /** What a container still holds worth taking, each item judged by all of it there, as the value model has it for the player now. */
    private static List<String> notLeft(NativePaidSkillsGameTests.Fixture f, net.minecraft.inventory.Inventory container) {
        var holding = Valuables.holding(f.player(), Map.of(Worth.FOOD, 6));
        var totals = new java.util.LinkedHashMap<String, Integer>();
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.getStack(slot);
            if (!stack.isEmpty()) totals.merge(Valuables.id(stack), stack.getCount(), Integer::sum);
        }
        var kept = new ArrayList<String>();
        totals.forEach((item, count) -> {
            var judgement = Worth.judge(new Worth.Stack(item, count), holding);
            KindredMod.LOGGER.info("Loot fixture: left in it {} {}: {}", count, item, judgement.why());
            if (judgement.keep() != Worth.Keep.LEAVE) kept.add(count + " " + item + " (" + judgement.why() + ")");
        });
        return kept;
    }

    private static String held(NativePaidSkillsGameTests.Fixture f) {
        var inventory = f.player().getInventory();
        var parts = new ArrayList<String>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) parts.add(inventory.getStack(slot).getCount() + " " + Valuables.id(inventory.getStack(slot)));
        }
        return String.join(", ", parts);
    }

    private static void kit(NativePaidSkillsGameTests.Fixture f) {
        var inventory = f.player().getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
        inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
        inventory.setStack(2, new ItemStack(Items.BREAD, 8));
        inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 16));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lootPortal", tickLimit = 2400)
    public void aRuinedPortalsChestIsLootedOfWhatIsWorthTakingItsGoldMinedAndAnOpenedChestLeftAlone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[4]; // the portal's chest, two gold blocks, the opened chest
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Looter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos b = f.player().getBlockPos().toImmutable();
                    // Open ground from the player to the portal, a stone floor and air above it: the test's structure is
                    // walled round with barriers, which stand between the player and anything laid outside it.
                    for (int x = -16; x <= 7; x++) for (int z = -5; z <= 5; z++) {
                        world.setBlockState(b.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 4; y++) world.setBlockState(b.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // A ruined portal twelve blocks west: a broken frame of obsidian and crying obsidian on netherrack,
                    // two blocks of gold, and its chest, nobody's yet. (No magma: its burn is a reflex of its own.)
                    BlockPos p = b.add(-12, 0, 0);
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) world.setBlockState(p.add(x, -1, z), Blocks.NETHERRACK.getDefaultState());
                    for (int y = 0; y <= 3; y++) world.setBlockState(p.add(-1, y, 2), Blocks.OBSIDIAN.getDefaultState());
                    world.setBlockState(p.add(0, 0, 2), Blocks.CRYING_OBSIDIAN.getDefaultState());
                    world.setBlockState(p.add(1, 0, 2), Blocks.OBSIDIAN.getDefaultState());
                    at[1] = p.add(2, 0, 1);
                    at[2] = p.add(-2, 0, 1);
                    world.setBlockState(at[1], Blocks.GOLD_BLOCK.getDefaultState());
                    world.setBlockState(at[2], Blocks.GOLD_BLOCK.getDefaultState());
                    at[0] = p.add(0, 0, -1);
                    world.setBlockState(at[0], Blocks.CHEST.getDefaultState());
                    ((ChestBlockEntity) world.getBlockEntity(at[0])).setLootTable(LootTables.RUINED_PORTAL_CHEST, 20261025L);
                    // Five blocks east, a chest someone has opened and filled: three diamonds in it, never to be taken.
                    at[3] = b.add(5, 0, 1);
                    world.setBlockState(at[3], Blocks.CHEST.getDefaultState());
                    ((ChestBlockEntity) world.getBlockEntity(at[3])).setStack(0, new ItemStack(Items.DIAMOND, 3));
                    world.setTimeOfDay(1000);
                    kit(f);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var chest = (ChestBlockEntity) context.getWorld().getBlockEntity(at[0]);
                    context.assertTrue(chest.getLootTable() == null, "The ruined portal's chest opened: "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var world = context.getWorld();
                    boolean mined = world.getBlockState(at[1]).isAir() && world.getBlockState(at[2]).isAir();
                    context.assertTrue(mined && f.player().getInventory().count(Items.GOLD_BLOCK) >= 2,
                            "Its gold blocks mined and kept: " + held(f) + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var chest = (ChestBlockEntity) context.getWorld().getBlockEntity(at[0]);
                    var kept = notLeft(f, chest);
                    context.assertTrue(kept.isEmpty(), "Nothing worth taking left in it: " + kept);
                    var opened = (ChestBlockEntity) context.getWorld().getBlockEntity(at[3]);
                    context.assertTrue(opened.getStack(0).isOf(Items.DIAMOND) && opened.getStack(0).getCount() == 3,
                            "The chest someone opened left as it was: " + opened.getStack(0));
                    context.assertTrue(f.player().getInventory().count(Items.DIAMOND) == 0, "None of its diamonds taken");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Loot fixture: the player holds {}", held(f));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lootCart", tickLimit = 2400)
    public void aChestMinecartInAMineshaftsTunnelIsLootedOfWhatIsWorthTaking(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        ChestMinecartEntity[] cart = new ChestMinecartEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Delver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos b = f.player().getBlockPos().toImmutable();
                    // A mineshaft's tunnel running east, three wide and three high in stone, torches along it, rails down
                    // its middle, and a chest minecart on them eleven blocks along.
                    for (int x = -2; x <= 14; x++) for (int z = -2; z <= 2; z++) for (int y = -1; y <= 3; y++) {
                        boolean inside = x >= -1 && x <= 13 && Math.abs(z) <= 1 && y >= 0 && y <= 2;
                        world.setBlockState(b.add(x, y, z), inside ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    for (int x = -1; x <= 13; x++) world.setBlockState(b.add(x, 0, 0), Blocks.RAIL.getDefaultState());
                    for (int x = 0; x <= 12; x += 4) world.setBlockState(b.add(x, 2, -1), Blocks.TORCH.getDefaultState());
                    cart[0] = EntityType.CHEST_MINECART.create(world);
                    BlockPos c = b.add(11, 0, 0);
                    cart[0].refreshPositionAndAngles(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0, 0);
                    cart[0].setLootTable(LootTables.ABANDONED_MINESHAFT_CHEST, 20261025L);
                    world.spawnEntity(cart[0]);
                    world.setTimeOfDay(1000);
                    kit(f);
                    var player = f.player();
                    player.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 1.5, -90, 0);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(cart[0].getLootTable() == null, "The cart opened: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var kept = notLeft(f, cart[0]);
                    context.assertTrue(kept.isEmpty(), "Nothing worth taking left in the cart: " + kept);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Loot fixture: the player holds {}", held(f));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
