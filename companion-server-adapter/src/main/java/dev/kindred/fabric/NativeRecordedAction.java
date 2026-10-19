package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.SkillBody;
import net.minecraft.util.math.BlockPos;
import java.util.concurrent.CompletableFuture;

/** Shared observation/flush contract for skills using the existing durable action ledger. */
abstract class NativeRecordedAction implements SkillBody {
    abstract boolean permits(NativePlayerBody.Intent intent, BlockPos target);
    abstract boolean uncertain();
    abstract ActionRecord snapshot();
    abstract String phase();
    abstract CompletableFuture<ActionRecord> close();
}
