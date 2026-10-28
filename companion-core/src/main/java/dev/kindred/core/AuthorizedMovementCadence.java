package dev.kindred.core;

/**
 * In plain terms: when a moving player should sprint and when it should hop, so its movement looks like a
 * person's: jumps over terrain always, and the occasional sprint-jump spaced out by its footsteps.
 *
 * Technical: Sprint and hop requests for an authorized movement command. Terrain jumps take precedence; habit
 * hops use {@link SprintStride} so jumps are spaced by footfalls rather than every grounded tick.
 */
public final class AuthorizedMovementCadence {
    private AuthorizedMovementCadence() { }

    /** Whether to sprint: only on a long straight stretch, and not when its habit is to walk. */
    public static boolean sprint(MannerismState.Gait gait, boolean striding) {
        return striding && gait != MannerismState.Gait.WALK;
    }

    /**
     * Whether to jump this tick: always for a block in the way, otherwise only for a sprint-jump in step with
     * its stride.
     */
    public static boolean requestJump(SprintStride stride, MannerismState.Gait gait, boolean striding, boolean onGround,
                                      int age, boolean terrainJump) {
        if (terrainJump) return true;
        if (!sprint(gait, striding) || gait != MannerismState.Gait.SPRINT_JUMP) return false;
        return stride.tick(true, onGround, striding, age);
    }
}
