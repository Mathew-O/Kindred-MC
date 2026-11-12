package dev.kindred.sim;

import dev.kindred.core.route.Cell;
import dev.kindred.core.route.DigTerrain;
import dev.kindred.core.route.Sight;
import dev.kindred.core.route.SightWorld;
import dev.kindred.core.route.Terrain;

import java.util.SplittableRandom;

/**
 * In plain terms: a small made-up world for the simulator: hills of grass over dirt and stone, trees, a few caves, lakes,
 * lava deep down, ore in the stone where the game puts it, sunlight and torchlight, and a clock for day and night.
 *
 * Technical: F-28. Cells in one array over a seeded terrain, 128 blocks square (eight chunks by eight) and from y −64 to
 * 127. Coal, iron and diamond ore are placed as vanilla 1.21.1's world generation places them, chunk by chunk (F-35f: see
 * {@link #ORES}); the atlas's priors, which are a player's knowledge, are not the world's. Sky light is by column (open to
 * the sky or not), block light by radius from torches and lava; light is never propagated. No Minecraft code: what a
 * block does is what the simulator's skills say, and what the route planners ask of a cell ({@link Terrain}), what the
 * core's dig decisions ask ({@link DigTerrain}) and what the core's perception asks ({@link SightWorld}), is answered as
 * the server adapter answers it for the matching vanilla block (F-35d, F-35e, F-35f).
 */
public final class SimWorld implements DigTerrain, SightWorld {
    public static final int SIZE = 128, MIN_Y = -64, HEIGHT = 192, SEA = 62;

    public enum Block {
        AIR, STONE, DIRT, GRASS, LOG, LEAVES, WATER, LAVA, COAL_ORE, IRON_ORE, DIAMOND_ORE, GRAVEL, TORCH, PLANKS, COBBLE,
        BEDROCK, OBSIDIAN, TABLE, FURNACE, SAND;

        static final Block[] ALL = values();

        /** Whether a body can stand in it and see through it. */
        public boolean open() { return this == AIR || this == TORCH; }

        /** Whether it is solid enough to stand on. */
        public boolean solid() { return !open() && this != WATER && this != LAVA && this != LEAVES; }
    }

    private final byte[] cells = new byte[SIZE * SIZE * HEIGHT];
    private final int[] top = new int[SIZE * SIZE];
    /** Each 16-cube section's palette: a bit for each block ever set in it, as a chunk section's palette keeps a state once used. */
    private final int[] palette = new int[(SIZE >> 4) * (SIZE >> 4) * (HEIGHT >> 4)];
    public final long seed;
    /** Game ticks since the world began; the time of day is this past dawn. */
    public long tick;

    private SimWorld(long seed) { this.seed = seed; }

    /** An empty world of air, for a staged scene to fill: nothing generated in it, and the clock at dawn. */
    static SimWorld staged(long seed) { return new SimWorld(seed); }

    private static int index(int x, int y, int z) { return ((y - MIN_Y) * SIZE + z) * SIZE + x; }

    private static int section(int sx, int sy, int sz) { return (sy * (SIZE >> 4) + sz) * (SIZE >> 4) + sx; }

    public boolean inside(int x, int y, int z) { return x >= 0 && z >= 0 && x < SIZE && z < SIZE && y >= MIN_Y && y < MIN_Y + HEIGHT; }

    public Block at(int x, int y, int z) { return inside(x, y, z) ? Block.ALL[cells[index(x, y, z)]] : y < MIN_Y ? Block.BEDROCK : Block.AIR; }

    public void set(int x, int y, int z, Block block) {
        if (!inside(x, y, z)) return;
        cells[index(x, y, z)] = (byte) block.ordinal();
        if (block != Block.AIR) palette[section(x >> 4, (y - MIN_Y) >> 4, z >> 4)] |= 1 << block.ordinal();
        int column = z * SIZE + x;
        if (!block.open() && y > top[column]) top[column] = y;
        else if (block.open() && y == top[column]) {
            int t = y;
            while (t > MIN_Y && at(x, t, z).open()) t--;
            top[column] = t;
        }
    }

