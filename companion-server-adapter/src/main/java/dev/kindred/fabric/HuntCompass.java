package dev.kindred.fabric;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.UUID;

/**
 * The hunters' tracking compass (M-02a, D6): the one item a manhunt hands out, by a rule announced at its start and the
 * same for every hunter, human or AI. Each hunter holds one while the manhunt runs. It points at the nearest runner in the
 * hunter's world, or where a runner last left it, and spins when there is neither. It carries the curse of vanishing, so
 * a hunter's death takes it with it rather than leaving it to be picked up, and a new one is handed to the hunter on its
 * feet again. When the manhunt ends, every one is taken back. An AI hunter reads it as a player reads a compass: the
 * needle's bearing, never the place itself.
 */
final class HuntCompass {
    private static final String KEY = "kindred_hunt";

    private HuntCompass() { }

    /** A compass for the hunters of {@code match}, pointing nowhere yet. */
    static ItemStack make(ServerPlayerEntity hunter, UUID match) {
        var stack = new ItemStack(Items.COMPASS);
        var tag = new NbtCompound();
        tag.putUuid(KEY, match);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Tracking compass").formatted(Formatting.RED));
        stack.set(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTrackerComponent(Optional.empty(), false));
        hunter.getServerWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.VANISHING_CURSE)
                .ifPresent(curse -> stack.addEnchantment(curse, 1));
        return stack;
    }

    /** Whether {@code stack} is a tracking compass of {@code match}. */
    static boolean of(ItemStack stack, UUID match) {
        if (!stack.isOf(Items.COMPASS)) return false;
        var data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) return false;
        var tag = data.copyNbt();
        return tag.containsUuid(KEY) && tag.getUuid(KEY).equals(match);
    }

    /**
     * Each of the hunter's compasses of {@code match} set to point at {@code target} (none: it spins); one handed to it
     * if it holds none. The number handed, for the log.
     */
    static int point(ServerPlayerEntity hunter, UUID match, Optional<GlobalPos> target) {
        var inventory = hunter.getInventory();
        boolean held = false;
        var tracker = new LodestoneTrackerComponent(target, false);
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (!of(stack, match)) continue;
            held = true;
            if (!tracker.equals(stack.get(DataComponentTypes.LODESTONE_TRACKER))) stack.set(DataComponentTypes.LODESTONE_TRACKER, tracker);
        }
        if (held || !hunter.isAlive()) return 0;
        var stack = make(hunter, match);
        stack.set(DataComponentTypes.LODESTONE_TRACKER, tracker);
        return hunter.getInventory().insertStack(stack) ? 1 : 0;
    }

    /** Every compass of {@code match} taken back from {@code player}: the rule that handed them is over. */
    static int takeBack(ServerPlayerEntity player, UUID match) {
        int taken = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (of(inventory.getStack(i), match)) {
                inventory.setStack(i, ItemStack.EMPTY);
                taken++;
            }
        }
        if (of(player.currentScreenHandler.getCursorStack(), match)) {
            player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
            taken++;
        }
        return taken;
    }

    /**
     * The needle's bearing, as a player reads it off a compass it holds: the yaw toward where it points in the hunter's
     * own world, or nothing when it spins (no target, or one in another world).
     */
    static Optional<Float> bearing(ServerPlayerEntity hunter, UUID match) {
        var inventory = hunter.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (!of(stack, match)) continue;
            var tracker = stack.get(DataComponentTypes.LODESTONE_TRACKER);
            if (tracker == null || tracker.target().isEmpty()) return Optional.empty();
            GlobalPos target = tracker.target().get();
            if (target.dimension() != hunter.getWorld().getRegistryKey()) return Optional.empty();
            Vec3d at = Vec3d.ofCenter(target.pos());
            double dx = at.x - hunter.getX(), dz = at.z - hunter.getZ();
            if (dx * dx + dz * dz < 1) return Optional.empty();
            return Optional.of(MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz))));
        }
        return Optional.empty();
    }

    /** A spot {@code distance} blocks along {@code yaw} from {@code from}, at its height: where following the needle leads. */
    static BlockPos along(BlockPos from, float yaw, int distance) {
        double radians = Math.toRadians(yaw);
        return from.add((int) Math.round(-Math.sin(radians) * distance), 0, (int) Math.round(Math.cos(radians) * distance));
    }
}
