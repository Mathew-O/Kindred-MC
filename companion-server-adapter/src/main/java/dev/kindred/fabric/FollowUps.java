package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.guide.MinecraftGuide;
import dev.kindred.core.guide.Need;
import dev.kindred.core.guide.NeedsAssessment;
import dev.kindred.core.guide.Objective;
import dev.kindred.core.guide.TaskQueue;
import dev.kindred.core.guide.Want;
import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.PortalRunPlanner;
import dev.kindred.core.survival.PortalRunSnapshot;
import dev.kindred.core.survival.StrongholdRunPlanner;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The follow-up queue in play, for every goal (F-25c7): what the tasks ahead need, assessed from what the player holds
 * on every pass, the chunks it can get at now, and for each task at its gate what it alone still lacks. Both goals
 * use it: the portal goal takes chunks on its way (a chicken in sight while feathers are wanted for the End fight),
 * and the End goal also gathers each task's needs at its gate, the Nether trip's before going through and the End
 * fight's before going down to the stronghold. A gate gives up after half a day without progress, or half a minute
 * with nothing to be had from where the player is. Every chunk is an ordinary recorded skill; nothing is given.
 */
final class FollowUps {
    /** Half a day of game time without a gate's work getting smaller: a night for spiders to come out included. */
    static final long STALL_TICKS = 12_000;
    /** Nothing of a gate's work can be done from here at all for this long: none of the sources is in reach. */
    static final long IDLE_TICKS = 600;

    /** How the gathering at one task's gate is going. */
    private static final class Gate {
        long since = -1, idleSince = -1;
        int left;
        boolean over;
    }

    private final PortalRunCampaign campaign;
    private final Map<Objective, Gate> gates = new EnumMap<>(Objective.class);
    private StrongholdRunPlanner.Provisions provisions = StrongholdRunPlanner.Provisions.NONE;
    private Set<Objective> ahead = Set.of();

    FollowUps(PortalRunCampaign campaign) {
        this.campaign = campaign;
    }

    StrongholdRunPlanner.Provisions provisions() { return provisions; }

