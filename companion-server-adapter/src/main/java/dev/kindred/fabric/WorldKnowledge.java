package dev.kindred.fabric;

import dev.kindred.core.route.Cell;
import dev.kindred.core.route.Sight;
import dev.kindred.core.route.SightWorld;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.ChunkSection;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What one AI player has seen of the world near it. Blocks are remembered only when exposed to open air
 * within a bounded radius, and ores and fluids additionally only when a line of sight from the player's eyes
 * reaches them, so the player plans from what it could see rather than from hidden world data. Cave floor it
 * has seen (a dim, two-high space over stone) is remembered as places to explore, one per 4×4×4 block cell.
 */
final class WorldKnowledge {
    enum Kind {
        LOG, STONE, COAL_ORE, IRON_ORE, DIAMOND_ORE, GRAVEL, WATER, LAVA, CRAFTING_TABLE, FURNACE, OBSIDIAN, CAVE, LEAVES,
        /** A Nether fortress's bricks, fences or stairs. */
        NETHER_BRICK,
        /** A mob spawner (in a fortress, the blazes'). */
        SPAWNER,
        /** Warped nylium: a warped forest, where endermen gather. */
        WARPED,
        /** A lit portal's own block. */
        PORTAL,
        /** A stronghold's stone bricks (plain, mossy or cracked). */
        STONE_BRICK,
        /** An End portal frame. */
        END_PORTAL_FRAME,
        /** A cobweb (mineshafts, strongholds): string, broken with a sword. */
        COBWEB,
        /** Nether gold ore: gold nuggets, mined with any pickaxe (E-07e). */
        NETHER_GOLD_ORE,
        /** A chest, trapped chest or barrel: a structure's loot, if nobody has opened it (E-42). */
        CONTAINER,
        /** A block of gold, as ruined portals have: nine ingots, mined with an iron pickaxe (E-42). */
        GOLD_BLOCK
    }

    static final int HORIZONTAL_RADIUS = Sight.HORIZONTAL_RADIUS;
    static final int VERTICAL_RADIUS = Sight.VERTICAL_RADIUS;
    private static final Kind[] KINDS = Kind.values();
    private static final Sight.Kind[] SIGHT_KINDS = new Sight.Kind[KINDS.length];
    static {
        for (Kind kind : KINDS) SIGHT_KINDS[kind.ordinal()] = Sight.Kind.valueOf(kind.name());
    }

    /** What it has seen and how it sees, the core's (F-35f); this class answers its world facts from the live world. */
    private final Sight sight = new Sight();
    /** The columns of still water it saw that it keeps, the core's (F-35j). */
    static final int WATER_COLUMNS = Sight.WATER_COLUMNS;
    private String dimension = "";

    WorldKnowledge() { }

    private static Sight.Kind sight(Kind kind) { return kind == null ? null : SIGHT_KINDS[kind.ordinal()]; }

    private static Kind kind(Sight.Kind kind) { return kind == null ? null : KINDS[kind.ordinal()]; }

    private static Cell cell(BlockPos pos) { return new Cell(pos.getX(), pos.getY(), pos.getZ()); }

    private static BlockPos pos(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }

    private static double[] eye(ServerPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        return new double[] {eye.x, eye.y, eye.z};
    }

