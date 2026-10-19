package dev.kindred.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.kindred.core.CanonicalRosterSkins;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Applies canonical roster skins to native {@link GameProfile} instances at body creation. */
final class RosterGameProfiles {

    private static final String TEXTURES = "textures";

    private RosterGameProfiles() { }

    static GameProfile forNativeBody(UUID entityId, String internalProfileName, String displayName) {
        GameProfile profile = new GameProfile(entityId, internalProfileName);
        CanonicalRosterSkins.forDisplayName(displayName).ifPresent(skin -> applySkin(profile, skin));
        return profile;
    }

    private static void applySkin(GameProfile profile, CanonicalRosterSkins.SkinBinding skin) {
        if (skin.textureValue() != null && skin.textureSignature() != null) {
            profile.getProperties().put(TEXTURES, new Property(TEXTURES, skin.textureValue(), skin.textureSignature()));
        } else {
            String payload = encodeTexturesPayload(skin.mojangTextureUrl(), skin.slim());
            profile.getProperties().put(TEXTURES, new Property(TEXTURES, payload));
        }
    }

    private static String encodeTexturesPayload(String skinUrl, boolean slim) {
        String metadata = slim ? ",\"metadata\":{\"model\":\"slim\"}" : "";
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + skinUrl + "\"" + metadata + "}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}