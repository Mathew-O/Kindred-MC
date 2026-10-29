package dev.kindred.fabric;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.ChunkSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    static final int HORIZONTAL_RADIUS = 32;
    static final int VERTICAL_RADIUS = 24;
    private static final int BLOCK_READS_PER_SCAN = 6_000;
    private static final int RAYCASTS_PER_SCAN = 24;
    /** Cave floors passed in a sweep while their sight lines were spent, looked at first in the scans after. */
    private static final int CAVE_BACKLOG = 1024;
    private final java.util.ArrayDeque<BlockPos> caveBacklog = new java.util.ArrayDeque<>();
    private static final int MAX_PER_KIND = 2_000;

    private final Map<Kind, Map<Long, BlockPos>> known = new EnumMap<>(Kind.class);
    private String dimension = "";
    private final List<ChunkSectionPos> sweep = new ArrayList<>();
    private int cursor;
    private int index;
    private int sweeps;

    WorldKnowledge() {
        for (Kind kind : Kind.values()) known.put(kind, new HashMap<>());
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

    private static boolean needsSight(Kind kind) {
        return kind == Kind.COAL_ORE || kind == Kind.IRON_ORE || kind == Kind.DIAMOND_ORE || kind == Kind.WATER || kind == Kind.LAVA
                || kind == Kind.CAVE || kind == Kind.SPAWNER || kind == Kind.STONE_BRICK || kind == Kind.END_PORTAL_FRAME
                || kind == Kind.NETHER_BRICK || kind == Kind.COBWEB || kind == Kind.NETHER_GOLD_ORE || kind == Kind.CONTAINER
                || kind == Kind.GOLD_BLOCK;
    }

    /**
     * A place a player could stand in a cave: two blocks of air over a stone floor, out of daylight (sky light
     * at most 7, so overhangs at a cave mouth count and open ground does not), or a ravine floor well below sea level.
     * Ground open to the sky, nothing above it in the heightmap, is never a cave floor, whatever its light says: sky light
     * reaches stone just set a few ticks late, more so on a busy server, and a wall's top read as a cave in the meantime
     * sent players on walks to it.
     */
    static boolean caveFloor(ServerWorld world, BlockPos feet) {
        if (!world.getBlockState(feet).isAir() || !world.getBlockState(feet.up()).isAir()) return false;
        if (classify(world.getBlockState(feet.down())) != Kind.STONE) return false;
        if (feet.getY() < world.getSeaLevel() - 10) return true;
        return world.getLightLevel(net.minecraft.world.LightType.SKY, feet) <= 7
                && world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, feet.getX(), feet.getZ()) > feet.getY();
    }

    private static long cell(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
    }

    /** Advances the incremental sweep around the player by a bounded amount of block reads. */
    void scan(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        String dim = world.getRegistryKey().getValue().toString();
        if (!dim.equals(dimension)) {
            dimension = dim;
            for (var map : known.values()) map.clear();
            sweep.clear();
            caveBacklog.clear();
        }
        // A sweep ends once the cave floors it passed over are looked at too: what was not seen in it is known not there.
        if (cursor >= sweep.size() && caveBacklog.isEmpty()) rebuildSweep(player);
        int reads = 0;
        int raycasts = 0, caveRaycasts = 0;
        while (!caveBacklog.isEmpty() && caveRaycasts < RAYCASTS_PER_SCAN) {
            BlockPos floor = caveBacklog.poll();
            if (known.get(Kind.CAVE).containsKey(cell(floor)) || !world.isChunkLoaded(floor)) continue;
            caveRaycasts++;
            if (visible(player, floor)) remember(Kind.CAVE, floor);
        }
        while (cursor < sweep.size() && reads < BLOCK_READS_PER_SCAN) {
            ChunkSectionPos sectionPos = sweep.get(cursor);
            var chunk = world.getChunkManager().getWorldChunk(sectionPos.getSectionX(), sectionPos.getSectionZ());
            if (chunk == null || sectionPos.getSectionY() < world.getBottomSectionCoord() || sectionPos.getSectionY() >= world.getTopSectionCoord()) {
                nextSection();
                continue;
            }
            ChunkSection section = chunk.getSection(chunk.sectionCoordToIndex(sectionPos.getSectionY()));
            if (section.isEmpty() || !section.hasAny(state -> classify(state) != null)) {
                nextSection();
                continue;
            }
            while (index < 4096 && reads < BLOCK_READS_PER_SCAN) {
                int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
                reads++;
                BlockState state = section.getBlockState(x, y, z);
                Kind kind = classify(state);
                if (kind != null) {
                    BlockPos pos = new BlockPos(sectionPos.getMinX() + x, sectionPos.getMinY() + y, sectionPos.getMinZ() + z);
                    // Stone with a cave floor on top: one sighting per cell is enough to know there is a cave there.
                    BlockPos floor = pos.up();
                    boolean airAbove = y < 15 ? section.getBlockState(x, y + 1, z).isAir() : world.getBlockState(floor).isAir();
                    // Cave floors have sight lines of their own to spend: a wide floor of open stone (a hillside, a quarry)
                    // took them all, scan after scan, and the ore beside it went unseen. Those it has none left for are
                    // looked at first in the scans after.
                    if (kind == Kind.STONE && airAbove && !known.get(Kind.CAVE).containsKey(cell(floor)) && caveFloor(world, floor)) {
                        if (caveRaycasts < RAYCASTS_PER_SCAN) {
                            caveRaycasts++;
                            if (visible(player, floor)) remember(Kind.CAVE, floor);
                        } else if (caveBacklog.size() < CAVE_BACKLOG) {
                            caveBacklog.add(floor.toImmutable());
                        }
                    }
                    if (exposed(world, pos, kind)) {
                        if (needsSight(kind)) {
                            if (raycasts >= RAYCASTS_PER_SCAN) return; // Resume at this exact block next scan.
                            raycasts++;
                            if (visible(player, pos)) remember(kind, pos);
                        } else {
                            remember(kind, pos);
                        }
                    }
                }
                index++;
            }
            if (index >= 4096) nextSection();
        }
    }

    /**
     * A close look at one place, as a player looks over something it has come to: every block of {@code kind} within
     * {@code radius} (and {@code height} up and down) of {@code center} that the player can see from where it stands is
     * remembered now, wherever the sweep has got to. Bounded: a small box, and one sight line a block of the kind.
     */
    void look(ServerPlayerEntity player, BlockPos center, int radius, int height, Kind kind) {
        ServerWorld world = player.getServerWorld();
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -height, -radius), center.add(radius, height, radius))) {
            if (!world.isChunkLoaded(pos) || classify(world.getBlockState(pos)) != kind || !exposed(world, pos, kind)) continue;
            if (!needsSight(kind) || visible(player, pos)) remember(kind, pos);
        }
    }

    private void nextSection() {
        cursor++;
        index = 0;
    }

    /** Completed sweeps of the surroundings so far; a new one starts around wherever the player then is. */
    int sweeps() { return sweeps; }

    private void rebuildSweep(ServerPlayerEntity player) {
        if (!sweep.isEmpty()) sweeps++;
        sweep.clear();
        cursor = 0;
        index = 0;
        ChunkSectionPos center = ChunkSectionPos.from(player);
        int horizontal = HORIZONTAL_RADIUS >> 4;
        int vertical = Math.max(1, VERTICAL_RADIUS >> 4);
        for (int dy = -vertical; dy <= vertical; dy++) {
            for (int dx = -horizontal; dx <= horizontal; dx++) {
                for (int dz = -horizontal; dz <= horizontal; dz++) {
                    sweep.add(ChunkSectionPos.from(center.getSectionX() + dx, center.getSectionY() + dy, center.getSectionZ() + dz));
                }
            }
        }
        // Nearest sections first, so fresh surroundings are known quickly.
        sweep.sort((a, b) -> Integer.compare(distance(a, center), distance(b, center)));
    }

    private static int distance(ChunkSectionPos a, ChunkSectionPos b) {
        int dx = a.getSectionX() - b.getSectionX(), dy = a.getSectionY() - b.getSectionY(), dz = a.getSectionZ() - b.getSectionZ();
        return dx * dx + dy * dy * 2 + dz * dz;
    }

    private static boolean exposed(ServerWorld world, BlockPos pos, Kind kind) {
        for (Direction direction : Direction.values()) {
            BlockPos near = pos.offset(direction);
            if (!world.isChunkLoaded(near)) continue;
            BlockState state = world.getBlockState(near);
            if (kind == Kind.WATER || kind == Kind.LAVA) {
                if (state.isAir()) return true;
                continue;
            }
            // A face under water is not a face a walking player can work from, except obsidian, which forms
            // under the water poured to make it.
            if (!state.getFluidState().isEmpty() && kind == Kind.OBSIDIAN && state.getFluidState().isIn(FluidTags.WATER)) return true;
            if (state.getFluidState().isEmpty() && (state.isAir() || !state.isOpaqueFullCube(world, near))) return true;
        }
        return false;
    }

    private static boolean visible(ServerPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        if (eye.squaredDistanceTo(center) > (HORIZONTAL_RADIUS + 8) * (HORIZONTAL_RADIUS + 8)) return false;
        if (sees(player, eye, center, pos)) return true;
        // A pool set into the floor shows only its surface: from a few blocks away the ray to its centre meets the
        // floor's edge, while a player plainly sees the lava or water. Look at the surface too.
        var fluid = player.getServerWorld().getFluidState(pos);
        if (fluid.isEmpty()) return false;
        float height = fluid.getHeight(player.getServerWorld(), pos);
        return sees(player, eye, new Vec3d(pos.getX() + 0.5, pos.getY() + Math.max(0.1, height - 0.05), pos.getZ() + 0.5), pos);
    }

    private static boolean sees(ServerPlayerEntity player, Vec3d eye, Vec3d point, BlockPos pos) {
        BlockHitResult hit = player.getServerWorld().raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.ANY, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    void remember(Kind kind, BlockPos pos) {
        var map = known.get(kind);
        long key = kind == Kind.CAVE ? cell(pos) : pos.asLong();
        if (map.size() >= MAX_PER_KIND && !map.containsKey(key)) return;
        map.put(key, pos.toImmutable());
    }

    void forget(BlockPos pos) {
        for (var map : known.values()) map.remove(pos.asLong());
        known.get(Kind.CAVE).remove(cell(pos));
    }

    /** Still what it was remembered as: the same kind of block, or for a cave, still a cave floor. */
    private static boolean still(ServerWorld world, Kind kind, BlockPos pos) {
        return kind == Kind.CAVE ? caveFloor(world, pos) : classify(world.getBlockState(pos)) == kind;
    }

    int count(Kind kind) { return known.get(kind).size(); }

    /** Nearest remembered block of a kind that still matches the world and the caller's filter. */
    Optional<BlockPos> nearest(ServerWorld world, Kind kind, BlockPos from, Predicate<BlockPos> filter) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        var stale = new ArrayList<Long>();
        for (var entry : known.get(kind).entrySet()) {
            BlockPos pos = entry.getValue();
            if (world.isChunkLoaded(pos) && !still(world, kind, pos)) {
                stale.add(entry.getKey());
                continue;
            }
            double distance = pos.getSquaredDistance(from);
            if (distance < bestDistance && filter.test(pos)) {
                bestDistance = distance;
                best = pos;
            }
        }
        stale.forEach(key -> known.get(kind).remove(key));
        return Optional.ofNullable(best);
    }

    List<BlockPos> all(Kind kind) { return List.copyOf(known.get(kind).values()); }

    /** Lowest-scoring remembered block of a kind that still matches the world and the caller's filter. */
    Optional<BlockPos> best(ServerWorld world, Kind kind, java.util.function.ToDoubleFunction<BlockPos> score, Predicate<BlockPos> filter) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        var stale = new ArrayList<Long>();
        for (var entry : known.get(kind).entrySet()) {
            BlockPos pos = entry.getValue();
            if (world.isChunkLoaded(pos) && !still(world, kind, pos)) {
                stale.add(entry.getKey());
                continue;
            }
            if (!filter.test(pos)) continue;
            double value = score.applyAsDouble(pos);
            if (value < bestScore) {
                bestScore = value;
                best = pos;
            }
        }
        stale.forEach(key -> known.get(kind).remove(key));
        return Optional.ofNullable(best);
    }
}
