package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;

import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** Shared GameTest helpers for deterministic admission IO under parallel load. */
final class NativeGameTestSupport {
    private NativeGameTestSupport() { }

    static UnaryOperator<ActionAdmission.Writer> synchronousAdmissionWriter() {
        return delegate -> new ActionAdmission.Writer() {
            @Override
            public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                return CompletableFuture.completedFuture(delegate.prepare(intent).join());
            }

            @Override
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long revision) {
                return CompletableFuture.completedFuture(delegate.transition(next, revision).join());
            }
        };
    }
}