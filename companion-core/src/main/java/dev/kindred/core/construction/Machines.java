package dev.kindred.core.construction;

import java.util.List;

/**
 * Functional builds with redstone parts (M-01d, the owner's note of 2026-10-23: farms and other builds use working
 * redstone mechanics), as survival players build them early. Each is a plan of blocks in the states it works in; the
 * build runner places them as for any build, each part set against the block it feeds. Pure.
 */
public final class Machines {
    private Machines() { }

    /**
     * An auto-smelter: a furnace fed by hoppers, as players build one once they have iron to spare. What goes into the
     * input chest on top runs down a hopper into the furnace's top; fuel in the chest to its east runs down a hopper
     * into its side; what it smelts a hopper under it takes out into the output chest in front, at the ground. The
     * origin is the hopper under the furnace; the furnace and every chest face south, where its player stands.
     * Its bill: a furnace, three chests and three hoppers (fifteen iron and three more chests).
     */
    public static Blueprint smelter() {
        var blocks = List.of(
                // The output chest, on the ground in front.
                new Blueprint.Block(new Pos(0, 0, 1), BlockSpec.parse("minecraft:chest[facing=south,type=single]")),
                // Under the furnace, into the output chest.
                new Blueprint.Block(new Pos(0, 0, 0), BlockSpec.parse("minecraft:hopper[facing=south]")),
                new Blueprint.Block(new Pos(0, 1, 0), BlockSpec.parse("minecraft:furnace[facing=south]")),
                // The fuel: into the furnace's side, from the chest above it.
                new Blueprint.Block(new Pos(1, 1, 0), BlockSpec.parse("minecraft:hopper[facing=west]")),
                new Blueprint.Block(new Pos(1, 2, 0), BlockSpec.parse("minecraft:chest[facing=south,type=single]")),
                // What it smelts: into the furnace's top, from the chest above it.
                new Blueprint.Block(new Pos(0, 2, 0), BlockSpec.parse("minecraft:hopper[facing=down]")),
                new Blueprint.Block(new Pos(0, 3, 0), BlockSpec.parse("minecraft:chest[facing=south,type=single]")));
        return new Blueprint("auto-smelter", Generators.SOURCE + "smelter", "Kindred", "Kindred's own", blocks);
    }

    /** Where in the smelter, from its origin: the input chest, the fuel chest and the output chest. */
    public static final Pos INPUT = new Pos(0, 3, 0), FUEL = new Pos(1, 2, 0), OUTPUT = new Pos(0, 0, 1);
}
