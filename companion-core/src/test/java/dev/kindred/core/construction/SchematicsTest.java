package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that a building file a user supplies, saved by a structure block or a common
 * building editor, is read with each block's facing, creator and licence kept, that chest contents, creatures
 * and blocks no player could place are left out with a note, and that a broken file is refused with a reason.
 */
class SchematicsTest {
    /** A structure file as a structure block saves one: a palette, blocks by position and state, air saved as air. */
    @Test
    void aStructureFileIsReadWithItsStatesAndItsAirKeptClear() {
        var palette = List.<Object>of(
                state("minecraft:cobblestone", Map.of()),
                state("minecraft:oak_stairs", Map.of("facing", "east", "half", "top", "shape", "straight", "waterlogged", "false")),
                state("minecraft:air", Map.of()),
                state("minecraft:structure_void", Map.of()),
                state("minecraft:chest", Map.of("facing", "north", "type", "single", "waterlogged", "false")),
                state("minecraft:bedrock", Map.of()));
        var blocks = List.<Object>of(
                block(0, 0, 0, 0, null), block(1, 0, 0, 1, null), block(0, 1, 0, 2, null), block(1, 1, 0, 3, null),
                block(2, 0, 0, 4, Map.of("Items", List.of(Map.of("id", "minecraft:diamond", "count", 64)))), block(2, 1, 0, 5, null));
        var root = new LinkedHashMap<String, Object>();
        root.put("DataVersion", 3955);
        root.put("size", List.of(3, 2, 1));
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", List.of(Map.of("blockPos", List.of(0, 0, 0))));
        var read = Schematics.read(gzip(compound(root)), "kiosk", "user:kiosk.nbt", "Ada", "CC-BY-4.0");
        var at = Blueprint.byPosition(read.blueprint().blocks());
        assertEquals(3955, read.dataVersion());
        assertEquals(BlockSpec.of("minecraft:cobblestone"), at.get(new Pos(0, 0, 0)));
        assertEquals("east", at.get(new Pos(1, 0, 0)).property("facing"));
        assertEquals("top", at.get(new Pos(1, 0, 0)).property("half"));
        assertEquals(List.of(new Pos(0, 1, 0)), read.blueprint().clear(), "air kept clear; structure void neither");
        assertNull(at.get(new Pos(1, 1, 0)));
        assertEquals("minecraft:chest", at.get(new Pos(2, 0, 0)).block(), "the chest is placed");
        assertNull(at.get(new Pos(2, 1, 0)), "bedrock is no block a player can place");
        assertTrue(read.notes().contains("1 block entities placed empty: their contents are not copied"), read.notes().toString());
        assertTrue(read.notes().contains("Left out: minecraft:bedrock"), read.notes().toString());
        assertTrue(read.notes().contains("1 entities left out"), read.notes().toString());
        assertEquals("Ada", read.blueprint().creator());
        assertEquals("CC-BY-4.0", read.blueprint().license());
        assertEquals("user:kiosk.nbt", read.blueprint().source());
    }

    /** A Sponge schematic, version 2 (WorldEdit 7.0 to 7.2): the palette by name, the blocks as varints, x fastest. */
    @Test
    void aSpongeSchematicVersionTwoIsRead() {
        var root = sponge(2);
        // Version 2 is the schematic itself at the root (the root tag is named "Schematic").
        var read = Schematics.read(gzip(compound(root)), "", "user:gate.schem", "", "");
        check(read);
        assertEquals("Gate", read.blueprint().name(), "the schematic's own name when none is given");
        assertEquals("Bram", read.blueprint().creator(), "the schematic's own author");
    }

    /** Version 3 (WorldEdit 7.3): the blocks inside a container of their own, under a root without a name. */
    @Test
    void aSpongeSchematicVersionThreeIsRead() {
        var root = sponge(3);
        var read = Schematics.read(gzip(compound(Map.of("Schematic", root))), "gate", "user:gate.schem", "Cy", "MIT");
        check(read);
        assertEquals("Cy", read.blueprint().creator(), "a creator named on import wins");
    }

