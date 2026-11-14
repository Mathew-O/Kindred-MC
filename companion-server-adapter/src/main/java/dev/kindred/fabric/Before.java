package dev.kindred.fabric;

/**
 * What an action knew of its player's inventory before it began, for settling it from evidence (F-25c6c, F-31g1): the
 * stacks while the action is still live, only the recorded fingerprint after a restart. One rule per action type reads
 * either, so the live close and the restart settle decide alike.
 */
record Before(String fingerprint, java.util.Optional<InventoryEvidence> stacks) {
    /** The live snapshot, stacks and all. */
    static Before live(InventoryEvidence evidence) { return new Before(evidence.description(), java.util.Optional.of(evidence)); }

    /** Only the fingerprint an action's record kept, as recovery has it after a restart. */
    static Before recorded(String beforeEvidence) { return new Before(beforeEvidence, java.util.Optional.empty()); }
}
