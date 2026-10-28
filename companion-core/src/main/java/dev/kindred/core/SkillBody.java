package dev.kindred.core;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: what every skill (walking, mining, crafting...) looks like from the inside: it is moved
 * forward one tick at a time, reports how far it got and when it is safe to pause, can be stopped at any
 * moment, and says when the body has fully stopped.
 *
 * Technical: The adapter-side implementation of one skill, driven one step at a time on the server thread
 * under a current lease. It reports progress and checkpoint boundaries; it never decides success for the
 * runner beyond reporting the postcondition it actually observed.
 */
public interface SkillBody {
    enum Status { RUNNING, COMPLETED, BLOCKED }

    /**
     * @param progress    absolute progress facts to checkpoint (small strings)
     * @param checkpoint  true when pausing here is safe
     * @param reason      typed/short reason for BLOCKED, otherwise null
     * @param permanent   a BLOCKED reason that retrying cannot fix (permission revoked, unsupported, target gone)
     */
    record Step(Status status, Map<String, String> progress, boolean checkpoint, String reason, boolean permanent) {
        public Step {
            Objects.requireNonNull(status);
            progress = Map.copyOf(Objects.requireNonNull(progress));
            if ((status == Status.BLOCKED) != (reason != null)) throw new IllegalArgumentException("Only BLOCKED carries a reason");
        }
        /** Still working; optionally at a safe point to pause. */
        public static Step running(Map<String, String> progress, boolean checkpoint) { return new Step(Status.RUNNING, progress, checkpoint, null, false); }
        /** Finished, and the result was seen in the world. */
        public static Step completed(Map<String, String> progress) { return new Step(Status.COMPLETED, progress, true, null, false); }
        /** Could not carry on, and why; permanent means trying again will not help. */
        public static Step blocked(String reason, boolean permanent) { return new Step(Status.BLOCKED, Map.of(), true, reason, permanent); }
    }

    /** Advance one tick. On resume or after a local recovery the previous checkpoint is supplied; refresh observations from it. */
    Step tick(Map<String, String> arguments, Optional<Checkpoint> resumeFrom, boolean freshStart);

    /** Stop motion now; may be called more than once and after a stale lease. */
    void halt();

    /** True once no native operation is still controlling the body. */
    boolean settled();
}
