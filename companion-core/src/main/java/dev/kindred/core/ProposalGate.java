package dev.kindred.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks a proposed plan against the installed skills and the fences. With no provider configured, nothing
 * is sent anywhere; a caller gets a deterministic rejection and keeps doing the chore it already has.
 */
public final class ProposalGate {
    public static final int MAX_STEPS = 6;
    private final Set<String> installedSkills;

    public ProposalGate(Set<String> installedSkills) {
        this.installedSkills = Set.copyOf(installedSkills);
    }

    public List<String> reject(List<String> steps, boolean providerConfigured, boolean revisionsCurrent, boolean expired) {
        List<String> reasons = new ArrayList<>();
        if (!providerConfigured) reasons.add("no model provider is configured");
        if (!revisionsCurrent) reasons.add("the proposal's revisions are stale");
        if (expired) reasons.add("the proposal has expired");
        if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS) reasons.add("a plan has 1.." + MAX_STEPS + " steps");
        else for (String step : steps) {
            if (step == null || !installedSkills.contains(step)) reasons.add("unknown skill " + step);
        }
        return List.copyOf(reasons);
    }
}
