package dev.kindred.fabric;

import dev.kindred.core.ui.HudOptions;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: where a player's own Factions panel settings are kept, on their own computer, in
 * {@code config/kindred/hud.properties}. The server never sees them.
 *
 * Technical: The HUD options' client store (M-06l). Read once at start; written whenever a setting changes, off the
 * render thread and one save after another, so a later change is never overwritten by an earlier save. A file that
 * cannot be read leaves the defaults.
 */
final class KindredHudOptions {
    private static volatile HudOptions current = HudOptions.DEFAULT;
    private static CompletableFuture<Void> saved = CompletableFuture.completedFuture(null);

    private KindredHudOptions() {}

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kindred").resolve("hud.properties");
    }

    static HudOptions current() { return current; }

    /** The kept settings read; the defaults where there are none or they cannot be read. */
    static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        var kept = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            kept.load(reader);
        } catch (IOException | IllegalArgumentException unreadable) {
            KindredMod.LOGGER.warn("Kindred HUD settings unreadable at {}; defaults used", path);
            return;
        }
        var map = new HashMap<String, String>();
        for (String key : kept.stringPropertyNames()) map.put(key, kept.getProperty(key));
        current = HudOptions.fromMap(map);
    }

    /** The settings changed, and kept. */
    static synchronized void set(HudOptions options) {
        current = options;
        var text = new Properties();
        options.toMap().forEach(text::setProperty);
        Path path = file();
        saved = saved.handle((ignored, failure) -> null).thenRunAsync(() -> {
            try {
                Files.createDirectories(path.getParent());
                Path temp = path.resolveSibling("hud.properties.tmp");
                try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    text.store(writer, "Kindred Factions panel settings (client only)");
                }
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                KindredMod.LOGGER.warn("Kindred HUD settings not saved to {}: {}", path, failure.toString());
            }
        });
    }
}
