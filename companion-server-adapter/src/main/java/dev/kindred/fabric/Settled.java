package dev.kindred.fabric;

/**
 * What the evidence says an action came to (F-25c6c, F-31g1): done, not done (nothing it owned lost, nothing changed),
 * or held, the evidence not enough to say, for reconciliation. Evidence decides; nothing is replayed.
 */
enum Settled { DONE, NOT_DONE, HELD }
