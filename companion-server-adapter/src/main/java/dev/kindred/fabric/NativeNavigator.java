package dev.kindred.fabric;

import dev.kindred.core.route.Cell;
import dev.kindred.core.route.Navigator;
import dev.kindred.core.route.Terrain;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * In plain terms: finds a walking route for an AI player through the world as it really is: walking, stepping
 * up, dropping a few blocks or swimming, but never into lava, fire or other harmful blocks. It only looks at
 * parts of the world that are loaded, and never changes anything.
 *
 * Technical: The server's side of {@link Navigator}, the core's bounded A* over standing positions for a player-sized
 * body (F-35d). This class reads the live world for it: each per-cell fact of {@link Terrain} is worked out here from
 * the block's state, and blocks and chunks are read once a search. It keeps the methods its callers use, in block
 * positions, and hands each to the core navigator, which decides every move, cost and route.
 */
final class NativeNavigator {
    static final int DEFAULT_MAX_NODES = Navigator.DEFAULT_MAX_NODES;
    static final int MAX_SAFE_DROP = Navigator.MAX_SAFE_DROP;
    static final int MAX_WATER_DROP = Navigator.MAX_WATER_DROP;
    /**
     * The deepest deliberate drop, taken only with a water bucket ready to break it (E-07d): forty blocks down a fall
     * is about two blocks a tick, inside the 2.8 blocks a tick at which the ground is surely in reach for a tick.
     */
    static final int MAX_CLUTCH_DROP = Navigator.MAX_CLUTCH_DROP;

    record Path(List<BlockPos> nodes, boolean complete, int expanded) {
        BlockPos end() { return nodes.isEmpty() ? null : nodes.getLast(); }
    }

    private final ServerWorld world;
    /**
     * What one search has read, so each block and chunk is read once: a search asks the same few hundred cells about
     * many times over. A navigator lives for one search or one tick, so nothing here goes stale. What it works out from
     * them (which cells pass and stand, and what each costs) the core navigator keeps.
     */
    private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
    private final Long2ByteOpenHashMap chunks = new Long2ByteOpenHashMap();
    private final Navigator core;

    /** A navigator for the given world that drops the body no further than is safe. */
    NativeNavigator(ServerWorld world) {
        this(world, MAX_SAFE_DROP);
    }

    ServerWorld world() { return world; }

    /** A mob to keep clear of: where it is, how it senses, and whether it can see the player now. */
    record Danger(net.minecraft.util.math.Vec3d at, dev.kindred.core.tactics.MobSenses.Profile profile, boolean seen) { }

    /** Extra cost per block, beside a mob, of a route that would rather not be noticed. */
    static final double DANGER_WEIGHT = Navigator.DANGER_WEIGHT;

    /** A water bucket in the hotbar where water stays poured (not the Nether): a long fall can be broken (E-07d). */
    static boolean clutchReady(net.minecraft.server.network.ServerPlayerEntity player) {
        if (player.getServerWorld().getDimension().ultrawarm()) return false;
        for (int slot = 0; slot < 9; slot++) if (player.getInventory().getStack(slot).isOf(net.minecraft.item.Items.WATER_BUCKET)) return true;
        return false;
    }

    /** Routes priced dearer through places it has been hurt or killed (F-30): {@code cost} is the extra for each block there. */
    NativeNavigator shunning(java.util.function.ToDoubleFunction<BlockPos> cost) {
        core.shunning(cost == null ? null : cell -> cost.applyAsDouble(pos(cell)));
        return this;
    }

    /** Routes priced to keep out of these mobs' notice: dearer the nearer they pass (F-10g). */
    NativeNavigator avoiding(List<Danger> dangers) {
        var cores = new ArrayList<Navigator.Danger>(dangers.size());
        for (Danger danger : dangers) {
            cores.add(new Navigator.Danger(danger.at().x, danger.at().y, danger.at().z, danger.profile(), danger.seen()));
        }
        core.avoiding(cores);
        return this;
    }

    /**
     * With a larger {@code maxDrop}, routes may include one deliberate fall that costs some health, or, past twelve
     * blocks, one broken with water (callers allow that only with a water bucket ready; see {@link #clutchReady}).
     */
    NativeNavigator(ServerWorld world, int maxDrop) {
        this.world = world;
        this.core = new Navigator(new WorldTerrain(), maxDrop);
    }

    /** The core navigator this one hands its searches to, over this world. */
    Navigator core() { return core; }

    /** The same navigator, ending an unfinished route only where {@code where} allows. */
    NativeNavigator endingOnly(Predicate<BlockPos> where) {
        core.endingOnly(where == null ? null : cell -> where.test(pos(cell)));
        return this;
    }

