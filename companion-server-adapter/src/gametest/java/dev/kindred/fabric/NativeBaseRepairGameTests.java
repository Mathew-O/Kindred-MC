package dev.kindred.fabric;

import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: in a real test world, a player given all the materials for its cabin builds it
 * as its base; the test then breaks a chest in it and blows a hole in its north wall, and checks the player
 * notices and puts back every missing block, the chest included, exactly to the cabin's plan.
 *
 * Technical: What a player built it keeps whole (E-31a). Its cabin built as its base, a chest of it broken
 * with nothing left of it and its north wall blown out, the player sees its base out of place and builds what
 * is missing back on the cabin's own plan, with what it holds, what the blast dropped and a chest it crafts
 * from planks.
 */
public final class NativeBaseRepairGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "base-repair", tickLimit = 30000)
    public void aPlayerRepairsItsOwnCabinWhenAChestIsBrokenAndAWallBlownOut(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 29500);
        var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        BlockPos[] origin = {null};
        int[] damage = {0};
        boolean[] repairing = {false}, whole = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, trunks);
                    // By day: the suite's clock runs on, and at night a player with a bed of its own sleeps (F-25c9).
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The whole cabin's bill as though gathered (its furnace made), and what is left over from
                    // gathering it: planks, logs, cobblestone, a torch and a bed besides. No spare chest.
                    ItemStack[] held = {
                            new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_PLANKS, 64),
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_LOG, 16), new ItemStack(Items.OAK_STAIRS, 42),
                            new ItemStack(Items.OAK_SLAB, 7), new ItemStack(Items.OAK_DOOR, 1), new ItemStack(Items.RED_BED, 2),
                            new ItemStack(Items.CRAFTING_TABLE, 2), new ItemStack(Items.CHEST, 2), new ItemStack(Items.TORCH, 2),
                            new ItemStack(Items.FURNACE, 1), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 32),
                            new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                    // Its base on the first plan in oak, as this fixture's bill is made for, not grown (E-29a).
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                })
                .createAndAdd(() -> {
                    var base = f.runtime.currentBase(f.id);
                    context.assertTrue(base.isPresent() && base.get().complete() && f.runtime.baseNote(f.id).startsWith("Iron tools first"),
                            "Its cabin built: " + base + " / " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.baseNote(f.id));
                    origin[0] = new BlockPos(base.get().x(), base.get().y(), base.get().z());
                    NativeBaseGameTests.exact(context, cabin, origin[0]);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    // Away from what is to come.
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 14.5, 0, 0);
                    // A chest broken, its block gone with nothing left of it; then a blast, as a creeper's, outside the
                    // middle of the north wall at its windows' height.
                    world.breakBlock(origin[0].add(5, 1, 4), false);
                    world.createExplosion(null, origin[0].getX() + 3.5, origin[0].getY() + 2.5, origin[0].getZ() - 0.5, 3.0f,
                            net.minecraft.world.World.ExplosionSourceType.TNT);
                    for (var b : cabin.placed(BuildRunner.pos(origin[0]), 0)) {
                        if (!BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at()))).sameAs(b.spec())) damage[0]++;
                    }
                    KindredMod.LOGGER.info("Base repair: {} of the cabin's blocks out of place", damage[0]);
                    context.assertTrue(damage[0] > 4 && world.getBlockState(origin[0].add(5, 1, 4)).isAir(), "Damaged: " + damage[0]);
                })
                .createAndAdd(() -> {
                    // Its repair begun, then the cabin whole: what the world shows, not the note it ended on, which its
                    // next look at its base may overwrite in the same tick (suite run 47).
                    String note = f.runtime.baseNote(f.id);
                    if (note.startsWith("Repairing its cabin")) repairing[0] = true;
                    if (note.equals("Its cabin is whole again")) whole[0] = true;
                    var world = context.getWorld();
                    int out = 0;
                    for (var b : cabin.placed(BuildRunner.pos(origin[0]), 0)) {
                        if (!BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at()))).sameAs(b.spec())) out++;
                    }
                    context.assertTrue(repairing[0] && out == 0 && f.runtime.buildStatus(f.id).isEmpty(),
                            "Repaired: " + out + " out of place / " + note + " / " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    NativeBaseGameTests.exact(context, cabin, origin[0]);
                    var chest = context.getWorld().getBlockState(origin[0].add(5, 1, 4));
                    context.assertTrue(chest.isOf(Blocks.CHEST), "The chest replaced: " + chest);
                    KindredMod.LOGGER.info("Base repair: the cabin whole again, {} blocks put back (its note seen: {})", damage[0], whole[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
