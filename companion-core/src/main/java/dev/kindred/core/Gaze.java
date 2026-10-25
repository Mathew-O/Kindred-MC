package dev.kindred.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a player looks while it waits (E-33a), as a person does:
 * <ul>
 *   <li>at a threat while it is in sight, turning quickly;</li>
 *   <li>at whoever speaks to it;</li>
 *   <li>back at a player close by who looks at it, for a moment, then back to its own business, and not at the same
 *       player again for a while;</li>
 *   <li>at what it waits on (the furnace it waits by);</li>
 *   <li>otherwise about it: a slow look round, each way held a few seconds.</li>
 * </ul>
 * Pure and deterministic: the adapter says what the player sees, from where, facing which way, and this says where to
 * look and how fast the head may turn. A subject once chosen is held, so the look does not flicker between two. The
 * look about is drawn from the player's own seed, so players waiting side by side do not turn as one.
 */
public final class Gaze {
    public enum Kind { THREAT, SPEAKER, PERSON, WORK, ABOUT }

    /** The most the head turns in a tick, in degrees: fast for a threat, slower for a person, slowest looking about. */
    public static final float THREAT_TURN = 30, SPEAKER_TURN = 15, PERSON_TURN = 12, WORK_TURN = 10, ABOUT_TURN = 5;
    /** A player looking at it from no further than this is met with a look back. */
    public static final double EYE_CONTACT = 6;
    /** Ticks before the same player is looked back at again. */
    public static final int REST = 200;

    /** Something seen, at its eyes; for a player, whether it is looking at this one. */
    public record Seen(UUID id, double x, double y, double z, boolean looksAtMe) {
        public Seen {
            Objects.requireNonNull(id);
        }
    }

    /** A point to look at that is not a creature: what the player waits on. */
    public record Point(double x, double y, double z) { }

    /** What the player sees from where it stands this tick: its eyes, its heading, and what is in view. */
    public record View(long tick, double eyeX, double eyeY, double eyeZ, float yaw, Seen threat, Seen speaker, List<Seen> people, Point work) {
        public View {
            people = List.copyOf(Objects.requireNonNull(people));
        }
    }

    /** Where to look, as a heading and a pitch, and the most the head may turn toward it this tick. */
    public record Look(Kind kind, UUID subject, float yaw, float pitch, float maxTurn) { }

    private final long seed;
    private final Personality personality;
    private UUID person;
    private long personUntil = Long.MIN_VALUE;
    private final Map<UUID, Long> metAt = new LinkedHashMap<>();
    private long aboutUntil = Long.MIN_VALUE;
    private float aboutYaw, aboutPitch;
    private int aboutTurns;
    private Kind last;

    public Gaze(long seed, Personality personality) {
        this.seed = seed;
        this.personality = Objects.requireNonNull(personality);
    }

    public Look choose(View view) {
        Objects.requireNonNull(view);
        long tick = view.tick();
        if (view.threat() != null) return at(Kind.THREAT, view.threat().id(), view, view.threat().x(), view.threat().y(), view.threat().z(), THREAT_TURN);
        if (view.speaker() != null) return at(Kind.SPEAKER, view.speaker().id(), view, view.speaker().x(), view.speaker().y(), view.speaker().z(), SPEAKER_TURN);
        // A look back held for its moment, while that player is still in view.
        if (person != null && tick < personUntil) {
            for (Seen p : view.people()) {
                if (p.id().equals(person)) return at(Kind.PERSON, p.id(), view, p.x(), p.y(), p.z(), PERSON_TURN);
            }
            personUntil = Long.MIN_VALUE;
        }
        person = null;
        for (Seen p : view.people()) {
            if (!p.looksAtMe() || distance(view, p.x(), p.y(), p.z()) > EYE_CONTACT) continue;
            Long met = metAt.get(p.id());
            if (met != null && tick - met < REST) continue;
            person = p.id();
            // The sociable hold a look longer.
            personUntil = tick + 30 + personality.sociability() / 3;
            metAt.remove(p.id());
            metAt.put(p.id(), tick);
            if (metAt.size() > 8) metAt.remove(metAt.keySet().iterator().next());
            return at(Kind.PERSON, p.id(), view, p.x(), p.y(), p.z(), PERSON_TURN);
        }
        if (view.work() != null) return at(Kind.WORK, null, view, view.work().x(), view.work().y(), view.work().z(), WORK_TURN);
        if (last != Kind.ABOUT || tick >= aboutUntil) {
            // A new way to look, from where it faces now: up to seventy degrees aside, from a little above level to
            // the ground a few blocks off; the curious look about more often.
            long draw = mix(seed + 31L * aboutTurns++);
            aboutYaw = view.yaw() + (Math.floorMod(draw, 141) - 70);
            aboutPitch = Math.floorMod(draw >>> 16, 36) - 10;
            aboutUntil = tick + 40 + (100 - personality.curiosity()) / 2 + Math.floorMod(draw >>> 32, 30);
        }
        last = Kind.ABOUT;
        return new Look(Kind.ABOUT, null, wrap(aboutYaw), aboutPitch, ABOUT_TURN);
    }

    /** Forgets what it was looking at, as after a stop. */
    public void clear() {
        person = null;
        personUntil = Long.MIN_VALUE;
        aboutUntil = Long.MIN_VALUE;
        last = null;
    }

    private Look at(Kind kind, UUID subject, View view, double x, double y, double z, float turn) {
        last = kind;
        double dx = x - view.eyeX(), dy = y - view.eyeY(), dz = z - view.eyeZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new Look(kind, subject, wrap(yaw), pitch, turn);
    }

    private static double distance(View view, double x, double y, double z) {
        double dx = x - view.eyeX(), dy = y - view.eyeY(), dz = z - view.eyeZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * One eased step of the head toward {@code look} from {@code yaw} and {@code pitch}: a third of what is left, at
     * least a degree and a half and at most the look's turn, so it starts briskly and settles without overshooting.
     * Returns the new yaw and pitch.
     */
    public static float[] step(float yaw, float pitch, Look look) {
        float turn = wrap(look.yaw() - yaw);
        float tilt = look.pitch() - pitch;
        return new float[] {wrap(yaw + ease(turn, look.maxTurn())), pitch + ease(tilt, look.maxTurn())};
    }

    private static float ease(float left, float most) {
        float size = Math.abs(left);
        return Math.signum(left) * Math.min(size, Math.min(most, Math.max(1.5f, size / 3)));
    }

    /** Degrees in -180..180. */
    static float wrap(float degrees) {
        float d = degrees % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /** A 64-bit mix (SplitMix64's finaliser), so nearby draws look unrelated. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
