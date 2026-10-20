package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.survival.PortalRunPlanner;
import dev.kindred.core.survival.PortalRunSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns the local portal-run plan into one concrete, bounded skill at a time for one AI player. Everything it
 * proposes still goes through the runtime's normal admission, paid activity, durable ledger and body checks;
 * this class only reads the player's own inventory and what it has seen.
 */
final class PortalRunCampaign {
    record Task(String skill, Map<String, String> args, String label) { }

    private static final double REACH = 4.3;
    private static final int IRON_TOP = 56, IRON_TARGET = 32, IRON_BOTTOM = -24;
    /** Exploration stays within this many blocks of where the run started, so the player can find its way back. */
    private static final int EXPLORE_RADIUS = 192;
    private static final java.util.EnumSet<PortalRunPlanner.Action> TRAVEL = java.util.EnumSet.of(
            PortalRunPlanner.Action.GATHER_LOGS, PortalRunPlanner.Action.GATHER_STONE, PortalRunPlanner.Action.MINE_COAL,
            PortalRunPlanner.Action.MINE_IRON, PortalRunPlanner.Action.MINE_DIAMOND, PortalRunPlanner.Action.MAKE_OBSIDIAN,
            PortalRunPlanner.Action.FILL_WATER, PortalRunPlanner.Action.GATHER_FLINT);
    private static final int DIAMOND_TOP = -52, DIAMOND_TARGET = -58;

    private final WorldKnowledge knowledge = new WorldKnowledge();
    private final Set<Long> ownWorkstations = new HashSet<>();
    private final Map<Long, Long> blockedUntil = new HashMap<>();
    /** Targets the player could not walk to; consulted when choosing what to gather, never when digging. */
    private final Map<Long, Long> unreachableUntil = new HashMap<>();
    /** Where the player stood when each unreachable target failed: it stays unreachable from around there. */
    private final Map<Long, BlockPos> unreachableFrom = new HashMap<>();
    private BlockPos lastFeet = BlockPos.ORIGIN;
    private final Map<Long, Integer> failures = new HashMap<>();
    private final Map<Long, Integer> approaches = new HashMap<>();
    private final Map<Task, Integer> repeatFailures = new HashMap<>();
    private final Map<Task, Long> setAside = new HashMap<>();
    private Direction digDirection = Direction.NORTH;
    private Direction exploreHeading;
    private int exploreFailures;
    private BlockPos exploredFrom;
    private int exploreStalls;
    private int cutAdvances;
    private ObsidianSite obsidianSite;
    private int pourWaits;
    private PortalSite portalSite;
    private BlockPos dropSite;
    private java.util.UUID foe;
    private final Map<java.util.UUID, Long> ignoredFoes = new HashMap<>();
    private long foeUntil;
    private BlockPos home;
    private long dropSiteUntil;
    private String progress = "Starting";
    private PortalRunPlanner.Stage stage = PortalRunPlanner.Stage.WOOD;
    private long lastTick;
    private EditPolicy.ServerPolicy policy = EditPolicy.ServerPolicy.deferredD4();

    /** Lava source L to convert, the air cell W to pour water into, and the stand cell S beyond it. */
    private record ObsidianSite(BlockPos lava, BlockPos water, BlockPos stand) { }

    /** Origin is the frame's bottom-left corner; the frame spans origin..origin+3 along {@code axis}, five tall. */
    private record PortalSite(BlockPos origin, Direction along, Direction toward, BlockPos stand) {
        BlockPos at(int across, int up) { return origin.offset(along, across).up(up); }
        /** The cell between the player and the frame plane, which must be open to reach the frame. */
        BlockPos front(int across, int up) { return at(across, up).offset(toward.getOpposite()); }
    }

    String progress() { return progress; }
    PortalRunPlanner.Stage stage() { return stage; }
    WorldKnowledge knowledge() { return knowledge; }

    /** Bounded perception each tick; cheap enough to run while other work proceeds. */
    void observe(ServerPlayerEntity player) {
        knowledge.scan(player);
    }

    void taskFinished(Task task, boolean completed, long tick) {
        if (task != null && task.label().startsWith("Recover") && dropSite != null && completed) {
            dropSiteUntil = Math.min(dropSiteUntil, tick + 600); // One more look around, then move on.
        }
        if (task != null && completed && "native_bucket".equals(task.skill()) && "pour".equals(task.args().get("mode"))) {
            BlockPos poured = position(task.args());
            if (poured != null) knowledge.remember(WorldKnowledge.Kind.WATER, poured); // It knows where it poured.
        }
        if (task != null && completed && task.label().startsWith("Cut through") && task.label().endsWith("advance") && ++cutAdvances >= 4) {
            exploreStalls = 0; // A few blocks on: try walking again.
            cutAdvances = 0;
        }
        if (task != null && completed && "native_goto".equals(task.skill()) && task.label().startsWith("Explore")) {
            exploredFrom = lastFeet; // Compared with where the player stands at the next plan.
        }
        if (task != null && completed && "native_goto".equals(task.skill()) && task.label().startsWith("Walk to")) {
            BlockPos reached = position(task.args());
            if (reached != null) approaches.merge(reached.asLong(), 1, Integer::sum);
        }
        if (task != null && completed) repeatFailures.remove(task);
        if (completed || task == null) return;
        // The same task failing again and again is set aside for a while, whatever it is, so the plan moves on.
        if (repeatFailures.merge(task, 1, Integer::sum) >= 3) {
            repeatFailures.remove(task);
            setAside.put(task, tick + 1200);
        }
        BlockPos pos = position(task.args());
        // An unreachable trunk or ore column usually stays unreachable; skip its neighbours too.
        if (pos != null) avoid(pos, "native_goto".equals(task.skill()) && task.label().startsWith("Walk to"), tick);
        if ("native_goto".equals(task.skill()) && task.label().startsWith("Explore")) {
            exploreFailures++;
            exploreHeading = null;
        }
        if (task.label().startsWith("Recover")) dropSite = null;
        if (task.label().startsWith("Cut through")) exploreStalls = 0; // That way is closed too; walk and look again.
        // A foe in a hole or across a ravine cannot be reached; leave it be for a while rather than try forever.
        if ((task.label().startsWith("Close in") || task.label().startsWith("Fight")) && foe != null) {
            ignoredFoes.put(foe, tick + 600);
            foe = null;
        }
        if (task.label().startsWith("Dig") || task.label().startsWith("Tunnel") || task.label().startsWith("Climb")) digDirection = digDirection.rotateYClockwise();
        if (task.label().startsWith("Obsidian")) obsidianSite = null;
        if (task.label().startsWith("Portal") && "native_goto".equals(task.skill())) portalSite = null;
    }

    void placedWorkstation(BlockPos pos) { ownWorkstations.add(pos.asLong()); }

    /** Remembers where the player died, like a person would, so it can try once to pick its things back up. */
    void died(BlockPos pos, ServerWorld world, long tick, boolean killedByMob) {
        obsidianSite = null;
        exploreHeading = null;
        approaches.clear();
        // Lava burns the drops; anything else stays on the ground for about five minutes. Whatever killed the
        // player is probably still standing over them, so a mob kill is not worth walking back into.
        boolean burned = world.getFluidState(pos).isIn(FluidTags.LAVA) || world.getBlockState(pos).isOf(Blocks.FIRE);
        dropSite = burned || killedByMob ? null : pos.toImmutable();
        dropSiteUntil = tick + 5_000;
    }

