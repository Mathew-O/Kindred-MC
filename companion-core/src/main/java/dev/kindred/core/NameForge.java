package dev.kindred.core;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * In plain terms: how a player makes up names for its pets, places, buildings and tools, from short word
 * lists in a style that suits its personality: playful, descriptive or classic. The same request always gives
 * the same name, two players alike in every trait still get different ones, and a name given by anyone else
 * must pass the same rules.
 *
 * Technical: Deterministic, curated naming. The same brief and attempt always produce the same name;
 * different companions with identical traits produce different names through their UUID. A name is a memory
 * fact with provenance, never evidence that the named thing exists or is present. No model is involved.
 */
public final class NameForge {
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 32;
    public static final int MAX_ATTEMPTS = 8;
    private static final Pattern VALID = Pattern.compile("[A-Z][A-Za-z' -]*");

    public enum Subject { PET, PLACE, STRUCTURE, TOOL }

    public enum Flavor {
        PLAYFUL, DESCRIPTIVE, CLASSIC;

        /**
         * The naming style a personality leans to: playful for the funny, descriptive for the curious,
         * otherwise classic.
         */
        public static Flavor of(Personality personality) {
            Objects.requireNonNull(personality);
            if (personality.humor() >= 65) return PLAYFUL;
            if (personality.curiosity() >= 65) return DESCRIPTIVE;
            return CLASSIC;
        }
    }

    public enum Source { FORGE, OWNER, MODEL }

    /**
     * @param descriptor a short observed descriptor: species for a pet, terrain for a place, material or
     *                   kind for a structure/tool. It comes from observation, never from a model.
     * @param taken names already in this companion's registry, compared case-insensitively.
     */
    public record Brief(Subject subject, String descriptor, UUID companionId, Personality personality, Set<String> taken) {
        public Brief {
            Objects.requireNonNull(subject);
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(companionId);
            Objects.requireNonNull(personality);
            taken = taken == null ? Set.of() : Set.copyOf(taken);
            if (descriptor.isBlank() || descriptor.length() > 32) throw new IllegalArgumentException("Descriptor must be 1..32 characters");
        }
    }

    public record Name(String text, Subject subject, Source source, int attempt) {
        public Name {
            Objects.requireNonNull(subject);
            Objects.requireNonNull(source);
            text = validate(text);
        }
    }

    private NameForge() { }

