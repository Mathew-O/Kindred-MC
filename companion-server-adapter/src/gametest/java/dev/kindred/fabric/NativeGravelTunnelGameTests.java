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

/** A tunnel through gravel resting over its head cells: never walked into while what rests there comes down (F-25c4bv). */
public final class NativeGravelTunnelGameTests implements FabricGameTest {
    private static final int FIELD = 12;

    /**
     * A branch mine for iron through stone with gravel over the head-level cells two to four blocks out, all round: it
     * waits for what falls into each cell it opens to come to rest before it steps in, and is never buried. In E-40's
     * iteration 9 (`e40a9-dd9b037`) four players suffocated in a wall: Kindred on 20261030, Sol on 606 and Sol on 8675309
     * branch mining at Y=-54 through pockets of gravel in the deepslate, Echo on 8675309 at Y=19. Each cleared the cell
     * ahead at head height and then at its feet, saw both open while the gravel over them was still falling, stepped in,
     * and dug out of the wall as the next block came down onto it, at 1 to 8 health with no food (F-25c4bv). Staged: the
     * stone, a pocket in it, the gravel round it from head height up five blocks, the morning held, and its kit as though
     * made by the iron stage, as the branch mine fixture's.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "gravelTunnel", tickLimit = 4000)
    public void tunnellingUnderGravelItWaitsForWhatFallsBeforeItStepsIn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        String[] buried = {null};
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Burrower"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-FIELD, -2, -FIELD), o.add(FIELD, 6, FIELD));
                    FixtureGround.simulated(context, o.add(-FIELD, 0, -FIELD), o.add(FIELD, 0, FIELD));
                    for (int x = -FIELD; x <= FIELD; x++) for (int z = -FIELD; z <= FIELD; z++) for (int y = -2; y <= 6; y++) {
                        int ring = Math.max(Math.abs(x), Math.abs(z));
                        boolean pocket = x == 0 && z == 0 && (y == 0 || y == 1);
                        boolean gravel = ring >= 2 && ring <= 4 && y >= 1 && y <= 5;
                        world.setBlockState(o.add(x, y, z), pocket ? Blocks.AIR.getDefaultState()
                                : gravel ? Blocks.GRAVEL.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(3, new ItemStack(Items.TORCH, 32));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(6, new ItemStack(Items.FURNACE));
                    inventory.setStack(7, new ItemStack(Items.COAL, 8));
                    inventory.setStack(8, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(9, new ItemStack(Items.STICK, 8));
                    inventory.setStack(10, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(11, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In its pocket"))
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "A moment"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    // The first burial is the one reported: the test cannot pass once it has been in a wall.
                    var player = f.player();
                    context.getWorld().setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    context.assertTrue(!bots.isEmpty() && player != null, "Its panel and body");
                    String progress = bots.getFirst().goalProgress();
                    if (buried[0] == null && (player.isInsideWall() || !player.isAlive())) {
                        buried[0] = "Buried in its tunnel at " + player.getPos() + ", health " + player.getHealth() + ": " + progress;
                    }
                    context.assertTrue(buried[0] == null, buried[0]);
                    BlockPos feet = player.getBlockPos();
                    int out = Math.max(Math.abs(feet.getX() - o.getX()), Math.abs(feet.getZ() - o.getZ()));
                    context.assertTrue(out >= 6, "Through the gravel: " + out + " blocks out at " + player.getPos() + ", health "
                            + player.getHealth() + ": " + progress);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
