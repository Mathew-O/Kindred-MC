package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * In plain terms: reads building plans saved by the game's structure blocks or by common building tools, so a
 * player can build from them. Only the blocks are taken, never what chests or signs held; blocks no survival
 * player can get are left out; and who made the plan and its terms of use are kept with it.
 *
 * Technical: Schematics read into blueprints (M-05): vanilla structure files ({@code .nbt}, as a structure
 * block saves them), Sponge schematics ({@code .schem}, versions 2 and 3, as WorldEdit saves them) and Litematica
 * schematics ({@code .litematic}, versions 4 to 7, every region of one).
 * Positions are kept relative to the file's own corner; air is space to keep clear; structure void is neither
 * a block nor space. What a block entity held (a chest's items, a sign's words) is never read: a build places
 * blocks, and nothing comes from nowhere. Who made it and the terms it is used under go with it (D8). Pure.
 */
public final class Schematics {
    /** What was read, and what was left out and why. */
    public record Read(Blueprint blueprint, int dataVersion, List<String> notes) {
        public Read { notes = List.copyOf(notes); }
    }

    /**
     * Blocks a survival player cannot get as an item at all: they are never in a bill of materials as something to
     * gather, only reported (M-05).
     */
    public static final Set<String> UNOBTAINABLE = Set.of("minecraft:bedrock", "minecraft:barrier", "minecraft:light",
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:spawner",
            "minecraft:trial_spawner", "minecraft:vault", "minecraft:end_portal_frame", "minecraft:end_portal",
            "minecraft:nether_portal", "minecraft:end_gateway", "minecraft:budding_amethyst", "minecraft:reinforced_deepslate",
            "minecraft:petrified_oak_slab", "minecraft:farmland", "minecraft:dirt_path", "minecraft:frosted_ice",
            "minecraft:chorus_plant", "minecraft:infested_stone", "minecraft:infested_cobblestone",
            "minecraft:infested_stone_bricks", "minecraft:infested_mossy_stone_bricks", "minecraft:infested_cracked_stone_bricks",
            "minecraft:infested_chiseled_stone_bricks", "minecraft:infested_deepslate", "minecraft:moving_piston",
            "minecraft:piston_head", "minecraft:fire", "minecraft:soul_fire", "minecraft:water", "minecraft:lava",
            "minecraft:bubble_column", "minecraft:tall_seagrass", "minecraft:test_block", "minecraft:test_instance_block");

    private static final Set<String> VOID = Set.of("minecraft:structure_void");

    private Schematics() { }

    /**
     * Reads {@code data}, a {@code .nbt}, {@code .schem} or {@code .litematic} file, into a blueprint called {@code name}. The creator is
     * the file's own (a Sponge schematic's author, a structure's) unless {@code creator} names one.
     */
    public static Read read(byte[] data, String name, String source, String creator, String license) {
        var root = Nbt.read(data);
        if (root.get("Regions") instanceof Map<?, ?>) return litematic(root, name, source, creator, license);
        if (root.containsKey("palette") || root.containsKey("palettes")) return structure(root, name, source, creator, license);
        var sponge = root.get("Schematic") instanceof Map<?, ?> ? Nbt.compound(root, "Schematic") : root;
        if (sponge.containsKey("Version") && (sponge.containsKey("BlockData") || sponge.containsKey("Blocks"))) {
            return sponge(sponge, name, source, creator, license);
        }
        throw new IllegalArgumentException("Neither a structure file, a Sponge schematic nor a Litematica schematic");
    }

