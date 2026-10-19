package dev.kindred.core;

import java.util.Objects;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public record Personality(int initiative, int caution, int curiosity, int sociability,
                          int humor, int combativeness, int helpfulness, int farming, int mining, long revision) {
    public enum Trait {
        INITIATIVE, CAUTION, CURIOSITY, SOCIABILITY, HUMOR,
        COMBATIVENESS("combative"), HELPFULNESS("assistful", "assitful"), FARMING("farmer"), MINING("miner");

        private final List<String> aliases;
        Trait(String... aliases) { this.aliases = List.of(aliases); }
        public List<String> commandNames() {
            return Stream.concat(Stream.of(name().toLowerCase(Locale.ROOT)), aliases.stream()).toList();
        }
        public boolean isTaskPreference() {
            return this == COMBATIVENESS || this == HELPFULNESS || this == FARMING || this == MINING;
        }
    }

    public Personality {
        validate(initiative);
        validate(caution);
        validate(curiosity);
        validate(sociability);
        validate(humor);
        validate(combativeness);
        validate(helpfulness);
        validate(farming);
        validate(mining);
        if (revision < 0) throw new IllegalArgumentException("Revision must be non-negative");
    }

    /** Earlier callers retain neutral preferences rather than inheriting a specialization. */
    public Personality(int initiative, int caution, int curiosity, int sociability, int humor, long revision) {
        this(initiative, caution, curiosity, sociability, humor, 50, 50, 50, 50, revision);
    }

    public static Personality defaults() {
        return new Personality(50, 50, 50, 50, 50, 0);
    }

    public Personality withTrait(Trait trait, int value) {
        Objects.requireNonNull(trait, "trait");
        validate(value);
        long next = Math.incrementExact(revision);
        return new Personality(trait == Trait.INITIATIVE ? value : initiative,
                trait == Trait.CAUTION ? value : caution,
                trait == Trait.CURIOSITY ? value : curiosity,
                trait == Trait.SOCIABILITY ? value : sociability,
                trait == Trait.HUMOR ? value : humor,
                trait == Trait.COMBATIVENESS ? value : combativeness,
                trait == Trait.HELPFULNESS ? value : helpfulness,
                trait == Trait.FARMING ? value : farming,
                trait == Trait.MINING ? value : mining, next);
    }

    public double normalized(Trait trait) {
        return value(trait) / 100.0;
    }

    public int value(Trait trait) {
        Objects.requireNonNull(trait, "trait");
        return switch (trait) {
            case INITIATIVE -> initiative;
            case CAUTION -> caution;
            case CURIOSITY -> curiosity;
            case SOCIABILITY -> sociability;
            case HUMOR -> humor;
            case COMBATIVENESS -> combativeness;
            case HELPFULNESS -> helpfulness;
            case FARMING -> farming;
            case MINING -> mining;
        };
    }

    private static void validate(int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Traits must be 0..100");
    }
}
