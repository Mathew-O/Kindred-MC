package dev.kindred.core.construction;

import dev.kindred.core.VanillaIds;

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
 * In plain terms: the next step of a build, worked out fresh from the world each time, as a player builds:
 * break what is in the way, then place blocks from the bottom layer up, nearest first, from a spot it can
 * stand on and reach. With nowhere to stand it builds a pillar of its own blocks and takes it down at the
 * end, and it never walls itself in or breaks what is not part of the build.
 *
 * Technical: The next step of a build, re-planned from the world every time it is asked (E-08a), as a player
 * builds: what is already there in the state wanted is done; a block in the way of a target or of space to
 * keep clear is broken; of the rest, the lowest layer first and then the nearest, each placed against a face
 * it can be set on, from a spot the player can stand on and reach the face from, looking the way that gives
 * the state wanted. With no such spot, a scaffold column of its own blocks is built to stand on, and taken
 * down at the end. It never stands where it would seal itself in, never builds against what it cannot see,
 * and never breaks what is not the build's to change. Pure.
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
    public static final int MAX_DROP = 3;
    /** Spots weighed in working out where it can walk. */
    static final int WALK_BUDGET = 40_000;
    /** How far from a target a scaffold column may stand. */
    static final int SCAFFOLD_RADIUS = 4;
    /** A scaffold block, as the planner reckons it while weighing a column. */
    private static final Cell SCAFFOLD_CELL = new Cell(BuildSite.Kind.SOLID, BlockSpec.of(VanillaIds.DIRT), true);
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

        /** Blocked over {@code target}: nothing to be done from here, but there may be from nearer it. */
        static Step blockedAt(Pos target, String reason) { return new Step(Kind.BLOCKED, target, null, null, null, null, null, null, 0, 0, 0, reason); }

        /** A step to break one block, standing at a given spot. */
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
    public static Step next(List<Blueprint.Block> blocks, List<Pos> clear, BuildSite world, Pos feet, Set<String> items, Set<Pos> scaffold,
                            Set<Pos> shunned) {
        return next(blocks, clear, world, feet, items, scaffold, shunned, 0);
    }

    /**
     * As {@link #next(List, List, BuildSite, Pos, Set, Set, Set)}, with {@code spare} the scaffold blocks the player holds
     * beyond what the build still needs: taking its scaffold down, a spot it could not walk back from counts as one it
     * can come back from when a scaffold of those blocks takes it back up (F-22c45).
     */
    public static Step next(List<Blueprint.Block> blocks, List<Pos> clear, BuildSite world, Pos feet, Set<String> items, Set<Pos> scaffold,
                            Set<Pos> shunned, int spare) {
        return next(blocks, clear, world, feet, items, scaffold, shunned, spare, List.of());
    }

    /** Why a block waits on one the build set aside: the step's target is the set-aside block's cell (E-70a1). */
    public static final String WAITS_ON_SET_ASIDE = "Waits on a block it set aside at ";

    /**
     * As {@link #next(List, List, BuildSite, Pos, Set, Set, Set, int)}, with {@code setAside} the blocks the build gave up
     * on for now (refused again and again, as with an animal standing in the cell) and will try once more. They are not
     * placed now, but their cells are still the build's: never stood in, never filled with a prop or a scaffold. A block
     * with no other face to be set against than a set-aside block's waits on it: the step blocked at that cell, so its
     * runner can try that block again first. A bed's foot over a floor plank set aside was propped in the plank's cell;
     * the bed went on top, the prop was walled in by the floor and out of reach to take down, and the cabin was left one
     * plank short (#239 and #240 in CI, E-70a1).
     */
    public static Step next(List<Blueprint.Block> blocks, List<Pos> clear, BuildSite world, Pos feet, Set<String> items, Set<Pos> scaffold,
                            Set<Pos> shunned, int spare, List<Blueprint.Block> setAside) {
        BuildSite site = steps(blocks, clear, world);
        Map<Pos, BlockSpec> wanted = new HashMap<>(Blueprint.byPosition(setAside));
        wanted.putAll(Blueprint.byPosition(blocks));
        Set<Pos> owed = new HashSet<>();
        for (var b : setAside) if (site.cell(b.at()).map(c -> !c.state().sameAs(b.spec())).orElse(false)) owed.add(b.at());
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
        // Where it can walk to from here: only those spots are stood on, and only those grounds scaffolded from. Off
        // the build and the ground round it, only spots it can walk back from: a drop off the edge of its ground is
        // walkable one way, and stood down there (three below a cabin's ring, run 5 of 29 October), the scaffolding
        // beside it was out of reach and the way back up a wall.
        Set<Pos> reachable = walkable(site, feet, scaffold);
        Set<Pos> walk = new HashSet<>(reachable);
        walk.retainAll(returnable(site, walk, feet, scaffold, box));
        walk.removeAll(shunned);
        // Of the top layer, one it can break from where it stands first. The nearest first, the last two blocks of a
        // hideout's floor, side by side, the player on one: the one underfoot came first, broken from on the other, the
        // only spot beside it, and there the other was underfoot; it walked between them without end (F-22c8).
        // The top layer is the topmost it can break any of: a block higher up out of reach from every spot it can stand
        // on left that layer with nothing to break, the nearest came first again, and on the farm ward's way under its
        // wall it walked between the floor's last two 1,961 and 1,960 times (#121's CI) (F-22c54).
        Integer top = null;
        for (Pos p : inTheWay) {
            if (site.cell(p).get().kind() == BuildSite.Kind.PROTECTED) continue;
            if (stanceToBreak(site, p, feet, wanted, box, walk).isPresent()) {
                top = p.y();
                break;
            }
        }
        for (Pos p : inTheWay) {
            if (top == null) break;
            if (p.y() > top) continue;
            if (p.y() != top) break;
            if (site.cell(p).get().kind() == BuildSite.Kind.PROTECTED) continue;
            Optional<Pos> stance = stanceToBreak(site, p, feet, wanted, box, walk);
            if (stance.isPresent() && stance.get().equals(feet)) return Step.breaking(p, feet, "In the way of the build");
        }
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
            if (Placement.rule(b.spec()) == Placement.Rule.UNSUPPORTED || Placement.rule(b.spec()) == Placement.Rule.COMPANION) continue;
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
            // Built: the scaffolding comes down, each column from its top, the highest first and of those the nearest.
            // One it cannot reach from here waits while another it can comes down (a suite run stood beside one column
            // and gave up on another across the hut, the higher only by the order the set gave them).
            var tops = scaffoldTops(site, scaffold, feet);
            var down = takeDown(site, tops, feet, wanted, box, walk, "Scaffolding down");
            if (down.isPresent()) return down.get();
            // None in reach from where it can stand beside one: onto a column's own top, if it can walk there (a drop of at
            // most three) and come back by its own blocks, and down the column from on top (F-22c47). In a local class run the house cabin's builder
            // slid off its column after its last slab onto the cabin, diagonal to it and two above its top, out of sight
            // of it; the column's top was a step down, but no stance was ever offered on it, and it waited "out of reach to
            // take down" until its ticks ran out. #113's base cabin (run 42756884925) ended much the same way.
            Set<Pos> ontoTops = new HashSet<>(reachable);
            ontoTops.retainAll(returnable(site, reachable, feet, scaffold, box, spare));
            ontoTops.removeAll(shunned);
            for (Pos high : tops) {
                Pos on = high.up();
                if (ontoTops.contains(on) && site.standable(on) && !wanted.containsKey(on) && !wanted.containsKey(on.up())) {
                    return Step.breaking(high, on, "Scaffolding down, from on top of it");
                }
            }
            // A column left too high to take down from the ground, its taking down cut short (a fall off it), is reached
            // as a block to place is: from the top of a scaffold beside it. A builder fallen off its column three blocks
            // into a hollow climbed back out to a column four high, and its top was "out of reach to take down" eight
            // times, the build stopped (#86's CI, run 42566156809; #78's) (F-22c40).
            for (Pos high : tops) {
                var up = scaffoldToBreak(site, high, feet, wanted, box, walk);
                if (up.isPresent()) return up.get();
            }
            // Still out of reach: from a spot it could not walk back from, if a scaffold of its own blocks would take it back
            // up, the blocks it can spare and the one it gets back from the break (F-22c45). In #121's CI (run 42750254641)
            // its last column stood in a hollow three below the ground; it climbed out on F-22c40's scaffold to take the
            // others down, and the drop back into the hollow was walkable one way only, so the block was left "out of
            // reach to take down" until the build stopped.
            if (!tops.isEmpty()) {
                // Its way back worked out with the tops gone, as they will be: never back up by the block it breaks.
                var gone = new HashMap<Pos, Cell>();
                for (Pos high : tops) gone.put(high, Cell.AIR);
                BuildSite without = site.with(gone);
                var standing = new HashSet<>(scaffold);
                standing.removeAll(tops);
                Set<Pos> outAgain = new HashSet<>(reachable);
                outAgain.retainAll(returnable(without, walkable(without, feet, standing), feet, standing, box, spare + 1));
                outAgain.removeAll(shunned);
                if (!outAgain.equals(walk)) {
                    var fromBelow = takeDown(site, tops, feet, wanted, box, outAgain, "Scaffolding down, a scaffold of its own back out");
                    if (fromBelow.isPresent()) return fromBelow.get();
                }
                return Step.blockedAt(tops.getFirst(), "Scaffolding at " + tops.getFirst() + " is out of reach to take down");
            }
            return Step.done();
        }
        todo.sort(Comparator.<Blueprint.Block>comparingInt(b -> b.at().y()).thenComparingLong(b -> b.at().distanceSquared(feet)));
        // Up its own scaffold, what it went up for first: a block placed from where it stands, before a lower one from
        // somewhere it would have to dig down to walk to (a roof's deck beside the pillar, M-01b).
        if (scaffold.contains(feet.down())) {
            int lowestY = todo.getFirst().at().y();
            for (var b : todo) {
                if (b.at().y() > lowestY + 1) break;
                var place = placement(site, b, feet, wanted, box, List.of(feet), walk);
                if (place.isPresent()) return place.get();
            }
        }
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
        // Hemmed in by its own scaffolding (columns it walked off before they were down, round the cell it stands in):
        // what of it is in reach comes down, the plan looked at again after each, before the block is called out of
        // reach. Called out of reach, its walks round found "no walkable route" eight times, the navigator able to reach
        // only the cell it stood in, and the build stopped short of its roof (#82: a seed cabin's last slab, a spruce
        // cabin's stairs, in CI suites).
        var clearing = takeDown(site, scaffoldTops(site, scaffold, feet), feet, wanted, box, walk,
                "Its own scaffolding in the way to " + lowest.at());
        if (clearing.isPresent()) return clearing.get();
        // Nothing to set it against but a block it set aside: that block first.
        for (var b : todo) {
            for (Face face : Placement.faces(b.spec())) {
                Pos against = b.at().offset(face.opposite());
                if (owed.contains(against)) return Step.blockedAt(against, WAITS_ON_SET_ASIDE + against + " for " + b.spec().block() + " at " + b.at());
            }
        }
        return Step.blockedAt(lowest.at(), "No way to reach " + lowest.spec().block() + " at " + lowest.at());
    }

    /**
     * The tops of its scaffolding's columns still standing, the highest first and of those the nearest: a column comes
     * down from its top.
     */
    private static List<Pos> scaffoldTops(BuildSite site, Set<Pos> scaffold, Pos feet) {
        return scaffold.stream().filter(p -> site.cell(p).map(c -> c.kind() == BuildSite.Kind.SOLID).orElse(false))
                .filter(p -> !scaffold.contains(p.up()) || site.cell(p.up()).map(c -> c.kind() != BuildSite.Kind.SOLID).orElse(true))
                .sorted(Comparator.<Pos>comparingInt(p -> -p.y()).thenComparingLong(p -> p.distanceSquared(feet)).thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** The first of {@code tops} it can break from a spot it can walk to, or from on top of it where it stands there. */
    private static Optional<Step> takeDown(BuildSite site, List<Pos> tops, Pos feet, Map<Pos, BlockSpec> wanted, Box box, Set<Pos> walk,
                                           String reason) {
        for (Pos top : tops) {
            Optional<Pos> stance = stanceToBreak(site, top, feet, wanted, box, walk);
            if (stance.isPresent()) return Optional.of(Step.breaking(top, stance.get(), reason));
            if (feet.equals(top.up())) return Optional.of(Step.breaking(top, feet, reason + ", from on top of it"));
        }
        return Optional.empty();
    }

    /**
     * The placement of {@code b} from the best of {@code stances}, if any: those it can walk to ({@code walk}; null for
     * one it will be stood on by other means, the top of a scaffold).
     */
    private static Optional<Step> placement(BuildSite site, Blueprint.Block b, Pos feet, Map<Pos, BlockSpec> wanted, Box box,
                                            List<Pos> stances, Set<Pos> walk) {
        BlockSpec spec = b.spec();
        // A door's upper half and a bed's head come with it: their cell must be clear for them.
        var partner = Placement.partner(spec, b.at());
        if (partner.isPresent() && !site.cell(partner.get()).map(Cell::placeable).orElse(false)) return Optional.empty();
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
                if (partner.isPresent() && occupies(stance, partner.get())) continue;
                if (wanted.containsKey(stance) || wanted.containsKey(stance.up())) continue; // Never stand where a block is still to go.
                double ex = stance.x() + 0.5, ey = stance.y() + EYE, ez = stance.z() + 0.5;
                double dx = hit[0] - ex, dy = hit[1] - ey, dz = hit[2] - ez;
                double reach = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (reach > REACH - 0.05) continue;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
                // Well inside the facing's quarter, so a body a little off the middle of its spot still faces the same way.
                if (mustFace != null && (Placement.facingOf(yaw) != mustFace || offQuarter(yaw) > FACING_MARGIN)) continue;
                if (!clearSight(site, new double[] {ex, ey, ez}, hit, support, b.at(), false)) continue;
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
            if (!clearSight(site, new double[] {ex, ey, ez}, middle, target, target, true)) continue;
            double cost = Math.sqrt(stance.distanceSquared(feet)) + (box.inside(stance) ? 3 : 0);
            if (cost < bestCost) { bestCost = cost; best = stance; }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Whether {@code ground} is a door's step: beside a door's lower half, level with it or a block below it. A column
     * there shuts the door, and a player indoors while it stands has no way out to take it down. A cabin's column on
     * its doorstep, for the roof over the door, had its player sleep indoors through the night halfway through taking
     * its scaffolding down. Every walk out then found no route, and the build stopped eight steps on (CI on PR #25,
     * `b3f1590`) (F-22c16).
     */
    static boolean doorStep(Pos ground, Map<Pos, BlockSpec> wanted) {
        for (var e : wanted.entrySet()) {
            Pos door = e.getKey();
            if (!dev.kindred.core.Capabilities.current().is(VanillaIds.DOORS, e.getValue().block()) || "upper".equals(e.getValue().property("half"))) continue;
            int dy = door.y() - ground.y();
            if ((dy == 0 || dy == 1) && Math.abs(door.x() - ground.x()) + Math.abs(door.z() - ground.z()) == 1) return true;
        }
        return false;
    }

    /**
     * A scaffold column to stand on to break {@code target}, a block of its scaffold out of reach from the ground: as
     * {@link #scaffold} finds one to place from, just high enough that from its top the block is in reach and in sight.
     */
    private static Optional<Step> scaffoldToBreak(BuildSite site, Pos target, Pos feet, Map<Pos, BlockSpec> wanted, Box box, Set<Pos> walk) {
        Step best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dx = -SCAFFOLD_RADIUS; dx <= SCAFFOLD_RADIUS; dx++) for (int dz = -SCAFFOLD_RADIUS; dz <= SCAFFOLD_RADIUS; dz++) {
            if (dx == 0 && dz == 0) continue;
            Pos column = new Pos(target.x() + dx, target.y(), target.z() + dz);
            Pos ground = null;
            for (int down = 0; down <= MAX_SCAFFOLD + 4; down++) {
                Pos at = column.add(0, -down, 0);
                if (site.standable(at)) { ground = at; break; }
                if (site.cell(at).map(c -> !c.passable()).orElse(true)) break;
            }
            if (ground == null || !walk.contains(ground) || doorStep(ground, wanted)) continue;
            for (int height = 1; height <= Math.min(MAX_SCAFFOLD, target.y() - ground.y()); height++) {
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
                if (stanceToBreak(site.with(raised), target, top, wanted, box, Set.of(top)).isEmpty()) continue;
                bestCost = cost;
                best = new Step(Kind.SCAFFOLD, target, null, null, ground, null, null, null, 0, 0, height,
                        "Scaffold " + height + " up to take down " + target);
                break;
            }
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
            if (doorStep(ground, wanted)) continue;
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
    /**
     * The site with the build's own staircase as footing (M-01b): a stair placed as wanted, with the head room over
     * it kept clear by the blueprint, is walked up though its top is not one full face. A roof's courses, with nothing
     * kept clear over them, are not.
     */
    static BuildSite steps(List<Blueprint.Block> blocks, List<Pos> clear, BuildSite site) {
        if (clear.isEmpty()) return site;
        var keep = new HashSet<>(clear);
        var footing = new java.util.HashMap<Pos, Cell>();
        for (var b : blocks) {
            if (!dev.kindred.core.Capabilities.current().is(VanillaIds.STAIRS, b.spec().block()) || !keep.contains(b.at().up())) continue;
            var cell = site.cell(b.at());
            if (cell.isPresent() && cell.get().kind() == BuildSite.Kind.SOLID && cell.get().state().sameAs(b.spec()) && !cell.get().floor()) {
                footing.put(b.at(), new Cell(BuildSite.Kind.SOLID, cell.get().state(), true));
            }
        }
        return footing.isEmpty() ? site : site.with(footing);
    }

    /** {@link #walkable(BuildSite, Pos, Set)} on the site with the build's own staircase as footing. */
    public static Set<Pos> walkable(List<Blueprint.Block> blocks, List<Pos> clear, BuildSite site, Pos feet, Set<Pos> scaffold) {
        return walkable(steps(blocks, clear, site), feet, scaffold);
    }

    /** Every spot the player can walk to from where it stands, up a block or down a short drop. */
    public static Set<Pos> walkable(BuildSite site, Pos feet, Set<Pos> scaffold) {
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
            for (Face f : SIDES) {
                Pos to = stepTo(site, at, f);
                if (to != null && seen.add(to)) queue.add(to);
            }
        }
        return seen;
    }

    /**
     * The way {@link #walkable(BuildSite, Pos, Set)} reaches {@code goal} from {@code feet}, both ends included; empty
     * when it does not. For the diagnostics of a walk the plan counted on and the navigator found no way for (#153's CI:
     * a hut's floor, "expanded 6000") (F-22c59).
     */
    public static List<Pos> walkRoute(BuildSite site, Pos feet, Pos goal, Set<Pos> scaffold) {
        var parent = new java.util.HashMap<Pos, Pos>();
        var queue = new ArrayDeque<Pos>();
        parent.put(feet, feet);
        queue.add(feet);
        int budget = WALK_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            Pos at = queue.poll();
            if (at.equals(goal)) {
                var route = new ArrayList<Pos>();
                for (Pos p = at; ; p = parent.get(p)) {
                    route.add(p);
                    if (p.equals(feet)) break;
                }
                java.util.Collections.reverse(route);
                return route;
            }
            if (scaffold.contains(at.down()) && site.cell(at.down().down()).map(Cell::floor).orElse(false) && parent.putIfAbsent(at.down(), at) == null) {
                queue.add(at.down());
            }
            for (Face f : SIDES) {
                Pos to = stepTo(site, at, f);
                if (to != null && parent.putIfAbsent(to, at) == null) queue.add(to);
            }
        }
        return List.of();
    }

    private static final List<Face> SIDES = List.of(Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST);

    /** Where one step from {@code at} toward {@code f} lands: up one, level, or down as far as {@link #MAX_DROP}; or null. */
    private static Pos stepTo(BuildSite site, Pos at, Face f) {
        Pos side = at.offset(f);
        for (int dy = 1; dy >= -MAX_DROP; dy--) {
            Pos to = side.add(0, dy, 0);
            // Up a step needs the head room for the jump; down, the body falls through the side's open cells.
            if (dy == 1 && !through(site, at.add(0, 2, 0))) continue;
            if (site.cell(to.down()).map(Cell::floor).orElse(false) && through(site, to) && through(site, to.up())) return to;
            if (dy <= 0 && !through(site, to.up())) break;
        }
        return null;
    }

    /**
     * The spots of {@code walk} from which a player can walk back to the work: to where it stands, or onto the build or
     * the ground a block round it, by steps taken the other way. Spots there count as back already.
     */
    static Set<Pos> returnable(BuildSite site, Set<Pos> walk, Pos feet, Set<Pos> scaffold, Box box) {
        return returnable(site, walk, feet, scaffold, box, 0);
    }

    /**
     * As {@link #returnable(BuildSite, Set, Pos, Set, Box)}, and with {@code blocks} to put up under its own feet, also a spot
     * from which a scaffold of at most that many, and no higher than {@link #MAX_DROP}, takes it up to a step back (F-22c45).
     */
    static Set<Pos> returnable(BuildSite site, Set<Pos> walk, Pos feet, Set<Pos> scaffold, Box box, int blocks) {
        var back = new HashSet<Pos>();
        var queue = new ArrayDeque<Pos>();
        for (Pos p : walk) {
            if (p.equals(feet) || p.x() >= box.minX() - 1 && p.x() <= box.maxX() + 1 && p.z() >= box.minZ() - 1 && p.z() <= box.maxZ() + 1) {
                back.add(p);
                queue.add(p);
            }
        }
        int budget = WALK_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            Pos to = queue.poll();
            // A spot from which one step lands here: beside, from one lower to as far up as a drop.
            for (Face f : SIDES) {
                Pos from = to.offset(f.opposite());
                for (int dy = -1; dy <= MAX_DROP; dy++) {
                    Pos at = from.add(0, dy, 0);
                    if (walk.contains(at) && !back.contains(at) && to.equals(stepTo(site, at, f))) {
                        back.add(at);
                        queue.add(at);
                    }
                }
            }
            // Down a scaffold column it stands on.
            Pos above = to.up();
            if (scaffold.contains(to) && walk.contains(above) && !back.contains(above) && site.cell(to.down()).map(Cell::floor).orElse(false)) {
                back.add(above);
                queue.add(above);
            }
            // With nothing left to look at, a spot a scaffold of its own takes back up from.
            if (queue.isEmpty() && blocks > 0) {
                for (Pos at : walk) {
                    if (!back.contains(at) && pillarsBack(site, at, back, blocks)) {
                        back.add(at);
                        queue.add(at);
                    }
                }
            }
        }
        return back;
    }

    /**
     * Whether a scaffold of at most {@code blocks} under its feet at {@code at}, no higher than {@link #MAX_DROP} (no higher
     * than the drop the walk takes down), lifts the player to where one step lands on a spot of {@code back}: each cell it
     * rises through open, with head room over it.
     */
    private static boolean pillarsBack(BuildSite site, Pos at, Set<Pos> back, int blocks) {
        if (!site.standable(at)) return false;
        var raised = new HashMap<Pos, Cell>();
        for (int h = 1; h <= Math.min(blocks, MAX_DROP); h++) {
            if (!through(site, at.add(0, h, 0)) || !through(site, at.add(0, h + 1, 0))) return false;
            raised.put(at.add(0, h - 1, 0), SCAFFOLD_CELL);
            var with = site.with(raised);
            Pos top = at.add(0, h, 0);
            for (Face f : SIDES) {
                Pos to = stepTo(with, top, f);
                if (to != null && back.contains(to)) return true;
            }
        }
        return false;
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

    /**
     * Nothing solid between the eyes and the point, but the block clicked and the target itself. With {@code outlines}, a
     * break's look: nor a block of no collision with a shape of its own (a torch, a flower, a sign), which the server's
     * look at a block to break meets first. A placement's click is no such look, and sees past them (F-22c58).
     */
    static boolean clearSight(BuildSite site, double[] eye, double[] to, Pos support, Pos target, boolean outlines) {
        // A break's look: every cell the line passes through, in order, as the server's look meets blocks, a corner the line
        // clips for a thirtieth of a block among them. Sampled every eighth of a block, such a corner was stepped over; the
        // server refused the break as out of sight, and a hideout's last block in the way was waited on to the end. A
        // placement's click is no look the server checks, and keeps the sampled line (F-22c58).
        for (Pos at : outlines ? cellsOnLine(eye, to) : sampledLine(eye, to)) {
            if (at.equals(support) || at.equals(target)) continue;
            var cell = site.cell(at);
            if (cell.isEmpty() || !cell.get().passable() && cell.get().kind() != BuildSite.Kind.REPLACEABLE) return false;
            // No bar to the feet but in the eyes' way for a break: a torch, a sign, a flower. The server's look at a block to
            // break meets its shape first and refuses it as out of sight; seen through one, a hideout's last block in the way
            // was refused from every spot it was planned from (#148's CI; F-22c56's diagnostics). Barred for placements too,
            // a pasture ward on a field of flowers was sent to farther spots to place from, and stopped on walks to them
            // (F-22c58).
            if (outlines && cell.get().kind() == BuildSite.Kind.PASSABLE) return false;
        }
        return true;
    }

    /** The cells at every eighth of a block along the segment, its ends left out: the line a placement is planned by. */
    private static List<Pos> sampledLine(double[] eye, double[] to) {
        double dx = to[0] - eye[0], dy = to[1] - eye[1], dz = to[2] - eye[2];
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 8);
        var cells = new ArrayList<Pos>();
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            cells.add(new Pos((int) Math.floor(eye[0] + dx * t), (int) Math.floor(eye[1] + dy * t), (int) Math.floor(eye[2] + dz * t)));
        }
        return cells;
    }

    /**
     * The cells the segment from {@code from} to {@code to} passes through, from the first cell after {@code from}'s own to
     * {@code to}'s, one cell face at a time, in the order and with the ties of vanilla's block raycast (BlockView.raycast):
     * the server's look at a block meets them so.
     */
    static List<Pos> cellsOnLine(double[] from, double[] to) {
        var cells = new ArrayList<Pos>();
        int x = (int) Math.floor(from[0]), y = (int) Math.floor(from[1]), z = (int) Math.floor(from[2]);
        int ex = (int) Math.floor(to[0]), ey = (int) Math.floor(to[1]), ez = (int) Math.floor(to[2]);
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double stepX = sx == 0 ? Double.MAX_VALUE : sx / dx, stepY = sy == 0 ? Double.MAX_VALUE : sy / dy, stepZ = sz == 0 ? Double.MAX_VALUE : sz / dz;
        double nextX = stepX * (sx > 0 ? 1 - (from[0] - Math.floor(from[0])) : from[0] - Math.floor(from[0]));
        double nextY = stepY * (sy > 0 ? 1 - (from[1] - Math.floor(from[1])) : from[1] - Math.floor(from[1]));
        double nextZ = stepZ * (sz > 0 ? 1 - (from[2] - Math.floor(from[2])) : from[2] - Math.floor(from[2]));
        int guard = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z) + 3;
        while ((nextX <= 1 || nextY <= 1 || nextZ <= 1) && guard-- > 0) {
            if (nextX < nextY) {
                if (nextX < nextZ) { x += sx; nextX += stepX; } else { z += sz; nextZ += stepZ; }
            } else if (nextY < nextZ) {
                y += sy; nextY += stepY;
            } else {
                z += sz; nextZ += stepZ;
            }
            cells.add(new Pos(x, y, z));
            if (x == ex && y == ey && z == ez) break;
        }
        return cells;
    }

    /**
     * Whether a player standing at {@code stance} inside the build would be shut in once {@code placing} and every
     * block still wanted were in place: no way out of the build's box through cells that stay clear.
     */
    /** Feet or head may pass: open, or a wooden door, which a walk opens on its way through (M-01b). */
    private static boolean through(BuildSite site, Pos at) {
        return site.cell(at).map(c -> c.passable() || doorway(c.state())).orElse(false);
    }

    /** A wooden door, either half: a way through for a player, unlike an iron one. */
    static boolean doorway(BlockSpec spec) {
        String id = spec.block();
        return dev.kindred.core.Capabilities.current().is(VanillaIds.DOORS, id) && !id.equals(VanillaIds.IRON_DOOR);
    }

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
                if (!seen.add(next) || next.equals(placing)) continue;
                // A door still to go is a way out all the same.
                if (wanted.containsKey(next) && !doorway(wanted.get(next)) || wanted.containsKey(next.up()) && !doorway(wanted.get(next.up()))) continue;
                if (!through(site, next) || !through(site, next.up())) continue;
                queue.add(next);
            }
        }
        return true;
    }

    /** The build's bounding box, by x and z: inside it a player is among the build. */
    record Box(int minX, int minZ, int maxX, int maxZ) {
        /** The box round a set of blocks. */
        static Box of(List<Blueprint.Block> blocks) {
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (var b : blocks) {
                minX = Math.min(minX, b.at().x()); minZ = Math.min(minZ, b.at().z());
                maxX = Math.max(maxX, b.at().x()); maxZ = Math.max(maxZ, b.at().z());
            }
            return new Box(minX, minZ, maxX, maxZ);
        }

        /** Whether a spot lies within the box. */
        boolean inside(Pos p) {
            return p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ;
        }
    }
}
