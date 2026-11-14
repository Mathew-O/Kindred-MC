package dev.kindred.fabric.audit;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.kindred.fabric.FixtureBounds;
import net.minecraft.block.Block;
import net.minecraft.test.GameTestState;
import org.spongepowered.asm.mixin.Mixin;

/** The test server's marks on a test, its beacon, glass and lectern, left out of {@link FixtureBounds} (F-22c75b). */
@Mixin(targets = "net.minecraft.test.StructureTestListener")
abstract class StructureTestListenerMixin {
    @WrapMethod(method = {"visualizeTest", "placeAboveBeacon"})
    private static void kindred$marking(GameTestState test, Block block, Operation<Void> original) {
        FixtureBounds.placing(true);
        try {
            original.call(test, block);
        } finally {
            FixtureBounds.placing(false);
        }
    }

    @WrapMethod(method = "createTestOutputLectern")
    private static void kindred$lectern(GameTestState test, String text, Operation<Void> original) {
        FixtureBounds.placing(true);
        try {
            original.call(test, text);
        } finally {
            FixtureBounds.placing(false);
        }
    }
}
