package dev.kindred.core.hearing;

import java.util.Optional;

/**
 * In plain terms: how a heard sound is put into words a player can use: what kind it is, which way it came from, how
 * far off it was.
 *
 * Technical: pure functions of strings and numbers only (E-71f); the adapter passes the packet's sound id (or world
 * event id), its category, and the sound's offset from the body with the body's yaw. Positions are used only here,
 * to bucket, and are never kept.
 */
public final class Hearing {
    private Hearing() { }

    /** Near is within this many blocks, mid within {@link #MID}. */
    public static final double NEAR = 4, MID = 8;

    /** The world event a broken block is sent as (vanilla's 2001). */
    public static final String BLOCK_BROKEN_EVENT = "2001";

    /**
     * The kind of a sound the server sent, or empty for one not listened for. {@code packet} is "sound", "sound from
     * entity", "world event" or "explosion" (as the adapter names them); {@code sound} the sound's id (in vanilla's namespace)
     * or the world event's number; {@code category} the sound's category ("hostile", ...).
     */
    public static Optional<HeardSound.Kind> kindOf(String packet, String sound, String category) {
        if ("explosion".equals(packet)) return Optional.of(HeardSound.Kind.EXPLOSION);
        if ("world event".equals(packet)) return BLOCK_BROKEN_EVENT.equals(sound) ? Optional.of(HeardSound.Kind.BLOCK_BROKEN) : Optional.empty();
        if (sound == null) return Optional.empty();
        String ns = dev.kindred.core.VanillaIds.NAMESPACE;
        String id = sound.startsWith(ns) ? sound.substring(ns.length()) : sound;
        if (id.equals("entity.creeper.primed")) return Optional.of(HeardSound.Kind.CREEPER_HISS);
        if (id.endsWith(".shoot") && (id.startsWith("entity.") || id.equals("item.crossbow.shoot"))) return Optional.of(HeardSound.Kind.BOW_SHOT);
        if (id.equals("block.lava.pop") || id.equals("block.lava.ambient")) return Optional.of(HeardSound.Kind.LAVA);
        if (id.equals("block.water.ambient")) return Optional.of(HeardSound.Kind.WATER);
        if (id.endsWith("door.open") || id.endsWith("door.close")) return Optional.of(HeardSound.Kind.DOOR);
        if (id.equals("block.chest.open") || id.equals("block.chest.close") || id.equals("block.barrel.open") || id.equals("block.barrel.close")) {
            return Optional.of(HeardSound.Kind.CHEST);
        }
        if (id.equals("entity.generic.eat") || id.equals("entity.generic.drink") || id.equals("entity.player.burp")) return Optional.of(HeardSound.Kind.EATING);
        if (id.endsWith(".step")) return Optional.of(HeardSound.Kind.STEP);
        if ("hostile".equals(category)) return Optional.of(HeardSound.Kind.HOSTILE);
        return Optional.empty();
    }

    /**
     * Which way a sound {@code dx}, {@code dz} blocks off came from, for a body facing {@code yawDegrees} (Minecraft's
     * yaw: 0 faces +z, 90 faces -x; its right hand is then -x and +z respectively).
     */
    public static HeardSound.Bearing bearing(double dx, double dz, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double ahead = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;
        double angle = Math.toDegrees(Math.atan2(right, ahead)); // 0 ahead, 90 right, 180 behind, -90 left.
        int sector = (int) Math.floorMod(Math.round(angle / 45.0), 8);
        return HeardSound.Bearing.values()[sector];
    }

    /** How far a sound {@code distance} blocks off was. */
    public static HeardSound.Band band(double distance) {
        return distance <= NEAR ? HeardSound.Band.NEAR : distance <= MID ? HeardSound.Band.MID : HeardSound.Band.FAR;
    }

    /** The observation of a sound of {@code kind}, {@code dx}, {@code dy}, {@code dz} blocks off, heard at {@code tick}. */
    public static HeardSound heard(HeardSound.Kind kind, double dx, double dy, double dz, float yawDegrees, long tick) {
        return new HeardSound(kind, bearing(dx, dz, yawDegrees), band(Math.sqrt(dx * dx + dy * dy + dz * dz)), tick);
    }
}
