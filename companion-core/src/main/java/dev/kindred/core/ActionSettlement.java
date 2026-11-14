package dev.kindred.core;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * In plain terms: after a crash, an AI player's action whose outcome was never recorded is settled from what can still be
 * seen: whether the block it was digging is still there, and whether what it holds is exactly what it held when it began.
 * Only when both say the same thing is it settled without a person: the block still there and its things as they were,
 * not done; the block gone and its things as they were (the drop left on the ground), done. Anything else waits for an
 * operator to choose, and the choice is recorded with who made it and what was seen. Nothing is ever done again and no
 * item is ever made: settling writes the action's outcome, and only that.
 *
 * Technical: F-31g1. Pure: the adapter observes the block at the action's precondition target and the fingerprint of the
 * player's native save, and this decides. Only a {@code native_break} has an automatic rule; its precondition text is
 * {@code "<block id> at <x>, <y>, <z>"} and its before-evidence carries {@code sha256=<hex>} of the inventory, computed by
 * the adapter's {@code InventoryEvidence}. Comparing fingerprints is exact (slots, counts and components, wear included).
 */
public final class ActionSettlement {
    private ActionSettlement() { }

    /** A break, settled from its block and the inventory's fingerprint. */
    public static final String BREAK = "native_break";
    /** A pillar step, settled by its own rule ({@code NativePillarAction.settleFromEvidence}): not done, or held. */
    public static final String PILLAR = "native_pillar";

    private static final Pattern TARGET = Pattern.compile("^([a-z0-9_.-]+:[a-z0-9_/.-]+) at (-?\\d+), (-?\\d+), (-?\\d+)$");
    private static final Pattern HASH = Pattern.compile("sha256=([0-9a-f]{64})");
    private static final Pattern PILLAR_CELL = Pattern.compile("^block=([a-z0-9_.-]+:[a-z0-9_/.-]+); pos=(-?\\d+), (-?\\d+), (-?\\d+)(;.*)?$");

    /** A block an action meant to change, as its preconditions name it. */
    public record Target(String block, int x, int y, int z) {
        public Target {
            Objects.requireNonNull(block);
        }
        public String describe() { return block + " at " + x + ", " + y + ", " + z; }
    }

    public enum Verdict { DONE, NOT_DONE, AMBIGUOUS }

    /** What was decided, and the evidence that decided it, in words for the operator and the Record. */
    public record Decision(Verdict verdict, String evidence) {
        public Decision {
            Objects.requireNonNull(verdict);
            Objects.requireNonNull(evidence);
        }
    }

    /** The block a {@code native_break}'s preconditions name, or none for any other text. */
    public static Optional<Target> target(String kind, String preconditions) {
        if (!BREAK.equals(kind) || preconditions == null) return Optional.empty();
        var match = TARGET.matcher(preconditions.trim());
        if (!match.matches()) return Optional.empty();
        return Optional.of(new Target(match.group(1), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)),
                Integer.parseInt(match.group(4))));
    }

    /**
     * The cell a {@code native_pillar} step's preconditions name and the block it puts there ({@code block=<id>; pos=x, y,
     * z; setupRevision=n}), or none for any other text.
     */
    public static Optional<Target> pillarCell(String kind, String preconditions) {
        if (!PILLAR.equals(kind) || preconditions == null) return Optional.empty();
        var match = PILLAR_CELL.matcher(preconditions.trim());
        if (!match.matches()) return Optional.empty();
        return Optional.of(new Target(match.group(1), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)),
                Integer.parseInt(match.group(4))));
    }

    /**
     * A break's rule (F-31g1), shared by {@link #decide} and the adapter's {@code NativeBreakAction.settleFromEvidence}: with
     * the inventory's fingerprint exactly as before the break, not done if its block still stands and done if it is gone;
     * anything else, or a fingerprint missing on either side, is for an operator.
     */
    public static Verdict breakRule(boolean blockPresent, String beforeEvidence, String observedEvidence) {
        var before = fingerprint(beforeEvidence);
        var now = fingerprint(observedEvidence);
        if (before.isEmpty() || now.isEmpty() || !before.get().equals(now.get())) return Verdict.AMBIGUOUS;
        return blockPresent ? Verdict.NOT_DONE : Verdict.DONE;
    }

    /** The inventory fingerprint in an evidence text, or none. */
    public static Optional<String> fingerprint(String evidence) {
        if (evidence == null) return Optional.empty();
        var match = HASH.matcher(evidence);
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    /**
     * Decides one unsettled action. {@code observedBlock} is the block now at the target (null when the target could not
     * be read); {@code observedEvidence} the player's native save described as {@code InventoryEvidence} describes an
     * inventory (null when the save could not be read).
     */
    public static Decision decide(ActionRecord action, String observedBlock, String observedEvidence) {
        Objects.requireNonNull(action);
        if (action.state() != ActionRecord.State.UNCERTAIN) {
            return new Decision(Verdict.AMBIGUOUS, "the action is " + action.state() + ", not uncertain");
        }
        String seen = "saved inventory " + (observedEvidence == null ? "unreadable" : observedEvidence);
        var target = target(action.kind(), action.preconditions());
        if (target.isEmpty()) {
            return new Decision(Verdict.AMBIGUOUS, "no automatic rule for " + action.kind() + " (" + action.preconditions() + "); " + seen);
        }
        String where = target.get().describe();
        if (observedBlock == null) return new Decision(Verdict.AMBIGUOUS, where + ": the block could not be read; " + seen);
        var before = fingerprint(action.beforeEvidence());
        var now = fingerprint(observedEvidence);
        boolean present = target.get().block().equals(observedBlock);
        String block = where + ": now " + observedBlock;
        if (before.isEmpty() || now.isEmpty()) {
            return new Decision(Verdict.AMBIGUOUS, block + "; no inventory fingerprint to compare (before: " + action.beforeEvidence() + "; " + seen + ")");
        }
        boolean same = before.get().equals(now.get());
        String inventory = same ? "the saved inventory exactly as before the action (sha256=" + now.get() + ")"
                : "the saved inventory differs from before the action (before sha256=" + before.get() + ", now sha256=" + now.get() + ")";
        return new Decision(breakRule(present, action.beforeEvidence(), observedEvidence), block + (present ? ", still there; " : ", gone; ") + inventory);
    }

    /** The action settled as {@code done} or not, with the evidence and who settled it in its note: never a replay. */
    public static ActionRecord settled(ActionRecord action, boolean done, String evidence, String by) {
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(by);
        String note = "Settled " + (done ? "done" : "not done") + " by " + by + " after a restart: " + evidence;
        String after = "settlement: " + evidence;
        return action.reconciled(done, clip(after), clip(note));
    }

    private static String clip(String text) {
        return text.length() > ActionRecord.MAX_TEXT ? text.substring(0, ActionRecord.MAX_TEXT) : text;
    }
}
