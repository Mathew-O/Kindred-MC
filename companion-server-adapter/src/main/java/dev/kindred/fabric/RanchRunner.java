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
 * A ranch in progress for one player (M-01a): the pen's fences and gate crafted from the player's own wood, the pen
 * built through the build runner, then the {@link Ranch} plan's steps turned into the runtime's own skills: walk with the
 * food in hand for the animals to follow, open and shut the gate, feed the pair. What stands and what it sees is read
 * from the world each pass.
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
    private boolean opened;
    private int openFailures;
    private String ranched;

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
            if (kind == null || !Registries.ITEM.containsId(Identifier.ofVanilla(kind + "_fence"))) continue;
            wood.merge(kind, stack.getCount() * (id.endsWith("_log") ? 4 : 1), Integer::sum);
        }
        return wood.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    Optional<PortalRunCampaign.Task> next(NativePlayerBody body, PortalRunCampaign campaign) {
        if (done) return Optional.empty();
        // Its animals in: the doorway shut behind them, from the blocks it dug out of it.
        if (ranched != null) {
            if (shutting == null) shutting = new BuildRunner(shut, at, 0);
            var task = shutting.next(body);
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
        String before = ranch.phase();
        var step = ranch.next(seen);
        // What it saw as it gave up fetching, as well as at the end: the ranch fixture once left with cows at the gate
        // that the look at its end saw.
        if (step instanceof Ranch.Step.Done || !before.equals(ranch.phase()) && ranch.phase().equals("leaving the pen")) {
            KindredMod.LOGGER.info("Ranch {} at {}: {}; standing at {}, it saw {}", step instanceof Ranch.Step.Done ? "done" : "leaving", origin.toShortString(),
                    ranch.phase(), seen.feet(), seen.animals().stream()
                    .map(a -> a.kind().noun() + (a.adult() ? "" : " (young)") + " at " + a.at().x() + "," + a.at().y() + "," + a.at().z()).toList());
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
                yield Optional.of(new PortalRunCampaign.Task("native_goto", Map.copyOf(args), go.why()));
            }
            case Ranch.Step.Gate gate -> Optional.of(new PortalRunCampaign.Task("native_gate", Map.of("x", str(ranch.gate().x()), "y", str(ranch.gate().y()),
                    "z", str(ranch.gate().z()), "open", String.valueOf(gate.open())), gate.why()));
            case Ranch.Step.Feed feed -> {
                int slot = holdSlot(player, true);
                if (slot < 0) yield swapIn(player, true);
                yield Optional.of(new PortalRunCampaign.Task("native_feed", Map.of("target", feed.animal().toString(), "slot", str(slot)), feed.why()));
            }
            case Ranch.Step.Wait wait -> Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), wait.why()));
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
        if (shutting != null && !shutting.done()) shutting.finished(task, completed, reason, world);
        else if (!doorway.isEmpty() && !opened && (task.label().equals("Open the doorway") || task.label().equals("To the doorway"))) {
            if (!completed) openFailures++;
        } else if (build != null && !build.done()) build.finished(task, completed, reason, world);
    }

    private Ranch.Observation observe(ServerPlayerEntity player, ServerWorld world) {
        var animals = new ArrayList<Ranch.Animal>();
        Box around = new Box(origin).expand(Ranch.RANGE + 8, 6, Ranch.RANGE + 8);
        for (AnimalEntity animal : world.getEntitiesByClass(AnimalEntity.class, around, AnimalEntity::isAlive)) {
            var kind = Ranch.Kind.of(Registries.ENTITY_TYPE.getId(animal.getType()).toString());
            if (kind.isEmpty()) continue;
            BlockPos at = animal.getBlockPos();
            boolean adult = !animal.isBaby();
            animals.add(new Ranch.Animal(animal.getUuid(), kind.get(), new Pos(at.getX(), at.getY(), at.getZ()), adult,
                    adult && animal.getBreedingAge() == 0 && !animal.isInLove(), animal.isInLove()));
        }
        BlockPos feet = BlockPos.ofFloored(player.getX(), player.getY() + 0.2, player.getZ());
        var gate = world.getBlockState(pos(ranch.gate()));
        boolean open = gate.getBlock() instanceof FenceGateBlock && gate.get(FenceGateBlock.OPEN);
        return new Ranch.Observation(new Pos(feet.getX(), feet.getY(), feet.getZ()), built(world), open, food(player), animals, world.getTime());
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
        if (inventory.count(Items.CRAFTING_TABLE) > 0) return campaign.placeTable(body);
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
