package dev.kindred.core.construction;

import dev.kindred.core.construction.BuildSite.Cell;
import dev.kindred.core.construction.Pos.Face;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The next step of a build, re-planned from the world every time it is asked (E-08a), as a player builds: what is
 * already there in the state wanted is done; a block in the way of a target or of space to keep clear is broken; of
 * the rest, the lowest layer first and then the nearest, each placed against a face it can be set on, from a spot the
 * player can stand on and reach the face from, looking the way that gives the state wanted. With no such spot, a
 * scaffold column of its own blocks is built to stand on, and taken down at the end. It never stands where it would
 * seal itself in, never builds against what it cannot see, and never breaks what is not the build's to change. Pure.
 */
public final class ConstructionPlanner {
    /** How far from the eyes a face can be clicked: vanilla's block interaction range. */
    public static final double REACH = 4.5;
    public static final double EYE = 1.62;
    /** Targets weighed for a spot to stand each pass, in build order; the rest wait their turn. */
    static final int CANDIDATES = 48;
    /** How far round a target a spot to stand is looked for. */
    static final int STANCE_RADIUS = 4;
    /** For a block whose state is the way the player faces, the look stays this many degrees inside the quarter. */
    static final double FACING_MARGIN = 35;
    /** The highest drop it walks off, as the navigator does without a water bucket. */
    static final int MAX_DROP = 3;
    /** Spots weighed in working out where it can walk. */
    static final int WALK_BUDGET = 40_000;
    /** How far from a target a scaffold column may stand. */
    static final int SCAFFOLD_RADIUS = 4;
    /** A scaffold block, as the planner reckons it while weighing a column. */
    private static final Cell SCAFFOLD_CELL = new Cell(BuildSite.Kind.SOLID, BlockSpec.of("minecraft:dirt"), true);
    /** Tallest prop it puts up beside a block with nothing to be set against. */
    static final int MAX_PROP = 6;
    /** Tallest scaffold column it builds. */
    public static final int MAX_SCAFFOLD = 24;

    public enum Kind {
        /** Place {@code spec} at {@code target}, from {@code stance}, clicking {@code face} of {@code support} at {@code hit}. */
        PLACE,
        /** Break the block at {@code target}: in a target's way, in space to keep clear, or scaffolding no longer wanted. */
        BREAK,
        /** Stand at {@code stance} and pillar up {@code height} blocks of scaffolding. */
        SCAFFOLD,
        /**
         * Place a block of scaffolding at {@code target}, from {@code stance} clicking {@code face} of {@code support}:
         * a prop for a block that has nothing to be set against, as a log lying along a row with nothing at its ends.
         * The prop is taken down with the rest of the scaffolding.
         */
        SUPPORT,
        /** Every block is in place and every clear cell clear, scaffolding down. */
        DONE,
        /** Nothing can be done now; {@code reason} says why. */
        BLOCKED
    }

    /**
     * A step.
     *
     * @param kind    what to do
     * @param target  the block placed or broken
     * @param spec    the state placed
     * @param item    the item placed from
     * @param stance  where the feet stand to do it
     * @param support the block whose face is clicked
     * @param face    that face, on the support's side toward the target
     * @param hit     where on the face, in world coordinates {x, y, z}
     * @param yaw     the look's yaw (vanilla's: 0 south, 90 west)
     * @param pitch   the look's pitch (down positive)
     * @param height  scaffold blocks to pillar
     * @param reason  why, for the log and for a blocked build
     */
    public record Step(Kind kind, Pos target, BlockSpec spec, String item, Pos stance, Pos support, Face face, double[] hit,
                       float yaw, float pitch, int height, String reason) {
        public Step { Objects.requireNonNull(kind); reason = Objects.requireNonNullElse(reason, ""); }

        static Step done() { return new Step(Kind.DONE, null, null, null, null, null, null, null, 0, 0, 0, "Built"); }

        static Step blocked(String reason) { return new Step(Kind.BLOCKED, null, null, null, null, null, null, null, 0, 0, 0, reason); }

        static Step breaking(Pos target, Pos stance, String reason) {
            return new Step(Kind.BREAK, target, null, null, stance, null, null, null, 0, 0, 0, reason);
        }
    }

