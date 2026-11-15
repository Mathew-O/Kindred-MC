package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * A refused set-down of the bed it carries is offered again only after a while, and given up for the night after a few
 * (F-25c4di1c). Vanilla places no bed where an entity stands in its cells, and says nothing: the action finds no bed and is
 * BLOCKED "The bed was not set down". Nothing about the spot changes from one try to the next, and it was offered again
 * at once: in a corpus run on #362's gate, `e40a20-79a9aad-101-sol-107500`'s player was refused 426 times in a row in its
 * sealed pocket; in E-40 iteration 23 (`e40a23-3115b1c`, #352) 101 Sol, 85 times before its bed went down (RUNNER 2's
 * {@code claude/corpus-data-e40a23-1115}).
 */
public final class NativeBedRetryGameTests implements FabricGameTest {
    /** How long a night it is watched. */
    private static final int WATCH = 1200;
    /** The least time between two tries at setting its bed down, and the most tries in the watched night. */
    private static final int MIN_GAP = 100, MOST_TRIES = 4;

    /**
     * In plain terms: an AI player whose bed will not go down where it wants it does not try again and again on the spot,
     * hundreds of times; it waits before trying again, and after a few tries gives it up for the night.
     *
     * Technical: F-25c4di1c (above). STAGED: rock round it with no sky; its pocket two high, closed, with its bed's room
     * open north of it, two long, on a solid floor; the player at the pocket's north edge, so that its body stands a tenth of
     * a block into the bed's foot cell, where vanilla will not set a bed down; its bed and nothing else; no home bed; night
     * held; no goal, its own play. Each {@code native_bed} it starts is counted. Fails if two start within {@value #MIN_GAP}
     * ticks of each other or more than {@value #MOST_TRIES} start in {@value #WATCH} ticks of night; passes otherwise,
     * alive, with at least one tried.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedRetry", tickLimit = WATCH + 800)
    public void aRefusedSetDownIsTriedAgainOnlyAfterAWhileAndGivenUpForTheNight(TestContext context) {
        java.util.List<Long> tries = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        long[] now = {0};
        java.util.function.UnaryOperator<ActionAdmission.Writer> watch = writer -> new ActionAdmission.Writer() {
            @Override
            public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                if ("native_bed".equals(intent.kind())) tries.add(now[0]);
                return writer.prepare(intent);
            }

            @Override public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) { return writer.transition(next, expectedRevision); }
        };
        var f = new NativePaidSkillsGameTests.Fixture(context, watch, true, WATCH + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 14, 40));
        int[] watched = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bedder"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-6, -2, -6), o.add(6, 6, 6));
                    for (BlockPos cell : BlockPos.iterate(o.add(-6, -2, -6), o.add(6, 6, 6))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
                    for (BlockPos cell : BlockPos.iterate(o.add(0, 0, -2), o.add(0, 1, 0))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
                    var player = f.player();
                    player.getInventory().clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    player.getInventory().setStack(0, new ItemStack(Items.WHITE_BED));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    world.setWeather(12_000, 0, false, false);
                    // At the pocket's north edge: its body (0.6 across) a tenth of a block into the bed's foot cell.
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.2, 180, 10);
                    player.setVelocity(Vec3d.ZERO);
                    world.setTimeOfDay(14_000);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Its own play resumed");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(14_000);
                    now[0] = context.getWorld().getTime();
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died");
                    java.util.List<Long> seen;
                    synchronized (tries) { seen = java.util.List.copyOf(tries); }
                    for (int i = 1; i < seen.size(); i++) {
                        if (seen.get(i) - seen.get(i - 1) < MIN_GAP) {
                            throw new net.minecraft.test.GameTestException("Its bed tried again " + (seen.get(i) - seen.get(i - 1)) + " ticks after a try: "
                                    + seen.size() + " tries so far; " + f.runtime.nativeLastResult(f.id) + " / " + f.runtime.lifeNote(f.id));
                        }
                    }
                    context.assertTrue(seen.size() <= MOST_TRIES, "Its bed tried " + seen.size() + " times in a night");
                    context.assertTrue(++watched[0] > WATCH, "Watching the night");
                    context.assertTrue(!seen.isEmpty(), "Its bed never tried: " + f.runtime.lifeNote(f.id));
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Bed retry: {} tries in {} ticks of night, at {}; {}", tries.size(), WATCH, tries, f.runtime.bedChoreForTest(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
