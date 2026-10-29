package dev.kindred.fabric;

import dev.kindred.core.construction.BlockSpec;
import dev.kindred.core.construction.BuildNeeds;
import dev.kindred.core.construction.Blueprint;
import dev.kindred.core.construction.BuildSite;
import dev.kindred.core.construction.ConstructionPlanner;
import dev.kindred.core.construction.Placement;
import dev.kindred.core.construction.Pos;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In plain terms: an AI player building something from a plan, block by block, with its own blocks: walking
 * to where it can reach, placing each block the right way round, clearing what is in the way, and putting up
 * and taking down scaffolding. When short of blocks it builds what it can and then fetches, gathers or crafts
 * the rest. What is built is read from the world, so it can pick up again where it left off.
 *
 * Technical: A build in progress for one player (E-08b): a blueprint placed at an origin and turned, built
 * from the player's own blocks one recorded step at a time. Each pass it looks at the world round the build,
 * asks the construction planner (E-08a) for the next step and turns it into a task for the runtime's own
 * skills: walk to the spot to stand, place a block with the look that gives its state, break what is in the
 * way, pillar a scaffold, take the scaffold down. Progress is read from the world, so nothing but the project
 * itself is remembered: what is built is built.
 */
final class BuildRunner {
    /** Blocks it scaffolds with, the cheapest first: none of them is ever taken from what the build needs. */
    private static final List<Item> SCAFFOLD = List.of(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE);
    /** A block placed in the wrong state twice is left for the operator, not fought over. */
    private static final int TRIES = 2;
    /** How far round the build the world is looked at. */
    private static final int MARGIN = 6;
    /** The label of a prop's placement. */
    private static final String PROP = "Prop at ";
    /** Tasks failing one after another before the build stops and says why. */
    private static final int STUCK = 8;

    final Blueprint blueprint;
    final BlockPos origin;
    final int turns;
    private final List<Blueprint.Block> blocks;
    private final List<Pos> clear;
    private final Set<Pos> scaffold = new HashSet<>();
    private final Map<Pos, Integer> wrong = new HashMap<>();
    private final Set<Pos> givenUp = new HashSet<>();
    private final Map<Pos, Integer> refused = new HashMap<>();
    /** Whether it has gone back once, all else built, to what it gave up on as it went. */
    private boolean retriedGivenUp;
    /** Spots where the server refused a step the plan thought sound; avoided until the build gets on again. */
    private final Set<Pos> shunned = new HashSet<>();
    /** Where the feet stood for the place or break last given. */
    private Pos issuedFrom;
    /** A break it is walking to, and where from: broken on arriving, not planned again from there. */
    private Pos breakTarget, breakStance;
    private int breakWalks;
    /** A placement it walked over to make, to be made there, as it was planned when it set out. */
    private ConstructionPlanner.Step placeStep;
    /** The last two blocks it set out to place, to see it walk to and fro between them. */
    private Pos lastPlaceTo, placeToBefore;
    /** The spot it last walked to, to place from, and how often in a row the walk ended short of standing just there. */
    private Pos lastStance;
    private int stanceWalks;
    /** Walks in a row to one spot to place from, never there by the plan's reckoning, before that spot is avoided. */
    private static final int WALKS_TO_A_SPOT = 12;
    /** Waits in a row for a landing before it looks for a way round a block it cannot reach. */
    private int airWaits;
    private int failures;
    private String lastFailure = "";
    /** A walk round to the ground beside what it cannot reach from where it is, and how many such walks in a row found no way. */
    private static final String OVER = "; over to it from ";
    private int overFailed;
    /**
     * After a step fails, a second's pause before the next, so that eight failures in a row take eight seconds at
     * least: tried again every tick, eight route searches failed within a second of its coming down off its scaffold,
     * and stopped a cabin short of its roof (the seed fixtures, 2026-10-24). The pause is no step of its own.
     */
    private static final String PAUSE = "A moment before trying again";
    private boolean pause;
    /** The scaffold being put up: its foot and how high it is to go. */
    private Pos column;
    private int columnTop;
    private String progress = "Starting";
    private boolean done;

    /** A build of the given plan at the given spot, turned the given number of quarter turns. */
    BuildRunner(Blueprint blueprint, BlockPos origin, int turns) {
        this.blueprint = blueprint;
        this.origin = origin.toImmutable();
        this.turns = Math.floorMod(turns, 4);
        this.blocks = blueprint.placed(pos(origin), this.turns);
        this.clear = blueprint.placedClear(pos(origin), this.turns);
    }

    String progress() { return progress; }
    List<Blueprint.Block> blocks() { return blocks; }

