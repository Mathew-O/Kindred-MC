package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
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

/** Dug in for the night with nothing to do before daylight: it stays in, sealed, and waits (F-25c4bx). */
public final class NativeNightShelterGameTests implements FabricGameTest {
    private static final int FIELD = 12;

    /**
     * Night on open ground beside its lit portal, with the End goal's next step food for the Nether trip and no food
     * carried: it digs in for the night and stays below, its way in sealed, until morning, when it goes up to look for
     * animals. In E-40's iteration 10 (`e40a10-eed2dc5`) the commonest stuck cause, 15 of 96 spells, was the night's
     * dig-in undone: dug in a few blocks below the surface with no step it could take from there before daylight, the
     * last fallback climbed it out "to find another way", where it was out under the sky at night and dug in again,
     * up and down a step a second (Kindred on 202 at the cast, seven spells, and on 8675309 six). In the first End-goal
     * probe (`e40c1-eed2dc5`) Sol, its portal lit on 101, did the same through a whole night with this step, 408 climbs
     * and 196 dig-ins, its food step sending it up to look for animals at night besides. Staged: a block of stone with a
     * lit portal on it, the night held, and the End goal's kit but for food; open to the sky, as the ground is, with no
     * barriers over the field (under them the ground's top is the barriers', and the shelter seemed deep).
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "nightShelterStays", tickLimit = 4000)
    public void dugInForTheNightWithNothingToDoBeforeDaylightItStaysInAndWaits(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int surface = o.getY() + 7;
        int[] watched = new int[1];
        int[] surfaced = new int[1];
        boolean[] down = new boolean[1];
        boolean[] waited = new boolean[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nighthawk"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> stage(context, f, o, surface))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == surface, "On the stone"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    // The night held: once below, each time it is back up at the surface is the night's dig-in undone.
                    var world = context.getWorld();
                    world.setTimeOfDay(14000);
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    boolean below = feet.getY() <= surface - 2 && !world.isSkyVisible(feet.up());
                    if (below) down[0] = true;
                    else if (down[0] && feet.getY() >= surface) {
                        surfaced[0]++;
                        down[0] = false;
                    }
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    waited[0] |= goal.contains("Wait for daylight");
                    if (watched[0] % 100 == 0) seen.append(watched[0]).append(": ").append(goal).append(" @ ").append(feet.toShortString()).append("; ");
                    context.assertTrue(surfaced[0] < 2, "Out of its night shelter and dug in again, " + surfaced[0] + " times: " + seen);
                    context.assertTrue(++watched[0] >= 1500, "Through the night");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(surfaced[0] == 0, "Out of its night shelter " + surfaced[0] + " times: " + seen);
                    context.assertTrue(player.getBlockY() <= surface - 2, "Below for the night: at " + player.getBlockPos().toShortString() + " - " + goal);
                    context.assertTrue(waited[0] && goal.contains("Wait for daylight") || goal.contains("Seal the shelter"),
                            "Waiting in its shelter for daylight: " + goal);
                    context.getWorld().setTimeOfDay(1000);
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(f.player().getBlockY() >= surface - 1 || goal.contains("look for animals"), "Morning: up to look for animals: " + goal);
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The same scene, a short night and then the morning: the way it makes from its shelter up to its portal is not
     * blocked by its own torch. In every run of the test above, its way out in the morning dug the cell beside it, its
     * next stand, and "light the way" put a torch there; it walked on into that cell, and its pillar up from there was
     * refused, "The cell it stands in is not open". About one run in 15 it did not find another way up before the test's
     * morning check gave up (F-22c70). Staged: the night held for 1500 ticks, as above, then morning. Fails once its way's
     * pillar up is refused, "The cell it stands in is not open"; passes once it is back up at the surface.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "nightShelterWayTorch", tickLimit = 4000)
    public void itsWayUpFromItsShelterIsNotBlockedByItsOwnTorch(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int surface = o.getY() + 7;
        int[] watched = new int[1];
        boolean[] down = new boolean[1];
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        String[] blockedAt = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nighthawk"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> stage(context, f, o, surface))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == surface, "On the stone"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    if (++watched[0] <= 1500) world.setTimeOfDay(14000);
                    else if (watched[0] == 1501) world.setTimeOfDay(1000);
                    if (feet.getY() <= surface - 2) down[0] = true;
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!goal.equals(last[0]) && seen.length() < 2500) seen.append(watched[0]).append(": ").append(goal.replaceAll("^.* - ", "")).append(" @ ").append(feet.toShortString()).append("; ");
                    last[0] = goal;
                    // The pillar's refusal and the next decision come within a tick, so the refusal is read from the
                    // campaign's own list of steps refused lately, which keeps the last few.
                    if (blockedAt[0] == null) {
                        for (String refused : f.runtime.campaignForTest(f.id).blockedForTest()) {
                            if (refused.contains("pillar up") && refused.contains("The cell it stands in is not open")) {
                                blockedAt[0] = watched[0] + ": " + refused + " (" + (world.getBlockState(feet).isOf(Blocks.TORCH) ? "a torch" : "no torch")
                                        + " in its cell at " + feet.toShortString() + ")";
                            }
                        }
                    }
                    context.assertTrue(blockedAt[0] == null, "Its pillar up refused on the way it is making, at " + blockedAt[0] + ": " + seen);
                    context.assertTrue(watched[0] > 1501 && down[0] && feet.getY() >= surface - 1, "Out of its shelter and up in the morning: " + seen);
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The scene: a block of stone with a lit portal on it four blocks off, the player on it, the End goal's kit but for food, at night. */
    private static void stage(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos o, int surface) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-FIELD, -2, -FIELD), o.add(FIELD, 14, FIELD));
        FixtureGround.simulated(context, o.add(-FIELD, 0, -FIELD), o.add(FIELD, 0, FIELD));
        for (int x = -FIELD; x <= FIELD; x++) for (int z = -FIELD; z <= FIELD; z++) for (int y = -2; y <= 14; y++) {
            world.setBlockState(o.add(x, y, z), (y <= 6 ? Blocks.STONE : Blocks.AIR).getDefaultState());
        }
        // Its portal, lit, on the stone four blocks off: obsidian round a 2 by 3 opening along X.
        BlockPos frame = o.add(3, 7, 4);
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
        // As the portal stage leaves it: a sword, two pickaxes, blocks and torches; no food.
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
