package dev.kindred.fabric;

import dev.kindred.core.construction.BlockSpec;
import dev.kindred.core.construction.Blueprint;
import dev.kindred.core.construction.Schematics;
import net.minecraft.SharedConstants;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * In plain terms: the building plans a server's players may build, kept as files the server's administrator
 * puts in one folder. Each may come with a note of who made it, where it came from and under what terms,
 * which is kept and said when a build starts. Nothing is downloaded from anywhere.
 *
 * Technical: The schematics a server's players may build (M-05): vanilla structure files ({@code .nbt}) and
 * Sponge schematics ({@code .schem}) put in {@code config/kindred/schematics} by the operator. Beside each, a
 * {@code <name>.license} file may say who made it, where it came from and under what terms ({@code creator=},
 * {@code source=}, {@code license=}); that is kept with the blueprint and said when a build starts (D8).
 * Nothing is fetched from anywhere. Files are read off the game thread.
 */
final class SchematicLibrary {
    /** Largest file read: a schematic of the largest blueprint is far smaller. */
    static final long MAX_FILE = 8L << 20;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.+-]{1,64}");

    private final Path directory;

    /** The plans kept in the given folder. */
    SchematicLibrary(Path directory) {
        this.directory = directory;
    }

    Path directory() {
        return directory;
    }

    /** The schematics there, by name (the file name without its extension). */
    CompletableFuture<List<String>> names() {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isDirectory(directory)) return List.of();
            try (Stream<Path> files = Files.list(directory)) {
                return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".nbt") || n.endsWith(".schem"))
                        .map(n -> n.substring(0, n.lastIndexOf('.'))).sorted().toList();
            } catch (IOException failure) {
                return List.of();
            }
        });
    }

    /** A schematic read into a blueprint, with what was left out; the blocks are checked against the game's own. */
    record Loaded(Blueprint blueprint, List<String> notes) { }

    /** Reads the schematic {@code name}, off the game thread; fails with the reason a player is told. */
    CompletableFuture<Loaded> load(String name) {
        return CompletableFuture.supplyAsync(() -> {
            if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Not a schematic name: " + name);
            Path file = null;
            for (String extension : List.of(".nbt", ".schem")) {
                Path candidate = directory.resolve(name + extension);
                if (Files.isRegularFile(candidate)) { file = candidate; break; }
            }
            if (file == null) throw new IllegalArgumentException("No schematic called " + name + " in " + directory);
            try {
                if (Files.size(file) > MAX_FILE) throw new IllegalArgumentException(name + " is larger than " + (MAX_FILE >> 20) + " MB");
                byte[] data = Files.readAllBytes(file);
                var terms = new Properties();
                Path license = directory.resolve(name + ".license");
                if (Files.isRegularFile(license)) {
                    try (Reader reader = Files.newBufferedReader(license)) { terms.load(reader); }
                }
                var read = Schematics.read(data, name, terms.getProperty("source", "file:" + file.getFileName()),
                        terms.getProperty("creator", ""), terms.getProperty("license", ""));
                var notes = new ArrayList<>(read.notes());
                if (read.dataVersion() > SharedConstants.getGameVersion().getSaveVersion().getId()) {
                    notes.add("Saved by a newer Minecraft (data version " + read.dataVersion() + "); blocks it does not know are left out");
                }
                return new Loaded(known(read.blueprint(), notes), List.copyOf(notes));
            } catch (IOException failure) {
                throw new IllegalArgumentException(name + " could not be read: " + failure.getMessage());
            }
        });
    }

    /**
     * The blueprint with only blocks this game knows in states it accepts: a block or a state from another version is
     * left out and noted, never guessed at.
     */
    static Blueprint known(Blueprint blueprint, List<String> notes) {
        var checked = new HashMap<BlockSpec, Boolean>();
        var kept = new ArrayList<Blueprint.Block>();
        var dropped = new java.util.TreeSet<String>();
        for (var b : blueprint.blocks()) {
            boolean ok = checked.computeIfAbsent(b.spec(), spec -> {
                try {
                    BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), spec.toString(), false);
                    return true;
                } catch (Exception unknown) {
                    return false;
                }
            });
            if (ok) kept.add(b);
            else dropped.add(b.spec().toString());
        }
        dropped.stream().limit(8).forEach(spec -> notes.add("Not a block this game knows: " + spec));
        if (dropped.size() > 8) notes.add("... and " + (dropped.size() - 8) + " more");
        if (kept.isEmpty()) throw new IllegalArgumentException(blueprint.name() + " has no block this game knows");
        return new Blueprint(blueprint.name(), blueprint.source(), blueprint.creator(), blueprint.license(), kept, blueprint.clear());
    }

    /** How the blueprint is credited when a build starts. */
    static String credit(Blueprint blueprint) {
        var parts = new ArrayList<String>();
        if (!blueprint.creator().isBlank()) parts.add("by " + blueprint.creator());
        parts.add(blueprint.license().isBlank() ? "no license recorded" : "under " + blueprint.license());
        if (!blueprint.source().isBlank()) parts.add("from " + blueprint.source());
        return String.join(", ", parts);
    }
}
