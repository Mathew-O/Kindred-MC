package dev.kindred.core;

/**
 * In plain terms: the error raised when a command to an AI player is refused, with the reason: it was
 * stopped, is busy, is still cancelling, has a fault, or was removed.
 */
public final class ControlRejectedException extends IllegalStateException {
    public enum Reason { STOPPED, BUSY, CANCELLATION_PENDING, FAULTED, REMOVED }

    private final Reason reason;

    /** A refusal with its reason and a message for the player. */
    public ControlRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Why the command was refused. */
    public Reason reason() {
        return reason;
    }
}
