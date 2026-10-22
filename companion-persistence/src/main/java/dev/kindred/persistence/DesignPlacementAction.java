package dev.kindred.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable action extracted from accepted design geometry; it carries no authority to mutate a world. */
public record DesignPlacementAction(String designId, long revision, String actionId, int x, int y, int z,
                                    String blockId, String side, String dependencyDigest) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern BLOCK = Pattern.compile("minecraft:[a-z0-9_./-]{1,128}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    public DesignPlacementAction {
        if (designId == null || !ID.matcher(designId).matches() || actionId == null || !ID.matcher(actionId).matches()
                || revision < 1 || blockId == null || !BLOCK.matcher(blockId).matches() || side == null || !ID.matcher(side).matches()
                || dependencyDigest == null || !SHA.matcher(dependencyDigest).matches()) throw new IllegalArgumentException("Invalid design placement action");
        Objects.requireNonNull(actionId);
    }
}
