package dev.kindred.core.construction;

import dev.kindred.core.Capabilities;
import dev.kindred.core.VanillaIds;

/**
 * In plain terms: the blocks a build's wood gives it (F-38): its planks, log, stairs, slab, fence, gate, door and
 * trapdoor, by the wood's family rather than by gluing a name together, so a wood a mod adds is built with as
 * vanilla's are.
 *
 * Technical: Looks the build's wood up in {@link Capabilities#current()}. A vanilla wood's parts are the ids it always
 * had ("oak" gives minecraft:oak_planks). An unknown name, or a part its wood lacks, falls back to the vanilla naming,
 * which is what the generators produced before, so a saved base with a wood the server no longer has still plans.
 */
public final class Woods {

    private Woods() { }

    private static Capabilities.Wood of(String wood) {
        return Capabilities.current().wood(wood).orElse(null);
    }

    private static String named(String wood, String suffix) {
        return VanillaIds.NAMESPACE + wood + suffix;
    }

    public static String planks(String wood) { var w = of(wood); return w != null ? w.planks() : named(wood, "_planks"); }
    public static String log(String wood) { var w = of(wood); return w != null ? w.log() : named(wood, "_log"); }
    public static String stairs(String wood) { var w = of(wood); return w != null && w.stairs() != null ? w.stairs() : named(wood, "_stairs"); }
    public static String slab(String wood) { var w = of(wood); return w != null && w.slab() != null ? w.slab() : named(wood, "_slab"); }
    public static String fence(String wood) { var w = of(wood); return w != null && w.fence() != null ? w.fence() : named(wood, "_fence"); }
    public static String fenceGate(String wood) { var w = of(wood); return w != null && w.fenceGate() != null ? w.fenceGate() : named(wood, "_fence_gate"); }
    public static String door(String wood) { var w = of(wood); return w != null && w.door() != null ? w.door() : named(wood, "_door"); }
    public static String trapdoor(String wood) { var w = of(wood); return w != null && w.trapdoor() != null ? w.trapdoor() : named(wood, "_trapdoor"); }

    /** Whether {@code wood} names a wood a build can be made of here. */
    public static boolean known(String wood) {
        return Capabilities.current().wood(wood).isPresent();
    }
}