    private static Read structure(Map<String, Object> root, String name, String source, String creator, String license) {
        var notes = new ArrayList<String>();
        List<Object> palette = Nbt.list(root, "palette");
        if (palette.isEmpty()) {
            var palettes = Nbt.list(root, "palettes");
            if (palettes.isEmpty()) throw new IllegalArgumentException("A structure with no palette");
            if (palettes.size() > 1) notes.add("One of " + palettes.size() + " palettes used (the first)");
            @SuppressWarnings("unchecked")
            var first = (List<Object>) palettes.getFirst();
            palette = first;
        }
        var states = new ArrayList<BlockSpec>(palette.size());
        for (Object entry : palette) {
            @SuppressWarnings("unchecked")
            var state = (Map<String, Object>) entry;
            var properties = new TreeMap<String, String>();
            Nbt.compound(state, "Properties").forEach((k, v) -> properties.put(k, String.valueOf(v)));
            states.add(new BlockSpec(Nbt.string(state, "Name"), properties));
        }
        var blocks = new ArrayList<Blueprint.Block>();
        var clear = new ArrayList<Pos>();
        var skipped = new TreeSet<String>();
        int entities = 0;
        for (Object entry : Nbt.list(root, "blocks")) {
            @SuppressWarnings("unchecked")
            var block = (Map<String, Object>) entry;
            var pos = Nbt.list(block, "pos");
            if (pos.size() != 3) throw new IllegalArgumentException("A block with no position");
            var at = new Pos(((Number) pos.get(0)).intValue(), ((Number) pos.get(1)).intValue(), ((Number) pos.get(2)).intValue());
            int index = Nbt.integer(block, "state", -1);
            if (index < 0 || index >= states.size()) throw new IllegalArgumentException("A block with no state in the palette");
            if (block.containsKey("nbt")) entities++;
            add(states.get(index), at, blocks, clear, skipped);
        }
        if (entities > 0) notes.add(entities + " block entities placed empty: their contents are not copied");
        if (!Nbt.list(root, "entities").isEmpty()) notes.add(Nbt.list(root, "entities").size() + " entities left out");
        skipped.forEach(id -> notes.add("Left out: " + id));
        String author = Nbt.string(root, "author");
        return new Read(new Blueprint(name, source, creator.isBlank() ? author : creator, license, blocks, clear),
                Nbt.integer(root, "DataVersion", 0), notes);
    }

    private static Read sponge(Map<String, Object> schematic, String name, String source, String creator, String license) {
        var notes = new ArrayList<String>();
        int version = Nbt.integer(schematic, "Version", 0);
        if (version < 2 || version > 3) throw new IllegalArgumentException("Sponge schematic version " + version + " is not read (2 and 3 are)");
        int width = Nbt.integer(schematic, "Width", 0) & 0xffff, height = Nbt.integer(schematic, "Height", 0) & 0xffff,
                length = Nbt.integer(schematic, "Length", 0) & 0xffff;
        if (width == 0 || height == 0 || length == 0) throw new IllegalArgumentException("A schematic of no size");
        if (width > Blueprint.MAX_SPAN || height > Blueprint.MAX_SPAN || length > Blueprint.MAX_SPAN) {
            throw new IllegalArgumentException("Wider than " + Blueprint.MAX_SPAN + " blocks: " + width + "x" + height + "x" + length);
        }
        var container = version == 3 ? Nbt.compound(schematic, "Blocks") : schematic;
        var palette = Nbt.compound(container, "Palette");
        Object data = version == 3 ? container.get("Data") : schematic.get("BlockData");
        if (!(data instanceof byte[] bytes)) throw new IllegalArgumentException("A schematic with no block data");
        var states = new HashMap<Integer, BlockSpec>();
        palette.forEach((key, value) -> states.put(((Number) value).intValue(), BlockSpec.parse(key)));
        var blocks = new ArrayList<Blueprint.Block>();
        var clear = new ArrayList<Pos>();
        var skipped = new TreeSet<String>();
        int index = 0, at = 0, cells = width * height * length;
        while (at < bytes.length && index < cells) {
            // Each cell's palette index, as a varint.
            int value = 0, shift = 0, b;
            do {
                if (at >= bytes.length) throw new IllegalArgumentException("Block data ends inside a number");
                b = bytes[at++];
                value |= (b & 0x7f) << shift;
                shift += 7;
                if (shift > 28) throw new IllegalArgumentException("A palette index too long");
            } while ((b & 0x80) != 0);
            var state = states.get(value);
            if (state == null) throw new IllegalArgumentException("A block not in the palette: " + value);
            int y = index / (width * length), rest = index % (width * length), z = rest / width, x = rest % width;
            add(state, new Pos(x, y, z), blocks, clear, skipped);
            index++;
        }
        if (index != cells) throw new IllegalArgumentException("Block data for " + index + " of " + cells + " cells");
        var entities = Nbt.list(container, version == 3 ? "BlockEntities" : "BlockEntities");
        if (!entities.isEmpty()) notes.add(entities.size() + " block entities placed empty: their contents are not copied");
        if (!Nbt.list(schematic, "Entities").isEmpty()) notes.add(Nbt.list(schematic, "Entities").size() + " entities left out");
        skipped.forEach(id -> notes.add("Left out: " + id));
        var metadata = Nbt.compound(schematic, "Metadata");
        String author = Nbt.string(metadata, "Author");
        String title = Nbt.string(metadata, "Name");
        return new Read(new Blueprint(name.isBlank() ? title : name, source, creator.isBlank() ? author : creator, license, blocks, clear),
                Nbt.integer(schematic, "DataVersion", 0), notes);
    }

