package dev.kindred.fabric;

import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: the player taming a horse it chose, as a person does: walking up to it, getting on with an empty hand
 * again and again until it stops throwing it off, getting off, and putting on it a saddle it found, if it has one.
 *
 * Technical: A horse of its own (M-01a), a step at a time through its own skills: `native_goto` up to it, `native_ride`
 * mount until vanilla's bonding goal tames it (at most {@link #MOUNTS} tries), `native_ride` off, a saddle brought to the
 * hotbar (`native_swap`) and put on (`native_saddle`). Ends tamed (saddled or not), or with why not: the horse gone, it
 * would not bond, or no hand free to mount with.
 */
final class StableRunner {
    /** Mounts before it gives a horse up: each makes it tamer, and a horse bonds within a few. */
    static final int MOUNTS = 24;

    final UUID horse;
    private int mounts, swaps;
    private String outcome = "", progress = "";

    StableRunner(UUID horse) {
        this.horse = horse;
    }

    String outcome() { return outcome; }
    String progress() { return progress; }

    /** The next step, or none when it is done ({@link #outcome}). */
    Optional<PortalRunCampaign.Task> next(ServerPlayerEntity player) {
        if (!(player.getServerWorld().getEntity(horse) instanceof AbstractHorseEntity mount) || !mount.isAlive()) {
            return done("The horse it chose is gone");
        }
        if (mount.isTame() && !player.getUuid().equals(mount.getOwnerUuid())) return done("Someone else's horse now");
        if (player.getVehicle() == mount) {
            if (!mount.isTame()) {
                progress = "Taming the horse: on it, " + mounts + " times so far";
                return Optional.of(new PortalRunCampaign.Task("native_wait", Map.of("seconds", "1"), "Hold on while the horse bucks"));
            }
            progress = "Tamed: off the horse";
            return Optional.of(new PortalRunCampaign.Task("native_ride", Map.of("op", "off"), "Off the horse"));
        }
        if (!mount.isTame() && mounts >= MOUNTS) return done("The horse would not bond after " + mounts + " tries");
        boolean saddle = mount.isTame() && !mount.isSaddled() && player.getInventory().count(Items.SADDLE) > 0;
        if (mount.isTame() && !saddle) {
            return done(mount.isSaddled() ? "A horse of its own, tamed and saddled" : "A horse of its own, tamed; a saddle when it finds one");
        }
        if (player.distanceTo(mount) > 2.5) {
            progress = mount.isTame() ? "To its horse, to saddle it" : "Up to the wild horse";
            return Optional.of(new PortalRunCampaign.Task("native_goto", Map.of("x", str(mount.getBlockX()), "y", str(mount.getBlockY()),
                    "z", str(mount.getBlockZ()), "range", "2"), progress));
        }
        var inventory = player.getInventory();
        if (saddle) {
            int slot = -1;
            for (int s = 0; s < 9 && slot < 0; s++) if (inventory.getStack(s).isOf(Items.SADDLE)) slot = s;
            if (slot < 0) {
                int from = -1, to = -1;
                for (int s = 9; s < 36 && from < 0; s++) if (inventory.getStack(s).isOf(Items.SADDLE)) from = s;
                for (int s = 8; s >= 0 && to < 0; s--) if (inventory.getStack(s).isEmpty()) to = s;
                if (to < 0) to = 8;
                if (from < 0 || ++swaps > 3) return done("A horse of its own, tamed; its saddle could not be brought to hand");
                progress = "The saddle to hand";
                return Optional.of(new PortalRunCampaign.Task("native_swap", Map.of("source", str(from), "target", str(to)), progress));
            }
            progress = "Saddling its horse";
            return Optional.of(new PortalRunCampaign.Task("native_saddle", Map.of("target", horse.toString(), "slot", str(slot)), progress));
        }
        int empty = -1;
        for (int s = 0; s < 9 && empty < 0; s++) if (inventory.getStack(s).isEmpty()) empty = s;
        if (empty < 0) return done("No hand free to get on the horse with");
        mounts++;
        progress = "Taming the horse: on it, " + mounts + " times so far";
        return Optional.of(new PortalRunCampaign.Task("native_ride", Map.of("op", "mount", "target", horse.toString(), "slot", str(empty)), "On the wild horse"));
    }

    private Optional<PortalRunCampaign.Task> done(String why) {
        outcome = why;
        progress = why;
        return Optional.empty();
    }

    private static String str(int value) { return String.valueOf(value); }
}
