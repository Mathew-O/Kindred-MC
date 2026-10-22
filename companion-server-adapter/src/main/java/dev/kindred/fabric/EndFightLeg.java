package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.tactics.Ballistics;
import dev.kindred.core.survival.EndFightPlanner;
import dev.kindred.core.survival.EndFightSnapshot;
import dev.kindred.core.survival.PortalRunSnapshot;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The dragon goal past the End portal (E-39): fills the frame with eyes and goes in, and in the End plays the fight
 * the planner names ({@link EndFightPlanner}), each step one ordinary bounded skill. Only where the server allows the
 * fight; otherwise the goal stops by the portal as the End-portal goal does.
 */
final class EndFightLeg {
    private final PortalRunCampaign campaign;
    private final StrongholdLeg leg;
    private EndFightPlanner.Stage stage;
    private String progress = "";
    /** It has seen the dragon die; kept for the trip home. */
    private boolean dragonKilled;
    /** It has seen the dragon alive in this fight. */
    private boolean dragonSeen;
    private int loggedCrystals = -1, loggedHealth = -100;
    /** Arrows loosed at each crystal, the spot it shoots each from, spots that would not do, crystals it gave up on. */
    private final Map<UUID, Integer> shotsAt = new HashMap<>();
    private final Map<UUID, BlockPos> stands = new HashMap<>();
    private final Map<UUID, Set<Long>> badStands = new HashMap<>();
    /** Crystals given up on, until when: a while later it tries each again, from wherever it then is. */
    private final Map<UUID, Long> givenUp = new HashMap<>();
    static final long GIVE_UP_TICKS = 1200;
    /** Caged crystals whose bars on its side it has broken, and the foot of the pillar it built to reach them. */
    private final Set<UUID> opened = new HashSet<>();
    private BlockPos towerBase;
    private UUID caging;
    /** The crystal it is shooting at. */
    private UUID target;
    /** How many arrows from one spot, and at one crystal, before trying elsewhere or giving it up. */
    static final int SHOTS_PER_STAND = 4, SHOTS_PER_CRYSTAL = 12;
    /** Where the bow is drawn from: far enough that a crystal's blast does not reach, near enough to hit. */
    static final double NEAREST_SHOT = 16, FARTHEST_SHOT = 56;

    EndFightLeg(PortalRunCampaign campaign, StrongholdLeg leg) {
        this.campaign = campaign;
        this.leg = leg;
    }

    EndFightPlanner.Stage stage() { return stage; }

    String progress() { return progress; }

    boolean dragonKilled() { return dragonKilled; }

    void dragonKilled(boolean killed) { dragonKilled = dragonKilled || killed; }

    private static boolean inEnd(ServerPlayerEntity player) {
        return player.getWorld().getRegistryKey() == World.END;
    }

    /** The frame blocks of the portal's ring it has seen: those within the ring's reach of the first frame it saw. */
    private List<BlockPos> frames(ServerWorld world) {
        var first = leg.endPortal().orElse(null);
        var frames = new ArrayList<BlockPos>();
        if (first == null) return frames;
        for (BlockPos frame : campaign.knowledge().all(WorldKnowledge.Kind.END_PORTAL_FRAME)) {
            if (Math.abs(frame.getY() - first.getY()) <= 1 && frame.getSquaredDistance(first) <= 6 * 6 && world.isChunkLoaded(frame)
                    && world.getBlockState(frame).isOf(Blocks.END_PORTAL_FRAME)) frames.add(frame);
        }
        return frames;
    }

    private static boolean filled(ServerWorld world, BlockPos frame) {
        return world.getBlockState(frame).get(EndPortalFrameBlock.EYE);
    }

    /** An End portal block of the ring, once it is full. */
    private Optional<BlockPos> portal(ServerWorld world, List<BlockPos> frames) {
        for (BlockPos frame : frames) {
            for (BlockPos at : BlockPos.iterate(frame.add(-3, 0, -3), frame.add(3, 0, 3))) {
                if (world.getBlockState(at).isOf(Blocks.END_PORTAL)) return Optional.of(at.toImmutable());
            }
        }
        return Optional.empty();
    }

    EndFightSnapshot snapshot(ServerPlayerEntity player, PortalRunSnapshot p) {
        var world = player.getServerWorld();
        int eyes = player.getInventory().count(Items.ENDER_EYE);
        var frames = inEnd(player) ? List.<BlockPos>of() : frames(world);
        int filled = (int) frames.stream().filter(f -> filled(world, f)).count();
        boolean open = !inEnd(player) && portal(world, frames).isPresent();
        int arrows = player.getInventory().count(Items.ARROW) + player.getInventory().count(Items.SPECTRAL_ARROW);
        int blocks = blocks(player);
        boolean bow = player.getInventory().count(Items.BOW) > 0;
        // Any melee weapon it holds strikes the perched head; the damage model picks which (E-39e).
        int melee = Weapons.held(player).isEmpty() ? p.swordTier() : Math.max(1, p.swordTier());
        if (!inEnd(player)) {
            return new EndFightSnapshot(false, dragonKilled, frames.size(), filled, eyes, open,
                    false, 0, 0, 0, true, false, false, false, bow, arrows, blocks, melee, player.getHealth(), pearls(player));
        }
        var crystals = crystals(world);
        int caged = (int) crystals.stream().filter(c -> caged(player, c) && !givenUp(world, c.getUuid())).count();
        int shootable = (int) crystals.stream().filter(c -> !caged(player, c) && !givenUp(world, c.getUuid())).count();
        EnderDragonEntity dragon = dragon(world);
        if (dragon != null) dragonSeen = true;
        boolean exit = exitPortal(world).isPresent();
        if (dragonSeen && dragon == null && exit) dragonKilled = true;
        int dragonHealth = dragon == null ? 0 : Math.round(dragon.getHealth());
        if (crystals.size() != loggedCrystals || Math.abs(dragonHealth - loggedHealth) >= 20) {
            loggedCrystals = crystals.size();
            loggedHealth = dragonHealth;
            KindredMod.LOGGER.info("{} in the End: {} crystals left ({} caged), the dragon at {} health{}", player.getName().getString(), crystals.size(),
                    caged, dragonHealth, dragon == null ? (dragonKilled ? ", dead" : ", unseen") : "");
        }
        return new EndFightSnapshot(true, dragonKilled, 0, 0, eyes, true, onIsland(player), crystals.size(), shootable, caged,
                dragon != null, dragon != null && NativeDragonAction.perched(dragon), NativeDragonAction.breath(player, 1.0) != null, exit,
                bow, arrows, blocks, melee, player.getHealth(), pearls(player));
    }

    private static int blocks(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        return inventory.count(Items.COBBLESTONE) + inventory.count(Items.COBBLED_DEEPSLATE) + inventory.count(Items.NETHERRACK)
                + inventory.count(Items.END_STONE) + inventory.count(Items.DIRT) + inventory.count(Items.STONE);
    }