    /** The highest block in the column that is not open. */
    public int top(int x, int z) { return x < 0 || z < 0 || x >= SIZE || z >= SIZE ? SEA : top[z * SIZE + x]; }

    /** Open to the sky: nothing above it in its column. */
    public boolean skyOpen(int x, int y, int z) { return y > top(x, z); }

    public long timeOfDay() { return tick % 24_000; }

    /** Night, as mobs see it: from dusk to dawn. */
    public boolean night() { long t = timeOfDay(); return t >= 13_000 && t < 23_000; }

    /** Whether the sky's light reaches a cell: nothing above it in its column but leaves, which let it through. */
    public boolean skyLit(int x, int y, int z) {
        if (skyOpen(x, y, z)) return true;
        for (int t = top(x, z); t > y; t--) {
            Block above = at(x, t, z);
            if (!above.open() && above != Block.LEAVES) return false;
        }
        return true;
    }

    /** Light at a cell: the sky's by column (15 by day, 4 at night), or the nearest torch or lava's, whichever is more. */
    public int light(int x, int y, int z) {
        int sky = skyLit(x, y, z) ? (night() ? 4 : 15) : 0;
        int best = sky;
        for (int dx = -7; dx <= 7 && best < 14; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -7; dz <= 7; dz++) {
            Block b = at(x + dx, y + dy, z + dz);
            if (b == Block.TORCH || b == Block.LAVA) {
                int lit = (b == Block.LAVA ? 15 : 14) - Math.abs(dx) - Math.abs(dy) - Math.abs(dz);
                if (lit > best) best = lit;
            }
        }
        return best;
    }

