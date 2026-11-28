package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * In plain terms: a GameTest: in a real test world, steps on the way from a lit Nether portal towards the End
 * portal are each checked on their own. A player throws an eye of ender and its flight is watched, with
 * exactly one eye spent (or the eye kept when the world has no stronghold); a player saved far off in ground
 * that has since unloaded reconnects after a restart where it was, with what it had; and a player stocked for
 * the trip walks through a lit portal into the Nether and starts looking for a fortress.
 *
 * Technical: The goal's way on from a lit Nether portal toward the End portal (F-25c6), one part at a time.
 * Fixtures build the ground and the portal and hand over what a player would have earned by then; everything
 * the player does with them goes through the goal, the paid activity and the ledger as in a run.
 */
public final class NativeStrongholdGameTests implements FabricGameTest {
    /** Stocked for the trip: the food, blocks, spare pickaxe and sword the plan asks for before going through. */
    static void stockedForTheNether(net.minecraft.server.network.ServerPlayerEntity player) {
        var inventory = player.getInventory();
        inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
        inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
        inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
        inventory.setStack(3, new ItemStack(Items.BREAD, 16));
        inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
    }

    /** A lit 4x5 portal on a stone floor, its interior along X at z = 3 above y = 2, as the plot's own. */
    static BlockPos litPortal(TestContext context) {
        var world = context.getWorld();
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
            for (int y = 2; y <= 7; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
        }
        for (int x = 2; x <= 5; x++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, 2, 3)), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, 6, 3)), Blocks.OBSIDIAN.getDefaultState());
        }
        for (int y = 3; y <= 5; y++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(2, y, 3)), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(context.getAbsolutePos(new BlockPos(5, y, 3)), Blocks.OBSIDIAN.getDefaultState());
        }
        var portal = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, Direction.Axis.X);
        for (int x = 3; x <= 4; x++) for (int y = 3; y <= 5; y++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, 3)), portal, net.minecraft.block.Block.FORCE_STATE | net.minecraft.block.Block.NOTIFY_LISTENERS);
        }
        return context.getAbsolutePos(new BlockPos(3, 3, 3));
    }

    /**
     * One eye thrown and watched under the ledger. Where the world has a stronghold the eye flies off toward it and
     * the throw reports where it was thrown from and the heading it took, with exactly one eye spent; where it has
     * none, vanilla keeps the eye and the step says so, with nothing spent. Either way the ledger and the inventory
     * agree.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endEyeThrow", tickLimit = 400)
    public void anEyeOfEnderIsThrownAndItsFlightWatched(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        int[] flew = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Seer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.ENDER_EYE, 2));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeThrowEye(f.source, f.id, 0, 90f) == 1, "Throw requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    boolean flown = activity.work().startsWith("Completed native_throw_eye");
                    boolean kept = activity.work().contains("No stronghold for the eye to fly to");
                    context.assertTrue(activity.phase().equals("IDLE") && (flown || kept), "The throw settles: " + activity);
                    flew[0] = flown ? 1 : 0;
                })
                .createAndAdd(() -> {
                    var result = f.runtime.nativeLastResult(f.id);
                    int eyes = f.player().getInventory().count(Items.ENDER_EYE);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    KindredMod.LOGGER.info("Seer throw: flew {} result {} eyes {} record {}", flew[0], result, eyes, record);
                    if (flew[0] == 1) {
                        context.assertTrue(result.containsKey("dx") || "unseen".equals(result.get("heading")), "A flight reported: " + result);
                        context.assertTrue(eyes <= 1, "One eye spent (and maybe picked up again): " + eyes);
                        context.assertTrue(record != null && record.state() == ActionRecord.State.VERIFIED, "Verified throw: " + record);
                    } else {
                        context.assertTrue(eyes == 2, "No stronghold to fly to: the eye is kept, " + eyes);
                        context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "Nothing uncertain: " + record);
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A restart with the body far from loaded ground, as when a server stops while its player is in the Nether: the
     * body's chunk is loaded as a joining player's is, and it reconnects where it was with what it had. Before, the
     * connection found the chunk unloaded and blocked the player for reconciliation (staged End run 7). The body is
     * moved off for the fixture; the reconnect is the ordinary one.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "farReconnect", tickLimit = 1200)
    public void aBodySavedInAnUnloadedChunkReconnectsAfterARestart(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var world = context.getWorld();
        BlockPos[] far = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Farfarer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    BlockPos here = f.player().getBlockPos();
                    int x = here.getX() + 4096, z = here.getZ() + 4096;
                    world.getChunk(x >> 4, z >> 4); // Generated for the fixture; the body stands on it.
                    far[0] = new BlockPos(x, world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z), z);
                    var player = f.player();
                    player.refreshPositionAndAngles(x + 0.5, far[0].getY(), z + 0.5, 0, 0);
                    player.getInventory().setStack(0, new ItemStack(Items.ENDER_EYE, 3));
                })
                .createAndAdd(() -> context.assertTrue(f.player().getBlockPos().getSquaredDistance(far[0]) <= 4, "Far off: " + f.player().getPos()))
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Saved and disconnected"))
                .createAndAdd(() -> context.assertFalse(world.isChunkLoaded(far[0]), "Its chunk unloads with no one there"))
                .createAndAdd(() -> {
                    f.runtime = new PlayerRuntime(world.getServer(), f.directory);
                    f.runtime.start();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Restarted"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 1, "Reconnect requested"))
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.connected() && status.phase() == dev.kindred.persistence.StoredNativePlayer.Phase.ESTABLISHED,
                            "Reconnected, not blocked: " + status + " - " + f.runtime.panelBots(Actor.console()).getFirst());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getBlockPos().getSquaredDistance(far[0]) <= 4, "Where it was: " + player.getBlockPos().toShortString());
                    context.assertTrue(player.getInventory().count(Items.ENDER_EYE) == 3, "With what it had");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Stocked for the trip and a lit portal in sight: the End goal walks in, stands in it until it crosses, and in
     * the Nether goes looking for a fortress. Only the goal moves it; the fixture's player starts five blocks off.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endPortalCrossing", tickLimit = 1200)
    public void theEndGoalGoesThroughItsLitPortalToLookForAFortress(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] inside = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wayfarer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    inside[0] = litPortal(context);
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(3, 2, 7));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180, 0);
                    stockedForTheNether(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.player().getServerWorld().getRegistryKey() == World.NETHER,
                            "Through to the Nether: " + f.player().getBlockPos().toShortString() + " - "
                                    + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(progress.startsWith("End: Fortress"), "Looking for a fortress: " + progress);
                    // Still itself over there: established, connected and at work, not blocked for reconciliation by
                    // the save it made on crossing (the staged End run's player was, and stood still in the Nether).
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.phase() == dev.kindred.persistence.StoredNativePlayer.Phase.ESTABLISHED && status.connected(),
                            "Established and connected after crossing: " + status);
                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).work().contains("native_goto")
                            || f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("ACTIVE"),
                            "At work in the Nether: " + f.runtime.nativeActivityStatus(Actor.console(), f.id));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * F-25c6e: with the rods done and no pearl coming, home through its portal, then pearls hunted in the Overworld, not
     * back to the Nether. STAGED: seven blaze rods given (said so: the rods are not earned here), and the pearl hunt's
     * clock set back past its ten-minute stall once the hunt has begun ({@code pearlHuntBegunForTest}); the walk in, the
     * hunt, the stall's ending of it, the walk back through the portal and the stage after are the player's own. On
     * main it stays in the Nether in PEARLS (e40c4's 31102026 Sol: 135 game minutes, no pearl, no return).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "returnAfterRods", tickLimit = 3600)
    public void withTheRodsAndNoPearlComingItComesHomeAndHuntsThemThere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3500);
        boolean[] stalled = {false};
        int[] home = {0};
        boolean[] backAgain = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Homebound"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    litPortal(context);
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(3, 2, 7));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180, 0);
                    stockedForTheNether(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.player().getServerWorld().getRegistryKey() == World.NETHER,
                            "Through to the Nether: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    // STAGED: the rods, given once over there (given at home, it would hunt its pearls at home and never go).
                    f.player().getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.BLAZE_ROD, 7));
                    // STAGED, the Nether end: a closed netherrack room laid round the portal it arrived by, the portal and
                    // its frame kept, so no run's ground differs (vanilla's own there, once, had it gather gold far off and
                    // find no walk back to the portal) and nothing in it but the room. Put back as the test ends.
                    // Laid round the portal block nearest it, not its feet: by this tick it may already have stepped off
                    // toward ground of vanilla's (a room round its feet once left the portal behind its wall).
                    var nether = f.player().getServerWorld();
                    BlockPos at = f.player().getBlockPos();
                    BlockPos feet = null;
                    for (BlockPos cell : BlockPos.iterateOutwards(at, 8, 4, 8)) {
                        if (nether.getBlockState(cell).isOf(Blocks.NETHER_PORTAL) && !nether.getBlockState(cell.down()).isOf(Blocks.NETHER_PORTAL)) {
                            feet = cell.toImmutable();
                            break;
                        }
                    }
                    context.assertTrue(feet != null, "The portal it arrived by, near " + at.toShortString());
                    context.assertTrue(Math.abs(at.getX() - feet.getX()) <= 5 && Math.abs(at.getZ() - feet.getZ()) <= 5
                            && at.getY() - feet.getY() >= 0 && at.getY() - feet.getY() <= 3, "Still in the room's bounds: " + at.toShortString());
                    FixtureGround.keep(context, nether, feet.add(-6, -2, -6), feet.add(6, 5, 6));
                    for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -2; dy <= 5; dy++) {
                        BlockPos cell = feet.add(dx, dy, dz);
                        var state = nether.getBlockState(cell);
                        if (state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.OBSIDIAN)) continue;
                        boolean shell = Math.abs(dx) == 6 || Math.abs(dz) == 6 || dy <= -1 || dy == 5;
                        nether.setBlockState(cell, (shell ? Blocks.NETHERRACK : Blocks.AIR).getDefaultState());
                    }
                })
                .createAndAdd(() -> {
                    var leg = f.runtime.campaignForTest(f.id).stronghold();
                    context.assertTrue(leg.stage() == dev.kindred.core.survival.StrongholdRunPlanner.Stage.PEARLS,
                            "Hunting pearls with its rods: " + leg.stage() + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    // Hunted off from where it came through, as ten minutes of hunting would take it: standing in the
                    // portal it arrived by, vanilla's portal cooldown keeps it from going back through at once.
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    boolean inPortal = player.getServerWorld().getBlockState(feet).isOf(Blocks.NETHER_PORTAL)
                            || player.getServerWorld().getBlockState(feet.up()).isOf(Blocks.NETHER_PORTAL);
                    context.assertTrue(!inPortal && !player.hasPortalCooldown(), "Out of the portal it came through, its cooldown over: "
                            + feet.toShortString() + ", cooldown " + player.hasPortalCooldown());
                    if (!stalled[0]) {
                        // STAGED: the hunt's clock set back past its stall, once.
                        leg.pearlHuntBegunForTest(StrongholdLeg.PEARL_STALL_TICKS + 20);
                        stalled[0] = true;
                    }
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    f.player().getHungerManager().setFoodLevel(20);
                    context.assertTrue(f.player().getServerWorld().getRegistryKey() == World.OVERWORLD,
                            "Home through its portal: " + f.player().getServerWorld().getRegistryKey().getValue() + " - "
                                    + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    backAgain[0] |= f.player().getServerWorld().getRegistryKey() == World.NETHER;
                    context.assertTrue(++home[0] >= 300, "Home a while: " + home[0] + " ticks");
                })
                .createAndAdd(() -> {
                    var leg = f.runtime.campaignForTest(f.id).stronghold();
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(!backAgain[0], "Never back into the Nether for pearls: " + progress);
                    context.assertTrue(leg.stage() == dev.kindred.core.survival.StrongholdRunPlanner.Stage.PEARLS,
                            "Hunting pearls at home, not the Nether again: " + leg.stage() + " - " + progress);
                    int rods = 0;
                    var inventory = f.player().getInventory();
                    for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(net.minecraft.item.Items.BLAZE_ROD)) rods += inventory.getStack(i).getCount();
                    context.assertTrue(rods == 7, "Home with its seven rods: " + rods);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
