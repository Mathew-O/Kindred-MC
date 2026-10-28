package dev.kindred.fabric;

import dev.kindred.core.SkinFiles;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Server half of the skin selector. Operators drop skin PNGs into {@code config/kindred/skins}; whoever may adjust an
 * AI player picks one for it from the panel or with {@code /aiplayer skin}. The choice is kept per body in
 * {@code config/kindred/skin-choices.properties}, and the PNG goes to every Kindred client, which shows it on that
 * body. Vanilla clients keep seeing the default or signed skin, since they only accept Mojang-signed textures.
 * Folder and file work runs on its own thread, never on the server's.
 */
final class KindredSkins {
    static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve("kindred").resolve("skins");
    private static final Path CHOICES = FabricLoader.getInstance().getConfigDir().resolve("kindred").resolve("skin-choices.properties");
    /** A folder scanned this recently is not read again. */
    private static final long RESCAN_MS = 2_000;
    /** How often one player may ask for the whole catalog. */
    private static final long CATALOG_COOLDOWN_MS = 2_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kindred-skins");
        thread.setDaemon(true);
        return thread;
    });

    /** The last scan, by key (read on the server thread, replaced from the IO thread). */
    private static volatile Map<String, SkinFiles.Skin> library = Map.of();
    private static volatile long scannedAt;
    private static volatile String lastProblems = "";
    /** Body UUID to chosen key; server thread only. */
    private static final Map<UUID, String> choices = new HashMap<>();
    private static final Map<UUID, Long> lastCatalog = new HashMap<>();

    private KindredSkins() { }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            choices.clear();
            CompletableFuture.supplyAsync(KindredSkins::readChoices, IO).thenAcceptBoth(scan(), (saved, scanned) -> server.execute(() -> {
                saved.forEach(choices::putIfAbsent);
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sendBindings(player);
            }));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendBindings(handler.player));
        ServerPlayNetworking.registerGlobalReceiver(KindredSkinPayloads.CatalogRequest.ID, (payload, context) -> catalog(context.player()));
    }

    /** The key a body wears, or empty for its own skin. Server thread. */
    static String choiceOf(UUID body) {
        return body == null ? "" : choices.getOrDefault(body, "");
    }

    /** Keys on offer at the last scan. */
    static List<String> keys() {
        return List.copyOf(library.keySet());
    }

    /**
     * Puts the skin {@code key} on {@code body} (or its own skin back, for {@link SkinFiles#DEFAULT_KEY}), tells every
     * Kindred client and keeps the choice. The caller has already checked the right to adjust this player. Feedback
     * goes to {@code reply} at once when the skin is known; a key not seen yet is looked for in the folder first.
     */
    static void choose(MinecraftServer server, UUID body, String name, String key, Consumer<String> reply, Consumer<String> error) {
        String wanted = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        if (wanted.equals(SkinFiles.DEFAULT_KEY) || wanted.isEmpty()) {
            choices.remove(body);
            broadcast(server, new KindredSkinPayloads.Binding(body, "", false, new byte[0]));
            save();
            reply.accept(name + " wears its own skin again.");
            return;
        }
        SkinFiles.Skin known = library.get(wanted);
        if (known != null) {
            apply(server, body, name, known, reply);
            return;
        }
        scan(true).thenAccept(scanned -> server.execute(() -> {
            SkinFiles.Skin found = scanned.get(wanted);
            if (found == null) {
                error.accept("No skin \"" + wanted + "\" in " + FOLDER + ". On offer: " + (scanned.isEmpty() ? "none" : String.join(", ", scanned.keySet())) + ".");
            } else {
                apply(server, body, name, found, reply);
            }
        }));
    }

    /** Lists what the folder offers, scanning it again. */
    static void list(MinecraftServer server, Consumer<String> reply) {
        scan(true).thenAccept(scanned -> server.execute(() -> reply.accept(scanned.isEmpty()
                ? "No skins yet. Put 64x64 skin PNGs in " + FOLDER + "."
                : "Skins in " + FOLDER + ": " + String.join(", ", scanned.keySet()) + ".")));
    }

    private static void apply(MinecraftServer server, UUID body, String name, SkinFiles.Skin skin, Consumer<String> reply) {
        choices.put(body, skin.key());
        broadcast(server, binding(body, skin));
        save();
        reply.accept(name + " now wears " + skin.key() + (skin.slim() ? " (slim arms)." : "."));
    }

    private static KindredSkinPayloads.Binding binding(UUID body, SkinFiles.Skin skin) {
        return new KindredSkinPayloads.Binding(body, skin.key(), skin.slim(), skin.png());
    }

    private static void broadcast(MinecraftServer server, KindredSkinPayloads.Binding binding) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(player, KindredSkinPayloads.Binding.ID)) ServerPlayNetworking.send(player, binding);
        }
    }

    /** Every chosen skin, to one client that just joined. A skin whose file has gone is left out. */
    private static void sendBindings(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, KindredSkinPayloads.Binding.ID)) return;
        Map<String, SkinFiles.Skin> skins = library;
        choices.forEach((body, key) -> {
            SkinFiles.Skin skin = skins.get(key);
            if (skin != null) ServerPlayNetworking.send(player, binding(body, skin));
        });
    }

    private static void catalog(ServerPlayerEntity player) {
        long now = System.currentTimeMillis();
        Long last = lastCatalog.get(player.getUuid());
        if (last != null && now - last < CATALOG_COOLDOWN_MS) return;
        lastCatalog.put(player.getUuid(), now);
        MinecraftServer server = player.server;
        scan(true).thenAccept(scanned -> server.execute(() -> {
            if (player.isDisconnected() || !ServerPlayNetworking.canSend(player, KindredSkinPayloads.CatalogEntry.ID)) return;
            if (scanned.isEmpty()) {
                ServerPlayNetworking.send(player, new KindredSkinPayloads.CatalogEntry("", false, new byte[0], 0, 0));
                return;
            }
            int index = 0;
            for (SkinFiles.Skin skin : scanned.values()) {
                ServerPlayNetworking.send(player, new KindredSkinPayloads.CatalogEntry(skin.key(), skin.slim(), skin.png(), index++, scanned.size()));
            }
        }));
    }

    private static CompletableFuture<Map<String, SkinFiles.Skin>> scan() {
        return scan(false);
    }

    private static CompletableFuture<Map<String, SkinFiles.Skin>> scan(boolean fresh) {
        if (!fresh && scannedAt != 0 || System.currentTimeMillis() - scannedAt < RESCAN_MS) {
            return CompletableFuture.completedFuture(library);
        }
        return CompletableFuture.supplyAsync(KindredSkins::readFolder, IO);
    }

    /** IO thread: the folder's usable skins in name order, the folder and its read-me made if missing. */
    private static Map<String, SkinFiles.Skin> readFolder() {
        Map<String, SkinFiles.Skin> found = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        try {
            Files.createDirectories(FOLDER);
            Path readme = FOLDER.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                        Kindred AI player skins
                        Put Minecraft skin PNGs here (64x64, or the old 64x32), at most 64 KiB each.
                        The file name is the skin's name: Red Knight.png is chosen as red_knight.
                        End the name in _slim or _wide to set the arm model; otherwise it is worked out from the image.
                        Choose one for an AI player in the K panel (Skin...) or with /aiplayer skin <name> [player].
                        Players with the Kindred client mod see it; vanilla clients see the default skin.
                        """, StandardCharsets.UTF_8);
            }
            List<Path> files;
            try (var stream = Files.list(FOLDER)) {
                files = stream.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                        .filter(Files::isRegularFile)
                        .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                        .toList();
            }
            for (Path file : files) {
                if (found.size() >= SkinFiles.MAX_SKINS) {
                    problems.add(file.getFileName() + ": more than " + SkinFiles.MAX_SKINS + " skins");
                    continue;
                }
                String name = file.getFileName().toString();
                if (Files.size(file) > SkinFiles.MAX_BYTES) {
                    problems.add(name + ": larger than 64 KiB");
                    continue;
                }
                var read = SkinFiles.read(name, Files.readAllBytes(file));
                if (!read.usable()) problems.add(name + ": " + read.problem());
                else if (found.putIfAbsent(read.skin().key(), read.skin()) != null) problems.add(name + ": same name as another skin");
            }
        } catch (IOException failure) {
            KindredMod.LOGGER.warn("Kindred skins folder {} could not be read", FOLDER, failure);
            return library;
        }
        String report = String.join("; ", problems);
        if (!report.equals(lastProblems)) {
            lastProblems = report;
            if (!report.isEmpty()) KindredMod.LOGGER.warn("Kindred skins not usable: {}", report);
        }
        Map<String, SkinFiles.Skin> result = java.util.Collections.unmodifiableMap(found);
        library = result;
        scannedAt = System.currentTimeMillis();
        return result;
    }

    private static Map<UUID, String> readChoices() {
        Map<UUID, String> saved = new HashMap<>();
        if (!Files.isRegularFile(CHOICES)) return saved;
        Properties properties = new Properties();
        try (var in = Files.newInputStream(CHOICES)) {
            properties.load(in);
        } catch (IOException failure) {
            KindredMod.LOGGER.warn("Kindred skin choices {} could not be read; AI players wear their own skins", CHOICES, failure);
            return saved;
        }
        for (String body : properties.stringPropertyNames()) {
            try {
                String key = SkinFiles.keyOf(properties.getProperty(body));
                if (!key.isEmpty()) saved.put(UUID.fromString(body), key);
            } catch (IllegalArgumentException ignored) {
                // Not a body UUID; ignored.
            }
        }
        return saved;
    }

    /** Writes the choices as they are now, on the IO thread, replacing the file whole. */
    private static void save() {
        Map<UUID, String> snapshot = Map.copyOf(choices);
        IO.execute(() -> {
            Properties properties = new Properties();
            snapshot.forEach((body, key) -> properties.setProperty(body.toString(), key));
            try {
                Files.createDirectories(CHOICES.getParent());
                Path temporary = CHOICES.resolveSibling("skin-choices.properties.tmp");
                try (var out = Files.newOutputStream(temporary)) {
                    properties.store(out, "Kindred AI player skins: body UUID = skin name in config/kindred/skins");
                }
                Files.move(temporary, CHOICES, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                KindredMod.LOGGER.warn("Kindred skin choices could not be saved to {}", CHOICES, failure);
            }
        });
    }
}
