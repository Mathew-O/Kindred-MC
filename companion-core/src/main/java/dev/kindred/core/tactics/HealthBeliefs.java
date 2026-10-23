package dev.kindred.core.tactics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a player can know of other creatures' health (E-41a). Not the number the server keeps, which no player sees, but
 * what it can work out from what it saw:
 * <ul>
 *   <li>a creature starts at its kind's full health, as a player knows a zombie has twenty;</li>
 *   <li>each hit the player saw land (the hurt flash) takes off what the ruleset says that hit deals. A stronger hit
 *   landing within another's damage immunity shows no flash, so a player does not see it, and it is not counted;</li>
 *   <li>a player heals as time passes, a mob does not;</li>
 *   <li>it is less sure the longer since it last saw a hit, and of a player it is never sure.</li>
 * </ul>
 * The Dragon's health is the exception: every player sees it on the boss bar. One per player; bounded.
 */
public final class HealthBeliefs {
    /** How a value is known. */
    public enum Basis { OBSERVED, INFERRED, UNKNOWN }

    /** A value with how sure the player is of it, when it was last borne out, and how it is known. */
    public record Estimate(double value, double confidence, long observedAtTick, Basis basis) {
        public Estimate {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("A value must be finite and >= 0");
            if (!(confidence >= 0 && confidence <= 1)) throw new IllegalArgumentException("Confidence must be within 0..1");
        }
    }

    /** At most this many creatures remembered: the least recently seen go first. */
    static final int CAPACITY = 64;
    /** A player at full food heals about a point every four seconds: what one is believed to have regained. */
    static final double PLAYER_REGEN_PER_TICK = 1.0 / 80;
    /** Ticks for a belief's confidence to halve without a hit seen. */
    static final long HALF_LIFE = 1200;

    private record Belief(double max, double health, long since, boolean player, boolean hit) { }

    private final Map<UUID, Belief> beliefs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Belief> eldest) { return size() > CAPACITY; }
    };

    /** What the player believes of {@code id}, a creature of {@code maxHealth} at full health (a player if {@code player}). */
    public Estimate of(UUID id, double maxHealth, boolean player, long tick) {
        Belief belief = beliefs.get(id);
        if (belief == null) {
            // Never seen hit: full health, as its kind has; a player may have been hurt anywhere since.
            return new Estimate(maxHealth, player ? 0.3 : 0.9, tick, Basis.INFERRED);
        }
        long since = Math.max(0, tick - belief.since());
        double health = belief.player() ? Math.min(belief.max(), belief.health() + since * PLAYER_REGEN_PER_TICK) : belief.health();
        double sure = (belief.player() ? 0.6 : 0.85) * Math.pow(0.5, since / (double) HALF_LIFE);
        return new Estimate(health, sure, belief.since(), Basis.INFERRED);
    }

    /** A hit the player saw land on {@code id}, dealing {@code dealt} by the ruleset. */
    public void hit(UUID id, double maxHealth, boolean player, double dealt, long tick) {
        if (!Double.isFinite(dealt) || dealt < 0) throw new IllegalArgumentException("A hit deals a finite amount >= 0");
        double before = of(id, maxHealth, player, tick).value();
        beliefs.put(id, new Belief(maxHealth, Math.max(0, before - dealt), tick, player, true));
    }

    /** Whether the player has seen a hit land on {@code id} that it still remembers. */
    public boolean seenHit(UUID id) {
        Belief belief = beliefs.get(id);
        return belief != null && belief.hit();
    }

    public void forget(UUID id) { beliefs.remove(id); }

    public int size() { return beliefs.size(); }
}
