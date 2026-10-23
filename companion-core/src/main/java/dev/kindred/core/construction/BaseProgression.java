package dev.kindred.core.construction;

import java.util.Objects;
import java.util.Optional;

/**
 * When a player moves its base on to the next of its three stages (M-01b, the owner's note of 2026-10-23). A base
 * starts small, to get the world started and give the player somewhere to store what it gathers, and keeps getting
 * bigger and better as the world goes on, never ahead of what the player can furnish and fill. Pure.
 * <ul>
 *   <li>No base yet: a {@link Houses.Stage#CABIN} once it has stone tools (it can mine the walls' stone) and before
 *   its first night is long, as a player makes a first shelter.</li>
 *   <li>A cabin: a {@link Houses.Stage#HOUSE} once it has iron tools, when its storage is running out or a few days
 *   have gone by.</li>
 *   <li>A house: a {@link Houses.Stage#MANOR}, built round an enchanting room, once it can make the enchanting table:
 *   two diamonds, four obsidian and a book.</li>
 * </ul>
 */
public final class BaseProgression {
    /** Days played before a cabin is outgrown even with room left in it. */
    public static final int CABIN_DAYS = 3;

    /**
     * Where the player stands. {@code built} is the stage of base it has, if any; {@code storageFree} and
     * {@code storageSlots} the free and total slots of its base's storage; {@code days} the days played.
     */
    public record Progress(Optional<Houses.Stage> built, boolean stoneTools, boolean ironTools, boolean enchantingTable,
                           int storageFree, int storageSlots, long days) {
        public Progress {
            Objects.requireNonNull(built);
            if (storageFree < 0 || storageSlots < 0 || storageFree > storageSlots || days < 0) throw new IllegalArgumentException("Bad progress");
        }
    }

    /** The stage to build next, or none yet, and why. */
    public record Decision(Optional<Houses.Stage> next, String why) {
        public Decision {
            Objects.requireNonNull(next);
            Objects.requireNonNull(why);
        }

        static Decision build(Houses.Stage stage, String why) { return new Decision(Optional.of(stage), why); }

        static Decision wait(String why) { return new Decision(Optional.empty(), why); }
    }

    private BaseProgression() { }

    public static Decision next(Progress p) {
        if (p.built().isEmpty()) {
            return p.stoneTools() ? Decision.build(Houses.Stage.CABIN, "A cabin to start the world from and store what it gathers")
                    : Decision.wait("Stone tools first: the cabin's walls are stone");
        }
        return switch (p.built().get()) {
            case CABIN -> {
                if (!p.ironTools()) yield Decision.wait("Iron tools first: a house takes over two hundred stone");
                boolean full = p.storageSlots() > 0 && p.storageFree() * 4 < p.storageSlots();
                if (full) yield Decision.build(Houses.Stage.HOUSE, "The cabin's chests are almost full: a house with a storage wall");
                if (p.days() >= CABIN_DAYS) yield Decision.build(Houses.Stage.HOUSE, "The cabin is outgrown: a house of two floors");
                yield Decision.wait("The cabin still serves: room in its chests and the world young");
            }
            case HOUSE -> p.enchantingTable() ? Decision.build(Houses.Stage.MANOR, "The makings of an enchanting table: a manor round an enchanting room")
                    : Decision.wait("A manor is built round its enchanting table: two diamonds, four obsidian and a book first");
            case MANOR -> Decision.wait("The manor is the last stage");
        };
    }
}
