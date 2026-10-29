package dev.kindred.fabric;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Ground a fixture lays put back as it found it when the fixture ends, and the animals and items left on it taken
 * away. The test server keeps whatever a test changed, so the field one fixture laid lay under the tests placed there
 * after it, and its animals wandered on into them: a ranch went after a cow on ground another had raised above its
 * pen, and penned a cow not its own (full run 20 on 2026-10-24). A fixture that fails is put back as its test ends: its
 * field left laid lay under the next test placed beside it (a failed bring left its hall and line where the pit test
 * after it stood, full run 53 on 2026-10-29).
 */
final class FixtureGround {
    /**
     * Test structures of empty ground, as wide as a fixture's field: the test server spaces its tests by their
     * structures and runs several at once, so a fixture that lays ground wider than its structure laid it over another
     * running beside it (the night fixture and the repair fixture, two players called Mason, on one field). The server
     * also clears a test's structure, keeps it loaded while it runs, and walls it round with barriers, which keep its
     * players and animals on its own field; with sky access, as these fixtures ask, it puts no barrier over it, which
     * over a structure this wide shut its whole field from the sky ("No flat open ground for a cabin"). {@link #WIDE}
     * is 120 by 120 and 40 high, {@link #FIELD} 80 by 80 and 32 high; a fixture's origin is put in from the
     * structure's corner as far as its field runs the other way.
     */
    static final String WIDE = "kindred_gametest:wide", FIELD = "kindred_gametest:field";

    private record Kept(BlockPos min, BlockPos max, BlockState[] states) { }

    private static final Map<TestContext, Kept> KEPT = Collections.synchronizedMap(new IdentityHashMap<>());

    private FixtureGround() { }

    /**
     * The chunks from {@code min} to {@code max} kept simulated while the test runs. The test server sets no simulation
     * distance, so a player keeps entities moving only in the chunk it stands in, and the server only the chunk of a
     * test's own structure: a villager by a chunk's edge stopped ticking when the player beside it stepped over into
     * the next (it never let go of a job whose lectern was gone), and a minecart rolling out of the player's chunk
     * stopped dead. A server with a simulation distance of its own keeps them moving. The test server releases what is
     * forced when the batch ends.
     */
    static void simulated(TestContext context, BlockPos min, BlockPos max) {
        var world = context.getWorld();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) world.setChunkForced(cx, cz, true);
        }
    }

    /** What lies from {@code min} to {@code max} now, to be put back by {@link #restore}. */
    static void keep(TestContext context, BlockPos min, BlockPos max) {
        var world = context.getWorld();
        int dx = max.getX() - min.getX() + 1, dy = max.getY() - min.getY() + 1, dz = max.getZ() - min.getZ() + 1;
        var states = new BlockState[dx * dy * dz];
        var at = new BlockPos.Mutable();
        int i = 0;
        for (int x = 0; x < dx; x++) for (int y = 0; y < dy; y++) for (int z = 0; z < dz; z++) {
            states[i++] = world.getBlockState(at.set(min.getX() + x, min.getY() + y, min.getZ() + z));
        }
        KEPT.put(context, new Kept(min.toImmutable(), max.toImmutable(), states));
        whenDone(context, () -> restore(context));
    }

    /**
     * {@code action} run as the test ends, passed or failed (once a test is done the test server ticks nothing it
     * scheduled, its own clean-up steps included).
     */
    static void whenDone(TestContext context, Runnable action) {
        try {
            var field = TestContext.class.getDeclaredField("test");
            field.setAccessible(true);
            ((net.minecraft.test.GameTestState) field.get(context)).addListener(new net.minecraft.test.TestListener() {
                @Override public void onStarted(net.minecraft.test.GameTestState test) { }
                @Override public void onPassed(net.minecraft.test.GameTestState test, net.minecraft.test.TestRunContext run) { action.run(); }
                @Override public void onFailed(net.minecraft.test.GameTestState test, net.minecraft.test.TestRunContext run) { action.run(); }
                @Override public void onRetry(net.minecraft.test.GameTestState previous, net.minecraft.test.GameTestState next,
                                              net.minecraft.test.TestRunContext run) { action.run(); }
            });
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            KindredMod.LOGGER.warn("A fixture cannot act as its test ends: {}", unavailable.toString());
        }
    }

    /**
     * Every block kept put back, without updates to its neighbours, then everything on the ground but players taken
     * away: animals, mobs, and the items a container put back scattered.
     */
    static void restore(TestContext context) {
        var kept = KEPT.remove(context);
        if (kept == null) return;
        var world = context.getWorld();
        var min = kept.min();
        var max = kept.max();
        int dx = max.getX() - min.getX() + 1, dy = max.getY() - min.getY() + 1, dz = max.getZ() - min.getZ() + 1;
        var at = new BlockPos.Mutable();
        int i = 0;
        for (int x = 0; x < dx; x++) for (int y = 0; y < dy; y++) for (int z = 0; z < dz; z++) {
            var state = kept.states()[i++];
            at.set(min.getX() + x, min.getY() + y, min.getZ() + z);
            if (world.getBlockState(at) != state) world.setBlockState(at, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
        }
        var box = new Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 8, max.getZ() + 1);
        for (Entity entity : world.getEntitiesByClass(Entity.class, box, e -> !(e instanceof PlayerEntity))) entity.discard();
    }
}