    /** Item counts in the inventory, by id. */
    static Map<String, Integer> counts(ServerPlayerEntity player) {
        var counts = new HashMap<String, Integer>();
        for (int s = 0; s < 36; s++) {
            ItemStack stack = player.getInventory().getStack(s);
            if (!stack.isEmpty()) counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return counts;
    }
    boolean done() { return done; }

    /**
     * What a build's needs draw on (E-08c): the run's own gathering and crafting, and the player's own assigned
     * storage, if it has one (null otherwise); the animals it never hunts for them (its ranch's), and the iron its run
     * keeps for itself, which shears are not made from.
     */
    record Supply(PortalRunCampaign campaign, BlockPos storage, java.util.function.Predicate<net.minecraft.entity.passive.PassiveEntity> spare, int ironKept) {
        /** Supplies from the run and the given storage, sparing no animals and keeping no iron back. */
        Supply(PortalRunCampaign campaign, BlockPos storage) {
            this(campaign, storage, animal -> false, 0);
        }
    }

    /** How far its own storage may be for stepping over to it to count as easy. */
    private static final int STORAGE_NEAR = 24;

    private final BuildNeeds needs = new BuildNeeds();
    private PortalRunCampaign.Task needsTask;
    private String needsNote = "";
    /** What it cannot get for the rest of the build, once nothing it can do gets it; null while it still can. */
    private String wanting;
    /**
     * Its own site, never a source of blocks for it: the build's box, a block round it, and three below it, so that
     * stone mined for the walls never digs out the ground the floor stands on.
     */
    private boolean onSite(Pos p) {
        if (box == null) {
            box = new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (var b : blocks) {
                box[0] = Math.min(box[0], b.at().x()); box[1] = Math.min(box[1], b.at().y()); box[2] = Math.min(box[2], b.at().z());
                box[3] = Math.max(box[3], b.at().x()); box[4] = Math.max(box[4], b.at().y()); box[5] = Math.max(box[5], b.at().z());
            }
        }
        return p.x() >= box[0] - 1 && p.x() <= box[3] + 1 && p.z() >= box[2] - 1 && p.z() <= box[5] + 1 && p.y() >= box[1] - 3 && p.y() <= box[4];
    }

    /** The build's box, min x y z then max x y z, once worked out. */
    private int[] box;

    private static Pos feet(ServerPlayerEntity player) { return pos(player.getBlockPos()); }

    /**
     * Up from where it dug for stone to the ground round the site: by a route that digs, bridges or pillars where
     * walking will not do (F-10e), its own staircase perhaps mined away; else up as the goal climbs out.
     */
    private Optional<PortalRunCampaign.Task> backUp(NativePlayerBody body, Supply supply) {
        Pos feet = feet(body.entity());
        onSite(feet);
        int floor = siteFloor();
        BlockPos toward = new BlockPos(Math.max(box[0] - 1, Math.min(box[3] + 1, feet.x())), floor, Math.max(box[2] - 1, Math.min(box[5] + 1, feet.z())));
        var route = supply.campaign().buildRoute(body, toward, p -> {
            Pos at = pos(p);
            return p.getY() >= floor && !onSite(at) && nearSite(at);
        }, false, "Back up to the build");
        return route.isPresent() ? route : supply.campaign().climbOut(body);
    }

    /** The square of the distance across the ground between two cells, height aside. */
    private static int flat(Pos a, Pos b) {
        int dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    /** Up off the ground on the build: over its floor by more than a step, within its box. */
    private boolean up(Pos feet) {
        onSite(feet);
        return feet.y() > siteFloor() + 1 && feet.x() >= box[0] && feet.x() <= box[3] && feet.z() >= box[2] && feet.z() <= box[5];
    }

    /**
     * To {@code spot} outside the build, and no nearer than a block of it: a walk that may end anywhere within reach of
     * its spot can end inside the walls, from where the roof has no stance.
     */
    private PortalRunCampaign.Task outside(Pos spot, String reason) {
        progress = blueprint.name() + ": " + reason;
        return new PortalRunCampaign.Task("native_goto", Map.of("x", str(spot.x()), "y", str(spot.y()), "z", str(spot.z()), "range", "1.0"), reason);
    }

    /**
     * Ground to stand on outside the build near {@code at}: the spot two blocks out from the side nearest it, at the
     * floor's height, or where that is no place to stand (a tree's trunk, a fence, water), the nearest place round it
     * that is, a block up or down at most. Aimed at a spot no one could stand on, the walk was "no walkable route" eight
     * times, and a cabin in the woods stopped short of its roof (a seed fixture, in a suite run).
     */
    private Pos groundBeside(Pos at, ServerWorld world) {
        return groundBeside(at, world, 0);
    }

    /**
     * As {@link #groundBeside(Pos, ServerWorld)}, from the {@code side}-th nearest side of the build (0 the nearest, of
     * four): a walk round to the nearest side that found no way is tried next at the one after (#82: a cabin's roof
     * stairs, eight walks in a row to the same spot "no walkable route", in suite runs 40 and 41).
     */
    private Pos groundBeside(Pos at, ServerWorld world, int side) {
        Pos want = groundBeside(at, side);
        for (int r = 0; r <= 3; r++) {
            Pos best = null;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int dy : new int[] {0, 1, -1}) {
                    Pos p = want.add(dx, dy, dz);
                    if (onSite(p) || !standable(world, blockPos(p))) continue;
                    if (best == null || flat(p, want) < flat(best, want)) best = p;
                    break;
                }
            }
            if (best != null) return best;
        }
        return want;
    }

    /**
     * Whether the block at {@code at} is one of its own it can dig out from under itself: a scaffold block of the kinds it
     * scaffolds with, above the build's floor, and none the plan places there.
     */
    private boolean offPlan(Pos at, ServerWorld world) {
        if (at.y() < siteFloor()) return false;
        var item = world.getBlockState(blockPos(at)).getBlock().asItem();
        if (!SCAFFOLD.contains(item)) return false;
        for (var b : blocks) if (b.at().equals(at)) return false;
        return true;
    }

    /** Ground with a full top under {@code feet}, and room for a player's body above it. */
    private static boolean standable(ServerWorld world, BlockPos feet) {
        if (!world.isChunkLoaded(feet)) return false;
        BlockPos below = feet.down();
        return world.getBlockState(below).isSideSolidFullSquare(world, below, net.minecraft.util.math.Direction.UP)
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() && world.getFluidState(feet).isEmpty()
                && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty();
    }

    /** The ground two blocks outside the build nearest {@code at}, clear of scaffolds against its walls, at the floor's height. */
    private Pos groundBeside(Pos at) {
        return groundBeside(at, 0);
    }

    /** The ground two blocks outside the {@code side}-th nearest side of the build to {@code at} (0 the nearest), at the floor's height. */
    private Pos groundBeside(Pos at, int side) {
        onSite(at);
        int x = Math.max(box[0], Math.min(box[3], at.x())), z = Math.max(box[2], Math.min(box[5], at.z()));
        // West, east, north and south, by how far each side is from it: the nearest first, ties in that order.
        int[] far = {x - box[0], box[3] - x, z - box[2], box[5] - z};
        Integer[] sides = {0, 1, 2, 3};
        java.util.Arrays.sort(sides, java.util.Comparator.comparingInt(i -> far[i]));
        switch (sides[Math.floorMod(side, 4)]) {
            case 0 -> x = box[0] - 2;
            case 1 -> x = box[3] + 2;
            case 2 -> z = box[2] - 2;
            default -> z = box[5] + 2;
        }
        // At the floor's height, not its lowest block's: a tower's foundation is a course under the ground, and its walk
        // over to a block aimed into the ground, "no walkable route", eight times, and stopped (the hall's south-east
        // tower, M-06a).
        return new Pos(x, siteFloor(), z);
    }

    /**
     * The height of the build's floor: its lowest course, or its origin's height where it goes below that, as the
     * keep's way in under its wall does (M-06a). Measured from its lowest block, a player on a scaffold beside the
     * keep's cabin was "up on the build" and waited there rather than walk round to a roof stair it could not reach.
     */
    private int siteFloor() {
        onSite(new Pos(0, 0, 0));
        return Math.max(box[1], origin.getY());
    }

    /** Within a few blocks of the site, where a staircase dug down for stone could run under it. */
    private boolean nearSite(Pos feet) {
        onSite(feet);
        return feet.x() >= box[0] - 4 && feet.x() <= box[3] + 4 && feet.z() >= box[2] - 4 && feet.z() <= box[5] + 4
                && feet.y() >= box[1] - 4 && feet.y() <= box[4] + 1;
    }

