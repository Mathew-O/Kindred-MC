package dev.kindred.core;

/**
 * Sprint and hop requests for an authorized movement command. Terrain jumps take precedence; habit hops
 * use {@link SprintStride} so jumps are spaced by footfalls rather than every grounded tick.
 */
public final class AuthorizedMovementCadence {
    private AuthorizedMovementCadence() { }

    public static boolean sprint(MannerismState.Gait gait, boolean striding) {
        return striding && gait != MannerismState.Gait.WALK;
    }

    public static boolean requestJump(SprintStride stride, MannerismState.Gait gait, boolean striding, boolean onGround,
                                      int age, boolean terrainJump) {
        if (terrainJump) return true;
        if (!sprint(gait, striding) || gait != MannerismState.Gait.SPRINT_JUMP) return false;
        return stride.tick(true, onGround, striding, age);
    }
}