    /** What is left: blocks to place, blocks in the way, and those whose placement is not modelled. */
    public record Progress(int total, int placed, int remaining, int inTheWay, List<String> unsupported) {
        public Progress { unsupported = List.copyOf(unsupported); }
    }

    private ConstructionPlanner() { }

    /** How far along a build is, from what the world holds. */
    public static Progress progress(List<Blueprint.Block> blocks, List<Pos> clear, BuildSite site) {
        int placed = 0, inTheWay = 0;
        var unsupported = new java.util.TreeSet<String>();
        for (var b : blocks) {
            var cell = site.cell(b.at());
            if (cell.isPresent() && cell.get().state().sameAs(b.spec())) placed++;
            else if (cell.isPresent() && !cell.get().placeable()) inTheWay++;
            if (Placement.rule(b.spec()) == Placement.Rule.UNSUPPORTED) unsupported.add(b.spec().block());
        }
        for (Pos p : clear) {
            var cell = site.cell(p);
            if (cell.isPresent() && !cell.get().passable()) inTheWay++;
        }
        return new Progress(blocks.size(), placed, blocks.size() - placed, inTheWay, List.copyOf(unsupported));
    }

    /**
     * The next step for a player whose feet are at {@code feet}, holding {@code items} (item ids), with
     * {@code scaffold} the scaffolding it has put up for this build.
     */
    public static Step next(List<Blueprint.Block> blocks, List<Pos> clear, BuildSite site, Pos feet, Set<String> items, Set<Pos> scaffold) {
        return next(blocks, clear, site, feet, items, scaffold, Set.of());
    }

