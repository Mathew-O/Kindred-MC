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
import net.minecraft.util.math.Vec3d;

/**
 * A craft is offered only when its result has room in the pack; otherwise room is made first (F-25c4dn). In E-40 iteration
 * 27 (`claude/corpus-data-e40a27-consoles-1122` at `7b930d48`), batch 1's 404 Kindred, its pack full at y 34, was offered
 * "Craft a spare stone pickaxe" from counts alone; at the table the craft found no room for the result and refused it
 * ("cannot make stone_pickaxe at the table in reach: not the makings"), the plan had nothing, it climbed to find another
 * way, out of reach of the table, and was sent back to it: "Walk to the crafting table" 136 times in eight minutes, two
 * stuck spells. 20 of the 23 such refusals on the four bodies of that loop came with the pack full.
 */
public final class NativeCraftRoomGameTests implements FabricGameTest {
    /**
     * In plain terms: an AI player with a full pack and the makings of a stone pickaxe drops some surplus stone first, then
     * makes the pickaxe, instead of walking back and forth to its crafting table finding no room for it.
     *
     * Technical: F-25c4dn, as 404 Kindred (above). STAGED: stone round it, no sky; a corridor two high and nine long, lit by
     * torches set in its walls, a crafting table at its far end six blocks off (in sight of the plan, which counts a table
     * within sixteen, and out of the craft's reach, 4.4 from its eyes), its own, set down and known as the runtime records
     * one it places; the player at its near end with no pickaxe, four
     * sticks, sixteen cobblestone and twenty-four cobbled deepslate (the makings, and stone to spare beyond what the
     * stone crafts take), eight bread, and every other slot of its pack a full stack of bricks, which nothing uses or drops
     * as surplus; the portal goal; daylight held. Fails unless, within 600 ticks, a slot of its pack is freed and then it
     * holds a stone pickaxe: the drop makes room, and the craft follows (the drop step is MODES's, and in iteration 27 it
     * already alternated with the climb; this fix asks for it, so the fixture checks it does what it is asked for).
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "craftRoom", tickLimit = 1200)
    public void withItsPackFullItMakesRoomThenMakesTheStonePickaxe(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 8, 40));
        BlockPos table = o.add(6, 0, 0);
        int[] watched = new int[1];
        int[] freedAt = {-1};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Packed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // STAGED: stone round it, no sky; the corridor x 0 to 8, y 0 and 1, z 0; torches in its north wall.
                    FixtureGround.keep(context, o.add(-3, -2, -3), o.add(11, 4, 3));
                    for (BlockPos cell : BlockPos.iterate(o.add(-3, -2, -3), o.add(11, 4, 3))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
                    for (BlockPos cell : BlockPos.iterate(o.add(0, 0, 0), o.add(8, 1, 0))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
                    for (int x = 0; x <= 8; x += 4) {
                        world.setBlockState(o.add(x, 1, -1), Blocks.AIR.getDefaultState());
                        world.setBlockState(o.add(x, 1, -1), Blocks.TORCH.getDefaultState());
                    }
                    world.setBlockState(table, Blocks.CRAFTING_TABLE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // STAGED: the makings, stone to spare, food, and the rest of its pack full.
                    inventory.setStack(0, new ItemStack(Items.STICK, 4));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(2, new ItemStack(Items.COBBLED_DEEPSLATE, 24));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    for (int slot = 4; slot < 36; slot++) inventory.setStack(slot, new ItemStack(Items.BRICK, 64));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In place"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    // STAGED: the table its own, set down and known as the runtime records one it places.
                    var campaign = f.runtime.campaignForTest(f.id);
                    campaign.placedWorkstation(table);
                    campaign.knowledge().remember(WorldKnowledge.Kind.CRAFTING_TABLE, table);
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died or is gone; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 50 == 0) seen.append(watched[0]).append(": ").append(progress).append("; ");
                    context.assertTrue(watched[0] <= 600, "No stone pickaxe after 600 ticks with its pack full: " + seen + " now " + progress);
                    var inventory = player.getInventory();
                    boolean pickaxe = false;
                    for (int i = 0; i < inventory.size(); i++) pickaxe |= inventory.getStack(i).isOf(Items.STONE_PICKAXE);
                    if (freedAt[0] < 0 && !pickaxe) {
                        for (int i = 0; i < 36; i++) if (inventory.getStack(i).isEmpty()) { freedAt[0] = watched[0]; break; }
                    }
                    context.assertTrue(pickaxe, "No stone pickaxe yet");
                    if (freedAt[0] < 0) throw new AssertionError("A stone pickaxe with no slot of its pack seen freed first: " + seen);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Craft room: a slot freed after {} ticks, a stone pickaxe after {}; {}", freedAt[0], watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