    /**
     * Skips a target that failed. Repeat failures at the same place back off longer, so a fast-ticking server
     * cannot make the player cycle through the same few unreachable targets.
     */
    private void avoid(BlockPos pos, boolean column, long tick) {
        int count = failures.merge(pos.asLong(), 1, Integer::sum);
        long until = tick + (2400L << Math.min(count - 1, 5));
        blockedUntil.merge(pos.asLong(), until, Math::max); // The exact target, whatever kind it was.
        if (!column) return;
        for (int dy = -6; dy <= 6; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long key = pos.add(dx, dy, dz).asLong();
            unreachableUntil.merge(key, until, Math::max);
            unreachableFrom.put(key, lastFeet);
        }
    }

    /** Skipped until its back-off ends and the player has moved well away from where the walk failed. */
    private boolean unreachable(BlockPos pos) {
        Long until = unreachableUntil.get(pos.asLong());
        if (until == null) return false;
        BlockPos from = unreachableFrom.get(pos.asLong());
        return until > lastTick || from != null && from.getSquaredDistance(lastFeet) < 8 * 8;
    }

    private boolean blocked(BlockPos pos) {
        Long until = blockedUntil.get(pos.asLong());
        return until != null && until > lastTick;
    }

    private static BlockPos position(Map<String, String> args) {
        try {
            if (!args.containsKey("x")) return null;
            return new BlockPos(Integer.parseInt(args.get("x")), Integer.parseInt(args.get("y")), Integer.parseInt(args.get("z")));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    // ------------------------------------------------------------------ planning

    Optional<Task> next(NativePlayerBody body, EditPolicy.ServerPolicy policy, long tick) {
        lastTick = tick;
        lastFeet = body.entity().getBlockPos().toImmutable();
        if (exploredFrom != null) {
            exploreStalls = exploredFrom.getSquaredDistance(lastFeet) < 4 * 4 ? exploreStalls + 1 : 0;
            exploredFrom = null;
        }
        this.policy = policy;
        ServerPlayerEntity player = body.entity();
        PortalRunSnapshot snapshot = snapshot(body);
        stage = PortalRunPlanner.stage(snapshot);
        var steps = new java.util.ArrayList<>(PortalRunPlanner.plan(snapshot));
        // Opportunistic upkeep the plan does not order: water while a source is close, a sword before going deep.
        if (snapshot.emptyBuckets() > 0 && snapshot.waterBuckets() == 0 && obsidianSite == null
                && knowledge.nearest(player.getServerWorld(), WorldKnowledge.Kind.WATER, player.getBlockPos(), p -> !blocked(p) && p.getSquaredDistance(player.getBlockPos()) < 64 * 64).isPresent()) {
            steps.addFirst(new PortalRunPlanner.Step(PortalRunPlanner.Action.FILL_WATER, "", 1, stage, "Fill a bucket with water while it is close"));
        }
        if (home == null) home = player.getBlockPos().toImmutable();
        Optional<Task> defense = defend(player);
        if (defense.isPresent()) {
            progress = stageLabel(stage) + ": fight back - " + defense.get().label();
            return defense;
        }
        // Hurt with nothing after it: heal before going on (food in hand is eaten by the survival tasks).
        if (player.getHealth() < 10 && (player.getHungerManager().getFoodLevel() >= 18 || snapshot.edibleFood() > 0)) {
            progress = stageLabel(stage) + ": heal before going on";
            return Optional.of(new Task("native_wait", Map.of("seconds", "4"), "Rest to heal"));
        }
        // Leaving to gather: take the table and an emptied furnace along, as a player would.
        if (TRAVEL.contains(steps.getLast().action())) {
            Optional<Task> pickup = pickUpWorkstation(body, snapshot);
            if (pickup.isPresent()) {
                progress = stageLabel(stage) + ": take workstations along - " + pickup.get().label();
                return pickup;
            }
        }
        // Nights on the surface belong to skeletons: get below ground, and leave surface jobs for daylight.
        boolean night = player.getServerWorld().isNight() && player.getServerWorld().getDimension().hasSkyLight();
        if (night && !underground(player)) {
            Optional<Task> dig = digDown(body, player.getBlockY() - 3, "Dig in for the night");
            if (dig.isPresent()) {
                progress = stageLabel(stage) + ": shelter for the night - " + dig.get().label();
                return dig;
            }
        }
        if (night && steps.getLast().action() == PortalRunPlanner.Action.GATHER_LOGS) {
            progress = stageLabel(stage) + ": wait for daylight before going up for logs";
            return Optional.of(new Task("native_wait", Map.of("seconds", "4"), "Wait for daylight"));
        }
        Optional<Task> recover = recoverDrops(player, tick);
        if (recover.isPresent()) {
            progress = stageLabel(stage) + ": pick up what was dropped at death - " + recover.get().label();
            return recover;
        }
        for (PortalRunPlanner.Step step : steps) {
            if (night && step.action() == PortalRunPlanner.Action.HUNT && snapshot.edibleFood() > 0) continue;
            Optional<Task> task = realize(step, snapshot, body, policy);
            if (task.isPresent() && setAside.getOrDefault(task.get(), Long.MIN_VALUE) > tick) continue;
            if (task.isPresent()) {
                progress = stageLabel(stage) + ": " + step.why() + " - " + task.get().label();
                return task;
            }
        }
        progress = stageLabel(stage) + ": waiting for a feasible step";
        return Optional.empty();
    }

    private Optional<Task> pickUpWorkstation(NativePlayerBody body, PortalRunSnapshot s) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (s.craftingTables() == 0) {
            var table = nearestWorkstation(player, Blocks.CRAFTING_TABLE, 6);
            if (table.isPresent() && breakableNow(body, table.get())) return Optional.of(breakAt(table.get(), "Pick up the crafting table"));
        }
        if (s.furnaces() == 0 && ItemMoves.count(player.getInventory(), Items.RAW_IRON) == 0) {
            var furnace = nearestWorkstation(player, Blocks.FURNACE, 6);
            if (furnace.isPresent() && world.getBlockEntity(furnace.get()) instanceof AbstractFurnaceBlockEntity block && block.isEmpty()
                    && breakableNow(body, furnace.get())) {
                return Optional.of(breakAt(furnace.get(), "Pick up the furnace"));
            }
        }
        return Optional.empty();
    }

    private Optional<Task> recoverDrops(ServerPlayerEntity player, long tick) {
        if (dropSite == null) return Optional.empty();
        var world = player.getServerWorld();
        if (tick > dropSiteUntil || !world.isChunkLoaded(dropSite) || player.getBlockPos().getSquaredDistance(dropSite) > 140 * 140) {
            dropSite = null;
            return Optional.empty();
        }
        var drops = world.getEntitiesByClass(ItemEntity.class, new Box(dropSite).expand(8), drop -> drop.isAlive() && !drop.isInLava());
        if (drops.isEmpty()) {
            dropSite = null;
            return Optional.empty();
        }
        ItemEntity nearest = drops.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
        return Optional.of(go(nearest.getBlockPos(), 0.8, true, "Recover dropped items near " + dropSite.toShortString()));
    }

    private static String stageLabel(PortalRunPlanner.Stage stage) {
        String name = stage.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    PortalRunSnapshot snapshot(NativePlayerBody body) {
        var player = body.entity();
        var inventory = player.getInventory();
        int logs = 0, planks = 0, sticks = 0, cobble = 0, coal = 0, rawIron = 0, iron = 0, diamonds = 0, flint = 0, gravel = 0,
                obsidian = 0, buckets = 0, water = 0, fns = 0, tables = 0, furnaces = 0, food = 0, pick = 0, sword = 0, chest = 0;
        for (ItemStack worn : player.getArmorItems()) {
            if (!worn.isEmpty() && Registries.ITEM.getId(worn.getItem()).getPath().endsWith("_chestplate")) chest = Math.max(chest, tier(worn.getItem()));
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            int n = stack.getCount();
            Item item = stack.getItem();
            if (stack.isIn(ItemTags.LOGS)) logs += n;
            else if (stack.isIn(ItemTags.PLANKS)) planks += n;
            else if (item == Items.STICK) sticks += n;
            else if (stack.isIn(ItemTags.STONE_TOOL_MATERIALS) || item == Items.COBBLESTONE) cobble += n;
            else if (item == Items.COAL || item == Items.CHARCOAL) coal += n;
            else if (item == Items.RAW_IRON) rawIron += n;
            else if (item == Items.IRON_INGOT) iron += n;
            else if (item == Items.DIAMOND) diamonds += n;
            else if (item == Items.FLINT) flint += n;
            else if (item == Items.GRAVEL) gravel += n;
            else if (item == Items.OBSIDIAN) obsidian += n;
            else if (item == Items.BUCKET) buckets += n;
            else if (item == Items.WATER_BUCKET) water += n;
            else if (item == Items.FLINT_AND_STEEL) fns += n;
            else if (item == Items.CRAFTING_TABLE) tables += n;
            else if (item == Items.FURNACE) furnaces += n;
            if (stack.contains(DataComponentTypes.FOOD) && safeFood(stack)) food += n;
            pick = Math.max(pick, stack.isIn(ItemTags.PICKAXES) ? tier(item) : 0);
            sword = Math.max(sword, stack.isIn(ItemTags.SWORDS) ? tier(item) : 0);
            if (Registries.ITEM.getId(item).getPath().endsWith("_chestplate")) chest = Math.max(chest, tier(item));
        }
        BlockPos feet = player.getBlockPos();
        boolean tableNearby = nearestWorkstation(player, Blocks.CRAFTING_TABLE, 16).isPresent();
        Optional<BlockPos> furnacePos = nearestWorkstation(player, Blocks.FURNACE, 16);
        boolean furnaceSmelting = false;
        int furnaceOutput = 0;
        if (furnacePos.isPresent() && player.getServerWorld().getBlockEntity(furnacePos.get()) instanceof AbstractFurnaceBlockEntity furnace) {
            furnaceSmelting = !furnace.getStack(0).isEmpty();
            furnaceOutput = furnace.getStack(2).getCount();
            if (furnace.getStack(0).isOf(Items.RAW_IRON)) rawIron += furnace.getStack(0).getCount();
            if (furnace.getStack(2).isOf(Items.IRON_INGOT)) iron += furnace.getStack(2).getCount();
        }
        // Obsidian already set into the frame still counts toward the ten.
        if (portalSite != null) {
            obsidian += frameObsidian(player.getServerWorld(), portalSite);
            cobble += frameCorners(player.getServerWorld(), portalSite); // Placed corners count toward the four too.
        }
        boolean framed = portalSite != null && portalComplete(player.getServerWorld(), portalSite);
        boolean lit = portalSite != null && player.getServerWorld().getBlockState(portalSite.at(1, 1)).isOf(Blocks.NETHER_PORTAL);
        return new PortalRunSnapshot(logs, planks, sticks, cobble, coal, rawIron, iron, diamonds, flint, gravel, obsidian, buckets, water,
                fns, tables, furnaces, food, pick, sword, chest, tableNearby, furnacePos.isPresent(), furnaceSmelting, furnaceOutput,
                player.getHungerManager().getFoodLevel(), framed, lit);
    }

    private static boolean safeFood(ItemStack stack) {
        return !stack.isOf(Items.ROTTEN_FLESH) && !stack.isOf(Items.SPIDER_EYE) && !stack.isOf(Items.POISONOUS_POTATO)
                && !stack.isOf(Items.PUFFERFISH) && !stack.isOf(Items.CHICKEN) && !stack.isOf(Items.CHORUS_FRUIT);
    }

    static int tier(Item item) {
        String id = Registries.ITEM.getId(item).getPath();
        if (id.startsWith("netherite_")) return 5;
        if (id.startsWith("diamond_")) return 4;
        if (id.startsWith("iron_")) return 3;
        if (id.startsWith("stone_")) return 2;
        if (id.startsWith("wooden_") || id.startsWith("golden_")) return 1;
        return 0;
    }

    private Optional<BlockPos> nearestWorkstation(ServerPlayerEntity player, net.minecraft.block.Block block, int radius) {
        var kind = block == Blocks.CRAFTING_TABLE ? WorldKnowledge.Kind.CRAFTING_TABLE : WorldKnowledge.Kind.FURNACE;
        BlockPos feet = player.getBlockPos();
        return knowledge.nearest(player.getServerWorld(), kind, feet, p -> p.getSquaredDistance(feet) <= radius * radius
                && ownWorkstations.contains(p.asLong()) && !blocked(p) && !unreachable(p));
    }

    // ------------------------------------------------------------------ steps

    private Optional<Task> realize(PortalRunPlanner.Step step, PortalRunSnapshot s, NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        return switch (step.action()) {
            case DONE -> Optional.empty();
            case COLLECT_SMELTED -> nearestWorkstation(player, Blocks.FURNACE, 16).flatMap(f -> atWorkstation(player, f,
                    new Task("native_smelt", coords(f, Map.of("action", "extract")), "Take smelted iron")));
            case HUNT -> hunt(player);
            case GATHER_LOGS -> gather(body, WorldKnowledge.Kind.LOG, 64, "logs")
                    .or(() -> underground(player) ? climb(body, "Climb toward the surface for logs")
                            .or(() -> home == null ? Optional.empty() : Optional.of(go(home, 3, true, "Head back up toward home")))
                            : explore(body, "logs"));
            // Stone is everywhere below: take what is close, otherwise dig for it rather than walk to a far cave wall.
            case GATHER_STONE -> gather(body, WorldKnowledge.Kind.STONE, 6, "stone").or(() -> digDown(body, player.getBlockY() - 6, "Dig for stone"));
            case MINE_COAL -> gather(body, WorldKnowledge.Kind.COAL_ORE, oreRadius(player, 32), "coal ore").or(() -> gather(body, WorldKnowledge.Kind.LOG, 48, "logs for fuel"))
                    .or(() -> tunnel(body, "Tunnel for coal"));
            case MINE_IRON -> gather(body, WorldKnowledge.Kind.IRON_ORE, oreRadius(player, 40), "iron ore").or(() -> descendOrTunnel(body, IRON_TOP, IRON_TARGET, IRON_BOTTOM, "iron"));
            case MINE_DIAMOND -> gather(body, WorldKnowledge.Kind.DIAMOND_ORE, oreRadius(player, 40), "diamond ore")
                    .or(() -> gather(body, WorldKnowledge.Kind.IRON_ORE, 6, "iron ore on the way"))
                    .or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, -63, "diamonds"));
            case CRAFT -> craft(body, step.item());
            case PLACE_TABLE -> placeItem(body, Items.CRAFTING_TABLE, "Place a crafting table");
            case PLACE_FURNACE -> placeItem(body, Items.FURNACE, "Place a furnace");
            case SMELT -> smelt(body);
            case AWAIT_SMELT -> Optional.of(new Task("native_wait", Map.of("seconds", "3"), "Wait by the furnace"));
            // Water just poured for obsidian guards the mining: mine what it hardened before taking it back.
            case FILL_WATER -> (obsidianSite != null
                    ? obsidian(body).or(() -> gather(body, WorldKnowledge.Kind.OBSIDIAN, 8, "obsidian", true)) : Optional.<Task>empty())
                    .or(() -> fillWater(body)).or(() -> search(body, "water"));
            case MAKE_OBSIDIAN -> gather(body, WorldKnowledge.Kind.OBSIDIAN, 32, "obsidian", true).or(() -> obsidian(body))
                    .or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, -63, "lava"));
            case GATHER_FLINT -> flint(body);
            case BUILD_PORTAL -> buildPortal(body);
            case IGNITE_PORTAL -> ignite(body);
        };
    }

    /**
     * Underground, ore across an open cave is a walk into the dark where skeletons wait; take what the tunnel
     * exposes close by and keep digging instead.
     */
    private static int oreRadius(ServerPlayerEntity player, int surfaceRadius) {
        return underground(player) ? 12 : surfaceRadius;
    }

    private static Map<String, String> coords(BlockPos pos, Map<String, String> extra) {
        var args = new LinkedHashMap<String, String>();
        args.put("x", String.valueOf(pos.getX()));
        args.put("y", String.valueOf(pos.getY()));
        args.put("z", String.valueOf(pos.getZ()));
        args.putAll(extra);
        return Map.copyOf(args);
    }

    private static Task go(BlockPos target, double range, boolean partial, String label) {
        return new Task("native_goto", coords(target, Map.of("range", String.valueOf(range), "partial", String.valueOf(partial))), label);
    }

    private static Task breakAt(BlockPos pos, String label) {
        return new Task("native_break", coords(pos, Map.of()), label);
    }

    /**
     * A break whose drops wait on the ground: cells opened for a passage are walked through next anyway, and
     * chasing each drop would carry the player off the staircase or out of its working spot.
     */
    private static Task clearAt(BlockPos pos, String label) {
        return new Task("native_break", coords(pos, Map.of("collect", "false")), label);
    }

    private static double eyeDistance(ServerPlayerEntity player, BlockPos pos) {
        return player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
    }

    /** A block this player could break right now from where it stands. */
    private boolean breakableNow(NativePlayerBody body, BlockPos pos) {
        return breakableNow(body, pos, false);
    }

    private boolean breakableNow(NativePlayerBody body, BlockPos pos, boolean guarded) {
        var player = body.entity();
        if (eyeDistance(player, pos) > REACH || !body.miningEligible(pos)) return false;
        if (RoamingEdits.breakDenial(player, pos, policy, guarded) != null) return false;
        return NativeBreakAction.bestTool(body, player.getServerWorld().getBlockState(pos)) != -2;
    }

    private boolean harvestable(NativePlayerBody body, BlockPos pos) {
        var state = body.entity().getServerWorld().getBlockState(pos);
        return NativeBreakAction.bestTool(body, state) != -2;
    }

    private Optional<Task> atWorkstation(ServerPlayerEntity player, BlockPos station, Task use) {
        if (eyeDistance(player, station) <= 4.0) return Optional.of(use);
        return Optional.of(go(station, 2.5, false, "Walk to the workstation"));
    }

    /**
     * Break the nearest seen block of a kind, walking into reach first and clearing whatever blocks the view
     * (leaves around a trunk, a dirt lip over an ore). A target that stays out of reach after two approaches
     * is skipped along with its column.
     */
    private Optional<Task> gather(NativePlayerBody body, WorldKnowledge.Kind kind, int radius, String what) {
        return gather(body, kind, radius, what, false);
    }

    private Optional<Task> gather(NativePlayerBody body, WorldKnowledge.Kind kind, int radius, String what, boolean guarded) {
        var player = body.entity();
        BlockPos feet = player.getBlockPos();
        BlockPos support = feet.down();
        Predicate<BlockPos> usable = p -> !blocked(p) && !unreachable(p) && !p.equals(support) && p.getSquaredDistance(feet) <= radius * radius
                && (kind != WorldKnowledge.Kind.OBSIDIAN || madeFromLava(player.getServerWorld(), p))
                && harvestable(body, p) && player.getServerWorld().canPlayerModifyAt(player, p)
                && (kind != WorldKnowledge.Kind.LOG || p.getY() <= feet.getY() + 4 && reachableFromGround(player.getServerWorld(), p));
        for (int candidate = 0; candidate < 4; candidate++) {
            var target = knowledge.nearest(player.getServerWorld(), kind, feet, usable);
            if (target.isEmpty()) return Optional.empty();
            BlockPos pos = target.get();
            if (breakableNow(body, pos, guarded)) {
                return Optional.of(new Task("native_break", coords(pos, Map.of("guarded", String.valueOf(guarded))),
                        "Mine " + what + " at " + pos.toShortString()));
            }
            var cover = obstruction(body, pos);
            if (cover.isPresent()) return Optional.of(breakAt(cover.get(), "Clear the way to " + what + " at " + pos.toShortString()));
            // Plans are re-evaluated often; only finished walks count as approaches.
            if (approaches.getOrDefault(pos.asLong(), 0) < 2) {
                return Optional.of(go(pos, 3.5, false, "Walk to " + what + " at " + pos.toShortString()));
            }
            approaches.remove(pos.asLong());
            avoid(pos, true, lastTick);
        }
        return Optional.empty();
    }

    /**
     * A log a player standing beside its trunk can reach: the trunk stands on solid ground no more than four
     * blocks below it. Logs left hanging after the trunk below was cut would need a pillar, so they are skipped.
     */
    private static boolean reachableFromGround(ServerWorld world, BlockPos log) {
        BlockPos below = log.down();
        for (int depth = 1; depth <= 4; depth++, below = below.down()) {
            BlockState state = world.getBlockState(below);
            if (state.isIn(BlockTags.LOGS)) continue;
            return !state.getCollisionShape(world, below).isEmpty() && !state.isIn(BlockTags.LEAVES);
        }
        return false;
    }

    /**
     * Obsidian that water and lava just made, still touching one of them, as opposed to somebody's build (or this
     * player's own portal frame), which is never mined for material.
     */
    private boolean madeFromLava(ServerWorld world, BlockPos pos) {
        if (portalSite != null) {
            for (int up = 0; up <= 4; up++) for (int across = 0; across <= 3; across++) if (portalSite.at(across, up).equals(pos)) return false;
        }
        for (Direction direction : Direction.values()) {
            var fluid = world.getFluidState(pos.offset(direction));
            if (fluid.isIn(FluidTags.LAVA) || direction != Direction.DOWN && fluid.isIn(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** The first block between the player's eyes and an in-reach target, when the player could break it now. */
    private Optional<BlockPos> obstruction(NativePlayerBody body, BlockPos pos) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (eyeDistance(player, pos) > REACH) return Optional.empty();
        var hit = world.raycast(new RaycastContext(player.getEyePos(), Vec3d.ofCenter(pos), RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos)) return Optional.empty();
        BlockPos first = hit.getBlockPos().toImmutable();
        if (blocked(first) || liquidNear(world, first) || !breakableNow(body, first)) return Optional.empty();
        return Optional.of(first);
    }

    /** Underground, search by tunnelling along the current level; on the surface, walk out and look. */
    private Optional<Task> search(NativePlayerBody body, String what) {
        var player = body.entity();
        if (underground(player)) return tunnel(body, "Tunnel for " + what).or(() -> explore(body, what));
        return explore(body, what).or(() -> tunnel(body, "Tunnel for " + what));
    }

    /**
     * Walk out to look, and when walking keeps ending where it started (hemmed in by leaves, trunks or a drop the
     * routes cannot cross), cut a two-high way through along the heading, as a person would.
     */
    private Optional<Task> explore(NativePlayerBody body, String what) {
        var player = body.entity();
        if (exploreStalls >= 2 && exploreHeading != null) {
            digDirection = exploreHeading;
            Optional<Task> cut = tunnel(body, "Cut through " + exploreHeading.asString() + " for " + what);
            if (cut.isPresent()) return cut;
        }
        return explore(player, what);
    }

    private static boolean underground(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        // Below the ground surface (leaves do not count as cover) with no open sky: a shaft or a cave, not a forest floor.
        return !world.isSkyVisible(feet.up()) && feet.getY() < world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - 2;
    }

    /**
     * Head toward unexplored ground along a steady heading, turning when a heading keeps failing, would leave
     * the area around home, or ends on open water.
     */
    private Optional<Task> explore(ServerPlayerEntity player, String what) {
        var world = player.getServerWorld();
        if (exploreHeading == null) {
            Direction[] headings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
            exploreHeading = headings[Math.floorMod(exploreFailures + (int) (player.getUuid().getLeastSignificantBits() & 3), 4)];
        }
        BlockPos anchor = home == null ? player.getBlockPos() : home;
        for (int turn = 0; turn < 4; turn++, exploreHeading = exploreHeading.rotateYClockwise()) {
            BlockPos ahead = player.getBlockPos().offset(exploreHeading, 28);
            if (!world.isChunkLoaded(ahead)) ahead = player.getBlockPos().offset(exploreHeading, 12);
            if (!world.isChunkLoaded(ahead)) continue;
            if (Math.hypot(ahead.getX() - anchor.getX(), ahead.getZ() - anchor.getZ()) > EXPLORE_RADIUS) continue;
            int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ahead.getX(), ahead.getZ());
            boolean onSurface = player.getBlockY() > surface - 8;
            if (onSurface && !world.getFluidState(new BlockPos(ahead.getX(), surface - 1, ahead.getZ())).isEmpty()) continue;
            BlockPos target = new BlockPos(ahead.getX(), onSurface ? surface : player.getBlockY(), ahead.getZ());
            if (blocked(target) || unreachable(target)) continue;
            return Optional.of(go(target, 3, true, "Explore " + exploreHeading.asString() + " for " + what));
        }
        return Optional.empty();
    }

    private Optional<Task> descendOrTunnel(NativePlayerBody body, int top, int target, int bottom, String what) {
        int y = body.entity().getBlockY();
        if (y > Math.min(top, target + 3)) return digDown(body, target, "Dig down toward " + what);
        if (y < bottom) return Optional.empty();
        return tunnel(body, "Tunnel for " + what);
    }

    /**
     * Staircase one step down along {@code digDirection}: clear head, feet and step-down cells ahead, then step.
     * Every break is still a separate recorded action with the lava and support checks.
     */
    private Optional<Task> digDown(NativePlayerBody body, int targetY, String label) {
        var player = body.entity();
        if (player.getBlockY() <= targetY) return tunnel(body, label);
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (int turn = 0; turn < 4; turn++) {
            BlockPos ahead = feet.offset(digDirection);
            BlockPos step = ahead.down();
            List<BlockPos> clear = List.of(ahead.up(), ahead, step);
            boolean refused = false;
            for (BlockPos cell : clear) {
                if (passable(world, cell)) continue;
                if (blocked(cell) || liquidNear(world, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                refused = true;
                break;
            }
            if (!refused) {
                // Never step into a drop deeper than the navigator allows; turn away from a pit instead.
                BlockPos landing = step;
                while (passable(world, landing.down()) && landing.getY() > feet.getY() - NativeNavigator.MAX_SAFE_DROP) landing = landing.down();
                if (!passable(world, landing.down()) && !liquidNear(world, landing)) {
                    return Optional.of(go(landing, 0.4, false, label + ": step down"));
                }
            }
            digDirection = digDirection.rotateYClockwise();
        }
        return Optional.empty();
    }

    /**
     * A staircase one step up along {@code digDirection}: clear the head room above, then the two cells over the
     * block ahead, and step up onto it. The way out when the job needs the surface.
     */
    private Optional<Task> climb(NativePlayerBody body, String label) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (int turn = 0; turn < 4; turn++) {
            BlockPos step = feet.offset(digDirection);
            BlockPos landing = step.up();
            boolean refused = passable(world, step); // Nothing to climb onto this way.
            for (BlockPos cell : List.of(feet.up(2), landing, landing.up())) {
                if (refused || passable(world, cell)) continue;
                if (blocked(cell) || liquidNear(world, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                refused = true;
            }
            if (!refused) return Optional.of(go(landing, 0.4, false, label + ": step up"));
            digDirection = digDirection.rotateYClockwise();
        }
        return Optional.empty();
    }

    /** A two-high tunnel forward along {@code digDirection}; walls it exposes are scanned for ores. */
    private Optional<Task> tunnel(NativePlayerBody body, String label) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (int turn = 0; turn < 4; turn++) {
            BlockPos ahead = feet.offset(digDirection);
            boolean refused = false;
            for (BlockPos cell : List.of(ahead.up(), ahead)) {
                if (passable(world, cell)) continue;
                if (blocked(cell) || liquidNear(world, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                refused = true;
                break;
            }
            if (!refused && !passable(world, ahead.down()) && !liquidNear(world, ahead)) {
                return Optional.of(go(ahead, 0.4, false, label + ": advance"));
            }
            if (!refused && passable(world, ahead.down()) && !passable(world, ahead.down(2))) {
                return Optional.of(go(ahead.down(), 0.4, false, label + ": step down"));
            }
            digDirection = digDirection.rotateYClockwise();
        }
        return Optional.empty();
    }

    private static boolean passable(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty() && !state.getFluidState().isIn(FluidTags.LAVA);
    }

    /** Lava on any side, or water above or beside, which would pour into the opening. */
    private static boolean liquidNear(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            var fluid = world.getFluidState(pos.offset(direction));
            if (fluid.isIn(FluidTags.LAVA) || direction != Direction.DOWN && fluid.isIn(FluidTags.WATER)) return true;
        }
        return world.getFluidState(pos).isIn(FluidTags.LAVA);
    }

    // ------------------------------------------------------------------ crafting and smelting

    private Optional<Task> craft(NativePlayerBody body, String item) {
        String recipe = item.equals("planks") ? planksRecipe(body) : item;
        if (recipe == null) return Optional.empty();
        if (body.planCraft2x2(recipe).isPresent()) return Optional.of(new Task("native_craft", Map.of("recipe", recipe), "Craft " + recipe.replace('_', ' ')));
        var player = body.entity();
        var table = nearestWorkstation(player, Blocks.CRAFTING_TABLE, 16);
        if (table.isEmpty()) {
            var anyTable = body.findNearbyBlock(Blocks.CRAFTING_TABLE, 4.5);
            if (anyTable.isPresent() && body.planCraft(recipe, anyTable.get()).isPresent()) {
                return Optional.of(new Task("native_craft", Map.of("recipe", recipe), "Craft " + recipe.replace('_', ' ')));
            }
            return Optional.empty();
        }
        if (eyeDistance(player, table.get()) <= 4.4 && body.planCraft(recipe, table.get()).isPresent()) {
            return Optional.of(new Task("native_craft", Map.of("recipe", recipe), "Craft " + recipe.replace('_', ' ')));
        }
        return Optional.of(go(table.get(), 2.5, false, "Walk to the crafting table"));
    }

    private static String planksRecipe(NativePlayerBody body) {
        var inventory = body.entity().getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isIn(ItemTags.LOGS)) continue;
            String path = Registries.ITEM.getId(stack.getItem()).getPath().replace("stripped_", "");
            for (String suffix : List.of("_log", "_wood", "_stem", "_hyphae")) {
                if (path.endsWith(suffix)) {
                    String recipe = path.substring(0, path.length() - suffix.length()) + "_planks";
                    if (body.planCraft2x2(recipe).isPresent()) return recipe;
                }
            }
        }
        return null;
    }

    /** Puts an owned block item on solid ground beside the player, moving it to the hotbar first if needed. */
    private Optional<Task> placeItem(NativePlayerBody body, Item item, String label) {
        var player = body.entity();
        var inventory = player.getInventory();
        int slot = -1;
        for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(item)) { slot = s; break; }
        if (slot < 0) return Optional.empty();
        if (slot >= 9) return Optional.of(swapToHotbar(body, slot, "Ready " + Registries.ITEM.getId(item).getPath().replace('_', ' ')));
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            for (BlockPos spot : List.of(feet.offset(direction), feet.offset(direction, 2))) {
                if (!world.getBlockState(spot).isReplaceable() || !world.getFluidState(spot).isEmpty()) continue;
                if (world.getBlockState(spot.down()).getCollisionShape(world, spot.down()).isEmpty()) continue;
                if (eyeDistance(player, spot) > 4.4 || blocked(spot)) continue;
                return Optional.of(new Task("native_place", coords(spot, Map.of("side", "up", "slot", String.valueOf(slot))), label));
            }
        }
        // Make room: carve a pocket into a side wall at foot level, keeping the way along a tunnel or staircase open.
        List<Direction> sides = List.of(digDirection.rotateYClockwise(), digDirection.rotateYCounterclockwise(), digDirection.getOpposite(), digDirection);
        for (Direction direction : sides) {
            BlockPos wall = feet.offset(direction);
            if (passable(world, wall) || liquidNear(world, wall) || blocked(wall)) continue;
            if (world.getBlockState(wall.down()).getCollisionShape(world, wall.down()).isEmpty()) continue;
            if (breakableNow(body, wall)) return Optional.of(clearAt(wall, "Clear space for a workstation"));
        }
        return Optional.empty();
    }

    private Task swapToHotbar(NativePlayerBody body, int slot, String label) {
        var inventory = body.entity().getInventory();
        int target = 8;
        for (int s = 0; s < 9; s++) if (inventory.getStack(s).isEmpty()) { target = s; break; }
        return new Task("native_swap", Map.of("source", String.valueOf(slot), "target", String.valueOf(target)), label);
    }

    private Optional<Task> smelt(NativePlayerBody body) {
        var player = body.entity();
        var furnace = nearestWorkstation(player, Blocks.FURNACE, 16);
        if (furnace.isEmpty()) return placeItem(body, Items.FURNACE, "Place a furnace");
        var inventory = player.getInventory();
        int input = -1, fuel = -1, fuelScore = -1;
        for (int s = 0; s < 36; s++) {
            ItemStack stack = inventory.getStack(s);
            if (stack.isOf(Items.RAW_IRON) && input < 0) input = s;
            if (NativePlayerBody.isSafeAutonomousFuel(stack) && !stack.isOf(Items.RAW_IRON)) {
                int score = NativePlayerBody.fuelPriorityScore(stack);
                if (score > fuelScore) { fuelScore = score; fuel = s; }
            }
        }
        if (input < 0) return Optional.empty();
        var args = new LinkedHashMap<String, String>();
        args.put("action", "load");
        args.put("input_slot", String.valueOf(input));
        if (fuel >= 0) args.put("fuel_slot", String.valueOf(fuel));
        return atWorkstation(player, furnace.get(), new Task("native_smelt", coords(furnace.get(), args), "Load raw iron and fuel"));
    }

    // ------------------------------------------------------------------ water, obsidian, flint

    private Optional<Task> fillWater(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var source = knowledge.nearest(world, WorldKnowledge.Kind.WATER, feet, p -> !blocked(p) && p.getSquaredDistance(feet) < 64 * 64);
        if (source.isEmpty()) return Optional.empty();
        BlockPos water = source.get();
        if (eyeDistance(player, water) <= 4.0) {
            return Optional.of(new Task("native_bucket", coords(water, Map.of("mode", "fill")), "Fill a bucket at " + water.toShortString()));
        }
        return Optional.of(go(water, 2.5, false, "Walk to water at " + water.toShortString()));
    }

    private Optional<Task> obsidian(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (obsidianSite != null && !siteStillUseful(world, obsidianSite)) obsidianSite = null;
        if (obsidianSite == null) {
            obsidianSite = findObsidianSite(player).orElse(null);
            pourWaits = 0;
        }
        if (obsidianSite == null) return Optional.empty();
        ObsidianSite site = obsidianSite;
        BlockPos feet = player.getBlockPos();
        boolean hardened = world.getBlockState(site.lava()).isOf(Blocks.OBSIDIAN);
        // Flowing water nudges a player on the far shore; from the poured cell the obsidian is close and in plain view.
        if (hardened && !feet.equals(site.water())) return Optional.of(go(site.water(), 0.3, false, "Obsidian: step onto the shore"));
        if (!hardened && !feet.equals(site.stand())) {
            return Optional.of(go(site.stand(), 0.3, false, "Obsidian: stand beside lava at " + site.lava().toShortString()));
        }
        boolean lavaStill = world.getFluidState(site.lava()).isIn(FluidTags.LAVA);
        boolean waterPlaced = world.getFluidState(site.water()).isIn(FluidTags.WATER);
        if (lavaStill && !waterPlaced) {
            return Optional.of(new Task("native_bucket", coords(site.water(), Map.of("mode", "pour")), "Obsidian: pour water beside lava"));
        }
        // Poured water takes a few ticks to run over the lava; give it a moment before giving up on the site.
        if (lavaStill && waterPlaced && pourWaits < 3) {
            pourWaits++;
            return Optional.of(new Task("native_wait", Map.of("seconds", "1"), "Obsidian: let the water reach the lava"));
        }
        if (hardened) {
            return Optional.of(new Task("native_break", coords(site.lava(), Map.of("guarded", "true")), "Obsidian: mine " + site.lava().toShortString()));
        }
        if (waterPlaced && world.getFluidState(site.water()).isStill()) {
            obsidianSite = null; // Done with this lava source; recover the water for the next one.
            return Optional.of(new Task("native_bucket", coords(site.water(), Map.of("mode", "fill")), "Obsidian: recover the water"));
        }
        obsidianSite = null;
        return Optional.empty();
    }

    private static boolean siteStillUseful(ServerWorld world, ObsidianSite site) {
        return world.getFluidState(site.lava()).isIn(FluidTags.LAVA) || world.getBlockState(site.lava()).isOf(Blocks.OBSIDIAN)
                || world.getFluidState(site.water()).isIn(FluidTags.WATER);
    }

    private Optional<ObsidianSite> findObsidianSite(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var navigator = new NativeNavigator(world);
        ObsidianSite best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos lava : knowledge.all(WorldKnowledge.Kind.LAVA)) {
            if (blocked(lava) || lava.getSquaredDistance(feet) > 48 * 48) continue;
            if (!world.getFluidState(lava).isStill() || !world.getFluidState(lava).isIn(FluidTags.LAVA)) continue;
            BlockState below = world.getBlockState(lava.down());
            if (below.getCollisionShape(world, lava.down()).isEmpty()) continue;
            for (Direction d : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                BlockPos water = lava.offset(d);
                BlockPos stand = water.offset(d);
                if (!navigator.clear(water) || world.getBlockState(water.down()).getCollisionShape(world, water.down()).isEmpty()) continue;
                if (!navigator.standable(stand) || lavaAdjacent(world, stand) || lavaAdjacentExcept(world, water, lava)) continue;
                double distance = stand.getSquaredDistance(feet);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new ObsidianSite(lava.toImmutable(), water.toImmutable(), stand.toImmutable());
                }
            }
            // A pool recessed below its shore, the usual cave case: pour onto the shore beside the open cell above
            // the lava, and the water runs out over the pool, hardening every source it covers.
            BlockPos above = lava.up();
            if (!navigator.clear(above) || !world.getFluidState(above).isEmpty()) continue;
            for (Direction d : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                BlockPos water = above.offset(d);
                BlockPos stand = water.offset(d);
                if (!navigator.standable(water) || !world.getFluidState(water.down()).isEmpty()) continue;
                if (!navigator.standable(stand) || lavaAdjacent(world, stand) || lavaAdjacent(world, water)) continue;
                double distance = stand.getSquaredDistance(feet) + 4; // Prefer the one-source method when both exist.
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new ObsidianSite(lava.toImmutable(), water.toImmutable(), stand.toImmutable());
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean lavaAdjacent(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) if (world.getFluidState(pos.offset(direction)).isIn(FluidTags.LAVA)) return true;
        return world.getFluidState(pos).isIn(FluidTags.LAVA) || world.getFluidState(pos.up()).isIn(FluidTags.LAVA);
    }

    private static boolean lavaAdjacentExcept(ServerWorld world, BlockPos pos, BlockPos allowed) {
        for (Direction direction : Direction.values()) {
            BlockPos near = pos.offset(direction);
            if (!near.equals(allowed) && world.getFluidState(near).isIn(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private Optional<Task> flint(NativePlayerBody body) {
        var player = body.entity();
        var inventory = player.getInventory();
        int gravelSlot = -1;
        for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(Items.GRAVEL)) { gravelSlot = s; break; }
        // Gravel carried from earlier digging can be set down and dug again until it drops flint.
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockPos spot = feet.offset(direction);
            if (world.getBlockState(spot).isOf(Blocks.GRAVEL) && breakableNow(body, spot)) return Optional.of(breakAt(spot, "Dig gravel for flint"));
        }
        if (gravelSlot >= 0) return placeItem(body, Items.GRAVEL, "Set down gravel to dig for flint");
        return gather(body, WorldKnowledge.Kind.GRAVEL, 48, "gravel").or(() -> search(body, "gravel"));
    }

    // ------------------------------------------------------------------ portal

    private Optional<Task> buildPortal(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (portalSite == null) portalSite = choosePortalSite(player).orElse(null);
        if (portalSite == null) return tunnel(body, "Portal: look for open ground");
        PortalSite site = portalSite;
        if (!player.getBlockPos().equals(site.stand())) return Optional.of(go(site.stand(), 0.3, false, "Portal: stand in front of the frame site"));
        // Open a room: the row in front of the frame first, so every frame cell can be seen and reached.
        for (int up = 0; up <= 4; up++) {
            for (int across = 0; across <= 3; across++) {
                BlockPos cell = site.front(across, up);
                if (cell.equals(site.stand()) || cell.equals(site.stand().up())) continue;
                if (world.getBlockState(cell).isReplaceable() && world.getFluidState(cell).isEmpty()) continue;
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, "Portal: clear " + cell.toShortString()));
                portalSite = null;
                return Optional.empty();
            }
        }
        // Then the frame plane and its interior.
        for (int up = 0; up <= 4; up++) {
            for (int across = 0; across <= 3; across++) {
                BlockPos cell = site.at(across, up);
                BlockState state = world.getBlockState(cell);
                boolean frame = across == 0 || across == 3 || up == 0 || up == 4;
                boolean corner = (across == 0 || across == 3) && (up == 0 || up == 4);
                boolean wanted = frame ? (corner ? !state.isReplaceable() : state.isOf(Blocks.OBSIDIAN)) : state.isAir();
                if (wanted) continue;
                if (!state.isReplaceable() && !state.isOf(Blocks.OBSIDIAN)) {
                    if (breakableNow(body, cell)) return Optional.of(clearAt(cell, "Portal: clear " + cell.toShortString()));
                    portalSite = null;
                    return Optional.empty();
                }
            }
        }
        // Place: bottom row and corners on the ground, sides upward, top corners, then the top row against them.
        int[][] order = {{1, 0}, {2, 0}, {0, 0}, {3, 0}, {0, 1}, {3, 1}, {0, 2}, {3, 2}, {0, 3}, {3, 3}, {0, 4}, {3, 4}, {1, 4}, {2, 4}};
        for (int[] cell : order) {
            BlockPos pos = site.at(cell[0], cell[1]);
            boolean corner = (cell[0] == 0 || cell[0] == 3) && (cell[1] == 0 || cell[1] == 4);
            BlockState state = world.getBlockState(pos);
            if (corner ? !state.isReplaceable() : state.isOf(Blocks.OBSIDIAN)) continue;
            Item item = corner ? cornerItem(body) : Items.OBSIDIAN;
            if (item == null) return Optional.empty();
            int slot = hotbarSlot(body, item);
            if (slot < 0) return Optional.of(swapToHotbar(body, slotOf(body, item), "Portal: ready " + Registries.ITEM.getId(item).getPath()));
            Direction side = cell[1] == 4 && !corner ? (cell[0] == 1 ? site.along() : site.along().getOpposite()) : Direction.UP;
            return Optional.of(new Task("native_place", coords(pos, Map.of("side", side.getName(), "slot", String.valueOf(slot))),
                    "Portal: place " + (corner ? "corner" : "obsidian") + " at " + pos.toShortString()));
        }
        return Optional.empty();
    }

    private static Item cornerItem(NativePlayerBody body) {
        var inventory = body.entity().getInventory();
        for (Item item : List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DIRT, Items.NETHERRACK)) {
            for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(item)) return item;
        }
        return null;
    }

    private static int hotbarSlot(NativePlayerBody body, Item item) {
        var inventory = body.entity().getInventory();
        for (int s = 0; s < 9; s++) if (inventory.getStack(s).isOf(item)) return s;
        return -1;
    }

    private static int slotOf(NativePlayerBody body, Item item) {
        var inventory = body.entity().getInventory();
        for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(item)) return s;
        return -1;
    }

    private Optional<PortalSite> choosePortalSite(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        var navigator = new NativeNavigator(world);
        BlockPos feet = player.getBlockPos();
        for (int distance = 2; distance <= 2; distance++) {
            for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                Direction along = facing.rotateYClockwise();
                BlockPos origin = feet.offset(facing, distance).offset(along.getOpposite(), 1);
                BlockPos stand = feet;
                boolean ok = navigator.standable(stand);
                for (int across = 0; across <= 3 && ok; across++) {
                    BlockPos ground = origin.offset(along, across).down();
                    if (world.getBlockState(ground).getCollisionShape(world, ground).isEmpty()) ok = false;
                    for (int up = 0; up <= 4 && ok; up++) {
                        BlockPos cell = origin.offset(along, across).up(up);
                        var state = world.getBlockState(cell);
                        if (!world.getFluidState(cell).isEmpty() || state.hasBlockEntity() || state.getHardness(world, cell) < 0) ok = false;
                        else if (player.getEyePos().distanceTo(Vec3d.ofCenter(cell)) > 4.4) ok = false;
                    }
                }
                for (int across = 0; across <= 3 && ok; across++) {
                    for (int up = 0; up <= 4 && ok; up++) {
                        BlockPos cell = origin.offset(along, across).up(up).offset(facing.getOpposite());
                        if (!world.getFluidState(cell).isEmpty() || world.getBlockState(cell).hasBlockEntity()) ok = false;
                    }
                }
                if (ok) return Optional.of(new PortalSite(origin.toImmutable(), along, facing, stand.toImmutable()));
            }
        }
        return Optional.empty();
    }

    private static int frameObsidian(ServerWorld world, PortalSite site) {
        int placed = 0;
        for (int up = 0; up <= 4; up++) {
            for (int across = 0; across <= 3; across++) {
                boolean frame = across == 0 || across == 3 || up == 0 || up == 4;
                boolean corner = (across == 0 || across == 3) && (up == 0 || up == 4);
                if (frame && !corner && world.getBlockState(site.at(across, up)).isOf(Blocks.OBSIDIAN)) placed++;
            }
        }
        return placed;
    }

    private static int frameCorners(ServerWorld world, PortalSite site) {
        int placed = 0;
        for (int[] corner : new int[][]{{0, 0}, {3, 0}, {0, 4}, {3, 4}}) {
            if (!world.getBlockState(site.at(corner[0], corner[1])).isReplaceable()) placed++;
        }
        return placed;
    }

    private static boolean portalComplete(ServerWorld world, PortalSite site) {
        for (int up = 0; up <= 4; up++) {
            for (int across = 0; across <= 3; across++) {
                boolean frame = across == 0 || across == 3 || up == 0 || up == 4;
                boolean corner = (across == 0 || across == 3) && (up == 0 || up == 4);
                if (frame && !corner && !world.getBlockState(site.at(across, up)).isOf(Blocks.OBSIDIAN)) return false;
            }
        }
        return true;
    }

    private Optional<Task> ignite(NativePlayerBody body) {
        if (portalSite == null) return Optional.empty();
        int slot = hotbarSlot(body, Items.FLINT_AND_STEEL);
        if (slot < 0) {
            int anywhere = slotOf(body, Items.FLINT_AND_STEEL);
            return anywhere < 0 ? Optional.empty() : Optional.of(swapToHotbar(body, anywhere, "Portal: ready flint and steel"));
        }
        BlockPos base = portalSite.at(1, 0);
        return Optional.of(new Task("native_ignite_portal", coords(base, Map.of("slot", String.valueOf(slot))), "Portal: light it"));
    }

    // ------------------------------------------------------------------ defense

    /**
     * A hostile mob that is after this player and in sight is dealt with before work continues: close in and
     * strike, as a player with a weapon would rather than keep mining while a skeleton shoots. Creepers are left
     * to the runtime's retreat, and a badly hurt player leaves it to the tactical retreat too.
     */
    private Optional<Task> defend(ServerPlayerEntity player) {
        if (player.getHealth() < 6) return backOff(player);
        var threats = threats(player);
        HostileEntity nearest = threats.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        if (nearest == null) nearest = rememberedFoe(player);
        if (nearest == null) return Optional.empty();
        // A skeleton that steps behind cover is still out there: keep after it for a few seconds.
        foe = nearest.getUuid();
        foeUntil = lastTick + 100;
        String name = Registries.ENTITY_TYPE.getId(nearest.getType()).getPath();
        Task strike = strike(player, nearest, "Fight " + name);
        return Optional.of(new Task("native_fight", strike.args(), strike.label()));
    }

    /** Too hurt to fight: put distance between the player and whatever is after it. */
    private Optional<Task> backOff(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        var hunters = world.getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(16, 6, 16),
                mob -> mob.isAlive() && mob.getTarget() == player);
        if (hunters.isEmpty()) return Optional.empty();
        Vec3d away = Vec3d.ZERO;
        for (HostileEntity mob : hunters) away = away.add(player.getPos().subtract(mob.getPos()).normalize());
        if (away.lengthSquared() < 1.0E-4) return Optional.empty();
        away = away.normalize().multiply(10);
        BlockPos target = BlockPos.ofFloored(player.getX() + away.x, player.getY(), player.getZ() + away.z);
        return Optional.of(go(target, 3, true, "Back off from " + Registries.ENTITY_TYPE.getId(hunters.getFirst().getType()).getPath()));
    }

    private HostileEntity rememberedFoe(ServerPlayerEntity player) {
        if (foe == null || lastTick > foeUntil || player.getHealth() < 6 || ignoredFoes.getOrDefault(foe, Long.MIN_VALUE) >= lastTick) return null;
        if (player.getServerWorld().getEntity(foe) instanceof HostileEntity mob && mob.isAlive() && mob.getTarget() == player
                && !(mob instanceof EndermanEntity)
                && player.squaredDistanceTo(mob) < 24 * 24) {
            return mob;
        }
        foe = null;
        return null;
    }

    private List<HostileEntity> threats(ServerPlayerEntity player) {
        if (player.getHealth() < 6) return List.of();
        return player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(16, 6, 16),
                mob -> mob.isAlive() && mob.getTarget() == player && !(mob instanceof CreeperEntity) && player.canSee(mob)
                        // An enderman is not chased (it out-teleports and out-hits early gear); it is struck when it comes close.
                        && !(mob instanceof EndermanEntity && player.squaredDistanceTo(mob) > 3.5 * 3.5)
                        && ignoredFoes.getOrDefault(mob.getUuid(), Long.MIN_VALUE) < lastTick);
    }

    /** Work in progress should stop for a fight: a hostile in sight is after the player and the task is not the fight. */
    boolean shouldStopForAFight(ServerPlayerEntity player, Task running) {
        if (running == null || running.label().startsWith("Fight") || running.label().startsWith("Close in")
                || running.label().startsWith("Back off")) return false;
        return !threats(player).isEmpty();
    }

    /** Work stopped for a fight is not a failed target; plan again without counting it. */
    void taskInterrupted(Task task) {
        progress = "Interrupted: " + (task == null ? "work" : task.label());
    }

    private static Task strike(ServerPlayerEntity player, net.minecraft.entity.LivingEntity target, String label) {
        int weapon = -1;
        float best = 0;
        for (int s = 0; s < 9; s++) {
            var stack = player.getInventory().getStack(s);
            if (!stack.isIn(ItemTags.SWORDS) && !stack.isIn(ItemTags.AXES)) continue;
            float score = tier(stack.getItem()) + (stack.isIn(ItemTags.SWORDS) ? 0.5f : 0);
            if (score > best) { best = score; weapon = s; }
        }
        var args = new LinkedHashMap<String, String>();
        args.put("target", target.getUuid().toString());
        if (weapon >= 0) args.put("slot", String.valueOf(weapon));
        return new Task("native_attack", Map.copyOf(args), label);
    }

    // ------------------------------------------------------------------ food

    private Optional<Task> hunt(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        Box area = player.getBoundingBox().expand(24, 8, 24);
        var drops = world.getEntitiesByClass(ItemEntity.class, area.contract(16, 4, 16),
                drop -> drop.getStack().contains(DataComponentTypes.FOOD) && safeFood(drop.getStack()) && player.canSee(drop)
                        && !blocked(drop.getBlockPos()));
        if (!drops.isEmpty()) {
            ItemEntity drop = drops.getFirst();
            return Optional.of(go(drop.getBlockPos(), 0.6, false, "Pick up food"));
        }
        var animals = world.getEntitiesByClass(PassiveEntity.class, area, animal -> animal.isAlive() && !animal.isBaby() && !blocked(animal.getBlockPos())
                && (animal instanceof CowEntity || animal instanceof PigEntity || animal instanceof SheepEntity
                || animal instanceof ChickenEntity || animal instanceof RabbitEntity) && player.canSee(animal));
        if (animals.isEmpty()) return Optional.empty();
        PassiveEntity nearest = animals.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
        if (player.squaredDistanceTo(nearest) <= 2.8 * 2.8) {
            return Optional.of(strike(player, nearest, "Hunt " + Registries.ENTITY_TYPE.getId(nearest.getType()).getPath()));
        }
        return Optional.of(go(nearest.getBlockPos(), 2.0, false, "Approach " + Registries.ENTITY_TYPE.getId(nearest.getType()).getPath()));
    }
}
