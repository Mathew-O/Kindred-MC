package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.survival.EndFightPlanner;
import dev.kindred.core.survival.EndFightSnapshot;
import dev.kindred.core.survival.PortalRunSnapshot;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
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
        int blocks = player.getInventory().count(Items.COBBLESTONE) + player.getInventory().count(Items.COBBLED_DEEPSLATE)
                + player.getInventory().count(Items.NETHERRACK) + player.getInventory().count(Items.END_STONE);
        return new EndFightSnapshot(inEnd(player), dragonKilled, frames.size(), filled, eyes, open,
                false, 0, 0, 0, true, false, false, false,
                player.getInventory().count(Items.BOW) > 0, arrows, blocks, p.swordTier(), player.getHealth());
    }

    /** The next step toward beating the dragon, or empty when nothing can be done from here now. */
    Optional<PortalRunCampaign.Task> next(NativePlayerBody body, PortalRunSnapshot p, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        var s = snapshot(player, p);
        stage = EndFightPlanner.stage(s);
        for (var step : EndFightPlanner.plan(s)) {
            Optional<PortalRunCampaign.Task> task = realize(step, s, body);
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

    private Optional<PortalRunCampaign.Task> realize(EndFightPlanner.Step step, EndFightSnapshot s, NativePlayerBody body) {
        return switch (step.action()) {
            case FILL_FRAME -> fillFrame(body);
            case ENTER_PORTAL -> enterPortal(body);
            default -> Optional.empty();
        };
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