    /**
     * A spot eight blocks out from the site's side nearest {@code feet}, at the floor's height: somewhere to dig, clear
     * of {@link #nearSite} however short of it the walk there ends (2.5 blocks).
     */
    private Pos offSite(Pos feet) {
        onSite(feet);
        int west = feet.x() - box[0], east = box[3] - feet.x(), north = feet.z() - box[2], south = box[5] - feet.z();
        int least = Math.min(Math.min(west, east), Math.min(north, south));
        int x = Math.max(box[0], Math.min(box[3], feet.x())), z = Math.max(box[2], Math.min(box[5], feet.z()));
        if (least == west) x = box[0] - OFF_SITE;
        else if (least == east) x = box[3] + OFF_SITE;
        else if (least == north) z = box[2] - OFF_SITE;
        else z = box[5] + OFF_SITE;
        return new Pos(x, box[1], z);
    }

    private static final int OFF_SITE = 8;

    /** The label of a pillar step up the build's own scaffold. */
    private static final String UP_THE_SCAFFOLD = "Up the scaffold";

    /** The blocks it has stood on below its build's floor, digging for stone: its own staircase, never mined. */
    private final Set<Pos> stood = new HashSet<>();

    /** How far under the floor it digs for stone before it tunnels along. */
    private static final int STONE_DEPTH = 6;

    /** One-second waits for a way up out of where it dug, before it gives the rest of the build up. */
    private int belowWaits;
    private static final int BELOW_WAITS = 30;
    /**
     * Times it has waited, with no way on to stone, for the spots it set aside to come free (about a minute's worth)
     * before the build ends without its stone.
     */
    private int stoneWaits;
    private static final int STONE_WAITS = 60;

    private boolean complete;

    /** Whether the build ended whole: every block in its state, nothing left wanting. */
    boolean complete() { return complete; }
    /** Walks out to look for what the trip still wants and has not seen; at most {@link #LOOKS} a trip. */
    private int looks;
    private static final int LOOKS = 4;

    /** Whether {@code task} was one the build's needs gave (taken, crafted or gathered), not a placement. */
    boolean needsTask(PortalRunCampaign.Task task) {
        return task != null && task.equals(needsTask);
    }

    /** What the build's needs are about, for its progress line. */
    String needsNote() { return needsNote; }

    private int trips;

    /** How many trips it has set out on for the build's shortfall. */
    int trips() { return trips; }

    /** The scaffold it put up and has still to take down, as it stands now. */
    Set<Pos> scaffold() { return Set.copyOf(scaffold); }

    /**
     * Scaffold it put up before a restart, to take down as its own when the build is done (F-13). A block no longer
     * standing there is dropped at the next step, as one taken down is.
     */
    void restoreScaffold(Set<Pos> standing) { scaffold.addAll(standing); }

    /** The next task, or empty when the build is done. */
    Optional<PortalRunCampaign.Task> next(NativePlayerBody body) {
        return next(body, null);
    }

