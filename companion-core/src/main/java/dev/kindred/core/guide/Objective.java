package dev.kindred.core.guide;

import java.util.Locale;
import java.util.Optional;

/**
 * In plain terms: the larger tasks a player gets ready for ahead of time: the Nether portal, the trip into
 * the Nether, finding the stronghold, the fight in the End, and staying alive on the way.
 *
 * Technical: What a need is for: the larger tasks a player prepares for ahead of time.
 */
public enum Objective {
    /** The Nether portal earned from an empty inventory: the portal goal's own route plans its materials. */
    NETHER_PORTAL("the Nether portal"),
    /** Through the portal for blaze rods and ender pearls, and back. */
    NETHER_TRIP("the Nether trip"),
    /** Eyes of ender thrown and followed, and the stronghold dug down to. */
    STRONGHOLD("the stronghold"),
    /** The Ender Dragon, and the End crystals that heal it. */
    END_FIGHT("the End fight"),
    /** Staying alive on the way: nights, mobs, hunger. */
    SURVIVAL("staying alive");

    private final String words;

    Objective(String words) { this.words = words; }

    /** How the report puts it ("the End fight"). */
    public String words() { return words; }

    /** A task by its name in any case, or nothing for one not in the list. */
    public static Optional<Objective> parse(String name) {
        if (name == null) return Optional.empty();
        try {
            return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_')));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
