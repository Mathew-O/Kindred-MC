package dev.kindred.sim;

import dev.kindred.core.atlas.Atlas;
import dev.kindred.core.atlas.Priors;
import dev.kindred.core.route.DigTerrain;
import dev.kindred.core.route.Terrain;

import java.util.SplittableRandom;

/**
 * In plain terms: a small made-up world for the simulator: hills of grass over dirt and stone, trees, a few caves, lakes,
 * lava deep down, ore in the stone where the game puts it, sunlight and torchlight, and a clock for day and night.
 *
 * Technical: F-28. Cells in one array over a seeded terrain, 128 blocks square and from y −64 to 127. Ores are placed by
 * the atlas's priors for 1.21.1 ({@link Priors#atHeight}), so the simulator and the atlas agree on where things are. Sky
 * light is by column (open to the sky or not), block light by radius from torches and lava; light is never propagated.
 * No Minecraft code: what a block does is what the simulator's skills say, and what the route planners ask of a cell
 * ({@link Terrain}), and what the core's dig decisions ask ({@link DigTerrain}), is answered as the server adapter answers it
 * for the matching vanilla block (F-35d, F-35e).
 */
public final class SimWorld implements DigTerrain {
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
    public final long seed;
    /** Game ticks since the world began; the time of day is this past dawn. */
    public long tick;

    private SimWorld(long seed) { this.seed = seed; }

    /** An empty world of air, for a staged scene to fill: nothing generated in it, and the clock at dawn. */
    static SimWorld staged(long seed) { return new SimWorld(seed); }

    private static int index(int x, int y, int z) { return ((y - MIN_Y) * SIZE + z) * SIZE + x; }

    public boolean inside(int x, int y, int z) { return x >= 0 && z >= 0 && x < SIZE && z < SIZE && y >= MIN_Y && y < MIN_Y + HEIGHT; }

    public Block at(int x, int y, int z) { return inside(x, y, z) ? Block.ALL[cells[index(x, y, z)]] : y < MIN_Y ? Block.BEDROCK : Block.AIR; }

    public void set(int x, int y, int z, Block block) {
        if (!inside(x, y, z)) return;
        cells[index(x, y, z)] = (byte) block.ordinal();
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

    /** Whether a cell has an open cell beside it (above, below or on a side), so a player could see its face. */
    public boolean exposed(int x, int y, int z) {
        return at(x + 1, y, z).open() || at(x - 1, y, z).open() || at(x, y + 1, z).open() || at(x, y - 1, z).open()
                || at(x, y, z + 1).open() || at(x, y, z - 1).open();
    }

    /** A world from its seed: terrain, lakes, caves, lava, ores by the priors, trees. */
    public static SimWorld generate(long seed, Priors priors) {
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
        // Ores, in veins, where the priors weigh their heights.
        place(world, random, priors, Atlas.Resource.COAL, Block.COAL_ORE, 260, 8);
        place(world, random, priors, Atlas.Resource.IRON, Block.IRON_ORE, 200, 6);
        place(world, random, priors, Atlas.Resource.DIAMOND, Block.DIAMOND_ORE, 30, 4);
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

    /** Veins of {@code ore}, {@code veins} tries over the world, each kept with the priors' weight at its height. */
    private static void place(SimWorld world, SplittableRandom random, Priors priors, Atlas.Resource resource, Block ore, int veins, int size) {
        double most = 0;
        for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) most = Math.max(most, priors.atHeight(resource, "overworld", y));
        if (most <= 0) return;
        for (int vein = 0; vein < veins * 3; vein++) {
            int x = random.nextInt(SIZE), y = random.nextInt(MIN_Y + 1, 120), z = random.nextInt(SIZE);
            if (random.nextDouble() * most > priors.atHeight(resource, "overworld", y)) continue;
            for (int i = 0; i < size; i++) {
                int px = x + random.nextInt(-1, 2), py = y + random.nextInt(-1, 2), pz = z + random.nextInt(-1, 2);
                if (world.at(px, py, pz) == Block.STONE) world.set(px, py, pz, ore);
            }
        }
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