    /**
     * The next task, or empty when the build is done. With a {@code supply}, a build short of blocks builds what it can
     * with what it holds, then fetches the whole shortfall in one trip (its own storage, then what it gathers and
     * crafts), taking at once what its storage close by holds and making at once what it can from what it carries.
     */
    Optional<PortalRunCampaign.Task> next(NativePlayerBody body, Supply supply) {
        if (done) return Optional.empty();
        if (failures >= STUCK) {
            done = true;
            progress = blueprint.name() + ": stopped, " + STUCK + " steps failed one after another (last: " + lastFailure + ")";
            return Optional.empty();
        }
        if (pause) {
            pause = false;
            return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), PAUSE));
        }
        var player = body.entity();
        var world = player.getServerWorld();
        if (player.isOnGround()) airWaits = 0;
        var site = snapshot(world);
        Pos feet = standing(player, site);
        // Scaffolding it no longer finds standing (broken by another, or a pillar step that did not land) is forgotten.
        scaffold.removeIf(p -> site.cell(p).map(c -> c.kind() != BuildSite.Kind.SOLID).orElse(false));
        var wanted = blocks.stream().filter(b -> !givenUp.contains(b.at())).toList();
        var items = held(player);
        // A scaffold being put up is finished first: the planner looks again only from its top.
        if (column != null) {
            Pos cell = pos(player.getBlockPos());
            boolean onColumn = cell.x() == column.x() && cell.z() == column.z() && cell.y() < columnTop;
            if (onColumn && !player.isOnGround()) return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Landing on the scaffold"));
            if (onColumn) {
                int slot = scaffoldSlot(player, wanted, site);
                if (slot < 0) return Optional.of(wait("No blocks to spare for a scaffold"));
                if (slot >= 9) return Optional.of(swap(player, slot, "Ready a block for the scaffold"));
                progress = blueprint.name() + ": up the scaffold to " + columnTop;
                return Optional.of(new PortalRunCampaign.Task("native_pillar", Map.of("x", str(cell.x()), "y", str(cell.y()), "z", str(cell.z()),
                        "slot", str(slot)), UP_THE_SCAFFOLD));
            }
            if (feet.y() >= columnTop || !(feet.x() == column.x() && feet.z() == column.z())) column = null;
        }
        // Back from a trip down for stone with all it went for: out of the ground first, as the goal climbs out. Only
        // from where it dug, begun off the site: a hollow beside the build (a blast's crater) is where it works from.
        if (supply != null && !needs.onTrip() && !onSite(feet) && !nearSite(feet) && feet.y() < siteFloor()) {
            var out = backUp(body, supply);
            if (out.isPresent()) {
                needsTask = out.get();
                progress = blueprint.name() + ": climbing out to the build";
                return out;
            }
        }
        // A break it set out for is walked to the end and made there, not planned again on the way. Planned again, the
        // block underfoot came first, to be broken from the far end of a passage it was digging: it walked there, and
        // there the block underfoot came first again, back at the other end, so it walked to and fro breaking neither
        // (the keep, M-06a). Arrived is standing in the spot, as the plan reckons where it stands.
        if (breakTarget != null) {
            Pos target = breakTarget, from = breakStance;
            boolean there = !world.getBlockState(blockPos(target)).isAir();
            if (there && feet.equals(from) && player.isOnGround()) {
                breakTarget = breakStance = null;
                issuedFrom = from;
                return Optional.of(breakTask(target, feet, "The break it walked over to"));
            }
            if (there && ++breakWalks <= 8) {
                return Optional.of(player.isOnGround() ? go(from, "To break " + target + " from " + feet)
                        : new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Landing to break " + target));
            }
            breakTarget = breakStance = null;
        }
        var walkedTo = placeWalkedTo(player, site);
        var step = walkedTo != null ? walkedTo : ConstructionPlanner.next(wanted, clear, site, feet, items, scaffold, shunned);
        if (supply != null && step.kind() != ConstructionPlanner.Kind.DONE) {
            var fetch = fetch(body, supply, wanted, site, step);
            if (fetch.isPresent()) {
                needsTask = fetch.get();
                var tally = ConstructionPlanner.progress(blocks, clear, site);
                progress = blueprint.name() + ": " + tally.placed() + "/" + tally.total() + " placed - " + needsNote;
                return fetch;
            }
            // Built all it can, and nothing it can do gets the rest: it ends there and says what the build still wants,
            // rather than wait on blocks that will not come.
            if (wanting != null && step.kind() == ConstructionPlanner.Kind.BLOCKED) {
                done = true;
                var tally = ConstructionPlanner.progress(blocks, clear, site);
                progress = blueprint.name() + ": built but for " + wanting + ", " + tally.placed() + "/" + tally.total() + " blocks";
                return Optional.empty();
            }
        }
        if (step.kind() == ConstructionPlanner.Kind.PLACE || step.kind() == ConstructionPlanner.Kind.BREAK
                || step.kind() == ConstructionPlanner.Kind.SUPPORT) issuedFrom = step.stance();
        var tally = ConstructionPlanner.progress(blocks, clear, site);
        progress = blueprint.name() + ": " + tally.placed() + "/" + tally.total() + " placed - " + step.reason();
        // Wanted elsewhere while on its own scaffold: down it first, digging out the block underfoot, as a player gets
        // off a pillar too high to jump from.
        Pos under = feet.down();
        if (scaffold.contains(under) && player.isOnGround() && site.cell(under).map(c -> c.kind() == BuildSite.Kind.SOLID).orElse(false) && (step.kind() == ConstructionPlanner.Kind.PLACE
                || step.kind() == ConstructionPlanner.Kind.SUPPORT
                || step.kind() == ConstructionPlanner.Kind.SCAFFOLD || step.kind() == ConstructionPlanner.Kind.BREAK) && !feet.equals(step.stance())) {
            return Optional.of(new PortalRunCampaign.Task("native_break", Map.of("x", str(under.x()), "y", str(under.y()), "z", str(under.z()),
                    "collect", "true", "underfoot", "true"), "Down the scaffold"));
        }
        return switch (step.kind()) {
            case DONE -> {
                // What it gave up on as it went (a break refused while its own scaffold stood on the cell, a placement
                // refused while it stood in it) is tried once more when all else is built: what was in the way is
                // likely gone. A cabin's floor plank so left had kept a base unfinished until its retry five minutes on.
                if (!givenUp.isEmpty() && !retriedGivenUp && tally.placed() < tally.total()) {
                    retriedGivenUp = true;
                    givenUp.clear();
                    refused.clear();
                    wrong.clear();
                    progress = blueprint.name() + ": " + tally.placed() + "/" + tally.total() + " placed - once more at what it gave up on";
                    yield Optional.of(wait("Once more at what it gave up on"));
                }
                done = true;
                complete = tally.placed() == tally.total();
                progress = blueprint.name() + ": built, " + tally.placed() + "/" + tally.total() + " blocks"
                        + (tally.unsupported().isEmpty() ? "" : ", without " + String.join(", ", tally.unsupported()));
                yield Optional.empty();
            }
            // Out of reach from here of something it can see: over to it, by the navigator's own way, to plan again
            // from beside it. Anything else is waited out.
            // Over to the ground beside it, never onto the build itself (a walk to a roof's cell walks onto the roof); and
            // stuck up on the build, back down to the ground beside it.
            // In the air (just down off its scaffold), it lands first: every route looked for from mid-fall was "no
            // walkable route", and eight in a row within a second stopped a cabin short of its roof (a seed fixture,
            // and the nine by seven cabin in full run 19).
            case BLOCKED -> !player.isOnGround() && ++airWaits <= 5 ? Optional.of(wait("Landing first"))
                    // Walks round failing from up on a block that is none of the build's (a scaffold's foot it lost count
                    // of, a pillar's): off it, the block underfoot dug out, as a player steps off a stray block (#82).
                    : overFailed >= 2 && player.isOnGround() && offPlan(feet.down(), world) ? Optional.of(new PortalRunCampaign.Task("native_break",
                            Map.of("x", str(feet.down().x()), "y", str(feet.down().y()), "z", str(feet.down().z()), "collect", "true", "underfoot", "true"),
                            "Off what it stands on"))
                    : step.target() != null && flat(feet, step.target()) > 9 && !up(feet)
                    ? Optional.of(outside(groundBeside(step.target(), world, overFailed), step.reason() + OVER + feet))
                    : step.target() != null && up(feet) && !scaffold.contains(feet.down())
                    ? Optional.of(outside(groundBeside(feet, world), step.reason() + "; down off the build first"))
                    : Optional.of(wait(step.reason() + " (standing at " + feet + ")"));
            case BREAK -> {
                // Its own scaffold's block in the column it stands on, wanted gone: dug out from on top, the block underfoot
                // first, never a walk to the ground beside from up a pillar it cannot step down from. Asked for such a
                // walk, eight in a row found "no walkable route" and stopped a cabin with all its blocks placed (#82; suite
                // run 49, and runs 22 and 48 before it).
                if (scaffold.contains(step.target()) && step.target().x() == feet.x() && step.target().z() == feet.z() && step.target().y() < feet.y()) {
                    if (!player.isOnGround()) yield Optional.of(wait("Landing first"));
                    yield Optional.of(new PortalRunCampaign.Task("native_break", Map.of("x", str(under.x()), "y", str(under.y()), "z", str(under.z()),
                            "collect", "true", "underfoot", "true"), "Down the scaffold"));
                }
                if (!at(player, step.stance())) {
                    breakTarget = step.target();
                    breakStance = step.stance();
                    breakWalks = 0;
                    yield Optional.of(go(step.stance(), "To break " + step.target() + " from " + feet));
                }
                yield Optional.of(breakTask(step.target(), feet, step.reason()));
            }
            case SCAFFOLD -> {
                if (!at(player, step.stance())) yield Optional.of(go(step.stance(), "To the foot of a scaffold from " + feet));
                column = step.stance();
                columnTop = step.stance().y() + step.height();
                yield next(body);
            }
            case SUPPORT -> {
                if (!at(player, step.stance())) yield Optional.of(go(step.stance(), "To a spot to prop " + step.target() + " from " + feet));
                int slot = scaffoldSlot(player, wanted, site);
                if (slot < 0) yield Optional.of(wait("No blocks to spare for a prop"));
                if (slot >= 9) yield Optional.of(swap(player, slot, "Ready a block for a prop"));
                yield Optional.of(new PortalRunCampaign.Task("native_place", Map.of("x", str(step.target().x()), "y", str(step.target().y()),
                        "z", str(step.target().z()), "side", step.face().id(), "slot", str(slot),
                        "yaw", String.format(Locale.ROOT, "%.3f", step.yaw()), "pitch", String.format(Locale.ROOT, "%.3f", step.pitch()),
                        "hit", String.format(Locale.ROOT, "%.4f,%.4f,%.4f", step.hit()[0], step.hit()[1], step.hit()[2])),
                        PROP + step.target()));
            }
            case PLACE -> {
                // Walked to the same spot three times over, its feet in that spot's own block but never standing just
                // there (on a stair it had just placed, the walk called it there and the runner did not, 7040 times, in
                // the hall): placed from where it stands, the placement aimed from its own eyes. Short of the block, it
                // walks on as before.
                boolean walkedThere = step.stance().equals(lastStance) && stanceWalks >= 3 && player.isOnGround()
                        && standing(player).equals(step.stance());
                if (!walkedThere && !at(player, step.stance())) {
                    // Walked to the same spot a dozen times and never there by the plan's reckoning (the hut's last
                    // stair from its scaffold, in a suite run): that spot is avoided until the build gets on, and
                    // another found to place from.
                    if (step.stance().equals(lastStance) && stanceWalks > WALKS_TO_A_SPOT) {
                        shunned.add(step.stance());
                        stanceWalks = 0;
                        lastStance = null;
                        yield Optional.of(wait("Another spot to place " + shortName(step.spec()) + " from, not " + step.stance()));
                    }
                    // Set out again for the block it set out for two walks ago, another between: it walks to and fro,
                    // and this time places it where it walks to. Committed to every placement walked to, the keep's
                    // last roof blocks, and a cabin's, came to be left where no scaffold reached them. Asked again for
                    // the same walk before it set out, as the runtime may ask, nothing changes.
                    if (!step.target().equals(lastPlaceTo)) {
                        if (step.target().equals(placeToBefore)) placeStep = step;
                        placeToBefore = lastPlaceTo;
                        lastPlaceTo = step.target();
                    }
                    yield Optional.of(go(step.stance(), "To " + step.stance() + " to place " + shortName(step.spec()) + " at " + step.target() + " from " + feet));
                }
                lastStance = null;
                stanceWalks = 0;
                Item item = Registries.ITEM.get(Identifier.of(step.item()));
                int slot = slotOf(player, item);
                if (slot < 0) yield Optional.of(wait("No " + shortName(step.spec()) + " in hand"));
                if (slot >= 9) yield Optional.of(swap(player, slot, "Ready " + shortName(step.spec())));
                yield Optional.of(new PortalRunCampaign.Task("native_place", Map.of("x", str(step.target().x()), "y", str(step.target().y()),
                        "z", str(step.target().z()), "side", step.face().id(), "slot", str(slot),
                        "yaw", String.format(Locale.ROOT, "%.3f", step.yaw()), "pitch", String.format(Locale.ROOT, "%.3f", step.pitch()),
                        "hit", String.format(Locale.ROOT, "%.4f,%.4f,%.4f", step.hit()[0], step.hit()[1], step.hit()[2])),
                        "Place " + shortName(step.spec()) + " at " + step.target()));
            }
        };
    }

    /** The build's needs this pass, as a task, or empty to go on building. */
    private Optional<PortalRunCampaign.Task> fetch(NativePlayerBody body, Supply supply, List<Blueprint.Block> wanted, BuildSite site,
                                                   ConstructionPlanner.Step step) {
        var player = body.entity();
        var held = counts(player);
        var bill = dev.kindred.core.construction.BillOfMaterials.of(wanted, site, held);
        wanting = null;
        if (bill.missing().isEmpty()) {
            needs.allInHand();
            needsNote = "";
            return Optional.empty();
        }
        String missing = bill.missing().entrySet().stream().map(e -> e.getValue() + " " + shortName(e.getKey()))
                .collect(java.util.stream.Collectors.joining(", "));
        boolean placeable = step.kind() == ConstructionPlanner.Kind.PLACE || step.kind() == ConstructionPlanner.Kind.SUPPORT
                || step.kind() == ConstructionPlanner.Kind.BREAK || step.kind() == ConstructionPlanner.Kind.SCAFFOLD;
        Map<String, Integer> stored = Map.of();
        boolean near = false;
        if (supply.storage() != null && player.getServerWorld().isChunkLoaded(supply.storage())
                && player.getServerWorld().getBlockEntity(supply.storage()) instanceof Inventory chest
                && player.getBlockPos().getSquaredDistance(supply.storage()) <= 64 * 64) {
            stored = contents(chest);
            near = player.getBlockPos().getSquaredDistance(supply.storage()) <= STORAGE_NEAR * STORAGE_NEAR;
        }
        boolean pickaxe = false;
        for (int s = 0; s < 36; s++) if (player.getInventory().getStack(s).isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) pickaxe = true;
        boolean wasOnTrip = needs.onTrip();
        var next = needs.next(bill.needed(), held, placeable, stored, near, wood(), pickaxe);
        if (!wasOnTrip && needs.onTrip()) {
            trips++;
            looks = 0;
            KindredMod.LOGGER.info("Build {}: out for the rest of the build, trip {}: {}", blueprint.name(), trips, bill.missing());
        }
        switch (next) {
            case BuildNeeds.Step.Build b -> {
                needsNote = needs.onTrip() ? needsNote : "";
                return Optional.empty();
            }
            case BuildNeeds.Step.Take t -> {
                var first = t.items().entrySet().iterator().next();
                needsNote = t.why() + ": " + first.getValue() + " " + shortName(first.getKey());
                Vec3d centre = Vec3d.ofCenter(supply.storage());
                if (player.getEyePos().squaredDistanceTo(centre) > 4.2 * 4.2) return Optional.of(near(pos(supply.storage()), "To its storage for " + shortName(first.getKey())));
                return Optional.of(new PortalRunCampaign.Task("native_take", Map.of("item", first.getKey(), "count", str(first.getValue())),
                        "Take " + first.getValue() + " " + shortName(first.getKey()) + " from its storage"));
            }
            case BuildNeeds.Step.Craft c -> {
                needsNote = c.why() + ": " + shortName(c.recipe().item());
                return supply.campaign().craftAtTable(body, Identifier.of(c.recipe().item()).getPath());
            }
            case BuildNeeds.Step.Gather g -> {
                needsNote = g.why() + " (" + g.count() + ")";
                if (g.item().endsWith("_wool")) return wool(body, supply, g.item(), missing);
                var kind = g.item().endsWith("_log") ? WorldKnowledge.Kind.LOG : WorldKnowledge.Kind.STONE;
                String what = kind == WorldKnowledge.Kind.LOG ? "logs" : "stone";
                // Stone as the goal gets it: close by, else a staircase down, begun off the build's site.
                if (kind == WorldKnowledge.Kind.STONE) {
                    Pos feet = pos(player.getBlockPos());
                    if (nearSite(feet)) return Optional.of(near(offSite(feet), "Off its site to dig for stone"));
                    // Down to a fixed depth under the floor, then along it: not deeper each time it runs out in sight.
                    // Never the steps it stood on going down: they are its way back up.
                    if (feet.y() < siteFloor()) stood.add(feet.down());
                    var dig = supply.campaign().stoneFor(body, siteFloor() - STONE_DEPTH, p -> !onSite(pos(p)) && !stood.contains(pos(p)));
                    // No way on from here, every stone in sight walled off from walking and every step down refused: a
                    // tunnel straight on away from the site, else a wait for the spots it set aside to come free, before
                    // the build ends without its stone (a cabin ended so with 61 of its 62 cobblestone in hand).
                    if (dig.isEmpty() && feet.y() < siteFloor()) {
                        onSite(feet);
                        int dx = feet.x() - (box[0] + box[3]) / 2, dz = feet.z() - (box[2] + box[5]) / 2;
                        Direction away = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
                        dig = supply.campaign().tunnelAlong(body, away, "Dig for stone");
                    }
                    if (dig.isEmpty() && ++stoneWaits < STONE_WAITS) {
                        needsNote = "Finding a way on to stone";
                        return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Finding a way on to stone"));
                    }
                    if (dig.isPresent()) stoneWaits = 0;
                    if (dig.isEmpty()) {
                        needsNote = "No way to stone from here for the rest of the build";
                        wanting = missing;
                    }
                    return dig;
                }
                // Trees are found from the ground: up out of where it dug for stone first.
                if (feet(player).y() < siteFloor()) {
                    var out = backUp(body, supply);
                    if (out.isPresent()) {
                        belowWaits = 0;
                        needsNote = "Out of the ground for " + what;
                        return out;
                    }
                    // A way up refused for now (a step that did not land is set aside a while): it waits and tries
                    // again, rather than end the build for want of trees it cannot see from below.
                    if (++belowWaits < BELOW_WAITS) {
                        needsNote = "Finding a way up for " + what;
                        return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Finding a way up for " + what));
                    }
                }
                // Logs of its own wood: a cherry build is not made of the oak beside it (E-29a).
                String log = g.item();
                var world = player.getServerWorld();
                var task = supply.campaign().gather(body, kind, 64, what, p -> !onSite(pos(p)) && (kind != WorldKnowledge.Kind.LOG
                        || net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(p).getBlock()).toString().equals(log)));
                // None in sight: out over the ground to look, a few times, before the build ends without them.
                if (task.isEmpty() && looks < LOOKS) {
                    task = supply.campaign().lookFor(body, what);
                    if (task.isPresent()) needsNote = "Looking for " + what + " for the rest of the build";
                }
                if (task.isEmpty()) {
                    needsNote = "No " + shortName(g.item()) + " in sight for the rest of the build";
                    wanting = missing;
                }
                return task;
            }
            case BuildNeeds.Step.Short shortOf -> {
                needsNote = shortOf.why();
                wanting = missing;
                return Optional.empty();
            }
        }
    }

    /** How often it has gone for each drop of wool, walked or waited, which it lets go after a few (one that keeps drifting off). */
    private final Map<java.util.UUID, Integer> woolWalks = new HashMap<>();

    /**
     * Wool for the build (a trading hall's beds, E-45d), as players get it: what has dropped picked up first; a sheep of
     * its colour sheared with shears it holds, which drops one to three and grows it again, shears made first from iron
     * it can spare; else a sheep of its colour hunted, one wool a sheep, never one of its ranch's or one named. None in
     * sight: out over the ground to look, a few times, before the build ends without it.
     */
    private Optional<PortalRunCampaign.Task> wool(NativePlayerBody body, Supply supply, String wool, String missing) {
        var player = body.entity();
        var world = player.getServerWorld();
        Item item = Registries.ITEM.get(Identifier.of(wool));
        var colour = net.minecraft.util.DyeColor.byName(Identifier.of(wool).getPath().replace("_wool", ""), null);
        if (colour == null) {
            needsNote = "No way to " + shortName(wool) + " for the rest of the build";
            wanting = missing;
            return Optional.empty();
        }
        var drops = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, player.getBoundingBox().expand(12, 4, 12),
                drop -> drop.isAlive() && drop.getStack().isOf(item) && woolWalks.getOrDefault(drop.getUuid(), 0) < 6 && player.canSee(drop));
        if (!drops.isEmpty()) {
            var drop = drops.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
            needsNote = "Picking up " + shortName(wool) + " for the rest of the build";
            woolWalks.merge(drop.getUuid(), 1, Integer::sum);
            // Beside it already: a moment for it, as a drop is not picked up in its first half second. Still falling from
            // the sheep: a moment for it to land, a walk to a block in the air being no walk at all.
            if (player.getBoundingBox().expand(1, 0.5, 1).intersects(drop.getBoundingBox()) || !drop.isOnGround()) {
                return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Pick up " + shortName(wool)));
            }
            return Optional.of(PortalRunCampaign.go(drop.getBlockPos(), 0.6, false, "Pick up " + shortName(wool)));
        }
        int shears = -1;
        for (int s = 0; s < 36 && shears < 0; s++) if (player.getInventory().getStack(s).isOf(Items.SHEARS)) shears = s;
        if (shears < 0 && player.getInventory().count(Items.IRON_INGOT) - supply.ironKept() >= 2) {
            var make = supply.campaign().craft(body, "shears");
            if (make.isPresent()) {
                needsNote = "Making shears for " + shortName(wool);
                return make;
            }
        }
        boolean shearing = shears >= 0;
        var sheep = world.getEntitiesByClass(net.minecraft.entity.passive.SheepEntity.class, player.getBoundingBox().expand(24, 8, 24),
                s -> s.isAlive() && !s.isBaby() && s.getColor() == colour && player.canSee(s)
                        && (shearing ? s.isShearable() : !s.isSheared() && !s.hasCustomName() && !supply.spare().test(s)));
        if (!sheep.isEmpty()) {
            var nearest = sheep.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
            if (shearing) {
                needsNote = "Shearing sheep for " + shortName(wool);
                if (shears >= 9) return Optional.of(new PortalRunCampaign.Task("native_swap", Map.of("source", str(shears), "target", "8"), "Shears to hand"));
                if (!player.canInteractWithEntity(nearest, 0)) return Optional.of(PortalRunCampaign.go(nearest.getBlockPos(), 2.0, false, "Approach a sheep to shear"));
                return Optional.of(new PortalRunCampaign.Task("native_shear", Map.of("target", nearest.getUuid().toString(), "slot", str(shears)), "Shear a sheep"));
            }
            needsNote = "Hunting sheep for " + shortName(wool);
            if (player.squaredDistanceTo(nearest) <= 2.8 * 2.8) return Optional.of(PortalRunCampaign.strike(player, nearest, "Hunt a sheep for its wool"));
            return Optional.of(PortalRunCampaign.go(nearest.getBlockPos(), 2.0, false, "Approach a sheep for its wool"));
        }
        if (looks < LOOKS) {
            var look = supply.campaign().lookFor(body, "sheep");
            if (look.isPresent()) {
                needsNote = "Looking for sheep for the rest of the build";
                return look;
            }
        }
        needsNote = "No sheep in sight for the rest of the build";
        wanting = missing;
        return Optional.empty();
    }

    /** The wood the build is mostly made of, for recipes that take any planks; oak if none. */
    private String wood() {
        var count = new HashMap<String, Integer>();
        for (var b : blocks) {
            String id = b.spec().block().substring(b.spec().block().indexOf(':') + 1);
            for (String w : BuildNeeds.WOODS) if (id.startsWith(w + "_")) count.merge(w, 1, Integer::sum);
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("oak");
    }

    private static Map<String, Integer> contents(Inventory chest) {
        var counts = new HashMap<String, Integer>();
        for (int slot = 0; slot < chest.size(); slot++) {
            ItemStack stack = chest.getStack(slot);
            if (!stack.isEmpty()) counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /**
     * A task the runner gave has ended: a placement in the wrong state is counted, and after {@link #TRIES} the block is
     * given up on (the planner breaks it and places it again until then, as anything else in a target's way).
     */
    void finished(PortalRunCampaign.Task task, boolean completed, String reason, ServerWorld world) {
        // A step for the build's needs is no placement: the run's own gathering keeps account of those that fail.
        if (needsTask(task)) {
            needsTask = null;
            if ("native_goto".equals(task.skill()) && task.label().startsWith("Explore")) looks++;
            if (completed) failures = 0;
            return;
        }
        if (PAUSE.equals(task.label())) return;
        // A walk to a spot to place from is counted when it ends, not when it is asked for: the runtime may ask for the
        // next step more than once before it sets out, and a count kept there passed a dozen in two walks, in the farm
        // ward's first course, and gave up every spot round a block it could reach.
        if ("native_goto".equals(task.skill()) && task.label().startsWith("To ") && task.label().contains(" to place ")) {
            Pos to = new Pos(Integer.parseInt(task.args().get("x")), Integer.parseInt(task.args().get("y")), Integer.parseInt(task.args().get("z")));
            stanceWalks = to.equals(lastStance) ? stanceWalks + 1 : 1;
            lastStance = to;
        }
        if ("native_goto".equals(task.skill()) && task.label().contains(OVER)) overFailed = completed ? 0 : overFailed + 1;
        if (completed) {
            failures = 0;
        } else if (!"native_wait".equals(task.skill())) {
            failures++;
            pause = true;
            lastFailure = task.label() + (reason.isEmpty() ? "" : ": " + reason);
        }
        boolean placing = "native_place".equals(task.skill()), breaking = "native_break".equals(task.skill());
        boolean pillar = "native_pillar".equals(task.skill());
        if (!placing && !breaking && !pillar) return;
        Pos at = new Pos(Integer.parseInt(task.args().get("x")), Integer.parseInt(task.args().get("y")), Integer.parseInt(task.args().get("z")));
        if (pillar) {
            // The block it jumped and put under its feet, on a scaffold of the build's own: not a pillar a route out of
            // where it dug for stone put up, however often the build planned again while that ran.
            if (completed && UP_THE_SCAFFOLD.equals(task.label())) scaffold.add(at);
            return;
        }
        if (completed) shunned.clear();
        else if (issuedFrom != null && !"Down the scaffold".equals(task.label())) shunned.add(issuedFrom);
        // A prop is scaffolding once it stands, as the task it was placed by says (re-planning may run before this does).
        if (placing && task.label().startsWith(PROP)) {
            if (completed) scaffold.add(at);
            return;
        }
        if (breaking) {
            if (completed) scaffold.remove(at);
            // A block in the build's place that may not be broken (a container placed in another state) is left as it is,
            // rather than refused again between every placement.
            else if (refused.merge(at, 1, Integer::sum) >= 3) givenUp.add(at);
            return;
        }
        // A block whose placement is refused again and again (the server's policy, another's claim) is left.
        if (!completed) {
            if (refused.merge(at, 1, Integer::sum) >= 3) givenUp.add(at);
            return;
        }
        var want = blocks.stream().filter(b -> b.at().equals(at)).findFirst();
        if (want.isEmpty()) return;
        var got = spec(world.getBlockState(blockPos(at)));
        if (got.sameAs(want.get().spec())) return;
        int count = wrong.merge(at, 1, Integer::sum);
        KindredMod.LOGGER.info("Build {}: {} came out as {} rather than {} ({} of {})", blueprint.name(), at, got, want.get().spec(), count, TRIES);
        if (count >= TRIES) givenUp.add(at);
    }

    /** What the world holds round the build, cell by cell, as the planner wants it. */
    BuildSite snapshot(ServerWorld world) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var b : blocks) {
            minX = Math.min(minX, b.at().x()); minY = Math.min(minY, b.at().y()); minZ = Math.min(minZ, b.at().z());
            maxX = Math.max(maxX, b.at().x()); maxY = Math.max(maxY, b.at().y()); maxZ = Math.max(maxZ, b.at().z());
        }
        var cells = new HashMap<Pos, BuildSite.Cell>();
        var mutable = new BlockPos.Mutable();
        for (int x = minX - MARGIN; x <= maxX + MARGIN; x++) for (int z = minZ - MARGIN; z <= maxZ + MARGIN; z++) {
            if (!world.isChunkLoaded(mutable.set(x, minY, z))) continue;
            for (int y = minY - 4; y <= maxY + MARGIN; y++) {
                mutable.set(x, y, z);
                cells.put(new Pos(x, y, z), cell(world, mutable));
            }
        }
        return new BuildSite(cells);
    }

    /**
     * What a block in the world is to the build: air, danger, a filled container not to touch, or something
     * to replace, pass through or stand on.
     */
    static BuildSite.Cell cell(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return BuildSite.Cell.AIR;
        var spec = spec(state);
        if (state.getFluidState().isIn(FluidTags.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)) {
            return new BuildSite.Cell(BuildSite.Kind.DANGER, spec, false);
        }
        BlockEntity entity = world.getBlockEntity(pos);
        if (entity instanceof Inventory inventory && !inventory.isEmpty()) return new BuildSite.Cell(BuildSite.Kind.PROTECTED, spec, false);
        if (state.isReplaceable()) return new BuildSite.Cell(BuildSite.Kind.REPLACEABLE, spec, false);
        var shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return new BuildSite.Cell(BuildSite.Kind.PASSABLE, spec, false);
        return new BuildSite.Cell(BuildSite.Kind.SOLID, spec, state.isSideSolidFullSquare(world, pos, Direction.UP));
    }

    /** A block as the plans write it, with its state. */
    static BlockSpec spec(BlockState state) {
        return BlockSpec.parse(BlockArgumentParser.stringifyBlockState(state));
    }

    /** Item ids in the inventory. */
    private static Set<String> held(ServerPlayerEntity player) {
        var items = new HashSet<String>();
        for (int s = 0; s < 36; s++) {
            ItemStack stack = player.getInventory().getStack(s);
            if (!stack.isEmpty()) items.add(Registries.ITEM.getId(stack.getItem()).toString());
        }
        return items;
    }

    /** A slot holding a block it can spare for scaffolding: one the build needs no more of. */
    private static int scaffoldSlot(ServerPlayerEntity player, List<Blueprint.Block> wanted, BuildSite site) {
        var bill = dev.kindred.core.construction.BillOfMaterials.of(wanted, site, Map.of());
        for (Item item : SCAFFOLD) {
            int need = bill.needed().getOrDefault(Registries.ITEM.getId(item).toString(), 0);
            if (player.getInventory().count(item) > need) {
                int slot = slotOf(player, item);
                if (slot >= 0) return slot;
            }
        }
        return -1;
    }

    private static int slotOf(ServerPlayerEntity player, Item item) {
        for (int s = 0; s < 36; s++) if (player.getInventory().getStack(s).isOf(item)) return s;
        return -1;
    }

    private static PortalRunCampaign.Task swap(ServerPlayerEntity player, int slot, String label) {
        var inventory = player.getInventory();
        int target = 8;
        for (int s = 0; s < 9; s++) if (inventory.getStack(s).isEmpty()) { target = s; break; }
        return new PortalRunCampaign.Task("native_swap", Map.of("source", str(slot), "target", str(target)), label);
    }

    /**
     * The cell the feet stand in, as the planner counts standing: the block position, or, for a body over the edge of
     * a block or just off the ground, the cell below or beside it whose floor bears it.
     */
    static Pos standing(ServerPlayerEntity player, BuildSite site) {
        Pos feet = pos(player.getBlockPos());
        if (site.standable(feet)) return feet;
        if (site.standable(feet.down())) return feet.down();
        var box = player.getBoundingBox();
        Pos best = feet;
        double nearest = Double.MAX_VALUE;
        for (double x : new double[] {box.minX + 0.01, box.maxX - 0.01}) for (double z : new double[] {box.minZ + 0.01, box.maxZ - 0.01}) {
            Pos corner = new Pos((int) Math.floor(x), feet.y(), (int) Math.floor(z));
            double d = Math.hypot(corner.x() + 0.5 - player.getX(), corner.z() + 0.5 - player.getZ());
            if (site.standable(corner) && d < nearest) {
                nearest = d;
                best = corner;
            }
        }
        return best;
    }

    /** On the spot, near its middle (the walk there stops within 0.4 of it). */
    /** Break {@code target}, collecting the drop; the block it stands on with the leave to step down onto what is below. */
    private static PortalRunCampaign.Task breakTask(Pos target, Pos feet, String reason) {
        var args = new HashMap<String, String>(Map.of("x", str(target.x()), "y", str(target.y()), "z", str(target.z()), "collect", "true"));
        if (target.equals(feet.down())) args.put("underfoot", "true");
        return new PortalRunCampaign.Task("native_break", Map.copyOf(args), reason + " at " + target);
    }

    /**
     * The placement it walked over to make, as planned when it set out, if it stands in the spot it set out for and the
     * block is still to be placed; else null, to plan the whole build again. Planned again with the rest, the nearest
     * block from the spot for a plank was a stone whose spot was back where it came from, and from there the plank's
     * again: it walked to and fro placing neither (the farm ward's own way in, M-06a). Planned again for the plank
     * alone, it found another spot for it, and walked on. Kept while it is not yet there: the runtime may ask for the
     * next step again before the walk starts, and taken then, the commitment was gone before it set out.
     */
    private ConstructionPlanner.Step placeWalkedTo(ServerPlayerEntity player, BuildSite site) {
        var step = placeStep;
        if (step == null) return null;
        boolean placed = site.cell(step.target()).map(c -> c.state() != null && c.state().sameAs(step.spec())).orElse(false);
        if (placed) placeStep = null;
        if (placed || !at(player, step.stance())) return null;
        placeStep = null;
        return step;
    }

    private static boolean at(ServerPlayerEntity player, Pos stance) {
        return standing(player).equals(stance)
                && Math.hypot(player.getX() - (stance.x() + 0.5), player.getZ() - (stance.z() + 0.5)) < 0.45 && player.isOnGround();
    }

    /**
     * The block it stands in as a plan reckons it, on top of whatever it stands on: on a stair's step, a slab or
     * farmland, the block above that, not the stair's own. Stood on a stair it had just placed in the hall's house,
     * half a block up, it was never "at" the spot on top of the stair it walked to, and walked there again more than 4700 times.
     */
    private static Pos standing(ServerPlayerEntity player) {
        return new Pos(player.getBlockX(), net.minecraft.util.math.MathHelper.floor(player.getY() + 0.5), player.getBlockZ());
    }

    private static PortalRunCampaign.Task go(Pos stance, String label) {
        return new PortalRunCampaign.Task("native_goto", Map.of("x", str(stance.x()), "y", str(stance.y()), "z", str(stance.z()),
                "range", "0", "centre", "true"), label);
    }

    /** Within a few blocks of {@code target}, wherever the navigator finds to stand. */
    private PortalRunCampaign.Task near(Pos target, String reason) {
        progress = blueprint.name() + ": " + reason;
        return new PortalRunCampaign.Task("native_goto", Map.of("x", str(target.x()), "y", str(target.y()), "z", str(target.z()), "range", "2.5"), reason);
    }

    private PortalRunCampaign.Task wait(String reason) {
        progress = blueprint.name() + ": " + reason;
        return new PortalRunCampaign.Task("native_wait", Map.of("seconds", "2"), "Wait: " + reason);
    }

    private static String shortName(BlockSpec spec) {
        return shortName(spec.block());
    }

    private static String shortName(String id) {
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }

    /**
     * The look a build placement asked for, from where the eyes really are: toward the planned point on the face, keeping
     * the planned facing if the body stands a little off its spot and the straight look would turn it a quarter round.
     * Null for a placement that asked for none.
     */
    static NativePlayerBody.PlaceLook aim(ServerPlayerEntity player, Map<String, String> args) {
        if (!args.containsKey("hit")) return null;
        String[] parts = args.get("hit").split(",");
        if (parts.length != 3) throw new IllegalArgumentException("A hit is x,y,z");
        var hit = new Vec3d(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
        var delta = hit.subtract(player.getEyePos());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        if (args.containsKey("yaw")) {
            float planned = Float.parseFloat(args.get("yaw"));
            if (Placement.facingOf(yaw) != Placement.facingOf(planned)) yaw = planned;
        }
        return new NativePlayerBody.PlaceLook(yaw, pitch, hit);
    }

    static Pos pos(BlockPos p) { return new Pos(p.getX(), p.getY(), p.getZ()); }

    static BlockPos blockPos(Pos p) { return new BlockPos(p.x(), p.y(), p.z()); }

    private static String str(int v) { return String.valueOf(v); }
}
