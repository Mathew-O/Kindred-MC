package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.MultifaceGrowthBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A block put into a cell holding a sculk vein goes where the game puts it, and is seen there: the player is not held for
 * an outcome that was plain to see (F-31g3).
 */
public final class NativePlaceGapGameTests implements FabricGameTest {

    /**
     * In plain terms: an AI player putting a block where a sculk vein grows (the thin sculk on deep stone) puts it there,
     * as a player does, and carries on, rather than standing still for the rest of the game waiting for someone to sort
     * out where its block went.
     *
     * Technical: F-31g3, E-40 iteration 30 batch 1 (`e40a30-63b97ac`), seed 303: Echo's cast mould at -28, -53, 95 was
     * stopped "Native placement effect cannot be verified; reconcile (placed=true, at -28, -52, 95: air; slots changed: 4:
     * 33 cobblestone -> 32 cobblestone)" and held for reconciliation for the run's last 37.2 minutes. A sculk vein is the
     * one block a cobblestone replaces whose {@code isReplaceable()} is false (every block state, probed on 1.21.1): the
     * placement and its check looked for the block one above, clicked against the vein's top, while vanilla's
     * {@code ItemPlacementContext} put it in the vein's own cell. STAGED: a native player on the field with 16 cobblestone
     * in slot 0; a sculk vein on the floor two blocks east of it; one {@code native_place} of the cobblestone into the
     * vein's cell, against its top. Passes if the cobblestone is in that cell, the place is VERIFIED, one cobblestone is
     * used, the player is not held for reconciliation, and a next task is accepted.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "placeGap", tickLimit = 600)
    public void aBlockPutIntoACellHoldingASculkVeinIsSeenWhereTheGamePutItAndThePlayerCarriesOn(TestContext context) {
        AtomicReference<ActionRecord.State> last = new AtomicReference<>();
        java.util.function.UnaryOperator<ActionAdmission.Writer> watch = writer -> new ActionAdmission.Writer() {
            @Override public CompletableFuture<ActionRecord> prepare(ActionRecord intent) { return writer.prepare(intent); }

            @Override
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                if ("native_place".equals(next.kind())) last.set(next.state());
                return writer.transition(next, expectedRevision);
            }
        };
        var f = new NativePaidSkillsGameTests.Fixture(context, watch, true);
        BlockPos[] cell = {null};
        net.minecraft.server.network.ServerPlayerEntity[] body = {null};
        long[] at = {-1};
        boolean[] logged = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Veined"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = body[0] = f.player();
                    context.assertTrue(player.isOnGround(), "Standing");
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.COBBLESTONE, 16));
                    var world = context.getWorld();
                    BlockPos feet = player.getBlockPos();
                    cell[0] = feet.east(2).toImmutable();
                    FixtureGround.keep(context, cell[0].add(-1, -1, -1), cell[0].add(1, 2, 1));
                    world.setBlockState(cell[0].down(), Blocks.STONE.getDefaultState());
                    world.setBlockState(cell[0].up(), Blocks.AIR.getDefaultState());
                    world.setBlockState(cell[0], Blocks.SCULK_VEIN.getDefaultState().with(MultifaceGrowthBlock.getProperty(net.minecraft.util.math.Direction.DOWN), true));
                    context.assertTrue(world.getBlockState(cell[0]).isOf(Blocks.SCULK_VEIN), "The vein staged");
                    context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, f.id, "native_place", Map.of("x", String.valueOf(cell[0].getX()),
                            "y", String.valueOf(cell[0].getY()), "z", String.valueOf(cell[0].getZ()), "side", "up", "slot", "0")) == 1, "Place accepted");
                    at[0] = world.getTime();
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getTime() - at[0] >= 40, "Settling"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var state = last.get();
                    boolean held = f.runtime.reconciliationBlockedForFixtures(f.id);
                    int cobble = body[0].getInventory().count(Items.COBBLESTONE);
                    if (!logged[0]) {
                        logged[0] = true;
                        KindredMod.LOGGER.info("Place gap: record {}, held for reconciliation {}, cobblestone {}, the cell {}, above {}; {}", state, held, cobble,
                                world.getBlockState(cell[0]).getBlock(), world.getBlockState(cell[0].up()).getBlock(), f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(!held, "Held for reconciliation: " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(state == ActionRecord.State.VERIFIED, "The place's record ended " + state + ", not VERIFIED");
                    context.assertTrue(world.getBlockState(cell[0]).isOf(Blocks.COBBLESTONE), "The vein's cell holds " + world.getBlockState(cell[0]).getBlock());
                    context.assertTrue(cobble == 15, "One cobblestone used, not " + (16 - cobble));
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "A next task refused: " + f.runtime.nativeLastResult(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The other side of the same rule: snow two layers deep is replaceable yet takes no block (vanilla refuses it), so the
     * placement is refused before it is tried, as when vanilla put nothing there. STAGED: as above, snow two layers deep in
     * the cell instead of the vein. Passes if the cell keeps its snow, the cell above stays empty, no cobblestone is used, the
     * refusal is the no-effect one (which the runtime counts, at its cell, to let a refusing cell be), and the player is not
     * held for reconciliation.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "placeGapSnow", tickLimit = 600)
    public void aBlockPutIntoDeepSnowIsRefusedBeforeItIsTriedAndNothingIsHeld(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] cell = {null};
        net.minecraft.server.network.ServerPlayerEntity[] body = {null};
        long[] at = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Snowed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = body[0] = f.player();
                    context.assertTrue(player.isOnGround(), "Standing");
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.COBBLESTONE, 16));
                    var world = context.getWorld();
                    cell[0] = player.getBlockPos().east(2).toImmutable();
                    FixtureGround.keep(context, cell[0].add(-1, -1, -1), cell[0].add(1, 2, 1));
                    world.setBlockState(cell[0].down(), Blocks.STONE.getDefaultState());
                    world.setBlockState(cell[0].up(), Blocks.AIR.getDefaultState());
                    world.setBlockState(cell[0], Blocks.SNOW.getDefaultState().with(net.minecraft.block.SnowBlock.LAYERS, 2));
                    context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, f.id, "native_place", Map.of("x", String.valueOf(cell[0].getX()),
                            "y", String.valueOf(cell[0].getY()), "z", String.valueOf(cell[0].getZ()), "side", "up", "slot", "0")) == 1, "Place accepted");
                    at[0] = world.getTime();
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getTime() - at[0] >= 40, "Settling"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    String last = String.valueOf(f.runtime.nativeLastResult(f.id));
                    KindredMod.LOGGER.info("Place gap, snow: the cell {}, above {}, cobblestone {}, held {}; {}", world.getBlockState(cell[0]),
                            world.getBlockState(cell[0].up()).getBlock(), body[0].getInventory().count(Items.COBBLESTONE),
                            f.runtime.reconciliationBlockedForFixtures(f.id), last);
                    context.assertTrue(!f.runtime.reconciliationBlockedForFixtures(f.id), "Held for reconciliation: " + last);
                    context.assertTrue(world.getBlockState(cell[0]).isOf(Blocks.SNOW), "The snow's cell holds " + world.getBlockState(cell[0]).getBlock());
                    context.assertTrue(world.getBlockState(cell[0].up()).isAir(), "A block went above the snow: " + world.getBlockState(cell[0].up()).getBlock());
                    context.assertTrue(body[0].getInventory().count(Items.COBBLESTONE) == 16, "A cobblestone used");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "A next task refused: " + last);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
