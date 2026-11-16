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
 * {@code claude/corpus-data-e40a23-1115}). And its own body at its pocket's edge over the bed's cells steps clear first
 * (F-25c4di1d), the cause of the refusals where nothing else stood there.
 */
public final class NativeBedRetryGameTests implements FabricGameTest {
    /** How long a night it is watched. */
    private static final int WATCH = 1200;
    /** The least time between two tries at setting its bed down, and the most tries in the watched night. */
    private static final int MIN_GAP = 100, MOST_TRIES = 4;

    /** Rock round {@code o} with no sky; its pocket two high, closed, its bed's room open north of it, two long, on solid floor. */
    private static void pocket(TestContext context, BlockPos o) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-6, -2, -6), o.add(6, 6, 6));
        for (BlockPos cell : BlockPos.iterate(o.add(-6, -2, -6), o.add(6, 6, 6))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
        for (BlockPos cell : BlockPos.iterate(o.add(0, 0, -2), o.add(0, 1, 0))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
    }

    /**
     * Its night in {@code pocket}, the player at {@code standZ} (within its cell) and {@code stage} run after, its bed and
     * nothing else, no home bed, night held, its own play; every {@code native_bed} it starts counted. {@code check} is given
     * the tries so far and the bed's foot, each tick, for {@value #WATCH} ticks; it throws to fail and returns true to pass.
     */
    private static void night(TestContext context, String name, double standZ, java.util.function.Consumer<BlockPos> stage,
                              CheckTries check) {
        java.util.List<Long> tries = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.Set<String> details = new java.util.LinkedHashSet<>();
        long[] now = {0};
        java.util.function.UnaryOperator<ActionAdmission.Writer> watch = writer -> new ActionAdmission.Writer() {
            @Override
            public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                if ("native_bed".equals(intent.kind())) tries.add(now[0]);
                return writer.prepare(intent);
            }

            @Override
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                if ("native_bed".equals(next.kind()) && next.detail() != null && !next.detail().isBlank()) {
                    synchronized (details) { details.add(next.detail()); }
                }
                return writer.transition(next, expectedRevision);
            }
        };
        var f = new NativePaidSkillsGameTests.Fixture(context, watch, true, WATCH + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 14, 40));
        int[] watched = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    pocket(context, o);
                    var player = f.player();
                    player.getInventory().clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    player.getInventory().setStack(0, new ItemStack(Items.WHITE_BED));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    world.setWeather(12_000, 0, false, false);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + standZ, 180, 10);
                    player.setVelocity(Vec3d.ZERO);
                    stage.accept(o);
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
                    String said;
                    synchronized (details) { said = String.join(" | ", details); }
                    String note = f.runtime.lifeNote(f.id) + " / its bed's records: " + said;
                    if (check.passed(seen, o.north(), ++watched[0], note)) return;
                    throw new net.minecraft.test.GameTestException("Watching the night");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Bed retry ({}): {} tries, at {}; {}; its bed's records: {}", name, tries.size(), tries,
                            f.runtime.bedChoreForTest(f.id), details);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** A night's check: the tries so far, the bed's foot, the ticks watched and the player's last note; true when passed. */
    private interface CheckTries {
        boolean passed(java.util.List<Long> tries, BlockPos foot, int watched, String note);
    }

    /**
     * In plain terms: an AI player whose bed will not go down where it wants it, for something in the way it cannot move,
     * does not try again and again on the spot, hundreds of times; it waits before trying again, and after a few tries
     * gives it up for the night.
     *
     * Technical: F-25c4di1c (above). STAGED: its pocket (above); the player at its cell's middle; a sheep with its AI off in
     * the bed's foot cell, where vanilla will not set a bed down (F-25c4di1c's first staging, its own body a tenth of a block
     * into that cell, is F-25c4di1d's, which steps it clear; restaged here so that this still meets a refusal); its bed and
     * nothing else; no home bed; night held; no goal, its own play. Each {@code native_bed} it starts is counted. Fails if two
     * start within {@value #MIN_GAP} ticks of each other or more than {@value #MOST_TRIES} start in {@value #WATCH} ticks of
     * night; passes otherwise, alive, with at least one tried, and its refusal names the sheep.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedRetry", tickLimit = WATCH + 800)
    public void aRefusedSetDownIsTriedAgainOnlyAfterAWhileAndGivenUpForTheNight(TestContext context) {
        boolean[] named = {false};
        net.minecraft.entity.passive.SheepEntity[] sheep = {null};
        night(context, "Bedder", 0.5, o -> {
            var world = context.getWorld();
            var made = net.minecraft.entity.EntityType.SHEEP.create(world);
            made.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() - 0.5, 0, 0);
            made.setAiDisabled(true);
            made.setPersistent();
            world.spawnEntity(made);
            sheep[0] = made;
        }, (seen, foot, watched, note) -> {
            if (note.contains("in its cells minecraft:sheep")) named[0] = true;
            for (int i = 1; i < seen.size(); i++) {
                if (seen.get(i) - seen.get(i - 1) < MIN_GAP) {
                    throw new net.minecraft.test.GameTestException("Its bed tried again " + (seen.get(i) - seen.get(i - 1)) + " ticks after a try: "
                            + seen.size() + " tries so far; " + note);
                }
            }
            context.assertTrue(seen.size() <= MOST_TRIES, "Its bed tried " + seen.size() + " times in a night");
            if (watched <= WATCH) return false;
            context.assertTrue(!seen.isEmpty(), "Its bed never tried: " + note);
            context.assertTrue(named[0], "Its refusal did not name the sheep: " + note);
            sheep[0].discard();
            return true;
        });
    }

    /**
     * In plain terms: an AI player standing at the very edge of its pocket, so close to where its bed goes that the bed will
     * not fit, steps back first and sets its bed down on the first try.
     *
     * Technical: F-25c4di1d. STAGED: its pocket (above); the player at the pocket's north edge, so that its body (0.6
     * across) stands a tenth of a block into the bed's foot cell, where vanilla will not set a bed down: F-25c4di1c's
     * fixture as first staged; its bed and nothing else; no home bed; night held; no goal, its own play. Each
     * {@code native_bed} it starts is counted. Fails at a second try, or with no bed down after {@value #WATCH} ticks of night;
     * passes once its bed stands in the room on its first try.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedStep", tickLimit = WATCH + 800)
    public void standingOverItsBedsRoomItStepsClearAndSetsTheBedDownFirstTime(TestContext context) {
        night(context, "Stepper", 0.2, o -> { }, (seen, foot, watched, note) -> {
            context.assertTrue(seen.size() <= 1, "Its bed tried " + seen.size() + " times: " + note);
            if (context.getWorld().getBlockState(foot).isIn(net.minecraft.registry.tag.BlockTags.BEDS)) return true;
            context.assertTrue(watched <= WATCH, "No bed down after " + WATCH + " ticks of night: " + seen.size() + " tries; " + note);
            return false;
        });
    }
}
