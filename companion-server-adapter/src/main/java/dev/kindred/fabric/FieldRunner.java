package dev.kindred.fabric;

import dev.kindred.core.construction.Pos;
import dev.kindred.core.survival.FieldWork;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A field made ready and sown for one player (M-06a's farm ward): the {@link FieldWork} plan's steps turned into the
 * runtime's own skills, its water poured from a bucket it holds, its soil tilled with its own hoe and sown with its own
 * seeds, each from where it stands in reach. What the field holds is read from the world each pass.
 */
final class FieldRunner {
    /** What it sows: wheat seeds, as grass gives them. */
    private static final Item SEED = Items.WHEAT_SEEDS;

    final FieldWork work;
    final BlockPos corner;
    private final int size;
    private String progress = "Starting";
    private String outcome;
    private boolean done;

    /** The field {@code size} cells square, its soil's north-west cell {@code corner}. */
    FieldRunner(BlockPos corner, int size) {
        this.corner = corner.toImmutable();
        this.size = size;
        this.work = new FieldWork(new Pos(corner.getX(), corner.getY(), corner.getZ()), size);
    }

    String progress() { return progress; }

    boolean done() { return done; }

    /** How it ended, in words, once done. */
    String outcome() { return outcome; }

    Optional<PortalRunCampaign.Task> next(NativePlayerBody body) {
        if (done) return Optional.empty();
        var player = body.entity();
        var step = work.next(observe(player, player.getServerWorld()));
        return switch (step) {
            case FieldWork.Step.Go go -> {
                progress = go.why();
                yield Optional.of(new PortalRunCampaign.Task("native_goto", Map.of("x", str(go.target().x()), "y", str(go.target().y()),
                        "z", str(go.target().z()), "range", String.valueOf(go.range())), go.why()));
            }
            case FieldWork.Step.Pour pour -> {
                progress = pour.why();
                yield Optional.of(new PortalRunCampaign.Task("native_bucket", Map.of("x", str(pour.at().x()), "y", str(pour.at().y()),
                        "z", str(pour.at().z()), "mode", "pour"), pour.why()));
            }
            case FieldWork.Step.Till till -> {
                progress = till.why();
                int slot = hotbar(player, stack -> stack.getItem() instanceof HoeItem);
                if (slot < 0) yield bring(player, stack -> stack.getItem() instanceof HoeItem, "Take the hoe in hand");
                yield Optional.of(new PortalRunCampaign.Task("native_till", Map.of("x", str(till.at().x()), "y", str(till.at().y()),
                        "z", str(till.at().z()), "slot", str(slot), "roam", "true"), till.why()));
            }
            case FieldWork.Step.Sow sow -> {
                progress = sow.why();
                int slot = hotbar(player, stack -> stack.isOf(SEED));
                if (slot < 0) yield bring(player, stack -> stack.isOf(SEED), "Take the seeds in hand");
                yield Optional.of(new PortalRunCampaign.Task("native_plant", Map.of("x", str(sow.at().x()), "y", str(sow.at().y()),
                        "z", str(sow.at().z()), "slot", str(slot), "roam", "true"), sow.why()));
            }
            case FieldWork.Step.Wait wait -> {
                progress = wait.why();
                yield Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), wait.why()));
            }
            case FieldWork.Step.Done d -> {
                done = true;
                outcome = d.outcome();
                progress = d.outcome();
                yield Optional.empty();
            }
        };
    }

    /** A step of its own finished; one that did not come off counts against the cell it was for. */
    void finished(PortalRunCampaign.Task task, boolean completed) {
        if (!completed && !"native_swap".equals(task.skill()) && !"native_wait".equals(task.skill())) work.failed();
    }

    private FieldWork.Observation observe(ServerPlayerEntity player, ServerWorld world) {
        var soil = new HashMap<Pos, FieldWork.Soil>();
        for (var cell : work.cells()) {
            BlockPos at = pos(cell);
            var state = world.getBlockState(at);
            var above = world.getBlockState(at.up());
            FieldWork.Soil kind;
            if (state.isOf(Blocks.FARMLAND)) {
                kind = above.getBlock() instanceof CropBlock ? FieldWork.Soil.SOWN : above.isAir() ? FieldWork.Soil.FARMLAND : FieldWork.Soil.OTHER;
            } else if ((state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK)) && (above.isAir() || above.isReplaceable() && above.getFluidState().isEmpty())) {
                kind = FieldWork.Soil.DRY;
            } else {
                kind = FieldWork.Soil.OTHER;
            }
            soil.put(cell, kind);
        }
        var wet = new HashSet<Pos>();
        for (var cell : work.channel()) if (world.getFluidState(pos(cell)).isIn(FluidTags.WATER)) wet.add(cell);
        BlockPos feet = BlockPos.ofFloored(player.getX(), player.getY() + 0.2, player.getZ());
        var inventory = player.getInventory();
        boolean hoe = false;
        int seeds = 0, water = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() instanceof HoeItem) hoe = true;
            if (stack.isOf(SEED)) seeds += stack.getCount();
            if (stack.isOf(Items.WATER_BUCKET)) water += stack.getCount();
        }
        var footing = new HashSet<Pos>();
        for (var at : work.around()) if (standable(world, pos(at))) footing.add(at);
        return new FieldWork.Observation(new Pos(feet.getX(), feet.getY(), feet.getZ()), soil, wet, hoe, seeds, water, footing, world.getTime());
    }

    /** Ground with a full top under {@code feet}, and room for a player's body above it. */
    private static boolean standable(ServerWorld world, BlockPos feet) {
        BlockPos below = feet.down();
        return world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() && world.getFluidState(feet).isEmpty()
                && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty();
    }

    /** The hotbar slot holding what {@code wanted} accepts, the one in hand first; -1 if none. */
    private static int hotbar(ServerPlayerEntity player, Predicate<ItemStack> wanted) {
        var inventory = player.getInventory();
        if (wanted.test(inventory.getStack(inventory.selectedSlot))) return inventory.selectedSlot;
        for (int slot = 0; slot < 9; slot++) if (wanted.test(inventory.getStack(slot))) return slot;
        return -1;
    }

    /** What {@code wanted} accepts brought from the pack into the hotbar. */
    private Optional<PortalRunCampaign.Task> bring(ServerPlayerEntity player, Predicate<ItemStack> wanted, String why) {
        var inventory = player.getInventory();
        for (int slot = 9; slot < 36; slot++) {
            if (wanted.test(inventory.getStack(slot))) {
                return Optional.of(new PortalRunCampaign.Task("native_swap", Map.of("source", str(slot), "target", "8"), why));
            }
        }
        done = true;
        outcome = "Nothing at hand for it: " + why.toLowerCase(java.util.Locale.ROOT);
        progress = outcome;
        return Optional.empty();
    }

    private static BlockPos pos(Pos p) { return new BlockPos(p.x(), p.y(), p.z()); }

    private static String str(int value) { return String.valueOf(value); }
}
