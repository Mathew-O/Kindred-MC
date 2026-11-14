package dev.kindred.core.match;

import dev.kindred.core.progression.PlaystyleProfile;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * In plain terms: the game modes a world can be played in (Co-op Survival, Speedrun, Manhunt, Builder, and
 * Factions once it is ready), and the rules each mode and each kind of match plays by, written out so they can
 * be announced to everyone before they play: survival or creative, what may be built or broken, who may attack
 * whom, what items the mode hands out (the same for people and AI players), what happens on a death, and how
 * it is won.
 *
 * Technical: The world's mode (M-00) and the declared rules of each mode and match mode. A world's mode maps onto
 * the existing playstyle profile (it does not run beside it) and says which matches may be proposed in it; a duel,
 * opt-in for both players, may be proposed in any world. A world with no mode chosen keeps the behaviour from
 * before modes existed: every match may be proposed and every playstyle stays as set. Rules are data: the runtime
 * enforces each rule where it already lives ({@link MatchRegistry#mayAttack}, the builders' creative plots, the
 * hunters' compass), and announces these words so the rule is known before anyone plays (D6, D7).
 */
public final class GameModes {
    private GameModes() { }

    /** What a world is played as. */
    public enum WorldMode {
        COOP_SURVIVAL("Co-op Survival", PlaystyleProfile.OPTIMAL_SURVIVAL, true, Set.of(MatchRegistry.Mode.DUEL)),
        SPEEDRUN("Speedrun", PlaystyleProfile.SPEEDRUN_ANY_PERCENT, true, Set.of(MatchRegistry.Mode.SPEEDRUN, MatchRegistry.Mode.DUEL)),
        MANHUNT("Manhunt", PlaystyleProfile.OPTIMAL_SURVIVAL, true, Set.of(MatchRegistry.Mode.MANHUNT, MatchRegistry.Mode.DUEL)),
        BUILDER("Builder", PlaystyleProfile.IDLE_OBSERVING, true, Set.of(MatchRegistry.Mode.BUILDER, MatchRegistry.Mode.DUEL)),
        /**
         * The optional mode (M-06): an operator's choice like the others, never on by itself. The faction world is a
         * standing match its members joined by joining a faction (D7).
         */
        FACTIONS("Factions", PlaystyleProfile.OPTIMAL_SURVIVAL, true, Set.of(MatchRegistry.Mode.DUEL));

        public final String label;
        /** The playstyle an AI player plays this world at, unless its administrator sets its own. */
        public final PlaystyleProfile playstyle;
        /** Whether it may be chosen yet. */
        public final boolean playable;
        /** The matches that may be proposed in a world of this mode. */
        public final Set<MatchRegistry.Mode> matches;

        WorldMode(String label, PlaystyleProfile playstyle, boolean playable, Set<MatchRegistry.Mode> matches) {
            this.label = label;
            this.playstyle = playstyle;
            this.playable = playable;
            this.matches = Set.copyOf(matches);
        }

        /** The name it is typed as: coop, speedrun, manhunt, builder, factions. */
        public String key() { return this == COOP_SURVIVAL ? "coop" : name().toLowerCase(Locale.ROOT); }
    }

    /** One mode's declared rules, each in a sentence for players to read. */
    public record Rules(String title, String play, String edits, String pvp, String items, String respawn, String win) {
        /** The rules as lines, in the order they are announced. */
        public List<String> lines() {
            return List.of("Play: " + play, "Building: " + edits, "Fighting: " + pvp, "Items: " + items, "Deaths: " + respawn, "Winning: " + win);
        }
    }

    /** A world mode by the name it is typed as, or its own name. */
    public static Optional<WorldMode> parse(String raw) {
        if (raw == null) return Optional.empty();
        String text = raw.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        for (WorldMode mode : WorldMode.values()) {
            if (mode.key().equals(text) || mode.name().toLowerCase(Locale.ROOT).equals(text)) return Optional.of(mode);
        }
        return text.equals("co_op") || text.equals("survival") ? Optional.of(WorldMode.COOP_SURVIVAL) : Optional.empty();
    }

    /**
     * Whether a match of {@code match} may be proposed in a world of {@code world}: any match where no mode is chosen
     * (null), otherwise those its mode lists.
     */
    public static boolean allows(WorldMode world, MatchRegistry.Mode match) {
        return world == null || world.matches.contains(match);
    }

    /** Why a match is refused in a world of {@code world}, for the proposer. */
    public static String refusal(WorldMode world, MatchRegistry.Mode match) {
        return "This world plays " + world.label + "; a " + match.label().toLowerCase(Locale.ROOT)
                + " needs the world's mode to be " + switch (match) {
                    case SPEEDRUN -> "Speedrun";
                    case MANHUNT -> "Manhunt";
                    case BUILDER -> "Builder";
                    case DUEL -> "any";
                } + " (/aiplayer mode world <mode>).";
    }

    /** The playstyle a world of {@code world} plays an AI player at; {@code fallback} where no mode is chosen. */
    public static PlaystyleProfile playstyle(WorldMode world, PlaystyleProfile fallback) {
        return world == null ? fallback : world.playstyle;
    }

    /** A world mode's own rules: how the world is played outside any match. */
    public static Rules rules(WorldMode world) {
        return switch (world) {
            case COOP_SURVIVAL -> new Rules("Co-op Survival",
                    "survival, everyone on one side; AI players progress at the group's pace",
                    "the server's edit policy for AI players; people as the server allows",
                    "nobody attacks a player; a duel needs both players to join it",
                    "none handed out: everything is earned",
                    "respawn as usual, keeping nothing the death dropped",
                    "no winner: the playthrough, to the dragon when the group goes for it");
            case SPEEDRUN -> new Rules("Speedrun",
                    "survival, any% glitchless, races from a fresh start",
                    "the server's edit policy; an AI runner needs world edits and the dragon fight's leave",
                    "nobody attacks a player outside a duel both joined",
                    "none handed out: everything is earned",
                    "a fall is a setback: respawn and run on",
                    "the first runner to free the End wins its race");
            case MANHUNT -> new Rules("Manhunt",
                    "survival from scratch, runners against hunters",
                    "the server's edit policy for AI players",
                    "a runner and a hunter of a running manhunt both joined, never two on one side, never anyone outside it",
                    "every hunter gets a tracking compass when the hunt starts, the same for people and AI players: lost to a death or destroyed, replaced; set down or taken, not; taken back at the end",
                    "hunters respawn and hunt on; a runner who falls is out",
                    "a runner frees the End, or every runner falls");
            case BUILDER -> new Rules("Builder",
                    "creative build battles on a theme; outside a battle AI players only watch",
                    "each builder on its own plot only; blocks are free in Builder alone",
                    "nobody attacks anybody",
                    "the creative palette on the plot, nothing carried in or out",
                    "none: creative",
                    "the best builds by the builders' votes");
            case FACTIONS -> new Rules("Factions",
                    "survival in factions: found or join one, claim land by its power, earn coins at the market",
                    "the server's edit policy; claimed land only by its faction, the same for people and AI players",
                    // D7, the stricter reading: a person in no faction is never a target; the owner may loosen it later.
                    "only between members of different factions, never in safe zones; joining a faction is joining the match; leave it any time",
                    "none handed out",
                    "respawn as usual; a death to another faction costs two power",
                    "no end: the factions' standing by land and power");
        };
    }

    /** A match mode's rules: what everyone invited is told before joining. */
    public static Rules rules(MatchRegistry.Mode match) {
        return switch (match) {
            case DUEL -> new Rules("Duel",
                    "survival, two players who both joined; three seconds of countdown, then the fight",
                    "the world's own rules; nothing is set up for it",
                    "only the two of them, only while it runs, and only with the server's PvP on",
                    "none handed out: each fights with what it carries",
                    "a fall ends it: the fallen respawns as usual, dropping what it carried",
                    "the other falls or forfeits; when its time runs out, a draw");
            case SPEEDRUN -> new Rules("Speedrun race",
                    "survival from a fresh start: an empty inventory and none of the splits' advancements",
                    "the world's own rules; an AI runner needs world edits and the dragon fight's leave",
                    "nobody attacks anybody",
                    "none handed out: everything is earned",
                    "a fall is a setback: respawn and run on",
                    "the first runner to free the End; splits at vanilla's own advancements");
            case MANHUNT -> new Rules("Manhunt",
                    "survival from scratch, runners against hunters",
                    "the world's own rules",
                    "a runner and a hunter may attack one another once both joined and it runs; never two on one side",
                    "every hunter gets one tracking compass at the start, pointing at the nearest runner or its last portal: lost to a death or destroyed, replaced; set down or taken, not; taken back at the end",
                    "hunters respawn; a runner who falls is out",
                    "a runner frees the End, or every runner falls");
            case BUILDER -> new Rules("Build battle",
                    "creative on each builder's own plot for the build's time, then a vote",
                    "only on its own plot; blocks are free in Builder alone",
                    "nobody attacks anybody",
                    "the creative palette on the plot; nothing carried in or out",
                    "none: creative",
                    "the best average vote, each builder voting on the others' builds");
        };
    }
}
