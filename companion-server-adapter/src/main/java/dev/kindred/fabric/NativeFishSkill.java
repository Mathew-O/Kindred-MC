package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player fishing with its own rod, as a human does: it casts onto the water, waits for
 * a bite and reels in, and the catch flies to it like any drop. If nothing bites in time, it reels in and
 * says so.
 *
 * Technical: Fishes at the water at {@code water} with the rod in hotbar slot {@code slot}, as a player
 * fishes (E-05): casts toward the water, watches the bobber, and reels in the moment a fish is on (the
 * bobber's dip; vanilla's hook countdown), when the catch flies to it as any drop; then ends. A cast that
 * lands off the water is reeled back and cast again, twice at most; nothing biting in its time, it reels in
 * and says so. The rod wears as vanilla wears it.
 */
final class NativeFishSkill implements SkillBody {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final int slot;
    private final BlockPos water;
    private final int maxTicks;
    private int ticks, casts, sinceCast;
    private boolean reeled;

    /** Fishing at the given water with the rod in a hotbar slot, for at most the given seconds. */
    NativeFishSkill(NativePlayerBody body, Supplier<ActionLease> lease, int slot, BlockPos water, int seconds) {
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A rod is used from hotbar slot 0..8");
        if (seconds < 5 || seconds > 120) throw new IllegalArgumentException("A fishing lasts 5..120 seconds");
        this.body = body;
        this.lease = lease;
        this.slot = slot;
        this.water = water.toImmutable();
        this.maxTicks = seconds * 20;
    }

    private Vec3d surface() {
        return new Vec3d(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5);
    }

    /** Moves the fishing on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        var player = body.entity();
        ticks++;
        var hook = player.fishHook;
        if (reeled) {
            // A tick for the catch to be pulled out.
            return Step.completed(Map.of("caught", "true"));
        }
        if (hook == null) {
            if (casts >= 3) return Step.blocked("It could not cast onto the water", true);
            if (!body.useRod(lease.get(), slot, surface())) return Step.blocked("No rod in hand to fish with", true);
            casts++;
            sinceCast = 0;
            return Step.running(Map.of("cast", String.valueOf(casts)), false);
        }
        sinceCast++;
        // Landed off the water, or caught on something: reeled back to cast again. The bobber is on the water, as the game
        // reckons it, when the water is in its block, or in the one under it as it bobs up out of it.
        var world = player.getServerWorld();
        boolean onWater = world.getFluidState(hook.getBlockPos()).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                || world.getFluidState(hook.getBlockPos().down()).isIn(net.minecraft.registry.tag.FluidTags.WATER);
        if (sinceCast > 60 && !onWater || hook.getHookedEntity() != null) {
            body.useRod(lease.get(), slot, null);
            return Step.running(Map.of("recast", String.valueOf(casts)), false);
        }
        if (((dev.kindred.fabric.mixin.FishingBobberAccessor) hook).kindred$hookCountdown() > 0) {
            body.useRod(lease.get(), slot, null);
            reeled = true;
            return Step.running(Map.of("reeled", "true"), false);
        }
        if (ticks >= maxTicks) {
            body.useRod(lease.get(), slot, null);
            return Step.blocked("Nothing bit", true);
        }
        return Step.running(Map.of("waiting", String.valueOf(ticks / 20)), false);
    }

    /** Stopped with its line out: the line reeled in, as a player puts the rod away. */
    @Override public void halt() {
        if (body.entity().fishHook != null) body.useRod(lease.get(), slot, null);
    }

    @Override public boolean settled() { return true; }
}
