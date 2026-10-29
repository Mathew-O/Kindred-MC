package dev.kindred.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * In plain terms: what a player may tell the person talking to it. Whoever may command it hears everything it
 * knows, its position included; another player hears what anyone standing beside it could see or ask, but not where
 * it is or what it carries; an opponent in a match hears only what it could see for itself (E-20a).
 *
 * Technical: The listener tiers of the player's voice as a pure function. The adapter gathers a {@link Situation}
 * from the player's own state and this function writes out only the lines this listener may know, so a fact withheld
 * is never in the model's prompt to leak. A controller is whoever {@code mayControl} admits (its owner, an
 * administrator); an opponent is one it may fight in a running match, and is told the least even when it could also
 * command the player.
 */
public final class ConversationAudience {
    private ConversationAudience() { }

    /** Who the speaker is to the player. */
    public enum Relation { OWNER, ADMIN, TEAMMATE, PLAYER, OPPONENT }

    /** How much the speaker may be told. */
    public enum Tier { CONTROLLER, PLAYER, OPPONENT }

    /**
     * What the player knows at the moment it is spoken to, gathered by the adapter from its own state. Strings may be
     * empty and lists empty where the player knows nothing of that kind.
     *
     * @param hurtBy what hurt it last, as the game recorded it ("a fall", "a Zombie", "Steve"), empty when it is not hurt
     * @param doing what it is at now, in a few words ("gathering logs", "waiting")
     * @param goal its standing goal ("a Nether portal"), empty when it has none
     * @param goalProgress the goal's progress line ("Wood: Gather logs for a crafting table")
     * @param position where it stands, "x, y, z"
     * @param held the tools and weapons it holds, by name
     * @param foodItems how many pieces of food it carries
     * @param notable notable things it carries ("3 diamonds")
     * @param events the goal's last few events, as it logged them
     * @param memory its deaths, notes and kept habits in this world
     * @param transcript the last lines between it and this speaker, oldest first, each "Name: text"
     */
    public record Situation(String player, String speaker, Relation relation, boolean speakerControls,
                            double health, int food, String hurtBy,
                            String dimension, String time, String weather,
                            boolean stopped, boolean busy, String doing, String goal, String goalProgress,
                            String position, String threat, List<String> near,
                            List<String> held, int foodItems, List<String> notable,
                            List<String> events, List<String> memory, String lastSaid, List<String> transcript) {
        public Situation {
            Objects.requireNonNull(player);
            Objects.requireNonNull(speaker);
            Objects.requireNonNull(relation);
            hurtBy = Objects.requireNonNullElse(hurtBy, "");
            dimension = Objects.requireNonNullElse(dimension, "");
            time = Objects.requireNonNullElse(time, "");
            weather = Objects.requireNonNullElse(weather, "");
            doing = Objects.requireNonNullElse(doing, "");
            goal = Objects.requireNonNullElse(goal, "");
            goalProgress = Objects.requireNonNullElse(goalProgress, "");
            position = Objects.requireNonNullElse(position, "");
            threat = Objects.requireNonNullElse(threat, "");
            lastSaid = Objects.requireNonNullElse(lastSaid, "");
            near = List.copyOf(near);
            held = List.copyOf(held);
            notable = List.copyOf(notable);
            events = List.copyOf(events);
            memory = List.copyOf(memory);
            transcript = List.copyOf(transcript);
        }
    }

    /** The tier a speaker is in: an opponent first, whatever else it is; then whoever may command the player. */
    public static Tier tier(Relation relation, boolean speakerControls) {
        if (relation == Relation.OPPONENT) return Tier.OPPONENT;
        return speakerControls ? Tier.CONTROLLER : Tier.PLAYER;
    }

    /** Whether this speaker may be told where the player is (and so be given coordinates in a reply). */
    public static boolean mayKnowPosition(Situation situation) {
        return tier(situation.relation(), situation.speakerControls()) == Tier.CONTROLLER;
    }

    /**
     * Whether this speaker may ask the player to do something in chat (E-20b): only one who may already command it, and
     * never an opponent in a match, whatever else it is. An ask still goes through the command's own checks.
     */
    public static boolean mayAsk(Situation situation) {
        return tier(situation.relation(), situation.speakerControls()) == Tier.CONTROLLER;
    }

