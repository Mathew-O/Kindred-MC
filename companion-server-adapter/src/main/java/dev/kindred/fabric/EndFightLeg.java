package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.tactics.Ballistics;
import dev.kindred.core.survival.EndFightPlanner;
import dev.kindred.core.survival.EndFightSnapshot;
import dev.kindred.core.survival.PortalRunSnapshot;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
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
    private final Set<Long> badStands = new HashSet<>();
    private final Set<UUID> givenUp = new HashSet<>();
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
        if (!inEnd(player)) {
            return new EndFightSnapshot(false, dragonKilled, frames.size(), filled, eyes, open,
                    false, 0, 0, 0, true, false, false, false, bow, arrows, blocks, p.swordTier(), player.getHealth());
        }
        var crystals = crystals(world);
        int caged = (int) crystals.stream().filter(c -> caged(world, c)).count();
        int shootable = (int) crystals.stream().filter(c -> !caged(world, c) && !givenUp.contains(c.getUuid())).count();
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
                bow, arrows, blocks, p.swordTier(), player.getHealth());
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

    /** Iron bars about the crystal: its cage, while any stands on the side the player last opened it from or any side. */
    private boolean caged(ServerWorld world, EndCrystalEntity crystal) {
        if (opened.contains(crystal.getUuid())) return false;
        return !bars(world, crystal).isEmpty();
    }

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
        // Up its own pillar by a cage: the bars, then down again, before anything that walks (staged dragon run 2, a
        // block off the pillar's middle, was about to walk off it twenty blocks up).
        if (s.inEnd() && !s.inBreath() && onTower(player)) {
            Optional<PortalRunCampaign.Task> tower = caging != null ? openCage(body) : Optional.empty();
            if (tower.isEmpty()) tower = Optional.of(down(player));
            progress = "Dragon: " + label(stage) + ": up beside a cage - " + tower.get().label();
            return tower;
        }
        for (var step : EndFightPlanner.plan(s)) {
            Optional<PortalRunCampaign.Task> task = realize(step, s, body, policy);
            if (task.isPresent()) {
                progress = "Dragon: " + label(stage) + ": " + step.why() + " - " + task.get().label();
                return task;
            }
        }
        progress = stage == EndFightPlanner.Stage.DONE ? "Dragon: beaten" : "Dragon: " + label(stage) + ": waiting for a feasible step";
        return Optional.empty();
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

    /**
     * Whether an arrow loosed from {@code stand} (where the feet are) reaches {@code target} on its low arc with nothing in
     * the way: the arc followed tick by tick, each step checked against the blocks.
     */
    private static boolean clearShot(ServerWorld world, Vec3d stand, Vec3d target) {
        Vec3d start = stand.add(0, 1.62 - 0.1, 0);
        double dx = target.x - start.x, dz = target.z - start.z, distance = Math.hypot(dx, dz);
        if (distance < 1) return false;
        var shot = Ballistics.aim(distance, target.y - start.y, Ballistics.FULL_DRAW);
        if (shot.isEmpty()) return false;
        var arc = Ballistics.trajectory(shot.get().pitch(), Ballistics.FULL_DRAW, distance);
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
    private boolean unusable(BlockPos stand) {
        return badStands.contains(stand.asLong()) || campaign.unreachable(stand) || campaign.blocked(stand);
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
        for (double range = NEAREST_SHOT + 4; range <= FARTHEST_SHOT; range += 8) {
            for (int a = 0; a < 24; a++) {
                double angle = Math.toRadians(a * 15);
                int x = (int) Math.floor(crystal.getX() + Math.cos(angle) * range), z = (int) Math.floor(crystal.getZ() + Math.sin(angle) * range);
                var top = islandTop(world, x, z);
                if (top.isEmpty() || unusable(top.get()) || Math.hypot(x, z) > 80 || Math.hypot(x, z) < PERCH_CLEAR) continue;
                // Not by the island's edge, where a charging dragon knocks the player into the void.
                if (!islandTop(world, x + 4, z).isPresent() && !islandTop(world, x - 4, z).isPresent()) continue;
                Vec3d feet = Vec3d.ofBottomCenter(top.get());
                if (!clearShot(world, feet, target)) continue;
                double distance = feet.distanceTo(player.getPos());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = top.get();
                }
            }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    /** The nearest crystal it can shoot: from where it stands if the arc is clear, else from a spot with a clear arc. */
    private Optional<PortalRunCampaign.Task> shootCrystal(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        // The crystal it set out for first, until it is down or given up: the nearest changes on the walk between two
        // (staged dragon run 2 turned back and forth between two pillars for a minute).
        var targets = crystals(world).stream().filter(c -> !caged(world, c) && !givenUp.contains(c.getUuid()))
                .sorted(java.util.Comparator.<EndCrystalEntity>comparingInt(c -> c.getUuid().equals(target) ? 0 : 1)
                        .thenComparingDouble(player::squaredDistanceTo)).toList();
        for (EndCrystalEntity crystal : targets) {
            UUID id = crystal.getUuid();
            target = id;
            int bow = hotbarOf(player, Items.BOW);
            if (bow < 0) return readyInHotbar(body, Items.BOW, "Ready the bow");
            double away = player.getPos().distanceTo(crystal.getPos());
            boolean here = player.isOnGround() && away >= NEAREST_SHOT && away <= FARTHEST_SHOT
                    && clearShot(world, player.getPos(), crystal.getBoundingBox().getCenter());
            BlockPos stand = stands.get(id);
            if (!here && (stand == null || unusable(stand))) {
                stand = standFor(player, crystal).orElse(null);
                if (stand == null) {
                    givenUp.add(id);
                    KindredMod.LOGGER.info("{} finds no spot with a clear shot at the crystal at {}", player.getName().getString(), crystal.getBlockPos().toShortString());
                    continue;
                }
                stands.put(id, stand);
            }
            if (here || stand != null && player.getBlockPos().isWithinDistance(stand, 1.5) && player.isOnGround()) {
                int shots = shotsAt.merge(id, 1, Integer::sum);
                if (shots % SHOTS_PER_STAND == 0 && stand != null) badStands.add(stand.asLong());
                if (shots >= SHOTS_PER_CRYSTAL) givenUp.add(id);
                return Optional.of(new PortalRunCampaign.Task("native_shoot", Map.of("slot", String.valueOf(bow), "target", id.toString()),
                        "Shoot the crystal on the pillar at " + crystal.getBlockPos().toShortString()));
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
        var cages = crystals(world).stream().filter(c -> caged(world, c) && !givenUp.contains(c.getUuid()))
                .sorted(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).toList();
        // Coming down from a cage already opened: a block at a time, down to the ground it started from.
        if (caging == null && onTower(player)) return Optional.of(down(player));
        if (cages.isEmpty()) {
            caging = null;
            return onTower(player) ? Optional.of(down(player)) : Optional.empty();
        }
        EndCrystalEntity crystal = cages.stream().filter(c -> c.getUuid().equals(caging)).findFirst().orElse(cages.getFirst());
        caging = crystal.getUuid();
        BlockPos center = crystal.getBlockPos();
        if (towerBase == null || towerBase.getSquaredDistance(center.getX(), towerBase.getY(), center.getZ()) > 6 * 6) {
            towerBase = towerFoot(world, player, crystal).orElse(null);
            if (towerBase == null) {
                givenUp.add(caging);
                caging = null;
                return Optional.empty();
            }
        }
        boolean atFoot = player.getBlockY() == towerBase.getY() && Math.hypot(player.getX() - (towerBase.getX() + 0.5), player.getZ() - (towerBase.getZ() + 0.5)) < 0.6;
        if (!atFoot && !onTower(player)) {
            if (player.getBlockY() > towerBase.getY() + 1) return Optional.empty(); // On some other height: not from here.
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
            return Optional.of(new PortalRunCampaign.Task("native_break", Map.of("x", String.valueOf(bar.getX()), "y", String.valueOf(bar.getY()),
                    "z", String.valueOf(bar.getZ())), "Break the cage's bars at " + bar.toShortString()));
        }
        opened.add(caging);
        KindredMod.LOGGER.info("{} opened the cage of the crystal at {}", player.getName().getString(), center.toShortString());
        caging = null;
        return Optional.of(down(player));
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

    /** How far out from the exit portal's pillar the player waits: clear of the perch, where the dragon lands and breathes. */
    static final int HOLD_OUT = 18, REST_OUT = 28;

    /**
     * Hurt in the End: out of the dragon's breath, then out from the exit portal's pillar where the dragon lands and
     * breathes on whoever is near, and there rest (staged dragon run 1 rested beside the pillar and was breathed on).
     */
    Optional<PortalRunCampaign.Task> rest(NativePlayerBody body) {
        var out = leaveBreath(body);
        if (out.isPresent()) return out;
        var player = body.entity();
        double d = Math.hypot(player.getX(), player.getZ());
        if (d < REST_OUT - 4) {
            double ux = d < 1 ? 0 : player.getX() / d, uz = d < 1 ? 1 : player.getZ() / d;
            for (int r = REST_OUT; r <= REST_OUT + 16; r += 4) {
                var spot = islandTop(player.getServerWorld(), (int) Math.floor(ux * r), (int) Math.floor(uz * r));
                if (spot.isPresent()) return Optional.of(PortalRunCampaign.go(spot.get(), 2.0, true, "Away from the dragon's perch to heal"));
            }
        }
        return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "2"), "Rest to heal"));
    }

    /** Out from the exit portal's pillar, away from the edge, while the dragon flies or the crystals left are looked for. */
    private Optional<PortalRunCampaign.Task> holdGround(NativePlayerBody body) {
        var player = body.entity();
        if (player.getHealth() < EndFightPlanner.LOW_HEALTH) return rest(body);
        var world = player.getServerWorld();
        Optional<BlockPos> spot = Optional.empty();
        for (int r = HOLD_OUT; r <= HOLD_OUT + 10 && spot.isEmpty(); r += 2) spot = islandTop(world, 0, r);
        if (spot.isEmpty()) return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Wait"));
        if (player.getBlockPos().isWithinDistance(spot.get(), 6)) return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Wait for the dragon to perch"));
        return Optional.of(PortalRunCampaign.go(spot.get(), 2.0, true, "To the exit portal's pillar"));
    }

    /** Out of the dragon's breath, ahead of anything else the campaign would do. */
    Optional<PortalRunCampaign.Task> outOfBreath(NativePlayerBody body) {
        return leaveBreath(body);
    }

    /** Out of the dragon's breath, on the island, away from the cloud. */
    private Optional<PortalRunCampaign.Task> leaveBreath(NativePlayerBody body) {
        var player = body.entity();
        var cloud = NativeDragonAction.breath(player, 1.0);
        if (cloud == null) return Optional.empty();
        double dx = player.getX() - cloud.getX(), dz = player.getZ() - cloud.getZ(), d = Math.hypot(dx, dz);
        // In the middle of the cloud there is no "away": toward the island's middle, away from the void.
        if (d < 1) {
            dx = -player.getX();
            dz = -player.getZ();
            d = Math.max(1, Math.hypot(dx, dz));
        }
        double reach = cloud.getRadius() + 4, base = Math.atan2(dz / d, dx / d);
        for (double turn : new double[] {0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2}) {
            double angle = base + turn;
            var spot = islandTop(player.getServerWorld(), (int) Math.floor(cloud.getX() + Math.cos(angle) * reach),
                    (int) Math.floor(cloud.getZ() + Math.sin(angle) * reach));
            if (spot.isPresent() && !player.getBlockPos().isWithinDistance(spot.get(), 2)) {
                return Optional.of(PortalRunCampaign.go(spot.get(), 1.0, true, "Out of the dragon's breath"));
            }
        }
        return Optional.empty();
    }

    /** The perched dragon: in under its head and strike it, in one continuous fight while it sits. */
    private Optional<PortalRunCampaign.Task> strikeDragon(NativePlayerBody body) {
        var player = body.entity();
        var dragon = dragon(player.getServerWorld());
        if (dragon == null || !NativeDragonAction.perched(dragon)) return Optional.empty();
        var head = NativeDragonAction.part(dragon, "head");
        Vec3d at = head == null ? dragon.getPos() : head.getPos();
        int sword = -1;
        float best = 0;
        for (int slot = 0; slot < 9; slot++) {
            var stack = player.getInventory().getStack(slot);
            if (!stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) && !stack.isIn(net.minecraft.registry.tag.ItemTags.AXES)) continue;
            float score = PortalRunCampaign.tier(stack.getItem()) + (stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) ? 0.5f : 0);
            if (score > best) { best = score; sword = slot; }
        }
        if (Math.hypot(at.x - player.getX(), at.z - player.getZ()) <= 7) {
            var args = new HashMap<String, String>(Map.of("target", dragon.getUuid().toString()));
            if (sword >= 0) args.put("slot", String.valueOf(sword));
            return Optional.of(new PortalRunCampaign.Task("native_dragon", Map.copyOf(args), "Strike the dragon's head"));
        }
        BlockPos under = BlockPos.ofFloored(at.x, player.getY(), at.z);
        return Optional.of(PortalRunCampaign.go(under, 3.0, true, "In under the perched dragon"));
    }

    /** The dragon in flight, in range with a clear arc: an arrow at it. */
    private Optional<PortalRunCampaign.Task> shootDragon(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        var dragon = dragon(world);
        if (dragon == null || NativeDragonAction.perched(dragon) || !player.isOnGround()) return Optional.empty();
        double away = player.distanceTo(dragon);
        if (away < 12 || away > 48) return Optional.empty();
        int bow = hotbarOf(player, Items.BOW);
        if (bow < 0) return readyInHotbar(body, Items.BOW, "Ready the bow");
        Vec3d at = dragon.getBoundingBox().getCenter();
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
