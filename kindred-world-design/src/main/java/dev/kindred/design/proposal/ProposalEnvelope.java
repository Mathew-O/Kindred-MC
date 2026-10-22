package dev.kindred.design.proposal;

import dev.kindred.design.model.DesignModel;
import java.time.Instant;
import java.util.*;

/** Strict envelope for optional suggestions. It is incapable of carrying world mutations or authority. */
public final class ProposalEnvelope {
    private ProposalEnvelope() {}
    public enum Tool { INSPECT_DESIGN_CONTEXT, SEARCH_DESIGN_REFERENCES, PROPOSE_SCENE, GENERATE_LAYOUT_CANDIDATES,
        GENERATE_STRUCTURE, CONNECT_PORTS, EVALUATE_RESOURCES, VALIDATE_CANDIDATE, RENDER_CANDIDATE, REVISE_CANDIDATE, SUBMIT_DESIGN_PROPOSAL }
    public record Request(String requestId, Tool tool, String projectId, String revisionId, String observationId,
                          Instant expiresAt, Map<String, String> arguments) {
        public Request {
            requestId = DesignModel.id(requestId); Objects.requireNonNull(tool); projectId = DesignModel.id(projectId);
            revisionId = DesignModel.id(revisionId); observationId = DesignModel.id(observationId); Objects.requireNonNull(expiresAt);
            arguments = Map.copyOf(arguments);
            if (arguments.size() > 16) throw new IllegalArgumentException("Too many proposal arguments");
            arguments.forEach((key, value) -> { DesignModel.id(key); if (value == null || value.length() > 512) throw new IllegalArgumentException("Invalid proposal value"); });
        }
        public void requireFresh(Instant now) { if (!expiresAt.isAfter(now)) throw new IllegalStateException("Stale proposal request"); }
    }
    public record Response(String requestId, String projectId, String revisionId, String observationId, List<String> findings) {
        public Response { requestId = DesignModel.id(requestId); projectId = DesignModel.id(projectId); revisionId = DesignModel.id(revisionId);
            observationId = DesignModel.id(observationId); findings = List.copyOf(findings);
            if (findings.size() > 16 || findings.stream().anyMatch(f -> f == null || f.length() > 512)) throw new IllegalArgumentException("Invalid bounded findings"); }
    }
    public static void bind(Request request, Response response, Instant now) {
        request.requireFresh(now);
        if (!request.requestId().equals(response.requestId()) || !request.projectId().equals(response.projectId())
                || !request.revisionId().equals(response.revisionId()) || !request.observationId().equals(response.observationId()))
            throw new IllegalArgumentException("Response binding mismatch");
    }
}