    /** The crystals on the main island's pillars, alive, in loaded ground: not those on the exit portal. */
    private static List<EndCrystalEntity> crystals(ServerWorld world) {
        return world.getEntitiesByClass(EndCrystalEntity.class, new Box(-90, world.getBottomY(), -90, 90, world.getTopY(), 90),
                crystal -> crystal.isAlive() && Math.hypot(crystal.getX(), crystal.getZ()) > 12);
    }

    private void giveUp(ServerWorld world, UUID crystal) {
        givenUp.put(crystal, world.getTime() + GIVE_UP_TICKS);
    }

    /** Given up on for now; once the while is over, its count of arrows and its spot start again. */
    private boolean givenUp(ServerWorld world, UUID crystal) {
        Long until = givenUp.get(crystal);
        if (until == null) return false;
        if (world.getTime() < until) return true;
        givenUp.remove(crystal);
        shotsAt.remove(crystal);
        stands.remove(crystal);
        badStands.remove(crystal);
        return false;
    }

    /** Iron bars about the crystal: its cage, while any stands on the side the player last opened it from or any side. */
    private boolean caged(ServerPlayerEntity player, EndCrystalEntity crystal) {
        UUID id = crystal.getUuid();
        var world = player.getServerWorld();
        var around = bars(world, crystal);
        if (around.isEmpty()) return false;
        // Arrows kept missing it through its bars: a cage to open, until fewer bars stand than when that was found.
        Integer forced = forcedCage.get(id);
        if (forced != null) {
            if (around.size() >= forced) return true;
            forcedCage.remove(id);
            cageLooked.remove(id);
            opened.remove(id);
        }
        if (opened.contains(id)) return false;
        // Bars that leave a clear arc from somewhere on the island (broken on one side, by it or anyone, before a
        // restart or since) are no cage: looked at again every so often, not every tick.
        long now = world.getTime();
        if (cageLooked.getOrDefault(id, Long.MIN_VALUE) <= now) {
            cageLooked.put(id, now + CAGE_LOOK_TICKS);
            if (standFor(player, crystal).isPresent()) {
                opened.add(id);
                return false;
            }
        }
        return true;
    }

    /** Crystals its arrows kept missing through the bars, and how many bars stood about each then. */
    private final Map<UUID, Integer> forcedCage = new HashMap<>();

    private void forceCage(ServerPlayerEntity player, EndCrystalEntity crystal) {
        UUID id = crystal.getUuid();
        forcedCage.put(id, bars(player.getServerWorld(), crystal).size());
        opened.remove(id);
        shotsAt.remove(id);
        stands.remove(id);
        badStands.remove(id);
        KindredMod.LOGGER.info("{}: arrows keep missing the crystal at {} through its bars; opening the cage instead", player.getName().getString(),
                crystal.getBlockPos().toShortString());
    }

    /** When each caged crystal is next looked at for a clear arc through its bars. */
    private final Map<UUID, Long> cageLooked = new HashMap<>();
    static final long CAGE_LOOK_TICKS = 600;

