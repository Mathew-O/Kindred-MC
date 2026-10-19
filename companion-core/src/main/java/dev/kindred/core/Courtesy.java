package dev.kindred.core;

/** Where a moving companion should put its body when sharing a space. It proposes; the adapter still validates the route. */
public final class Courtesy {
    public enum Passage { PROCEED, WAIT, STEP_ASIDE }

    private Courtesy() { }

    /** A narrow gap with someone in it is waited, not pushed. An open gap is walked, never sprint-jumped. */
    public static Passage passage(boolean narrow, boolean someoneInTheGap, boolean crowdedAgainstOwner) {
        if (crowdedAgainstOwner) return Passage.STEP_ASIDE;
        if (narrow && someoneInTheGap) return Passage.WAIT;
        return Passage.PROCEED;
    }

    public static boolean sprintAllowed(Passage passage) { return passage == Passage.PROCEED; }
}