    /** A world from its seed: terrain, lakes, caves, lava, vanilla's ores, trees. */
    public static SimWorld generate(long seed) {
        var world = new SimWorld(seed);
        var random = new SplittableRandom(seed);
        double[] coarse = noise(random.nextLong(), 8), fine = noise(random.nextLong(), 24);
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            int h = (int) Math.round(66 + 9 * sample(coarse, 8, x, z) + 3 * sample(fine, 24, x, z));
            world.set(x, MIN_Y, z, Block.BEDROCK);
            for (int y = MIN_Y + 1; y <= h - 4; y++) world.set(x, y, z, Block.STONE);
            for (int y = Math.max(MIN_Y + 1, h - 3); y < h; y++) world.set(x, y, z, Block.DIRT);
            world.set(x, h, z, h < SEA ? Block.SAND : Block.GRASS);
            for (int y = h + 1; y <= SEA; y++) world.set(x, y, z, Block.WATER);
        }
        // Caves: worms of open cells through the stone, some breaking the surface; forty of them open some three
        // hundredths of the ground below, nearer 1.21's caves than a few would.
        for (int cave = 0; cave < 40; cave++) {
            double x = random.nextInt(SIZE), y = random.nextInt(-50, 50), z = random.nextInt(SIZE);
            double heading = random.nextDouble() * Math.PI * 2, pitch = 0;
            for (int step = 0; step < 120; step++) {
                int r = 1 + random.nextInt(2);
                for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                    int cx = (int) x + dx, cy = (int) y + dy, cz = (int) z + dz;
                    if (dx * dx + dy * dy + dz * dz > r * r + 1 || cy <= MIN_Y + 1) continue;
                    Block was = world.at(cx, cy, cz);
                    if (was == Block.WATER || was == Block.BEDROCK) continue;
                    world.set(cx, cy, cz, cy <= -55 ? Block.LAVA : Block.AIR);
                }
                heading += (random.nextDouble() - 0.5) * 0.6;
                pitch = Math.clamp(pitch + (random.nextDouble() - 0.5) * 0.3, -0.6, 0.6);
                x += Math.cos(heading);
                z += Math.sin(heading);
                y = Math.clamp(y + pitch, -58, 90);
            }
        }
        // Ores, chunk by chunk, as vanilla 1.21.1 places them.
        for (Ore ore : ORES) ore.place(world, random);
        // Gravel in patches, and lava lakes far down.
        for (int patch = 0; patch < 40; patch++) {
            int x = random.nextInt(SIZE), y = random.nextInt(-40, 60), z = random.nextInt(SIZE);
            for (int i = 0; i < 12; i++) {
                int px = x + random.nextInt(-2, 3), py = y + random.nextInt(-1, 2), pz = z + random.nextInt(-2, 3);
                if (world.at(px, py, pz) == Block.STONE) world.set(px, py, pz, Block.GRAVEL);
            }
        }
        for (int lake = 0; lake < 6; lake++) {
            int x = random.nextInt(8, SIZE - 8), z = random.nextInt(8, SIZE - 8), y = -55;
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 16) continue;
                world.set(x + dx, y, z + dz, Block.LAVA);
                for (int dy = 1; dy <= 3; dy++) world.set(x + dx, y + dy, z + dz, Block.AIR);
            }
        }
        // Trees on grass.
        for (int x = 2; x < SIZE - 2; x++) for (int z = 2; z < SIZE - 2; z++) {
            int h = world.top(x, z);
            if (world.at(x, h, z) != Block.GRASS || random.nextInt(55) != 0) continue;
            int trunk = 4 + random.nextInt(3);
            for (int y = 1; y <= trunk; y++) world.set(x, h + y, z, Block.LOG);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = trunk - 1; dy <= trunk + 1; dy++) {
                if (Math.abs(dx) + Math.abs(dz) > 3 || world.at(x + dx, h + dy, z + dz) != Block.AIR) continue;
                world.set(x + dx, h + dy, z + dz, Block.LEAVES);
            }
        }
        return world;
    }

    /**
     * One of vanilla's placed ore features: so many tries a chunk (or one try in {@code rarity} chunks), each at a height
     * from its height provider, a vein of up to {@code size} blocks, of which a block with air beside it is dropped with
     * the chance {@code discardOnAir}. {@code trapezoid} is vanilla's trapezoid provider with no plateau (a triangle over
     * the range, its peak in the middle); otherwise the height is uniform over the range, both ends included.
     */
    record Ore(String name, Block block, int count, int rarity, boolean trapezoid, int minY, int maxY, int size, float discardOnAir) {
        void place(SimWorld world, SplittableRandom random) {
            for (int cx = 0; cx < SIZE >> 4; cx++) for (int cz = 0; cz < SIZE >> 4; cz++) {
                int tries = rarity > 1 ? (random.nextInt(rarity) == 0 ? 1 : 0) : count;
                for (int i = 0; i < tries; i++) {
                    int x = (cx << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
                    int y = trapezoid ? trapezoid(random, minY, maxY) : minY + random.nextInt(maxY - minY + 1);
                    vein(world, random, x, y, z);
                }
            }
        }

        private static int trapezoid(SplittableRandom random, int min, int max) {
            int k = max - min, l = k / 2, m = k - l;
            return min + random.nextInt(m + 1) + random.nextInt(l + 1);
        }

        /**
         * The vein: as vanilla's ore feature lays it, a run of overlapping blobs along a short line through the try's
         * place, set in stone only; its own shape, the counts and heights above vanilla's.
         */
        private void vein(SimWorld world, SplittableRandom random, int x, int y, int z) {
            double angle = random.nextDouble() * Math.PI;
            double reach = size / 8.0;
            double x0 = x + Math.sin(angle) * reach, x1 = x - Math.sin(angle) * reach;
            double z0 = z + Math.cos(angle) * reach, z1 = z - Math.cos(angle) * reach;
            double y0 = y + random.nextInt(3) - 2, y1 = y + random.nextInt(3) - 2;
            for (int i = 0; i < size; i++) {
                double t = (double) i / size;
                double px = x0 + (x1 - x0) * t, py = y0 + (y1 - y0) * t, pz = z0 + (z1 - z0) * t;
                double spread = random.nextDouble() * size / 16.0;
                double radius = ((Math.sin(Math.PI * t) + 1) * spread + 1) / 2.0;
                for (int bx = (int) Math.floor(px - radius); bx <= (int) Math.floor(px + radius); bx++)
                    for (int by = (int) Math.floor(py - radius); by <= (int) Math.floor(py + radius); by++)
                        for (int bz = (int) Math.floor(pz - radius); bz <= (int) Math.floor(pz + radius); bz++) {
                            double dx = (bx + 0.5 - px) / radius, dy = (by + 0.5 - py) / radius, dz = (bz + 0.5 - pz) / radius;
                            if (dx * dx + dy * dy + dz * dz >= 1 || world.at(bx, by, bz) != Block.STONE) continue;
                            if (discardOnAir > 0 && world.touchesAir(bx, by, bz) && random.nextDouble() < discardOnAir) continue;
                            world.set(bx, by, bz, block);
                        }
            }
        }
    }

    /**
     * Vanilla 1.21.1's placed ore features for coal, iron and diamond, read from the game's own {@code OrePlacedFeatures}
     * and {@code OreConfiguredFeatures} in the mapped 1.21.1 jar (F-35f; the counts, rarities, height ranges, vein sizes and
     * air-discard chances as those classes give them, the world's bottom at -64 and its top at 320). The upper coal band
     * starts at 136, over the simulator's world; iron's upper band, a triangle from 80 to 384 that peaks at 232, is the
     * high-mountain band, and lands in stone only on the few hills over 80.
     */
    static final java.util.List<Ore> ORES = java.util.List.of(
            new Ore("ore_coal_upper", Block.COAL_ORE, 30, 1, false, 136, 319, 17, 0f),
            new Ore("ore_coal_lower", Block.COAL_ORE, 20, 1, true, 0, 192, 17, 0.5f),
            new Ore("ore_iron_upper", Block.IRON_ORE, 90, 1, true, 80, 384, 9, 0f),
            new Ore("ore_iron_middle", Block.IRON_ORE, 10, 1, true, -24, 56, 9, 0f),
            new Ore("ore_iron_small", Block.IRON_ORE, 10, 1, false, -64, 72, 4, 0f),
            new Ore("ore_diamond", Block.DIAMOND_ORE, 7, 1, true, -144, 16, 4, 0.5f),
            new Ore("ore_diamond_medium", Block.DIAMOND_ORE, 2, 1, false, -64, -4, 8, 0.5f),
            new Ore("ore_diamond_large", Block.DIAMOND_ORE, 1, 9, true, -144, 16, 12, 0.7f),
            new Ore("ore_diamond_buried", Block.DIAMOND_ORE, 4, 1, true, -144, 16, 8, 1.0f));

    /** Whether air is beside the block on any side. */
    boolean touchesAir(int x, int y, int z) {
        return at(x + 1, y, z) == Block.AIR || at(x - 1, y, z) == Block.AIR || at(x, y + 1, z) == Block.AIR || at(x, y - 1, z) == Block.AIR
                || at(x, y, z + 1) == Block.AIR || at(x, y, z - 1) == Block.AIR;
    }

    // ------------------------------------------------------------------ the route planners' terrain (F-35d)
    //
    // Each fact as the adapter's WorldTerrain reads it from the matching vanilla block's state: its collision shape, its
    // fluid, whether a placed block replaces it, and its hardness. Every simulator block but air, the torch, water and
    // lava is a full cube to collide with (leaves too: the adapter reads their full collision box, so a route walks on a
    // tree's crown, though the simulator's own Block.solid() keeps mobs and its skills off it).

    /** Inside the 128 square and the height range: the whole simulated world is loaded, and nothing outside it is. */
    @Override
    public boolean loaded(int x, int y, int z) { return inside(x, y, z); }

    @Override
    public Fluid fluid(int x, int y, int z) {
        if (!inside(x, y, z)) return Fluid.NONE;
        return switch (at(x, y, z)) {
            case WATER -> Fluid.WATER;
            case LAVA -> Fluid.LAVA;
            default -> Fluid.NONE;
        };
    }

    /** Of the adapter's hazards (lava, fire, cactus, cobweb, magma and the rest), the simulator has only lava. */
    @Override
    public boolean harms(int x, int y, int z) { return at(x, y, z) == Block.LAVA; }

    /** No collision: air and the torch, and water and lava, whose blocks have no collision box (lava is refused as a hazard). */
    @Override
    public boolean passThrough(int x, int y, int z) { return !cube(at(x, y, z)); }

    /** A full cube's top, 1.0 high: every block with a collision box, the leaves included. */
    @Override
    public boolean supports(int x, int y, int z) { return cube(at(x, y, z)); }

    /** As {@link #supports}: the simulator has no fence or wall with a taller top. */
    @Override
    public boolean bears(int x, int y, int z) { return cube(at(x, y, z)); }

    /** Magma or fire: none in the simulator. */
    @Override
    public boolean scorches(int x, int y, int z) { return false; }

    /** A bottom slab or stair: none in the simulator. */
    @Override
    public boolean bottomHalf(int x, int y, int z) { return false; }

    /** A portal's own block: none in the simulator, whose lit portal is a flag on the player. */
    @Override
    public boolean portal(int x, int y, int z) { return false; }

    /** Air, water and lava, as vanilla's {@code isReplaceable}; a torch is not. */
    @Override
    public boolean replaceable(int x, int y, int z) {
        Block b = at(x, y, z);
        return b == Block.AIR || b == Block.WATER || b == Block.LAVA;
    }

    /** Vanilla 1.21.1's hardness for the matching block; bedrock's -1, and outside the world, as the void's air, 0. */
    @Override
    public float hardness(int x, int y, int z) {
        if (!inside(x, y, z)) return 0;
        return switch (at(x, y, z)) {
            case AIR, TORCH -> 0;
            case STONE -> 1.5f;
            case DIRT, SAND -> 0.5f;
            case GRASS, GRAVEL -> 0.6f;
            case LOG, PLANKS, COBBLE -> 2;
            case LEAVES -> 0.2f;
            case WATER, LAVA -> 100;
            case COAL_ORE, IRON_ORE, DIAMOND_ORE -> 3;
            case BEDROCK -> -1;
            case OBSIDIAN -> 50;
            case TABLE -> 2.5f;
            case FURNACE -> 3.5f;
        };
    }

    /** As the adapter's {@code NativeStairAction.solid}: loaded, not replaceable, no fluid, and a collision box. */
    @Override
    public boolean placeAgainst(int x, int y, int z) { return inside(x, y, z) && cube(at(x, y, z)); }

    /** No collision box: air, the torch, water and lava, as {@link #passThrough}: the simulator has no carpet or door. */
    @Override
    public boolean empty(int x, int y, int z) { return !cube(at(x, y, z)); }

    /** Vanilla's falling blocks among the simulator's: gravel and sand (though in the simulator they never fall). */
    @Override
    public boolean falls(int x, int y, int z) {
        Block b = at(x, y, z);
        return b == Block.GRAVEL || b == Block.SAND;
    }

    /** As vanilla's {@code FallingBlock.canFallThrough}: air, a liquid, or a replaceable block; a torch is none of them. */
    @Override
    public boolean fallsThrough(int x, int y, int z) {
        Block b = at(x, y, z);
        return b == Block.AIR || b == Block.WATER || b == Block.LAVA;
    }

    /** Nothing falls in the simulator: no falling block is ever in flight. */
    @Override
    public boolean falling(int x0, int y0, int z0, int x1, int y1, int z1) { return false; }

    // ------------------------------------------------------------------ the perception's world (F-35f)
    //
    // Each fact as the adapter's WorldKnowledge reads it from the matching vanilla block: its kind as the sweep classifies
    // it (cobblestone counts as stone, all water is a still source), air, its fluid, whether it is an opaque full cube
    // (leaves are not), the sky's light, the heightmap's top, and a sight line through the blocks' collision shapes.

    /** The square's columns: every chunk of the simulated world is loaded, and none outside it. */
    @Override
    public boolean inLoadedChunk(int x, int y, int z) { return x >= 0 && z >= 0 && x < SIZE && z < SIZE; }

    @Override
    public boolean columnLoaded(int chunkX, int chunkZ) { return chunkX >= 0 && chunkZ >= 0 && chunkX < SIZE >> 4 && chunkZ < SIZE >> 4; }

    @Override
    public boolean sectionLoaded(int sx, int sy, int sz) {
        return columnLoaded(sx, sz) && sy >= MIN_Y >> 4 && sy < (MIN_Y + HEIGHT) >> 4;
    }

    /** Its palette holds a block of a kind the sweep keeps. */
    @Override
    public boolean sectionOfInterest(int sx, int sy, int sz) {
        int bits = palette[section(sx, sy - (MIN_Y >> 4), sz)];
        for (Block b : Block.ALL) if ((bits & 1 << b.ordinal()) != 0 && sightKind(b) != null) return true;
        return false;
    }

    @Override
    public Sight.Kind kind(int x, int y, int z) { return sightKind(at(x, y, z)); }

    /** As the adapter's {@code WorldKnowledge.classify} for the matching vanilla block. */
    static Sight.Kind sightKind(Block b) {
        return switch (b) {
            case LOG -> Sight.Kind.LOG;
            case LEAVES -> Sight.Kind.LEAVES;
            case STONE, COBBLE -> Sight.Kind.STONE;
            case COAL_ORE -> Sight.Kind.COAL_ORE;
            case IRON_ORE -> Sight.Kind.IRON_ORE;
            case DIAMOND_ORE -> Sight.Kind.DIAMOND_ORE;
            case GRAVEL -> Sight.Kind.GRAVEL;
            case TABLE -> Sight.Kind.CRAFTING_TABLE;
            case FURNACE -> Sight.Kind.FURNACE;
            case OBSIDIAN -> Sight.Kind.OBSIDIAN;
            case WATER -> Sight.Kind.WATER;
            case LAVA -> Sight.Kind.LAVA;
            default -> null;
        };
    }

    @Override
    public boolean air(int x, int y, int z) { return at(x, y, z) == Block.AIR; }

    @Override
    public boolean fluidEmpty(int x, int y, int z) { Block b = at(x, y, z); return b != Block.WATER && b != Block.LAVA; }

    @Override
    public boolean water(int x, int y, int z) { return at(x, y, z) == Block.WATER; }

    /** Every full cube but leaves, which vanilla draws see-through. */
    @Override
    public boolean opaqueFullCube(int x, int y, int z) { Block b = at(x, y, z); return cube(b) && b != Block.LEAVES; }

    @Override
    public boolean log(int x, int y, int z) { return at(x, y, z) == Block.LOG; }

    @Override
    public boolean leaves(int x, int y, int z) { return at(x, y, z) == Block.LEAVES; }

    @Override
    public boolean collisionEmpty(int x, int y, int z) { return !cube(at(x, y, z)); }

    /** Planks are the simulator's only built block. */
    @Override
    public boolean built(int x, int y, int z) { return at(x, y, z) == Block.PLANKS; }

    @Override
    public int seaLevel() { return SEA; }

    /** Fifteen open to the sky, fourteen under leaves only, none under anything else: the simulator does not spread light. */
    @Override
    public int skyLight(int x, int y, int z) { return skyOpen(x, y, z) ? 15 : skyLit(x, y, z) ? 14 : 0; }

    /** Over the column's highest block that is not open: leaves and fluids block motion, a torch does not. */
    @Override
    public int motionTop(int x, int z) { return top(x, z) + 1; }

    /** A source's height: eight ninths, or the whole block with the same fluid over it. */
    @Override
    public float fluidHeight(int x, int y, int z) {
        Block b = at(x, y, z);
        if (b != Block.WATER && b != Block.LAVA) return 0;
        return at(x, y + 1, z) == b ? 1.0f : 8 / 9.0f;
    }

    /**
     * The line's blocks in order, from the one it starts in, as the game's ray cast walks them: the first with a collision
     * box (a full cube: every one in the simulator has one), or with {@code fluids} a fluid whose surface the line passes
     * under, is the one it meets.
     */
    @Override
    public Cell ray(double ax, double ay, double az, double bx, double by, double bz, boolean fluids) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int x = (int) Math.floor(ax), y = (int) Math.floor(ay), z = (int) Math.floor(az);
        int ex = (int) Math.floor(bx), ey = (int) Math.floor(by), ez = (int) Math.floor(bz);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tx = sx == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - ax : ax - x) / Math.abs(dx));
        double ty = sy == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - ay : ay - y) / Math.abs(dy));
        double tz = sz == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - az : az - z) / Math.abs(dz));
        double stepX = sx == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx), stepY = sy == 0 ? Double.MAX_VALUE : 1 / Math.abs(dy),
                stepZ = sz == 0 ? Double.MAX_VALUE : 1 / Math.abs(dz);
        double t = 0;
        for (int guard = 0; guard < 1024; guard++) {
            if (meets(x, y, z, ax, ay, az, dx, dy, dz, t, Math.min(1, Math.min(tx, Math.min(ty, tz))), fluids)) return new Cell(x, y, z);
            if (x == ex && y == ey && z == ez) return null;
            if (tx <= ty && tx <= tz) { t = tx; x += sx; tx += stepX; }
            else if (ty <= tz) { t = ty; y += sy; ty += stepY; }
            else { t = tz; z += sz; tz += stepZ; }
            if (t > 1) return null;
        }
        return null;
    }

    /** Whether the line, between {@code t0} and {@code t1} of the way along, meets the cell's shape. */
    private boolean meets(int x, int y, int z, double ax, double ay, double az, double dx, double dy, double dz, double t0, double t1, boolean fluids) {
        Block b = at(x, y, z);
        if (cube(b)) return true;
        if (!fluids || b != Block.WATER && b != Block.LAVA) return false;
        double surface = y + fluidHeight(x, y, z);
        // The fluid fills the cell up to its surface: met if the line is under it anywhere in the cell.
        return Math.min(ay + dy * t0, ay + dy * t1) < surface;
    }

    /** Whether the block has a collision box, a full cube's: all but air, the torch, water and lava. */
    private static boolean cube(Block b) {
        return b != Block.AIR && b != Block.TORCH && b != Block.WATER && b != Block.LAVA;
    }

    private static double[] noise(long seed, int cells) {
        var random = new SplittableRandom(seed);
        double[] grid = new double[(cells + 1) * (cells + 1)];
        for (int i = 0; i < grid.length; i++) grid[i] = random.nextDouble() * 2 - 1;
        return grid;
    }

    private static double sample(double[] grid, int cells, int x, int z) {
        double gx = x * (double) cells / SIZE, gz = z * (double) cells / SIZE;
        int x0 = (int) gx, z0 = (int) gz;
        double fx = smooth(gx - x0), fz = smooth(gz - z0);
        int w = cells + 1;
        double a = grid[z0 * w + x0], b = grid[z0 * w + x0 + 1], c = grid[(z0 + 1) * w + x0], d = grid[(z0 + 1) * w + x0 + 1];
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }
}
