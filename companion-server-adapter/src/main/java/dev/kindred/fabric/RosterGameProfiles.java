package dev.kindred.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.kindred.core.CanonicalRosterSkins;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/**
 * In plain terms: builds the player profile (name, id and skin) an AI player's body joins the server with.
 * Players using the Kindred client see the crew's own skins; everyone else sees a default skin unless the
 * server owner supplies signed skin data.
 *
 * Technical: Builds native body profiles. Canonical roster bodies carry a {@link
 * CanonicalRosterSkins#PROFILE_PROPERTY} marker so the optional Kindred client renders the bundled skin.
 * Vanilla clients can only show a custom skin from Mojang-signed textures, which an operator may supply in
 * {@code config/kindred_skins.properties} as {@code <key>.value} and {@code <key>.signature}; without them
 * those clients see a default skin.
 */
final class RosterGameProfiles {

    private static final String TEXTURES = "textures";
    private static final Path SIGNED_TEXTURES = FabricLoader.getInstance().getConfigDir().resolve("kindred_skins.properties");

    private RosterGameProfiles() { }

    /** The profile for one AI player's body. */
    static GameProfile forNativeBody(UUID entityId, String internalProfileName, String displayName) {
        GameProfile profile = new GameProfile(entityId, internalProfileName);
        CanonicalRosterSkins.forDisplayName(displayName).ifPresent(skin -> {
            profile.getProperties().put(CanonicalRosterSkins.PROFILE_PROPERTY,
                    new Property(CanonicalRosterSkins.PROFILE_PROPERTY, skin.key()));
            signedTextures(skin.key()).ifPresent(textures -> profile.getProperties().put(TEXTURES, textures));
        });
        return profile;
    }

    private static java.util.Optional<Property> signedTextures(String key) {
        if (!Files.isRegularFile(SIGNED_TEXTURES)) return java.util.Optional.empty();
        Properties properties = new Properties();
        try (var in = Files.newInputStream(SIGNED_TEXTURES)) {
            properties.load(in);
        } catch (Exception failure) {
            KindredMod.LOGGER.warn("Kindred signed skin textures could not be read; vanilla clients will see default skins", failure);
            return java.util.Optional.empty();
        }
        String value = properties.getProperty(key + ".value", "").trim();
        String signature = properties.getProperty(key + ".signature", "").trim();
        if (value.isEmpty() || signature.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(new Property(TEXTURES, value, signature));
    }
}
