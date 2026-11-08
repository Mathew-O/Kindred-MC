package dev.kindred.core.factions;

import java.util.Locale;
import java.util.Optional;

/**
 * In plain terms: the colour a faction is shown in, chosen by its leader from Minecraft's sixteen dye colours.
 *
 * Technical: The owner's decision (2026-11-08): each faction's colour from the 16 dye colours, by their Minecraft names,
 * so the adapter maps one to {@code DyeColor.byName} unchanged.
 */
public enum FactionColour {
    WHITE, ORANGE, MAGENTA, LIGHT_BLUE, YELLOW, LIME, PINK, GRAY, LIGHT_GRAY, CYAN, PURPLE, BLUE, BROWN, GREEN, RED, BLACK;

    /** Its Minecraft dye name, such as {@code light_blue}. */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<FactionColour> parse(String raw) {
        if (raw == null) return Optional.empty();
        String text = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (text.equals("GREY")) text = "GRAY";
        if (text.equals("LIGHT_GREY") || text.equals("SILVER")) text = "LIGHT_GRAY";
        try {
            return Optional.of(valueOf(text));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
