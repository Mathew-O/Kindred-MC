package dev.kindred.fabric;

import dev.kindred.core.construction.Blueprint;
import dev.kindred.core.construction.Generators;
import dev.kindred.core.construction.Pos;
import dev.kindred.core.survival.Ranch;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In plain terms: an AI player starting a small ranch: crafting fence and gate from its own wood, building
 * the pen, then leading a pair of animals in with food in hand, shutting the gate and feeding them so they
 * breed. It looks at the pen and the animals in the world each time to see what is still to do.
 *
 * Technical: A ranch in progress for one player (M-01a): the pen's fences and gate crafted from the player's
 * own wood, the pen built through the build runner, then the {@link Ranch} plan's steps turned into the
 * runtime's own skills: walk with the food in hand for the animals to follow, open and shut the gate, feed
 * the pair. What stands and what it sees is read from the world each pass.
 */
final class RanchRunner {
    final Ranch ranch;
    final BlockPos origin;
    final String wood;
    private final Blueprint pen;
    private BuildRunner build;
    private String progress = "Starting";
    private String outcome;
    private boolean done;
    /**
     * A pasture's doorway (M-06a), where it has one: opened, its blocks dug out from outside it, before the animals
     * are brought in, and shut again behind them with the blocks it dug, as the plan {@code shut} placed at {@code at}.
     */
    private final List<BlockPos> doorway;
    private final Blueprint shut;
    private final BlockPos at;
    private BuildRunner shutting;
    /** Whether the last task it gave was a walk of the ranch's own, its refusal to be told to the ranch. */
    private boolean ranchWalk;
    private boolean opened;
    private int openFailures;
    private String ranched;

    /** A ranch for the given kind of animal, its pen made of the given wood at the given spot. */
    RanchRunner(BlockPos origin, Ranch.Kind kind, String wood) {
        this(origin, kind, wood, null, null);
    }

    /** A ranch whose animals come in by a doorway, the plan {@code shut} of it placed at {@code at}. */
    RanchRunner(BlockPos origin, Ranch.Kind kind, String wood, Blueprint shut, BlockPos at) {
        this.origin = origin.toImmutable();
        this.wood = wood;
        this.pen = Generators.pen(7, 7, wood);
        this.shut = shut;
        this.at = at == null ? null : at.toImmutable();
        this.doorway = shut == null ? List.of() : shut.placed(new Pos(at.getX(), at.getY(), at.getZ()), 0).stream().map(b -> pos(b.at()))
                // Outside first, from the top: the wall's stone, then the lining's planks behind it.
                .sorted(java.util.Comparator.comparingInt(BlockPos::getZ).reversed().thenComparing(java.util.Comparator.comparingInt(BlockPos::getY).reversed()))
                .toList();
        // Its animals brought up to two out from the doorway, in line with it, the wall's own south face.
        Pos approach = doorway.isEmpty() ? null : new Pos(doorway.getFirst().getX(), origin.getY(), doorway.getFirst().getZ() + 2);
        this.ranch = new Ranch(new Pos(origin.getX(), origin.getY(), origin.getZ()), 7, 7, kind, approach);
    }

    String progress() { return progress; }

    boolean done() { return done; }

    /** How it ended, in words, once done. */
    String outcome() { return outcome; }

