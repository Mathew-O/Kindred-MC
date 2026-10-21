package dev.kindred.core.tactics;

import java.util.Map;
import java.util.Objects;

/**
 * How far each mob notices a player, as vanilla 1.21.1 has it, so a route can keep out of range of a mob it does not
 * mean to fight and a fight can be taken on purpose. A hostile mob picks a player as its target within its follow
 * range when it can see it (a few, like the enderman, only when provoked); a sneaking player is noticed at four
 * fifths of that. Ranges are the mobs' own attributes: 16 blocks unless the mob says otherwise.
 */
public final class MobSenses {
    /** What a mob does about a player it notices. */
    public enum Temper {
        /** Attacks on sight. */
        HOSTILE,
        /** Attacks only when provoked: hit, looked at (the enderman), or a piglin's gold missing. */
        PROVOKED,
        /** Never attacks a player. */
        PEACEFUL
    }

    /**
     * @param range     follow range: how far it notices a player it can see, in blocks
     * @param sight     needs a line of sight to notice
     * @param temper    what noticing leads to
     * @param ranged    attacks from a distance (arrows, fireballs, beams)
     * @param outpaces  faster than a walking player: walking away does not shake it off
     */
    public record Profile(double range, boolean sight, Temper temper, boolean ranged, boolean outpaces) {
        public Profile {
            if (!(range > 0 && range <= 128)) throw new IllegalArgumentException("range out of bounds: " + range);
            Objects.requireNonNull(temper);
        }

        /** The distance at which it notices a player, sneaking or not. */
        public double notices(boolean sneaking) {
            return range * (sneaking ? SNEAK_FACTOR : 1.0);
        }
    }

    /** Vanilla's scaling of a sneaking player's visibility. */
    public static final double SNEAK_FACTOR = 0.8;
    private static final double DEFAULT_RANGE = 16;
    private static final Profile UNKNOWN_HOSTILE = new Profile(DEFAULT_RANGE, true, Temper.HOSTILE, false, false);

    private static final Map<String, Profile> PROFILES = Map.ofEntries(
            Map.entry("zombie", new Profile(35, true, Temper.HOSTILE, false, false)),
            Map.entry("husk", new Profile(35, true, Temper.HOSTILE, false, false)),
            Map.entry("drowned", new Profile(35, true, Temper.HOSTILE, true, false)),
            Map.entry("zombie_villager", new Profile(35, true, Temper.HOSTILE, false, false)),
            Map.entry("skeleton", new Profile(16, true, Temper.HOSTILE, true, false)),
            Map.entry("stray", new Profile(16, true, Temper.HOSTILE, true, false)),
            Map.entry("bogged", new Profile(16, true, Temper.HOSTILE, true, false)),
            Map.entry("wither_skeleton", new Profile(16, true, Temper.HOSTILE, false, true)),
            Map.entry("creeper", new Profile(16, true, Temper.HOSTILE, false, false)),
            Map.entry("spider", new Profile(16, true, Temper.HOSTILE, false, true)),
            Map.entry("cave_spider", new Profile(16, true, Temper.HOSTILE, false, true)),
            Map.entry("witch", new Profile(16, true, Temper.HOSTILE, true, false)),
            Map.entry("pillager", new Profile(32, true, Temper.HOSTILE, true, true)),
            Map.entry("vindicator", new Profile(12, true, Temper.HOSTILE, false, true)),
            Map.entry("blaze", new Profile(48, true, Temper.HOSTILE, true, false)),
            Map.entry("ghast", new Profile(100, true, Temper.HOSTILE, true, false)),
            Map.entry("magma_cube", new Profile(16, true, Temper.HOSTILE, false, false)),
            Map.entry("slime", new Profile(16, true, Temper.HOSTILE, false, false)),
            Map.entry("hoglin", new Profile(16, true, Temper.HOSTILE, false, true)),
            Map.entry("zoglin", new Profile(16, true, Temper.HOSTILE, false, true)),
            Map.entry("piglin_brute", new Profile(16, true, Temper.HOSTILE, false, true)),
            Map.entry("guardian", new Profile(16, true, Temper.HOSTILE, true, true)),
            Map.entry("elder_guardian", new Profile(16, true, Temper.HOSTILE, true, false)),
            Map.entry("phantom", new Profile(64, false, Temper.HOSTILE, false, true)),
            Map.entry("silverfish", new Profile(16, true, Temper.HOSTILE, false, false)),
            Map.entry("endermite", new Profile(16, true, Temper.HOSTILE, false, false)),
            Map.entry("shulker", new Profile(16, true, Temper.HOSTILE, true, false)),
            Map.entry("warden", new Profile(24, false, Temper.HOSTILE, true, true)),
            // Provoked: a piglin by a player with no gold on, an enderman by a look at its face, the rest when hit.
            Map.entry("piglin", new Profile(16, true, Temper.PROVOKED, true, true)),
            Map.entry("enderman", new Profile(64, true, Temper.PROVOKED, false, true)),
            Map.entry("zombified_piglin", new Profile(35, true, Temper.PROVOKED, false, false)),
            Map.entry("wolf", new Profile(16, true, Temper.PROVOKED, false, true)),
            Map.entry("bee", new Profile(48, true, Temper.PROVOKED, false, true)),
            Map.entry("iron_golem", new Profile(16, true, Temper.PROVOKED, false, false)),
            Map.entry("polar_bear", new Profile(20, true, Temper.PROVOKED, false, true)),
            Map.entry("llama", new Profile(40, true, Temper.PROVOKED, true, false)));

    private MobSenses() { }

    /**
     * The profile of a mob by its id's path ("wither_skeleton"), or the vanilla default for a hostile one not listed;
     * {@code hostile} says whether the adapter sees it as a hostile mob at all.
     */
    public static Profile of(String id, boolean hostile) {
        Objects.requireNonNull(id);
        Profile known = PROFILES.get(id);
        if (known != null) return known;
        return hostile ? UNKNOWN_HOSTILE : new Profile(DEFAULT_RANGE, true, Temper.PEACEFUL, false, false);
    }

    /**
     * Would this mob, at {@code distance}, notice the player now? With {@code seen} a line of sight between them; a
     * mob that needs one and has none does not. Peaceful and provoked-only mobs never start a fight by noticing.
     */
    public static boolean wouldAttack(Profile profile, double distance, boolean seen, boolean sneaking) {
        Objects.requireNonNull(profile);
        if (profile.temper() != Temper.HOSTILE) return false;
        if (profile.sight() && !seen) return false;
        return distance <= profile.notices(sneaking);
    }

    /**
     * How much a route should mind passing {@code distance} from this mob: nothing beyond its notice range and two
     * and a half blocks more, a firm price anywhere inside (being noticed at all is what the route avoids), rising to
     * one right beside it. A mob that cannot see the way counts for a third of its range only, enough not to brush
     * past it round a corner.
     */
    public static double unease(Profile profile, double distance, boolean seen) {
        Objects.requireNonNull(profile);
        if (profile.temper() != Temper.HOSTILE) return 0;
        double reach = (profile.sight() && !seen ? profile.range() / 3 : profile.range()) + MARGIN;
        if (distance >= reach) return 0;
        double closeness = 1 - distance / reach;
        return INSIDE + (1 - INSIDE) * closeness * closeness;
    }

    /** Room beyond a mob's range that a route keeps (a mob wanders a little), and the least it minds being inside it. */
    static final double MARGIN = 2.5, INSIDE = 0.5;
}