    @Test
    void whatIsNotASchematicOrIsBrokenIsRefusedWithAReason() {
        assertThrows(IllegalArgumentException.class, () -> Schematics.read(new byte[] {1, 2, 3}, "x", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> Schematics.read(gzip(compound(Map.of("Hello", 1))), "x", "", "", ""));
        var tooWide = sponge(2);
        tooWide.put("Width", (short) 200);
        assertThrows(IllegalArgumentException.class, () -> Schematics.read(gzip(compound(Map.of("Schematic", tooWide))), "x", "", "", ""));
        var shortData = sponge(2);
        shortData.put("BlockData", new byte[] {0, 1});
        var failure = assertThrows(IllegalArgumentException.class, () -> Schematics.read(gzip(compound(Map.of("Schematic", shortData))), "x", "", "", ""));
        assertTrue(failure.getMessage().contains("of 8 cells"), failure.getMessage());
        // A list claiming more than the file could hold is refused before anything is allocated for it.
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeByte(10); out.writeUTF(""); out.writeByte(11); out.writeUTF("big"); out.writeInt(Integer.MAX_VALUE);
        } catch (IOException e) { throw new RuntimeException(e); }
        assertThrows(IllegalArgumentException.class, () -> Nbt.read(bytes.toByteArray()));
    }

    /** A 2x2x2 gate: a log, stairs facing west, glass and air, written as a schematic of either version. */
    private static Map<String, Object> sponge(int version) {
        var palette = new LinkedHashMap<String, Object>();
        palette.put("minecraft:air", 0);
        palette.put("minecraft:oak_log[axis=x]", 1);
        palette.put("minecraft:stone_brick_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]", 2);
        palette.put("minecraft:glass", 3);
        // Cell by cell, x fastest, then z, then y: a log, stairs, glass, air; then air, air, glass (its index written as
        // a varint two bytes long), air.
        byte[] data = {1, 2, 3, 0, 0, 0, (byte) 0x83, 0x00, 0};
        var root = new LinkedHashMap<String, Object>();
        root.put("Version", version);
        root.put("DataVersion", 3955);
        root.put("Width", (short) 2);
        root.put("Height", (short) 2);
        root.put("Length", (short) 2);
        root.put("Metadata", Map.of("Name", "Gate", "Author", "Bram"));
        if (version == 3) {
            root.put("Blocks", Map.of("Palette", palette, "Data", data, "BlockEntities", List.of()));
        } else {
            root.put("Palette", palette);
            root.put("PaletteMax", 4);
            root.put("BlockData", data);
        }
        return root;
    }

    /** The gate as Litematica saves it (version 6): one region, its palette by name and properties, two bits a block. */
    @Test
    void aLitematicaSchematicIsRead() {
        var palette = List.<Object>of(state("minecraft:air", Map.of()), state("minecraft:oak_log", Map.of("axis", "x")),
                state("minecraft:stone_brick_stairs", Map.of("facing", "west", "half", "bottom", "shape", "straight", "waterlogged", "false")),
                state("minecraft:glass", Map.of()));
        var region = litematicRegion(0, 0, 0, 2, 2, 2, palette, new int[] {1, 2, 3, 0, 0, 0, 3, 0});
        region.put("TileEntities", List.of());
        var read = Schematics.read(gzip(compound(litematic(6, Map.of("Gate", region)))), "", "user:gate.litematic", "", "");
        check(read);
        assertEquals("Gate", read.blueprint().name(), "the schematic's own name when none is given");
        assertEquals("Bram", read.blueprint().creator(), "the schematic's own author");
        assertEquals("user:gate.litematic", read.blueprint().source());
    }

