package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.guide.Need;
import dev.kindred.core.guide.Objective;
import dev.kindred.core.survival.EyeFlights;
import dev.kindred.core.survival.PortalRunPlanner;
import dev.kindred.core.survival.PortalRunSnapshot;
import dev.kindred.core.survival.StrongholdRunPlanner;
import dev.kindred.core.survival.StrongholdRunSnapshot;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The goal's way on from its lit Nether portal to the End portal (F-25c6), as {@link StrongholdRunPlanner} plans it.
 * The campaign's own rules still come first (getting out of water and walls, fights, holing up, eating), and the
 * Overworld work it already knows (hunting, stone, crafting) is done its way. This keeps what the player has learnt on
 * the trip: where its portals are, the fortress it found, each eye's flight and where they cross.
 */
final class StrongholdLeg {
    /** How far to the side of the first flight the second eye is thrown from. */
    static final int SIDE_THROW = 200;
    /** Within this many blocks of the crossing, it digs down. */
    static final int NEAR = 24;
    /** Game time without a new pearl after which the hunt counts as stalled. */
    static final long PEARL_STALL_TICKS = 20L * 60 * 10;
    /** One walk toward a far place covers at most this far. */
    private static final int LEG = 80;

    private final PortalRunCampaign campaign;
    private BlockPos overworldPortal, netherPortal, fortress;
    private final List<EyeFlights.Flight> flights = new ArrayList<>();
    private double[] estimate;
    private BlockPos asideFor;
    private long pearlHuntSince = -1;
    private int pearlsAtHunt;
    private String dimension = "";
    private Direction heading;
    /** Exploring: where the heading was taken, the furthest gained along it, and passes since it last gained. */
    private BlockPos exploreFrom;
    private double exploreBest;
    private long exploreSince;
    /**
     * Exploring by made ways: passes left before walking to stands again, and where it began; passes left in which a
     * made way must change something; passes without headway; where the last walk to a stand set out from. Counted in
     * passes as well as time: at a sprinted server's pace a timed spell ran out within two passes, and the explorer
     * flipped between walking and making a way in one spot (staged run 9).
     */
    private int makePasses, editOnlyPasses, stallPasses;
    private BlockPos makeFrom, lastWalkFrom;
    private static final int MAKE_PASSES = 16, STALL_PASSES = 24;
    /** Sight lines per pass, and how far they reach. */
    private static final int FAR_RAYS = 24;
    private static final double FAR_SIGHT = 128;
    private int farSweep;
    /** Game time without headway (as well as {@link #STALL_PASSES} passes) before a heading is given up. */
    private static final long EXPLORE_PATIENCE = 800;

    /**
     * A leg task has ended: a failed walk while exploring turns to making a way or cutting through for a while, and a
     * failed walk along a made way leaves only ways that change something for a while.
     */
    void taskFinished(PortalRunCampaign.Task task, boolean completed, long tick) {
        if (completed || !task.skill().equals("native_goto")) return;
        if (task.label().startsWith("Explore ")) startMaking(null);
        if (task.label().startsWith("Make a way ")) editOnlyPasses = MAKE_PASSES;
    }

    private void startMaking(BlockPos from) {
        if (makePasses == 0) makeFrom = from;
        makePasses = MAKE_PASSES;
        lastWalkFrom = null;
    }

    private double along(BlockPos from, BlockPos to) {
        return (to.getX() - from.getX()) * heading.getOffsetX() + (to.getZ() - from.getZ()) * heading.getOffsetZ();
    }
    private final Set<Long> visited = new HashSet<>();
    /** How far off the fortress was when the last walk there set out, or -1. */
    private double fortressWalkFrom = -1;
    /** Chunk columns it has explored through in this dimension: a heading back over them is a poor one. */
    private final Set<Long> explored = new HashSet<>();
    private StrongholdRunPlanner.Stage stage;
    private StrongholdRunSnapshot lastSnapshot;
    private boolean killRemembered;

    /** On the dragon goal, past the End portal: the fight (E-39). */
    private final EndFightLeg endFight;

    EndFightLeg endFight() { return endFight; }

    /** The follow-up queue's chunks this pass (F-25c7). */
    private StrongholdRunPlanner.Provisions provisions = StrongholdRunPlanner.Provisions.NONE;
    private String progress = "";
    private BlockPos endPortal;
    private boolean arrived, pinned;
    /** The last dry ground it stood on in the Overworld: where to dig down from when the place is out at sea. */
    private BlockPos lastDryLand;
    /** Deep enough to be in or below most strongholds' upper parts, and still above the deepest. */
    static final int DIG_TO = 8;
    /**
     * Exploring the Nether stays above this: the lava sea's surface is at 31, and fortresses stand well above it. Below,
     * every way on is lava; live staged run 5 tunnelled down to 32 and spent its time there.
     */
    static final int NETHER_FLOOR = 40;

    StrongholdLeg(PortalRunCampaign campaign) {
        this.endFight = new EndFightLeg(campaign, this);
        this.campaign = campaign;
    }

    /** The leg has begun: the portal is lit, or the player has already been through it. */
    boolean active(ServerPlayerEntity player) {
        // Back from the End with nothing: the run's own early stages make a pickaxe again before the leg goes on.
        if (endFight.restocking() && !pickaxe(player)) return false;
        return overworldPortal != null || netherPortal != null || player.getServerWorld().getRegistryKey() != World.OVERWORLD
                || campaign.litPortal(player).isPresent() || seenPortal(player).isPresent();
    }

