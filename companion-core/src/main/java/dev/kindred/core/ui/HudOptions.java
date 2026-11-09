package dev.kindred.core.ui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: how a player likes the Factions panel drawn on their own screen: how see-through its card is, how
 * big, which side, and whether it steps aside while a title (a match countdown) is on screen. Each player's own
 * client keeps these; the server never sees them.
 *
 * Technical: The Factions HUD's client settings (M-06l). Pure: read from and written to plain key and value text,
 * every value clamped to its range, anything unreadable left at its default. {@link #DEFAULT} is the card as it was
 * drawn before these existed: 35% opaque stone grey, scale 1, on the right, hidden during titles.
 */
public record HudOptions(int opacity, double scale, Side side, boolean hideForTitles) {
    public enum Side { LEFT, RIGHT }

    public static final int MIN_OPACITY = 10, MAX_OPACITY = 100;
    public static final double MIN_SCALE = 0.5, MAX_SCALE = 2.0;
    public static final HudOptions DEFAULT = new HudOptions(35, 1.0, Side.RIGHT, true);

    /** The card's stone grey and its lighter edge, before their opacity. */
    private static final int GREY = 0x3C_3C_3C, EDGE_GREY = 0x8A_8A_8A;

    public HudOptions {
        Objects.requireNonNull(side);
        opacity = Math.clamp(opacity, MIN_OPACITY, MAX_OPACITY);
        scale = Double.isFinite(scale) ? Math.clamp(Math.round(scale * 20) / 20.0, MIN_SCALE, MAX_SCALE) : 1.0;
    }

    /** The card's colour, ARGB: its grey at this opacity. At 35% it is the card's own 0x59 alpha. */
    public int card() {
        return alpha(opacity) << 24 | GREY;
    }

    /** The card's edge, ARGB: lighter, and a little more opaque than the card, as before (0x80 at 35%). */
    public int edge() {
        return Math.min(255, Math.round(alpha(opacity) * 128f / 89f)) << 24 | EDGE_GREY;
    }

    private static int alpha(int percent) {
        return Math.round(percent * 255f / 100f);
    }

    /** Where the card goes, in the card's own scaled coordinates: its corner and size. */
    public record Card(int x, int y, int width, int height) { }

    /**
     * The card for {@code lines} rows of text {@code fontHeight} high, on a screen {@code screenWidth} by
     * {@code screenHeight} in GUI coordinates: laid out in its own scale (the screen divided by it), a quarter of that
     * width (120 at least), four from the chosen side and a third of the way down, as the card always was.
     */
    public Card place(int screenWidth, int screenHeight, int lines, int fontHeight) {
        int width = Math.max(120, (int) Math.round(screenWidth / scale) / 4);
        int pad = 4, height = pad * 2 + lines * (fontHeight + 2);
        int scaledWidth = (int) Math.round(screenWidth / scale), scaledHeight = (int) Math.round(screenHeight / scale);
        int x = side == Side.LEFT ? 4 : scaledWidth - width - 4;
        int y = Math.max(4, (scaledHeight - height) / 3);
        return new Card(x, y, width, height);
    }

    /** One setting changed from text ({@code opacity 50}, {@code scale 1.5}, {@code side left}, {@code titles off}), or why not. */
    public Result with(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        String v = value.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (key.trim().toLowerCase(Locale.ROOT)) {
                case "opacity" -> new Result(Optional.of(new HudOptions(Integer.parseInt(v.replace("%", "")), scale, side, hideForTitles)), "");
                case "scale" -> new Result(Optional.of(new HudOptions(opacity, Double.parseDouble(v), side, hideForTitles)), "");
                case "side" -> switch (v) {
                    case "left", "right" -> new Result(Optional.of(new HudOptions(opacity, scale, Side.valueOf(v.toUpperCase(Locale.ROOT)), hideForTitles)), "");
                    default -> new Result(Optional.empty(), "Side is left or right");
                };
                case "titles" -> switch (v) {
                    case "on", "hide", "true" -> new Result(Optional.of(new HudOptions(opacity, scale, side, true)), "");
                    case "off", "show", "false" -> new Result(Optional.of(new HudOptions(opacity, scale, side, false)), "");
                    default -> new Result(Optional.empty(), "Titles is on (hidden during titles) or off");
                };
                default -> new Result(Optional.empty(), "No setting " + key + ": opacity, scale, side or titles");
            };
        } catch (NumberFormatException notANumber) {
            return new Result(Optional.empty(), "Not a number: " + value);
        }
    }

    /** A setting's change: the new options, or none and why. */
    public record Result(Optional<HudOptions> options, String error) {
        public Result {
            Objects.requireNonNull(options);
            Objects.requireNonNull(error);
        }
    }

    /** The options as text to keep, in a fixed order. */
    public Map<String, String> toMap() {
        var map = new LinkedHashMap<String, String>();
        map.put("opacity", String.valueOf(opacity));
        map.put("scale", String.valueOf(scale));
        map.put("side", side.name().toLowerCase(Locale.ROOT));
        map.put("titles", hideForTitles ? "on" : "off");
        return map;
    }

    /** The options kept as text, each read on its own: one missing or unreadable keeps its default. */
    public static HudOptions fromMap(Map<String, String> kept) {
        var options = DEFAULT;
        for (var key : new String[] {"opacity", "scale", "side", "titles"}) {
            String value = kept.get(key);
            if (value == null) continue;
            var changed = options.with(key, value);
            if (changed.options().isPresent()) options = changed.options().get();
        }
        return options;
    }

    /** As said to the player: {@code opacity 35%, scale 1.0, right side, hidden during titles}. */
    public String describe() {
        return "opacity " + opacity + "%, scale " + scale + ", " + side.name().toLowerCase(Locale.ROOT) + " side, "
                + (hideForTitles ? "hidden during titles" : "shown during titles");
    }
}
