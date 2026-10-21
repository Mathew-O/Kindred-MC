package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
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
    private int exploreIdle;
    /** Passes without headway before a heading is given up. */
    private static final int EXPLORE_PATIENCE = 12;
    private final Set<Long> visited = new HashSet<>();
    private StrongholdRunPlanner.Stage stage;
    private String progress = "";
    private BlockPos endPortal;
    private boolean arrived, pinned;
    /** Deep enough to be in or below most strongholds' upper parts, and still above the deepest. */
    static final int DIG_TO = 8;

    StrongholdLeg(PortalRunCampaign campaign) {
        this.campaign = campaign;
    }

    /** The leg has begun: the portal is lit, or the player has already been through it. */
    boolean active(ServerPlayerEntity player) {
        return overworldPortal != null || netherPortal != null || player.getServerWorld().getRegistryKey() != World.OVERWORLD
                || campaign.litPortal(player).isPresent() || seenPortal(player).isPresent();
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
        if (overworld(player)) {
            if (overworldPortal == null) overworldPortal = campaign.litPortal(player).or(() -> seenPortal(player)).orElse(null);
            if (arrived) portalAt(world, feet).ifPresent(p -> overworldPortal = p);
        } else if (nether(player)) {
            if (arrived) netherPortal = portalAt(world, feet).orElse(feet.toImmutable());
            if (fortress == null) campaign.knowledge().nearest(world, WorldKnowledge.Kind.NETHER_BRICK, feet, p -> true).ifPresent(p -> fortress = p);
        }
        if (endPortal == null && overworld(player)) {
            campaign.knowledge().nearest(world, WorldKnowledge.Kind.END_PORTAL_FRAME, feet, p -> true).ifPresent(p -> endPortal = p);
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
        int rods = 0, powder = 0, pearls = 0, eyes = 0, blocks = 0, armor = 0;
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
                endPortal != null, 0);
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

    /** The next task toward the End portal, or empty when nothing can be done from here now. */
    Optional<PortalRunCampaign.Task> next(NativePlayerBody body, PortalRunSnapshot p, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        observe(player);
        var s = snapshot(player, p);
        stage = StrongholdRunPlanner.stage(s);
        for (var step : StrongholdRunPlanner.plan(s)) {
            Optional<PortalRunCampaign.Task> task = realize(step, s, p, body, policy);
            if (task.isPresent()) {
                progress = "End: " + label(stage) + ": " + step.why() + " - " + task.get().label();
                return task;
            }
        }
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
            case FIND_FORTRESS -> fortress != null && fortress.getSquaredDistance(player.getBlockPos()) > 6 * 6
                    ? Optional.of(PortalRunCampaign.go(fortress, 3, true, "Walk to the fortress")) : explore(body, "a fortress");
            case FIGHT_BLAZE -> fight(player, BlazeEntity.class, 32, "Fight blaze");
            case SEEK_BLAZE -> seekBlaze(body);
            case FIGHT_ENDERMAN -> fight(player, EndermanEntity.class, 24, "Fight enderman");
            case SEEK_ENDERMAN -> seekEnderman(body);
            case THROW_EYE -> throwEye(body);
            case TRAVEL_TO_STRONGHOLD -> travel(player);
            case DIG_TO_STRONGHOLD -> descend(body);
            case FIND_PORTAL_ROOM -> findRoom(body);
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

    /** By the fortress's spawner, where blazes keep coming; otherwise along the fortress for one. */
    private Optional<PortalRunCampaign.Task> seekBlaze(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
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
            return Optional.of(PortalRunCampaign.go(on.get().up(), 2, true, "Walk along the fortress for blazes"));
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
     * Onward across the Nether along one heading, as players do to find a fortress: to standing ground a couple of
     * dozen blocks on along it, a little up or down as the ground goes, and cutting through netherrack where there is
     * none. The heading turns only once it has stopped gaining ground for a while, never back and forth.
     */
    private Optional<PortalRunCampaign.Task> explore(NativePlayerBody body, String what) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (heading == null || exploreFrom == null) {
            if (heading == null) heading = Direction.fromHorizontal(Math.floorMod((int) player.getUuid().getMostSignificantBits(), 4));
            exploreFrom = feet;
            exploreBest = 0;
            exploreIdle = 0;
        }
        double gained = (feet.getX() - exploreFrom.getX()) * heading.getOffsetX() + (feet.getZ() - exploreFrom.getZ()) * heading.getOffsetZ();
        if (gained > exploreBest + 1) {
            exploreBest = gained;
            exploreIdle = 0;
        } else if (++exploreIdle > EXPLORE_PATIENCE) {
            heading = heading.rotateYClockwise();
            exploreFrom = feet;
            exploreBest = 0;
            exploreIdle = 0;
        }
        var navigator = new NativeNavigator(world);
        for (int ahead : new int[] {24, 16, 10}) {
            BlockPos column = feet.offset(heading, ahead);
            if (!world.isChunkLoaded(column)) continue;
            for (int dy = 0; dy <= 6; dy++) {
                for (int sign : dy == 0 ? new int[] {1} : new int[] {1, -1}) {
                    BlockPos stand = column.up(dy * sign);
                    if (!navigator.standable(stand) || navigator.hazard(stand.down()) || campaign.blocked(stand) || campaign.unreachable(stand)) continue;
                    return Optional.of(PortalRunCampaign.go(stand, 2, true, "Explore " + heading.asString() + " for " + what));
                }
            }
        }
        return campaign.tunnelAlong(body, heading, "Cut through " + heading.asString() + " for " + what);
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
            if (first.offLine(player.getX(), player.getZ()) < SIDE_THROW * 0.6) return walkToward(player, asideFor.getX(), asideFor.getZ(), "Walk to the side for a second throw");
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
            if (!result.containsKey("dx")) return; // Not seen to fly: nothing learnt.
            // Thrown close by, an eye flies straight to the stronghold's spot and no further: its stopping place is it.
            double flown = Math.hypot(Double.parseDouble(result.get("dx")), Double.parseDouble(result.get("dz")));
            if (arrived && estimate != null && flown < 11.5 && result.containsKey("toX")) {
                estimate = new double[] {Double.parseDouble(result.get("toX")), Double.parseDouble(result.get("toZ"))};
                pinned = true;
                KindredMod.LOGGER.info("Stronghold pinned at {} {} by an eye thrown close by", Math.round(estimate[0]), Math.round(estimate[1]));
                return;
            }
            var flight = new EyeFlights.Flight(Double.parseDouble(result.get("fromX")), Double.parseDouble(result.get("fromZ")),
                    Double.parseDouble(result.get("dx")), Double.parseDouble(result.get("dz")));
            flights.add(flight);
            if (flights.size() > 4) flights.removeFirst();
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

    private Optional<PortalRunCampaign.Task> travel(ServerPlayerEntity player) {
        if (estimate == null) return Optional.empty();
        double distance = Math.hypot(estimate[0] - player.getX(), estimate[1] - player.getZ());
        // Rounded, so the progress line changes every fifty blocks rather than every step.
        long about = Math.max(50, Math.round(distance / 50) * 50);
        return walkToward(player, estimate[0], estimate[1], "Head for the stronghold, about " + about + " blocks off");
    }

    /** A walk of at most {@link #LEG} blocks toward a far point, at ground level there. */
    private Optional<PortalRunCampaign.Task> walkToward(ServerPlayerEntity player, double x, double z, String label) {
        var world = player.getServerWorld();
        double dx = x - player.getX(), dz = z - player.getZ(), distance = Math.hypot(dx, dz);
        if (distance < 2) return Optional.empty();
        double step = Math.min(distance, LEG);
        int tx = (int) Math.floor(player.getX() + dx / distance * step), tz = (int) Math.floor(player.getZ() + dz / distance * step);
        BlockPos column = new BlockPos(tx, player.getBlockY(), tz);
        int y = world.isChunkLoaded(column) ? world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, tx, tz) : player.getBlockY();
        return Optional.of(PortalRunCampaign.go(new BlockPos(tx, y, tz), 4, true, label));
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
                var shore = new NativeNavigator(world).nearestReachableDryStand(feet, 6000, 64);
                return shore.map(dry -> PortalRunCampaign.go(dry, 1, true, "To dry land to dig down from"));
            }
            Optional<PortalRunCampaign.Task> down = campaign.digDown(body, DIG_TO, "Dig down toward the stronghold");
            if (down.isPresent()) return down;
            return campaign.tunnelAlong(body, toward, "Tunnel toward the stronghold");
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
