package dev.kindred.fabric;

import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In plain terms: a player that wants seeds for its chickens breaks the grass about it, as players do, and picks up
 * the seeds that drop (about one in eight), until it has enough or no grass is left in sight.
 *
 * Technical: Seeds earned from grass (M-01a, the ranch's food earned in play). Each step is one task through the same
 * skills as any work: a walk to the nearest grass in sight, or a break of it within reach, its drops collected. Only
 * grass the player can see from its eyes is used; a tuft it could not break three times is passed over.
 */
final class SeedRunner {
    /** How far it looks for grass, and how far it reaches to break it. */
    static final int LOOK = 16;
    private static final double REACH = 4.0;
    /** Breaks at most, before it gives up: a few dozen tufts give a pair of seeds and more. */
    private static final int MOST = 96;

    private final int wanted;
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    private int breaks;
    private String outcome = "";

    SeedRunner(int wanted) {
        this.wanted = wanted;
    }

    String outcome() { return outcome; }

    String progress(ServerPlayerEntity player) {
        return "Seeds from the grass: " + player.getInventory().count(Items.WHEAT_SEEDS) + " of " + wanted;
    }

    /** The next step, or none when it is done: enough seeds, or no grass left to break. */
    Optional<PortalRunCampaign.Task> next(ServerPlayerEntity player) {
        if (player.getInventory().count(Items.WHEAT_SEEDS) >= wanted) {
            outcome = "Seeds enough for the chickens";
            return Optional.empty();
        }
        if (breaks >= MOST) {
            outcome = "Not seeds enough from the grass about";
            return Optional.empty();
        }
        var grass = nearestGrass(player, tries);
        if (grass.isEmpty()) {
            outcome = "No grass in sight to earn seeds from";
            return Optional.empty();
        }
        BlockPos at = grass.get();
        if (player.getEyePos().distanceTo(Vec3d.ofCenter(at)) <= REACH) {
            tries.merge(at, 1, Integer::sum);
            breaks++;
            return Optional.of(new PortalRunCampaign.Task("native_break",
                    Map.of("x", String.valueOf(at.getX()), "y", String.valueOf(at.getY()), "z", String.valueOf(at.getZ())), "Break grass for seeds"));
        }
        return Optional.of(PortalRunCampaign.go(at, 2.0, true, "To the grass for seeds"));
    }

    /** Whether there is grass in sight to earn seeds from. */
    static boolean grassInSight(ServerPlayerEntity player) {
        return nearestGrass(player, Map.of()).isPresent();
    }

    private static Optional<BlockPos> nearestGrass(ServerPlayerEntity player, Map<BlockPos, Integer> tries) {
        ServerWorld world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(feet.add(-LOOK, -4, -LOOK), feet.add(LOOK, 4, LOOK))) {
            var state = world.getBlockState(pos);
            if (!state.isOf(Blocks.SHORT_GRASS) && !state.isOf(Blocks.TALL_GRASS)) continue;
            // A tall tuft is broken by its lower half.
            if (state.isOf(Blocks.TALL_GRASS) && world.getBlockState(pos.down()).isOf(Blocks.TALL_GRASS)) continue;
            if (tries.getOrDefault(pos, 0) >= 3) continue;
            double distance = pos.getSquaredDistance(feet);
            if (distance >= bestDistance || !seen(player, world, pos)) continue;
            best = pos.toImmutable();
            bestDistance = distance;
        }
        return Optional.ofNullable(best);
    }

    /** Whether nothing solid stands between the player's eyes and the tuft. */
    private static boolean seen(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        var hit = world.raycast(new RaycastContext(player.getEyePos(), Vec3d.ofCenter(pos), RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit instanceof BlockHitResult block && block.getBlockPos().equals(pos);
    }
}
