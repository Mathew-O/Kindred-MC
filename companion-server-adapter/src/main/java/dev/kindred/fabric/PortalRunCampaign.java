package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.survival.PortalRunPlanner;
import dev.kindred.core.survival.PortalRunSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
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
    private static final int IRON_TOP = 56, IRON_TARGET = 16, IRON_BOTTOM = -24;
    private static final int DIAMOND_TOP = -52, DIAMOND_TARGET = -58;

    private final WorldKnowledge knowledge = new WorldKnowledge();
    private final Set<Long> ownWorkstations = new HashSet<>();
    private final Map<Long, Long> blockedUntil = new HashMap<>();
    private final Map<Long, Integer> failures = new HashMap<>();
    private final Map<Long, Integer> approaches = new HashMap<>();
    private Direction digDirection = Direction.NORTH;
    private Direction exploreHeading;
    private int exploreFailures;
    private ObsidianSite obsidianSite;
    private PortalSite portalSite;
    private BlockPos dropSite;
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
        if (completed || task == null) return;
        BlockPos pos = position(task.args());
        // An unreachable trunk or ore column usually stays unreachable; skip its neighbours too.
        if (pos != null) avoid(pos, "native_goto".equals(task.skill()) && task.label().startsWith("Walk to"), tick);
        if ("native_goto".equals(task.skill()) && task.label().startsWith("Explore")) {
            exploreFailures++;
            exploreHeading = null;
        }
        if (task.label().startsWith("Recover")) dropSite = null;
        if (task.label().startsWith("Dig") || task.label().startsWith("Tunnel")) digDirection = digDirection.rotateYClockwise();
        if (task.label().startsWith("Obsidian")) obsidianSite = null;
        if (task.label().startsWith("Portal") && "native_goto".equals(task.skill())) portalSite = null;
    }

    void placedWorkstation(BlockPos pos) { ownWorkstations.add(pos.asLong()); }

    /** Remembers where the player died, like a person would, so it can try once to pick its things back up. */
    void died(BlockPos pos, ServerWorld world, long tick) {
        obsidianSite = null;
        exploreHeading = null;
        approaches.clear();
        // Lava burns the drops; anything else stays on the ground for about five minutes.
        dropSite = world.getFluidState(pos).isIn(FluidTags.LAVA) || world.getBlockState(pos).isOf(Blocks.FIRE) ? null : pos.toImmutable();
        dropSiteUntil = tick + 5_000;
    }

    /**
     * Skips a target that failed. Repeat failures at the same place back off longer, so a fast-ticking server
     * cannot make the player cycle through the same few unreachable targets.
     */
    private void avoid(BlockPos pos, boolean column, long tick) {
        int count = failures.merge(pos.asLong(), 1, Integer::sum);
        long until = tick + (2400L << Math.min(count - 1, 5));
        if (!column) {
            blockedUntil.merge(pos.asLong(), until, Math::max);
            return;
        }
        for (int dy = -6; dy <= 6; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            blockedUntil.merge(pos.add(dx, dy, dz).asLong(), until, Math::max);
        }
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
        this.policy = policy;
        ServerPlayerEntity player = body.entity();
        PortalRunSnapshot snapshot = snapshot(body);
        stage = PortalRunPlanner.stage(snapshot);
        var steps = new java.util.ArrayList<>(PortalRunPlanner.plan(snapshot));
        // Opportunistic upkeep the plan does not order: water while a source is close, a sword before going deep.
        if (snapshot.emptyBuckets() > 0 && snapshot.waterBuckets() == 0
                && knowledge.nearest(player.getServerWorld(), WorldKnowledge.Kind.WATER, player.getBlockPos(), p -> !blocked(p) && p.getSquaredDistance(player.getBlockPos()) < 24 * 24).isPresent()) {
            steps.addFirst(new PortalRunPlanner.Step(PortalRunPlanner.Action.FILL_WATER, "", 1, stage, "Fill a bucket with water while it is close"));
        }
        Optional<Task> recover = recoverDrops(player, tick);
        if (recover.isPresent()) {
            progress = stageLabel(stage) + ": pick up what was dropped at death - " + recover.get().label();
            return recover;
        }
        for (PortalRunPlanner.Step step : steps) {
            Optional<Task> task = realize(step, snapshot, body, policy);
            if (task.isPresent()) {
                progress = stageLabel(stage) + ": " + step.why() + " - " + task.get().label();
                return task;
            }
        }
        progress = stageLabel(stage) + ": waiting for a feasible step";
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
                obsidian = 0, buckets = 0, water = 0, fns = 0, tables = 0, furnaces = 0, food = 0, pick = 0, sword = 0;
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
        boolean framed = portalSite != null && portalComplete(player.getServerWorld(), portalSite);
        boolean lit = portalSite != null && player.getServerWorld().getBlockState(portalSite.at(1, 1)).isOf(Blocks.NETHER_PORTAL);
        return new PortalRunSnapshot(logs, planks, sticks, cobble, coal, rawIron, iron, diamonds, flint, gravel, obsidian, buckets, water,
                fns, tables, furnaces, food, pick, sword, tableNearby, furnacePos.isPresent(), furnaceSmelting, furnaceOutput,
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
                && ownWorkstations.contains(p.asLong()) && !blocked(p));
    }

    // ------------------------------------------------------------------ steps

    private Optional<Task> realize(PortalRunPlanner.Step step, PortalRunSnapshot s, NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        return switch (step.action()) {
            case DONE -> Optional.empty();
            case COLLECT_SMELTED -> nearestWorkstation(player, Blocks.FURNACE, 16).flatMap(f -> atWorkstation(player, f,
                    new Task("native_smelt", coords(f, Map.of("action", "extract")), "Take smelted iron")));
            case HUNT -> hunt(player);
            case GATHER_LOGS -> gather(body, WorldKnowledge.Kind.LOG, 64, "logs").or(() -> explore(player, "logs"));
            case GATHER_STONE -> gather(body, WorldKnowledge.Kind.STONE, 12, "stone").or(() -> digDown(body, player.getBlockY() - 6, "Dig for stone"));
            case MINE_COAL -> gather(body, WorldKnowledge.Kind.COAL_ORE, 32, "coal ore").or(() -> gather(body, WorldKnowledge.Kind.LOG, 48, "logs for fuel"))
                    .or(() -> tunnel(body, "Tunnel for coal"));
            case MINE_IRON -> gather(body, WorldKnowledge.Kind.IRON_ORE, 40, "iron ore").or(() -> descendOrTunnel(body, IRON_TOP, IRON_TARGET, IRON_BOTTOM, "iron"));
            case MINE_DIAMOND -> gather(body, WorldKnowledge.Kind.DIAMOND_ORE, 40, "diamond ore")
                    .or(() -> gather(body, WorldKnowledge.Kind.IRON_ORE, 6, "iron ore on the way"))
                    .or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, -63, "diamonds"));
            case CRAFT -> craft(body, step.item());
            case PLACE_TABLE -> placeItem(body, Items.CRAFTING_TABLE, "Place a crafting table");
            case PLACE_FURNACE -> placeItem(body, Items.FURNACE, "Place a furnace");
            case SMELT -> smelt(body);
            case AWAIT_SMELT -> Optional.of(new Task("native_wait", Map.of("seconds", "3"), "Wait by the furnace"));
            case FILL_WATER -> fillWater(body).or(() -> explore(player, "water"));
            case MAKE_OBSIDIAN -> obsidian(body).or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, -63, "lava"));
            case GATHER_FLINT -> flint(body);
            case BUILD_PORTAL -> buildPortal(body);
            case IGNITE_PORTAL -> ignite(body);
        };
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

    private static double eyeDistance(ServerPlayerEntity player, BlockPos pos) {
        return player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
    }

    /** A block this player could break right now from where it stands. */
    private boolean breakableNow(NativePlayerBody body, BlockPos pos) {
        var player = body.entity();
        if (eyeDistance(player, pos) > REACH || !body.miningEligible(pos)) return false;
        if (RoamingEdits.breakDenial(player, pos, policy, false) != null) return false;
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
        var player = body.entity();
        BlockPos feet = player.getBlockPos();
        BlockPos support = feet.down();
        Predicate<BlockPos> usable = p -> !blocked(p) && !p.equals(support) && p.getSquaredDistance(feet) <= radius * radius
                && harvestable(body, p) && player.getServerWorld().canPlayerModifyAt(player, p)
                && (kind != WorldKnowledge.Kind.LOG || p.getY() <= feet.getY() + 4);
        for (int candidate = 0; candidate < 4; candidate++) {
            var target = knowledge.nearest(player.getServerWorld(), kind, feet, usable);
            if (target.isEmpty()) return Optional.empty();
            BlockPos pos = target.get();
            if (breakableNow(body, pos)) return Optional.of(breakAt(pos, "Mine " + what + " at " + pos.toShortString()));
            var cover = obstruction(body, pos);
            if (cover.isPresent()) return Optional.of(breakAt(cover.get(), "Clear the way to " + what + " at " + pos.toShortString()));
            if (approaches.merge(pos.asLong(), 1, Integer::sum) <= 2) {
                return Optional.of(go(pos, 3.5, false, "Walk to " + what + " at " + pos.toShortString()));
            }
            approaches.remove(pos.asLong());
            avoid(pos, true, lastTick);
        }
        return Optional.empty();
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

    /** Head toward unexplored ground along a steady heading, turning when a heading keeps failing. */
    private Optional<Task> explore(ServerPlayerEntity player, String what) {
        var world = player.getServerWorld();
        if (exploreHeading == null) {
            Direction[] headings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
            exploreHeading = headings[Math.floorMod(exploreFailures + (int) (player.getUuid().getLeastSignificantBits() & 3), 4)];
        }
        BlockPos ahead = player.getBlockPos().offset(exploreHeading, 28);
        if (!world.isChunkLoaded(ahead)) ahead = player.getBlockPos().offset(exploreHeading, 12);
        int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ahead.getX(), ahead.getZ());
        BlockPos target = new BlockPos(ahead.getX(), player.getBlockY() > surface - 8 ? surface : player.getBlockY(), ahead.getZ());
        return Optional.of(go(target, 3, true, "Explore " + exploreHeading.asString() + " for " + what));
    }

    private Optional<Task> descendOrTunnel(NativePlayerBody body, int top, int target, int bottom, String what) {
        int y = body.entity().getBlockY();
        if (y > top) return digDown(body, target, "Dig down toward " + what);
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
                if (breakableNow(body, cell)) return Optional.of(breakAt(cell, label + ": clear " + cell.toShortString()));
                refused = true;
                break;
            }
            if (!refused) {
                BlockPos landing = step;
                while (passable(world, landing.down()) && landing.getY() > step.getY() - 3) landing = landing.down();
                if (!passable(world, landing.down()) && !liquidNear(world, landing)) {
                    return Optional.of(go(landing, 0.4, false, label + ": step down"));
                }
            }
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
                if (breakableNow(body, cell)) return Optional.of(breakAt(cell, label + ": clear " + cell.toShortString()));
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

    private static boolean liquidNear(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            var fluid = world.getFluidState(pos.offset(direction));
            if (fluid.isIn(FluidTags.LAVA)) return true;
        }
        return world.getFluidState(pos).isIn(FluidTags.LAVA) || world.getFluidState(pos.up()).isIn(FluidTags.WATER);
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
                if (!world.getBlockState(spot.up()).isReplaceable()) continue;
                if (eyeDistance(player, spot) > 4.4 || blocked(spot)) continue;
                return Optional.of(new Task("native_place", coords(spot, Map.of("side", "up", "slot", String.valueOf(slot))), label));
            }
        }
        // Make room: clear the block in front so the next attempt has a spot.
        BlockPos ahead = feet.offset(player.getHorizontalFacing());
        if (!passable(world, ahead) && breakableNow(body, ahead)) return Optional.of(breakAt(ahead, "Clear space for a workstation"));
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
        var source = knowledge.nearest(world, WorldKnowledge.Kind.WATER, feet, p -> !blocked(p) && p.getSquaredDistance(feet) < 48 * 48);
        if (source.isEmpty()) return Optional.empty();
        BlockPos water = source.get();
        if (eyeDistance(player, water) <= 4.0 && !player.isTouchingWater()) {
            return Optional.of(new Task("native_bucket", coords(water, Map.of("mode", "fill")), "Fill a bucket at " + water.toShortString()));
        }
        return Optional.of(go(water, 2.5, false, "Walk to water at " + water.toShortString()));
    }

    private Optional<Task> obsidian(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (obsidianSite != null && !siteStillUseful(world, obsidianSite)) obsidianSite = null;
        if (obsidianSite == null) obsidianSite = findObsidianSite(player).orElse(null);
        if (obsidianSite == null) return Optional.empty();
        ObsidianSite site = obsidianSite;
        BlockPos feet = player.getBlockPos();
        if (!feet.equals(site.stand())) return Optional.of(go(site.stand(), 0.3, false, "Obsidian: stand beside lava at " + site.lava().toShortString()));
        boolean lavaStill = world.getFluidState(site.lava()).isIn(FluidTags.LAVA);
        boolean waterPlaced = world.getFluidState(site.water()).isIn(FluidTags.WATER);
        if (lavaStill && !waterPlaced) {
            return Optional.of(new Task("native_bucket", coords(site.water(), Map.of("mode", "pour")), "Obsidian: pour water beside lava"));
        }
        if (world.getBlockState(site.lava()).isOf(Blocks.OBSIDIAN)) {
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
        return gather(body, WorldKnowledge.Kind.GRAVEL, 48, "gravel").or(() -> tunnel(body, "Tunnel for gravel"))
                .or(() -> explore(player, "gravel"));
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
                if (breakableNow(body, cell)) return Optional.of(breakAt(cell, "Portal: clear " + cell.toShortString()));
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
                    if (breakableNow(body, cell)) return Optional.of(breakAt(cell, "Portal: clear " + cell.toShortString()));
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

    // ------------------------------------------------------------------ food

    private Optional<Task> hunt(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        Box area = player.getBoundingBox().expand(24, 8, 24);
        var drops = world.getEntitiesByClass(ItemEntity.class, area.contract(16, 4, 16),
                drop -> drop.getStack().contains(DataComponentTypes.FOOD) && safeFood(drop.getStack()) && player.canSee(drop));
        if (!drops.isEmpty()) {
            ItemEntity drop = drops.getFirst();
            return Optional.of(go(drop.getBlockPos(), 0.6, false, "Pick up food"));
        }
        var animals = world.getEntitiesByClass(PassiveEntity.class, area, animal -> animal.isAlive() && !animal.isBaby()
                && (animal instanceof CowEntity || animal instanceof PigEntity || animal instanceof SheepEntity
                || animal instanceof ChickenEntity || animal instanceof RabbitEntity) && player.canSee(animal));
        if (animals.isEmpty()) return Optional.empty();
        PassiveEntity nearest = animals.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
        if (player.squaredDistanceTo(nearest) <= 9.0) {
            int weapon = -1;
            for (int s = 0; s < 9; s++) {
                var stack = player.getInventory().getStack(s);
                if (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES)) { weapon = s; break; }
            }
            var args = new LinkedHashMap<String, String>();
            args.put("target", nearest.getUuid().toString());
            if (weapon >= 0) args.put("slot", String.valueOf(weapon));
            return Optional.of(new Task("native_attack", Map.copyOf(args), "Hunt " + Registries.ENTITY_TYPE.getId(nearest.getType()).getPath()));
        }
        return Optional.of(go(nearest.getBlockPos(), 2.0, false, "Approach " + Registries.ENTITY_TYPE.getId(nearest.getType()).getPath()));
    }
}
