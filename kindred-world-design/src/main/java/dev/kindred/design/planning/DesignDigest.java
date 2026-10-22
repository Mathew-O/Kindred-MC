package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Exact artifact binding. Not rotation-invariant or a semantic originality test. */
public final class DesignDigest {
    private DesignDigest() {}

    public static String sha256(Design design) {
        StringBuilder canonical = new StringBuilder("kindred-design-artifact-v1\n");
        canonical.append(design.designId()).append('\n').append(design.revision()).append('\n')
                .append(design.observationId()).append('\n').append(design.observationRevision()).append('\n')
                .append(design.generatorVersion()).append('\n');
        Brief brief = design.brief();
        canonical.append(brief.projectId()).append('\n').append(brief.worldId()).append('\n')
                .append(brief.dimension()).append('\n').append(brief.site().min().key()).append('\n')
                .append(brief.site().max().key()).append('\n').append(brief.floorOrigin().key()).append('\n')
                .append(brief.accessAnchor().key()).append('\n').append(brief.rotation()).append('\n')
                .append(brief.seed()).append('\n').append(brief.maxPlacements()).append('\n');
        design.voxels().stream().sorted(Comparator.comparing(Voxel::position)
                        .thenComparing(v -> v.material().name()).thenComparing(v -> v.phase().name()))
                .forEach(v -> canonical.append("V:").append(v.position().key()).append(':')
                        .append(v.material().itemId()).append(':').append(v.phase()).append('\n'));
        design.requiredAir().stream().sorted().forEach(p -> canonical.append("A:").append(p.key()).append('\n'));
        design.ports().stream().sorted(Comparator.comparing(Port::portId).thenComparing(Port::feet))
                .forEach(p -> canonical.append("P:").append(p.portId()).append(':').append(p.feet().key()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 unavailable", impossible);
        }
    }
}
