package dev.kindred.core.wire;

import java.util.Objects;

/**
 * In plain terms: one thing a message says, with who may know it ("power 14", public; "stock: 40 iron", faction).
 *
 * Technical: A message's datum. The value is one bounded line; the visibility decides which channels may carry it
 * ({@link Wire} refuses a message whose facts are narrower than its channel's audience).
 */
public record Fact(String value, Visibility visibility) {
    public static final int MAX_CHARS = 100;

    public Fact {
        Objects.requireNonNull(visibility);
        String flat = value == null ? "" : value.replaceAll("§.?", " ").replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        value = flat.length() <= MAX_CHARS ? flat : flat.substring(0, MAX_CHARS);
    }

    public static Fact of(String value, Visibility visibility) { return new Fact(value, visibility); }
}
