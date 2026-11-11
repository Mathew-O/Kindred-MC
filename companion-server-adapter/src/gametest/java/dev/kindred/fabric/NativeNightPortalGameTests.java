package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest of the walk to a lit portal at night (MOVEMENT, F-35c1). Sealed in its night shelter below
 * the surface, a player does not dig its way out to its portal in the dark; by day the walk goes a leg at a time, as
 * F-35c made it ({@code NativeDeepPortalGameTests}). On the surface at night it digs in first, before any walk.
 *
 * Technical: after F-35c (#126) the night shelter's fixture ({@code NativeNightShelterGameTests}, F-25c4bx) failed in
 * the suite: on main and on #146 alike, a player dug in five below its portal (seven off, past F-35c's six) was sent
 * "Walk to the portal" and "Make a way to the portal" through the held night, in and out of its shelter, and whether it
 * was up by morning came down to timing. Staged as that fixture stages it: a block of stone with a lit portal on it, the
 * night held, the End goal's kit but for food.
 */
public final class NativeNightPortalGameTests implements FabricGameTest {
    private static final int FIELD = 12;

    /** Sealed in below for the night, its portal seven off: through the held night, no walk to the portal at all. */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "nightPortalSheltered", tickLimit = 3000)
    public void dugInForTheNightBelowItsPortalItSendsNoWalkToThePortal(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int surface = o.getY() + 7;
        int[] watched = new int[1];
        int[] toPortal = new int[1];
        boolean[] down = new boolean[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nightjar"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> staged(context, f, o, surface, o.add(3, 7, 4)))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == surface, "On the stone"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    // The night held; once it is below, every tick its goal is a walk to the portal is counted.
                    var world = context.getWorld();
                    world.setTimeOfDay(14000);
                    var feet = f.player().getBlockPos();
                    if (feet.getY() <= surface - 2 && !world.isSkyVisible(feet.up())) down[0] = true;
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (down[0] && (goal.contains(StrongholdLeg.TO_PORTAL) || goal.contains("Make a way to the portal"))) {
                        if (toPortal[0]++ < 3) seen.append(watched[0]).append(": ").append(goal).append(" @ ").append(feet.toShortString()).append("; ");
                    }
                    context.assertTrue(down[0] && ++watched[0] >= 1500, "Through the night, below: " + watched[0]);
                })
                .createAndAdd(() -> {
                    context.assertTrue(toPortal[0] == 0, "No walk to its portal from its shelter in the night: " + toPortal[0] + " ticks of one; " + seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The night shelter fixture's ground and kit, its portal's frame at {@code frame}. */
    private static void staged(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos o, int surface, BlockPos frame) {
        var world = context.getWorld();
        int far = Math.max(FIELD, frame.getX() - o.getX() + 4);
        FixtureGround.keep(context, o.add(-FIELD, -2, -FIELD), o.add(far, 14, FIELD));
        FixtureGround.simulated(context, o.add(-FIELD, 0, -FIELD), o.add(far, 0, FIELD));
        for (int x = -FIELD; x <= far; x++) for (int z = -FIELD; z <= FIELD; z++) for (int y = -2; y <= 14; y++) {
            world.setBlockState(o.add(x, y, z), (y <= 6 ? Blocks.STONE : Blocks.AIR).getDefaultState());
        }
        for (int x = 0; x <= 3; x++) {
            world.setBlockState(frame.add(x, 0, 0), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(frame.add(x, 4, 0), Blocks.OBSIDIAN.getDefaultState());
        }
        for (int y = 1; y <= 3; y++) {
            world.setBlockState(frame.add(0, y, 0), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(frame.add(3, y, 0), Blocks.OBSIDIAN.getDefaultState());
        }
        var portal = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, Direction.Axis.X);
        for (int x = 1; x <= 2; x++) for (int y = 1; y <= 3; y++) {
            world.setBlockState(frame.add(x, y, 0), portal, net.minecraft.block.Block.FORCE_STATE | net.minecraft.block.Block.NOTIFY_LISTENERS);
        }
        var player = f.player();
        player.refreshPositionAndAngles(o.getX() + 0.5, surface, o.getZ() + 0.5, 0, 0);
        player.setVelocity(Vec3d.ZERO);
        var inventory = player.getInventory();
        inventory.clear();
        for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
        inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
        inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
        inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
        inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 64));
        inventory.setStack(4, new ItemStack(Items.TORCH, 16));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        world.setTimeOfDay(14000);
    }
}
