package dev.kindred.fabric.audit;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.kindred.fabric.FixtureBounds;
import net.minecraft.test.GameTestState;
import net.minecraft.test.TestRunContext;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Each test's tick, its start, its steps and what runs as it ends among them, marked as that test's for
 * {@link FixtureBounds} (F-22c75b); the server's own placing of the test, in that tick, left out.
 */
@Mixin(GameTestState.class)
abstract class GameTestStateMixin {
    @WrapMethod(method = "tick")
    private void kindred$asItsOwn(TestRunContext run, Operation<Void> original) {
        var self = (GameTestState) (Object) this;
        var outer = FixtureBounds.enter(self);
        try {
            original.call(run);
        } finally {
            FixtureBounds.exit(self, outer);
        }
    }

    /** The server's own placing and clearing of the test's area, not the fixture's. */
    @WrapMethod(method = "initialize")
    private boolean kindred$placing(Operation<Boolean> original) {
        FixtureBounds.placing(true);
        try {
            return original.call();
        } finally {
            FixtureBounds.placing(false);
        }
    }
}