    /** Whether a closed wooden door counts as a way through: for a walk that opens the doors on its way (M-01b). */
    private boolean doors;

    /**
     * The same navigator, its routes taking no drop deeper than {@code blocks}, nor into water deeper: one it can step
     * back up from, when that is wanted (M-01a: leading an animal, which must follow it back up).
     */
    NativeNavigator fallingAtMost(int blocks) {
        core.fallingAtMost(blocks);
        return this;
    }

    /**
     * The same navigator, now jumping gaps up to {@code blocks} wide (1 or 2) as a player does (MOVEMENT): straight across
     * to ground at the same level, with head room for the jump and nothing harmful below the gap. Only a walk that can
     * make the jump asks for it (a two-block gap needs a sprint); other plans keep to the ground.
     */
    NativeNavigator jumpingGaps(int blocks) {
        core.jumpingGaps(blocks);
        return this;
    }

    /** The same navigator, now counting closed wooden doors as a way through. */
    NativeNavigator throughDoors() {
        doors = true;
        core.forgetPassage();
        return this;
    }

    /**
     * Searches from {@code start} (feet position) toward {@code target}. A node satisfying {@code goal} ends the
     * search; otherwise the path to the explored node closest to the target is returned as incomplete.
     */
    Path find(BlockPos start, BlockPos target, Predicate<BlockPos> goal, int maxNodes, int maxHorizontalRadius) {
        return path(core.find(cell(start), cell(target), cell -> goal.test(pos(cell)), maxNodes, maxHorizontalRadius));
    }

    /**
     * A cell a body stands in on the lower half of a block, as on a bottom slab or a bottom stair, with room above it: no
     * stand to the search, which wants a full block's top under an empty cell.
     */
    boolean lowStand(BlockPos cell) {
        return core.lowStand(cell(cell));
    }

    record Move(BlockPos to, double cost) { }

    /** Every move a body standing at the given spot can make next, with what each costs. */
    List<Move> moves(BlockPos from) {
        var cores = core.moves(cell(from));
        var moves = new ArrayList<Move>(Math.max(12, cores.size()));
        for (var move : cores) moves.add(new Move(pos(move.to()), move.cost()));
        return moves;
    }

    /**
     * Nearest dry place to stand around {@code from}, searched in growing square rings so the first ring with a
     * match gives the closest shore. Used to leave deep water before breath runs out.
     */
    java.util.Optional<BlockPos> nearestDryStand(BlockPos from, int radius, int vertical) {
        return core.nearestDryStand(cell(from), radius, vertical).map(NativeNavigator::pos);
    }

    /**
     * The nearest dry ground the body can actually get to from {@code from}, swimming if it must: the search follows
     * routes, so dry ground behind a ceiling or across a wall, however close, is passed over.
     */
    java.util.Optional<BlockPos> nearestReachableDryStand(BlockPos from, int maxNodes, int maxHorizontalRadius) {
        return nearestReachableDryStand(from, maxNodes, maxHorizontalRadius, feet -> true);
    }

    /** As above, passing over dry ground {@code allowed} refuses: one a walk out of the water could not get to, say. */
    java.util.Optional<BlockPos> nearestReachableDryStand(BlockPos from, int maxNodes, int maxHorizontalRadius,
                                                          java.util.function.Predicate<BlockPos> allowed) {
        return core.nearestReachableDryStand(cell(from), maxNodes, maxHorizontalRadius, cell -> allowed.test(pos(cell)))
                .map(NativeNavigator::pos);
    }

    /**
     * The block a body moving along {@code yaw} is about to enter is one it may walk into: standable or swimmable,
     * a wall or one-block step up, or a drop no deeper than {@link #MAX_SAFE_DROP}, and never lava or fire.
     * Direct steering (a fight chase, walking onto a drop) checks this where it does not follow a planned route.
     */
    boolean safeHeading(net.minecraft.entity.Entity body, float yaw) {
        double radians = Math.toRadians(yaw);
        double sx = -Math.sin(radians), sz = Math.cos(radians);
        BlockPos feet = BlockPos.ofFloored(body.getX(), body.getY() + 0.2, body.getZ());
        for (double reach : new double[]{0.6, 1.1}) {
            BlockPos ahead = BlockPos.ofFloored(body.getX() + sx * reach, body.getY() + 0.2, body.getZ() + sz * reach);
            if (ahead.getX() == feet.getX() && ahead.getZ() == feet.getZ()) continue;
            if (!core.safeToEnter(cell(ahead))) return false;
        }
        return true;
    }