    /** The first unused candidate within the attempt budget, or empty when the curated space is exhausted. */
    public static Optional<Name> propose(Brief brief) {
        Objects.requireNonNull(brief, "brief");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = candidate(brief, attempt);
            if (candidate.length() < MIN_LENGTH || candidate.length() > MAX_LENGTH) continue;
            if (isTaken(brief.taken(), candidate)) continue;
            return Optional.of(new Name(candidate, brief.subject(), Source.FORGE, attempt));
        }
        return Optional.empty();
    }

    /** The exact candidate for a replayed attempt; retries of one action must not invent a different name. */
    public static String candidate(Brief brief, int attempt) {
        Objects.requireNonNull(brief, "brief");
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) throw new IllegalArgumentException("Attempt must be 0.." + (MAX_ATTEMPTS - 1));
        long seed = seed(brief, attempt);
        Flavor flavor = Flavor.of(brief.personality());
        return switch (brief.subject()) {
            case PET -> pet(flavor, seed);
            case PLACE -> place(flavor, brief.descriptor(), seed);
            case STRUCTURE -> structure(flavor, brief.descriptor(), seed);
            case TOOL -> tool(flavor, brief.descriptor(), seed);
        };
    }

    /** An owner- or model-supplied name passes the same rules and is tagged with its source. */
    public static Name external(String text, Subject subject, Source source, Set<String> taken) {
        Objects.requireNonNull(source);
        if (source == Source.FORGE) throw new IllegalArgumentException("External names must declare OWNER or MODEL");
        String valid = validate(text);
        if (isTaken(taken == null ? Set.of() : taken, valid)) throw new IllegalArgumentException("That name is already used by this companion");
        return new Name(valid, subject, source, 0);
    }

    /**
     * A name tidied and checked: 2 to 32 letters, spaces, apostrophes or hyphens, starting with a capital.
     */
    static String validate(String text) {
        if (text == null) throw new IllegalArgumentException("Name is required");
        String trimmed = text.strip().replaceAll("\\s+", " ");
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) throw new IllegalArgumentException("Names are " + MIN_LENGTH + ".." + MAX_LENGTH + " characters");
        if (!VALID.matcher(trimmed).matches()) throw new IllegalArgumentException("Names use letters, spaces, apostrophes and hyphens and start with a capital");
        return trimmed;
    }

    private static boolean isTaken(Set<String> taken, String candidate) {
        for (String used : taken) if (used.equalsIgnoreCase(candidate)) return true;
        return false;
    }

    private static long seed(Brief brief, int attempt) {
        long seed = brief.companionId().getMostSignificantBits() * 31 + brief.companionId().getLeastSignificantBits();
        seed = mix(seed ^ brief.subject().ordinal());
        seed = mix(seed ^ brief.descriptor().toLowerCase(Locale.ROOT).hashCode());
        return mix(seed ^ (attempt * 0x9E3779B97F4A7C15L));
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private static <T> T pick(List<T> options, long seed, int lane) {
        return options.get((int) Math.floorMod(mix(seed + lane), options.size()));
    }

    private static String pet(Flavor flavor, long seed) {
        return switch (flavor) {
            case PLAYFUL -> pick(List.of("Biscuit", "Pickles", "Noodle", "Waffles", "Sprocket", "Pudding", "Doodle", "Mochi",
                    "Pebbles", "Bumble", "Tater", "Wiggles", "Nugget", "Pancake", "Zoomer", "Crumpet"), seed, 1);
            case DESCRIPTIVE -> pick(List.of("Ash", "Ember", "Shadow", "Birch", "Dusk", "Frost", "Moss", "Flint",
                    "Clover", "Cinder", "Slate", "Fern", "Rust", "Willow", "Storm", "Juniper"), seed, 1);
            case CLASSIC -> pick(List.of("Max", "Bella", "Rex", "Luna", "Toby", "Daisy", "Finn", "Ruby",
                    "Oscar", "Hazel", "Milo", "Rosie", "Bear", "Molly", "Jasper", "Olive"), seed, 1);
        };
    }

    private static String place(Flavor flavor, String descriptor, long seed) {
        String terrain = terrainWord(descriptor);
        return switch (flavor) {
            case PLAYFUL -> pick(List.of("Kettle", "Pudding", "Lazy", "Tumble", "Wobbly", "Snoozy", "Giggle", "Button"), seed, 2)
                    + " " + terrain;
            case DESCRIPTIVE -> pick(List.of("Grey", "Quiet", "Mossy", "Windy", "Narrow", "Sunlit", "Hollow", "Broad"), seed, 2)
                    + " " + terrain;
            case CLASSIC -> pick(List.of("North", "Old", "High", "Far", "Little", "Long", "Low", "West"), seed, 2)
                    + " " + terrain;
        };
    }

    private static String structure(Flavor flavor, String descriptor, long seed) {
        String kind = structureWord(descriptor);
        return switch (flavor) {
            case PLAYFUL -> pick(List.of("The Snug", "The Nook", "The Burrow", "The Teapot", "The Roost", "The Hideout"), seed, 3)
                    + (kind.isEmpty() ? "" : " " + kind);
            case DESCRIPTIVE -> pick(List.of("Stone", "Timber", "Hillside", "Riverside", "Cliff", "Meadow"), seed, 3) + " " + (kind.isEmpty() ? "House" : kind);
            case CLASSIC -> pick(List.of("Home", "The Cottage", "The Lodge", "The Hall", "The Cabin", "The Homestead"), seed, 3);
        };
    }

    private static String tool(Flavor flavor, String descriptor, long seed) {
        String kind = capitalise(descriptor);
        return switch (flavor) {
            case PLAYFUL -> pick(List.of("Chomper", "Old Grumpy", "Thunk", "Mister Sparkles", "Whacker", "The Persuader"), seed, 4);
            case DESCRIPTIVE -> pick(List.of("Keen", "Worn", "Trusty", "Heavy", "Quick", "Patient"), seed, 4) + " " + kind;
            case CLASSIC -> pick(List.of("Old Reliable", "Faithful", "Steadfast", "Old Friend", "Workhorse", "True"), seed, 4);
        };
    }

    private static String terrainWord(String descriptor) {
        String lower = descriptor.toLowerCase(Locale.ROOT);
        if (lower.contains("ridge") || lower.contains("mountain") || lower.contains("peak")) return "Ridge";
        if (lower.contains("river") || lower.contains("stream")) return "Bend";
        if (lower.contains("lake") || lower.contains("pond") || lower.contains("water")) return "Pond";
        if (lower.contains("forest") || lower.contains("wood") || lower.contains("tree")) return "Woods";
        if (lower.contains("cave") || lower.contains("ravine")) return "Hollow";
        if (lower.contains("hill")) return "Hill";
        if (lower.contains("beach") || lower.contains("shore") || lower.contains("coast")) return "Shore";
        if (lower.contains("plain") || lower.contains("field") || lower.contains("meadow")) return "Meadow";
        return "Point";
    }

    private static String structureWord(String descriptor) {
        String lower = descriptor.toLowerCase(Locale.ROOT);
        if (lower.contains("farm")) return "Farm";
        if (lower.contains("barn") || lower.contains("pen")) return "Barn";
        if (lower.contains("tower")) return "Tower";
        if (lower.contains("shed") || lower.contains("storage")) return "Shed";
        if (lower.contains("house") || lower.contains("home") || lower.contains("cottage")) return "";
        return "";
    }

    private static String capitalise(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return trimmed;
        String word = trimmed.replaceAll("[^A-Za-z' -]", "").strip();
        if (word.isEmpty()) return "Tool";
        return Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT);
    }
}
