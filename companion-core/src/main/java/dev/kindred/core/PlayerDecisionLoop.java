package dev.kindred.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Local AI-player lifecycle decisions. No human-presence or companion-mode input, IO or body control.
 * Proposals still require current admission, permissions and an ActionArbiter lease at dispatch.
 */
public final class PlayerDecisionLoop {
    public enum Action { HALT, RUN_SKILL, RUN_FOLLOW, WAIT, PROPOSE_TASK }

    public record Frame(boolean usable, boolean stopped, boolean skillRunning, boolean following,
                        boolean followLeaseCurrent, boolean autonomyAdmitted, UUID currentTask,
                        List<TaskPreferences.Candidate> candidates) {
        public Frame { candidates = List.copyOf(candidates); }
    }

    public record Decision(Action action, Optional<TaskDirector.Selection> proposal, String reason) {
        public Decision {
            Objects.requireNonNull(action);
            Objects.requireNonNull(proposal);
            Objects.requireNonNull(reason);
            if ((action == Action.PROPOSE_TASK) != proposal.isPresent()) {
                throw new IllegalArgumentException("Only task proposals carry a selection");
            }
        }
    }

    private final PlayerIdentity identity;

    public PlayerDecisionLoop(PlayerIdentity identity) { this.identity = Objects.requireNonNull(identity); }
    public PlayerIdentity identity() { return identity; }

    public Decision decide(Frame frame, Personality personality) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(personality);
        if (!frame.usable() || frame.stopped()) return result(Action.HALT, "Stopped or unavailable");
        // A current skill owns its body until a checkpoint/interrupt is settled by the existing runner.
        if (frame.skillRunning()) return result(Action.RUN_SKILL, "Continue the current skill");
        if (frame.following()) return result(frame.followLeaseCurrent() ? Action.RUN_FOLLOW : Action.HALT,
                frame.followLeaseCurrent() ? "Continue the follow task" : "Follow authority expired");
        if (!frame.autonomyAdmitted()) return result(Action.WAIT,
                "Autonomous progression awaits qualified body, skills and activity admission");
        return TaskDirector.select(frame.candidates(), frame.currentTask(), personality)
                .map(selection -> new Decision(Action.PROPOSE_TASK, Optional.of(selection), selection.rationale()))
                .orElseGet(() -> result(Action.WAIT, "No executable local task; retain goals and wait for a meaningful change"));
    }

    private static Decision result(Action action, String reason) {
        return new Decision(action, Optional.empty(), reason);
    }
}
