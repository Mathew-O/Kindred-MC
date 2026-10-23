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
 * A build in progress for one player (E-08b): a blueprint placed at an origin and turned, built from the player's own
 * blocks one recorded step at a time. Each pass it looks at the world round the build, asks the construction planner
 * (E-08a) for the next step and turns it into a task for the runtime's own skills: walk to the spot to stand, place a
 * block with the look that gives its state, break what is in the way, pillar a scaffold, take the scaffold down.
 * Progress is read from the world, so nothing but the project itself is remembered: what is built is built.
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
    /** Spots where the server refused a step the plan thought sound; avoided until the build gets on again. */
    private final Set<Pos> shunned = new HashSet<>();
    /** Where the feet stood for the place or break last given. */
    private Pos issuedFrom;
    private int failures;
    private String lastFailure = "";
    /** The scaffold being put up: its foot and how high it is to go. */
    private Pos column;
    private int columnTop;
    private String progress = "Starting";
    private boolean done;

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
     * storage, if it has one (null otherwise).
     */
    record Supply(PortalRunCampaign campaign, BlockPos storage) { }

    /** How far its own storage may be for stepping over to it to count as easy. */
    private static final int STORAGE_NEAR = 24;

    private final BuildNeeds needs = new BuildNeeds();
    private PortalRunCampaign.Task needsTask;
    private String needsNote = "";
    /** What it cannot get for the rest of the build, once nothing it can do gets it; null while it still can. */
    private String wanting;
    /** Its own build's cells, never a source of blocks for it. */
    private Set<Pos> own;
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
        var player = body.entity();
        var world = player.getServerWorld();
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
                        "slot", str(slot)), "Up the scaffold"));
            }
            if (feet.y() >= columnTop || !(feet.x() == column.x() && feet.z() == column.z())) column = null;
        }
        var step = ConstructionPlanner.next(wanted, clear, site, feet, items, scaffold, shunned);
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
                done = true;
                progress = blueprint.name() + ": built, " + tally.placed() + "/" + tally.total() + " blocks"
                        + (tally.unsupported().isEmpty() ? "" : ", without " + String.join(", ", tally.unsupported()));
                yield Optional.empty();
            }
            // Out of reach from here of something it can see: over to it, by the navigator's own way, to plan again
            // from beside it. Anything else is waited out.
            case BLOCKED -> step.target() != null && feet.distanceSquared(step.target()) > 9
                    ? Optional.of(near(step.target(), step.reason() + "; over to it"))
                    : Optional.of(wait(step.reason() + " (standing at " + feet + ")"));
            case BREAK -> {
                if (!at(player, step.stance())) yield Optional.of(go(step.stance(), "To break " + step.target() + " from " + feet));
                boolean underfoot = step.target().equals(feet.down());
                var args = new HashMap<String, String>(Map.of("x", str(step.target().x()), "y", str(step.target().y()), "z", str(step.target().z()),
                        "collect", "true"));
                if (underfoot) args.put("underfoot", "true");
                yield Optional.of(new PortalRunCampaign.Task("native_break", Map.copyOf(args), step.reason() + " at " + step.target()));
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
                if (!at(player, step.stance())) yield Optional.of(go(step.stance(), "To a spot to place " + shortName(step.spec()) + " from " + feet));
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
                var kind = g.item().endsWith("_log") ? WorldKnowledge.Kind.LOG : WorldKnowledge.Kind.STONE;
                if (own == null) {
                    own = new HashSet<>(clear);
                    for (var b : blocks) own.add(b.at());
                }
                String what = kind == WorldKnowledge.Kind.LOG ? "logs" : "stone";
                var task = supply.campaign().gather(body, kind, 64, what, p -> !own.contains(pos(p)));
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
        if (completed) {
            failures = 0;
        } else if (!"native_wait".equals(task.skill())) {
            failures++;
            lastFailure = task.label() + (reason.isEmpty() ? "" : ": " + reason);
        }
        boolean placing = "native_place".equals(task.skill()), breaking = "native_break".equals(task.skill());
        boolean pillar = "native_pillar".equals(task.skill());
        if (!placing && !breaking && !pillar) return;
        Pos at = new Pos(Integer.parseInt(task.args().get("x")), Integer.parseInt(task.args().get("y")), Integer.parseInt(task.args().get("z")));
        if (pillar) {
            if (completed) scaffold.add(at); // The block it jumped and put under its feet.
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
    private static boolean at(ServerPlayerEntity player, Pos stance) {
        return player.getBlockX() == stance.x() && player.getBlockY() == stance.y() && player.getBlockZ() == stance.z()
                && Math.hypot(player.getX() - (stance.x() + 0.5), player.getZ() - (stance.z() + 0.5)) < 0.45 && player.isOnGround();
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
