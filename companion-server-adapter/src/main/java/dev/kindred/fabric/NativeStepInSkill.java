package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The last step into a portal (E-39a): from the frame ring of an End portal, or beside one, straight into the portal's
 * own block, as a player steps or jumps in. A walk's route never goes there, since an End portal's blocks have no
 * floor under them; this step goes nowhere else: it is refused unless the target is a portal block a few blocks off,
 * and it ends when the player has gone through or after a few seconds.
 */
final class NativeStepInSkill implements SkillBody {
    private static final int MAX_TICKS = 100;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final BlockPos target;
    private final String world;
    private int ticks;

    NativeStepInSkill(NativePlayerBody body, Supplier<ActionLease> lease, BlockPos target) {
        this.body = body;
        this.lease = lease;
        this.target = target.toImmutable();
        var player = body.entity();
        var state = player.getServerWorld().getBlockState(target);
        if (!state.isOf(Blocks.END_PORTAL) && !state.isOf(Blocks.NETHER_PORTAL)) throw new IllegalArgumentException("Not a portal block");
        if (player.getPos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(target)) > 4.5) throw new IllegalArgumentException("The portal is out of a step's reach");
        this.world = player.getWorld().getRegistryKey().getValue().toString();
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        var player = body.entity();
        if (!player.getWorld().getRegistryKey().getValue().toString().equals(world) || player.isRemoved()) {
            body.stopInputs();
            return Step.completed(Map.of("through", "true"));
        }
        if (++ticks > MAX_TICKS) {
            body.stopInputs();
            return Step.blocked("Did not go through the portal", true);
        }
        double dx = target.getX() + 0.5 - player.getX(), dz = target.getZ() + 0.5 - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        boolean close = Math.hypot(dx, dz) < 0.3;
        // Onto the frame ring with a jump where it stands higher than a step; once over the portal, stand and fall in.
        boolean jump = player.isOnGround() && player.horizontalCollision;
        if (!body.move(lease.get(), close ? 0 : 0.6f, 0, jump, false, yaw)) {
            body.stopInputs();
            return Step.blocked("Movement authority denied", true);
        }
        return Step.running(Map.of("distance", String.format(java.util.Locale.ROOT, "%.1f", Math.hypot(dx, dz))), true);
    }

    @Override public void halt() { body.stopInputs(); }
    @Override public boolean settled() { return true; }
}
