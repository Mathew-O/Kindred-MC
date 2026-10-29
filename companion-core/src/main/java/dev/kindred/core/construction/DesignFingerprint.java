package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a design is new (E-30): a build's shape as a fingerprint that turning it, moving it or building it in other
 * materials does not change, and a likeness between two shapes that another window or a moved door hardly moves, so
 * that a near copy is told from a new design. The shape is the cells a build fills, whatever fills them; it is compared
 * at each of the four quarter turns, aligned on its footprint's corner. Pure.
 *
 * <p>A {@link Registry} keeps each player's reserved designs: a design is reserved before it is built, and refused
 * when it is a near copy of one another player has reserved. Its reservations are written and read back as text, for
 * keeping with the player's memory.
 */
public final class DesignFingerprint {
    /** Two shapes at least this alike, at their best turn, are one design. */
    public static final double NEAR_COPY = 0.9;

    private final Set<Pos> cells;
    private final String hash;

    private DesignFingerprint(Set<Pos> cells) {
        this.cells = Set.copyOf(cells);
        this.hash = canonicalHash(this.cells);
    }

    /** The fingerprint of {@code blueprint}: the cells its blocks fill. */
    public static DesignFingerprint of(Blueprint blueprint) {
        return of(blueprint.blocks());
    }

    /** The fingerprint of blocks as placed (turned and moved, as {@link Blueprint#placed} gives them). */
    public static DesignFingerprint of(List<Blueprint.Block> blocks) {
        var filled = new HashSet<Pos>();
        for (var b : blocks) filled.add(b.at());
        return new DesignFingerprint(normal(filled));
    }

    /** The fingerprint of cells given as they are, for reading back. */
    static DesignFingerprint ofCells(Set<Pos> cells) {
        return new DesignFingerprint(normal(cells));
    }

    /** The same at every turn and place: the least of its four quarter turns' encodings, hashed. */
    public String hash() { return hash; }

    public int size() { return cells.size(); }

    /** How alike two shapes are at the best of the four turns: the cells both fill over the cells either fills. */
    public double likeness(DesignFingerprint other) {
        double best = 0;
        var turned = cells;
        for (int turn = 0; turn < 4; turn++) {
            int both = 0;
            for (Pos p : turned) if (other.cells.contains(p)) both++;
            int either = turned.size() + other.cells.size() - both;
            best = Math.max(best, either == 0 ? 1 : (double) both / either);
            turned = normal(turn(turned));
        }
        return best;
    }

    /** Whether {@code other} is this design again, or nearly: turned, moved, in other materials or with a window more. */
    public boolean nearCopyOf(DesignFingerprint other) {
        return hash.equals(other.hash) || likeness(other) >= NEAR_COPY;
    }

    private static Set<Pos> turn(Set<Pos> cells) {
        var out = new HashSet<Pos>();
        for (Pos p : cells) out.add(new Pos(-p.z(), p.y(), p.x()));
        return out;
    }

    /** Moved so its least corner is at the origin. */
    private static Set<Pos> normal(Set<Pos> cells) {
        if (cells.isEmpty()) return Set.of();
        int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
        for (Pos p : cells) {
            x = Math.min(x, p.x());
            y = Math.min(y, p.y());
            z = Math.min(z, p.z());
        }
        var out = new HashSet<Pos>();
        for (Pos p : cells) out.add(new Pos(p.x() - x, p.y() - y, p.z() - z));
        return out;
    }

    private static String encode(Set<Pos> cells) {
        var sorted = new ArrayList<>(cells);
        sorted.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y()) : a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        var out = new StringBuilder();
        for (Pos p : sorted) out.append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(';');
        return out.toString();
    }

    private static String canonicalHash(Set<Pos> cells) {
        String least = null;
        var turned = cells;
        for (int turn = 0; turn < 4; turn++) {
            String e = encode(turned);
            if (least == null || e.compareTo(least) < 0) least = e;
            turned = normal(turn(turned));
        }
        long h = 1125899906842597L;
        for (int i = 0; i < least.length(); i++) h = 31 * h + least.charAt(i);
        return String.format(Locale.ROOT, "%016x", h);
    }

    /** A reserved design: whose, under what name, and its shape. */
    public record Reservation(UUID owner, String name, DesignFingerprint design) {
        public Reservation {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(name);
            Objects.requireNonNull(design);
        }
    }

    /** Each player's reserved designs, and the refusal of another's near copy. */
    public static final class Registry {
        private final Map<String, Reservation> reserved = new LinkedHashMap<>();

        /**
         * Reserves {@code design} for {@code owner}, or refuses it with the reservation it nearly copies: another
         * player's design (its own again is its own, and kept once).
         */
        public Optional<Reservation> reserve(UUID owner, String name, DesignFingerprint design) {
            for (var r : reserved.values()) {
                if (!r.owner().equals(owner) && r.design().nearCopyOf(design)) return Optional.of(r);
            }
            reserved.putIfAbsent(owner + "|" + design.hash(), new Reservation(owner, name, design));
            return Optional.empty();
        }

        /** Its reservation of {@code design} let go (a build given up or pulled down). */
        public void release(UUID owner, DesignFingerprint design) {
            reserved.remove(owner + "|" + design.hash());
        }

        public List<Reservation> reservations() { return List.copyOf(reserved.values()); }

        /** The reservations as text: a line each, its owner, its name and its cells. */
        public String write() {
            var out = new StringBuilder();
            for (var r : reserved.values()) {
                out.append(r.owner()).append('\t').append(r.name().replace('\t', ' ').replace('\n', ' ')).append('\t').append(encode(r.design().cells)).append('\n');
            }
            return out.toString();
        }

        /** Reservations read back from {@link #write}'s text; a line that is not one is skipped. */
        public static Registry read(String text) {
            var registry = new Registry();
            for (String line : text.split("\n")) {
                String[] parts = line.split("\t");
                if (parts.length != 3) continue;
                try {
                    var cells = new HashSet<Pos>();
                    for (String cell : parts[2].split(";")) {
                        if (cell.isBlank()) continue;
                        String[] c = cell.split(",");
                        cells.add(new Pos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
                    }
                    var design = ofCells(cells);
                    UUID owner = UUID.fromString(parts[0]);
                    registry.reserved.put(owner + "|" + design.hash(), new Reservation(owner, parts[1], design));
                } catch (IllegalArgumentException notOne) {
                    // Skipped.
                }
            }
            return registry;
        }
    }

}
