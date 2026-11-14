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
 * In plain terms: the hunters' tracking compass in a manhunt: the one item the match hands out, by a rule
 * announced at the start and the same for human and AI hunters. It points at the nearest runner, vanishes
 * when its holder dies, and is taken back from everyone when the hunt ends. An AI hunter reads only which way
 * the needle points, as a human would.
 *
 * Technical: The hunters' tracking compass (M-02a, D6): the one item a manhunt hands out, by a rule announced
 * at its start and the same for every hunter, human or AI. Each hunter holds one while the manhunt runs. It
 * points at the nearest runner in the hunter's world, or where a runner last left it, and spins when there is
 * neither. It carries the curse of vanishing, so a hunter's death takes it with it rather than leaving it to
 * be picked up, and a new one is handed to the hunter on its feet again. When the manhunt ends, every one is
 * taken back. An AI hunter reads it as a player reads a compass: the needle's bearing, never the place
 * itself.
 *
 * Issued once (M-06k2): each compass carries the id it was issued under, and the runtime keeps each hunter's one. A
 * hunter holding none is handed another only when its own is provably gone: deleted by the curse at a death (only with
 * keepInventory off), or destroyed lying on the ground (burnt, blown up, fallen out of the world, despawned). One set
 * down, stored or taken by another player is not gone, and is not replaced: the hunter goes to get it. After its hunt,
 * one is taken back wherever it next turns up in a player's inventory or ender chest. It makes nothing at a crafting
 * table or in a player's own grid, and the auction house will not list it.
 */
public final class HuntCompass {
    private static final String KEY = "kindred_hunt";
    private static final String ISSUE = "kindred_hunt_issue";
    /** The issues destroyed since the runtime last asked: server thread only, filled by the vanish and removal hooks. */
    private static final java.util.Set<UUID> GONE = new java.util.HashSet<>();

    private HuntCompass() { }

    /** A compass for {@code match} issued under {@code issue}. */
    static ItemStack make(ServerPlayerEntity hunter, UUID match, UUID issue) {
        var stack = new ItemStack(Items.COMPASS);
        var tag = new NbtCompound();
        tag.putUuid(KEY, match);
        tag.putUuid(ISSUE, issue);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Tracking compass").formatted(Formatting.RED));
        stack.set(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTrackerComponent(Optional.empty(), false));
        hunter.getServerWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.VANISHING_CURSE)
                .ifPresent(curse -> stack.addEnchantment(curse, 1));
        return stack;
    }

    /**
     * Whether {@code stack} is a tracking compass of any hunt: the match's rule hands it and takes it back, so it is
     * never the player's own to count as kept or lost.
     */
    public static boolean handed(ItemStack stack) {
        if (!stack.isOf(Items.COMPASS)) return false;
        var data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && data.contains(KEY);
    }

    /** Whether {@code stack} is a tracking compass of {@code match}. */
    static boolean of(ItemStack stack, UUID match) {
        if (!stack.isOf(Items.COMPASS)) return false;
        var data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) return false;
        var tag = data.copyNbt();
        return tag.containsUuid(KEY) && tag.getUuid(KEY).equals(match);
    }

    /** The issue a tracking compass carries, or none (one made before issues, or not a tracking compass). */
    static java.util.Optional<UUID> issue(ItemStack stack) {
        if (!handed(stack)) return java.util.Optional.empty();
        var tag = stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        return tag.containsUuid(ISSUE) ? java.util.Optional.of(tag.getUuid(ISSUE)) : java.util.Optional.empty();
    }

    /** The match a tracking compass belongs to, or none. */
    static java.util.Optional<UUID> match(ItemStack stack) {
        if (!handed(stack)) return java.util.Optional.empty();
        var tag = stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        return tag.containsUuid(KEY) ? java.util.Optional.of(tag.getUuid(KEY)) : java.util.Optional.empty();
    }

    /** Each of the hunter's compasses of {@code match} set to point at {@code target} (none: it spins). */
    static void aim(ServerPlayerEntity hunter, UUID match, Optional<GlobalPos> target) {
        var inventory = hunter.getInventory();
        var tracker = new LodestoneTrackerComponent(target, false);
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (!of(stack, match)) continue;
            if (!tracker.equals(stack.get(DataComponentTypes.LODESTONE_TRACKER))) stack.set(DataComponentTypes.LODESTONE_TRACKER, tracker);
        }
    }

    /** Whether the hunter holds the compass issued as {@code issue} (its inventory or its cursor). */
    static boolean holds(ServerPlayerEntity hunter, UUID issue) {
        var inventory = hunter.getInventory();
        for (int i = 0; i < inventory.size(); i++) if (issue(inventory.getStack(i)).filter(issue::equals).isPresent()) return true;
        return issue(hunter.currentScreenHandler.getCursorStack()).filter(issue::equals).isPresent();
    }

    /** A new compass of {@code match} issued as {@code issue}, pointing at {@code target}, into the hunter's inventory. */
    static boolean issue(ServerPlayerEntity hunter, UUID match, UUID issue, Optional<GlobalPos> target) {
        if (!hunter.isAlive()) return false;
        var stack = make(hunter, match, issue);
        stack.set(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTrackerComponent(target, false));
        return hunter.getInventory().insertStack(stack);
    }

    /** A tracking compass destroyed: deleted by the curse at a death, or a drop destroyed. Server thread. */
    public static void destroyed(ItemStack stack) {
        issue(stack).ifPresent(GONE::add);
    }

    /** Whether the compass issued as {@code issue} has been destroyed, the mark kept until the runtime forgets it. */
    static boolean gone(UUID issue) { return GONE.contains(issue); }

    /** Forgets a destroyed issue once replaced or once its hunt is over. */
    static void forget(UUID issue) { GONE.remove(issue); }

    /** Every tracking compass of a hunt in {@code over} taken from {@code player}'s inventory, cursor and ender chest. */
    static int sweep(ServerPlayerEntity player, java.util.Set<UUID> over) {
        int taken = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            var at = match(inventory.getStack(i));
            if (at.isPresent() && over.contains(at.get())) { inventory.setStack(i, ItemStack.EMPTY); taken++; }
        }
        var cursor = match(player.currentScreenHandler.getCursorStack());
        if (cursor.isPresent() && over.contains(cursor.get())) { player.currentScreenHandler.setCursorStack(ItemStack.EMPTY); taken++; }
        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.size(); i++) {
            var at = match(ender.getStack(i));
            if (at.isPresent() && over.contains(at.get())) { ender.setStack(i, ItemStack.EMPTY); taken++; }
        }
        return taken;
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
