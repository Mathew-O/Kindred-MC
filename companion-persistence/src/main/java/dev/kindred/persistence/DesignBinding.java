package dev.kindred.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

/** Mutable acceptance state for an otherwise immutable design revision. */
public record DesignBinding(String designId, long revision, String projectId, State state, String validationSha256, long version) {
    public enum State { DRAFT, VALIDATED, ACCEPTED, SUPERSEDED }
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    public DesignBinding {
        if (designId == null || !ID.matcher(designId).matches() || projectId == null || !ID.matcher(projectId).matches()
                || revision < 1 || version < 0 || validationSha256 == null || !SHA256.matcher(validationSha256).matches()) {
            throw new IllegalArgumentException("Invalid design binding");
        }
        Objects.requireNonNull(state);
    }
    public DesignBinding transition(State next) {
        if (!allowed(state, next)) throw new IllegalStateException("Invalid design binding transition " + state + " -> " + next);
        return new DesignBinding(designId, revision, projectId, next, validationSha256, Math.incrementExact(version));
    }
    private static boolean allowed(State from, State to) {
        return from == to || switch (from) {
            case DRAFT -> to == State.VALIDATED || to == State.SUPERSEDED;
            case VALIDATED -> to == State.ACCEPTED || to == State.DRAFT || to == State.SUPERSEDED;
            case ACCEPTED -> to == State.SUPERSEDED;
            case SUPERSEDED -> false;
        };
    }
}
