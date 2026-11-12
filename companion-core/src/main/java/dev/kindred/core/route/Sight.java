package dev.kindred.core.route;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * In plain terms: what one player has seen of the world near it, and how it comes to see it. A block is remembered only
 * when a face of it is open to the air, within a bounded distance; ores, fluids and the other things a player has to
 * look at are remembered only when a straight line from its eyes reaches them. Cave floors it has seen are remembered as
 * places to explore. Trees far off are seen on the skyline. Nothing it could not have seen is known.
 *
 * Technical: The game's perception (F-35f), ported line for line from the adapter's {@code WorldKnowledge}: its exposure
 * rule, its sight line (with the fluid surface's second look), its cave floor, its incremental sweep of the chunk
 * sections round the player (a bounded number of block reads and sight lines a scan, the cave floors whose sight lines
 * were spent kept for the next scans), its close look, its trees on the skyline and its memory (per kind, keyed as the
 * game keys block positions, capped, stale entries dropped when asked). Also the campaign's {@code trunkFoot}, which
 * decides whether a log is a tree's to be chopped from the ground. Every world read is a {@link SightWorld} fact. Not
 * thread-safe: one player's perception, on one thread.
 */
public final class Sight {
    /** The kinds of block it keeps, in the adapter's order. */
    public enum Kind {
        LOG, STONE, COAL_ORE, IRON_ORE, DIAMOND_ORE, GRAVEL, WATER, LAVA, CRAFTING_TABLE, FURNACE, OBSIDIAN, CAVE, LEAVES,
        NETHER_BRICK, SPAWNER, WARPED, PORTAL, STONE_BRICK, END_PORTAL_FRAME, COBWEB, NETHER_GOLD_ORE, CONTAINER, GOLD_BLOCK
    }

    public static final int HORIZONTAL_RADIUS = 32;
    public static final int VERTICAL_RADIUS = 24;
    /** How far below a log the ground it is chopped from may be: the campaign's reach up a trunk. */
    public static final int TRUNK_REACH = 5;
    private static final int BLOCK_READS_PER_SCAN = 6_000;
    private static final int RAYCASTS_PER_SCAN = 24;
    /** Cave floors passed in a sweep while their sight lines were spent, looked at first in the scans after. */
    private static final int CAVE_BACKLOG = 1024;
    private static final int MAX_PER_KIND = 2_000;

    private final ArrayDeque<Cell> caveBacklog = new ArrayDeque<>();
    private final Map<Kind, Map<Long, Cell>> known = new EnumMap<>(Kind.class);
    /** The columns where it saw still water open to the air, the last {@value #WATER_COLUMNS} (F-35j). */
    private final java.util.LinkedHashSet<Long> waterColumns = new java.util.LinkedHashSet<>();
    public static final int WATER_COLUMNS = 16_384;
    private final List<int[]> sweep = new ArrayList<>();
    private int cursor;
    private int index;
    private int sweeps;
    /** Told of each block of a kind remembered for the first time; none by default. */
    private BiConsumer<Kind, Cell> firstSight;

    public Sight() {
        for (Kind kind : Kind.values()) known.put(kind, new HashMap<>());
    }

    /** Everything forgotten and the sweep begun again: a new dimension. */
    public void reset() {
        for (var map : known.values()) map.clear();
        waterColumns.clear();
        sweep.clear();
        caveBacklog.clear();
    }

    /** A column's key, as the game keys a chunk position: x in the low half, z in the high. */
    private static long column(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }

    /** Whether a kind is remembered only once a sight line reaches it, not on its exposure alone. */
    public static boolean needsSight(Kind kind) {
        return kind == Kind.COAL_ORE || kind == Kind.IRON_ORE || kind == Kind.DIAMOND_ORE || kind == Kind.WATER || kind == Kind.LAVA
                || kind == Kind.CAVE || kind == Kind.SPAWNER || kind == Kind.STONE_BRICK || kind == Kind.END_PORTAL_FRAME
                || kind == Kind.NETHER_BRICK || kind == Kind.COBWEB || kind == Kind.NETHER_GOLD_ORE || kind == Kind.CONTAINER
                || kind == Kind.GOLD_BLOCK;
    }

    /**
     * A place a player could stand in a cave: two blocks of air over a stone floor, out of daylight (sky light at most 7,
     * so overhangs at a cave mouth count and open ground does not), or a ravine floor well below sea level. Ground open to
     * the sky, nothing above it in the heightmap, is never a cave floor, whatever its light says.
     */
    public static boolean caveFloor(SightWorld world, Cell feet) {
        if (!world.air(feet.x(), feet.y(), feet.z()) || !world.air(feet.x(), feet.y() + 1, feet.z())) return false;
        if (world.kind(feet.x(), feet.y() - 1, feet.z()) != Kind.STONE) return false;
        if (feet.y() < world.seaLevel() - 10) return true;
        return world.skyLight(feet.x(), feet.y(), feet.z()) <= 7 && world.motionTop(feet.x(), feet.z()) > feet.y();
    }

    private static long cell(Cell pos) {
        return new Cell(pos.x() >> 2, pos.y() >> 2, pos.z() >> 2).asLong();
    }

    /** Whether the sweep under way has been read to its end, its cave floors' sight lines too: the next scan begins anew. */
    public boolean swept() { return cursor >= sweep.size() && caveBacklog.isEmpty(); }

    /**
     * Advances the incremental sweep round the player by a bounded amount of block reads: its eyes at {@code eye} (x, y,
     * z), its feet's cell {@code feet}.
     */
    public void scan(SightWorld world, double[] eye, Cell feet) {
        // A sweep ends once the cave floors it passed over are looked at too: what was not seen in it is known not there.
        if (cursor >= sweep.size() && caveBacklog.isEmpty()) rebuildSweep(feet);
        int reads = 0;
        int raycasts = 0, caveRaycasts = 0;
        while (!caveBacklog.isEmpty() && caveRaycasts < RAYCASTS_PER_SCAN) {
            Cell floor = caveBacklog.poll();
            if (known.get(Kind.CAVE).containsKey(cell(floor)) || !world.inLoadedChunk(floor.x(), floor.y(), floor.z())) continue;
            caveRaycasts++;
            if (visible(world, eye, floor)) remember(Kind.CAVE, floor);
        }
        while (cursor < sweep.size() && reads < BLOCK_READS_PER_SCAN) {
            int[] section = sweep.get(cursor);
            if (!world.sectionLoaded(section[0], section[1], section[2])) {
                nextSection();
                continue;
            }
            if (!world.sectionOfInterest(section[0], section[1], section[2])) {
                nextSection();
                continue;
            }
            int minX = section[0] << 4, minY = section[1] << 4, minZ = section[2] << 4;
            while (index < 4096 && reads < BLOCK_READS_PER_SCAN) {
                int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
                reads++;
                Kind kind = world.kind(minX + x, minY + y, minZ + z);
                if (kind != null) {
                    Cell pos = new Cell(minX + x, minY + y, minZ + z);
                    // Stone with a cave floor on top: one sighting per cell is enough to know there is a cave there.
                    Cell floor = pos.up();
                    boolean airAbove = world.air(floor.x(), floor.y(), floor.z());
                    // Cave floors have sight lines of their own to spend; those it has none left for are looked at first
                    // in the scans after.
                    if (kind == Kind.STONE && airAbove && !known.get(Kind.CAVE).containsKey(cell(floor)) && caveFloor(world, floor)) {
                        if (caveRaycasts < RAYCASTS_PER_SCAN) {
                            caveRaycasts++;
                            if (visible(world, eye, floor)) remember(Kind.CAVE, floor);
                        } else if (caveBacklog.size() < CAVE_BACKLOG) {
                            caveBacklog.add(floor);
                        }
                    }
                    if (exposed(world, pos, kind)) {
                        if (needsSight(kind)) {
                            if (raycasts >= RAYCASTS_PER_SCAN) return; // Resume at this exact block next scan.
                            raycasts++;
                            if (visible(world, eye, pos)) remember(kind, pos);
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
    public void look(SightWorld world, double[] eye, Cell center, int radius, int height, Kind kind) {
        for (int z = center.z() - radius; z <= center.z() + radius; z++) {
            for (int y = center.y() - height; y <= center.y() + height; y++) {
                for (int x = center.x() - radius; x <= center.x() + radius; x++) {
                    Cell pos = new Cell(x, y, z);
                    if (!world.inLoadedChunk(x, y, z) || world.kind(x, y, z) != kind || !exposed(world, pos, kind)) continue;
                    if (!needsSight(kind) || visible(world, eye, pos)) remember(kind, pos);
                }
            }
        }
    }

    /**
     * Trees on the skyline: the nearest crown of leaves or top of a trunk the player can see from where it stands, over
     * loaded ground out to {@code radius} blocks, past its sweep's {@value #HORIZONTAL_RADIUS}, and a log of that tree,
     * which is remembered. Bounded: every third column, and one sight line a top for the eight nearest.
     */
    public Optional<Cell> treeInView(SightWorld world, double[] eye, Cell feet, int radius, Predicate<Cell> usable) {
        var tops = new ArrayList<Cell>();
        for (int dx = -radius; dx <= radius; dx += 3) {
            for (int dz = -radius; dz <= radius; dz += 3) {
                int square = dx * dx + dz * dz;
                if (square > radius * radius || square <= HORIZONTAL_RADIUS * HORIZONTAL_RADIUS) continue;
                int x = feet.x() + dx, z = feet.z() + dz;
                if (!world.columnLoaded(x >> 4, z >> 4)) continue;
                Cell top = new Cell(x, world.motionTop(x, z) - 1, z);
                if (world.leaves(top.x(), top.y(), top.z()) || world.log(top.x(), top.y(), top.z())) tops.add(top);
            }
        }
        tops.sort(Comparator.comparingDouble(p -> p.squaredDistance(feet)));
        for (int i = 0; i < Math.min(8, tops.size()); i++) {
            Cell top = tops.get(i);
            // Never across ground that is not loaded, which is not there to see and which a sight line would load (F-35m).
            if (!loadedAlong(world, eye[0], eye[2], top.x() + 0.5, top.z() + 0.5)) continue;
            // Seen if the sight line meets the tree itself, a leaf or log of its crown short of the top block.
            Cell hit = world.ray(eye[0], eye[1], eye[2], top.x() + 0.5, top.y() + 0.5, top.z() + 0.5, false);
            if (hit != null && !(manhattan(hit, top) <= 3
                    && (world.leaves(hit.x(), hit.y(), hit.z()) || world.log(hit.x(), hit.y(), hit.z())))) continue;
            Optional<Cell> log = logUnder(world, top);
            if (log.isPresent() && usable.test(log.get())) {
                remember(Kind.LOG, log.get());
                return log;
            }
        }
        return Optional.empty();
    }

    private static int manhattan(Cell a, Cell b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    /** A log of the tree whose crown or trunk top is {@code top}: down its own column or one within two of it, eight deep. */
    private static Optional<Cell> logUnder(SightWorld world, Cell top) {
        for (int dy = 0; dy <= 8; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Cell at = top.add(dx, -dy, dz);
                    if (world.inLoadedChunk(at.x(), at.y(), at.z()) && world.log(at.x(), at.y(), at.z())) return Optional.of(at);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Where a player stands to chop {@code log}: on solid ground under its trunk, at most {@link #TRUNK_REACH} blocks
     * below it, through the trunk or the cells where its lower logs were cut. Nothing for logs held up by leaves, or
     * hanging higher, which would need a pillar, or for a column of logs built into something ({@link #builtIn}).
     */
    public static Optional<Cell> trunkFoot(SightWorld world, Cell log) {
        if (builtIn(world, log)) return Optional.empty();
        Cell below = log.down();
        for (int depth = 1; depth <= TRUNK_REACH + 1; depth++, below = below.down()) {
            // The rest of a trunk whose bottom logs were cut is still chopped from the ground beneath it.
            if (world.air(below.x(), below.y(), below.z())) continue;
            if (world.log(below.x(), below.y(), below.z())) {
                if (builtIn(world, below)) return Optional.empty();
                continue;
            }
            return !world.collisionEmpty(below.x(), below.y(), below.z()) && !world.leaves(below.x(), below.y(), below.z())
                    ? Optional.of(below.up()) : Optional.empty();
        }
        return Optional.empty();
    }

    /** A log beside something built: a cabin's post, not a tree. */
    public static boolean builtIn(SightWorld world, Cell log) {
        for (Side side : Side.values()) {
            Cell next = log.offset(side);
            if (!world.columnLoaded(next.x() >> 4, next.z() >> 4)) continue;
            if (world.built(next.x(), next.y(), next.z())) return true;
        }
        return false;
    }

    private void nextSection() {
        cursor++;
        index = 0;
    }

    /** Completed sweeps of the surroundings so far; a new one starts around wherever the player then is. */
    public int sweeps() { return sweeps; }

    private void rebuildSweep(Cell feet) {
        if (!sweep.isEmpty()) sweeps++;
        sweep.clear();
        cursor = 0;
        index = 0;
        int cx = feet.x() >> 4, cy = feet.y() >> 4, cz = feet.z() >> 4;
        int horizontal = HORIZONTAL_RADIUS >> 4;
        int vertical = Math.max(1, VERTICAL_RADIUS >> 4);
        for (int dy = -vertical; dy <= vertical; dy++) {
            for (int dx = -horizontal; dx <= horizontal; dx++) {
                for (int dz = -horizontal; dz <= horizontal; dz++) {
                    sweep.add(new int[] {cx + dx, cy + dy, cz + dz});
                }
            }
        }
        // Nearest sections first, so fresh surroundings are known quickly.
        sweep.sort((a, b) -> Integer.compare(distance(a, cx, cy, cz), distance(b, cx, cy, cz)));
    }

    private static int distance(int[] a, int cx, int cy, int cz) {
        int dx = a[0] - cx, dy = a[1] - cy, dz = a[2] - cz;
        return dx * dx + dy * dy * 2 + dz * dz;
    }

    /**
     * Whether a face of the block is one a player sees: beside air or a block that is not an opaque full cube, and not
     * under a fluid; for water and lava, only beside air. Obsidian under water shows, as it forms under the water poured.
     */
    public static boolean exposed(SightWorld world, Cell pos, Kind kind) {
        for (Side side : Side.values()) {
            Cell near = pos.offset(side);
            int x = near.x(), y = near.y(), z = near.z();
            if (!world.inLoadedChunk(x, y, z)) continue;
            if (kind == Kind.WATER || kind == Kind.LAVA) {
                if (world.air(x, y, z)) return true;
                continue;
            }
            // A face under water is not a face a walking player can work from, except obsidian, which forms
            // under the water poured to make it.
            if (!world.fluidEmpty(x, y, z) && kind == Kind.OBSIDIAN && world.water(x, y, z)) return true;
            if (world.fluidEmpty(x, y, z) && (world.air(x, y, z) || !world.opaqueFullCube(x, y, z))) return true;
        }
        return false;
    }

    /** Whether a sight line from the eyes reaches the block, within the sweep's reach and eight more. */
    public static boolean visible(SightWorld world, double[] eye, Cell pos) {
        double cx = pos.x() + 0.5, cy = pos.y() + 0.5, cz = pos.z() + 0.5;
        double dx = cx - eye[0], dy = cy - eye[1], dz = cz - eye[2];
        if (dx * dx + dy * dy + dz * dz > (HORIZONTAL_RADIUS + 8) * (HORIZONTAL_RADIUS + 8)) return false;
        if (sees(world, eye, cx, cy, cz, pos)) return true;
        // A pool set into the floor shows only its surface: from a few blocks away the ray to its centre meets the
        // floor's edge, while a player plainly sees the lava or water. Look at the surface too.
        if (world.fluidEmpty(pos.x(), pos.y(), pos.z())) return false;
        float height = world.fluidHeight(pos.x(), pos.y(), pos.z());
        return sees(world, eye, pos.x() + 0.5, pos.y() + Math.max(0.1, height - 0.05), pos.z() + 0.5, pos);
    }

    /**
     * In plain terms: whether its eyes reach a block, however far off, over ground that is there to look at. Every
     * stretch of ground the sight line crosses must be loaded; a stretch that is not was never seen, and nothing here
     * loads one.
     *
     * Technical: F-35m. Every chunk column the line's ground track crosses is checked loaded (a walk of the chunk grid
     * along it) before the one sight line, which aims at the block's top face, a thousandth inside it (so a line along
     * flat ground does not meet the ground before it), and counts only if it meets the block itself or nothing. Water is
     * seen through, as a swimmer with its eyes under the surface still sees, and a water top is seen when the line ends
     * at its surface unmet. No distance cap: the callers bound how many they ask.
     */
    public static boolean seenFrom(SightWorld world, double[] eye, Cell pos) {
        double px = pos.x() + 0.5, py = pos.y() + 0.999, pz = pos.z() + 0.5;
        if (!loadedAlong(world, eye[0], eye[2], px, pz)) return false;
        Cell hit = world.ray(eye[0], eye[1], eye[2], px, py, pz, false);
        return hit == null || hit.equals(pos);
    }

    /**
     * In plain terms: the top of a far column, if it can see that top from where it stands; nothing if it cannot, if the
     * column's ground is not there to look at, or if its sight line crosses ground that is not.
     *
     * Technical: F-35m. {@code aim} (the column's top as the world keys it, the first block over the top) is asked only of
     * a column whose ground and whose way from the eyes are loaded, and only to choose the one block the sight line goes
     * to: when {@link #seenFrom} fails, nothing from that column is returned or kept.
     */
    public static Optional<Cell> seenTop(SightWorld world, double[] eye, int x, int z, java.util.function.IntBinaryOperator aim) {
        if (!world.columnLoaded(x >> 4, z >> 4)) return Optional.empty();
        if (!loadedAlong(world, eye[0], eye[2], x + 0.5, z + 0.5)) return Optional.empty();
        Cell top = new Cell(x, aim.applyAsInt(x, z) - 1, z);
        Cell hit = world.ray(eye[0], eye[1], eye[2], top.x() + 0.5, top.y() + 0.999, top.z() + 0.5, false);
        return hit == null || hit.equals(top) ? Optional.of(top) : Optional.empty();
    }

    /**
     * In plain terms: the first of the places, in order, that is what it looks for and that it can see from where it
     * stands. Whether a place is what it looks for is asked first only to spare sight lines: a place that is not could
     * never be the answer, so the answer is the one a sight line to every place in turn would give.
     *
     * Technical: F-35m. Equal to the first {@code c} in {@code candidates} with {@code seenFrom(world, eye, c) &&
     * answer.test(c)} while the sight lines last: {@code answer} must be a property of the place alone (not of the order,
     * nor of other places), so testing it before the sight line only skips places that could not win. {@code rays[0]} is
     * the sight lines left, spent one a cast; when it runs out, nothing further is seen (fewer casts than the
     * unfiltered search, so it runs out no sooner).
     */
    public static Optional<Cell> firstSeen(SightWorld world, double[] eye, List<Cell> candidates, Predicate<Cell> answer, int[] rays) {
        for (Cell c : candidates) {
            if (rays[0] <= 0) return Optional.empty();
            if (!answer.test(c)) continue;
            double px = c.x() + 0.5, py = c.y() + 0.999, pz = c.z() + 0.5;
            if (!loadedAlong(world, eye[0], eye[2], px, pz)) continue;
            rays[0]--;
            Cell hit = world.ray(eye[0], eye[1], eye[2], px, py, pz, false);
            if (hit == null || hit.equals(c)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** Every chunk column the straight ground track from (ax, az) to (bx, bz) crosses is loaded: the chunk grid walked along it. */
    static boolean loadedAlong(SightWorld world, double ax, double az, double bx, double bz) {
        int cx = (int) Math.floor(ax / 16), cz = (int) Math.floor(az / 16);
        int ex = (int) Math.floor(bx / 16), ez = (int) Math.floor(bz / 16);
        double dx = bx - ax, dz = bz - az;
        int sx = Integer.signum(ex - cx), sz = Integer.signum(ez - cz);
        double tx = sx == 0 ? Double.MAX_VALUE : ((sx > 0 ? (cx + 1) * 16.0 : cx * 16.0) - ax) / dx;
        double tz = sz == 0 ? Double.MAX_VALUE : ((sz > 0 ? (cz + 1) * 16.0 : cz * 16.0) - az) / dz;
        double stepX = sx == 0 ? 0 : 16.0 / Math.abs(dx), stepZ = sz == 0 ? 0 : 16.0 / Math.abs(dz);
        for (int guard = 0; guard < 4096; guard++) {
            if (!world.columnLoaded(cx, cz)) return false;
            if (cx == ex && cz == ez) return true;
            if (tx == tz) {
                // Through a corner the line touches both neighbours: both must be loaded.
                if (!world.columnLoaded(cx + sx, cz) || !world.columnLoaded(cx, cz + sz)) return false;
                cx += sx; cz += sz; tx += stepX; tz += stepZ;
            } else if (tx < tz) {
                cx += sx; tx += stepX;
            } else {
                cz += sz; tz += stepZ;
            }
        }
        return false;
    }

    private static boolean sees(SightWorld world, double[] eye, double px, double py, double pz, Cell pos) {
        Cell hit = world.ray(eye[0], eye[1], eye[2], px, py, pz, true);
        return hit == null || hit.equals(pos);
    }

    /** Listens for each block of a kind remembered for the first time. */
    public void onFirstSight(BiConsumer<Kind, Cell> listener) { firstSight = listener; }

    public void remember(Kind kind, Cell pos) {
        // Water it saw, by column, past the cap on blocks of a kind: a sea fills that cap long before it has looked round.
        if (kind == Kind.WATER) {
            long column = column(pos.x(), pos.z());
            waterColumns.remove(column);
            waterColumns.add(column);
            if (waterColumns.size() > WATER_COLUMNS) waterColumns.remove(waterColumns.iterator().next());
        }
        var map = known.get(kind);
        long key = kind == Kind.CAVE ? cell(pos) : pos.asLong();
        if (map.size() >= MAX_PER_KIND && !map.containsKey(key)) return;
        if (map.put(key, pos) == null && firstSight != null) firstSight.accept(kind, pos);
    }

    /**
     * F-30d: a block of a kind it saw before this start, as its atlas kept it, remembered again without telling the
     * listener (it has it already). Checked against the world before any use, as everything remembered is.
     */
    public void recall(Kind kind, Cell pos) {
        var map = known.get(kind);
        long key = kind == Kind.CAVE ? cell(pos) : pos.asLong();
        if (map.size() >= MAX_PER_KIND && !map.containsKey(key)) return;
        map.putIfAbsent(key, pos);
    }

    /**
     * Whether a walk from {@code from} to {@code to} would end in open water it saw (F-35j). Within its sight
     * ({@value #HORIZONTAL_RADIUS} blocks), every column within two of the end is still water open to the air that it saw;
     * beyond, the last {@code run} columns along the line out to the edge of its sight are. Nothing is read from the world:
     * a column it has not seen is not water to it, and land seen beyond a strait breaks the run, so a crossing to land it
     * can see is not open water.
     */
    public boolean seenOpenWaterToward(Cell from, Cell to, int run) {
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        double far = Math.hypot(dx, dz);
        if (far <= HORIZONTAL_RADIUS) {
            for (int ox = -2; ox <= 2; ox++) for (int oz = -2; oz <= 2; oz++) {
                if (!waterColumns.contains(column(to.x() + ox, to.z() + oz))) return false;
            }
            return true;
        }
        for (int i = HORIZONTAL_RADIUS - run + 1; i <= HORIZONTAL_RADIUS; i++) {
            int x = (int) Math.floor(from.x() + 0.5 + dx * i / far), z = (int) Math.floor(from.z() + 0.5 + dz * i / far);
            if (!waterColumns.contains(column(x, z))) return false;
        }
        return true;
    }

    public void forget(Cell pos) {
        for (var map : known.values()) map.remove(pos.asLong());
        known.get(Kind.CAVE).remove(cell(pos));
    }

    /** Still what it was remembered as: the same kind of block, or for a cave, still a cave floor. */
    private static boolean still(SightWorld world, Kind kind, Cell pos) {
        return kind == Kind.CAVE ? caveFloor(world, pos) : world.kind(pos.x(), pos.y(), pos.z()) == kind;
    }

    public int count(Kind kind) { return known.get(kind).size(); }

    /** Nearest remembered block of a kind that still matches the world and the caller's filter. */
    public Optional<Cell> nearest(SightWorld world, Kind kind, Cell from, Predicate<Cell> filter) {
        Cell best = null;
        double bestDistance = Double.MAX_VALUE;
        var stale = new ArrayList<Long>();
        for (var entry : known.get(kind).entrySet()) {
            Cell pos = entry.getValue();
            if (world.inLoadedChunk(pos.x(), pos.y(), pos.z()) && !still(world, kind, pos)) {
                stale.add(entry.getKey());
                continue;
            }
            double distance = pos.squaredDistance(from);
            if (distance < bestDistance && filter.test(pos)) {
                bestDistance = distance;
                best = pos;
            }
        }
        stale.forEach(key -> known.get(kind).remove(key));
        return Optional.ofNullable(best);
    }

    public List<Cell> all(Kind kind) { return List.copyOf(known.get(kind).values()); }

    /** Lowest-scoring remembered block of a kind that still matches the world and the caller's filter. */
    public Optional<Cell> best(SightWorld world, Kind kind, ToDoubleFunction<Cell> score, Predicate<Cell> filter) {
        Cell best = null;
        double bestScore = Double.MAX_VALUE;
        var stale = new ArrayList<Long>();
        for (var entry : known.get(kind).entrySet()) {
            Cell pos = entry.getValue();
            if (world.inLoadedChunk(pos.x(), pos.y(), pos.z()) && !still(world, kind, pos)) {
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