    static Kind classify(BlockState state) {
        if (state.isIn(BlockTags.LOGS)) return Kind.LOG;
        if (state.isIn(BlockTags.LEAVES)) return Kind.LEAVES;
        if (state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.COBBLED_DEEPSLATE)) return Kind.STONE;
        if (state.isOf(Blocks.COAL_ORE) || state.isOf(Blocks.DEEPSLATE_COAL_ORE)) return Kind.COAL_ORE;
        if (state.isOf(Blocks.IRON_ORE) || state.isOf(Blocks.DEEPSLATE_IRON_ORE)) return Kind.IRON_ORE;
        if (state.isOf(Blocks.DIAMOND_ORE) || state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE)) return Kind.DIAMOND_ORE;
        if (state.isOf(Blocks.GRAVEL)) return Kind.GRAVEL;
        if (state.isOf(Blocks.CRAFTING_TABLE)) return Kind.CRAFTING_TABLE;
        if (state.isOf(Blocks.FURNACE)) return Kind.FURNACE;
        if (state.isOf(Blocks.OBSIDIAN)) return Kind.OBSIDIAN;
        if (state.isOf(Blocks.NETHER_BRICKS) || state.isOf(Blocks.NETHER_BRICK_FENCE) || state.isOf(Blocks.NETHER_BRICK_STAIRS)) return Kind.NETHER_BRICK;
        if (state.isOf(Blocks.SPAWNER)) return Kind.SPAWNER;
        if (state.isOf(Blocks.WARPED_NYLIUM)) return Kind.WARPED;
        if (state.isOf(Blocks.NETHER_PORTAL)) return Kind.PORTAL;
        if (state.isOf(Blocks.STONE_BRICKS) || state.isOf(Blocks.MOSSY_STONE_BRICKS) || state.isOf(Blocks.CRACKED_STONE_BRICKS)) return Kind.STONE_BRICK;
        if (state.isOf(Blocks.END_PORTAL_FRAME)) return Kind.END_PORTAL_FRAME;
        if (state.isOf(Blocks.COBWEB)) return Kind.COBWEB;
        if (state.isOf(Blocks.NETHER_GOLD_ORE)) return Kind.NETHER_GOLD_ORE;
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)) return Kind.CONTAINER;
        if (state.isOf(Blocks.GOLD_BLOCK)) return Kind.GOLD_BLOCK;
        var fluid = state.getFluidState();
        if (fluid.isStill() && fluid.isIn(FluidTags.WATER) && state.isOf(Blocks.WATER)) return Kind.WATER;
        if (fluid.isStill() && fluid.isIn(FluidTags.LAVA)) return Kind.LAVA;
        return null;
    }

    /**
     * A place a player could stand in a cave: two blocks of air over a stone floor, out of daylight (sky light
     * at most 7, so overhangs at a cave mouth count and open ground does not), or a ravine floor well below sea level.
     * Ground open to the sky, nothing above it in the heightmap, is never a cave floor, whatever its light says: sky light
     * reaches stone just set a few ticks late, more so on a busy server, and a wall's top read as a cave in the meantime
     * sent players on walks to it. The core's rule (F-35f).
     */
    static boolean caveFloor(ServerWorld world, BlockPos feet) {
        return Sight.caveFloor(new WorldSight(world, null), cell(feet));
    }

    /** Advances the incremental sweep around the player by a bounded amount of block reads (the core's, F-35f). */
    void scan(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        String dim = world.getRegistryKey().getValue().toString();
        if (!dim.equals(dimension)) {
            dimension = dim;
            sight.reset();
        }
        sight.scan(new WorldSight(world, player), eye(player), cell(player.getBlockPos()));
    }

    /**
     * A close look at one place, as a player looks over something it has come to: every block of {@code kind} within
     * {@code radius} (and {@code height} up and down) of {@code center} that the player can see from where it stands is
     * remembered now, wherever the sweep has got to. Bounded: a small box, and one sight line a block of the kind.
     */
    void look(ServerPlayerEntity player, BlockPos center, int radius, int height, Kind kind) {
        sight.look(new WorldSight(player.getServerWorld(), player), eye(player), cell(center), radius, height, sight(kind));
    }

    /**
     * Trees on the skyline: the nearest crown of leaves or top of a trunk the player can see from where it stands, over
     * loaded ground out to {@code radius} blocks, past its sweep's {@value #HORIZONTAL_RADIUS}, and a log of that tree,
     * which is remembered: as a player on open ground sees trees far off and makes for them (F-25c4ap). Bounded: every
     * third column, and one sight line a top for the eight nearest.
     */
    Optional<BlockPos> treeInView(ServerPlayerEntity player, int radius, Predicate<BlockPos> usable) {
        return sight.treeInView(new WorldSight(player.getServerWorld(), player), eye(player), cell(player.getBlockPos()), radius,
                c -> usable.test(pos(c))).map(WorldKnowledge::pos);
    }

    /** Where a player stands to chop {@code log}, the core's rule (F-35f): see {@link Sight#trunkFoot}. */
    static Optional<BlockPos> trunkFoot(ServerWorld world, BlockPos log) {
        return Sight.trunkFoot(new WorldSight(world, null), cell(log)).map(WorldKnowledge::pos);
    }

    /** A log beside something built, a cabin's post and not a tree: the core's rule (F-35f), see {@link Sight#builtIn}. */
    static boolean builtIn(ServerWorld world, BlockPos log) {
        return Sight.builtIn(new WorldSight(world, null), cell(log));
    }

    /** Completed sweeps of the surroundings so far; a new one starts around wherever the player then is. */
    int sweeps() { return sight.sweeps(); }

    /** Told of each block of a kind remembered for the first time (F-30's atlas), on the server thread; none by default. */
    void onFirstSight(java.util.function.BiConsumer<Kind, BlockPos> listener) {
        sight.onFirstSight((kind, cell) -> listener.accept(kind(kind), pos(cell)));
    }

    void remember(Kind kind, BlockPos pos) { sight.remember(sight(kind), cell(pos)); }

    /**
     * F-30d: a block of a kind it saw before this start in {@code world} (a dimension's id), as its atlas kept it,
     * remembered again without telling the atlas (it has it already). Checked against the world before any use, as
     * everything remembered is; nothing for a world other than the one it is in.
     */
    void recall(String world, Kind kind, BlockPos pos) {
        // Of the world it is in: the first sweep's change of world would otherwise forget it at once.
        if (dimension.isEmpty()) dimension = world;
        if (!world.equals(dimension)) return;
        sight.recall(sight(kind), cell(pos));
    }

    void forget(BlockPos pos) { sight.forget(cell(pos)); }

    int count(Kind kind) { return sight.count(sight(kind)); }

    /** For tests: where it last looked round from, as a scan sets it; the far ones go from here when a kind is full. */
    void centerAt(BlockPos at) { sight.centerAt(at == null ? null : cell(at)); }

    /** How many a full kind drops at once, the farthest first (the core's, {@link Sight#EVICT}). */
    static final int EVICT = Sight.EVICT;

    static int maxPerKind() { return Sight.maxPerKind(); }

    /** Nearest remembered block of a kind that still matches the world and the caller's filter. */
    Optional<BlockPos> nearest(ServerWorld world, Kind kind, BlockPos from, Predicate<BlockPos> filter) {
        return sight.nearest(new WorldSight(world, null), sight(kind), cell(from), c -> filter.test(pos(c))).map(WorldKnowledge::pos);
    }

    List<BlockPos> all(Kind kind) { return sight.all(sight(kind)).stream().map(WorldKnowledge::pos).toList(); }

    /**
     * Whether a walk from {@code from} to {@code to} would end in open water it saw (F-35j). Within its sight
     * ({@value #HORIZONTAL_RADIUS} blocks), every column within two of the end is still water open to the air that it saw;
     * beyond, the last {@code run} columns along the line out to the edge of its sight are. Nothing is read from the world:
     * a column it has not seen is not water to it, and land seen beyond a strait breaks the run, so a crossing to land it
     * can see is not open water.
     */
    boolean seenOpenWaterToward(BlockPos from, BlockPos to, int run) {
        return sight.seenOpenWaterToward(cell(from), cell(to), run);
    }

    /** Lowest-scoring remembered block of a kind that still matches the world and the caller's filter. */
    Optional<BlockPos> best(ServerWorld world, Kind kind, java.util.function.ToDoubleFunction<BlockPos> score, Predicate<BlockPos> filter) {
        return sight.best(new WorldSight(world, null), sight(kind), c -> score.applyAsDouble(pos(c)), c -> filter.test(pos(c))).map(WorldKnowledge::pos);
    }

    /**
     * The live world as the core's sight reads it: each fact as this class read it before the port, the sight lines by the
     * game's own ray cast from the player (its shape context), and the blocks of the section a scan is reading taken from
     * that section, as the sweep read them.
     */
    private static final class WorldSight implements SightWorld {
        private final ServerWorld world;
        private final ServerPlayerEntity player;
        private ChunkSection section;
        private int sectionX, sectionY, sectionZ;

        WorldSight(ServerWorld world, ServerPlayerEntity player) {
            this.world = world;
            this.player = player;
        }

        private BlockState state(int x, int y, int z) {
            if (section != null && x >> 4 == sectionX && y >> 4 == sectionY && z >> 4 == sectionZ) return section.getBlockState(x & 15, y & 15, z & 15);
            return world.getBlockState(new BlockPos(x, y, z));
        }

        @Override public boolean inLoadedChunk(int x, int y, int z) { return world.isChunkLoaded(new BlockPos(x, y, z)); }
        @Override public boolean columnLoaded(int chunkX, int chunkZ) { return world.isChunkLoaded(chunkX, chunkZ); }
        @Override public boolean sectionLoaded(int sx, int sy, int sz) {
            section = null;
            var chunk = world.getChunkManager().getWorldChunk(sx, sz);
            if (chunk == null || sy < world.getBottomSectionCoord() || sy >= world.getTopSectionCoord()) return false;
            ChunkSection at = chunk.getSection(chunk.sectionCoordToIndex(sy));
            section = at;
            sectionX = sx;
            sectionY = sy;
            sectionZ = sz;
            return true;
        }
        @Override public boolean sectionOfInterest(int sx, int sy, int sz) {
            if (section == null || sx != sectionX || sy != sectionY || sz != sectionZ) sectionLoaded(sx, sy, sz);
            return section != null && !(section.isEmpty() || !section.hasAny(state -> classify(state) != null));
        }
        @Override public Sight.Kind kind(int x, int y, int z) { return sight(classify(state(x, y, z))); }
        @Override public boolean air(int x, int y, int z) { return state(x, y, z).isAir(); }
        @Override public boolean fluidEmpty(int x, int y, int z) { return state(x, y, z).getFluidState().isEmpty(); }
        @Override public boolean water(int x, int y, int z) { return state(x, y, z).getFluidState().isIn(FluidTags.WATER); }
        @Override public boolean opaqueFullCube(int x, int y, int z) { return state(x, y, z).isOpaqueFullCube(world, new BlockPos(x, y, z)); }
        @Override public boolean log(int x, int y, int z) { return state(x, y, z).isIn(BlockTags.LOGS); }
        @Override public boolean leaves(int x, int y, int z) { return state(x, y, z).isIn(BlockTags.LEAVES); }
        @Override public boolean collisionEmpty(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return state(x, y, z).getCollisionShape(world, pos).isEmpty();
        }
        @Override public boolean built(int x, int y, int z) {
            BlockState state = state(x, y, z);
            return state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.STAIRS) || state.isIn(BlockTags.SLABS) || state.isIn(BlockTags.WALLS)
                    || state.isIn(BlockTags.DOORS) || state.isIn(BlockTags.TRAPDOORS) || state.isIn(BlockTags.FENCES)
                    || state.isIn(BlockTags.FENCE_GATES) || state.isIn(BlockTags.WOOL) || state.isIn(BlockTags.BEDS)
                    || state.isIn(BlockTags.IMPERMEABLE) || state.getBlock() instanceof net.minecraft.block.PaneBlock;
        }
        @Override public int seaLevel() { return world.getSeaLevel(); }
        @Override public int skyLight(int x, int y, int z) { return world.getLightLevel(net.minecraft.world.LightType.SKY, new BlockPos(x, y, z)); }
        @Override public int motionTop(int x, int z) { return world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z); }
        @Override public float fluidHeight(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return world.getFluidState(pos).getHeight(world, pos);
        }
        @Override public Cell ray(double ax, double ay, double az, double bx, double by, double bz, boolean fluids) {
            BlockHitResult hit = world.raycast(new RaycastContext(new Vec3d(ax, ay, az), new Vec3d(bx, by, bz), RaycastContext.ShapeType.COLLIDER,
                    fluids ? RaycastContext.FluidHandling.ANY : RaycastContext.FluidHandling.NONE, player));
            return hit.getType() == HitResult.Type.MISS ? null : cell(hit.getBlockPos());
        }
    }
}