    /**
     * A Litematica schematic: one or more regions, each with its corner, its size (negative along an axis it was
     * selected backwards on), its own palette, and its blocks as palette indices packed into longs, each index as wide
     * as the palette needs (two bits at least), x fastest, then z, then y, an index running over from one long into
     * the next where it does not fit. Positions are kept relative to the corner of all the regions together.
     */
    private static Read litematic(Map<String, Object> root, String name, String source, String creator, String license) {
        var notes = new ArrayList<String>();
        int version = Nbt.integer(root, "Version", 0);
        if (version < 4 || version > 7) throw new IllegalArgumentException("Litematica schematic version " + version + " is not read (4 to 7 are)");
        var regions = Nbt.compound(root, "Regions");
        if (regions.isEmpty()) throw new IllegalArgumentException("A Litematica schematic with no regions");
        record Region(String name, Map<String, Object> tag, int x, int y, int z, int sx, int sy, int sz) { }
        var list = new ArrayList<Region>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var entry : regions.entrySet()) {
            @SuppressWarnings("unchecked")
            var region = (Map<String, Object>) entry.getValue();
            var position = Nbt.compound(region, "Position");
            var size = Nbt.compound(region, "Size");
            int px = Nbt.integer(position, "x", 0), py = Nbt.integer(position, "y", 0), pz = Nbt.integer(position, "z", 0);
            int sx = Nbt.integer(size, "x", 0), sy = Nbt.integer(size, "y", 0), sz = Nbt.integer(size, "z", 0);
            if (sx == 0 || sy == 0 || sz == 0) throw new IllegalArgumentException("Region " + entry.getKey() + " has no size");
            // A negative size runs back from the region's position: its corner is at the far end.
            int cx = sx < 0 ? px + sx + 1 : px, cy = sy < 0 ? py + sy + 1 : py, cz = sz < 0 ? pz + sz + 1 : pz;
            sx = Math.abs(sx); sy = Math.abs(sy); sz = Math.abs(sz);
            if (sx > Blueprint.MAX_SPAN || sy > Blueprint.MAX_SPAN || sz > Blueprint.MAX_SPAN) {
                throw new IllegalArgumentException("Wider than " + Blueprint.MAX_SPAN + " blocks: " + sx + "x" + sy + "x" + sz);
            }
            list.add(new Region(entry.getKey(), region, cx, cy, cz, sx, sy, sz));
            minX = Math.min(minX, cx); minY = Math.min(minY, cy); minZ = Math.min(minZ, cz);
            maxX = Math.max(maxX, cx + sx - 1); maxY = Math.max(maxY, cy + sy - 1); maxZ = Math.max(maxZ, cz + sz - 1);
        }
        if (maxX - minX + 1 > Blueprint.MAX_SPAN || maxY - minY + 1 > Blueprint.MAX_SPAN || maxZ - minZ + 1 > Blueprint.MAX_SPAN) {
            throw new IllegalArgumentException("Its regions together are wider than " + Blueprint.MAX_SPAN + " blocks");
        }
        var blocks = new ArrayList<Blueprint.Block>();
        var clear = new ArrayList<Pos>();
        var skipped = new TreeSet<String>();
        int blockEntities = 0, entities = 0;
        for (Region region : list) {
            var palette = Nbt.list(region.tag(), "BlockStatePalette");
            if (palette.isEmpty()) throw new IllegalArgumentException("Region " + region.name() + " has no palette");
            var states = new ArrayList<BlockSpec>(palette.size());
            for (Object entry : palette) {
                @SuppressWarnings("unchecked")
                var state = (Map<String, Object>) entry;
                var properties = new TreeMap<String, String>();
                Nbt.compound(state, "Properties").forEach((k, v) -> properties.put(k, String.valueOf(v)));
                states.add(new BlockSpec(Nbt.string(state, "Name"), properties));
            }
            if (!(region.tag().get("BlockStates") instanceof long[] packed)) {
                throw new IllegalArgumentException("Region " + region.name() + " has no block states");
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(states.size() - 1));
            long cells = (long) region.sx() * region.sy() * region.sz();
            if ((long) packed.length * 64 < cells * bits) {
                throw new IllegalArgumentException("Region " + region.name() + " holds " + (long) packed.length * 64 / bits + " of " + cells + " cells");
            }
            long mask = (1L << bits) - 1;
            for (int i = 0; i < cells; i++) {
                long first = (long) i * bits;
                int start = (int) (first >>> 6), end = (int) ((first + bits - 1) >>> 6), offset = (int) (first & 63);
                long value = start == end ? packed[start] >>> offset & mask
                        : (packed[start] >>> offset | packed[end] << (64 - offset)) & mask;
                if (value >= states.size()) throw new IllegalArgumentException("A block not in the palette: " + value);
                int y = (int) (i / (region.sx() * region.sz())), rest = (int) (i % (region.sx() * region.sz()));
                int z = rest / region.sx(), x = rest % region.sx();
                add(states.get((int) value), new Pos(region.x() + x - minX, region.y() + y - minY, region.z() + z - minZ), blocks, clear, skipped);
            }
            blockEntities += Nbt.list(region.tag(), "TileEntities").size();
            entities += Nbt.list(region.tag(), "Entities").size();
        }
        if (list.size() > 1) notes.add(list.size() + " regions read together");
        if (blockEntities > 0) notes.add(blockEntities + " block entities placed empty: their contents are not copied");
        if (entities > 0) notes.add(entities + " entities left out");
        skipped.forEach(id -> notes.add("Left out: " + id));
        var metadata = Nbt.compound(root, "Metadata");
        String author = Nbt.string(metadata, "Author");
        String title = Nbt.string(metadata, "Name");
        return new Read(new Blueprint(name.isBlank() ? title : name, source, creator.isBlank() ? author : creator, license, blocks, clear),
                Nbt.integer(root, "MinecraftDataVersion", 0), notes);
    }

    private static void add(BlockSpec state, Pos at, List<Blueprint.Block> blocks, List<Pos> clear, Set<String> skipped) {
        if (VOID.contains(state.block())) return;
        if (state.air()) {
            clear.add(at);
        } else if (UNOBTAINABLE.contains(state.block())) {
            skipped.add(state.block());
        } else {
            blocks.add(new Blueprint.Block(at, state));
        }
    }
}
