package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In plain terms: a place it cannot walk to is asked for three times and then let be for ten minutes, whatever routine
 * keeps asking for it, so that one request never becomes a thousand.
 *
 * Technical: LOOPS 1 / M-01f slice 2. Seed 109's trace on main with #72 (one game hour, no goal set): 494 refused faction
 * walks and 99 refused "Walk to gravel" walks, each target within its own routine's back-off, but neighbouring targets of
 * one place asked for in turn. The global set-aside counts refusals by the four-block cell of the walk's target, from
 * around where the player stands, whatever proposed the walk. Here a routine of the fixture's own, with no back-off of its
 * own, offers a walk to a place eight blocks off, and the player is boxed in bedrock. Staged: the box and the routine.
 */
public final class NativeWalkSetAsideGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "walkSetAside", tickLimit = 3000)
    public void aPlaceItCannotWalkToIsAskedForThreeTimesThenLetBe(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        var refused = new AtomicInteger();
        long[] first = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.observeRun(new RunObserver() {
                        @Override public void ended(String player, String skill, String state, String label) {
                            if ("native_goto".equals(skill) && "BLOCKED".equals(state)) refused.incrementAndGet();
                        }
                    });
                    f.runtime.createNative(f.source, "Boxed");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos();
                    // Boxed in bedrock where it stands: no way out, and so no way to anywhere.
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
                        if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue;
                        world.setBlockState(feet.add(dx, dy, dz), Blocks.BEDROCK.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    BlockPos there = feet.east(8);
                    UUID task = UUID.nameUUIDFromBytes(("fixture-walk:" + there.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var candidate = new dev.kindred.core.TaskPreferences.Candidate(task, dev.kindred.core.TaskPreferences.Priority.PROJECT, 90.0,
                            dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                    // A routine with no back-off of its own: the same walk offered at every decision.
                    f.runtime.extraCandidatesForTest(id -> List.of(new PlayerRuntime.NativeTaskCandidate(candidate, "native_goto",
                            Map.of("x", String.valueOf(there.getX()), "y", String.valueOf(there.getY()), "z", String.valueOf(there.getZ()), "range", "1"))));
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    context.assertTrue(refused.get() >= 1, "The walk asked for and refused");
                    if (first[0] < 0) first[0] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getServer().getTicks() - first[0] >= 1200, "A game minute on"))
                .createAndAdd(() -> {
                    int asked = refused.get();
                    KindredMod.LOGGER.info("Walk set-aside: {} refused walks in a game minute", asked);
                    f.runtime.extraCandidatesForTest(null);
                    context.assertTrue(asked <= dev.kindred.core.WalkSetAside.REFUSALS, "Asked for no more than three times: " + asked);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A routine that judges its own refused walks is left to: patch mining (its drops set aside and tried twice more once
     * the patch is open), an evade walk (a reflex's way out of a mob's reach) and a faction match's moves. On #133's run and
     * on #138's the set-aside held Hal off its last two raw iron ("Mined out, six raw iron: 2 left", with "Hal lets its
     * walks … be for ten minutes … [from patchMineCandidates]"). Staged: the bedrock box, and the same walk offered at every
     * decision under each of those three proposers' names in turn.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "walkSetAsideOwn", tickLimit = 3000)
    public void aRoutineWithItsOwnBackOffHasItsWalksLeftToIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        String[] sources = {"patchMineCandidates:0", "evadeCandidates:0", "factionCandidates:0"};
        var offered = new AtomicInteger();
        var refusedBy = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        String[] last = {null};
        long[] first = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.observeRun(new RunObserver() {
                        @Override public void ended(String player, String skill, String state, String label) {
                            if ("native_goto".equals(skill) && "BLOCKED".equals(state) && last[0] != null)
                                refusedBy.computeIfAbsent(last[0], k -> new AtomicInteger()).incrementAndGet();
                        }
                    });
                    f.runtime.createNative(f.source, "Boxed");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
                        if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue;
                        world.setBlockState(feet.add(dx, dy, dz), Blocks.BEDROCK.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    BlockPos there = feet.east(8);
                    UUID task = UUID.nameUUIDFromBytes(("fixture-own-walk:" + there.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var candidate = new dev.kindred.core.TaskPreferences.Candidate(task, dev.kindred.core.TaskPreferences.Priority.PROJECT, 90.0,
                            dev.kindred.core.TaskPreferences.Affinities.none(), new dev.kindred.core.TaskPreferences.Eligibility(true, true, true, true));
                    var arguments = Map.of("x", String.valueOf(there.getX()), "y", String.valueOf(there.getY()), "z", String.valueOf(there.getZ()), "range", "1");
                    f.runtime.extraCandidatesForTest(id -> {
                        String source = sources[Math.floorMod(offered.getAndIncrement(), sources.length)];
                        last[0] = source;
                        return List.of(new PlayerRuntime.NativeTaskCandidate(candidate, "native_goto", arguments, source));
                    });
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!refusedBy.isEmpty(), "The walk asked for and refused");
                    if (first[0] < 0) first[0] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getServer().getTicks() - first[0] >= 1200, "A game minute on"))
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Walk set-aside, own back-off: refused walks in a game minute by proposer {}", refusedBy);
                    f.runtime.extraCandidatesForTest(null);
                    int total = refusedBy.values().stream().mapToInt(AtomicInteger::get).sum();
                    context.assertTrue(total > dev.kindred.core.WalkSetAside.REFUSALS, "Asked for more than three times, not let be: " + refusedBy);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
