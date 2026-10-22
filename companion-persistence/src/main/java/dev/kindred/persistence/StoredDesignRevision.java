package dev.kindred.persistence;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable design metadata. Geometry text is descriptive only and never an executable world instruction. */
public record StoredDesignRevision(
        String designId, long revision, Long parentRevision, UUID worldId, String dimension,
        String observationId, long observationRevision, String geometrySha256, String semanticSha256,
        String generatorVersion, String status, String artifactJson, String validationJson
) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public StoredDesignRevision {
        validId(designId); validId(dimension); validId(observationId); validId(generatorVersion); validId(status);
        Objects.requireNonNull(worldId); Objects.requireNonNull(parentRevision); Objects.requireNonNull(geometrySha256);
        Objects.requireNonNull(semanticSha256); Objects.requireNonNull(artifactJson); Objects.requireNonNull(validationJson);
        if (revision < 1 || parentRevision < 0 || observationRevision < 0 || !SHA256.matcher(geometrySha256).matches()
                || !SHA256.matcher(semanticSha256).matches() || artifactJson.length() > 1_048_576 || validationJson.length() > 262_144) {
            throw new IllegalArgumentException("Invalid immutable design revision");
        }
    }
    private static void validId(String value) { if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("Invalid identifier"); }
}