    private static List<BlockPos> bars(ServerWorld world, EndCrystalEntity crystal) {
        var bars = new ArrayList<BlockPos>();
        BlockPos at = crystal.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(at.add(-3, -2, -3), at.add(3, 4, 3))) {
            if (world.getBlockState(pos).isOf(Blocks.IRON_BARS)) bars.add(pos.toImmutable());
        }
        return bars;
    }

    private static EnderDragonEntity dragon(ServerWorld world) {
        var dragons = world.getAliveEnderDragons();
        return dragons.isEmpty() ? null : dragons.getFirst();
    }

    /** An open exit portal's block on the bedrock fountain at the island's middle: the dragon is dead. */
    private static Optional<BlockPos> exitPortal(ServerWorld world) {
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, 0, 0);
        for (BlockPos pos : BlockPos.iterate(-3, top - 12, -3, 3, top + 2, 3)) {
            if (world.isChunkLoaded(pos) && world.getBlockState(pos).isOf(Blocks.END_PORTAL)) return Optional.of(pos.toImmutable());
        }
        return Optional.empty();
    }

    /** On the main island's end stone (or blocks it put down on it), within the fight's reach of the middle. */
    private static boolean onIsland(ServerPlayerEntity player) {
        if (Math.hypot(player.getX(), player.getZ()) > 90) return false;
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        // Over end stone, or over a pillar's obsidian (the island's own), however high it has climbed beside a cage.
        for (int dy = 1; dy <= 48; dy++) {
            var state = world.getBlockState(feet.down(dy));
            if (state.isOf(Blocks.END_STONE) || state.isOf(Blocks.OBSIDIAN)) return true;
        }
        return false;
    }

    /** The next step toward beating the dragon, or empty when nothing can be done from here now. */
    Optional<PortalRunCampaign.Task> next(NativePlayerBody body, PortalRunSnapshot p, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        var s = snapshot(player, p);
        stage = EndFightPlanner.stage(s);
        // Up on a cage's roof by pearl: open it, shoot from the height, and down by pearl again; nothing that walks.
        if (s.inEnd() && !s.inBreath() && player.isOnGround()) {
            var roof = roofUnder(player);
            if (roof.isPresent()) {
                var work = roofWork(body, roof.get());
                progress = "Dragon: " + label(stage) + ": on a cage's roof - " + work.label();
                return Optional.of(work);
            }
        }
        // High up with no tower remembered (the server restarted while it was up there): its own pillar found again.
        if (s.inEnd() && !s.inBreath() && towerBase == null && caging == null && player.isOnGround() && aloft(player)) {
            towerBase = findTower(player).orElse(null);
            if (towerBase != null && !onTower(player)) {
                BlockPos top = new BlockPos(towerBase.getX(), player.getBlockY(), towerBase.getZ());
                progress = "Dragon: " + label(stage) + ": up high - back onto its own pillar";
                return Optional.of(PortalRunCampaign.go(top, 0.3, true, "Back onto its own pillar"));
            }
        }
        // Up its own pillar by a cage: the bars, then down again, before anything that walks (staged dragon run 2, a
        // block off the pillar's middle, was about to walk off it twenty blocks up).
        // Its pillar is gone under it (it is down, or the column was another's): done with it.
        if (towerBase != null && onTower(player) && !placed(player.getServerWorld().getBlockState(
                new BlockPos(towerBase.getX(), player.getBlockY() - 1, towerBase.getZ())))) {
            towerBase = null;
            caging = null;
        }
        if (s.inEnd() && !s.inBreath() && onTower(player)) {
            Optional<PortalRunCampaign.Task> tower = caging != null ? openCage(body) : Optional.empty();
            if (tower.isEmpty()) tower = Optional.of(down(player));
            progress = "Dragon: " + label(stage) + ": up beside a cage - " + tower.get().label();
            return tower;
        }
        for (var step : EndFightPlanner.plan(s)) {
            Optional<PortalRunCampaign.Task> task = realize(step, s, body, policy);
            if (task.isPresent() && !campaign.setAside(task.get())) {
                progress = "Dragon: " + label(stage) + ": " + step.why() + " - " + task.get().label();
                return task;
            }
        }
        if (stage == EndFightPlanner.Stage.DONE) {
            progress = "Dragon: beaten";
            return Optional.empty();
        }
        // In the End nothing is ever left to the campaign's own fallbacks (an island's night pit, a ledge to jump off):
        // a moment's wait where it stands, and the fight looked at again (staged dragon run 5 stood idle for seconds
        // at a time with the dragon breathing nearby).
        progress = "Dragon: " + label(stage) + ": nothing feasible this moment - Wait";
        return s.inEnd() ? Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Wait a moment")) : Optional.empty();
    }

    static String label(EndFightPlanner.Stage stage) {
        String name = stage.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private Optional<PortalRunCampaign.Task> realize(EndFightPlanner.Step step, EndFightSnapshot s, NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        return switch (step.action()) {
            case FILL_FRAME -> fillFrame(body);
            case ENTER_PORTAL -> s.inEnd() ? exit(body) : enterPortal(body);
            case TO_ISLAND -> toIsland(body, policy);
            case SHOOT_CRYSTAL -> shootCrystal(body);
            case OPEN_CAGE -> openCage(body);
            case SEEK_CRYSTALS, HOLD_GROUND -> holdGround(body);
            case LEAVE_BREATH -> leaveBreath(body);
            case STRIKE_DRAGON -> strikeDragon(body);
            case SHOOT_DRAGON -> shootDragon(body);
            case DONE -> Optional.empty();
        };
    }

    // ------------------------------------------------------------------ in the End

    /** The island's top at a column, if it is end stone there: somewhere to stand. */
    private static Optional<BlockPos> islandTop(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(new BlockPos(x, 0, z))) return Optional.empty();
        BlockPos top = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        return world.getBlockState(top).isOf(Blocks.END_STONE) && world.getBlockState(top.up()).isAir() && world.getBlockState(top.up(2)).isAir()
                ? Optional.of(top.up()) : Optional.empty();
    }

    /** Off the arrival platform onto the main island: the first end stone toward the middle, bridged to if need be. */
    private Optional<PortalRunCampaign.Task> toIsland(NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        var world = player.getServerWorld();
        double d = Math.hypot(player.getX(), player.getZ());
        double ux = -player.getX() / Math.max(1, d), uz = -player.getZ() / Math.max(1, d);
        for (int step = 4; step <= d; step += 2) {
            var top = islandTop(world, (int) Math.floor(player.getX() + ux * step), (int) Math.floor(player.getZ() + uz * step));
            if (top.isPresent()) {
                BlockPos at = top.get();
                return campaign.buildRoute(body, policy, at, pos -> pos.isWithinDistance(at, 2.5), "Onto the main island")
                        .or(() -> Optional.of(PortalRunCampaign.go(at, 2.0, true, "Onto the main island")));
            }
        }
        BlockPos toward = BlockPos.ofFloored(player.getX() + ux * 12, player.getY(), player.getZ() + uz * 12);
        return campaign.buildRoute(body, policy, toward, pos -> pos.isWithinDistance(toward, 3), "Toward the main island");
    }

    private static int hotbarOf(ServerPlayerEntity player, net.minecraft.item.Item item) {
        for (int slot = 0; slot < 9; slot++) if (player.getInventory().getStack(slot).isOf(item)) return slot;
        return -1;
    }

    private Optional<PortalRunCampaign.Task> readyInHotbar(NativePlayerBody body, net.minecraft.item.Item item, String label) {
        var inventory = body.entity().getInventory();
        for (int slot = 9; slot < 36; slot++) if (inventory.getStack(slot).isOf(item)) return Optional.of(campaign.swapToHotbar(body, slot, label));
        return Optional.empty();
    }

    /** Whether an arrow from {@code stand} (where the feet are) reaches {@code target} on its low arc with nothing in the way. */
    private static boolean clearShot(ServerWorld world, Vec3d stand, Vec3d target) {
        return clearAlong(world, stand, target, false);
    }

    /**
     * The arc an arrow from {@code stand} reaches {@code target} on with nothing in the way, the arc followed tick by
     * tick against the blocks: the low one ({@code false}) if it is clear, else the high one ({@code true}), which
     * comes down steeply through an opening above the target (E-39f); empty when neither is clear.
     */
    static Optional<Boolean> clearArc(ServerWorld world, Vec3d stand, Vec3d target) {
        if (clearAlong(world, stand, target, false)) return Optional.of(false);
        if (clearAlong(world, stand, target, true)) return Optional.of(true);
        return Optional.empty();
    }

    private static boolean clearAlong(ServerWorld world, Vec3d stand, Vec3d target, boolean high) {
        Vec3d start = stand.add(0, 1.62 - 0.1, 0);
        double dx = target.x - start.x, dz = target.z - start.z, distance = Math.hypot(dx, dz);
        if (distance < 1) return false;
        var shot = Ballistics.aim(distance, target.y - start.y, Ballistics.Projectile.ARROW, high);
        if (shot.isEmpty()) return false;
        var arc = Ballistics.trajectory(shot.get().pitch(), Ballistics.Projectile.ARROW, distance);
        double ux = dx / distance, uz = dz / distance;
        Vec3d previous = start;
        for (var point : arc) {
            Vec3d next = new Vec3d(start.x + ux * point[0], start.y + point[1], start.z + uz * point[0]);
            if (next.distanceTo(target) < 1.0) return true;
            var hit = world.raycast(new RaycastContext(previous, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
                    net.minecraft.block.ShapeContext.absent()));
            if (hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) return false;
            previous = next;
        }
        return true;
    }

    /** A stand it has shot from enough, or one its walk there failed to reach (staged dragon run 2 tried two for a minute). */
    private boolean unusable(UUID crystal, BlockPos stand) {
        return badStands.getOrDefault(crystal, Set.of()).contains(stand.asLong()) || campaign.unreachable(stand) || campaign.blocked(stand);
    }

    /** Not this near the exit portal's pillar, where the dragon lands and breathes. */
    static final double PERCH_CLEAR = 12;

    /**
     * A spot on the island to shoot {@code crystal} from: on end stone, with a clear arc to it, far enough from the blast
     * and near enough to hit; the one nearest the player, round the pillar at a widening range.
     */
    private Optional<BlockPos> standFor(ServerPlayerEntity player, EndCrystalEntity crystal) {
        var world = player.getServerWorld();
        Vec3d target = crystal.getBoundingBox().getCenter();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean bestHigh = false;
        for (double range = NEAREST_SHOT + 4; range <= FARTHEST_SHOT; range += 8) {
            for (int a = 0; a < 24; a++) {
                double angle = Math.toRadians(a * 15);
                int x = (int) Math.floor(crystal.getX() + Math.cos(angle) * range), z = (int) Math.floor(crystal.getZ() + Math.sin(angle) * range);
                var top = islandTop(world, x, z);
                if (top.isEmpty() || unusable(crystal.getUuid(), top.get()) || Math.hypot(x, z) > 80 || Math.hypot(x, z) < PERCH_CLEAR) continue;
                // Not by the island's edge, where a charging dragon knocks the player into the void.
                if (!islandTop(world, x + 4, z).isPresent() && !islandTop(world, x - 4, z).isPresent()) continue;
                Vec3d feet = Vec3d.ofBottomCenter(top.get());
                var arc = clearArc(world, feet, target);
                if (arc.isEmpty()) continue;
                double distance = feet.distanceTo(player.getPos());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = top.get();
                    bestHigh = arc.get();
                }
            }
            if (best != null) {
                highArc.put(crystal.getUuid(), bestHigh);
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }

    /** Whether the stand chosen for each crystal shoots it on the high arc. */
    private final Map<UUID, Boolean> highArc = new HashMap<>();

    /** The nearest crystal it can shoot: from where it stands if the arc is clear, else from a spot with a clear arc. */
    private Optional<PortalRunCampaign.Task> shootCrystal(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        // The crystal it set out for first, until it is down or given up: the nearest changes on the walk between two
        // (staged dragon run 2 turned back and forth between two pillars for a minute).
        var targets = crystals(world).stream().filter(c -> !caged(player, c) && !givenUp(world, c.getUuid()))
                .sorted(java.util.Comparator.<EndCrystalEntity>comparingInt(c -> c.getUuid().equals(target) ? 0 : 1)
                        .thenComparingDouble(player::squaredDistanceTo)).toList();
        for (EndCrystalEntity crystal : targets) {
            UUID id = crystal.getUuid();
            target = id;
            int bow = hotbarOf(player, Items.BOW);
            if (bow < 0) return readyInHotbar(body, Items.BOW, "Ready the bow");
            double away = player.getPos().distanceTo(crystal.getPos());
            var hereArc = player.isOnGround() && away >= NEAREST_SHOT && away <= FARTHEST_SHOT
                    ? clearArc(world, player.getPos(), crystal.getBoundingBox().getCenter()) : Optional.<Boolean>empty();
            boolean here = hereArc.isPresent();
            BlockPos stand = stands.get(id);
            if (!here && (stand == null || unusable(id, stand))) {
                stand = standFor(player, crystal).orElse(null);
                if (stand == null) {
                    giveUp(world, id);
                    KindredMod.LOGGER.info("{} finds no spot with a clear shot at the crystal at {}", player.getName().getString(), crystal.getBlockPos().toShortString());
                    continue;
                }
                stands.put(id, stand);
            }
            if (here || stand != null && player.getBlockPos().isWithinDistance(stand, 1.5) && player.isOnGround()) {
                int shots = shotsAt.merge(id, 1, Integer::sum);
                if (shots % SHOTS_PER_STAND == 0 && stand != null) badStands.computeIfAbsent(id, k -> new HashSet<>()).add(stand.asLong());
                if (shots >= SHOTS_PER_CRYSTAL) {
                    // Through bars that keep turning its arrows: no longer a crystal to shoot at but a cage to open.
                    if (!bars(world, crystal).isEmpty()) forceCage(player, crystal);
                    else giveUp(world, id);
                }
                boolean high = here ? hereArc.get() : highArc.getOrDefault(id, false);
                var args = new HashMap<String, String>(Map.of("slot", String.valueOf(bow), "target", id.toString()));
                if (high) args.put("arc", "high");
                return Optional.of(new PortalRunCampaign.Task("native_shoot", Map.copyOf(args),
                        "Shoot the crystal on the pillar at " + crystal.getBlockPos().toShortString() + (high ? " on the high arc" : "")));
            }
            return Optional.of(PortalRunCampaign.go(stand, 1.0, true, "To a clear shot at the crystal at " + crystal.getBlockPos().toShortString()));
        }
        return Optional.empty();
    }

    /**
     * A caged crystal's bars opened on one side: up a pillar of its own blocks beside the crystal's pillar to the cage,
     * the bars in reach on its side broken, and down its pillar again a block at a time; the crystal is then shot from
     * the ground like the rest.
     */
    private Optional<PortalRunCampaign.Task> openCage(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        var cages = crystals(world).stream().filter(c -> caged(player, c) && !givenUp(world, c.getUuid()))
                .sorted(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).toList();
        // Coming down from a cage already opened: a block at a time, down to the ground it started from.
        if (caging == null && onTower(player)) return Optional.of(down(player));
        if (cages.isEmpty()) {
            caging = null;
            return onTower(player) ? Optional.of(down(player)) : Optional.empty();
        }
        EndCrystalEntity crystal = cages.stream().filter(c -> c.getUuid().equals(caging)).findFirst().orElse(cages.getFirst());
        // Quicker than a pillar when it holds pearls: up onto the roof by one, and back down by another (E-39f).
        if (towerBase == null) {
            var byPearl = pearlUp(body, crystal);
            if (byPearl.isPresent()) return byPearl;
        }
        caging = crystal.getUuid();
        BlockPos center = crystal.getBlockPos();
        if (towerBase == null || towerBase.getSquaredDistance(center.getX(), towerBase.getY(), center.getZ()) > 6 * 6) {
            towerBase = towerFoot(world, player, crystal).orElse(null);
            if (towerBase == null) {
                giveUp(world, caging);
                caging = null;
                return Optional.empty();
            }
        }
        boolean atFoot = player.getBlockY() == towerBase.getY() && Math.hypot(player.getX() - (towerBase.getX() + 0.5), player.getZ() - (towerBase.getZ() + 0.5)) < 0.6;
        if (!atFoot && !onTower(player)) {
            if (aloft(player)) return Optional.empty(); // Up on some other height: not from here.
            return Optional.of(PortalRunCampaign.go(towerBase, 0.4, true, "To the foot of the caged crystal's pillar"));
        }
        // Up to where the bars are in reach, a block at a time, on the pillar's own column.
        BlockPos feet = new BlockPos(towerBase.getX(), player.getBlockY(), towerBase.getZ());
        int barsAt = crystal.getBlockY() - 1;
        if (feet.getY() < barsAt) {
            int slot = -1;
            for (int i = 0; i < 9 && slot < 0; i++) if (isBlock(player.getInventory().getStack(i))) slot = i;
            if (slot < 0) {
                for (int i = 9; i < 36; i++) if (isBlock(player.getInventory().getStack(i))) return Optional.of(campaign.swapToHotbar(body, i, "Ready a block"));
                return Optional.empty();
            }
            return Optional.of(new PortalRunCampaign.Task("native_pillar", Map.of("x", String.valueOf(feet.getX()), "y", String.valueOf(feet.getY()),
                    "z", String.valueOf(feet.getZ()), "slot", String.valueOf(slot)), "Up beside the cage: pillar up"));
        }
        // At the cage: the bars in reach on this side.
        Vec3d eye = player.getEyePos();
        var near = bars(world, crystal).stream().filter(b -> eye.distanceTo(Vec3d.ofCenter(b)) <= 4.4)
                .min(java.util.Comparator.comparingDouble(b -> eye.squaredDistanceTo(Vec3d.ofCenter(b))));
        if (near.isPresent()) {
            BlockPos bar = near.get();
            // Not picked up: going after a bar's drop walks the player off its own pillar (staged dragon run 3).
            return Optional.of(new PortalRunCampaign.Task("native_break", Map.of("x", String.valueOf(bar.getX()), "y", String.valueOf(bar.getY()),
                    "z", String.valueOf(bar.getZ()), "collect", "false"), "Break the cage's bars at " + bar.toShortString()));
        }
        opened.add(caging);
        KindredMod.LOGGER.info("{} opened the cage of the crystal at {}", player.getName().getString(), center.toShortString());
        caging = null;
        return Optional.of(down(player));
    }

    // ------------------------------------------------------------------ pearls (E-39f)

    /** Pearl tries at each cage's roof: after two that did not bring it there, the pillar of its own blocks instead. */
    private final Map<UUID, Integer> pearlTries = new HashMap<>();
    private final Map<UUID, BlockPos> pearlStands = new HashMap<>();
    /** Arrows loosed from up on a roof at each other crystal. */
    private final Map<UUID, Integer> roofShots = new HashMap<>();
    /** Health it goes up with: five for the pearl up and five for the one down, and some to spare. */
    static final float PEARL_HEALTH = 16;
    static final int PEARL_TRIES = 2;

    private static int pearls(ServerPlayerEntity player) {
        return player.getInventory().count(Items.ENDER_PEARL);
    }

    /** Where feet stand on a cage's roof: on the bars three above the bedrock under the crystal. */
    private static int roofFeetY(EndCrystalEntity crystal) {
        return crystal.getBlockY() + 3;
    }

    static boolean onRoof(ServerPlayerEntity player, EndCrystalEntity crystal) {
        BlockPos at = crystal.getBlockPos();
        return player.getBlockY() == roofFeetY(crystal) && Math.abs(player.getBlockX() - at.getX()) <= 2 && Math.abs(player.getBlockZ() - at.getZ()) <= 2;
    }

    private static Optional<EndCrystalEntity> roofUnder(ServerPlayerEntity player) {
        return crystals(player.getServerWorld()).stream().filter(c -> onRoof(player, c)).findFirst();
    }

    private static PortalRunCampaign.Task pearlTask(int slot, PearlFlight.Throw t, String label) {
        BlockPos rest = t.rest();
        return new PortalRunCampaign.Task("native_pearl", Map.of("slot", String.valueOf(slot),
                "yaw", String.format(Locale.ROOT, "%.3f", t.yaw()), "pitch", String.format(Locale.ROOT, "%.3f", t.pitch()),
                "x", String.valueOf(rest.getX() + 0.5), "y", String.valueOf(rest.getY()), "z", String.valueOf(rest.getZ() + 0.5)), label);
    }

    /**
     * A steady throw from {@code feet} onto the roof of {@code crystal}'s cage, aimed at the roof's edge nearest the
     * thrower so that the bars over the crystal are left to break, and from whose landing a steady throw back down exists.
     */
    private Optional<PearlFlight.Throw> roofThrow(ServerPlayerEntity player, Vec3d feet, EndCrystalEntity crystal) {
        return roofThrow(player, feet, crystal, campaign::blocked);
    }

    static Optional<PearlFlight.Throw> roofThrow(ServerPlayerEntity player, Vec3d feet, EndCrystalEntity crystal,
                                                java.util.function.Predicate<BlockPos> blocked) {
        BlockPos at = crystal.getBlockPos();
        double dx = feet.x - (at.getX() + 0.5), dz = feet.z - (at.getZ() + 0.5), d = Math.hypot(dx, dz);
        if (d < 4 || d > 22) return Optional.empty();
        BlockPos edge = Math.abs(dx) >= Math.abs(dz) ? new BlockPos(at.getX() + (int) Math.signum(dx) * 2, roofFeetY(crystal), at.getZ())
                : new BlockPos(at.getX(), roofFeetY(crystal), at.getZ() + (int) Math.signum(dz) * 2);
        var up = PearlFlight.toward(player, feet, edge, 2.0)
                .filter(t -> Math.abs(t.rest().getX() - at.getX()) <= 2 && Math.abs(t.rest().getZ() - at.getZ()) <= 2);
        return up.filter(t -> downThrow(player, Vec3d.ofBottomCenter(t.rest()), crystal, blocked).isPresent());
    }

    /** A steady throw from {@code feet} up on the roof down to end stone round the pillar, the nearest first. */
    private Optional<PearlFlight.Throw> downThrow(ServerPlayerEntity player, Vec3d feet, EndCrystalEntity crystal) {
        return downThrow(player, feet, crystal, campaign::blocked);
    }

    static Optional<PearlFlight.Throw> downThrow(ServerPlayerEntity player, Vec3d feet, EndCrystalEntity crystal,
                                                java.util.function.Predicate<BlockPos> blocked) {
        var world = player.getServerWorld();
        var spots = new ArrayList<BlockPos>();
        for (int r = 12; r <= 24; r += 4) for (int a = 0; a < 16; a++) {
            double angle = Math.toRadians(a * 22.5);
            islandTop(world, (int) Math.floor(crystal.getX() + Math.cos(angle) * r), (int) Math.floor(crystal.getZ() + Math.sin(angle) * r))
                    .filter(top -> Math.hypot(top.getX(), top.getZ()) >= PERCH_CLEAR + 4 && !blocked.test(top))
                    .filter(top -> islandTop(world, top.getX() + 4, top.getZ()).isPresent() || islandTop(world, top.getX() - 4, top.getZ()).isPresent())
                    .ifPresent(spots::add);
        }
        spots.sort(java.util.Comparator.comparingDouble(top -> Vec3d.ofBottomCenter(top).squaredDistanceTo(feet)));
        int tried = 0;
        for (BlockPos spot : spots) {
            if (++tried > 16) break;
            var t = PearlFlight.toward(player, feet, spot, 1.5);
            if (t.isPresent()) return t;
        }
        return Optional.empty();
    }

    /**
     * Up onto the roof of {@code crystal}'s cage by pearl: from here if a steady throw reaches it, else from a spot round
     * the pillar that one does; only with two pearls (up and down) and the health for both landings.
     */
    private Optional<PortalRunCampaign.Task> pearlUp(NativePlayerBody body, EndCrystalEntity crystal) {
        var player = body.entity();
        UUID id = crystal.getUuid();
        if (pearls(player) < 2 || player.getHealth() < PEARL_HEALTH || pearlTries.getOrDefault(id, 0) >= PEARL_TRIES) return Optional.empty();
        if (!player.isOnGround() || aloft(player)) return Optional.empty();
        int slot = hotbarOf(player, Items.ENDER_PEARL);
        if (slot < 0) return readyInHotbar(body, Items.ENDER_PEARL, "Ready an ender pearl");
        String where = crystal.getBlockPos().toShortString();
        var fromHere = roofThrow(player, player.getPos(), crystal);
        if (fromHere.isPresent()) {
            pearlTries.merge(id, 1, Integer::sum);
            KindredMod.LOGGER.info("{} pearls up onto the cage of the crystal at {}", player.getName().getString(), where);
            return Optional.of(pearlTask(slot, fromHere.get(), "Pearl up onto the cage of the crystal at " + where));
        }
        BlockPos stand = pearlStands.get(id);
        if (stand != null && player.getBlockPos().isWithinDistance(stand, 1.5)) {
            // There, and the throw no longer holds (the player stands a little off the spot): another try elsewhere.
            pearlStands.remove(id);
            pearlTries.merge(id, 1, Integer::sum);
            return Optional.empty();
        }
        if (stand == null || unusable(id, stand)) {
            stand = pearlStand(player, crystal).orElse(null);
            if (stand == null) {
                pearlTries.put(id, PEARL_TRIES);
                KindredMod.LOGGER.info("{} finds no steady pearl throw onto the cage at {}; a pillar instead", player.getName().getString(), where);
                return Optional.empty();
            }
            pearlStands.put(id, stand);
        }
        return Optional.of(PortalRunCampaign.go(stand, 0.4, true, "To a pearl's throw onto the cage at " + where));
    }

    /** End stone round the pillar from which a steady throw lands on the roof, the nearest to the player first. */
    private Optional<BlockPos> pearlStand(ServerPlayerEntity player, EndCrystalEntity crystal) {
        var world = player.getServerWorld();
        var spots = new ArrayList<BlockPos>();
        for (int r = 8; r <= 17; r += 3) for (int a = 0; a < 16; a++) {
            double angle = Math.toRadians(a * 22.5);
            islandTop(world, (int) Math.floor(crystal.getX() + Math.cos(angle) * r), (int) Math.floor(crystal.getZ() + Math.sin(angle) * r))
                    .filter(top -> !unusable(crystal.getUuid(), top) && Math.hypot(top.getX(), top.getZ()) >= PERCH_CLEAR)
                    .ifPresent(spots::add);
        }
        spots.sort(java.util.Comparator.comparingDouble(top -> top.getSquaredDistance(player.getBlockPos())));
        int tried = 0;
        for (BlockPos spot : spots) {
            if (++tried > 12) break;
            if (roofThrow(player, Vec3d.ofBottomCenter(spot), crystal).isPresent()) return Optional.of(spot);
        }
        return Optional.empty();
    }

    /**
     * On the roof: the bars over the crystal broken (not those it stands on, nor picked up), then an arrow at any other
     * crystal with a clear arc from the height, then down by pearl to end stone; the opened crystal is shot from the
     * ground on the high arc, down through the roof.
     */
    private PortalRunCampaign.Task roofWork(NativePlayerBody body, EndCrystalEntity crystal) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos at = crystal.getBlockPos();
        int roofY = at.getY() + 2;
        Vec3d eye = player.getEyePos();
        Box under = player.getBoundingBox().expand(0.05, 0, 0.05).offset(0, -0.2, 0);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos bar = new BlockPos(at.getX() + dx, roofY, at.getZ() + dz);
            if (!world.getBlockState(bar).isOf(Blocks.IRON_BARS) || new Box(bar).intersects(under)) continue;
            if (eye.distanceTo(Vec3d.ofCenter(bar)) > 4.4) continue;
            return new PortalRunCampaign.Task("native_break", Map.of("x", String.valueOf(bar.getX()), "y", String.valueOf(bar.getY()),
                    "z", String.valueOf(bar.getZ()), "collect", "false"), "Open the cage's roof at " + bar.toShortString());
        }
        cageLooked.remove(crystal.getUuid()); // From the ground, its arc through the opened roof is looked at afresh.
        int bow = hotbarOf(player, Items.BOW);
        if (bow >= 0) {
            for (EndCrystalEntity other : crystals(world)) {
                if (other == crystal || givenUp(world, other.getUuid()) || roofShots.getOrDefault(other.getUuid(), 0) >= SHOTS_PER_STAND) continue;
                if (player.getPos().distanceTo(other.getPos()) < NEAREST_SHOT || !bars(world, other).isEmpty()) continue;
                var arc = clearArc(world, player.getPos(), other.getBoundingBox().getCenter());
                if (arc.isEmpty()) continue;
                roofShots.merge(other.getUuid(), 1, Integer::sum);
                var args = new HashMap<String, String>(Map.of("slot", String.valueOf(bow), "target", other.getUuid().toString()));
                if (arc.get()) args.put("arc", "high");
                return new PortalRunCampaign.Task("native_shoot", Map.copyOf(args), "From the cage's roof, shoot the crystal at " + other.getBlockPos().toShortString());
            }
        }
        int slot = hotbarOf(player, Items.ENDER_PEARL);
        if (slot < 0) {
            for (int i = 9; i < 36; i++) if (player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) return campaign.swapToHotbar(body, i, "Ready an ender pearl");
        }
        var down = slot < 0 ? Optional.<PearlFlight.Throw>empty() : downThrow(player, player.getPos(), crystal);
        if (down.isPresent()) return pearlTask(slot, down.get(), "Pearl down from the cage at " + at.toShortString());
        return new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Wait on the cage's roof for a throw down");
    }

    /** No end stone within a few blocks underfoot: on a pillar's top, or a tower of its own. */
    private static boolean aloft(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        for (int dy = 1; dy <= 4; dy++) if (world.getBlockState(player.getBlockPos().down(dy)).isOf(Blocks.END_STONE)) return false;
        return true;
    }

    /**
     * A tower of its own beside where it stands, its top level with the player's feet: a column of placed blocks, at
     * least three high, down to the island's end stone. Its foot is where the first of them was put.
     */
    private static Optional<BlockPos> findTower(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            BlockPos at = feet.add(dx, -1, dz);
            int height = 0;
            while (height < 64 && placed(world.getBlockState(at.down(height)))) height++;
            if (height < 3 || !world.getBlockState(at.down(height)).isOf(Blocks.END_STONE)) continue;
            double distance = Math.hypot(dx, dz);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = at.down(height - 1);
            }
        }
        return Optional.ofNullable(best);
    }

    /** A block of the kinds it pillars with, not the island's own end stone. */
    private static boolean placed(net.minecraft.block.BlockState state) {
        return state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.COBBLED_DEEPSLATE) || state.isOf(Blocks.NETHERRACK)
                || state.isOf(Blocks.DIRT) || state.isOf(Blocks.STONE);
    }

    /** On its own pillar by a cage, above the ground it started from: within a block or so of the column's middle. */
    private boolean onTower(ServerPlayerEntity player) {
        return towerBase != null && player.getBlockY() > towerBase.getY()
                && Math.hypot(player.getX() - (towerBase.getX() + 0.5), player.getZ() - (towerBase.getZ() + 0.5)) < 1.5;
    }

    /** Down its pillar a block: the block of the column underfoot broken, as a player digs down its own tower. */
    private PortalRunCampaign.Task down(ServerPlayerEntity player) {
        return new PortalRunCampaign.Task("native_break", Map.of("x", String.valueOf(towerBase.getX()), "y", String.valueOf(player.getBlockY() - 1),
                "z", String.valueOf(towerBase.getZ()), "underfoot", "true"), "Down the pillar a block");
    }

    private static boolean isBlock(net.minecraft.item.ItemStack stack) {
        return stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.COBBLED_DEEPSLATE) || stack.isOf(Items.NETHERRACK) || stack.isOf(Items.END_STONE)
                || stack.isOf(Items.DIRT) || stack.isOf(Items.STONE);
    }

    /** Ground beside the crystal's pillar, a block out from its obsidian, the nearest to the player. */
    private static Optional<BlockPos> towerFoot(ServerWorld world, ServerPlayerEntity player, EndCrystalEntity crystal) {
        int cx = crystal.getBlockX(), cz = crystal.getBlockZ();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int r = 2; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                var top = islandTop(world, cx + dx, cz + dz);
                if (top.isEmpty()) continue;
                // Beside the obsidian: a neighbour toward the pillar's middle is obsidian, a block or two up.
                BlockPos in = top.get().add(-Integer.signum(dx), 1, -Integer.signum(dz));
                if (!world.getBlockState(in).isOf(Blocks.OBSIDIAN)) continue;
                double distance = top.get().getSquaredDistance(player.getBlockPos());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = top.get();
                }
            }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    /** The dragon's breath lying on the island within reach of the player's walks. */
    private static List<AreaEffectCloudEntity> clouds(ServerPlayerEntity player) {
        return player.getServerWorld().getEntitiesByClass(AreaEffectCloudEntity.class, player.getBoundingBox().expand(48, 8, 48),
                cloud -> cloud.isAlive() && cloud.getParticleType().getType() == ParticleTypes.DRAGON_BREATH);
    }

    /**
     * A walk straight from the player to {@code spot} keeps a block clear of every cloud of breath, the one it may stand
     * in now aside, which the spot itself must be well out of.
     */
    private static boolean clearOfBreath(ServerPlayerEntity player, BlockPos spot, List<AreaEffectCloudEntity> clouds) {
        double ax = player.getX(), az = player.getZ(), bx = spot.getX() + 0.5, bz = spot.getZ() + 0.5;
        for (var cloud : clouds) {
            double margin = cloud.getRadius() + 1.5;
            if (Math.hypot(bx - cloud.getX(), bz - cloud.getZ()) < margin + 1) return false;
            if (Math.hypot(ax - cloud.getX(), az - cloud.getZ()) < margin) continue; // In it now: only out of it counts.
            // Nearest approach of the walk to the cloud's middle.
            double dx = bx - ax, dz = bz - az, length = dx * dx + dz * dz;
            double t = length == 0 ? 0 : Math.clamp(((cloud.getX() - ax) * dx + (cloud.getZ() - az) * dz) / length, 0, 1);
            if (Math.hypot(ax + t * dx - cloud.getX(), az + t * dz - cloud.getZ()) < margin) return false;
        }
        return true;
    }

    /**
     * The nearest spot on the island's end stone in the ring {@code inner}..{@code outer} blocks out from the exit
     * portal's pillar whose walk there keeps out of the dragon's breath; empty when none.
     */
    private Optional<BlockPos> ringSpot(ServerPlayerEntity player, int inner, int outer) {
        var world = player.getServerWorld();
        var clouds = clouds(player);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int r = inner; r <= outer; r += 3) {
            for (int a = 0; a < 24; a++) {
                double angle = Math.toRadians(a * 15);
                var top = islandTop(world, (int) Math.floor(Math.cos(angle) * r), (int) Math.floor(Math.sin(angle) * r));
                if (top.isEmpty() || campaign.unreachable(top.get()) || campaign.blocked(top.get()) || !clearOfBreath(player, top.get(), clouds)) continue;
                // Not by the island's edge, where a charging dragon knocks the player into the void.
                if (islandTop(world, top.get().getX() + 4, top.get().getZ()).isEmpty() && islandTop(world, top.get().getX() - 4, top.get().getZ()).isEmpty()) continue;
                double distance = Math.hypot(top.get().getX() + 0.5 - player.getX(), top.get().getZ() + 0.5 - player.getZ());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = top.get();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** How far out from the exit portal's pillar the player waits: clear of the perch, where the dragon lands and breathes. */
    static final int HOLD_OUT = 18, REST_OUT = 28;

    /**
     * Hurt in the End: out of the dragon's breath, then out from the exit portal's pillar where the dragon lands and
     * breathes on whoever is near, and there rest (staged dragon run 1 rested beside the pillar and was breathed on;
     * run 3, walking out to rest, walked back through the cloud it had just left).
     */
    Optional<PortalRunCampaign.Task> rest(NativePlayerBody body) {
        var out = leaveBreath(body);
        if (out.isPresent()) return out;
        var player = body.entity();
        double d = Math.hypot(player.getX(), player.getZ());
        if (d < REST_OUT - 4) {
            var spot = ringSpot(player, REST_OUT, REST_OUT + 16);
            if (spot.isPresent()) return Optional.of(PortalRunCampaign.go(spot.get(), 2.0, true, "Away from the dragon's perch to heal"));
        }
        return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "2"), "Rest to heal"));
    }

    /** Out from the exit portal's pillar, away from the edge, while the dragon flies or the crystals left are looked for. */
    private Optional<PortalRunCampaign.Task> holdGround(NativePlayerBody body) {
        var player = body.entity();
        if (player.getHealth() < EndFightPlanner.LOW_HEALTH) return rest(body);
        double d = Math.hypot(player.getX(), player.getZ());
        if (d >= HOLD_OUT - 2 && d <= HOLD_OUT + 14 && !aloft(player)) {
            return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Wait for the dragon to perch"));
        }
        var spot = ringSpot(player, HOLD_OUT, HOLD_OUT + 9);
        if (spot.isEmpty()) return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Wait"));
        return Optional.of(PortalRunCampaign.go(spot.get(), 2.0, true, "To the exit portal's pillar"));
    }

    /** Out of the dragon's breath, ahead of anything else the campaign would do. */
    Optional<PortalRunCampaign.Task> outOfBreath(NativePlayerBody body) {
        return leaveBreath(body);
    }

    /**
     * Out of the dragon's breath: the nearest end stone round the cloud a few blocks past its edge, not toward the perch
     * (staged dragon run 3 fled a cloud toward the pillar) and not through another cloud.
     */
    private Optional<PortalRunCampaign.Task> leaveBreath(NativePlayerBody body) {
        var player = body.entity();
        var cloud = NativeDragonAction.breath(player, 1.0);
        if (cloud == null) return Optional.empty();
        var world = player.getServerWorld();
        var clouds = clouds(player);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (double reach = cloud.getRadius() + 3; reach <= cloud.getRadius() + 9; reach += 3) {
            for (int a = 0; a < 16; a++) {
                double angle = Math.toRadians(a * 22.5);
                var spot = islandTop(world, (int) Math.floor(cloud.getX() + Math.cos(angle) * reach), (int) Math.floor(cloud.getZ() + Math.sin(angle) * reach));
                if (spot.isEmpty() || player.getBlockPos().isWithinDistance(spot.get(), 2) || !clearOfBreath(player, spot.get(), clouds)) continue;
                double out = Math.hypot(spot.get().getX(), spot.get().getZ());
                double score = Math.hypot(spot.get().getX() + 0.5 - player.getX(), spot.get().getZ() + 0.5 - player.getZ())
                        + (out < PERCH_CLEAR + 4 ? 20 : 0) + (out > 70 ? 20 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = spot.get();
                }
            }
        }
        if (best == null) return Optional.empty();
        return Optional.of(PortalRunCampaign.go(best, 1.0, true, "Out of the dragon's breath"));
    }

    /**
     * The perched dragon: to a spot in front of its head, out of its wings' reach and its head's touch, and strike it
     * there in one continuous fight while it sits (staged dragon run 5, sent in under the head, was flung back by the
     * wings every time).
     */
    private Optional<PortalRunCampaign.Task> strikeDragon(NativePlayerBody body) {
        var player = body.entity();
        var dragon = dragon(player.getServerWorld());
        if (dragon == null || !NativeDragonAction.perched(dragon)) return Optional.empty();
        // Nowhere to stand: it waits for the next perch (arrows do a perched dragon no harm).
        var spot = NativeDragonAction.strikeSpot(player, dragon);
        if (spot.isEmpty()) return Optional.empty();
        // The weapon that deals the most over the perch (a sword's crits beat an axe's), or the one hit that finishes it.
        var weapon = Weapons.choose(player, dragon.getHealth());
        if (weapon.isPresent() && weapon.get().slot() >= 9) {
            return Optional.of(campaign.swapToHotbar(body, weapon.get().slot(), "Ready the weapon for the dragon"));
        }
        int sword = weapon.map(Weapons.Held::slot).orElse(-1);
        BlockPos at = spot.get();
        // Close enough, the fight itself walks the last blocks, keeping the spot up as the head turns.
        if (Math.hypot(at.getX() + 0.5 - player.getX(), at.getZ() + 0.5 - player.getZ()) <= 6 && Math.abs(at.getY() - player.getY()) <= 2) {
            var args = new HashMap<String, String>(Map.of("target", dragon.getUuid().toString(),
                    "x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()), "z", String.valueOf(at.getZ())));
            if (sword >= 0) args.put("slot", String.valueOf(sword));
            return Optional.of(new PortalRunCampaign.Task("native_dragon", Map.copyOf(args), "Strike the dragon's head"));
        }
        return Optional.of(PortalRunCampaign.go(at, 1.5, true, "In front of the perched dragon's head"));
    }

    /** The dragon in flight, in range with a clear arc: an arrow at its head. */
    private Optional<PortalRunCampaign.Task> shootDragon(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        var dragon = dragon(world);
        // Never at the perched dragon: vanilla sets an arrow alight and lets it do nothing while the dragon sits.
        if (dragon == null || !player.isOnGround() || NativeDragonAction.perched(dragon)) return Optional.empty();
        double away = player.distanceTo(dragon);
        if (away < 12 || away > 48) return Optional.empty();
        int bow = hotbarOf(player, Items.BOW);
        if (bow < 0) return readyInHotbar(body, Items.BOW, "Ready the bow");
        // At the head, which takes the arrow in full; any other part takes a quarter of it and one.
        Vec3d at = NativeShootAction.aimPoint(dragon);
        if (!clearShot(world, player.getPos(), at)) return Optional.empty();
        return Optional.of(new PortalRunCampaign.Task("native_shoot", Map.of("slot", String.valueOf(bow), "target", dragon.getUuid().toString()),
                "Shoot the dragon"));
    }

    /** Home through the open exit portal on the fountain. */
    private Optional<PortalRunCampaign.Task> exit(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        var portal = exitPortal(world);
        if (portal.isEmpty()) return Optional.empty();
        BlockPos in = portal.get();
        if (player.getPos().distanceTo(Vec3d.ofCenter(in)) <= 3.5) {
            return Optional.of(new PortalRunCampaign.Task("native_step_in", Map.of("x", String.valueOf(in.getX()), "y", String.valueOf(in.getY()),
                    "z", String.valueOf(in.getZ())), "Into the exit portal"));
        }
        // The fountain's bedrock rim, beside the portal.
        for (int r = 3; r <= 5; r++) {
            for (var direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                BlockPos rim = in.offset(direction, r);
                if (world.getBlockState(rim.down()).isSideSolidFullSquare(world, rim.down(), net.minecraft.util.math.Direction.UP)
                        && world.getBlockState(rim).isAir() && world.getBlockState(rim.up()).isAir()) {
                    return Optional.of(PortalRunCampaign.go(rim, 1.0, true, "Up to the exit portal"));
                }
            }
        }
        return Optional.of(PortalRunCampaign.go(in.up(), 3.0, true, "Up to the exit portal"));
    }

    /**
     * The nearest frame of the ring without an eye: an eye into it from within reach, else up to it. With every frame it
     * has seen filled and the ring not yet whole, round the ring to see the rest.
     */
    private Optional<PortalRunCampaign.Task> fillFrame(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        var frames = frames(world);
        var empty = frames.stream().filter(f -> !filled(world, f))
                .min(java.util.Comparator.comparingDouble(f -> f.getSquaredDistance(player.getBlockPos())));
        if (empty.isEmpty()) {
            var first = leg.endPortal().orElse(null);
            if (first == null) return Optional.empty();
            // Round the ring: the far side of the first frame, where the rest of it is in view.
            BlockPos across = first.add(first.getX() > player.getBlockX() ? 4 : -4, 1, first.getZ() > player.getBlockZ() ? 4 : -4);
            return Optional.of(PortalRunCampaign.go(across, 2.5, true, "Round the portal's ring to see the rest of it"));
        }
        BlockPos frame = empty.get();
        int slot = -1;
        var inventory = player.getInventory();
        for (int i = 0; i < 36 && slot < 0; i++) if (inventory.getStack(i).isOf(Items.ENDER_EYE)) slot = i;
        if (slot < 0) return Optional.empty();
        if (slot >= 9) return Optional.of(campaign.swapToHotbar(body, slot, "Ready an eye of ender"));
        Vec3d top = new Vec3d(frame.getX() + 0.5, frame.getY() + 0.8125, frame.getZ() + 0.5);
        if (player.getEyePos().distanceTo(top) <= 4.2) {
            return Optional.of(new PortalRunCampaign.Task("native_fill_frame", Map.of("slot", String.valueOf(slot), "x", String.valueOf(frame.getX()),
                    "y", String.valueOf(frame.getY()), "z", String.valueOf(frame.getZ())), "Put an eye into the frame at " + frame.toShortString()));
        }
        return Optional.of(PortalRunCampaign.go(frame.up(), 2.5, true, "Up to the frame at " + frame.toShortString()));
    }

    /** Into the open portal: onto the ring beside it, then the step in. */
    private Optional<PortalRunCampaign.Task> enterPortal(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (inEnd(player)) return Optional.empty();
        var portal = portal(world, frames(world));
        if (portal.isEmpty()) return Optional.empty();
        BlockPos in = portal.get();
        if (player.getPos().distanceTo(Vec3d.ofCenter(in)) <= 3.5) {
            return Optional.of(new PortalRunCampaign.Task("native_step_in", Map.of("x", String.valueOf(in.getX()), "y", String.valueOf(in.getY()),
                    "z", String.valueOf(in.getZ())), "Step into the End portal"));
        }
        // Onto the nearest frame of the ring, from where the portal is a step away.
        var frame = frames(world).stream().min(java.util.Comparator.comparingDouble(f -> f.getSquaredDistance(in)));
        return Optional.of(PortalRunCampaign.go(frame.map(BlockPos::up).orElse(in), 2.0, true, "Up to the End portal"));
    }
}