    /** The wood the player has most of, as planks or logs: what the pen is made of. Null if it has none. */
    static String woodOf(ServerPlayerEntity player) {
        Map<String, Integer> wood = new java.util.HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            String kind = id.endsWith("_planks") ? id.substring(0, id.length() - "_planks".length())
                    : id.endsWith("_log") && !id.startsWith("stripped_") ? id.substring(0, id.length() - "_log".length()) : null;
            boolean log = id.endsWith("_log");
            if (kind == null || !Registries.ITEM.containsId(Identifier.ofVanilla(kind + "_fence"))) {
                // F-38: a wood the server's tags and recipes name, with a fence of its own.
                if (stack.isEmpty()) continue;
                String full = Registries.ITEM.getId(stack.getItem()).toString();
                var facts = dev.kindred.core.Capabilities.current();
                log = facts.is(dev.kindred.core.VanillaIds.LOGS, full);
                if (!log && !facts.is(dev.kindred.core.VanillaIds.PLANKS, full)) continue;
                var family = facts.woodOfPart(full).filter(w -> w.fence() != null && w.fenceGate() != null);
                if (family.isEmpty()) continue;
                kind = family.get().name();
            }
            wood.merge(kind, stack.getCount() * (log ? 4 : 1), Integer::sum);
        }
        return wood.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** The next thing to do for the ranch, or nothing once it is done. */
    Optional<PortalRunCampaign.Task> next(NativePlayerBody body, PortalRunCampaign campaign) {
        if (done) return Optional.empty();
        // Its animals in: the doorway shut behind them, from the blocks it dug out of it.
        if (ranched != null) {
            if (shutting == null) shutting = new BuildRunner(shut, at, 0);
            // With the run's own supply, as any build of its own: a block short (a drop of the doorway's not picked up) is
            // picked up where it lies, else gathered or made from what it holds, never left short for good (F-22c44). It
            // hunts no animal for it.
            var task = shutting.next(body, new BuildRunner.Supply(campaign, null, animal -> true, 0));
            progress = "Shutting the doorway: " + shutting.progress();
            if (task.isPresent()) return task;
            done = true;
            outcome = ranched + (shutting.complete() ? "; the doorway shut behind them" : "; the doorway not shut: " + shutting.progress());
            progress = outcome;
            return Optional.empty();
        }
        // The doorway opened first, once the pen stands, from the ground just outside it.
        if (!doorway.isEmpty() && !opened && built(body.entity().getServerWorld())) {
            var world = body.entity().getServerWorld();
            var left = doorway.stream().filter(p -> !world.getBlockState(p).isAir()).toList();
            if (left.isEmpty()) opened = true;
            else if (openFailures >= 6) {
                done = true;
                outcome = "The doorway could not be opened";
                progress = outcome;
                return Optional.empty();
            } else {
                BlockPos stand = doorway.getFirst().withY(at.getY()).south();
                progress = "Opening the doorway";
                if (!body.entity().getBlockPos().equals(stand)) {
                    return Optional.of(new PortalRunCampaign.Task("native_goto", Map.of("x", str(stand.getX()), "y", str(stand.getY()), "z", str(stand.getZ()),
                            "range", "0.5"), "To the doorway"));
                }
                BlockPos next = left.getFirst();
                return Optional.of(new PortalRunCampaign.Task("native_break", Map.of("x", str(next.getX()), "y", str(next.getY()), "z", str(next.getZ()),
                        "collect", "true"), "Open the doorway"));
            }
        }
        var player = body.entity();
        var world = player.getServerWorld();
        var seen = observe(player, world);
        ranchWalk = false;
        String before = ranch.phase();
        var step = ranch.next(seen);
        // What it saw as it gave up fetching, as well as at the end: the ranch fixture once left with cows at the gate
        // that the look at its end saw.
        if (step instanceof Ranch.Step.Done || !before.equals(ranch.phase()) && ranch.phase().equals("leaving the pen")) {
            // Each with its id's first eight characters, and what it gave up and tried: the own-ranch fixture in CI left
            // with "no other in sight", one sheep in sight behind the pen's far fence, and nothing logged said why.
            KindredMod.LOGGER.info("Ranch {} at {}: {}; standing at {}, it saw {}; {}", step instanceof Ranch.Step.Done ? "done" : "leaving", origin.toShortString(),
                    ranch.phase(), seen.feet(), seen.animals().stream()
                    .map(a -> a.kind().noun() + (a.adult() ? "" : " (young)") + " " + a.id().toString().substring(0, 8) + " at " + a.at().x() + "," + a.at().y() + "," + a.at().z()).toList(),
                    ranch.diagnosis());
        }
        // F-22c124, diagnostic: as it turns from leaving to bring one back, where it stands against the pen and its gate cell,
        // whether the gate is open, and where each of its kind outside the pen stands (the existing lines give its feet only
        // as it began to leave and as it ended).
        if (before.equals("leaving the pen") && ranch.phase().startsWith("bringing a")) {
            KindredMod.LOGGER.info("Ranch turned back at {}: standing at {} ({}), the gate cell {} {}, outside the pen {}", origin.toShortString(),
                    seen.feet(), ranch.inside(seen.feet()) ? "inside" : seen.feet().x() == ranch.gate().x() && seen.feet().z() == ranch.gate().z() ? "in the gateway" : "outside",
                    ranch.gate(), seen.gateOpen() ? "open" : "shut", seen.animals().stream().filter(a -> !ranch.inside(a.at()))
                            .map(a -> a.kind().noun() + (a.adult() ? "" : " (young)") + " " + a.id().toString().substring(0, 8) + " at " + a.at().x() + "," + a.at().y() + "," + a.at().z()).toList());
        }
        progress = ranch.phase();
        return switch (step) {
            case Ranch.Step.Build b -> buildPen(body, campaign);
            case Ranch.Step.Go go -> {
                int slot = holdSlot(player, go.holdFood());
                if (slot < 0) yield swapIn(player, go.holdFood());
                var args = new java.util.HashMap<String, String>(Map.of("x", str(go.target().x()), "y", str(go.target().y()), "z", str(go.target().z()),
                        "range", String.valueOf(go.range()), "hold", str(slot)));
                if (go.leg() > 0) args.put("leg", str(go.leg()));
                // With the food in hand, leading or looking for an animal: no drop it cannot step back up, nor the animal
                // follow it back up (the wheat fixture: after a cow past the field's edge, down three and no way back).
                if (go.holdFood()) args.put("fall", "1");
                ranchWalk = true;
                yield Optional.of(new PortalRunCampaign.Task("native_goto", Map.copyOf(args), go.why()));
            }
            case Ranch.Step.Gate gate -> Optional.of(new PortalRunCampaign.Task("native_gate", Map.of("x", str(ranch.gate().x()), "y", str(ranch.gate().y()),
                    "z", str(ranch.gate().z()), "open", String.valueOf(gate.open())), gate.why()));
            case Ranch.Step.Feed feed -> {
                int slot = holdSlot(player, true);
                if (slot < 0) yield swapIn(player, true);
                yield Optional.of(new PortalRunCampaign.Task("native_feed", Map.of("target", feed.animal().toString(), "slot", str(slot)), feed.why()));
            }
            case Ranch.Step.Wait wait -> {
                // A wait with the food put away puts it away first, as a walk does; held through a wait, a pair jammed at the
                // pasture's doorway went on following it there (the cattle fixture).
                if (!wait.holdFood() && isFood(player.getInventory().getMainHandStack())) {
                    int slot = holdSlot(player, false);
                    if (slot < 0) yield swapIn(player, false);
                    BlockPos feet = player.getBlockPos();
                    yield Optional.of(new PortalRunCampaign.Task("native_goto", Map.of("x", str(feet.getX()), "y", str(feet.getY()), "z", str(feet.getZ()),
                            "range", "1.5", "hold", str(slot)), "Put the food for the " + ranch.kind().plural() + " away"));
                }
                yield Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), wait.why()));
            }
            case Ranch.Step.Done d -> {
                if (shut != null) {
                    ranched = d.outcome();
                    yield next(body, campaign);
                }
                done = true;
                outcome = d.outcome();
                progress = d.outcome();
                yield Optional.empty();
            }
        };
    }

    /** A task the build runner gave, when it finished; the pen's own build hears of it. */
    void finished(PortalRunCampaign.Task task, boolean completed, String reason, ServerWorld world) {
        // A walk of the ranch's own refused: the one it went for given up, or the spot crossed off, not asked for again.
        // Told before the pen's build, whose runner is never marked done once the pen stands.
        if (ranchWalk && task.skill().equals("native_goto")) {
            ranchWalk = false;
            if (!completed) {
                KindredMod.LOGGER.info("Ranch at {}: its walk not completed, the one it went for given up or its spot crossed off: {} [{}]",
                        origin.toShortString(), reason, task.label());
                ranch.refused();
            }
            return;
        }
        if (shutting != null && !shutting.done()) shutting.finished(task, completed, reason, world);
        else if (!doorway.isEmpty() && !opened && (task.label().equals("Open the doorway") || task.label().equals("To the doorway"))) {
            if (!completed) openFailures++;
        } else if (build != null && !build.done()) build.finished(task, completed, reason, world);
    }

    Ranch.Observation observe(ServerPlayerEntity player, ServerWorld world) {
        var animals = new ArrayList<Ranch.Animal>();
        Box around = new Box(origin).expand(Ranch.RANGE + 8, 6, Ranch.RANGE + 8);
        // Round the pen, and round the player wherever it is: out looking for a stray, it sees what is about it there.
        var found = new java.util.LinkedHashMap<java.util.UUID, AnimalEntity>();
        for (AnimalEntity animal : world.getEntitiesByClass(AnimalEntity.class, around, AnimalEntity::isAlive)) found.put(animal.getUuid(), animal);
        for (AnimalEntity animal : world.getEntitiesByClass(AnimalEntity.class, player.getBoundingBox().expand(24, 6, 24), AnimalEntity::isAlive)) {
            found.put(animal.getUuid(), animal);
        }
        for (AnimalEntity animal : found.values()) {
            var kind = Ranch.Kind.of(Registries.ENTITY_TYPE.getId(animal.getType()).toString());
            if (kind.isEmpty()) continue;
            BlockPos at = cell(animal.getBlockPos(), animal.getX(), animal.getZ(), world);
            boolean adult = !animal.isBaby();
            animals.add(new Ranch.Animal(animal.getUuid(), kind.get(), new Pos(at.getX(), at.getY(), at.getZ()), adult,
                    adult && animal.getBreedingAge() == 0 && !animal.isInLove(), animal.isInLove()));
        }
        BlockPos feet = cell(BlockPos.ofFloored(player.getX(), player.getY() + 0.2, player.getZ()), player.getX(), player.getZ(), world);
        var gate = world.getBlockState(pos(ranch.gate()));
        boolean open = gate.getBlock() instanceof FenceGateBlock && gate.get(FenceGateBlock.OPEN);
        return new Ranch.Observation(new Pos(feet.getX(), feet.getY(), feet.getZ()), built(world), open, food(player), animals, world.getTime());
    }

    /**
     * The cell one stands in, {@code at}. Pressed against the ring, its middle can be in a fence's block, or the shut
     * gate's, clear of the post: it is in the cell beside that on its own side. Read by that block alone, a player its cows
     * had pushed against the fence from inside was out of the pen, and the ranch was done with it shut in (the cattle
     * fixture, once in ten).
     */
    private BlockPos cell(BlockPos at, double x, double z, ServerWorld world) {
        var state = world.getBlockState(at);
        boolean fence = state.isIn(BlockTags.FENCES);
        boolean shutGate = state.getBlock() instanceof FenceGateBlock && !state.get(FenceGateBlock.OPEN);
        boolean acrossZ = shutGate && state.get(FenceGateBlock.FACING).getAxis() == net.minecraft.util.math.Direction.Axis.Z;
        int[] c = cellOf(fence, shutGate, acrossZ, at.getX(), at.getZ(), x, z, origin.getX(), origin.getZ(), ranch.width(), ranch.depth());
        return new BlockPos(c[0], at.getY(), c[1]);
    }

    /**
     * How far inside a face of a shut gate's cell, across its thin axis, a middle overlaps the gate: its collision spans
     * 0.375 to 0.625 of the cell and a body is 0.6 wide, so 0.075, and a little over so that one pressed flush against a
     * face does not flicker between cells (F-22c129).
     */
    static final double GATE_FACE = 0.075 + 1e-3;

    /**
     * The cell for a middle at {@code x}, {@code z} whose block is {@code ax}, {@code az}: that block's, unless it is a fence
     * or a shut gate of the ring, when it is the cell beside on the middle's own side; but a middle well inside a shut gate,
     * overlapping it across its thin axis ({@code acrossZ} when that is z), is in the gate's own cell (F-22c129). Shifted
     * out of the shut gate's cell as one pressed against it, a player in the gate itself was read as just outside, its walk
     * round to the gate's front asked from there, with no standable start, refused, and the calf it went for given up
     * (CI run 45143611807; the fixture `inTheShutGatesCellAsItTurnsBackForACalfItStillBringsTheCalfIn`).
     */
    static int[] cellOf(boolean fence, boolean shutGate, boolean acrossZ, int ax, int az, double x, double z, int ox, int oz, int width, int depth) {
        if (!fence && !shutGate) return new int[] {ax, az};
        if (shutGate) {
            double across = acrossZ ? z - az : x - ax;
            if (across > GATE_FACE && across < 1 - GATE_FACE) return new int[] {ax, az};
        }
        int cx = ax, cz = az;
        if (cz == oz || cz == oz + depth - 1) cz += z < az + 0.5 ? -1 : 1;
        if (cx == ox || cx == ox + width - 1) cx += x < ax + 0.5 ? -1 : 1;
        return new int[] {cx, cz};
    }

    /** Every post of the ring a fence and the gate a gate; a gate swung round by opening still counts. */
    private boolean built(ServerWorld world) {
        for (var block : pen.placed(new Pos(origin.getX(), origin.getY(), origin.getZ()), 0)) {
            var state = world.getBlockState(pos(block.at()));
            boolean gate = block.spec().block().endsWith("_fence_gate");
            if (gate ? !(state.getBlock() instanceof FenceGateBlock) : !state.isIn(BlockTags.FENCES)) return false;
        }
        return true;
    }

    /**
     * Before the pen is built: its gate and fences crafted from the player's planks (or logs made into planks, and
     * sticks), at a table, then handed to a build of the pen. A build that gave up is the ranch's end.
     */
    private Optional<PortalRunCampaign.Task> buildPen(NativePlayerBody body, PortalRunCampaign campaign) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (build != null && build.done()) {
            done = true;
            outcome = "The pen could not be built: " + build.progress();
            progress = outcome;
            return Optional.empty();
        }
        if (build == null) {
            int fences = 0, gates = 0;
            for (var block : pen.placed(new Pos(origin.getX(), origin.getY(), origin.getZ()), 0)) {
                var state = world.getBlockState(pos(block.at()));
                if (block.spec().block().endsWith("_fence_gate")) { if (!(state.getBlock() instanceof FenceGateBlock)) gates++; }
                else if (!state.isIn(BlockTags.FENCES)) fences++;
            }
            Item fence = Registries.ITEM.get(Identifier.ofVanilla(wood + "_fence")), gate = Registries.ITEM.get(Identifier.ofVanilla(wood + "_fence_gate"));
            var inventory = player.getInventory();
            if (inventory.count(gate) < gates) {
                progress = "Crafting the pen's gate";
                return craft(body, campaign, wood + "_fence_gate", 2, 4);
            }
            if (inventory.count(fence) < fences) {
                progress = "Crafting fences: " + inventory.count(fence) + " of " + fences;
                return craft(body, campaign, wood + "_fence", 4, 2);
            }
            build = new BuildRunner(pen, origin, 0);
        }
        var task = build.next(body);
        progress = "Building the pen: " + build.progress();
        return task;
    }

    /**
     * A spot no block of its own goes (F-22c91): its pen's footprint, fences and gate included, and a block round it, and
     * the gate's approach two out, from the floor below to two above. Set down inside the pen, its crafting table was a
     * step a penned chicken hopped the fence by; set down at the fence, at the gate's front, it would stand in the way
     * the herd is led in and the player walks out.
     */
    boolean keepOff(BlockPos spot) {
        if (spot.getY() < origin.getY() - 1 || spot.getY() > origin.getY() + 2) return false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        Pos gate = null;
        for (var block : pen.placed(new Pos(origin.getX(), origin.getY(), origin.getZ()), 0)) {
            minX = Math.min(minX, block.at().x()); maxX = Math.max(maxX, block.at().x());
            minZ = Math.min(minZ, block.at().z()); maxZ = Math.max(maxZ, block.at().z());
            if (block.spec().block().endsWith("_fence_gate")) gate = block.at();
        }
        int x = spot.getX(), z = spot.getZ();
        if (x >= minX - 1 && x <= maxX + 1 && z >= minZ - 1 && z <= maxZ + 1) return true;
        if (gate == null) return false;
        // Two out from the gate, a block either side of its line.
        int dx = gate.x() == minX ? -1 : gate.x() == maxX ? 1 : 0, dz = gate.z() == minZ ? -1 : gate.z() == maxZ ? 1 : 0;
        for (int side = -1; side <= 1; side++) {
            int ax = gate.x() + 2 * dx + (dx == 0 ? side : 0), az = gate.z() + 2 * dz + (dz == 0 ? side : 0);
            if (x == ax && z == az) return true;
        }
        return false;
    }

    /** Craft {@code item} from {@code planks} planks and {@code sticks} sticks: the sticks and planks first if short. */
    private Optional<PortalRunCampaign.Task> craft(NativePlayerBody body, PortalRunCampaign campaign, String item, int planks, int sticks) {
        var inventory = body.entity().getInventory();
        Item plank = Registries.ITEM.get(Identifier.ofVanilla(wood + "_planks")), log = Registries.ITEM.get(Identifier.ofVanilla(wood + "_log"));
        int havePlanks = inventory.count(plank), haveSticks = inventory.count(Items.STICK);
        String recipe;
        if (haveSticks < sticks && havePlanks >= 2) recipe = "stick";
        else if (havePlanks < (haveSticks < sticks ? 2 : planks)) {
            if (inventory.count(log) == 0) {
                done = true;
                outcome = "Short of " + wood + " wood for the pen";
                progress = outcome;
                return Optional.empty();
            }
            recipe = wood + "_planks";
        } else recipe = item;
        // At a crafting table: one close by, else one it carries set down, else one made from four planks.
        var made = campaign.craft(body, recipe);
        if (made.isPresent() || campaign.tableNear(body)) return made;
        // Never in or at its own pen (F-22c91): set down inside it, the table was a step a penned chicken hopped the fence by.
        if (inventory.count(Items.CRAFTING_TABLE) > 0) return campaign.placeTable(body, this::keepOff);
        if (inventory.count(plank) < 4) {
            if (inventory.count(log) == 0) {
                done = true;
                outcome = "Short of " + wood + " wood for a crafting table";
                progress = outcome;
                return Optional.empty();
            }
            return campaign.craft(body, wood + "_planks");
        }
        return campaign.craft(body, "crafting_table");
    }

    private int food(ServerPlayerEntity player) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) if (isFood(player.getInventory().getStack(slot))) total += player.getInventory().getStack(slot).getCount();
        return total;
    }

    private boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && ranch.kind().food.contains(Registries.ITEM.getId(stack.getItem()).toString());
    }

    /** The hotbar slot to hold: one with the food, or one without it (an empty one first); -1 if none fits. */
    private int holdSlot(ServerPlayerEntity player, boolean food) {
        var inventory = player.getInventory();
        if (food) {
            if (isFood(inventory.getStack(inventory.selectedSlot))) return inventory.selectedSlot;
            for (int slot = 0; slot < 9; slot++) if (isFood(inventory.getStack(slot))) return slot;
            return -1;
        }
        if (!isFood(inventory.getStack(inventory.selectedSlot))) return inventory.selectedSlot;
        for (int slot = 0; slot < 9; slot++) if (inventory.getStack(slot).isEmpty()) return slot;
        for (int slot = 0; slot < 9; slot++) if (!isFood(inventory.getStack(slot))) return slot;
        return -1;
    }

    /** The food brought into the hotbar from the pack (or, its hotbar all food, a stack of it moved out). */
    private Optional<PortalRunCampaign.Task> swapIn(ServerPlayerEntity player, boolean food) {
        var inventory = player.getInventory();
        for (int slot = 9; slot < 36; slot++) {
            if (isFood(inventory.getStack(slot)) == food && (food || inventory.getStack(slot).isEmpty())) {
                return Optional.of(new PortalRunCampaign.Task("native_swap", Map.of("source", str(slot), "target", "8"),
                        food ? "Take the food in hand" : "Make room in hand"));
            }
        }
        done = true;
        outcome = food ? "No food for the animals at hand" : "No free hand from the food";
        progress = outcome;
        return Optional.empty();
    }

    private static BlockPos pos(Pos p) { return new BlockPos(p.x(), p.y(), p.z()); }

    private static String str(int value) { return String.valueOf(value); }
}