    /**
     * As {@link #next}, never standing at a spot in {@code shunned}: where the server refused a step the plan thought
     * sound (a reach or a line of sight reckoned a hair differently), so another spot is tried.
     */
    public static Step next(List<Blueprint.Block> blocks, List<Pos> clear, BuildSite site, Pos feet, Set<String> items, Set<Pos> scaffold,
                            Set<Pos> shunned) {
        Map<Pos, BlockSpec> wanted = Blueprint.byPosition(blocks);
        Set<Pos> keepClear = new HashSet<>(clear);
        Box box = Box.of(blocks);
        // In the way: something solid where a block or clear space is wanted, the build's to break if not protected.
        var inTheWay = new ArrayList<Pos>();
        for (var b : blocks) {
            var cell = site.cell(b.at());
            if (cell.isEmpty()) continue;
            if (!cell.get().placeable() && !cell.get().state().sameAs(b.spec()) && cell.get().kind() != BuildSite.Kind.DANGER) inTheWay.add(b.at());
        }
        for (Pos p : keepClear) {
            var cell = site.cell(p);
            if (cell.isPresent() && !cell.get().passable() && !scaffold.contains(p) && cell.get().kind() != BuildSite.Kind.DANGER) inTheWay.add(p);
        }
        inTheWay.sort(Comparator.<Pos>comparingInt(p -> -p.y()).thenComparingLong(p -> p.distanceSquared(feet)));
        // Where it can walk to from here: only those spots are stood on, and only those grounds scaffolded from.
        Set<Pos> walk = walkable(site, feet, scaffold);
        walk.removeAll(shunned);
        for (Pos p : inTheWay) {
            if (site.cell(p).get().kind() == BuildSite.Kind.PROTECTED) return Step.blocked("In the way and not the build's to break: " + p);
            Optional<Pos> stance = stanceToBreak(site, p, feet, wanted, box, walk);
            if (stance.isPresent()) return Step.breaking(p, stance.get(), "In the way of the build");
        }
        // To place: not there yet, room for it, the item in hand, a rule for it.
        var todo = new ArrayList<Blueprint.Block>();
        boolean anyItem = false, anyRule = false;
        for (var b : blocks) {
            var cell = site.cell(b.at());
            if (cell.isEmpty()) continue;
            if (cell.get().state().sameAs(b.spec()) || !cell.get().placeable()) continue;
            if (Placement.rule(b.spec()) == Placement.Rule.UNSUPPORTED) continue;
            anyRule = true;
            if (!items.contains(Placement.item(b.spec()))) continue;
            anyItem = true;
            todo.add(b);
        }
        if (todo.isEmpty()) {
            boolean unknown = blocks.stream().anyMatch(b -> !site.known(b.at()));
            if (unknown) return Step.blocked("Part of the build is out of sight");
            if (anyRule && !anyItem) return Step.blocked("Nothing in hand to place");
            if (blocks.stream().anyMatch(b -> site.cell(b.at()).map(c -> !c.state().sameAs(b.spec())).orElse(true))) {
                return anyRule ? Step.blocked("The rest cannot be placed from here") : Step.blocked("The rest are blocks it cannot place yet");
            }
            // Built: the scaffolding comes down, top first.
            var up = scaffold.stream().filter(p -> site.cell(p).map(c -> c.kind() == BuildSite.Kind.SOLID).orElse(false))
                    .max(Comparator.comparingInt(Pos::y));
            if (up.isPresent()) {
                Pos top = up.get();
                Optional<Pos> stance = stanceToBreak(site, top, feet, wanted, box, walk);
                if (stance.isPresent()) return Step.breaking(top, stance.get(), "Scaffolding down");
                if (feet.equals(top.up())) return Step.breaking(top, feet, "Scaffolding down, from on top of it");
                return Step.blocked("Scaffolding at " + top + " is out of reach to take down");
            }
            return Step.done();
        }
        todo.sort(Comparator.<Blueprint.Block>comparingInt(b -> b.at().y()).thenComparingLong(b -> b.at().distanceSquared(feet)));
        int weighed = 0;
        for (var b : todo) {
            if (++weighed > CANDIDATES) break;
            if (b.at().y() > todo.getFirst().at().y() + 1) break; // A layer at a time: never a block hung above an unfinished one.
            var place = placement(site, b, feet, wanted, box, stances(b.at(), feet), walk);
            if (place.isPresent()) return place.get();
        }
        // Nothing in reach of a spot to stand: a scaffold to stand on, for the lowest block waiting that one serves.
        var lowest = todo.getFirst();
        weighed = 0;
        for (var b : todo) {
            if (++weighed > CANDIDATES || b.at().y() > lowest.at().y() + 1) break;
            var column = scaffold(site, b, feet, wanted, box, walk);
            if (column.isPresent()) return column.get();
        }
        // Nothing to be set against: a prop beside it, a column of scaffolding from the ground.
        weighed = 0;
        for (var b : todo) {
            if (++weighed > CANDIDATES || b.at().y() > lowest.at().y() + 1) break;
            var prop = prop(site, b, feet, wanted, keepClear, box, walk);
            if (prop.isPresent()) return prop.get();
        }
        return Step.blocked("No way to reach " + lowest.spec().block() + " at " + lowest.at());
    }

