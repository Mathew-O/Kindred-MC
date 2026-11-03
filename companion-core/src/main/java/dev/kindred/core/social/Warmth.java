package dev.kindred.core.social;

/**
 * In plain terms: how warmly a player is with someone, from the friendship it keeps with them (E-24a), and what that
 * changes: the words it greets them with, whether it makes small talk, whether it offers them what it has spare in talk,
 * and how sociable it must be to go on an outing they invite it on. A friend gets a warmer hello and a readier yes; someone
 * it is cool with gets a short answer and no small talk.
 *
 * <p>It never changes who may control the player, what they may do, any budget, a stop, or whether it strikes anyone
 * (AUTH-01, SOC-02): only tone and choices that are the player's own to make. Whether it warns someone of danger, or offers
 * food to someone hungry, does not depend on it.
 *
 * <p>Technical: E-25a, pure. The bands are {@link Rapport.Standing#band()}'s.
 */
public enum Warmth {
    /** A friend: friendship 30 or more. */
    WARM,
    /** Friendly: 8 or more. */
    KIND,
    /** Neither: as before E-25a. */
    PLAIN,
    /** Cool: below 0. */
    COOL,
    /** Wary: −24 or below. */
    CURT;

    /** The warmth of a friendship score, by the bands of {@link Rapport.Standing#band()}. */
    public static Warmth of(int friendship) {
        if (friendship >= 30) return WARM;
        if (friendship >= 8) return KIND;
        if (friendship <= -24) return CURT;
        if (friendship < 0) return COOL;
        return PLAIN;
    }

    /** The warmth of a standing, or plain for none. */
    public static Warmth of(Rapport.Standing standing) {
        return standing == null ? PLAIN : of(standing.friendship());
    }

    /** Whether it makes small talk with them or starts a talk unasked: not when it is cool with them. */
    public boolean smallTalk() {
        return this != COOL && this != CURT;
    }

    /** Whether, in talk, it offers them something it has spare: not when it is wary of them. */
    public boolean sharesSpare() {
        return this != CURT;
    }

    /** Whether it answers them in a word, keeping what it is doing and needs to itself. */
    public boolean curt() {
        return this == COOL || this == CURT;
    }

    /**
     * How sociable it must be to say yes to their outing: a friend's at 20, a friendly one's at 30, anyone else's at
     * {@code plain} (the rule before E-25a); never one it is cool with.
     */
    public int outingSociability(int plain) {
        return switch (this) {
            case WARM -> Math.min(plain, 20);
            case KIND -> Math.min(plain, 30);
            case PLAIN -> plain;
            case COOL, CURT -> Integer.MAX_VALUE;
        };
    }

    /** Its hello to {@code name}, in its manner; empty for plain, whose hello is as before. */
    public String hello(Expression sounds, String name) {
        Expression s = sounds == null ? Expression.PLAIN : sounds;
        return switch (this) {
            case WARM -> switch (s) {
                case PLAIN -> "Hi, " + name + "! Always good to see you.";
                case DRY -> name + ". Good. I was hoping you'd turn up.";
                case PLAYFUL, ROUGH -> "Hey, " + name + "! There's my favourite person.";
            };
            case KIND -> switch (s) {
                case PLAIN -> "Hi, " + name + ". Nice to see you again.";
                case DRY -> name + ". Hello again.";
                case PLAYFUL, ROUGH -> "Hey, " + name + "! Good to see you again.";
            };
            case COOL, CURT -> name + ".";
            case PLAIN -> "";
        };
    }

    /** Its answer to {@code name}'s hello; empty for plain, whose answer is as before. */
    public String helloBack(String name) {
        return switch (this) {
            case WARM -> "Hi, " + name + "! Good to see you too.";
            case KIND -> "Hi, " + name + ". You too.";
            case COOL, CURT -> "Hm.";
            case PLAIN -> "";
        };
    }
}
