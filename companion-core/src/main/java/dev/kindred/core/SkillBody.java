package dev.kindred.core;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The adapter-side implementation of one skill, driven one step at a time on the server thread under a
 * current lease. It reports progress and checkpoint boundaries; it never decides success for the runner
 * beyond reporting the postcondition it actually observed.
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
        public static Step running(Map<String, String> progress, boolean checkpoint) { return new Step(Status.RUNNING, progress, checkpoint, null, false); }
        public static Step completed(Map<String, String> progress) { return new Step(Status.COMPLETED, progress, true, null, false); }
        public static Step blocked(String reason, boolean permanent) { return new Step(Status.BLOCKED, Map.of(), true, reason, permanent); }
    }

    /** Advance one tick. On resume or after a local recovery the previous checkpoint is supplied; refresh observations from it. */
    Step tick(Map<String, String> arguments, Optional<Checkpoint> resumeFrom, boolean freshStart);

    /** Stop motion now; may be called more than once and after a stale lease. */
    void halt();

    /** True once no native operation is still controlling the body. */
    boolean settled();
}
