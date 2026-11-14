package dev.kindred.fabric;

import dev.kindred.core.route.Sight;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Optional;

/**
 * In plain terms: the ground one AI player has looked at, kept as it saw it, so that it sites a base, a pen, a plot or a
 * hall only on ground it has seen. A little more of the ground round it is looked at every tick.
 *
 * Technical: F-35m1. One per player, holding the core's {@link Sight.SeenColumns} ({@value #COLUMNS} columns at most, the
 * farthest from its last look dropped first) and its dimension; not saved, so a restart starts with none. A column is kept
 * only when a sight line from its eyes reaches its top ({@link Sight#seenTop}), read then by the base's own rules
 * ({@link #describe}); siting reads only these ({@link #ground}), and the site is checked again by the build when it stands
 * there.
 */
final class SeenGround {
    /** How many seen columns a player keeps: a base's 49 by 49 search round its anchor, and room round it. */
    static final int COLUMNS = 8192;
    /** Sight lines a tick. */
    static final int RAYS = 16;
    /** How far out it looks: its knowledge sweep's own reach. */
    static final int RADIUS = Sight.HORIZONTAL_RADIUS;
    /** Every sweep's cost, for the evidence's microseconds a tick. */
    static long sweeps, rays, nanos;

    private final Sight.SeenColumns columns = new Sight.SeenColumns(COLUMNS);
    private String dimension = "";

    /**
     * A few more of the columns round it looked at, {@value #RAYS} sight lines at most, spiralling out from where it stands;
     * the top of each it sees is kept as it saw it, and nothing of one it does not.
     */
    void sweep(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        String dim = world.getRegistryKey().getValue().toString();
        if (!dim.equals(dimension)) {
            dimension = dim;
            columns.reset();
        }
        long start = System.nanoTime();
        BlockPos feet = player.getBlockPos();
        double[] eye = {player.getX(), player.getEyeY(), player.getZ()};
        int cast = columns.sweep(new WorldKnowledge.WorldSight(world, player), eye, feet.getX(), feet.getZ(), RADIUS, RAYS,
                (x, z) -> world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z),
                top -> describe(world, top.x(), top.y(), top.z()));
        nanos += System.nanoTime() - start;
        rays += cast;
        sweeps++;
    }

    /**
     * A look over one site from where it stands (the check at the stand): a sight line to each column from ({@code x0},
     * {@code z0}) to ({@code x1}, {@code z1}), each top it sees kept as it sees it now. Bounded by the site: at most its
     * columns' count of sight lines, once a check.
     */
    void lookOver(ServerPlayerEntity player, int x0, int z0, int x1, int z1) {
        ServerWorld world = player.getServerWorld();
        String dim = world.getRegistryKey().getValue().toString();
        if (!dim.equals(dimension)) {
            dimension = dim;
            columns.reset();
        }
        long start = System.nanoTime();
        double[] eye = {player.getX(), player.getEyeY(), player.getZ()};
        var sight = new WorldKnowledge.WorldSight(world, player);
        int cast = 0;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            int cx = x, cz = z;
            cast++;
            Sight.seenTop(sight, eye, cx, cz, (ax, az) -> world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ax, az))
                    .ifPresent(top -> columns.put(describe(world, top.x(), top.y(), top.z())));
        }
        nanos += System.nanoTime() - start;
        rays += cast;
    }

    /** The columns it has seen in {@code world}, for siting: a column it has not seen is none ({@code Optional.empty()}). */
    dev.kindred.core.construction.BaseSite.Ground ground(ServerWorld world) {
        boolean here = world.getRegistryKey().getValue().toString().equals(dimension);
        return (x, z) -> here ? columns.get(x, z).map(SeenGround::column) : Optional.empty();
    }

    /** Grows with every column seen or seen changed: a site chosen from them is stale once it has. */
    long version() { return columns.version(); }

    int size() { return columns.size(); }

    /** Fixtures and the evidence: whether it has seen the column. */
    boolean seen(int x, int z) { return columns.get(x, z).isPresent(); }

    static dev.kindred.core.construction.BaseSite.Column column(Sight.SeenColumns.Top top) {
        return new dev.kindred.core.construction.BaseSite.Column(top.y(), top.firm(), top.open(), top.water());
    }

    /**
     * One column read from its top block at {@code top}: whether that is natural ground to build on (dirt, grass, stone,
     * sand, gravel; not water, a log, a container or another's build), whether it is open water, and how many open cells
     * are over it before anything that is not air or a plant in the way. The base's and the rail's own reading of a column
     * (M-01c, E-45e), kept here so that what it sees is read by the same rules.
     */
    static Sight.SeenColumns.Top describe(ServerWorld world, int x, int top, int z) {
        var state = world.getBlockState(new BlockPos(x, top, z));
        boolean firm = !state.hasBlockEntity() && state.getFluidState().isEmpty() && (state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.SAND) || state.isOf(Blocks.GRAVEL));
        // Open water at the top, still or flowing: a rail line crosses it on a bridge (E-45e).
        boolean water = state.isOf(Blocks.WATER);
        int open = 0;
        while (open < 24) {
            var above = world.getBlockState(new BlockPos(x, top + 1 + open, z));
            // A rail is no more in the way than air: a line laid again over its own rails (E-45e).
            if (!(above.isAir() || above.isReplaceable() && above.getFluidState().isEmpty() || above.isIn(BlockTags.RAILS))) break;
            open++;
        }
        return new Sight.SeenColumns.Top(x, top, z, firm, open, water);
    }
}
