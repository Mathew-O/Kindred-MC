package dev.kindred.core;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: a player's saved habits, its little quirks: sprint-jumping, being lively, glancing around
 * curiously, or moving steadily. Each can be forced on, forced off, or left to its personality. Habits only
 * change how it moves, never where it goes or whether it stops.
 *
 * Technical: Saved movement/idle habits. A quirk is presentation inside already authorized movement: it never
 * starts navigation, extends a route, bypasses a yield or landing check, continues after stop or
 * takes gaze from a skill that owns it. Resolution is deterministic so a companion keeps its character.
 */
public record Mannerisms(UUID companionId, Map<Quirk, Setting> settings, long revision) {
    /** Bump when the {@code auto} rules change so a saved profile is not silently re-characterised. */
    public static final int POLICY_VERSION = 1;

    public enum Quirk {
        SPRINTJUMP, LIVELY, CURIOUSGLANCE, STEADY;

        public String commandName() { return name().toLowerCase(Locale.ROOT); }

        /** The quirk with this name, as typed in a command. */
        public static Quirk parse(String name) {
            Objects.requireNonNull(name, "name");
            for (Quirk quirk : values()) if (quirk.commandName().equals(name.toLowerCase(Locale.ROOT))) return quirk;
            throw new IllegalArgumentException("Unknown quirk " + name);
        }
    }

    public enum Setting {
        AUTO, ON, OFF;

        /** The setting with this name, as typed in a command. */
        public static Setting parse(String name) {
            Objects.requireNonNull(name, "name");
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "on", "true" -> ON;
                case "off", "false" -> OFF;
                default -> throw new IllegalArgumentException("Quirk settings are auto, on or off");
            };
        }
    }

    public Mannerisms {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(settings, "settings");
        if (revision < 0) throw new IllegalArgumentException("Negative mannerism revision");
        EnumMap<Quirk, Setting> copy = new EnumMap<>(Quirk.class);
        for (Quirk quirk : Quirk.values()) copy.put(quirk, Objects.requireNonNull(settings.getOrDefault(quirk, Setting.AUTO)));
        settings = Map.copyOf(copy);
    }

    /** Every habit left to its personality. */
    public static Mannerisms defaults(UUID companionId) {
        return new Mannerisms(companionId, Map.of(), 0);
    }

    /** How one habit is set. */
    public Setting setting(Quirk quirk) { return settings.get(Objects.requireNonNull(quirk)); }

    /** The same habits with one changed. */
    public Mannerisms with(Quirk quirk, Setting setting) {
        Objects.requireNonNull(quirk);
        Objects.requireNonNull(setting);
        EnumMap<Quirk, Setting> next = new EnumMap<>(settings);
        next.put(quirk, setting);
        return new Mannerisms(companionId, next, Math.incrementExact(revision));
    }

    /**
     * The quirks this companion actually exhibits. {@code auto} derives from the traits plus a stable
     * per-companion tie-break so identical traits can still produce different characters.
     * {@code steady} suppresses the fidget quirks when it is on; an explicit {@code on} for a fidget wins.
     */
    public Set<Quirk> resolve(Personality personality) {
        Objects.requireNonNull(personality, "personality");
        EnumSet<Quirk> active = EnumSet.noneOf(Quirk.class);
        for (Quirk quirk : Quirk.values()) {
            Setting setting = settings.get(quirk);
            boolean on = switch (setting) {
                case ON -> true;
                case OFF -> false;
                case AUTO -> autoLeans(quirk, personality);
            };
            if (on) active.add(quirk);
        }
        if (active.contains(Quirk.STEADY)) {
            for (Quirk fidget : List.of(Quirk.SPRINTJUMP, Quirk.LIVELY, Quirk.CURIOUSGLANCE)) {
                if (settings.get(fidget) == Setting.AUTO) active.remove(fidget);
            }
        }
        return Set.copyOf(active);
    }

    /** Deterministic lean in -50..+50; positive means the companion exhibits the quirk under auto. */
    public int autoLean(Quirk quirk, Personality personality) {
        Objects.requireNonNull(quirk);
        Objects.requireNonNull(personality);
        double lean = switch (quirk) {
            case SPRINTJUMP -> 0.45 * centered(personality.initiative()) + 0.35 * centered(personality.humor())
                    - 0.5 * centered(personality.caution());
            case LIVELY -> 0.5 * centered(personality.sociability()) + 0.35 * centered(personality.humor())
                    - 0.25 * centered(personality.caution());
            case CURIOUSGLANCE -> 0.55 * centered(personality.curiosity()) + 0.35 * centered(personality.sociability());
            case STEADY -> 0.55 * centered(personality.caution()) - 0.3 * centered(personality.humor())
                    - 0.3 * centered(personality.sociability());
        };
        // Neutral traits leave the decision to the stable identity: a small, fixed personal tilt.
        return (int) Math.round(Math.max(-50, Math.min(50, lean + tieBreak(quirk))));
    }

    private boolean autoLeans(Quirk quirk, Personality personality) { return autoLean(quirk, personality) > 0; }

    /** -6..+6 derived from the companion UUID and the quirk; independent of traits and of the clock. */
    int tieBreak(Quirk quirk) {
        long bits = companionId.getMostSignificantBits() ^ Long.rotateLeft(companionId.getLeastSignificantBits(), 7 * (quirk.ordinal() + 1));
        bits ^= (bits >>> 29) * 0x9E3779B97F4A7C15L;
        return (int) Math.floorMod(bits, 13) - 6;
    }

    private static double centered(int value) { return value - 50; }
}
