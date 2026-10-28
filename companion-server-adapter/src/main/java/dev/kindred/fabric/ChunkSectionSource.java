package dev.kindred.fabric;

import dev.kindred.core.BlockPerception;
import dev.kindred.core.SectionKey;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In plain terms: lets an AI player look for blocks (ores, logs and so on) in the parts of the world that are
 * already loaded, quickly, and only counting blocks it could actually see: ones with air or glass next to
 * them. It never loads new chunks and never sees through walls.
 *
 * Technical: Server-thread section views over already-loaded chunks. Nothing here loads a chunk: an absent
 * chunk is {@code null}. The palette prefilter asks the section's {@code PalettedContainer} whether any entry
 * is a block of interest, which is proportional to the palette (usually a few dozen states), not to 4,096
 * blocks. Only a section whose palette says "maybe" is walked, and only matching blocks pay for the
 * six-neighbour exposure check.
 */
final class ChunkSectionSource implements BlockPerception.SectionSource {
    private final ServerWorld world;
    private Set<String> cachedIds = Set.of();
    private Set<Block> cachedBlocks = Set.of();
    private final Map<Block, String> idNames = new HashMap<>();

    ChunkSectionSource(ServerWorld world) { this.world = world; }

    ServerWorld world() { return world; }

    /** One 16-block cube of the world, if its chunk is loaded. */
    @Override
    public BlockPerception.SectionView section(SectionKey key) {
        if (key.y() < world.getBottomSectionCoord() || key.y() >= world.getTopSectionCoord()) return null;
        WorldChunk chunk = world.getChunkManager().getWorldChunk(key.x(), key.z()); // null when not loaded; never loads
        if (chunk == null) return null;
        return new View(chunk, chunk.getSection(chunk.sectionCoordToIndex(key.y())), key);
    }

    /** Resolve identities to registry objects once per interest set; unknown identities match nothing. */
    private Set<Block> blocks(Set<String> ids) {
        if (ids.equals(cachedIds)) return cachedBlocks;
        Set<Block> resolved = new HashSet<>();
        for (String id : ids) {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier != null && Registries.BLOCK.containsId(identifier)) resolved.add(Registries.BLOCK.get(identifier));
        }
        cachedIds = Set.copyOf(ids);
        cachedBlocks = Set.copyOf(resolved);
        return cachedBlocks;
    }

    private String idOf(Block block) {
        return idNames.computeIfAbsent(block, b -> Registries.BLOCK.getId(b).toString());
    }

    /** A neighbour that is air or lets light through means the block can be seen or reached from outside. */
    private static boolean revealing(BlockState neighbour) {
        return neighbour.isAir() || !neighbour.isOpaque();
    }

    private final class View implements BlockPerception.SectionView {
        private final WorldChunk chunk;
        private final ChunkSection section;
        private final SectionKey key;

        private View(WorldChunk chunk, ChunkSection section, SectionKey key) {
            this.chunk = chunk;
            this.section = section;
            this.key = key;
        }

        @Override
        public boolean isEmpty() { return section.isEmpty(); }

        /** A quick check whether any of these blocks could be in this cube. */
        @Override
        public boolean mayContain(Set<String> blockIds) {
            Set<Block> wanted = blocks(blockIds);
            return !wanted.isEmpty() && section.hasAny(state -> wanted.contains(state.getBlock()));
        }

        /** Reports each matching block in this cube that can be seen. */
        @Override
        public void visitMatches(Set<String> blockIds, BlockPerception.MatchVisitor visitor) {
            Set<Block> wanted = blocks(blockIds);
            if (wanted.isEmpty()) return;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!wanted.contains(state.getBlock())) continue;
                        var position = new WorkSetup.BlockPoint(key.minBlockX() + x, key.minBlockY() + y, key.minBlockZ() + z);
                        if (!visitor.visit(position, idOf(state.getBlock()), exposed(x, y, z))) return;
                    }
                }
            }
        }

        /** Whether any side of the block touches air or something see-through. */
        private boolean exposed(int x, int y, int z) {
            return revealing(neighbour(x + 1, y, z)) || revealing(neighbour(x - 1, y, z))
                    || revealing(neighbour(x, y + 1, z)) || revealing(neighbour(x, y - 1, z))
                    || revealing(neighbour(x, y, z + 1)) || revealing(neighbour(x, y, z - 1));
        }

        /** Inside the section: a direct palette read. Across an edge: the same chunk, or a loaded neighbour; unknown counts as hidden. */
        private BlockState neighbour(int x, int y, int z) {
            if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) return section.getBlockState(x, y, z);
            int worldY = key.minBlockY() + y;
            if (worldY >= world.getTopY()) return net.minecraft.block.Blocks.AIR.getDefaultState();
            if (worldY < world.getBottomY()) return net.minecraft.block.Blocks.BEDROCK.getDefaultState();
            BlockPos pos = new BlockPos(key.minBlockX() + x, worldY, key.minBlockZ() + z);
            if (x >= 0 && x < 16 && z >= 0 && z < 16) return chunk.getBlockState(pos);
            WorldChunk other = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return other == null ? net.minecraft.block.Blocks.BEDROCK.getDefaultState() : other.getBlockState(pos);
        }
    }
}
