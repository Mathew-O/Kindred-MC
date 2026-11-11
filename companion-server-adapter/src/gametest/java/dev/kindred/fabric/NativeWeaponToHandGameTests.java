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

/**
 * In plain terms: a player whose sword has ended up in its backpack, its hotbar full of odds and ends, takes the sword
 * to hand and fights with it when a zombie comes after it, rather than backing off from it as though it had none.
 *
 * Technical: F-25c4cv. The goal's fights strike with a hotbar weapon only, and {@code armed} looks at the hotbar only.
 * In E-40's iteration 16 (`e40a16-922f4fd`) on 707 Kindred, its stone sword in slot 15 behind dirt, sticks, arrows,
 * bones, rabbit hide, rotten flesh and two pickaxes, backed off from three zombies and struck them with its stone
 * pickaxe, and they killed it; four of the iteration's 23 deaths carried a sword off the hotbar. Staged: the kit put in
 * those slots, as the death's replay held it, and a zombie set on the player.
 */
public final class NativeWeaponToHandGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "weaponToHand", tickLimit = 1200)
    public void aSwordInItsBackpackIsTakenToHandAndFoughtWith(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        // The item in its hand each time the zombie was hit, and whether the sword ever reached the hotbar.
        var struckWith = new java.util.ArrayList<String>();
        boolean[] onHotbar = {false};
        int[] lastHurt = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) context.setBlockState(x, 4, z, Blocks.STONE);
                    f.runtime.createNative(f.source, "Backpack");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The hotbar as 707's Kindred held it, the sword behind it in the backpack.
                    inventory.setStack(0, new ItemStack(Items.DIRT, 24));
                    inventory.setStack(1, new ItemStack(Items.SPRUCE_LOG, 4));
                    inventory.setStack(2, new ItemStack(Items.STICK, 50));
                    inventory.setStack(3, new ItemStack(Items.ARROW, 2));
                    inventory.setStack(4, new ItemStack(Items.BONE, 2));
                    inventory.setStack(5, new ItemStack(Items.RABBIT_HIDE, 1));
                    inventory.setStack(6, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(7, new ItemStack(Items.ROTTEN_FLESH, 2));
                    inventory.setStack(8, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(9, new ItemStack(Items.COBBLESTONE, 56));
                    inventory.setStack(10, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(15, new ItemStack(Items.STONE_SWORD));
                    inventory.selectedSlot = 8;
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    zombie[0] = context.spawnMob(net.minecraft.entity.EntityType.ZOMBIE, new net.minecraft.util.math.Vec3d(1.5, 2, 6.5));
                    zombie[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Its body, alive");
                    for (int s = 0; s < 9; s++) if (player.getInventory().getStack(s).isOf(Items.STONE_SWORD)) onHotbar[0] = true;
                    if (zombie[0].hurtTime > lastHurt[0]) {
                        var hand = player.getMainHandStack();
                        struckWith.add(hand.isEmpty() ? "hand" : net.minecraft.registry.Registries.ITEM.getId(hand.getItem()).getPath());
                    }
                    lastHurt[0] = zombie[0].hurtTime;
                    context.assertTrue(!zombie[0].isAlive(), "Still fighting; struck with " + struckWith);
                    context.assertTrue(onHotbar[0], "The sword never reached its hotbar; struck with " + struckWith + ", "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    context.assertTrue(struckWith.contains("stone_sword"), "Never struck with the sword: " + struckWith);
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