    /**
     * Why {@link #safeHeading} refuses {@code yaw}: the first cell ahead it will not enter, how far ahead, and what is in
     * it, over it and under it; null when it would not refuse. Diagnostics only (F-22c64).
     */
    String headingRefusal(net.minecraft.entity.Entity body, float yaw) {
        double radians = Math.toRadians(yaw);
        double sx = -Math.sin(radians), sz = Math.cos(radians);
        BlockPos feet = BlockPos.ofFloored(body.getX(), body.getY() + 0.2, body.getZ());
        for (double reach : new double[]{0.6, 1.1}) {
            BlockPos ahead = BlockPos.ofFloored(body.getX() + sx * reach, body.getY() + 0.2, body.getZ() + sz * reach);
            if (ahead.getX() == feet.getX() && ahead.getZ() == feet.getZ()) continue;
            if (!core.safeToEnter(cell(ahead))) return ahead.toShortString() + " (" + reach + " ahead): " + world.getBlockState(ahead)
                    + ", " + world.getBlockState(ahead.up()) + " over it, " + world.getBlockState(ahead.down()) + " under it";
        }
        return null;
    }

    /**
     * Whether a push along {@code yaw} for up to {@code reach} blocks (a hit's knockback) would leave the body on safe
     * ground: no hazard, lava or drop beyond what is safe on the way, or a wall that stops it first.
     */
    boolean safeKnockback(net.minecraft.entity.Entity body, float yaw, double reach) {
        return safeKnockback(body.getX(), body.getY(), body.getZ(), yaw, reach);
    }

    /** As {@link #safeKnockback(net.minecraft.entity.Entity, float, double)}, for a body standing at x, y, z. */
    boolean safeKnockback(double x, double y, double z, float yaw, double reach) {
        double radians = Math.toRadians(yaw);
        double sx = -Math.sin(radians), sz = Math.cos(radians);
        BlockPos feet = BlockPos.ofFloored(x, y + 0.2, z);
        for (double r = 0.6; r <= reach + 0.01; r += 0.5) {
            BlockPos ahead = BlockPos.ofFloored(x + sx * r, y + 0.2, z + sz * r);
            if (ahead.getX() == feet.getX() && ahead.getZ() == feet.getZ()) continue;
            Cell cell = cell(ahead);
            if (!core.clear(cell)) return !core.hazard(cell.up()); // A wall stops the push.
            if (!core.safeToEnter(cell)) return false;
        }
        return true;
    }

    /** Feet and head positions are both free of collision and hazards. */
    boolean clear(BlockPos feet) {
        return core.clear(cell(feet));
    }

    /** No collision box (or only a thin one like a carpet) and nothing that hurts or traps. */
    boolean passable(BlockPos pos) {
        return core.passable(cell(pos));
    }

    /**
     * A body can stand at {@code feet}: solid support below and room for the body, and no lava beside the body or
     * just above it, which would flow in. A hole left by a bucket filled at the edge of a lava pool fills again
     * from the sources around it within a couple of seconds.
     */
    boolean standable(BlockPos feet) {
        return core.standable(cell(feet));
    }

    /**
     * A stand to end a walk on that someone else asked for (E-49): one it can stand on with nothing that harms beside it,
     * where a push or a misstep would hurt. No lava or fire at the feet or the head or just below them on any side, and
     * no open side with a drop deeper than {@link #MAX_SAFE_DROP} onto anything but water. The route's own way there is
     * as any route's: only where it ends is held to this.
     */
    boolean safeStand(BlockPos feet) {
        return core.safeStand(cell(feet));
    }

    /** Feet in water deep enough to swim, never in lava. */
    boolean swimmable(BlockPos feet) {
        return core.swimmable(cell(feet));
    }

    /** Whether the block there would hurt or trap the body. */
    boolean hazard(BlockPos pos) {
        return core.hazard(cell(pos));
    }

