package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.TaskPreferences.Priority;
import dev.kindred.core.expedition.ExpeditionObservation;
import dev.kindred.core.expedition.ExpeditionPlan;
import dev.kindred.core.expedition.ExpeditionPlanner;
import dev.kindred.core.expedition.ExpeditionState;
import dev.kindred.core.expedition.ExpeditionStatus;
import dev.kindred.core.expedition.ReturnReason;
import dev.kindred.core.expedition.Waypoint;
import dev.kindred.core.expedition.WaypointKind;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that trips stay within
 * range and head home at nightfall.
 *
 * Technical: Native Fabric GameTests qualifying E-14 (Bounded expeditions, exploration and return planning):
 * 1. Expedition readiness and outward traversal bounded within maximum distance
 * 2. Nightfall return trigger generating urgent traversal back toward home shelter
 * 3. Inventory capacity trigger (full loot slots) initiating return to deposit
 * 4. Waypoint backtracking arrival at home base and shared team landmark registry
 */
public final class NativeExpeditionGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "expeditionOutward", tickLimit = 420)
    public void expeditionReadinessAndOutwardBounding(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos startPos = context.getAbsolutePos(new BlockPos(2, 2, 2));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        f.runnerAwaitingReady(context, "Runtime ready with enabled edits")
                .createAndAdd(() -> f.runtime.createNative(f.source, "ExplorerBound"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Create stone floor for corridor
                    for (int x = 0; x <= 7; x++) {
                        for (int z = 0; z <= 4; z++) {
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 2, z)), Blocks.AIR.getDefaultState());
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 3, z)), Blocks.AIR.getDefaultState());
                        }
                    }

                    f.player().setPosition(startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5);
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD, 1));
                    f.player().getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    f.player().setHealth(20.0f);
                    f.player().getHungerManager().setFoodLevel(20);
                    context.getWorld().setTimeOfDay(1000); // Daytime

                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);

                    // Set expedition state anchored at startPos with radius 5.0
                    Waypoint anchor = Waypoint.of("camp_anchor", startPos.getX(), startPos.getY(), startPos.getZ(),
                            context.getWorld().getRegistryKey().getValue().toString(), WaypointKind.SHELTER, context.getWorld().getTime());
                    ExpeditionState expState = new ExpeditionState(anchor, 5.0);
                    f.runtime.setExpeditionState(f.id, expState);

                    // Ensure daytime immediately before evaluating candidates
                    context.getWorld().setTimeOfDay(1000);

                    // Evaluate candidates
                    var candidates = f.runtime.nativeCandidates(Actor.console(), f.id);
                    var expCandidate = candidates.stream().filter(c -> "native_traverse".equals(c.skill())).findFirst();
                    context.assertTrue(expCandidate.isPresent(), "Outward exploration traverse candidate generated");
                    context.assertTrue(expCandidate.get().candidate().priority() == Priority.ROUTINE, "Outward exploration is ROUTINE priority");

                    // Execute 2-block traverse east
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "east", 2) == 1, "Native traverse east requested");
                    // This fixture verifies one proposed/explicitly dispatched leg. Do not
                    // let another autonomous leg replace its transient completion status.
                    f.runtime.setPlaystyleMode(f.source, f.id,
                            dev.kindred.core.progression.PlaystyleProfile.IDLE_OBSERVING);
                })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> waitForActivityIdle(context, f, 0, () -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_traverse"),
                            "Traverse completed: " + activity + "; position=" + f.player().getPos());

                    context.getWorld().setTimeOfDay(1000);

                    // Check that breadcrumbs were recorded in expedition state
                    var expState = f.runtime.getExpeditionState(f.id);
                    context.assertTrue(expState != null && (expState.status() == ExpeditionStatus.EXPLORING_OUTWARD || expState.status() == ExpeditionStatus.RETURNING),
                            "Expedition status is active (outward or returning)");
                    context.assertTrue(!expState.breadcrumbs().isEmpty(), "Breadcrumb recorded during outward exploration");

                    f.runtime.stop();
                }))
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightExpeditionBatch", tickLimit = 420)
    public void nightfallTriggersSafeReturnToShelter(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos homePos = context.getAbsolutePos(new BlockPos(1, 2, 2));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "NightReturner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) {
                        for (int z = 0; z <= 4; z++) {
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 2, z)), Blocks.AIR.getDefaultState());
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 3, z)), Blocks.AIR.getDefaultState());
                        }
                    }

                    // Move player 5 blocks east to (6, 2, 2) so after traversing 2 blocks west to (4, 2, 2) it remains 3 blocks from home (> 2.0 blocks)
                    BlockPos currentPos = context.getAbsolutePos(new BlockPos(6, 2, 2));
                    f.player().setPosition(currentPos.getX() + 0.5, currentPos.getY(), currentPos.getZ() + 0.5);

                    f.player().getInventory().setStack(0, new ItemStack(Items.BREAD, 3));
                    f.player().setHealth(20.0f);
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);

                    // Establish expedition anchor at homePos
                    Waypoint anchor = Waypoint.of("shelter_base", homePos.getX(), homePos.getY(), homePos.getZ(),
                            context.getWorld().getRegistryKey().getValue().toString(), WaypointKind.SHELTER, context.getWorld().getTime());
                    ExpeditionState state = new ExpeditionState(anchor, 16.0);
                    state.startExpedition();
                    f.runtime.setExpeditionState(f.id, state);

                    // Set time to night (13500 ticks)
                    context.getWorld().setTimeOfDay(13500);

                    // Evaluate candidates
                    var candidates = f.runtime.nativeCandidates(Actor.console(), f.id);
                    var urgentReturn = candidates.stream().filter(c -> "native_traverse".equals(c.skill())
                            && c.candidate().priority() == Priority.URGENT).findFirst();

                    context.assertTrue(urgentReturn.isPresent(), "Urgent return candidate generated on nightfall");
                    context.assertTrue(urgentReturn.get().arguments().get("direction").equals("west"), "Return route traverses west toward shelter");

                    // Execute urgent traverse return
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "west", 2) == 1, "Return traverse initiated");
                })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> waitForActivityIdle(context, f, 0, () -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_traverse"), "Return traverse step completed");

                    var expState = f.runtime.getExpeditionState(f.id);
                    context.assertTrue(expState.status() == ExpeditionStatus.RETURNING, "Expedition state transitioned to RETURNING");
                    context.assertTrue(expState.returnReason() == ReturnReason.NIGHT, "Return reason is NIGHT");

                    context.getWorld().setTimeOfDay(1000L);
                    f.runtime.stop();
                }))
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "expeditionReturn", tickLimit = 350)
    public void fullInventoryTriggersReturnToDeposit(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos homePos = context.getAbsolutePos(new BlockPos(1, 2, 2));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "FullLootReturner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) {
                        for (int z = 0; z <= 4; z++) {
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 2, z)), Blocks.AIR.getDefaultState());
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 3, z)), Blocks.AIR.getDefaultState());
                        }
                    }

                    // Move player to (4, 2, 2)
                    BlockPos currentPos = context.getAbsolutePos(new BlockPos(4, 2, 2));
                    f.player().setPosition(currentPos.getX() + 0.5, currentPos.getY(), currentPos.getZ() + 0.5);

                    // Fill 33 slots with cobblestone
                    for (int i = 0; i < 33; i++) {
                        f.player().getInventory().setStack(i, new ItemStack(Items.COBBLESTONE, 64));
                    }
                    context.getWorld().setTimeOfDay(2000); // Daytime

                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);

                    Waypoint anchor = Waypoint.of("home_vault", homePos.getX(), homePos.getY(), homePos.getZ(),
                            context.getWorld().getRegistryKey().getValue().toString(), WaypointKind.SHELTER, context.getWorld().getTime());
                    ExpeditionState state = new ExpeditionState(anchor, 16.0);
                    state.startExpedition();
                    f.runtime.setExpeditionState(f.id, state);

                    // Ensure daytime immediately before evaluating candidates
                    context.getWorld().setTimeOfDay(2000);

                    // Evaluate candidates
                    var candidates = f.runtime.nativeCandidates(Actor.console(), f.id);
                    var returnCandidate = candidates.stream().filter(c -> "native_traverse".equals(c.skill())
                            && "west".equals(c.arguments().get("direction"))).findFirst();

                    context.assertTrue(returnCandidate.isPresent(), "Return candidate generated due to full inventory");
                    context.assertTrue(returnCandidate.get().candidate().priority() == Priority.PROJECT, "Inventory return is PROJECT priority");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 620)
    public void waypointBacktrackingArrivalAtHome(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos homePos = context.getAbsolutePos(new BlockPos(2, 2, 2));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> f.awaitReady(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Backtracker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) {
                        for (int z = 0; z <= 4; z++) {
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 2, z)), Blocks.AIR.getDefaultState());
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 3, z)), Blocks.AIR.getDefaultState());
                        }
                    }

                    // Place player 2 blocks away at (4, 2, 2)
                    BlockPos pPos = context.getAbsolutePos(new BlockPos(4, 2, 2));
                    f.player().setPosition(pPos.getX() + 0.5, pPos.getY(), pPos.getZ() + 0.5);

                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);

                    Waypoint anchor = Waypoint.of("bed_shelter", homePos.getX(), homePos.getY(), homePos.getZ(),
                            context.getWorld().getRegistryKey().getValue().toString(), WaypointKind.HOME_BED, context.getWorld().getTime());
                    ExpeditionState state = new ExpeditionState(anchor, 16.0);
                    state.startExpedition();
                    state.triggerReturn(ReturnReason.DUSK_APPROACHING);
                    f.runtime.setExpeditionState(f.id, state);

                    // Traverse 2 blocks west to return home
                    context.assertTrue(f.runtime.nativeTraverse(f.source, f.id, "west", 2) == 1, "Backtracking traverse west requested");
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_traverse"),
                            "Arrival step completed: " + activity + "; position=" + f.player().getPos());

                    // Check arrival at base
                    var expState = f.runtime.getExpeditionState(f.id);
                    context.assertTrue(expState.status() == ExpeditionStatus.IDLE_AT_BASE, "Player arrived at base, resetting status to IDLE_AT_BASE");
                    context.assertTrue(expState.returnReason() == ReturnReason.NONE, "Return reason cleared on arrival");

                    // Test landmark sharing through TeamCoordinator
                    Waypoint landmark = Waypoint.of("Iron_Outcrop", homePos.getX() + 20, 64, homePos.getZ() + 30,
                            context.getWorld().getRegistryKey().getValue().toString(), WaypointKind.RESOURCE_PATCH, context.getWorld().getTime());
                    f.runtime.teamCoordinator().shareWaypoint(landmark);

                    var shared = f.runtime.teamCoordinator().getSharedWaypoint("Iron_Outcrop");
                    context.assertTrue(shared.isPresent(), "Discovered landmark shared with team coordinator");
                    context.assertTrue(shared.get().kind() == WaypointKind.RESOURCE_PATCH, "Landmark kind verified as RESOURCE_PATCH");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static void waitForActivityIdle(TestContext context, NativePaidSkillsGameTests.Fixture f,
                                            int attempts, Runnable next) {
        var status = f.runtime.nativeActivityStatus(Actor.console(), f.id);
        if (status != null && status.phase().equals("IDLE")) {
            next.run();
            return;
        }
        if (attempts >= 100) {
            context.throwGameTestException("Native expedition traversal did not settle: " + status);
            return;
        }
        context.waitAndRun(1, () -> waitForActivityIdle(context, f, attempts + 1, next));
    }
}