    /** What the player holds of each need, counted from its own inventory. */
    static Map<Need, Integer> held(ServerPlayerEntity player, PortalRunSnapshot p) {
        var held = new EnumMap<Need, Integer>(Need.class);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            Need need = stack.isOf(Items.BOW) ? Need.BOW
                    : stack.isOf(Items.ARROW) || stack.isOf(Items.SPECTRAL_ARROW) || stack.isOf(Items.TIPPED_ARROW) ? Need.ARROWS
                    : stack.isOf(Items.WATER_BUCKET) ? Need.WATER_BUCKET
                    : stack.isOf(Items.STRING) ? Need.STRING
                    : stack.isOf(Items.FEATHER) ? Need.FEATHERS
                    : stack.isOf(Items.FLINT) ? Need.FLINT
                    : stack.isOf(Items.STICK) ? Need.STICKS
                    : stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.COBBLED_DEEPSLATE) || stack.isOf(Items.NETHERRACK) ? Need.BLOCKS : null;
            if (need != null) held.merge(need, stack.getCount(), Integer::sum);
        }
        held.put(Need.SHIELD, p.shields());
        held.put(Need.FOOD, p.edibleFood());
        return held;
    }

    /**
     * Every want for the tasks ahead: their requirements, what the strategist queued, and what a habit kept in this
     * world adds (more food for the Nether trip when the player has learned it needs more).
     */
    private List<Want> wants(TaskQueue queue, Set<Objective> ahead) {
        var wants = new ArrayList<>(queue.wants(ahead));
        if (ahead.contains(Objective.NETHER_TRIP) && campaign.preferences().has(PlanPreferences.Habit.MORE_FOOD)) {
            wants.add(new Want(Need.FOOD, StrongholdRunPlanner.PREP_FOOD + PlanPreferences.EXTRA_FOOD, Objective.NETHER_TRIP,
                    "a habit kept in this world: more food"));
        }
        return wants;
    }

    /**
     * The queue for this pass: the strategist's wants settled against what the player holds, the tasks {@code ahead}
     * broken into chunks, those it can get at now, and for each task whose gate it stands at ({@code atGate}), what
     * that task alone still lacks, while its gathering has neither stalled nor ended. {@code onTheWay} keeps the
     * chunks at hand to those that spend nothing another task is short of (the portal's route plans its own iron and
     * wood).
     */
    StrongholdRunPlanner.Provisions assess(ServerPlayerEntity player, PortalRunSnapshot p, Set<Objective> ahead, Set<Objective> atGate,
                                           int swordTier, boolean onTheWay) {
        this.ahead = Set.copyOf(ahead);
        var held = held(player, p);
        TaskQueue queue = campaign.queue().settle(held, ahead);
        campaign.queue(queue);
        var all = wants(queue, ahead);
        var chunks = NeedsAssessment.chunks(all, held);
        campaign.wanted(items(chunks));
        long now = campaign.lastTick();
        var gated = new EnumMap<Objective, List<NeedsAssessment.Chunk>>(Objective.class);
        for (Objective objective : atGate) {
            Gate gate = gates.computeIfAbsent(objective, key -> new Gate());
            if (gate.over) continue;
            var own = NeedsAssessment.chunks(all.stream().filter(w -> w.objective() == objective).toList(), held);
            int left = own.stream().mapToInt(NeedsAssessment.Chunk::count).sum();
            if (gate.since < 0 || left < gate.left) {
                gate.since = now;
                gate.left = left;
            } else if (!own.isEmpty() && now - gate.since > STALL_TICKS) {
                end(objective, player, "gathering for " + objective.words() + " has stalled");
                continue;
            }
            if (!own.isEmpty()) gated.put(objective, own);
        }
        provisions = new StrongholdRunPlanner.Provisions(chunks, gated, atHand(player, p, swordTier, chunks, onTheWay), held);
        return provisions;
    }

    /** The task at this gate has begun, or its gathering is given up: what it still lacks goes unmet. */
    void end(Objective objective, ServerPlayerEntity player, String why) {
        Gate gate = gates.computeIfAbsent(objective, key -> new Gate());
        if (gate.over) return;
        gate.over = true;
        var left = provisions.gated().getOrDefault(objective, List.of());
        if (!left.isEmpty()) {
            KindredMod.LOGGER.info("{}: {}; goes on without, for {}: {}", player.getName().getString(), why, objective.words(),
                    String.join(", ", left.stream().map(c -> c.count() + " " + c.need().words(c.count())).toList()));
        }
    }

    /** A new gate for this task: its gathering is due again (the Nether trip, back for more eyes). */
    void reopen(Objective objective) {
        gates.remove(objective);
    }

    /** A pass at {@code objective}'s gate that found nothing of its work to do from here; a pass that did, {@code worked}. */
    void idle(Objective objective, ServerPlayerEntity player, boolean worked) {
        Gate gate = gates.computeIfAbsent(objective, key -> new Gate());
        long now = campaign.lastTick();
        if (worked) gate.idleSince = -1;
        else if (gate.idleSince < 0) gate.idleSince = now;
        else if (now - gate.idleSince > IDLE_TICKS) end(objective, player, "nothing more for " + objective.words() + " to be had here");
    }

    /** The items the chunks gather, kept rather than dropped and walked back for. */
    private static Set<Item> items(List<NeedsAssessment.Chunk> chunks) {
        var items = new HashSet<Item>();
        for (var chunk : chunks) {
            switch (chunk.need()) {
                case STRING -> items.add(Items.STRING);
                case FEATHERS -> items.add(Items.FEATHER);
                case FLINT -> items.add(Items.FLINT);
                case ARROWS -> items.add(Items.ARROW);
                case BOW -> items.add(Items.BOW);
                default -> { }
            }
        }
        return items;
    }

    private static boolean ashore(ServerPlayerEntity player) {
        return player.getWorld().getRegistryKey() == World.OVERWORLD && !(player.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity);
    }

    private static int wood(ServerPlayerEntity player) {
        int planks = 0, logs = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) planks += stack.getCount();
            else if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) logs += stack.getCount();
        }
        return planks + 4 * logs;
    }

    private static int boats(ServerPlayerEntity player) {
        int boats = player.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity ? 1 : 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) if (inventory.getStack(slot).getItem() instanceof net.minecraft.item.BoatItem) boats++;
        return boats;
    }

    /**
     * The chunks the player can get at now: a chicken in sight while feathers are wanted, a spider it can take on or a
     * cobweb seen while string is, gravel close by while flint is, a craft whose inputs are all needs it holds; and,
     * except on the portal goal's way ({@code onTheWay}), wood for sticks beyond a boat's, iron and wood for a shield,
     * an empty bucket by water. Nothing from a boat or outside the Overworld but crafting.
     */
    private Set<Need> atHand(ServerPlayerEntity player, PortalRunSnapshot p, int swordTier, List<NeedsAssessment.Chunk> chunks, boolean onTheWay) {
        var at = EnumSet.noneOf(Need.class);
        boolean ashore = ashore(player);
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (var chunk : chunks) {
            switch (chunk.need()) {
                case FEATHERS -> {
                    if (ashore && !world.getEntitiesByClass(ChickenEntity.class, player.getBoundingBox().expand(16, 6, 16),
                            chicken -> chicken.isAlive() && !chicken.isBaby() && player.canSee(chicken)).isEmpty()) at.add(Need.FEATHERS);
                }
                case STRING -> {
                    boolean spider = swordTier >= 2 && player.getHealth() >= 14 && !world.getEntitiesByClass(SpiderEntity.class,
                            player.getBoundingBox().expand(16, 8, 16), mob -> mob.isAlive() && player.canSee(mob)).isEmpty();
                    boolean web = swordTier >= 1 && campaign.knowledge().nearest(world, WorldKnowledge.Kind.COBWEB, feet,
                            b -> b.getSquaredDistance(feet) <= 16 * 16 && world.getBlockState(b).isOf(Blocks.COBWEB)).isPresent();
                    if (ashore && (spider || web)) at.add(Need.STRING);
                }
                case FLINT -> {
                    boolean gravel = campaign.knowledge().nearest(world, WorldKnowledge.Kind.GRAVEL, feet,
                            b -> b.getSquaredDistance(feet) <= 12 * 12 && world.getBlockState(b).isOf(Blocks.GRAVEL)).isPresent();
                    if (ashore && (gravel || player.getInventory().count(Items.GRAVEL) > 0)) at.add(Need.FLINT);
                }
                case STICKS -> {
                    // Not from the wood a boat still wants: the water on the way comes first.
                    int keep = boats(player) == 0 ? StrongholdRunPlanner.BOAT_PLANKS : 0;
                    if (!onTheWay && wood(player) >= keep + 2) at.add(Need.STICKS);
                }
                case SHIELD -> { if (!onTheWay && p.ironIngots() >= 1 && wood(player) >= 6) at.add(Need.SHIELD); }
                case WATER_BUCKET -> {
                    if (!onTheWay && ashore && player.getInventory().count(Items.BUCKET) > 0 && campaign.knowledge().nearest(world, WorldKnowledge.Kind.WATER, feet,
                            b -> b.getSquaredDistance(feet) <= 16 * 16).isPresent()) at.add(Need.WATER_BUCKET);
                }
                default -> { }
            }
        }
        return at;
    }

    /** One craft of {@code need} can be made from the needs held now. */
    private boolean craftable(Need need) {
        var recipe = MinecraftGuide.recipe(need).orElseThrow();
        for (var input : recipe.inputs().entrySet()) if (provisions.held().getOrDefault(input.getKey(), 0) < input.getValue()) return false;
        return true;
    }

    /**
     * A chunk of {@code count} of {@code need} as an ordinary recorded skill: searching for its source only when it is
     * {@code due} at its gate, otherwise only what is at hand.
     */
    Optional<PortalRunCampaign.Task> realize(Need need, int count, boolean due, String why, PortalRunSnapshot p,
                                             NativePlayerBody body, EditPolicy.ServerPolicy policy, int swordTier) {
        var player = body.entity();
        boolean ashore = ashore(player);
        int wood = wood(player);
        return switch (need) {
            // A table is set down or made only for a craft whose inputs are in hand.
            case BOW, ARROWS -> !craftable(need) ? Optional.empty() : craftAtTable(body, need == Need.BOW ? "bow" : "arrow");
            case SHIELD -> p.ironIngots() >= 1 && wood >= 6 ? craftAtTable(body, "shield") : Optional.empty();
            case STICKS -> p.planks() >= 2 ? campaign.craft(body, "stick") : p.logs() > 0 ? campaign.craft(body, "planks") : Optional.empty();
            case FEATHERS -> !ashore ? Optional.empty() : campaign.huntFor(player, ChickenEntity.class, "chicken for feathers")
                    .or(() -> due ? shared(PortalRunPlanner.Action.SEEK_FOOD, count, why, p, body, policy) : Optional.empty());
            case STRING -> !ashore ? Optional.empty()
                    : (swordTier >= 2 && player.getHealth() >= 14 ? fightSpider(player) : Optional.<PortalRunCampaign.Task>empty())
                    .or(() -> swordTier >= 1 ? campaign.gather(body, WorldKnowledge.Kind.COBWEB, due ? 48 : 16, "cobweb") : Optional.empty());
            case FLINT -> ashore ? campaign.flint(body) : Optional.empty();
            case BLOCKS -> ashore ? shared(PortalRunPlanner.Action.GATHER_STONE, count, why, p, body, policy) : Optional.empty();
            case FOOD -> ashore ? shared(PortalRunPlanner.Action.HUNT, count, why, p, body, policy)
                    .or(() -> shared(PortalRunPlanner.Action.SEEK_FOOD, count, why, p, body, policy)) : Optional.empty();
            case WATER_BUCKET -> !ashore ? Optional.empty() : player.getInventory().count(Items.BUCKET) > 0
                    ? shared(PortalRunPlanner.Action.FILL_WATER, count, why, p, body, policy)
                    : p.ironIngots() >= 3 ? craftAtTable(body, "bucket") : Optional.empty();
        };
    }

    /**
     * On the portal goal's way: the first chunk at hand, as a short detour before the route's next step. The tasks
     * ahead have no gate here; the End goal gathers at them.
     */
    Optional<PortalRunCampaign.Task> onTheWay(NativePlayerBody body, PortalRunSnapshot p, EditPolicy.ServerPolicy policy, Set<Objective> ahead) {
        var player = body.entity();
        assess(player, p, ahead, Set.of(), p.swordTier(), true);
        for (var chunk : provisions.chunks()) {
            if (!provisions.now(chunk)) continue;
            Optional<PortalRunCampaign.Task> task = realize(chunk.need(), chunk.count(), false, "", p, body, policy, p.swordTier());
            if (task.isPresent()) return task;
        }
        return Optional.empty();
    }

    private Optional<PortalRunCampaign.Task> fightSpider(ServerPlayerEntity player) {
        var spider = player.getServerWorld().getEntitiesByClass(SpiderEntity.class, player.getBoundingBox().expand(16, 8, 16),
                        mob -> mob.isAlive() && player.canSee(mob)).stream()
                .min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        if (spider == null) return Optional.empty();
        var strike = PortalRunCampaign.strike(player, spider, "Fight spider for string");
        return Optional.of(new PortalRunCampaign.Task("native_fight", strike.args(), strike.label()));
    }

    private Optional<PortalRunCampaign.Task> shared(PortalRunPlanner.Action action, int count, String why, PortalRunSnapshot p,
                                                    NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        return campaign.realize(new PortalRunPlanner.Step(action, "", Math.max(1, count), PortalRunPlanner.Stage.DONE, why), p, body, policy);
    }

    /** Craft at a crafting table: one close by, else one it carries set down, else one made from four planks. */
    private Optional<PortalRunCampaign.Task> craftAtTable(NativePlayerBody body, String item) {
        Optional<PortalRunCampaign.Task> made = campaign.craft(body, item);
        if (made.isPresent() || campaign.tableNear(body)) return made;
        if (body.entity().getInventory().count(Items.CRAFTING_TABLE) > 0) return campaign.placeTable(body);
        return campaign.craft(body, "crafting_table");
    }

    /** The queue in words for the strategist, as it stood on the last pass. */
    String describe(ServerPlayerEntity player) {
        PortalRunSnapshot p = campaign.lastSnapshot();
        if (p == null || ahead.isEmpty()) return "not assessed yet.";
        return campaign.queue().describe(ahead, held(player, p));
    }
}
