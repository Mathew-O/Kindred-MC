package dev.kindred.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which refusals of a mine leave nothing done (F-22c63): only the one before dispatch, no break issued, which a patch's
 * mining counts as a try and plans again. Every other refusal or uncertain outcome is set aside as before.
 */
final class NativeMiningRefusalTest {
    @Test
    void onlyARefusalBeforeDispatchIsCountedAsNothingIssued() {
        assertTrue(NativeMiningAction.nothingIssued(NativeMiningAction.UNBROKEN));
        assertTrue(NativeMiningAction.nothingIssued("Mining preconditions changed before dispatch; no break issued"));
        for (String other : List.of(
                "Native mining never replays a saved attempt",
                "Another task holds this resource claim",
                "Durable mining acknowledgement is uncertain; reconcile",
                "Mining close outcome uncertain",
                "Native mining outcome requires reconciliation",
                "Native mining call interrupted",
                "Native mining controls denied",
                "Mining evidence or resource claim changed",
                "Authority ended",
                "")) {
            assertFalse(NativeMiningAction.nothingIssued(other), other);
        }
        assertFalse(NativeMiningAction.nothingIssued(null));
    }
}
