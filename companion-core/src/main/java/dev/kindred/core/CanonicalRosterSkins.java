package dev.kindred.core;

import java.util.Locale;
import java.util.Optional;

/**
 * In plain terms: links Kindred, Sol and Vesp to their own skins, which ship inside the mod. Players running
 * the Kindred client mod see these skins on the AI players.
 *
 * Technical: The canonical trio's own skins. The PNGs ship inside the mod; the optional client mod renders
 * them for native bodies that carry the matching profile marker. All three skins use the slim (three-pixel)
 * arm model.
 */
public final class CanonicalRosterSkins {

    /** Profile property naming which bundled roster skin a native body wears. */
    public static final String PROFILE_PROPERTY = "kindred_skin";

    /** One player's skin: its key, whether it has slim arms, and where the image is inside the mod. */
    public record SkinBinding(String key, boolean slim, String clientAssetPath) { }

    public static final SkinBinding KINDRED = new SkinBinding("kindred", true, "textures/entity/roster/kindred.png");
    public static final SkinBinding SOL = new SkinBinding("sol", true, "textures/entity/roster/sol.png");
    public static final SkinBinding VESP = new SkinBinding("vesp", true, "textures/entity/roster/vesp.png");

    /** The built-in skin for a player name (Kindred, Sol or Vesp), if it is one of them. */
    public static Optional<SkinBinding> forDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return Optional.empty();
        }
        String key = displayName.trim().toLowerCase(Locale.ROOT);
        if (CanonicalRoster.KINDRED_NAME.toLowerCase(Locale.ROOT).equals(key)) {
            return Optional.of(KINDRED);
        }
        if (CanonicalRoster.SOL_NAME.toLowerCase(Locale.ROOT).equals(key)) {
            return Optional.of(SOL);
        }
        if (CanonicalRoster.VESP_NAME.toLowerCase(Locale.ROOT).equals(key)) {
            return Optional.of(VESP);
        }
        return Optional.empty();
    }

    /** Resolves the marker a profile carries back to its binding; unknown keys are ignored. */
    public static Optional<SkinBinding> forKey(String key) {
        if (key == null) return Optional.empty();
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "kindred" -> Optional.of(KINDRED);
            case "sol" -> Optional.of(SOL);
            case "vesp" -> Optional.of(VESP);
            default -> Optional.empty();
        };
    }

    private CanonicalRosterSkins() { }
}
