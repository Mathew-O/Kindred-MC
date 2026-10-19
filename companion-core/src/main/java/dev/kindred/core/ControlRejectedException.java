package dev.kindred.core;

public final class ControlRejectedException extends IllegalStateException {
    public enum Reason { STOPPED, BUSY, CANCELLATION_PENDING, FAULTED, REMOVED }

    private final Reason reason;

    public ControlRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
