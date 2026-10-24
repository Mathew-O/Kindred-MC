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
 * pen, and penned a cow not its own (full run 20 on 2026-10-24). A fixture that fails before its end leaves its ground
 * as it was.
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
