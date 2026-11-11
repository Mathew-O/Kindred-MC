package dev.kindred.fabric;

import dev.kindred.core.factions.Territory;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * In plain terms: the one place every game event asks whether a faction's land allows it: a person's or an AI player's
 * break, place or use, an explosion, a flow of water or lava, a piston's push, a hopper's pull, a dispenser's shot and a
 * projectile's hit.
 *
 * Technical: M-06d's enforcement entry for the event callbacks and mixins. The runtimes playing a faction world register
 * their {@link FactionsService}; each check asks every one of the world's own server (any refusal refuses), on the server thread only (anything
 * off it, as world generation, is not a player's doing and passes). With no faction world running everything passes, so
 * the other modes are untouched.
 */
public final class TerritoryGuard {
    private static final Set<FactionsService> ACTIVE = new CopyOnWriteArraySet<>();

    private TerritoryGuard() {}

    static void activate(FactionsService service) { ACTIVE.add(service); }

    static void deactivate(FactionsService service) { ACTIVE.remove(service); }

    /** Test-only and read-only: whether {@code service} is guarding its world now. */
    static boolean activeForTest(FactionsService service) { return ACTIVE.contains(service); }

    /** The faction worlds of {@code world}'s server: usually one; every one of them must allow a change. */
    private static java.util.List<FactionsService> of(World world) {
        if (ACTIVE.isEmpty() || !(world instanceof ServerWorld server) || !server.getServer().isOnThread()) return java.util.List.of();
        var out = new java.util.ArrayList<FactionsService>(1);
        for (var service : ACTIVE) if (service.server() == server.getServer()) out.add(service);
        return out;
    }

    static Territory.Chunk chunk(World world, BlockPos pos) {
        return Territory.Chunk.at(world.getRegistryKey().getValue().toString(), pos.getX(), pos.getZ());
    }

    /** Whether {@code player} may break, place or use the block at {@code pos} (and a double chest's other half). */
    public static boolean mayChange(ServerPlayerEntity player, BlockPos pos) {
        var world = player.getServerWorld();
        BlockPos other = null;
        for (var service : of(world)) {
            if (!service.mayChange(player, chunk(world, pos))) return false;
            if (other == null) other = otherHalf(world, pos);
            if (other != null && !service.mayChange(player, chunk(world, other))) return false;
        }
        return true;
    }

    /** Why {@code player} may not change {@code pos}, or null. */
    public static String denial(ServerPlayerEntity player, BlockPos pos) {
        return mayChange(player, pos) ? null : "Another faction's land, or a safe zone";
    }

    /**
     * Why {@code attacker}'s hit on {@code target} (both players) is refused in a faction world, or null (M-06f): a safe
     * zone is absolute; elsewhere only enemy factions' members, or players a running match lets strike, fight. The
     * attacker is told why. {@code matchAllows} is the match registry's own leave.
     */
    public static String strikeRefusal(ServerPlayerEntity attacker, ServerPlayerEntity target, boolean matchAllows) {
        for (var service : of(attacker.getServerWorld())) {
            String why = service.strikeRefusal(attacker, target);
            if (why == null || matchAllows && !why.startsWith("No fighting in a safe zone")) continue;
            service.tell(attacker, why);
            return why;
        }
        return null;
    }

    /** Whether a Nether portal at {@code pos} in {@code world} would lead out of the border; the traveller told so (M-06f). */
    public static boolean portalRefused(ServerWorld world, net.minecraft.entity.Entity traveller, BlockPos pos) {
        var services = of(world);
        if (services.isEmpty() || !FactionsService.leavesBorder(world, pos)) return false;
        if (traveller instanceof ServerPlayerEntity player) services.getFirst().tell(player, "This portal would lead beyond the border");
        return true;
    }

    /** Frames, stands, paintings and minecarts that hold things: protected on claimed land as its blocks are. */
    public static boolean blockLike(net.minecraft.entity.Entity entity) {
        return entity instanceof net.minecraft.entity.decoration.AbstractDecorationEntity || entity instanceof net.minecraft.entity.decoration.ArmorStandEntity
                || entity instanceof net.minecraft.entity.vehicle.StorageMinecartEntity;
    }

    /** Whether something may pass from {@code from} into {@code to} (fluid, piston, hopper, dispenser). */
    public static boolean mayCross(World world, BlockPos from, BlockPos to) {
        if ((from.getX() >> 4) == (to.getX() >> 4) && (from.getZ() >> 4) == (to.getZ() >> 4)) return true;
        for (var service : of(world)) if (!service.mayCross(chunk(world, from), chunk(world, to))) return false;
        return true;
    }

    /**
     * Whether a hopper (or anything else) at {@code from} may reach the container at {@code container}: both halves of a
     * double chest count, so a half on the wilderness side of a border gives no way into a protected half.
     */
    public static boolean mayReachContainer(World world, BlockPos from, BlockPos container) {
        if (!mayCross(world, from, container)) return false;
        BlockPos other = otherHalf(world, container);
        return other == null || mayCross(world, from, other);
    }

    /** A double chest's other half, or null. */
    public static BlockPos otherHalf(World world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof net.minecraft.block.ChestBlock) || !state.contains(net.minecraft.block.ChestBlock.CHEST_TYPE)
                || state.get(net.minecraft.block.ChestBlock.CHEST_TYPE) == net.minecraft.block.enums.ChestType.SINGLE) return null;
        return pos.offset(net.minecraft.block.ChestBlock.getFacing(state));
    }

    /** Whether an explosion may break the block at {@code pos}: never on claimed land or in a safe zone. */
    public static boolean mayExplode(World world, BlockPos pos) {
        for (var service : of(world)) if (!service.unprotected(chunk(world, pos))) return false;
        return true;
    }

    /** Whether a projectile shot by {@code owner} (null for none) may set off the block at {@code pos}. */
    public static boolean mayHit(World world, net.minecraft.entity.Entity owner, BlockPos pos) {
        var chunk = chunk(world, pos);
        for (var service : of(world)) {
            if (service.unprotected(chunk)) continue;
            if (!(owner instanceof ServerPlayerEntity player) || !service.mayChange(player, chunk)) return false;
        }
        return true;
    }
}
