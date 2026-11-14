package dev.kindred.fabric;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * In plain terms: the small files Kindred keeps beside a world (what each AI player remembers, the server's customs, the
 * model's context) written so that neither a killed server nor a host that loses power leaves one half-written, and read
 * so that one that cannot be read is put aside rather than written over unseen (F-31h).
 *
 * Technical: a write goes to a temporary file beside the target, forced to disk, moved over the target atomically where
 * the file system can, and the folder forced after, so the move itself is on disk. Without the forces a power loss can
 * leave the move on disk ahead of the data, an empty or partial file. A read that fails, or finds the file empty or
 * reading as nothing, moves the file aside to {@code <name>.corrupt-<UTC date and time>}, kept on disk and never deleted, and says so in its exception: the caller
 * starts afresh as before, and the next write no longer replaces the only copy of what it held. Every call is on a store's
 * own IO thread (the writes and the reads are queued there), never the server's: the forces never hold up a tick.
 */
final class DurableFiles {
    private DurableFiles() { }

    /** {@code text} written over {@code target} whole, on disk before this returns. */
    static void write(Path target, String text) throws IOException {
        Path folder = target.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException plain) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        forceFolder(folder);
    }

    /** The folder's entries on disk (the move), where the platform lets a folder be opened; elsewhere the move stands as made. */
    private static void forceFolder(Path folder) {
        try (var channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException notOnThisPlatform) {
            // Windows does not open a folder as a channel; NTFS journals the rename itself.
        }
    }

    /**
     * The file read as {@code type}; an empty file, one that reads as nothing, or one that cannot be read or parsed is
     * moved aside first and named in the exception.
     */
    static <T> T parse(Path file, Gson gson, Type type) throws IOException {
        return parse(file, gson, type, true);
    }

    /**
     * As above; with {@code setAside} false the file is left where it is for the caller's own setting aside (the stores'
     * and villagers' readers, which have always set theirs aside as {@code <name>.unreadable}).
     */
    static <T> T parse(Path file, Gson gson, Type type, boolean setAside) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            throw new IOException(unreadable + "; " + setAsideNote(file, setAside), unreadable);
        }
        if (text.isBlank()) throw new IOException("the file is empty; " + setAsideNote(file, setAside));
        T value;
        try {
            value = gson.fromJson(text, type);
        } catch (RuntimeException malformed) {
            throw new IOException(malformed + "; " + setAsideNote(file, setAside), malformed);
        }
        if (value == null) throw new IOException("the file reads as nothing; " + setAsideNote(file, setAside));
        return value;
    }

    /** As {@link #parse(Path, Gson, Type)}, typed by its class. */
    static <T> T parse(Path file, Gson gson, Class<T> type) throws IOException {
        return parse(file, gson, (Type) type);
    }

    /** As {@link #parse(Path, Gson, Type, boolean)}, typed by its class. */
    static <T> T parse(Path file, Gson gson, Class<T> type, boolean setAside) throws IOException {
        return parse(file, gson, (Type) type, setAside);
    }

    private static final java.time.format.DateTimeFormatter DATED = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(java.time.ZoneOffset.UTC);

    /** Where {@code file} is set aside: {@code <name>.corrupt-<UTC date and time>}, never over another. */
    static Path asideOf(Path file) {
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + DATED.format(java.time.Instant.now()));
        for (int n = 2; Files.exists(aside); n++) aside = file.resolveSibling(file.getFileName() + ".corrupt-" + DATED.format(java.time.Instant.now()) + "-" + n);
        return aside;
    }

    /** Moves {@code file} aside (when asked) and says where to, or why it stayed. */
    private static String setAsideNote(Path file, boolean setAside) {
        if (!setAside) return "left for its reader to set aside";
        Path aside = asideOf(file);
        try {
            Files.move(file, aside);
            return "set aside as " + aside.getFileName();
        } catch (IOException | RuntimeException stays) {
            return "it could not be set aside (" + stays + ")";
        }
    }
}
