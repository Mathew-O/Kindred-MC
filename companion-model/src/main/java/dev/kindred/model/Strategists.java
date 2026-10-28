package dev.kindred.model;

import java.util.Map;
import java.util.Optional;

/**
 * In plain terms: sets up the AI adviser the server owner has chosen in their settings, or none at all if
 * nothing is set.
 *
 * Technical: Builds the strategist the operator's settings choose (F-19d); nothing when none is set up.
 */
public final class Strategists {
    private Strategists() { }

    /** A strategist, or nothing with the reason in {@code problem} when the settings are incomplete. */
    public record Built(Optional<Strategist> strategist, Optional<String> problem) { }

    /** The adviser the settings ask for, or nothing and why. */
    public static Built fromEnvironment(Map<String, String> env) {
        var selection = StrategistProvider.choose(env);
        if (selection.choice().isEmpty()) return new Built(Optional.empty(), selection.problem());
        var choice = selection.choice().get();
        if (choice.provider() == StrategistProvider.Provider.ANTHROPIC) {
            // Kept in its own class so that only this path loads the Anthropic SDK (absent from the release jar).
            return new Built(Anthropic.build(choice, env), Optional.empty());
        }
        return new Built(Optional.of(new OpenAiCompatibleStrategist(choice)), Optional.empty());
    }

    private static final class Anthropic {
        static Optional<Strategist> build(StrategistProvider.Choice choice, Map<String, String> env) {
            String effort = env.get("KINDRED_STRATEGIST_EFFORT"), thinking = env.get("KINDRED_STRATEGIST_THINKING");
            return ClaudeStrategist.withKey(choice.apiKey(), choice.model(), effort == null ? "low" : effort,
                    thinking == null ? "off" : thinking).map(s -> (Strategist) s);
        }
    }
}
