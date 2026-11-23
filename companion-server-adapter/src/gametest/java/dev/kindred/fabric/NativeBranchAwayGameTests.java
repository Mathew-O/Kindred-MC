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

import java.util.HashSet;
import java.util.Set;

/**
 * A branch mine begun inside a stuck spell lays its legs away from where the spell was caught (F-25c4dq). Main's own holepit
 * scene, `diag-holepit-079bdc2-8675309-kindred-111140`, fails about one run in five, still in its stuck spell. On the runs
 * read (LOOPS's gate runs and REFACTOR's #501 runs), a branch mine for iron begun within about ten of where the spell was
 * caught laid its first leg across that place: legs twenty long, three apart, alternating, so leg after leg swept back
 * through the eight blocks round it, every step fresh rock and no iron found. Near starts failed 7 of 16 runs, far starts 10
 * of 62. In E-40 iteration 29, 8 of 112 stuck spells were such a working mine, 24 of 375 stuck minutes.
 */
public final class NativeBranchAwayGameTests implements FabricGameTest {
    private static final int FIELD = 40;

    /**
     * In plain terms: an AI player that has been stuck, and starts a branch mine for iron close to where it got stuck, mines
     * away from that place rather than back across it.
     *
     * Technical: F-25c4dq. STAGED as NativeBranchMineGameTests' slab: stone round it with no ore, a pocket two high, the
     * morning held, the sprint, and its kit as though made by the iron stage (stone pickaxes, a stone sword, bread,
     * torches, coal, sticks, cobblestone, a table and a furnace). STAGED through the campaign's fixture setter: a stuck spell
     * caught 3 east and 9 north of the pocket (its level one, as the watchdog's count of one leaves it), and its digs heading
     * north, toward the spell's place, as the holepit scene's failing runs began. Its watchdog's own count held off (a
     * stuck spell of its own in the middle would move it elsewhere). Fails if, once its branch mine for iron has begun, its
     * feet come within eight blocks across of where the spell was caught before it has dug 30 blocks of the mine.
     */
    @GameTest(templateName = FixtureGround.WIDE, batchId = "branchAway", tickLimit = 8000)
    public void aBranchMineBegunInAStuckSpellLaysItsLegsAwayFromWhereTheSpellWasCaught(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 7800);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 8, 60));
        BlockPos caught = o.add(3, 0, -9);
        Set<Long> dug = new HashSet<>();
        int[] settle = new int[1];
        int[] nearest = {Integer.MAX_VALUE};
        String[] verdict = {null};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Delver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-FIELD, -2, -FIELD), o.add(FIELD, 4, FIELD));
                    FixtureGround.simulated(context, o.add(-FIELD, 0, -FIELD), o.add(FIELD, 0, FIELD));
                    for (int x = -FIELD; x <= FIELD; x++) for (int z = -FIELD; z <= FIELD; z++) for (int y = -2; y <= 4; y++) {
                        boolean pocket = x == 0 && z == 0 && (y == 0 || y == 1);
                        world.setBlockState(o.add(x, y, z), pocket ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 180, 0);
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
                .createAndAdd(() -> {
                    var ticks = context.getWorld().getServer().getTickManager();
                    FixtureGround.whenDone(context, ticks::stopSprinting);
                    context.assertTrue(ticks.startSprint(7_600), "Sprinting");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    // STAGED: the spell caught north of it, its digs heading north toward it; its own watchdog held off.
                    var campaign = f.runtime.campaignForTest(f.id);
                    campaign.stuckTicksForTest(1_000_000);
                    campaign.spellCaughtForTest(caught, Direction.NORTH);
                })
                .createAndAdd(() -> {
                    // The first failure is the one reported.
                    context.assertTrue(verdict[0] == null, String.valueOf(verdict[0]));
                    context.getWorld().setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    context.assertTrue(!bots.isEmpty() && f.player() != null && f.player().isAlive(), "Its panel and body");
                    String progress = bots.getFirst().goalProgress();
                    BlockPos feet = f.player().getBlockPos();
                    if (progress.contains("Branch mine for iron") || !dug.isEmpty()) {
                        if (progress.contains("Branch mine for iron") && dug.add(feet.asLong()) && dug.size() % 5 == 1) {
                            seen.append(dug.size()).append(": ").append(feet.subtract(o).toShortString()).append("; ");
                        }
                        int across = Math.max(Math.abs(feet.getX() - caught.getX()), Math.abs(feet.getZ() - caught.getZ()));
                        nearest[0] = Math.min(nearest[0], across);
                        if (across <= 8) {
                            verdict[0] = "Its branch mine came within " + across + " of where the spell was caught, at "
                                    + feet.subtract(o).toShortString() + " from its pocket, after " + dug.size() + " blocks: " + seen;
                        }
                    }
                    context.assertTrue(verdict[0] == null, String.valueOf(verdict[0]));
                    context.assertTrue(dug.size() >= 30, "Branch mining: " + dug.size() + " blocks; " + progress);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Branch mine away: {} blocks, never nearer than {} to where the spell was caught; {}", dug.size(), nearest[0], seen);
                    context.getWorld().getServer().getTickManager().stopSprinting();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
