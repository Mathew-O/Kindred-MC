package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.SkillBody;
import net.minecraft.util.math.BlockPos;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: the common shape of actions that move items (equipping, crafting, smelting and so on): each
 * is written down before it happens and checked after, so an item is never lost or duplicated, and one cut off
 * halfway (say by a server crash) is never blindly done again.
 *
 * Technical: Shared observation/flush contract for skills using the existing durable action ledger.
 */
abstract class NativeRecordedAction implements SkillBody {
    /** Whether the body may do this kind of thing at this block as part of the action. */
    abstract boolean permits(NativePlayerBody.Intent intent, BlockPos target);
    /** Whether it is unclear how the action ended, so it needs checking before anything else. */
    abstract boolean uncertain();
    /** What has been written down about the action so far. */
    abstract ActionRecord snapshot();
    /** Where the action is in its life (prepared, running, verified...), for status reports. */
    abstract String phase();
    /** Finishes the record of the action, in the background. */
    abstract CompletableFuture<ActionRecord> close();
}
