package dev.kindred.core;

/**
 * Native body boundary. Every method runs on the arbiter's owning server thread and must
 * return promptly, without waiting for IO. cancel must synchronously revoke navigation,
 * digging and item use. A false isSettled result keeps the replacement barrier closed.
 * Adapter callbacks must return to the server thread and pass executeIfCurrent before acting.
 */
public interface BodyAdapter {
    void start(ActionLease lease);
    void cancel(ActionLease lease);
    boolean isSettled(ActionLease lease);
}
