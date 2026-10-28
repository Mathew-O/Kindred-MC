package dev.kindred.core.guide;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The ways of doing each part of the run and which to take now (F-25c8), as players mix and match them: an ordinary way
 * for each part, and the faster ways speedrunners use. In normal play a faster way is taken only when the chance is at
 * hand (a lava pool beside the frame, pearls in the pack); in speedrun mode the quickest possible way is taken. A way
 * that has failed twice is set aside for the next, so a run never sticks on one. The optional strategist may recommend
 * a way from this fixed set; it is taken when it can be done and costs at most half as much again as the best. Pure:
 * the adapter says what can be done from what the player holds and has seen, and what each way would cost.
 */
public final class Playbook {
    /** Failures after which a way is set aside for the part's next. */
    public static final int MAX_FAILURES = 2;
    /** How much dearer than the best a recommended way may be and still be taken. */
    public static final double RECOMMENDED_SLACK = 1.5;

    /** Normal play takes a speedrunner's way only when it is at hand; speedrun mode takes the quickest. */
    public enum Mode { NORMAL, SPEEDRUN }

    /** The parts of the run with more than one way. */
    public enum Part { NETHER_ENTRY, PEARLS, STRONGHOLD_WAY, CAGED_CRYSTAL, DRAGON, RESPAWN }

    /** A way of doing a part; speedrunners' ways are marked. */
    public enum Approach {
        CAST_FRAME(Part.NETHER_ENTRY, false, "lava poured into a frame and hardened with water"),
        MINE_OBSIDIAN(Part.NETHER_ENTRY, false, "a diamond pickaxe and obsidian mined from water poured on lava"),
        RUINED_PORTAL(Part.NETHER_ENTRY, true, "a ruined portal it has found, completed"),
        HUNT_ENDERMEN(Part.PEARLS, false, "endermen hunted for their pearls"),
        BARTER(Part.PEARLS, true, "gold bartered with piglins"),
        OVERWORLD_WALK(Part.STRONGHOLD_WAY, false, "across the Overworld to where the eyes' flights cross"),
        NETHER_SHORTCUT(Part.STRONGHOLD_WAY, true, "through the Nether an eighth of the way, and out by a portal of its own"),
        // No pearl onto a cage's roof: its bars catch a pearl only about half the time, and one that falls through onto
        // the crystal blows it up beside the player (E-39f).
        SHOOT_THROUGH(Part.CAGED_CRYSTAL, false, "shot through the gaps in its cage from far off"),
        PILLAR(Part.CAGED_CRYSTAL, false, "a pillar of its own blocks beside the cage, the bars broken, and down again"),
        PERCH_STRIKES(Part.DRAGON, false, "its head struck with a sword while it perches, arrows while it flies"),
        BED_BOMBS(Part.DRAGON, true, "beds set off under its head while it perches"),
        BED_BY_PORTAL(Part.RESPAWN, false, "a bed set down and used by the End portal");

        private final Part part;
        private final boolean speedrun;
        private final String words;

        Approach(Part part, boolean speedrun, String words) {
            this.part = part;
            this.speedrun = speedrun;
            this.words = words;
        }

        public Part part() { return part; }

        /** A speedrunner's way: in normal play taken only when it is at hand. */
        public boolean speedrun() { return speedrun; }

        public String words() { return words; }

        /** An approach by its name in any case, or nothing for one not in the set. */
        public static Optional<Approach> parse(String name) {
            if (name == null) return Optional.empty();
            try {
                return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_')));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
        }
    }

    /**
     * One way as things stand: whether it can be done from what the player holds and has seen, whether it is at hand
     * (no detour to begin it), what it would cost in seconds, and how often it has failed in this run.
     */
    public record Option(Approach approach, boolean possible, boolean atHand, double seconds, int failures) {
        public Option {
            Objects.requireNonNull(approach);
            if (!(seconds >= 0)) throw new IllegalArgumentException("A way costs zero seconds or more");
            if (failures < 0) throw new IllegalArgumentException("Failures are not negative");
        }

        boolean usable() { return possible && failures < MAX_FAILURES; }
    }

    private Playbook() { }

    /**
     * The way to take for {@code part} now, from the options the adapter offers; empty when none can be done. In
     * normal mode the ordinary ways are weighed and a speedrunner's way only joins them when it is at hand; in
     * speedrun mode every possible way is weighed. The least costly wins; a recommended way within the slack of it is
     * taken instead.
     */
    public static Optional<Approach> choose(Part part, List<Option> options, Mode mode, Optional<Approach> recommended) {
        Objects.requireNonNull(part);
        Objects.requireNonNull(mode);
        var weighed = options.stream()
                .filter(o -> o.approach().part() == part && o.usable())
                .filter(o -> mode == Mode.SPEEDRUN || !o.approach().speedrun() || o.atHand())
                .toList();
        if (weighed.isEmpty()) return Optional.empty();
        var best = weighed.stream().min(Comparator.comparingDouble(Option::seconds)).orElseThrow();
        if (recommended.isPresent()) {
            var advised = weighed.stream().filter(o -> o.approach() == recommended.get()).findFirst();
            if (advised.isPresent() && advised.get().seconds() <= best.seconds() * RECOMMENDED_SLACK + 1e-9) return Optional.of(advised.get().approach());
        }
        return Optional.of(best.approach());
    }
}