    private static boolean pickaxe(ServerPlayerEntity player) {
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getStack(slot);
            if (stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES) && PortalRunCampaign.tier(stack.getItem()) >= 2) return true;
        }
        return false;
    }

    /** A lit portal it has seen, its own or one it came across, as players use any portal they find. */
    private Optional<BlockPos> seenPortal(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        return campaign.knowledge().nearest(world, WorldKnowledge.Kind.PORTAL, player.getBlockPos(),
                b -> world.isChunkLoaded(b) && world.getBlockState(b).isOf(Blocks.NETHER_PORTAL));
    }

    StrongholdRunPlanner.Stage stage() { return stage; }

    /** On the move between places (across water, too), rather than working where it stands. */
    boolean crossing() {
        return stage == StrongholdRunPlanner.Stage.TRAVEL || stage == StrongholdRunPlanner.Stage.LOCATE
                || stage == StrongholdRunPlanner.Stage.NETHER || stage == StrongholdRunPlanner.Stage.RETURN
                || stage == StrongholdRunPlanner.Stage.FORTRESS || stage == StrongholdRunPlanner.Stage.PEARLS;
    }
    String progress() { return progress; }
    /** Where it found the End portal, once it has. */
    Optional<BlockPos> endPortal() { return Optional.ofNullable(endPortal); }

    /**
     * Its respawn point is a bed in the Overworld within the portal room's reach of the End portal it found (E-39g):
     * the bed is down already, so none need be carried, and a death in the End starts again here.
     */
    boolean spawnByPortal(ServerPlayerEntity player) {
        BlockPos spawn = player.getSpawnPointPosition();
        if (endPortal == null || spawn == null || player.getSpawnPointDimension() != World.OVERWORLD || !spawn.isWithinDistance(endPortal, 24)) return false;
        var overworld = player.getServer().getOverworld();
        return !overworld.isChunkLoaded(spawn) || overworld.getBlockState(spawn).isIn(net.minecraft.registry.tag.BlockTags.BEDS);
    }

    private static boolean overworld(ServerPlayerEntity player) {
        return player.getServerWorld().getRegistryKey() == World.OVERWORLD;
    }

    private static boolean nether(ServerPlayerEntity player) {
        return player.getServerWorld().getRegistryKey() == World.NETHER;
    }

    /** Keeps track of the portals as the player goes through them, and of the fortress once seen. */
    private void observe(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        String here = world.getRegistryKey().getValue().toString();
        BlockPos feet = player.getBlockPos();
        boolean arrived = !here.equals(dimension);
        dimension = here;
        if (arrived) explored.clear(); // Another dimension's coordinates.
        if (overworld(player) && player.isOnGround() && !player.isTouchingWater() && world.getFluidState(feet.down()).isEmpty()) {
            lastDryLand = feet.toImmutable();
        }
        var known = java.util.Arrays.asList(overworldPortal, netherPortal, fortress, endPortal);
        if (overworld(player)) {
            if (overworldPortal == null) overworldPortal = campaign.litPortal(player).or(() -> seenPortal(player)).orElse(null);
            if (arrived) portalAt(world, feet).ifPresent(p -> overworldPortal = p);
        } else if (nether(player)) {
            if (arrived) netherPortal = portalAt(world, feet).orElse(feet.toImmutable());
            if (fortress == null) campaign.knowledge().nearest(world, WorldKnowledge.Kind.NETHER_BRICK, feet, p -> true).ifPresent(p -> fortress = p);
            if (fortress == null) lookFar(player);
        }
        if (endPortal == null && overworld(player)) {
            campaign.knowledge().nearest(world, WorldKnowledge.Kind.END_PORTAL_FRAME, feet, p -> true).ifPresent(p -> endPortal = p);
        }
        if (!known.equals(java.util.Arrays.asList(overworldPortal, netherPortal, fortress, endPortal))) memoryRevision++;
    }

    // ------------------------------------------------------------------ what outlasts a restart

    /** Bumped whenever the leg learns something worth keeping. */
    private int memoryRevision;

    int memoryRevision() { return memoryRevision; }

    /** What the leg knows, to be kept in the player's memory of this world. */
    PlayerMemoryStore.Memory remember(PlayerMemoryStore.Memory into) {
        into.dragonKilled = endFight.dragonKilled();
        into.overworldPortal = place("minecraft:overworld", overworldPortal);
        into.netherPortal = place("minecraft:the_nether", netherPortal);
        into.fortress = place("minecraft:the_nether", fortress);
        into.endPortal = place("minecraft:overworld", endPortal);
        into.flights = new ArrayList<>(flights.stream().map(f -> new PlayerMemoryStore.Flight(f.x(), f.z(), f.dx(), f.dz())).toList());
        into.stronghold = estimate == null ? null : estimate.clone();
        into.pinned = pinned;
        return into;
    }

    /** What the player remembers of this world from before a restart; what it has learnt since stands. */
    void recall(PlayerMemoryStore.Memory memory) {
        endFight.dragonKilled(memory.dragonKilled);
        if (overworldPortal == null) overworldPortal = pos(memory.overworldPortal);
        if (netherPortal == null) netherPortal = pos(memory.netherPortal);
        if (fortress == null) fortress = pos(memory.fortress);
        if (endPortal == null) endPortal = pos(memory.endPortal);
        if (flights.isEmpty() && memory.flights != null) {
            for (var f : memory.flights) flights.add(new EyeFlights.Flight(f.x(), f.z(), f.dx(), f.dz()));
        }
        if (estimate == null && memory.stronghold != null && memory.stronghold.length == 2) {
            estimate = memory.stronghold.clone();
            pinned = memory.pinned;
        }
        if (fortress != null || estimate != null) {
            KindredMod.LOGGER.info("Remembered: portals {} / {}, fortress {}, {} eye flights, stronghold {}{}", overworldPortal, netherPortal, fortress,
                    flights.size(), estimate == null ? "unknown" : Math.round(estimate[0]) + " " + Math.round(estimate[1]), pinned ? " (pinned)" : "");
        }
    }

    private static PlayerMemoryStore.Place place(String dimension, BlockPos pos) {
        return pos == null ? null : new PlayerMemoryStore.Place(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos pos(PlayerMemoryStore.Place place) {
        return place == null ? null : new BlockPos(place.x(), place.y(), place.z());
    }

    /**
     * A look round across the Nether, further than the knowledge sweep reaches: a fortress's dark bricks stand out
     * from a long way off, as players spot one across a lava sea. A fan of sight lines out to {@link #FAR_SIGHT} blocks,
     * a different part of the circle each pass; only what such a line meets first counts, and never past loaded ground.
     */
    private void lookFar(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        var eye = player.getEyePos();
        for (int i = 0; i < FAR_RAYS; i++) {
            double yaw = Math.toRadians((farSweep++ * 137.5) % 360), pitch = Math.toRadians(((farSweep * 53) % 50) - 25);
            var end = eye.add(-Math.sin(yaw) * Math.cos(pitch) * FAR_SIGHT, -Math.sin(pitch) * FAR_SIGHT, Math.cos(yaw) * Math.cos(pitch) * FAR_SIGHT);
            if (!world.isChunkLoaded(BlockPos.ofFloored(end))) continue;
            var hit = world.raycast(new net.minecraft.world.RaycastContext(eye, end, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) continue;
            BlockPos at = hit.getBlockPos();
            var kind = WorldKnowledge.classify(world.getBlockState(at));
            if (kind == WorldKnowledge.Kind.NETHER_BRICK) {
                campaign.knowledge().remember(kind, at);
                fortress = at.toImmutable();
                KindredMod.LOGGER.info("{} sees a fortress at {}", player.getName().getString(), at.toShortString());
                return;
            }
            if (kind == WorldKnowledge.Kind.WARPED) campaign.knowledge().remember(kind, at);
        }
    }

    /** The portal block the player stands in or right beside, as it is when it has just come through. */
    private static Optional<BlockPos> portalAt(ServerWorld world, BlockPos feet) {
        for (BlockPos near : BlockPos.iterateOutwards(feet, 2, 2, 2)) {
            if (world.getBlockState(near).isOf(Blocks.NETHER_PORTAL)) return Optional.of(near.toImmutable());
        }
        return Optional.empty();
    }

    StrongholdRunSnapshot snapshot(ServerPlayerEntity player, PortalRunSnapshot p) {
        int rods = 0, powder = 0, pearls = 0, eyes = 0, blocks = 0, armor = 0, boats = 0, planks = 0, logs = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            int n = stack.getCount();
            if (stack.isOf(Items.BLAZE_ROD)) rods += n;
            else if (stack.isOf(Items.BLAZE_POWDER)) powder += n;
            else if (stack.isOf(Items.ENDER_PEARL)) pearls += n;
            else if (stack.isOf(Items.ENDER_EYE)) eyes += n;
            else if (stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.COBBLED_DEEPSLATE) || stack.isOf(Items.NETHERRACK)) blocks += n;
            else if (armorPiece(stack)) armor++; // Worn (the inventory's armor slots) or carried.
            else if (stack.getItem() instanceof net.minecraft.item.BoatItem) boats += n;
            else if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) planks += n;
            else if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) logs += n;
        }
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        boolean inNether = nether(player);
        StrongholdRunSnapshot.Dimension dim = inNether ? StrongholdRunSnapshot.Dimension.NETHER
                : overworld(player) ? StrongholdRunSnapshot.Dimension.OVERWORLD : StrongholdRunSnapshot.Dimension.END;
        // A pearl hunt that has gone on a long while without a pearl is stalled; a new pearl restarts the clock, and
        // so does anything else it was doing before (the clock is the hunt's own, not the trip's).
        long now = campaign.lastTick();
        if (stage != StrongholdRunPlanner.Stage.PEARLS || pearls + eyes > pearlsAtHunt || pearlHuntSince < 0) {
            pearlHuntSince = now;
            pearlsAtHunt = pearls + eyes;
        }
        boolean stalled = now - pearlHuntSince > PEARL_STALL_TICKS;
        // Once there, it stays there to dig, even from dry land some way off when the place is under water.
        double off = estimate == null ? Double.MAX_VALUE : Math.hypot(estimate[0] - player.getX(), estimate[1] - player.getZ());
        if (off < NEAR) arrived = true;
        else if (off > 300) arrived = false;
        boolean near = estimate != null && arrived;
        // Stone bricks count as the stronghold only near where the eyes pointed, below ground.
        boolean strongholdSeen = overworld(player) && estimate != null && campaign.knowledge().nearest(world, WorldKnowledge.Kind.STONE_BRICK, feet,
                b -> Math.hypot(b.getX() - estimate[0], b.getZ() - estimate[1]) < 160 && b.getY() < world.getSeaLevel()).isPresent();
        return new StrongholdRunSnapshot(dim, inNether ? netherPortal != null : overworldPortal != null,
                p.edibleFood(), p.foodLevel(), p.swordTier(), p.pickaxeTier(), p.pickaxes(), armor, p.shields(), blocks, p.ironIngots(),
                rods, powder, pearls, eyes, inNether && fortress != null, inNether && visibleMob(player, BlazeEntity.class, 32) != null,
                visibleMob(player, EndermanEntity.class, 24) != null, stalled, flights.size(), estimate != null, near, strongholdSeen,
                endPortal != null, 0, boats + (inBoat(player) ? 1 : 0), planks, logs);
    }

    /** In a boat now: it has one, on the water. */
    private static boolean inBoat(ServerPlayerEntity player) {
        return player.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity;
    }

    private static boolean armorPiece(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        boolean piece = id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots");
        return piece && (id.startsWith("iron_") || id.startsWith("diamond_") || id.startsWith("netherite_"));
    }

    /** The nearest living mob of this kind the player can see, within {@code range}. */
    private static <T extends LivingEntity> T visibleMob(ServerPlayerEntity player, Class<T> type, double range) {
        return player.getServerWorld().getEntitiesByClass(type, player.getBoundingBox().expand(range, range / 2, range),
                        mob -> mob.isAlive() && player.canSee(mob)).stream()
                .min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    /**
     * After a death in the End: the End fight's own needs gathered again at its gate, as before the first descent, with
     * the same clocks (half a day without progress, half a minute with nothing to be had), then back to the fight.
     */
    private Optional<PortalRunCampaign.Task> restock(NativePlayerBody body, PortalRunSnapshot p, StrongholdRunSnapshot s, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        var followUps = campaign.followUps();
        provisions = followUps.assess(player, p, java.util.EnumSet.of(Objective.END_FIGHT, Objective.SURVIVAL), java.util.Set.of(Objective.END_FIGHT),
                s.swordTier(), false);
        stage = StrongholdRunPlanner.Stage.PROVISION;
        if (!provisions.due(Objective.END_FIGHT)) {
            endFight.restocked();
            return Optional.empty();
        }
        for (var chunk : provisions.gated().getOrDefault(Objective.END_FIGHT, java.util.List.of())) {
            Optional<PortalRunCampaign.Task> task = followUps.realize(chunk.need(), chunk.count(), true, chunk.why(), p, body, policy, s.swordTier());
            if (task.isPresent() && !campaign.setAside(task.get())) {
                followUps.idle(Objective.END_FIGHT, player, true);
                progress = "End: restocking after a death in the End - " + task.get().label();
                return task;
            }
        }
        followUps.idle(Objective.END_FIGHT, player, false);
        progress = "End: restocking after a death in the End - nothing of it to be had here yet";
        return Optional.of(new PortalRunCampaign.Task("native_wait", java.util.Map.of("seconds", "2"), "Wait: nothing to restock here yet"));
    }

    /** The next task toward the End portal, or empty when nothing can be done from here now. */
    Optional<PortalRunCampaign.Task> next(NativePlayerBody body, PortalRunSnapshot p, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        observe(player);
        var s = snapshot(player, p);
        lastSnapshot = s;
        // Back from the End with the dragon alive: the End fight's kit gathered again before going back through.
        if (campaign.dragonFight()) endFight.noteWhere(player);
        if (campaign.dragonFight() && endFight.restocking() && s.dimension() != StrongholdRunSnapshot.Dimension.END) {
            var restock = restock(body, p, s, policy);
            if (restock.isPresent()) return restock;
        }
        // On the dragon goal, once the End portal is found (or over there already): the fight's own steps.
        if (campaign.dragonFight() && (s.dimension() == StrongholdRunSnapshot.Dimension.END || s.portalRoomSeen())) {
            stage = StrongholdRunPlanner.Stage.DONE;
            Optional<PortalRunCampaign.Task> fight = endFight.next(body, p, policy);
            progress = endFight.progress();
            if (endFight.dragonKilled() && !killRemembered) {
                killRemembered = true;
                memoryRevision++;
            }
            return fight;
        }
        var followUps = campaign.followUps();
        // Over there the trip has begun; back here for more, its gate is due again.
        if (nether(player)) followUps.reopen(Objective.NETHER_TRIP);
        provisions = followUps.assess(player, p, ahead(s), atGate(s), s.swordTier(), false);
        stage = StrongholdRunPlanner.stage(s, provisions);
        if (stage == StrongholdRunPlanner.Stage.DESCEND) followUps.end(Objective.END_FIGHT, player, "going down to the stronghold");
        if (stage == StrongholdRunPlanner.Stage.NETHER && overworld(player)) followUps.end(Objective.NETHER_TRIP, player, "going through to the Nether");
        Objective gate = stage == StrongholdRunPlanner.Stage.PREPARE ? Objective.NETHER_TRIP
                : stage == StrongholdRunPlanner.Stage.PROVISION ? Objective.END_FIGHT : null;
        for (var step : StrongholdRunPlanner.plan(s, provisions)) {
            Optional<PortalRunCampaign.Task> task = realize(step, s, p, body, policy);
            if (task.isPresent()) {
                progress = "End: " + label(stage) + ": " + step.why() + " - " + task.get().label();
                if (gate != null) followUps.idle(gate, player, true);
                return task;
            }
        }
        if (gate != null) followUps.idle(gate, player, false);
        progress = stage == StrongholdRunPlanner.Stage.DONE ? "End: found the End portal" : "End: " + label(stage) + ": waiting for a feasible step";
        return Optional.empty();
    }

    static String label(StrongholdRunPlanner.Stage stage) {
        String name = stage.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private Optional<PortalRunCampaign.Task> realize(StrongholdRunPlanner.Step step, StrongholdRunSnapshot s, PortalRunSnapshot p,
                                                     NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        return switch (step.action()) {
            case DONE -> standBy(player);
            case HUNT -> shared(PortalRunPlanner.Action.HUNT, "", step, p, body, policy);
            case SEEK_FOOD -> shared(PortalRunPlanner.Action.SEEK_FOOD, "", step, p, body, policy);
            case GATHER_STONE -> overworld(player) ? shared(PortalRunPlanner.Action.GATHER_STONE, "", step, p, body, policy) : Optional.empty();
            case CRAFT -> campaign.craft(body, step.item());
            case ENTER_PORTAL -> enterPortal(player);
            case FIND_FORTRESS -> fortress != null && fortress.getSquaredDistance(player.getBlockPos()) > 6 * 6 ? toFortress(body) : explore(body, "a fortress");
            case FIGHT_BLAZE -> fight(player, BlazeEntity.class, 32, "Fight blaze");
            case SEEK_BLAZE -> seekBlaze(body);
            case FIGHT_ENDERMAN -> fight(player, EndermanEntity.class, 24, "Fight enderman");
            case SEEK_ENDERMAN -> seekEnderman(body);
            case THROW_EYE -> throwEye(body);
            case TRAVEL_TO_STRONGHOLD -> travel(body);
            case DIG_TO_STRONGHOLD -> descend(body);
            case FIND_PORTAL_ROOM -> findRoom(body);
            case FOLLOW_UP -> followUp(step, s, p, body, policy);
        };
    }

    /** The campaign's own way with Overworld work it already does. */
    private Optional<PortalRunCampaign.Task> shared(PortalRunPlanner.Action action, String item, StrongholdRunPlanner.Step step,
                                                    PortalRunSnapshot p, NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        return campaign.realize(new PortalRunPlanner.Step(action, item, Math.max(1, step.count()), PortalRunPlanner.Stage.DONE, step.why()), p, body, policy);
    }

    /** Into the portal and stand in it until it takes the player across. */
    private Optional<PortalRunCampaign.Task> enterPortal(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (world.getBlockState(feet).isOf(Blocks.NETHER_PORTAL)) {
            return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "4"), "Wait in the portal"));
        }
        BlockPos portal = overworld(player) ? overworldPortal : nether(player) ? netherPortal : null;
        if (portal == null || !world.getBlockState(portal).isOf(Blocks.NETHER_PORTAL)) {
            portal = campaign.knowledge().nearest(world, WorldKnowledge.Kind.PORTAL, feet, b -> world.getBlockState(b).isOf(Blocks.NETHER_PORTAL)).orElse(portal);
        }
        if (portal == null) return Optional.empty();
        // The portal's lowest block: its feet cell.
        while (world.getBlockState(portal.down()).isOf(Blocks.NETHER_PORTAL)) portal = portal.down();
        return Optional.of(PortalRunCampaign.go(portal, 0.4, false, "Walk into the portal"));
    }

    private Optional<PortalRunCampaign.Task> fight(ServerPlayerEntity player, Class<? extends LivingEntity> type, double range, String label) {
        LivingEntity mob = visibleMob(player, type, range);
        if (mob == null) return Optional.empty();
        var strike = PortalRunCampaign.strike(player, mob, label);
        return Optional.of(new PortalRunCampaign.Task("native_fight", strike.args(), strike.label()));
    }

    /**
     * The mobs it means to fight on its walks, which wary routes need not keep clear of: blazes while it is short of
     * rods, endermen while it is short of pearls (endermen only fight when looked at, but a route need not avoid one
     * it wants). Every other hostile mob a walk gives a wide berth where the ground allows.
     */
    private String wanted() {
        if (lastSnapshot == null) return "";
        var kinds = new ArrayList<String>();
        if (StrongholdRunPlanner.stage(lastSnapshot).ordinal() <= StrongholdRunPlanner.Stage.BLAZE_RODS.ordinal()
                && 2 * lastSnapshot.blazeRods() + lastSnapshot.blazePowder() + lastSnapshot.eyes() < StrongholdRunPlanner.PEARLS_TARGET) kinds.add("blaze");
        if (lastSnapshot.enderPearls() + lastSnapshot.eyes() < StrongholdRunPlanner.PEARLS_TARGET) kinds.add("enderman");
        if (provisions.chunks().stream().anyMatch(c -> c.need() == Need.STRING)) kinds.add("spider");
        return String.join(",", kinds);
    }

    // ------------------------------------------------------------------ follow-ups (F-25c7)

    /** The tasks still ahead at this point of the trip, from the stage the route alone would be at. */
    static Set<Objective> ahead(StrongholdRunSnapshot s) {
        var ahead = EnumSet.of(Objective.END_FIGHT, Objective.SURVIVAL);
        var base = StrongholdRunPlanner.stage(s, StrongholdRunPlanner.Provisions.NONE);
        if (base.ordinal() <= StrongholdRunPlanner.Stage.RETURN.ordinal()) ahead.add(Objective.NETHER_TRIP);
        if (base != StrongholdRunPlanner.Stage.DONE) ahead.add(Objective.STRONGHOLD);
        return ahead;
    }

    /**
     * The task whose gate the player stands at: the Nether trip while the next thing is going through, the End fight
     * at the stronghold before going down.
     */
    static Set<Objective> atGate(StrongholdRunSnapshot s) {
        var base = StrongholdRunPlanner.stage(s, StrongholdRunPlanner.Provisions.NONE);
        boolean overworld = s.dimension() == StrongholdRunSnapshot.Dimension.OVERWORLD;
        if (overworld && (base == StrongholdRunPlanner.Stage.PREPARE || base == StrongholdRunPlanner.Stage.NETHER)) return Set.of(Objective.NETHER_TRIP);
        if (base == StrongholdRunPlanner.Stage.DESCEND) return Set.of(Objective.END_FIGHT);
        return Set.of();
    }

    /** A chunk of the queue as a skill: searched for only when a task's gate is due it. */
    private Optional<PortalRunCampaign.Task> followUp(StrongholdRunPlanner.Step step, StrongholdRunSnapshot s, PortalRunSnapshot p,
                                                      NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        Need need = Need.valueOf(step.item());
        boolean due = provisions.gated().values().stream().flatMap(List::stream).anyMatch(c -> c.need() == need);
        return campaign.followUps().realize(need, step.count(), due, step.why(), p, body, policy, s.swordTier());
    }

    /** The queue in words for the strategist, as it stood on the last pass. */
    String queueReport(ServerPlayerEntity player) {
        return campaign.followUps().describe(player);
    }

    /** By the fortress's spawner, where blazes keep coming; otherwise along the fortress for one. */
    private Optional<PortalRunCampaign.Task> seekBlaze(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        // A fortress seen from afar (staged run 9 saw one 125 blocks off): there first, before looking about it.
        if (fortress != null && fortress.getSquaredDistance(feet) > 24 * 24) return toFortress(body);
        var spawner = campaign.knowledge().nearest(world, WorldKnowledge.Kind.SPAWNER, feet,
                b -> world.getBlockState(b).isOf(Blocks.SPAWNER) && !campaign.unreachable(b));
        if (spawner.isPresent()) {
            if (spawner.get().getSquaredDistance(feet) > 7 * 7) {
                return Optional.of(PortalRunCampaign.go(spawner.get(), 5, true, "Walk to the blaze spawner"));
            }
            return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "3"), "Wait by the blaze spawner"));
        }
        // Along the fortress to bricks not yet walked to, a couple of dozen blocks on.
        var on = campaign.knowledge().best(world, WorldKnowledge.Kind.NETHER_BRICK,
                b -> Math.abs(Math.sqrt(b.getSquaredDistance(feet)) - 24),
                b -> !visited.contains(cell(b)) && !campaign.blocked(b) && !campaign.unreachable(b) && world.getBlockState(b.up()).isAir());
        if (on.isPresent()) {
            visited.add(cell(on.get()));
            return Optional.of(PortalRunCampaign.goWary(on.get().up(), 2, true, "Walk along the fortress for blazes", wanted()));
        }
        // By the fortress with no brick walk left from here (it stands below, across lava): a way made onto it.
        var onto = campaign.knowledge().nearest(world, WorldKnowledge.Kind.NETHER_BRICK, feet,
                b -> world.getBlockState(b.up()).isAir() && world.getBlockState(b.up(2)).isAir() && !campaign.blocked(b.up()));
        if (onto.isPresent() && onto.get().up().getSquaredDistance(feet) > 3 * 3) {
            BlockPos stand = onto.get().up();
            Optional<PortalRunCampaign.Task> made = campaign.buildRoute(body, stand, pos -> pos.getSquaredDistance(stand) <= 2 * 2, false,
                    "Make a way onto the fortress");
            if (made.isPresent()) return made;
        }
        return explore(body, "blazes");
    }

    /** To a warped forest seen, where endermen gather; otherwise on across the Nether. */
    private Optional<PortalRunCampaign.Task> seekEnderman(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (nether(player)) {
            var forest = campaign.knowledge().best(world, WorldKnowledge.Kind.WARPED,
                    b -> b.getSquaredDistance(feet), b -> !visited.contains(cell(b)) && !campaign.unreachable(b) && world.getBlockState(b.up()).isAir());
            if (forest.isPresent() && forest.get().getSquaredDistance(feet) > 6 * 6) {
                visited.add(cell(forest.get()));
                return Optional.of(PortalRunCampaign.go(forest.get().up(), 3, true, "Walk into the warped forest for endermen"));
            }
        }
        return explore(body, "endermen");
    }

    /**
     * To the fortress once seen: walking, and where the walk fails (across lava, up a cliff), the way a player would
     * make, bridging and pillaring with its own blocks and digging through.
     */
    private Optional<PortalRunCampaign.Task> toFortress(NativePlayerBody body) {
        BlockPos feet = body.entity().getBlockPos();
        double off = Math.sqrt(fortress.getSquaredDistance(feet));
        // A walk there that got no closer (a partial route ending where it began; staged run 9 walked there 293 times
        // from one spot) is given up for a made way.
        boolean stalled = fortressWalkFrom >= 0 && off > fortressWalkFrom - 2;
        fortressWalkFrom = -1;
        if (!stalled && !campaign.unreachable(fortress)) {
            fortressWalkFrom = off;
            return Optional.of(PortalRunCampaign.goWary(fortress, 3, true, "Walk to the fortress", wanted()));
        }
        BlockPos target = fortress;
        return campaign.buildRoute(body, target, pos -> pos.getSquaredDistance(target) <= 5 * 5, false, "Make a way to the fortress")
                .or(() -> {
                    // No way planned from here: explore on toward it.
                    int dx = target.getX() - feet.getX(), dz = target.getZ() - feet.getZ();
                    Direction toward = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
                    if (heading != toward) {
                        heading = toward;
                        exploreFrom = null;
                    }
                    return explore(body, "a fortress");
                });
    }

    /**
     * Onward across the Nether along one heading, as players do to find a fortress: to standing ground a couple of
     * dozen blocks on along it, a little up or down as the ground goes. Where a walk on fails it makes a way, digging,
     * bridging or pillaring by cost, and only then cuts straight through the netherrack. It keeps above the lava sea.
     * The heading turns only once it has stopped gaining ground for a while, never back and forth.
     */
    private Optional<PortalRunCampaign.Task> explore(NativePlayerBody body, String what) {
        return explore(body, what, 0, java.util.EnumSet.noneOf(Direction.class));
    }

    /** {@code tried}: the headings this pass has given up on, and the way straight back from the first. */
    private Optional<PortalRunCampaign.Task> explore(NativePlayerBody body, String what, int turns, Set<Direction> tried) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (heading == null || exploreFrom == null) {
            if (heading == null) heading = openest(player, Set.of());
            exploreFrom = feet;
            exploreBest = 0;
            exploreSince = campaign.lastTick();
        }
        long now = campaign.lastTick();
        explored.add(net.minecraft.util.math.ChunkPos.toLong(feet.getX() >> 4, feet.getZ() >> 4));
        if (turns == 0 && editOnlyPasses > 0) editOnlyPasses--;
        double gained = along(exploreFrom, feet);
        if (gained > exploreBest + 1) {
            exploreBest = gained;
            exploreSince = now;
            stallPasses = 0;
        } else if (turns == 0 && ++stallPasses > STALL_PASSES && now - exploreSince > EXPLORE_PATIENCE) {
            heading = openest(player, Set.of(heading, heading.getOpposite()));
            exploreFrom = feet;
            exploreBest = 0;
            exploreSince = now;
            stallPasses = 0;
            makePasses = 0;
        }
        // A walk to a stand that got nowhere (a partial route that ended where it began) counts as a failed one.
        if (lastWalkFrom != null && turns == 0) {
            if (along(lastWalkFrom, feet) < 2) startMaking(feet);
            lastWalkFrom = null;
        }
        // Above the lava sea, unless a fortress it is after stands lower (bricks reach down to the lava's level).
        int floor = !nether(player) ? Integer.MIN_VALUE : fortress != null ? Math.min(NETHER_FLOOR, fortress.getY() - 2) : NETHER_FLOOR;
        // Down by the lava sea: up first, by the cheapest way there is.
        // Routes are planned on the first heading of a pass only: each plan is some milliseconds of the server's tick.
        if (feet.getY() < floor && turns == 0) {
            BlockPos up = feet.offset(heading, 12).withY(floor + 8);
            Optional<PortalRunCampaign.Task> climb = campaign.buildRoute(body, up, pos -> pos.getY() >= floor, false,
                    "Climb above the lava sea for " + what);
            if (climb.isPresent()) return climb;
        }
        // A walk that failed on this heading (the Nether's cliffs, ledges and lava): make a way on, else cut straight
        // on through the netherrack, as players tunnel, until it has made a dozen blocks' headway; then walk again.
        if (makePasses > 0) {
            if (makeFrom == null) makeFrom = feet;
            if (along(makeFrom, feet) >= 12) makePasses = 0;
            else {
                if (turns == 0) makePasses--;
                Optional<PortalRunCampaign.Task> made = turns > 0 ? Optional.empty() : makeWay(body, floor, what);
                if (made.isPresent()) return made;
                Optional<PortalRunCampaign.Task> cut = campaign.tunnelStraight(body, heading, floor, "Cut through " + heading.asString() + " for " + what);
                if (cut.isPresent()) return cut;
            }
        }
        var navigator = new NativeNavigator(world);
        for (int ahead : new int[] {24, 16, 10}) {
            BlockPos column = feet.offset(heading, ahead);
            if (!world.isChunkLoaded(column)) continue;
            for (int dy = 0; dy <= 6; dy++) {
                for (int sign : dy == 0 ? new int[] {1} : new int[] {1, -1}) {
                    BlockPos stand = column.up(dy * sign);
                    if (!navigator.standable(stand) || navigator.hazard(stand.down()) || campaign.blocked(stand) || campaign.unreachable(stand)) continue;
                    lastWalkFrom = feet;
                    return Optional.of(PortalRunCampaign.goWary(stand, 2, true, "Explore " + heading.asString() + " for " + what, wanted()));
                }
            }
        }
        Optional<PortalRunCampaign.Task> cut = campaign.tunnelStraight(body, heading, floor, "Cut through " + heading.asString() + " for " + what);
        if (cut.isPresent()) return cut;
        // Nothing to stand on ahead and no tunnel on (a ledge over a cavern or a lava lake): the way a player would make.
        if (turns == 0) {
            Optional<PortalRunCampaign.Task> made = makeWay(body, floor, what);
            if (made.isPresent()) {
                startMaking(feet);
                return made;
            }
        }
        // No way on along this heading at all, not even through the rock: another heading from here. With none on any,
        // a moment to look round, never nothing: the campaign's last resort is a jump down, and in the Nether that
        // led toward the lava sea (staged End run 7).
        if (turns == 0) tried.add(heading.getOpposite());
        tried.add(heading);
        exploreFrom = null;
        makePasses = 0;
        if (tried.size() < 4) {
            heading = openest(player, tried);
            return explore(body, what, turns + 1, tried);
        }
        return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Look round for a way on"));
    }

    /** Twelve blocks or more on along the heading, by the cheapest way with digging, bridging and pillaring. */
    private Optional<PortalRunCampaign.Task> makeWay(NativePlayerBody body, int floor, String what) {
        BlockPos from = body.entity().getBlockPos();
        Direction way = heading;
        BlockPos ahead = from.offset(heading, 24);
        if (ahead.getY() < floor) ahead = ahead.withY(floor);
        return campaign.buildRoute(body, ahead,
                pos -> (pos.getX() - from.getX()) * way.getOffsetX() + (pos.getZ() - from.getZ()) * way.getOffsetZ() >= 12 && pos.getY() >= floor - 2,
                editOnlyPasses > 0, "Make a way " + heading.asString() + " for " + what);
    }

    /** How far a look along a heading reaches, at eye level and a little below: open ground a player would make for. */
    private static final double OPEN_SIGHT = 64;

    /**
     * The heading with the longest open view from here, as a player heads for open caverns rather than into rock:
     * any but those in {@code skip} (given up on, or straight back), and seldom toward ground it has already explored.
     * Ties go to the player's own side, so two players spread out.
     */
    private Direction openest(ServerPlayerEntity player, Set<Direction> skip) {
        var world = player.getServerWorld();
        var eye = player.getEyePos();
        Direction own = Direction.fromHorizontal(Math.floorMod((int) player.getUuid().getMostSignificantBits(), 4));
        Direction best = null;
        double bestReach = -1;
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (skip.contains(side)) continue;
            double reach = 0;
            for (double drop : new double[] {0, 12}) {
                double pitch = Math.toRadians(drop);
                var end = eye.add(side.getOffsetX() * Math.cos(pitch) * OPEN_SIGHT, -Math.sin(pitch) * OPEN_SIGHT, side.getOffsetZ() * Math.cos(pitch) * OPEN_SIGHT);
                var hit = world.raycast(new net.minecraft.world.RaycastContext(eye, end, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
                reach += hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS ? OPEN_SIGHT : hit.getPos().distanceTo(eye);
            }
            // Back over ground it has already explored counts for little: staged run 8 turned round and walked two
            // hundred blocks back along its own tunnel.
            BlockPos ahead = player.getBlockPos().offset(side, 32);
            if (explored.contains(net.minecraft.util.math.ChunkPos.toLong(ahead.getX() >> 4, ahead.getZ() >> 4))) reach *= 0.25;
            if (best == null || reach > bestReach + 1 || Math.abs(reach - bestReach) <= 1 && side == own) {
                bestReach = reach;
                best = side;
            }
        }
        return best != null ? best : own;
    }

    private static long cell(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 3, pos.getY() >> 3, pos.getZ() >> 3);
    }

    /** One eye up and watched; the second from well to the side of the first flight. */
    private Optional<PortalRunCampaign.Task> throwEye(NativePlayerBody body) {
        var player = body.entity();
        if (!overworld(player)) return Optional.empty();
        if (flights.size() == 1) {
            var first = flights.getFirst();
            if (asideFor == null) {
                double[] spot = EyeFlights.secondThrow(first, SIDE_THROW);
                asideFor = BlockPos.ofFloored(spot[0], player.getY(), spot[1]);
            }
            if (first.offLine(player.getX(), player.getZ()) < SIDE_THROW * 0.6) return walkToward(body, asideFor.getX(), asideFor.getZ(), "Walk to the side for a second throw");
        }
        int slot = PortalRunCampaign.hotbarSlot(body, Items.ENDER_EYE);
        if (slot < 0) {
            int anywhere = PortalRunCampaign.slotOf(body, Items.ENDER_EYE);
            return anywhere < 0 ? Optional.empty() : Optional.of(campaign.swapToHotbar(body, anywhere, "Ready an eye of ender"));
        }
        float yaw = flights.isEmpty() ? player.getYaw() : (float) flights.getLast().yaw();
        return Optional.of(new PortalRunCampaign.Task("native_throw_eye",
                Map.of("slot", String.valueOf(slot), "yaw", String.format(Locale.ROOT, "%.1f", yaw)), "Throw an eye of ender"));
    }

    /** A thrown eye's flight, as the throw reported it; two flights far enough apart give the crossing. */
    void eyeFlown(Map<String, String> result) {
        try {
            // Thrown close by, an eye flies straight to the stronghold's spot and no further: its stopping place is it,
            // even when that is right where it was thrown from.
            double flown = result.containsKey("dx") ? Math.hypot(Double.parseDouble(result.get("dx")), Double.parseDouble(result.get("dz"))) : 0;
            if (arrived && estimate != null && flown < 11.5 && result.containsKey("toX")) {
                estimate = new double[] {Double.parseDouble(result.get("toX")), Double.parseDouble(result.get("toZ"))};
                pinned = true;
                memoryRevision++;
                KindredMod.LOGGER.info("Stronghold pinned at {} {} by an eye thrown close by", Math.round(estimate[0]), Math.round(estimate[1]));
                return;
            }
            if (!result.containsKey("dx")) return; // Not seen to fly any way: nothing learnt.
            var flight = new EyeFlights.Flight(Double.parseDouble(result.get("fromX")), Double.parseDouble(result.get("fromZ")),
                    Double.parseDouble(result.get("dx")), Double.parseDouble(result.get("dz")));
            flights.add(flight);
            if (flights.size() > 4) flights.removeFirst();
            memoryRevision++;
            KindredMod.LOGGER.info("Eye of ender flew {} from {} {}", Math.round(flight.yaw()), Math.round(flight.x()), Math.round(flight.z()));
            for (int i = flights.size() - 2; i >= 0 && estimate == null; i--) {
                var crossing = EyeFlights.crossing(flights.get(i), flight);
                if (crossing.isPresent()) {
                    estimate = crossing.get();
                    KindredMod.LOGGER.info("Stronghold estimated near {} {}", Math.round(estimate[0]), Math.round(estimate[1]));
                }
            }
        } catch (RuntimeException unreadable) {
            KindredMod.LOGGER.debug("Unreadable eye flight {}", result);
        }
    }

    private Optional<PortalRunCampaign.Task> travel(NativePlayerBody body) {
        var player = body.entity();
        if (estimate == null) return Optional.empty();
        double distance = Math.hypot(estimate[0] - player.getX(), estimate[1] - player.getZ());
        // Rounded, so the progress line changes every fifty blocks rather than every step.
        long about = Math.max(50, Math.round(distance / 50) * 50);
        return walkToward(body, estimate[0], estimate[1], "Head for the stronghold, about " + about + " blocks off");
    }

    /**
     * A walk toward a far point, at ground level where it ends: straight on as far as {@link #LEG} blocks, else a
     * shorter way, else off to one side or the other, skipping ends that a walk has already failed to reach (a cliff,
     * a ravine, a mountain in the way), as a player picks another line. With every line refused, it makes a way on:
     * up the cliff, over the ravine or through the hill, whichever costs least.
     */
    private Optional<PortalRunCampaign.Task> walkToward(NativePlayerBody body, double x, double z, String label) {
        var player = body.entity();
        var world = player.getServerWorld();
        double dx = x - player.getX(), dz = z - player.getZ(), distance = Math.hypot(dx, dz);
        if (distance < 2) return Optional.empty();
        Optional<PortalRunCampaign.Task> byBoat = byBoat(body, x, z, label);
        if (byBoat.isPresent()) return byBoat;
        double ux = dx / distance, uz = dz / distance;
        for (double leg : new double[] {LEG, 48, 24}) {
            double step = Math.min(distance, leg);
            for (double side : new double[] {0, 20, -20, 40, -40}) {
                int tx = (int) Math.floor(player.getX() + ux * step - uz * side), tz = (int) Math.floor(player.getZ() + uz * step + ux * side);
                BlockPos column = new BlockPos(tx, player.getBlockY(), tz);
                if (!world.isChunkLoaded(column)) continue;
                BlockPos end = new BlockPos(tx, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, tx, tz), tz);
                if (campaign.blocked(end) || campaign.unreachable(end)) continue;
                return Optional.of(PortalRunCampaign.goWary(end, 4, true, label, wanted()));
            }
            if (step >= distance) break;
        }
        double px = player.getX(), pz = player.getZ(), want = Math.min(12, distance - 2);
        BlockPos ahead = BlockPos.ofFloored(px + ux * Math.min(distance, 24), player.getY(), pz + uz * Math.min(distance, 24));
        return campaign.buildRoute(body, ahead, pos -> (pos.getX() + 0.5 - px) * ux + (pos.getZ() + 0.5 - pz) * uz >= want, false,
                label + ": make a way");
    }

    // ------------------------------------------------------------------ water, by boat (F-10f)

    /** Where the boat is being rowed to, and what for; set when it takes to the water. */
    private double[] rowTarget;
    private String rowLabel = "";
    /** How the last row ended ("shore", "arrived", "far"), from its result. */
    private String lastRowEnd;
    /** Where the boat dropped when it was broken on landing, to be picked up again, and the tries at it. */
    private BlockPos boatDrop;
    private int boatPickups;

    /** A row has ended: how, from its result. */
    void rowed(Map<String, String> result) {
        lastRowEnd = result.get("ended");
    }

    /** A boat step has ended: on landing, where the broken boat's item lies. */
    void boatStep(String mode, Map<String, String> result) {
        if ("launch".equals(mode) || "board".equals(mode)) lastRowEnd = null;
        if (!"land".equals(mode)) return;
        rowTarget = null;
        lastRowEnd = null;
        try {
            boatDrop = result.containsKey("dropX") ? new BlockPos(Integer.parseInt(result.get("dropX")), Integer.parseInt(result.get("dropY")),
                    Integer.parseInt(result.get("dropZ"))) : null;
            boatPickups = 0;
        } catch (NumberFormatException unreadable) {
            boatDrop = null;
        }
    }

    /**
     * Afloat: row on toward where it was going; at a shore (land under the bow, or aground), or with nowhere left to
     * row to, out of the boat, which is broken to take it back. Nothing on land is done from a boat.
     */
    Optional<PortalRunCampaign.Task> afloat(NativePlayerBody body) {
        var player = body.entity();
        // Over the stronghold's spot at sea: pin it with an eye from the boat, as on land.
        if (estimate != null && !pinned && Math.hypot(estimate[0] - player.getX(), estimate[1] - player.getZ()) < NEAR
                && player.getInventory().count(Items.ENDER_EYE) > 0) {
            arrived = true;
            Optional<PortalRunCampaign.Task> look = throwEye(body);
            if (look.isPresent()) {
                progress = "End: " + label(stage) + ": at the spot, at sea - " + look.get().label();
                return look;
            }
        }
        boolean ashore = "shore".equals(lastRowEnd);
        boolean there = rowTarget == null || Math.hypot(rowTarget[0] - player.getX(), rowTarget[1] - player.getZ()) < 8 || "arrived".equals(lastRowEnd);
        if (there && !ashore) {
            // Where it was going, but at sea (staged run 10 got out over the ocean stronghold and swam): on to the
            // nearest dry land it can reach by water, and out there.
            var dry = nearestLand(player.getServerWorld(), player.getBlockPos(), LAND_SIGHT)
                    .or(() -> Optional.ofNullable(lastDryLand)); // Else back to the last dry ground it stood on.
            KindredMod.LOGGER.info("{} is where it was rowing to, at sea at {}; land to make for: {}", player.getName().getString(),
                    player.getBlockPos().toShortString(), dry.map(BlockPos::toShortString).orElse("none in sight"));
            if (dry.isPresent() && dry.get().getSquaredDistance(player.getBlockPos()) > 3 * 3) {
                rowTarget = new double[] {dry.get().getX() + 0.5, dry.get().getZ() + 0.5};
                rowLabel = "To the nearest dry land";
                lastRowEnd = null;
                there = false;
            }
        }
        if (ashore || there) {
            progress = "End: " + label(stage) + ": " + (ashore ? "a shore" : "across") + " - Out of the boat and take it back";
            return Optional.of(new PortalRunCampaign.Task("native_boat", Map.of("mode", "land"), "Out of the boat and take it back"));
        }
        progress = "End: " + label(stage) + ": by boat - " + rowLabel;
        return Optional.of(new PortalRunCampaign.Task("native_row", Map.of("x", String.format(Locale.ROOT, "%.1f", rowTarget[0]),
                "z", String.format(Locale.ROOT, "%.1f", rowTarget[1]), "range", "6", "seconds", "20"), "Row: " + rowLabel));
    }

    /**
     * Over open water by boat, as players cross it: where most of the next thirty blocks toward the place are water,
     * a boat it carries goes onto the water within reach and it gets in, or it walks to the water's edge first. The
     * boat broken on landing is picked up again before going on. With no boat it swims, as before.
     */
    private Optional<PortalRunCampaign.Task> byBoat(NativePlayerBody body, double x, double z, String label) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (!overworld(player) || player.hasVehicle()) return Optional.empty();
        if (boatDrop != null) {
            var drop = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(boatDrop).expand(6),
                    item -> item.isAlive() && item.getStack().getItem() instanceof net.minecraft.item.BoatItem).stream().findFirst();
            if (drop.isPresent() && boatPickups++ < 4) {
                return Optional.of(PortalRunCampaign.go(drop.get().getBlockPos(), 0.6, true, "Pick up the boat"));
            }
            boatDrop = null;
        }
        double dx = x - player.getX(), dz = z - player.getZ(), distance = Math.hypot(dx, dz);
        if (distance < 24) return Optional.empty();
        double ux = dx / distance, uz = dz / distance;
        // An empty boat on the water close by (its own, left by a restart or a landing that went wrong): back into it.
        var empty = world.getEntitiesByClass(net.minecraft.entity.vehicle.BoatEntity.class, player.getBoundingBox().expand(8, 3, 8),
                boat -> boat.isAlive() && !boat.hasPassengers()).stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo));
        if (empty.isPresent()) {
            rowTarget = new double[] {x, z};
            rowLabel = label;
            lastRowEnd = null;
            if (player.canInteractWithEntity(empty.get(), 0)) {
                return Optional.of(new PortalRunCampaign.Task("native_boat", Map.of("mode", "board"), "Back into the boat"));
            }
            return Optional.of(PortalRunCampaign.go(empty.get().getBlockPos(), 1.5, true, "Swim to the boat"));
        }
        if (!waterAhead(world, player, ux, uz)) return Optional.empty();
        int slot = -1;
        var inventory = player.getInventory();
        for (int s = 0; s < 36 && slot < 0; s++) if (inventory.getStack(s).getItem() instanceof net.minecraft.item.BoatItem) slot = s;
        if (slot < 0) return Optional.empty();
        if (slot >= 9) return Optional.of(campaign.swapToHotbar(body, slot, "Ready the boat"));
        // Under the surface a boat cannot be put on it (the look meets the water, not its top), and staged run 13 kept
        // trying while it sank: up to the surface first, then the boat.
        if (player.isSubmergedInWater()) {
            BlockPos up = player.getBlockPos();
            while (up.getY() < player.getBlockY() + 16 && world.getFluidState(up.up()).isIn(net.minecraft.registry.tag.FluidTags.WATER)) up = up.up();
            return Optional.of(PortalRunCampaign.go(up, 0.5, true, "Get out of the water: up to the surface to put the boat in"));
        }
        BlockPos spot = launchSpot(world, player, ux, uz);
        if (spot == null) {
            BlockPos edge = firstWater(world, player, ux, uz);
            if (edge == null) return Optional.empty();
            // At the edge already, with no room for a boat within reach (staged run 10 walked to the edge over and over):
            // a few blocks out into the water, where it is open all round, as a player wades out to put a boat in.
            if (edge.getSquaredDistance(player.getBlockPos()) <= 3 * 3) {
                BlockPos out = surfaceWater(world, (int) Math.floor(edge.getX() + 0.5 + ux * 3), (int) Math.floor(edge.getZ() + 0.5 + uz * 3));
                if (out != null) return Optional.of(PortalRunCampaign.go(out, 0.8, true, "Wade out to put the boat in"));
            }
            return Optional.of(PortalRunCampaign.go(edge, 2.5, true, "To the water's edge for the boat"));
        }
        rowTarget = new double[] {x, z};
        rowLabel = label;
        lastRowEnd = null;
        return Optional.of(new PortalRunCampaign.Task("native_boat", Map.of("mode", "launch", "slot", String.valueOf(slot),
                "x", String.valueOf(spot.getX()), "y", String.valueOf(spot.getY()), "z", String.valueOf(spot.getZ())), "Into the boat to cross the water"));
    }

    /** How far out over water it looks round for land. */
    private static final int LAND_SIGHT = 160;

    /**
     * The nearest land in sight from the water to dig down from, as a player looks round for a shore: the nearest
     * column, out to {@code radius} blocks over loaded ground, whose top is solid ground with no water or lava within
     * two blocks of it or under it (a rock or a ruin poking out of the sea will not do: staged run 13 got out onto
     * one and could dig nowhere). Read from the heightmap, ring by ring.
     */
    static Optional<BlockPos> nearestLand(ServerWorld world, BlockPos from, int radius) {
        for (int r = 3; r <= radius; r += 3) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx += 3) for (int dz = -r; dz <= r; dz += 3) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // The ring only.
                int x = from.getX() + dx, z = from.getZ() + dz;
                if (!world.isChunkLoaded(new BlockPos(x, 0, z))) continue;
                BlockPos top = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                if (!world.getFluidState(top).isEmpty() || world.getBlockState(top).getCollisionShape(world, top).isEmpty()) continue;
                if (wetAround(world, top.up())) continue;
                double distance = top.getSquaredDistance(from);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = top.up();
                }
            }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    /** The top of the column at {@code x}, {@code z} is water: the surface of a lake or the sea. */
    private static BlockPos surfaceWater(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(new BlockPos(x, 0, z))) return null;
        BlockPos top = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1, z);
        return world.getFluidState(top).isIn(net.minecraft.registry.tag.FluidTags.WATER) && world.getBlockState(top.up()).isAir() ? top : null;
    }

    /** Most of the next thirty blocks along the way are open water. */
    private static boolean waterAhead(ServerWorld world, ServerPlayerEntity player, double ux, double uz) {
        int wet = 0;
        for (int d = 3; d <= 30; d += 3) {
            if (surfaceWater(world, (int) Math.floor(player.getX() + ux * d), (int) Math.floor(player.getZ() + uz * d)) != null) wet++;
        }
        return wet >= 7;
    }

    /** The first open water along the way, within thirty blocks. */
    private static BlockPos firstWater(ServerWorld world, ServerPlayerEntity player, double ux, double uz) {
        for (int d = 1; d <= 30; d++) {
            BlockPos water = surfaceWater(world, (int) Math.floor(player.getX() + ux * d), (int) Math.floor(player.getZ() + uz * d));
            if (water != null) return water;
        }
        return null;
    }

    /**
     * Water within reach along the way with room for a boat: open water all round it at the surface, so the boat
     * fits (vanilla refuses a boat that would touch the shore), and the surface close enough to the eyes.
     */
    private static BlockPos launchSpot(ServerWorld world, ServerPlayerEntity player, double ux, double uz) {
        var eye = player.getEyePos();
        for (double d = 1.5; d <= 3.75; d += 0.25) {
            BlockPos water = surfaceWater(world, (int) Math.floor(player.getX() + ux * d), (int) Math.floor(player.getZ() + uz * d));
            if (water == null || eye.distanceTo(net.minecraft.util.math.Vec3d.ofCenter(water).add(0, 0.4, 0)) > 4.0) continue;
            boolean room = true;
            for (int dx = -1; dx <= 1 && room; dx++) for (int dz = -1; dz <= 1 && room; dz++) {
                BlockPos at = water.add(dx, 0, dz);
                room = world.getFluidState(at).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                        && world.getBlockState(at.up()).getCollisionShape(world, at.up()).isEmpty();
            }
            if (room) return water;
        }
        return null;
    }

    /**
     * At the crossing: one more eye to pin the spot (close by, an eye flies straight to it), then a staircase down
     * beside it, where the stronghold's starting staircase lies, two to six blocks in from the corner the eyes point at.
     * Under water, the staircase starts from the nearest dry land and a tunnel runs across under the bed.
     */
    private Optional<PortalRunCampaign.Task> descend(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (estimate == null) return Optional.empty();
        double off = Math.hypot(estimate[0] - player.getX(), estimate[1] - player.getZ());
        if (!pinned && off < NEAR && player.getInventory().count(Items.ENDER_EYE) > 0) {
            Optional<PortalRunCampaign.Task> look = throwEye(body);
            if (look.isPresent()) return look;
        }
        BlockPos column = BlockPos.ofFloored(estimate[0] + 4, feet.getY(), estimate[1] + 4);
        int dx = column.getX() - feet.getX(), dz = column.getZ() - feet.getZ();
        Direction toward = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        boolean over = Math.abs(dx) <= 2 && Math.abs(dz) <= 2;
        if (feet.getY() > DIG_TO) {
            if (PlayerRuntime.inDeepWater(player) || !world.getFluidState(feet).isEmpty()) {
                // Out at sea: the nearest dry land it can swim to, else the nearest it can see, else the last it stood
                // on on the way out (staged run 12 found none to swim to and rowed 1,100 blocks back to that).
                var shore = nearestLand(world, feet, LAND_SIGHT)
                        .or(() -> new NativeNavigator(world).nearestReachableDryStand(feet, 20_000, 96))
                        .or(() -> Optional.ofNullable(lastDryLand));
                if (shore.isEmpty()) return Optional.empty();
                BlockPos dry = shore.get();
                return dry.getSquaredDistance(feet) > LEG * LEG ? walkToward(body, dry.getX() + 0.5, dry.getZ() + 0.5, "To dry land to dig down from")
                        : Optional.of(PortalRunCampaign.go(dry, 1, true, "To dry land to dig down from"));
            }
            Optional<PortalRunCampaign.Task> down = campaign.digDown(body, DIG_TO, "Dig down toward the stronghold");
            if (down.isPresent()) return down;
            Optional<PortalRunCampaign.Task> along = campaign.tunnelAlong(body, toward, "Tunnel toward the stronghold");
            if (along.isPresent()) return along;
            // On a shore or an island, water refuses every step down: further inland, then down from there.
            return inland(body);
        }
        if (!over) return campaign.tunnelAlong(body, toward, "Tunnel toward the stronghold");
        // Over the spot with no bricks yet: further down, then out round about it.
        Optional<PortalRunCampaign.Task> deeper = feet.getY() > -40 ? campaign.digDown(body, -40, "Dig down through the stronghold's depth") : Optional.empty();
        if (deeper.isPresent()) return deeper;
        if (heading == null) heading = Direction.NORTH;
        Optional<PortalRunCampaign.Task> cut = campaign.tunnelAlong(body, heading, "Tunnel round the stronghold's spot");
        if (cut.isEmpty()) heading = heading.rotateYClockwise();
        return cut;
    }

    /**
     * The nearest dry standing place, a few blocks off, with no water or lava anywhere a staircase dug down from it
     * would pass. Staged run 5's player stood on an ocean's shore, could dig down on no side for the sea, jumped back in
     * and swam out again, along the coast for ever.
     */
    private Optional<PortalRunCampaign.Task> inland(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var navigator = new NativeNavigator(world);
        for (BlockPos near : BlockPos.iterateOutwards(feet, 16, 3, 16)) {
            if (near.getSquaredDistance(feet) < 3 * 3 || !world.isChunkLoaded(near)) continue;
            if (!navigator.standable(near) || campaign.blocked(near) || campaign.unreachable(near)) continue;
            if (wetAround(world, near)) continue;
            return Optional.of(PortalRunCampaign.go(near.toImmutable(), 0.5, false, "Inland, away from the water, to dig down"));
        }
        return Optional.empty();
    }

    private static boolean wetAround(ServerWorld world, BlockPos stand) {
        for (int dy = -4; dy <= 0; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (!world.getFluidState(stand.add(dx, dy, dz)).isEmpty()) return true;
        }
        return false;
    }

    /** Through the stronghold toward bricks not yet walked to, until an End portal frame shows. */
    private Optional<PortalRunCampaign.Task> findRoom(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var on = campaign.knowledge().best(world, WorldKnowledge.Kind.STONE_BRICK, b -> Math.abs(Math.sqrt(b.getSquaredDistance(feet)) - 16),
                b -> !visited.contains(cell(b)) && !campaign.blocked(b) && !campaign.unreachable(b)
                        && world.getBlockState(b.up()).isAir() && world.getBlockState(b.up(2)).isAir());
        if (on.isPresent()) {
            visited.add(cell(on.get()));
            return Optional.of(PortalRunCampaign.go(on.get().up(), 2, true, "Explore the stronghold for the portal room"));
        }
        if (heading == null) heading = Direction.NORTH;
        Optional<PortalRunCampaign.Task> cut = campaign.tunnelAlong(body, heading, "Cut through the stronghold");
        if (cut.isEmpty()) heading = heading.rotateYClockwise();
        return cut;
    }

    /** By the End portal: a few blocks from its frame, where a player stands to look at it. */
    private Optional<PortalRunCampaign.Task> standBy(ServerPlayerEntity player) {
        if (endPortal == null || endPortal.getSquaredDistance(player.getBlockPos()) <= 4 * 4) return Optional.empty();
        return Optional.of(PortalRunCampaign.go(endPortal.up(), 3, true, "Walk up to the End portal"));
    }
}
