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
 * reaches them, so the player plans from what it could see rather than from hidden world data.
 */
final class WorldKnowledge {
    enum Kind { LOG, STONE, COAL_ORE, IRON_ORE, DIAMOND_ORE, GRAVEL, WATER, LAVA, CRAFTING_TABLE, FURNACE, OBSIDIAN }

    static final int HORIZONTAL_RADIUS = 32;
    static final int VERTICAL_RADIUS = 24;
    private static final int BLOCK_READS_PER_SCAN = 6_000;
    private static final int RAYCASTS_PER_SCAN = 24;
    private static final int MAX_PER_KIND = 2_000;

    private final Map<Kind, Map<Long, BlockPos>> known = new EnumMap<>(Kind.class);
    private String dimension = "";
    private final List<ChunkSectionPos> sweep = new ArrayList<>();
    private int cursor;
    private int index;

    WorldKnowledge() {
        for (Kind kind : Kind.values()) known.put(kind, new HashMap<>());
    }

    static Kind classify(BlockState state) {
        if (state.isIn(BlockTags.LOGS)) return Kind.LOG;
        if (state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.COBBLED_DEEPSLATE)) return Kind.STONE;
        if (state.isOf(Blocks.COAL_ORE) || state.isOf(Blocks.DEEPSLATE_COAL_ORE)) return Kind.COAL_ORE;
        if (state.isOf(Blocks.IRON_ORE) || state.isOf(Blocks.DEEPSLATE_IRON_ORE)) return Kind.IRON_ORE;
        if (state.isOf(Blocks.DIAMOND_ORE) || state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE)) return Kind.DIAMOND_ORE;
        if (state.isOf(Blocks.GRAVEL)) return Kind.GRAVEL;
        if (state.isOf(Blocks.CRAFTING_TABLE)) return Kind.CRAFTING_TABLE;
        if (state.isOf(Blocks.FURNACE)) return Kind.FURNACE;
        if (state.isOf(Blocks.OBSIDIAN)) return Kind.OBSIDIAN;
        var fluid = state.getFluidState();
        if (fluid.isStill() && fluid.isIn(FluidTags.WATER) && state.isOf(Blocks.WATER)) return Kind.WATER;
        if (fluid.isStill() && fluid.isIn(FluidTags.LAVA)) return Kind.LAVA;
        return null;
    }

    private static boolean needsSight(Kind kind) {
        return kind == Kind.COAL_ORE || kind == Kind.IRON_ORE || kind == Kind.DIAMOND_ORE || kind == Kind.WATER || kind == Kind.LAVA;
    }

    /** Advances the incremental sweep around the player by a bounded amount of block reads. */
    void scan(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        String dim = world.getRegistryKey().getValue().toString();
        if (!dim.equals(dimension)) {
            dimension = dim;
            for (var map : known.values()) map.clear();
            sweep.clear();
        }
        if (cursor >= sweep.size()) rebuildSweep(player);
        int reads = 0;
        int raycasts = 0;
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

    private void nextSection() {
        cursor++;
        index = 0;
    }

    private void rebuildSweep(ServerPlayerEntity player) {
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
        BlockHitResult hit = player.getServerWorld().raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.ANY, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    void remember(Kind kind, BlockPos pos) {
        var map = known.get(kind);
        if (map.size() >= MAX_PER_KIND && !map.containsKey(pos.asLong())) return;
        map.put(pos.asLong(), pos.toImmutable());
    }

    void forget(BlockPos pos) {
        for (var map : known.values()) map.remove(pos.asLong());
    }

    int count(Kind kind) { return known.get(kind).size(); }

    /** Nearest remembered block of a kind that still matches the world and the caller's filter. */
    Optional<BlockPos> nearest(ServerWorld world, Kind kind, BlockPos from, Predicate<BlockPos> filter) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        var stale = new ArrayList<Long>();
        for (var entry : known.get(kind).entrySet()) {
            BlockPos pos = entry.getValue();
            if (world.isChunkLoaded(pos) && classify(world.getBlockState(pos)) != kind) {
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
}