    /**
     * The placement of {@code b} from the best of {@code stances}, if any: those it can walk to ({@code walk}; null for
     * one it will be stood on by other means, the top of a scaffold).
     */
    private static Optional<Step> placement(BuildSite site, Blueprint.Block b, Pos feet, Map<Pos, BlockSpec> wanted, Box box,
                                            List<Pos> stances, Set<Pos> walk) {
        BlockSpec spec = b.spec();
        Face mustFace = Placement.playerFacing(spec);
        double height = Placement.hitHeight(spec);
        Step best = null;
        double bestCost = Double.MAX_VALUE;
        for (Face face : Placement.faces(spec)) {
            Pos support = b.at().offset(face.opposite());
            var supportCell = site.cell(support);
            if (supportCell.isEmpty() || !supportCell.get().clickable()) continue;
            if (Placement.rule(spec) == Placement.Rule.GRAVITY && !supportCell.get().floor()) continue;
            double[] hit = hitPoint(support, face, height);
            for (Pos stance : stances) {
                if (walk != null && !walk.contains(stance) || !site.standable(stance) || occupies(stance, b.at())) continue;
                if (wanted.containsKey(stance) || wanted.containsKey(stance.up())) continue; // Never stand where a block is still to go.
                double ex = stance.x() + 0.5, ey = stance.y() + EYE, ez = stance.z() + 0.5;
                double dx = hit[0] - ex, dy = hit[1] - ey, dz = hit[2] - ez;
                double reach = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (reach > REACH - 0.05) continue;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
                // Well inside the facing's quarter, so a body a little off the middle of its spot still faces the same way.
                if (mustFace != null && (Placement.facingOf(yaw) != mustFace || offQuarter(yaw) > FACING_MARGIN)) continue;
                if (!clearSight(site, new double[] {ex, ey, ez}, hit, support, b.at())) continue;
                if (box.inside(stance) && sealedIn(site, stance, b.at(), wanted, box)) continue;
                double cost = Math.sqrt(stance.distanceSquared(feet)) + (box.inside(stance) ? 3 : 0) + reach * 0.1;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new Step(Kind.PLACE, b.at(), spec, Placement.item(spec), stance, support, face, hit, yaw, pitch, 0,
                            "Place " + spec.block());
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** A spot to stand to break {@code target}, the nearest: in reach of its middle, with a clear sight of it. */
    private static Optional<Pos> stanceToBreak(BuildSite site, Pos target, Pos feet, Map<Pos, BlockSpec> wanted, Box box, Set<Pos> walk) {
        Pos best = null;
        double bestCost = Double.MAX_VALUE;
        double[] middle = {target.x() + 0.5, target.y() + 0.5, target.z() + 0.5};
        for (Pos stance : stances(target, feet)) {
            if (!walk.contains(stance) || !site.standable(stance) || occupies(stance, target) || stance.equals(target.up())) continue;
            if (wanted.containsKey(stance) || wanted.containsKey(stance.up())) continue;
            double ex = stance.x() + 0.5, ey = stance.y() + EYE, ez = stance.z() + 0.5;
            double reach = Math.sqrt(Math.pow(middle[0] - ex, 2) + Math.pow(middle[1] - ey, 2) + Math.pow(middle[2] - ez, 2));
            if (reach > REACH - 0.05) continue;
            if (!clearSight(site, new double[] {ex, ey, ez}, middle, target, target)) continue;
            double cost = Math.sqrt(stance.distanceSquared(feet)) + (box.inside(stance) ? 3 : 0);
            if (cost < bestCost) { bestCost = cost; best = stance; }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A scaffold column to stand on for {@code b}: from ground near the target, outside the build if it can be, just
     * high enough that from its top the block can be placed as wanted (in reach, in sight, looking the way its state
     * needs); nearest the player and lowest first.
     */
    private static Optional<Step> scaffold(BuildSite site, Blueprint.Block b, Pos feet, Map<Pos, BlockSpec> wanted, Box box, Set<Pos> walk) {
        Pos target = b.at();
        Step best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dx = -SCAFFOLD_RADIUS; dx <= SCAFFOLD_RADIUS; dx++) for (int dz = -SCAFFOLD_RADIUS; dz <= SCAFFOLD_RADIUS; dz++) {
            if (dx == 0 && dz == 0) continue;
            // From the ground at this column: the highest floor under the target's level.
            Pos column = new Pos(target.x() + dx, target.y(), target.z() + dz);
            Pos ground = null;
            for (int down = 0; down <= MAX_SCAFFOLD + 4; down++) {
                Pos at = column.add(0, -down, 0);
                if (site.standable(at)) { ground = at; break; }
                if (site.cell(at).map(c -> !c.passable()).orElse(true)) break;
            }
            if (ground == null || !walk.contains(ground)) continue;
            for (int height = Math.max(1, target.y() - 3 - ground.y()); height <= Math.min(MAX_SCAFFOLD, target.y() - ground.y()); height++) {
                boolean free = true;
                var raised = new HashMap<Pos, Cell>();
                for (int h = 0; h <= height + 1 && free; h++) {
                    Pos at = ground.add(0, h, 0);
                    free = !wanted.containsKey(at) && site.cell(at).map(Cell::passable).orElse(false);
                    if (h < height) raised.put(at, SCAFFOLD_CELL);
                }
                if (!free) break;
                double cost = Math.sqrt(ground.distanceSquared(feet)) + (box.inside(ground) ? 6 : 0) + height;
                if (cost >= bestCost) break;
                Pos top = ground.add(0, height, 0);
                if (placement(site.with(raised), b, top, wanted, box, List.of(top), null).isEmpty()) continue;
                bestCost = cost;
                best = new Step(Kind.SCAFFOLD, target, null, null, ground, null, null, null, 0, 0, height,
                        "Scaffold " + height + " up to reach " + target);
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A prop for {@code b}, which has no face to be set against: beside it on a side its state can be clicked from, a
     * column of scaffolding from whatever is under that cell up to it (at most {@link #MAX_PROP} high), worth it only
     * if the block can then be placed from somewhere, on foot or from a scaffold. The step places the column's lowest
     * missing block.
     */
    private static Optional<Step> prop(BuildSite site, Blueprint.Block b, Pos feet, Map<Pos, BlockSpec> wanted, Set<Pos> keepClear,
                                       Box box, Set<Pos> walk) {
        Step best = null;
        double bestCost = Double.MAX_VALUE;
        for (Face face : Placement.faces(b.spec())) {
            Pos beside = b.at().offset(face.opposite());
            var column = new ArrayList<Pos>();
            boolean grounded = false;
            for (Pos at = beside; column.size() < MAX_PROP; at = at.down()) {
                var cell = site.cell(at);
                if (cell.isEmpty() || !cell.get().placeable() || wanted.containsKey(at) || keepClear.contains(at)) break;
                column.add(at);
                if (site.cell(at.down()).map(Cell::clickable).orElse(false)) { grounded = true; break; }
            }
            if (!grounded) continue;
            var props = new HashMap<Pos, Cell>();
            for (Pos p : column) props.put(p, SCAFFOLD_CELL);
            var propped = site.with(props);
            if (placement(propped, b, feet, wanted, box, stances(b.at(), feet), walk).isEmpty()
                    && scaffold(propped, b, feet, wanted, box, walk).isEmpty()) continue;
            Pos lowest = column.getLast();
            var place = placement(site, new Blueprint.Block(lowest, SCAFFOLD_CELL.state()), feet, wanted, box, stances(lowest, feet), walk);
            if (place.isEmpty()) continue;
            var step = place.get();
            double cost = column.size() * 2 + Math.sqrt(step.stance().distanceSquared(feet));
            if (cost < bestCost) {
                bestCost = cost;
                best = new Step(Kind.SUPPORT, lowest, SCAFFOLD_CELL.state(), null, step.stance(), step.support(), step.face(), step.hit(),
                        step.yaw(), step.pitch(), column.size(), "Prop for " + b.spec().block() + " at " + b.at());
            }
        }
        return Optional.ofNullable(best);
    }

    /** Spots to stand round {@code target}, near enough to reach it. */
    private static List<Pos> stances(Pos target, Pos feet) {
        var out = new ArrayList<Pos>();
        if (Math.abs(feet.x() - target.x()) <= STANCE_RADIUS && Math.abs(feet.z() - target.z()) <= STANCE_RADIUS) out.add(feet);
        for (int dy = -3; dy <= 2; dy++) for (int dx = -STANCE_RADIUS; dx <= STANCE_RADIUS; dx++) for (int dz = -STANCE_RADIUS; dz <= STANCE_RADIUS; dz++) {
            Pos p = target.add(dx, dy, dz);
            if (!p.equals(feet)) out.add(p);
        }
        return out;
    }

    /**
     * The spots its feet can get to from {@code feet} on foot, as the navigator walks: a step up of a block with head
     * room for the jump, a drop of up to three, across to the four sides; and down its own scaffold, digging out the
     * block underfoot. Bounded.
     */
    static Set<Pos> walkable(BuildSite site, Pos feet, Set<Pos> scaffold) {
        var seen = new HashSet<Pos>();
        var queue = new ArrayDeque<Pos>();
        seen.add(feet);
        queue.add(feet);
        int budget = WALK_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            Pos at = queue.poll();
            if (scaffold.contains(at.down()) && site.cell(at.down().down()).map(Cell::floor).orElse(false) && seen.add(at.down())) {
                queue.add(at.down());
            }
            for (Face f : List.of(Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST)) {
                Pos side = at.offset(f);
                for (int dy = 1; dy >= -MAX_DROP; dy--) {
                    Pos to = side.add(0, dy, 0);
                    // Up a step needs the head room for the jump; down, the body falls through the side's open cells.
                    if (dy == 1 && !site.cell(at.add(0, 2, 0)).map(Cell::passable).orElse(false)) continue;
                    if (site.standable(to)) {
                        if (seen.add(to)) queue.add(to);
                        break;
                    }
                    if (dy <= 0 && !site.cell(to.up()).map(Cell::passable).orElse(false)) break;
                }
            }
        }
        return seen;
    }

    /** How far {@code yaw} is from the middle of the quarter it falls in, in degrees. */
    static double offQuarter(float yaw) {
        double off = Math.floorMod(Math.round(yaw * 1000.0), 90_000L) / 1000.0;
        return Math.min(off, 90 - off);
    }

    /** A player standing at {@code feet} would be in the way of a block at {@code target}. */
    private static boolean occupies(Pos feet, Pos target) {
        return target.x() == feet.x() && target.z() == feet.z() && (target.y() == feet.y() || target.y() == feet.y() + 1);
    }

    /** The middle of {@code face} of {@code support}, at {@code height} within a side face. */
    static double[] hitPoint(Pos support, Face face, double height) {
        double x = support.x() + 0.5 + face.dx * 0.5, z = support.z() + 0.5 + face.dz * 0.5;
        double y = face == Face.UP ? support.y() + 1.0 : face == Face.DOWN ? support.y() : support.y() + height;
        // A hair back inside the support, so the click is on its face and not past it.
        return new double[] {x - face.dx * 0.01, y - face.dy * 0.01, z - face.dz * 0.01};
    }

    /** Nothing solid between the eyes and the point, but the block clicked and the target itself. */
    private static boolean clearSight(BuildSite site, double[] eye, double[] to, Pos support, Pos target) {
        double dx = to[0] - eye[0], dy = to[1] - eye[1], dz = to[2] - eye[2];
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 8);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Pos at = new Pos((int) Math.floor(eye[0] + dx * t), (int) Math.floor(eye[1] + dy * t), (int) Math.floor(eye[2] + dz * t));
            if (at.equals(support) || at.equals(target)) continue;
            var cell = site.cell(at);
            if (cell.isEmpty() || !cell.get().passable() && cell.get().kind() != BuildSite.Kind.REPLACEABLE) return false;
        }
        return true;
    }

    /**
     * Whether a player standing at {@code stance} inside the build would be shut in once {@code placing} and every
     * block still wanted were in place: no way out of the build's box through cells that stay clear.
     */
    private static boolean sealedIn(BuildSite site, Pos stance, Pos placing, Map<Pos, BlockSpec> wanted, Box box) {
        var seen = new HashSet<Pos>();
        var queue = new ArrayDeque<Pos>();
        queue.add(stance);
        seen.add(stance);
        int budget = 20_000;
        while (!queue.isEmpty() && budget-- > 0) {
            Pos at = queue.poll();
            if (!box.inside(at)) return false;
            for (Face f : Face.values()) {
                Pos next = at.offset(f);
                if (!seen.add(next) || next.equals(placing) || wanted.containsKey(next) || wanted.containsKey(next.up())) continue;
                var cell = site.cell(next);
                var head = site.cell(next.up());
                if (cell.isEmpty() || head.isEmpty() || !cell.get().passable() || !head.get().passable()) continue;
                queue.add(next);
            }
        }
        return true;
    }

    /** The build's bounding box, by x and z: inside it a player is among the build. */
    record Box(int minX, int minZ, int maxX, int maxZ) {
        static Box of(List<Blueprint.Block> blocks) {
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (var b : blocks) {
                minX = Math.min(minX, b.at().x()); minZ = Math.min(minZ, b.at().z());
                maxX = Math.max(maxX, b.at().x()); maxZ = Math.max(maxZ, b.at().z());
            }
            return new Box(minX, minZ, maxX, maxZ);
        }

        boolean inside(Pos p) {
            return p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ;
        }
    }
}
