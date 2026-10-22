package dev.kindred.design.model;

import dev.kindred.design.model.DesignModel.*;
import java.util.*;

/** Immutable, bounded design intent. It deliberately contains no world authority. */
public final class SemanticDesign {
    private SemanticDesign() {}

    public enum NodeKind { WORLD, REGION, SETTLEMENT, DISTRICT, SCENE, BUILDING, ROUTE, COURTYARD, ROOM, STAGING }
    public enum Relation { CONTAINS, PART_OF, SERVES, REQUIRES_ACCESS_TO, CONNECTS_PORT, JOINS_ROUTE,
        FACES, SHARES_STYLE_WITH, DEPENDS_ON, REQUIRES_TEMPORARY_ACCESS, DERIVED_FROM_REFERENCE,
        OBSERVED_BY, REPLACES_REVISION }
    public enum RevisionStatus { DRAFT, VALIDATING, VALIDATED, ACCEPTED, SUPERSEDED }

    public record Node(String id, NodeKind kind, String function, boolean tombstone) {
        public Node { id = DesignModel.id(id); Objects.requireNonNull(kind); function = DesignModel.id(function); }
    }
    public record Edge(String sourceId, Relation relation, String targetId) {
        public Edge { sourceId = DesignModel.id(sourceId); Objects.requireNonNull(relation); targetId = DesignModel.id(targetId); }
    }
    public record StyleProfile(String id, Map<String, Material> roles, int minWidth, int maxWidth,
                               Set<String> motifs, Set<Material> substitutions) {
        public StyleProfile {
            id = DesignModel.id(id); roles = Map.copyOf(roles); motifs = Set.copyOf(motifs);
            substitutions = Set.copyOf(substitutions);
            if (minWidth < 1 || maxWidth < minWidth || maxWidth > 64 || roles.size() > 16 || motifs.size() > 16)
                throw new IllegalArgumentException("Invalid bounded style profile");
            roles.forEach((role, material) -> { DesignModel.id(role); Objects.requireNonNull(material); });
        }
    }
    public record Revision(String revisionId, long revision, String parentRevisionId, String observationId,
                           long observationRevision, String generatorVersion, RevisionStatus status,
                           Map<String, Node> nodes, Set<Edge> edges, StyleProfile style) {
        public Revision {
            revisionId = DesignModel.id(revisionId); parentRevisionId = parentRevisionId == null ? null : DesignModel.id(parentRevisionId);
            observationId = DesignModel.id(observationId); generatorVersion = DesignModel.id(generatorVersion);
            Objects.requireNonNull(status); nodes = Map.copyOf(nodes); edges = Set.copyOf(edges); Objects.requireNonNull(style);
            if (revision < 1 || observationRevision < 0 || nodes.isEmpty() || nodes.size() > 1024 || edges.size() > 4096)
                throw new IllegalArgumentException("Invalid bounded revision");
            nodes.forEach((id, node) -> { if (!id.equals(node.id())) throw new IllegalArgumentException("Node key mismatch"); });
            for (Edge edge : edges) {
                if (!nodes.containsKey(edge.sourceId()) || !nodes.containsKey(edge.targetId()))
                    throw new IllegalArgumentException("Edge refers to unknown node");
            }
        }
        public Revision withStatus(RevisionStatus next) {
            if (status == RevisionStatus.ACCEPTED && next != RevisionStatus.SUPERSEDED)
                throw new IllegalStateException("Accepted revisions are immutable");
            return new Revision(revisionId, revision, parentRevisionId, observationId, observationRevision,
                    generatorVersion, next, nodes, edges, style);
        }
    }

    /** Enforces stable graph identity and explicit tombstones across revisions. */
    public static Revision nextRevision(Revision prior, String id, String observationId, long observationRevision,
                                        String generatorVersion, Map<String, Node> nodes, Set<Edge> edges, StyleProfile style) {
        Objects.requireNonNull(prior);
        for (Map.Entry<String, Node> entry : prior.nodes().entrySet()) {
            Node replacement = nodes.get(entry.getKey());
            if (replacement == null) throw new IllegalArgumentException("Removed node requires tombstone: " + entry.getKey());
            if (replacement.kind() != entry.getValue().kind()) throw new IllegalArgumentException("Node identity changed: " + entry.getKey());
        }
        return new Revision(id, Math.addExact(prior.revision(), 1), prior.revisionId(), observationId,
                observationRevision, generatorVersion, RevisionStatus.DRAFT, nodes, edges, style);
    }
}
