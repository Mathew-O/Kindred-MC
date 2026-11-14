package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A pillar step interrupted just after its block went down is not held for reconciliation it never gets: what it did
 * is there to be seen (F-25c6c).
 */
public final class NativePillarInterruptGameTests implements FabricGameTest {

    /**
     * In plain terms: an AI player building a pillar step, stopped mid-jump by something more urgent once its block is
     * down, carries on afterwards, rather than standing still for good waiting for someone to sort out what it did.
     *
     * Technical: F-25c6c, E-40c3's seed 8675309 Echo in the Nether: "Out of the hoglin's reach: pillar up" was cancelled
     * "The hoglin is on it; fight back" in the same second it began, the step was recorded UNCERTAIN ("Pillar step
     * cancelled with uncertain outcome"), the player was held for reconciliation, and with no operator to recover it
     * did nothing for the run's last ~194,000 ticks. STAGED: a native player on stone with 16 cobblestone in slot 0; a
     * pillar step from where it stands; once its cobblestone is in the cell it jumped from, the step is interrupted
     * through {@link PlayerRuntime#interruptForFixtures} as a goal's stop interrupts it. Passes if the step's record ends
     * VERIFIED (its own block in its cell, one cobblestone gone, nothing else lost), the player is not held for
     * reconciliation, and a next task is accepted.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "pillarInterrupt", tickLimit = 600)
    public void aPillarStepInterruptedOnceItsBlockIsDownIsSeenForWhatItDidAndThePlayerCarriesOn(TestContext context) {
        AtomicReference<ActionRecord.State> last = new AtomicReference<>();
        java.util.function.UnaryOperator<ActionAdmission.Writer> watch = writer -> new ActionAdmission.Writer() {
            @Override public CompletableFuture<ActionRecord> prepare(ActionRecord intent) { return writer.prepare(intent); }

            @Override
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                if ("native_pillar".equals(next.kind())) last.set(next.state());
                return writer.transition(next, expectedRevision);
            }
        };
        var f = new NativePaidSkillsGameTests.Fixture(context, watch, true);
        BlockPos[] cell = {null};
        net.minecraft.server.network.ServerPlayerEntity[] body = {null};
        boolean[] interrupted = {false}, logged = {false};
        long[] at = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Pillar"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = body[0] = f.player();
                    context.assertTrue(player.isOnGround(), "Standing");
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.COBBLESTONE, 16));
                    cell[0] = player.getBlockPos().toImmutable();
                    context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, f.id, "native_pillar", Map.of("x", String.valueOf(cell[0].getX()),
                            "y", String.valueOf(cell[0].getY()), "z", String.valueOf(cell[0].getZ()), "slot", "0")) == 1, "Pillar step accepted");
                })
                .createAndAdd(() -> {
                    // The moment its block is in the cell it jumped from, before it lands on it: interrupted.
                    context.assertTrue(context.getWorld().getBlockState(cell[0]).isOf(Blocks.COBBLESTONE), "Block not yet down");
                    interrupted[0] = f.runtime.interruptForFixtures(f.id, "Staged: something more urgent, its block just down");
                    context.assertTrue(interrupted[0], "The step had already ended: nothing to interrupt");
                    at[0] = context.getWorld().getTime();
                    // A hold for reconciliation takes the body out of play at once: the player taken at the start is read.
                    KindredMod.LOGGER.info("Pillar interrupt: interrupted with its block down at {}, the player at y {}", cell[0].toShortString(),
                            body[0].getY());
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getTime() - at[0] >= 40, "Settling"))
                .createAndAdd(() -> {
                    var state = last.get();
                    boolean held = f.runtime.reconciliationBlockedForFixtures(f.id);
                    int cobble = body[0].getInventory().count(Items.COBBLESTONE);
                    if (!logged[0]) {
                        logged[0] = true;
                        KindredMod.LOGGER.info("Pillar interrupt: record {}, held for reconciliation {}, cobblestone {}, the cell {}", state, held, cobble,
                                context.getWorld().getBlockState(cell[0]).getBlock());
                    }
                    context.assertTrue(state == ActionRecord.State.VERIFIED, "The step's record ended " + state + ", not VERIFIED");
                    context.assertTrue(!held, "Held for reconciliation: " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(f.player() == body[0], "The body was taken out of play");
                    context.assertTrue(cobble == 15, "One cobblestone used, not " + (16 - cobble));
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "A next task refused: " + f.runtime.nativeLastResult(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
