package dev.kindred.core;

/**
 * In plain terms: the plug between the brain and a body in the game world. Through it the brain starts a job
 * on the body, cancels it at once (stopping movement, digging and item use), and asks whether the body has
 * fully stopped.
 *
 * Technical: Native body boundary. Every method runs on the arbiter's owning server thread and must
 * return promptly, without waiting for IO. cancel must synchronously revoke navigation,
 * digging and item use. A false isSettled result keeps the replacement barrier closed.
 * Adapter callbacks must return to the server thread and pass executeIfCurrent before acting.
 */
public interface BodyAdapter {
    void start(ActionLease lease);
    void cancel(ActionLease lease);
    boolean isSettled(ActionLease lease);
}
