package dev.kindred.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client half of the skin selector: the skins the server has put on AI players' bodies, and the ones it offers
 * while the selector is open, each held as a texture. Render thread only.
 */
@Environment(EnvType.CLIENT)
public final class KindredClientSkins {
    /** A skin held as a texture, with its arm model and whether it is the old 64×32 layout. */
    record Held(String key, Identifier texture, boolean slim, boolean legacy) {
        SkinTextures textures() {
            return new SkinTextures(texture, null, null, null, slim ? SkinTextures.Model.SLIM : SkinTextures.Model.WIDE, true);
        }
    }

    private static final Map<UUID, Held> bodies = new HashMap<>();
    private static final Map<String, Held> catalog = new LinkedHashMap<>();
    private static int catalogTotal = -1;

    private KindredClientSkins() { }

    /** The skin the server put on this body, if any. */
    static Held forBody(UUID body) {
        return body == null ? null : bodies.get(body);
    }

    /** For the skin mixin: the textures of the skin the server put on this body, or null. */
    public static SkinTextures texturesFor(UUID body) {
        Held held = forBody(body);
        return held == null ? null : held.textures();
    }

    static void bind(UUID body, String key, boolean slim, byte[] png) {
        Identifier id = Identifier.of("kindred", "skins/body/" + body);
        Held old = bodies.remove(body);
        if (old != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(old.texture());
        if (png.length == 0) return;
        Held held = load(id, key, slim, png);
        if (held != null) bodies.put(body, held);
    }

    /** Starts a fresh catalog when the selector opens. */
    static void clearCatalog() {
        for (Held held : catalog.values()) MinecraftClient.getInstance().getTextureManager().destroyTexture(held.texture());
        catalog.clear();
        catalogTotal = -1;
    }

    static void addToCatalog(String key, boolean slim, byte[] png, int total) {
        catalogTotal = total;
        if (total == 0 || key.isEmpty() || !key.matches("[a-z0-9_-]{1,32}")) return;
        Held old = catalog.remove(key);
        if (old != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(old.texture());
        Held held = load(Identifier.of("kindred", "skins/catalog/" + key), key, slim, png);
        if (held != null) catalog.put(key, held);
    }

    static List<Held> catalog() { return new ArrayList<>(catalog.values()); }

    /** Skins the server said it has: -1 before it answered. */
    static int catalogTotal() { return catalogTotal; }

    /** Leaving a server: nothing it sent is kept. */
    static void reset() {
        var textures = MinecraftClient.getInstance().getTextureManager();
        for (Held held : bodies.values()) textures.destroyTexture(held.texture());
        bodies.clear();
        clearCatalog();
    }

    private static Held load(Identifier id, String key, boolean slim, byte[] png) {
        try {
            NativeImage image = NativeImage.read(png);
            if (image.getWidth() != 64 || image.getHeight() != 64 && image.getHeight() != 32) {
                image.close();
                return null;
            }
            boolean legacy = image.getHeight() == 32;
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
            return new Held(key, id, slim, legacy);
        } catch (java.io.IOException | RuntimeException unreadable) {
            KindredMod.LOGGER.warn("Kindred skin {} from the server could not be read", key);
            return null;
        }
    }

    /**
     * Draws a skin from the front, as a player stands: head, body, arms and legs with their outer layers, {@code unit}
     * pixels to a skin pixel. The figure is 16 units wide and 32 high.
     */
    static void drawFront(DrawContext context, Identifier texture, boolean slim, boolean legacy, int x, int y, int unit) {
        int textureHeight = legacy ? 32 : 64;
        int arm = slim ? 3 : 4;
        // Base layer.
        part(context, texture, x + 4 * unit, y, 8, 8, 8, 8, unit, textureHeight);                       // head
        part(context, texture, x + 4 * unit, y + 8 * unit, 8, 12, 20, 20, unit, textureHeight);         // body
        part(context, texture, x + (4 - arm) * unit, y + 8 * unit, arm, 12, 44, 20, unit, textureHeight); // right arm
        part(context, texture, x + 4 * unit, y + 20 * unit, 4, 12, 4, 20, unit, textureHeight);         // right leg
        if (legacy) {
            // The old layout has one arm and one leg, mirrored for the other side.
            mirrored(context, texture, x + 12 * unit, y + 8 * unit, arm, 12, 44, 20, unit);
            mirrored(context, texture, x + 8 * unit, y + 20 * unit, 4, 12, 4, 20, unit);
        } else {
            part(context, texture, x + 12 * unit, y + 8 * unit, arm, 12, 36, 52, unit, 64);             // left arm
            part(context, texture, x + 8 * unit, y + 20 * unit, 4, 12, 20, 52, unit, 64);               // left leg
        }
        // Outer layer.
        RenderSystem.enableBlend();
        part(context, texture, x + 4 * unit, y, 8, 8, 40, 8, unit, textureHeight);                      // hat
        if (!legacy) {
            part(context, texture, x + 4 * unit, y + 8 * unit, 8, 12, 20, 36, unit, 64);                // jacket
            part(context, texture, x + (4 - arm) * unit, y + 8 * unit, arm, 12, 44, 36, unit, 64);     // right sleeve
            part(context, texture, x + 12 * unit, y + 8 * unit, arm, 12, 52, 52, unit, 64);            // left sleeve
            part(context, texture, x + 4 * unit, y + 20 * unit, 4, 12, 4, 36, unit, 64);                // right trouser
            part(context, texture, x + 8 * unit, y + 20 * unit, 4, 12, 4, 52, unit, 64);                // left trouser
        }
        RenderSystem.disableBlend();
    }

    private static void part(DrawContext context, Identifier texture, int x, int y, int w, int h, int u, int v, int unit, int textureHeight) {
        context.drawTexture(texture, x, y, w * unit, h * unit, u, v, w, h, 64, textureHeight);
    }

    private static void mirrored(DrawContext context, Identifier texture, int x, int y, int w, int h, int u, int v, int unit) {
        context.drawTexture(texture, x, y, w * unit, h * unit, u + w, v, -w, h, 64, 32);
    }
}