    /**
     * Five bits a block (a palette of twenty), so the thirteenth block's index starts in the first long and ends in the
     * second; and a second region selected backwards (negative sizes), placed by its far corner.
     */
    @Test
    void litematicaIndicesSpanLongsAndRegionsSelectedBackwardsArePlaced() {
        String[] colours = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
                "purple", "blue", "brown", "green", "red", "black"};
        var palette = new ArrayList<Object>();
        palette.add(state("minecraft:air", Map.of()));
        for (String colour : colours) palette.add(state("minecraft:" + colour + "_wool", Map.of()));
        for (String colour : List.of("white", "orange", "magenta")) palette.add(state("minecraft:" + colour + "_concrete", Map.of()));
        assertEquals(20, palette.size());
        // Every block of the palette once, x fastest; the thirteenth (bits 60 to 64) is index 18, whose top bit is in
        // the second long, swapped with the eighteenth.
        int[] row = new int[19];
        for (int x = 0; x < row.length; x++) row[x] = x + 1;
        row[12] = 18;
        row[17] = 13;
        var wool = litematicRegion(0, 0, 0, 19, 1, 1, palette, row);
        // Selected from (5, 2, 3) back to (4, 1, 3): a 2x2x1 box whose corner is (4, 1, 3); stone everywhere but air at its top west.
        var stonePalette = List.<Object>of(state("minecraft:air", Map.of()), state("minecraft:stone", Map.of()));
        var stone = litematicRegion(5, 2, 3, -2, -2, 1, stonePalette, new int[] {1, 1, 0, 1});
        var regions = new LinkedHashMap<String, Object>();
        regions.put("Wool", wool);
        regions.put("Stone", stone);
        var read = Schematics.read(gzip(compound(litematic(7, regions))), "row", "", "", "");
        var at = Blueprint.byPosition(read.blueprint().blocks());
        for (int x = 0; x < row.length; x++) {
            @SuppressWarnings("unchecked")
            var expected = (String) ((Map<String, Object>) palette.get(row[x])).get("Name");
            assertEquals(BlockSpec.of(expected), at.get(new Pos(x, 0, 0)), "block " + x);
        }
        assertEquals(BlockSpec.of("minecraft:orange_concrete"), at.get(new Pos(12, 0, 0)), "the index spanning two longs");
        assertEquals(BlockSpec.of("minecraft:stone"), at.get(new Pos(4, 1, 3)));
        assertEquals(BlockSpec.of("minecraft:stone"), at.get(new Pos(5, 1, 3)));
        assertEquals(BlockSpec.of("minecraft:stone"), at.get(new Pos(5, 2, 3)));
        assertEquals(List.of(new Pos(4, 2, 3)), read.blueprint().clear());
        assertEquals(22, read.blueprint().blocks().size());
        assertTrue(read.notes().contains("2 regions read together"), read.notes().toString());
    }

    @Test
    void aBrokenOrUnknownLitematicaSchematicIsRefusedWithAReason() {
        var palette = List.<Object>of(state("minecraft:air", Map.of()), state("minecraft:glass", Map.of()));
        var region = litematicRegion(0, 0, 0, 2, 2, 2, palette, new int[8]);
        var old = assertThrows(IllegalArgumentException.class,
                () -> Schematics.read(gzip(compound(litematic(3, Map.of("A", region)))), "x", "", "", ""));
        assertTrue(old.getMessage().contains("version 3"), old.getMessage());
        var tooFew = litematicRegion(0, 0, 0, 40, 40, 1, palette, new int[8]);
        var shortStates = assertThrows(IllegalArgumentException.class,
                () -> Schematics.read(gzip(compound(litematic(6, Map.of("A", tooFew)))), "x", "", "", ""));
        assertTrue(shortStates.getMessage().contains("of 1600 cells"), shortStates.getMessage());
        var outOfPalette = litematicRegion(0, 0, 0, 2, 1, 1, palette, new int[] {1, 3});
        assertThrows(IllegalArgumentException.class,
                () -> Schematics.read(gzip(compound(litematic(6, Map.of("A", outOfPalette)))), "x", "", "", ""));
        var far = litematicRegion(90, 0, 0, 10, 1, 1, palette, new int[10]);
        var regions = new LinkedHashMap<String, Object>();
        regions.put("A", region);
        regions.put("B", far);
        assertThrows(IllegalArgumentException.class, () -> Schematics.read(gzip(compound(litematic(6, regions))), "x", "", "", ""));
    }

    private static Map<String, Object> litematic(int version, Map<String, Object> regions) {
        var root = new LinkedHashMap<String, Object>();
        root.put("Version", version);
        root.put("SubVersion", 1);
        root.put("MinecraftDataVersion", 3955);
        root.put("Metadata", Map.of("Name", "Gate", "Author", "Bram"));
        root.put("Regions", regions);
        return root;
    }

    /** A region with its indices packed as Litematica packs them, bit by bit: x fastest, then z, then y, spanning longs. */
    private static Map<String, Object> litematicRegion(int x, int y, int z, int sx, int sy, int sz, List<Object> palette, int[] indices) {
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] packed = new long[(int) (((long) indices.length * bits + 63) / 64)];
        for (int i = 0; i < indices.length; i++) {
            for (int b = 0; b < bits; b++) {
                if ((indices[i] >> b & 1) != 0) {
                    int bit = i * bits + b;
                    packed[bit / 64] |= 1L << (bit % 64);
                }
            }
        }
        var region = new LinkedHashMap<String, Object>();
        region.put("Position", Map.of("x", x, "y", y, "z", z));
        region.put("Size", Map.of("x", sx, "y", sy, "z", sz));
        region.put("BlockStatePalette", palette);
        region.put("BlockStates", packed);
        return region;
    }

    private static void check(Schematics.Read read) {
        var at = Blueprint.byPosition(read.blueprint().blocks());
        assertEquals("x", at.get(new Pos(0, 0, 0)).property("axis"));
        assertEquals("west", at.get(new Pos(1, 0, 0)).property("facing"));
        assertEquals(BlockSpec.of("minecraft:glass"), at.get(new Pos(0, 0, 1)));
        assertEquals(BlockSpec.of("minecraft:glass"), at.get(new Pos(0, 1, 1)), "a varint two bytes long");
        assertEquals(4, read.blueprint().blocks().size());
        assertEquals(4, read.blueprint().clear().size());
        assertEquals(3955, read.dataVersion());
    }

    private static Map<String, Object> state(String name, Map<String, String> properties) {
        var state = new LinkedHashMap<String, Object>();
        state.put("Name", name);
        if (!properties.isEmpty()) state.put("Properties", new LinkedHashMap<String, Object>(properties));
        return state;
    }

    private static Map<String, Object> block(int x, int y, int z, int state, Map<String, Object> nbt) {
        var block = new LinkedHashMap<String, Object>();
        block.put("pos", List.of(x, y, z));
        block.put("state", state);
        if (nbt != null) block.put("nbt", nbt);
        return block;
    }

    // A small NBT writer for the tests: compounds from maps, lists from lists, and the number types as boxed.

    private static byte[] compound(Map<String, Object> root) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            payload(out, root);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bytes.toByteArray();
    }

    private static byte[] gzip(byte[] raw) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(bytes)) {
            out.write(raw);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bytes.toByteArray();
    }

    private static int type(Object value) {
        return switch (value) {
            case Byte b -> 1;
            case Short s -> 2;
            case Integer i -> 3;
            case Long l -> 4;
            case byte[] a -> 7;
            case String s -> 8;
            case List<?> l -> 9;
            case Map<?, ?> m -> 10;
            case int[] a -> 11;
            case long[] a -> 12;
            default -> throw new IllegalArgumentException(value.getClass().toString());
        };
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object value) throws IOException {
        switch (value) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case byte[] a -> { out.writeInt(a.length); out.write(a); }
            case String s -> out.writeUTF(s);
            case List<?> l -> {
                out.writeByte(l.isEmpty() ? 0 : type(l.getFirst()));
                out.writeInt(l.size());
                for (Object o : l) payload(out, o);
            }
            case Map<?, ?> m -> {
                for (var e : ((Map<String, Object>) m).entrySet()) {
                    out.writeByte(type(e.getValue()));
                    out.writeUTF(e.getKey());
                    payload(out, e.getValue());
                }
                out.writeByte(0);
            }
            case int[] a -> { out.writeInt(a.length); for (int i : a) out.writeInt(i); }
            case long[] a -> { out.writeInt(a.length); for (long l : a) out.writeLong(l); }
            default -> throw new IllegalArgumentException(value.getClass().toString());
        }
    }
}