    /** Three numbers in a row, or numbers marked x, y and z: a place, however it is written. */
    private static final Pattern PLACE = Pattern.compile("-?\\d+\\s*[,/ ]\\s*-?\\d+\\s*[,/ ]\\s*-?\\d+|\\b[xyz]\\s*[=:]?\\s*-?\\d+",
            Pattern.CASE_INSENSITIVE);

    /** {@code text} with any place written in it taken out, for a listener who may not know where things are. */
    public static String withoutPlaces(String text) {
        return PLACE.matcher(text).replaceAll("somewhere").replaceAll("\\s+", " ").strip();
    }

    /** Whether {@code text} gives a place. */
    public static boolean givesPlace(String text) {
        return PLACE.matcher(text).find();
    }

    /** The situation as labelled lines, only those this speaker may know. */
    public static List<String> lines(Situation s) {
        Tier tier = tier(s.relation(), s.speakerControls());
        var lines = new ArrayList<String>();
        lines.add("You are: " + s.player());
        lines.add("Speaking to you: " + s.speaker() + " (" + relation(s.relation()) + (s.speakerControls() && tier != Tier.OPPONENT
                ? ", who may give you commands" : ", who may not give you commands") + ")");
        lines.add("Your health: " + Math.round(s.health()) + " of 20; food: " + s.food() + " of 20"
                + (s.hurtBy().isEmpty() ? "" : "; just hurt by " + s.hurtBy()));
        if (!s.dimension().isEmpty() || !s.time().isEmpty() || !s.weather().isEmpty()) {
            lines.add("Where you are, in general: " + join(List.of(s.dimension(), s.time(), s.weather())));
        }
        if (s.stopped()) {
            lines.add("You are stopped: you do nothing until someone who may command you resumes you.");
        } else if (tier == Tier.OPPONENT) {
            lines.add("What you are doing: " + (s.busy() ? "busy" : "idle"));
        } else {
            lines.add("What you are doing: " + (s.doing().isEmpty() ? (s.busy() ? "busy" : "nothing in particular") : s.doing()));
            if (!s.goal().isEmpty()) {
                lines.add("Your goal: " + s.goal() + (tier == Tier.CONTROLLER && !s.goalProgress().isEmpty() ? "; " + s.goalProgress() : ""));
            }
        }
        if (tier == Tier.CONTROLLER && !s.position().isEmpty()) lines.add("Your position: " + s.position());
        lines.add("What you can see: " + (s.threat().isEmpty() ? "no hostile creature" : "a " + s.threat())
                + (s.near().isEmpty() ? "" : "; near you: " + String.join(", ", s.near())));
        if (tier == Tier.CONTROLLER) {
            lines.add("What you carry: " + (s.held().isEmpty() ? "no tools" : String.join(", ", s.held())) + "; "
                    + s.foodItems() + " pieces of food" + (s.notable().isEmpty() ? "" : "; also " + String.join(", ", s.notable())));
        } else if (tier == Tier.PLAYER) {
            lines.add("What you carry, roughly: " + s.held().size() + " tools, " + s.foodItems() + " pieces of food, "
                    + s.notable().size() + " notable things");
        }
        if (tier != Tier.OPPONENT) {
            for (String event : s.events()) {
                String told = tier == Tier.CONTROLLER ? event : withoutPlaces(event);
                if (!told.isEmpty()) lines.add("Lately: " + told);
            }
            for (String kept : s.memory()) {
                String told = tier == Tier.CONTROLLER ? kept : withoutPlaces(kept);
                if (!told.isEmpty()) lines.add("You remember: " + told);
            }
        }
        if (!s.lastSaid().isEmpty()) lines.add("The last thing you said aloud: " + (tier == Tier.CONTROLLER ? s.lastSaid() : withoutPlaces(s.lastSaid())));
        for (String said : s.transcript()) lines.add("Earlier in this talk: " + (tier == Tier.CONTROLLER ? said : withoutPlaces(said)));
        return List.copyOf(lines);
    }

    private static String relation(Relation relation) {
        return switch (relation) {
            case OWNER -> "your owner";
            case ADMIN -> "a server administrator";
            case TEAMMATE -> "a teammate";
            case PLAYER -> "another player";
            case OPPONENT -> "your opponent in a match";
        };
    }

    private static String join(List<String> parts) {
        return String.join(", ", parts.stream().filter(part -> !part.isBlank()).toList());
    }
}