    /**
     * What hurts or traps a body, by the block's own kind. Behind {@link Terrain#harms} for the core navigator; a later
     * change asks the installed capabilities instead of this fixed list.
     */
    private static boolean hazard(BlockState state) {
        return state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.COBWEB) || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.WITHER_ROSE) || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.POINTED_DRIPSTONE);
    }

    /**
     * A block a survival player cannot see, solid to the touch: a barrier (F-22c66). It is never ground to stand on or a
     * step to take, only an obstacle, as a player bumps into it. The other blocks it cannot see (light, structure void)
     * have no collision box, and are passed through as air already. In the corpus, a route walked the GameTest's own
     * barrier fence round a scene over a tree's leaves, toward a look aimed at its top, and fell off the canopy.
     */
    static boolean unseen(BlockState state) {
        return state.isOf(Blocks.BARRIER);
    }

    /** What a step through a portal cell costs a route not going through it. */
    static final double PORTAL_COST = Navigator.PORTAL_COST;

    /** A portal's own block at the feet or the head: a step there takes the player to another dimension. */
    boolean portal(BlockPos feet) {
        return core.portal(cell(feet));
    }

    private static boolean portalBlock(BlockState state) {
        return state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL) || state.isOf(Blocks.END_GATEWAY);
    }

    /** Whether the spot is in a loaded part of the world, inside its border. */
    boolean loaded(BlockPos pos) {
        if (!world.isInBuildLimit(pos)) return false;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        long chunk = net.minecraft.util.math.ChunkPos.toLong(cx, cz);
        byte known = chunks.get(chunk);
        if (known == 0) chunks.put(chunk, known = world.isChunkLoaded(cx, cz) ? (byte) 1 : (byte) 2);
        return known == 1 && world.getWorldBorder().contains(pos);
    }

    /** The block at the spot, read once and remembered for this search. */
    BlockState state(BlockPos pos) {
        long key = pos.asLong();
        BlockState state = states.get(key);
        if (state == null) states.put(key, state = world.getBlockState(pos));
        return state;
    }

    static Cell cell(BlockPos pos) {
        return new Cell(pos.getX(), pos.getY(), pos.getZ());
    }

    static BlockPos pos(Cell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static Path path(Navigator.Path path) {
        var nodes = new ArrayList<BlockPos>(path.nodes().size());
        for (Cell cell : path.nodes()) nodes.add(pos(cell));
        return new Path(nodes, path.complete(), path.expanded());
    }

    /** The live world's blocks, each fact read from the block's state as the navigator used to read it itself. */
    private final class WorldTerrain implements Terrain {
        @Override
        public boolean loaded(int x, int y, int z) {
            return NativeNavigator.this.loaded(new BlockPos(x, y, z));
        }

        @Override
        public Fluid fluid(int x, int y, int z) {
            var fluid = state(new BlockPos(x, y, z)).getFluidState();
            if (fluid.isIn(FluidTags.LAVA)) return Fluid.LAVA;
            if (fluid.isIn(FluidTags.WATER)) return Fluid.WATER;
            return Fluid.NONE;
        }

        @Override
        public boolean harms(int x, int y, int z) {
            return hazard(state(new BlockPos(x, y, z)));
        }

        @Override
        public boolean passThrough(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = state(pos);
            if (doors && state.getBlock() instanceof net.minecraft.block.DoorBlock door && door.getBlockSetType().canOpenByHand()) return true;
            var shape = state.getCollisionShape(world, pos);
            return shape.isEmpty() || shape.getMax(Direction.Axis.Y) <= 0.2;
        }

        @Override
        public boolean supports(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = state(pos);
            if (unseen(state)) return false;
            var shape = state.getCollisionShape(world, pos);
            return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.8 && shape.getMax(Direction.Axis.Y) <= 1.0;
        }

        @Override
        public boolean bears(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = state(pos);
            if (unseen(state)) return false;
            var shape = state.getCollisionShape(world, pos);
            return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.8;
        }

        @Override
        public boolean scorches(int x, int y, int z) {
            BlockState state = state(new BlockPos(x, y, z));
            return state.isOf(Blocks.MAGMA_BLOCK) || state.isIn(BlockTags.FIRE);
        }

        @Override
        public boolean bottomHalf(int x, int y, int z) {
            BlockState state = state(new BlockPos(x, y, z));
            if (state.getBlock() instanceof net.minecraft.block.SlabBlock) {
                return state.get(net.minecraft.block.SlabBlock.TYPE) == net.minecraft.block.enums.SlabType.BOTTOM;
            }
            if (state.getBlock() instanceof net.minecraft.block.StairsBlock) {
                return state.get(net.minecraft.block.StairsBlock.HALF) == net.minecraft.block.enums.BlockHalf.BOTTOM;
            }
            return false;
        }

        @Override
        public boolean portal(int x, int y, int z) {
            return portalBlock(state(new BlockPos(x, y, z)));
        }

        @Override
        public boolean replaceable(int x, int y, int z) {
            return state(new BlockPos(x, y, z)).isReplaceable();
        }

        @Override
        public float hardness(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return state(pos).getHardness(world, pos);
        }

        @Override
        public boolean placeAgainst(int x, int y, int z) {
            return NativeStairAction.solid(world, new BlockPos(x, y, z));
        }
    }
}
