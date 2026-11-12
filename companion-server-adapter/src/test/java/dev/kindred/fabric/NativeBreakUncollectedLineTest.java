package dev.kindred.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The line a break's collection gives when it ends with drops of its own uncollected (F-22c64): why it ended, how long it
 * had collected, each drop as it lay, and the body, with the heading's refusal only when that was why.
 */
final class NativeBreakUncollectedLineTest {
    @Test
    void theBoundArmNamesEachDropAndTheBody() {
        var drop = new NativeBreakAction.LeftDrop(-4096152.64, -56.34, 6119266.76, 73, 0, 0.0, -0.04, -0.1234);
        assertEquals("Hal break at -4096153, -57, 6119267: its collection ended (the bound) after 71 ticks, its own drops uncollected: "
                        + "[-4096152.64, -56.34, 6119266.76 age 73 delay 0 moving 0.000, -0.040, -0.123]; the body at -4096152.50, -57.00, 6119267.70 facing 180",
                NativeBreakAction.uncollectedLine("Hal", "-4096153, -57, 6119267", "the bound", 71, List.of(drop),
                        -4096152.5, -57.0, 6119267.7, 180f, null));
    }

    @Test
    void theUnsafeArmAddsTheHeadingsRefusal() {
        var a = new NativeBreakAction.LeftDrop(1.5, 2.0, 3.5, 4, 6, 0.1, 0.2, 0.0);
        var b = new NativeBreakAction.LeftDrop(2.25, 2.0, 3.0, 5, 5, 0.0, 0.0, 0.0);
        assertEquals("Iron break at 1, 2, 3: its collection ended (a heading it would not follow) after 3 ticks, its own drops uncollected: "
                        + "[1.50, 2.00, 3.50 age 4 delay 6 moving 0.100, 0.200, 0.000, 2.25, 2.00, 3.00 age 5 delay 5 moving 0.000, 0.000, 0.000]; "
                        + "the body at 1.50, 2.00, 5.00 facing -90; the heading refused: 2, 2, 5 (0.6 ahead): Block{minecraft:air}",
                NativeBreakAction.uncollectedLine("Iron", "1, 2, 3", "a heading it would not follow", 3, List.of(a, b),
                        1.5, 2.0, 5.0, -90f, "2, 2, 5 (0.6 ahead): Block{minecraft:air}"));
    }
}
