package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A fight with an edge at the player's back (F-25c6, from staged run 9, where a hoglin's toss threw the player into the
 * lava at a fortress's foot): a hoglin stands in reach in front of it, with a lava trench right behind it. A player
 * steps round the mob, off the edge, before trading hits; so must the native player, and it never goes in.
 */
public final class NativeEdgeFightGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "edgeFight", tickLimit = 900)
    public void aHoglinWithLavaAtThePlayersBackNeverThrowsItIn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        HoglinEntity[] hoglin = new HoglinEntity[1];
        boolean[] burned = {false};
        int[] edgeTicks = {0}, hitsTaken = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Edgewise"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (int x = -5; x <= 9; x++) for (int z = -3; z <= 9; z++) {
                        boolean trench = x >= -3 && x <= -1;
                        world.setBlockState(context.getAbsolutePos(new BlockPos(x, 0, z)), Blocks.STONE.getDefaultState());
                        world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), trench ? Blocks.LAVA.getDefaultState() : Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 5; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(0, 2, 3));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, -90, 0);
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_SWORD));
                    player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                    hoglin[0] = EntityType.HOGLIN.create(world);
                    // Within reach from the start: the player trades hits where it stands, its back to the lava.
                    BlockPos at = context.getAbsolutePos(new BlockPos(2, 2, 3));
                    hoglin[0].refreshPositionAndAngles(at.getX() + 0.3, at.getY(), at.getZ() + 0.5, 90, 0);
                    hoglin[0].setImmuneToZombification(true); // Out of the Nether it would turn into a zoglin.
                    hoglin[0].setPersistent();
                    world.spawnEntity(hoglin[0]);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeFight(f.source, f.id, hoglin[0].getUuid(), 0) == 1, "Fight requested"))
                .createAndAdd(() -> {
                    var player = f.player();
                    burned[0] |= player.isInLava();
                    if (player.hurtTime == 9) hitsTaken[0]++;
                    if (player.getX() < context.getAbsolutePos(BlockPos.ORIGIN).getX() + 0.7) edgeTicks[0]++;
                    context.assertFalse(burned[0], "Thrown into the lava");
                    context.assertTrue(!hoglin[0].isAlive() || !player.isAlive(), "The fight goes on: hoglin health " + hoglin[0].getHealth()
                            + ", player " + player.getHealth());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    KindredMod.LOGGER.info("Edgewise: hoglin dead {}, player health {}, hits taken {}, ticks with its back to the lava {}, in lava {}",
                            !hoglin[0].isAlive(), player.getHealth(), hitsTaken[0], edgeTicks[0], burned[0]);
                    context.assertTrue(player.isAlive() && !burned[0], "It never went into the lava");
                    // With the rule set aside it fought there for 81 ticks, its back to the lava, in three runs of three.
                    context.assertTrue(edgeTicks[0] <= 20, "Off the edge within a second, not trading hits there: " + edgeTicks[0] + " ticks");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
