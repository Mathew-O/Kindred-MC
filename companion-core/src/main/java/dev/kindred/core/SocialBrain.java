package dev.kindred.core;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: the player's small talk: it notices things worth a word, such as someone arriving, a threat
 * nearby, being hurt, rain or nightfall, or a job finished or stuck, and offers at most one short line at a
 * time, spaced out as its settings allow. It only ever offers words, never jobs or actions.
 *
 * Technical: Small deterministic social lane. It can propose words, never tasks or world actions.
 */
public final class SocialBrain {
    public enum Topic { THREAT, HURT, COMPANION, GREETING, RAIN, NIGHT, NOTICE, WORK_COMPLETED, WORK_BLOCKED, TEAM }

    /** What kind of line it is (E-18a): plain, a joke, or teasing (between AI players only). */
    public enum Tone { PLAIN, JOKE, TEASE }

    /**
     * A line to say, with its plain wording beside it: a joke or a tease is said plainly instead to anyone who asked for
     * no jokes or no teasing, and to everyone when one of those who will hear it did (E-19a).
     */
    public record Speech(Topic topic, UUID subject, String text, String key, String plain, Tone tone) {
        public Speech(Topic topic, UUID subject, String text, String key) { this(topic, subject, text, key, text, Tone.PLAIN); }

        /** The words for these listeners: the strictest of everyone who will hear it. */
        public String textFor(dev.kindred.core.social.ListenerPreferences heard) {
            if (heard == null || tone == Tone.PLAIN) return text;
            if (tone == Tone.JOKE && heard.noJokes()) return plain;
            if (tone == Tone.TEASE && (heard.noTeasing() || heard.noJokes())) return plain;
            return text;
        }
    }
    private record Pending(Speech speech, long expires, int priority) { }

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Long> spoken = new LinkedHashMap<>();
    private Set<UUID> previousContacts = Set.of();
    private SocialSnapshot previous;
    private long nextAmbient = Long.MIN_VALUE;
    private long nextReply = Long.MIN_VALUE;
    private long lastProfileRevision = -1;
    private int variation;

    /** Looks at what the player sees now and gives the line most worth saying, if it may speak yet. */
    public Optional<Speech> observe(SocialSnapshot snapshot, SocialProfile profile, long now) {
        if (lastProfileRevision != profile.revision()) {
            pending.clear();
            previousContacts = Set.of();
            previous = null;
            lastProfileRevision = profile.revision();
        }
        if (!snapshot.active() || !profile.ambientEnabled() || profile.personality().sociability() == 0) {
            clearObservations();
            return Optional.empty();
        }
        Set<UUID> contacts = new HashSet<>();
        for (var contact : snapshot.contacts()) {
            if (contact.kind() != SocialSnapshot.Kind.OWNER && !profile.publicConversation()) continue;
            contacts.add(contact.id());
            if (!previousContacts.contains(contact.id())) {
                boolean companion = contact.kind() == SocialSnapshot.Kind.COMPANION;
                queue(greeting(contact, companion, profile), now, companion ? 60 : 50);
            }
        }
        previousContacts = Set.copyOf(contacts);
        if (snapshot.visibleThreat() != null) {
            queue(new Speech(Topic.THREAT, null, "There's a " + snapshot.visibleThreat() + " nearby. Keep an eye on it.",
                    "threat"), now, 100);
        }
        if (snapshot.hurt() && (previous == null || !previous.hurt())) {
            queue(new Speech(Topic.HURT, null, hurt(snapshot.harm()), "hurt"), now, 90);
        }
        if (snapshot.raining() && (previous == null || !previous.raining())) {
            queue(styled(Topic.RAIN, "rain", profile, pick("It's raining here.", "A bit wet out here."),
                    "Rain. Lovely.", "Rain again. I seem to have forgotten my umbrella."), now, 20);
        }
        if (snapshot.night() && (previous == null || !previous.night())) {
            queue(styled(Topic.NIGHT, "night", profile, pick("It's dark out here. Let's watch our footing.",
                    "Night out here. Keep an eye on the path."), "Night. Again.",
                    "Dark already. Something out there is thinking about my boots."), now, 20);
        }
        previous = snapshot;
        pending.values().removeIf(p -> p.expires() <= now || !stillRelevant(p.speech(), snapshot, contacts));
        if (!canSpeak(profile, now)) return Optional.empty();
        return pending.values().stream().max(Comparator.comparingInt(Pending::priority)).map(Pending::speech);
    }

    /**
     * Whether the player may make small talk now: it is turned on and the pause since its last line is over.
     */
    public boolean canSpeak(SocialProfile profile, long now) {
        return profile.ambientEnabled() && profile.personality().sociability() > 0 && now >= nextAmbient;
    }

    /** Notes that a line was said: the pause before the next begins, and whatever was waiting is dropped. */
    public void didSpeak(Speech speech, SocialProfile profile, long now) {
        nextAmbient = now + profile.ambientIntervalMillis();
        remember(speech.key(), now);
        pending.clear(); // Coalesce observations; do not narrate a backlog after the cooldown.
        variation++;
    }

    /** Notes that the player answered another AI player's greeting, as if it had said hello itself. */
    public void didReplyToCompanion(UUID greeter, SocialProfile profile, long now) {
        didSpeak(new Speech(Topic.COMPANION, greeter, "", "hello:" + greeter), profile, now);
    }

    /**
     * An attention pause noticed someone mid-activity. Queue one short grounded line at low priority; it
     * competes under the ordinary allowance, expires with the contact and repeats for the same person at
     * most once per ten minutes. The activity label comes from the skill's declared phase, never a guess.
     */
    public void noticed(SocialSnapshot.Contact contact, String activity, SocialProfile profile, long now) {
        Objects.requireNonNull(contact);
        Objects.requireNonNull(activity);
        if (!profile.ambientEnabled() || profile.personality().sociability() == 0) return;
        if (contact.kind() != SocialSnapshot.Kind.OWNER && !profile.publicConversation()) return;
        String plain = pick("Oh, hey " + contact.name() + ". Just " + activity + ".", "Hi " + contact.name() + ". Just " + activity + ".");
        Speech line = styled(Topic.NOTICE, "notice:" + contact.id(), profile, plain, contact.name() + ". Just " + activity + ".",
                "Oh, hey " + contact.name() + ". Don't mind me, just " + activity + ".");
        queue(new Speech(Topic.NOTICE, contact.id(), line.text(), line.key(), line.plain(), line.tone()), now, 10);
    }

    /** Offers a line about a job the player finished. */
    public void workCompleted(String skill, String detail, SocialProfile profile, long now) {
        if (!profile.ambientEnabled() || profile.personality().sociability() == 0) return;
        queue(styled(Topic.WORK_COMPLETED, "work_completed:" + skill, profile, "Finished " + skill + ". " + detail,
                skill + ": done. " + detail, "All done with " + skill + "! " + detail), now, 70);
    }

    /** Offers a line about a job the player got stuck on, and why. */
    public void workBlocked(String skill, String reason, SocialProfile profile, long now) {
        if (!profile.ambientEnabled() || profile.personality().sociability() == 0) return;
        String line = "Hit a snag on " + skill + ": " + reason;
        queue(new Speech(Topic.WORK_BLOCKED, null, line, "work_blocked:" + skill), now, 75);
    }

    /**
     * A word to its team (E-40a): the part it takes up, or a find and who gets what of it, as {@code dev.kindred.core.team}
     * words them from the run's division and the share-out. Said under the same allowance as the rest; the same word is
     * not said again for five minutes, so a part reckoned again each step is said once.
     */
    public void team(String line, String key, SocialProfile profile, long now) {
        Objects.requireNonNull(line);
        Objects.requireNonNull(key);
        if (!profile.ambientEnabled() || profile.personality().sociability() == 0) return;
        queue(new Speech(Topic.TEAM, null, line, "team:" + key), now, 80);
    }

    /** Whether the player may answer someone directly now: at most once every three seconds. */
    public boolean admitDirectReply(long now) {
        if (now < nextReply) return false;
        nextReply = now + 3_000;
        return true;
    }

    /** Forgets what it was waiting to say and what it last saw. */
    public void clearObservations() {
        pending.clear();
        previousContacts = Set.of();
        previous = null;
    }

    private void queue(Speech speech, long now, int priority) {
        long repeatAfter = speech.topic() == Topic.GREETING || speech.topic() == Topic.COMPANION ? 1_800_000
                : speech.topic() == Topic.WORK_COMPLETED || speech.topic() == Topic.WORK_BLOCKED ? 5_000
                : speech.topic() == Topic.TEAM ? 300_000 : 600_000;
        Long last = spoken.get(speech.key());
        if (last != null && now - last < repeatAfter) return;
        if (pending.size() >= 16 && !pending.containsKey(speech.key())) return;
        pending.putIfAbsent(speech.key(), new Pending(speech, now + 10_000, priority));
    }

    private void remember(String key, long now) {
        spoken.remove(key);
        spoken.put(key, now);
        while (spoken.size() > 128) spoken.remove(spoken.keySet().iterator().next());
    }

    private static boolean stillRelevant(Speech speech, SocialSnapshot snapshot, Set<UUID> contacts) {
        return switch (speech.topic()) {
            case GREETING, COMPANION, NOTICE -> contacts.contains(speech.subject());
            case THREAT -> snapshot.visibleThreat() != null && speech.text().contains(snapshot.visibleThreat());
            case RAIN -> snapshot.raining();
            case NIGHT -> snapshot.night();
            case HURT -> snapshot.hurt();
            case WORK_COMPLETED, WORK_BLOCKED, TEAM -> true;
        };
    }

    /**
     * A word on being hurt, of what the game says hurt it and nothing more: a player is named only when the damage was
     * theirs, and a fall or a burn blames nobody.
     */
    private String hurt(SocialSnapshot.Harm harm) {
        if (harm == null) return pick("Ouch. I just took a hit.", "That hurt. Let's be careful here.");
        return switch (harm.source()) {
            case PLAYER -> pick("Hey, " + harm.name() + ", watch it!", "Ow! That was you, " + harm.name() + ".");
            case MOB -> pick("Ouch, a " + harm.name() + " got me.", "That " + harm.name() + " hit me. Careful.");
            case WORLD -> pick("Ouch. " + harm.name() + ".", "That hurt: " + harm.name() + ".");
        };
    }

    /**
     * A line in the speaker's manner (E-18a): plain, dry, or a playful one marked as a joke, which goes out plainly to
     * anyone who asked for no jokes. The profile is the one under the server's cap.
     */
    private static Speech styled(Topic topic, String key, SocialProfile profile, String plain, String dry, String playful) {
        return switch (profile.effectiveExpression()) {
            case PLAIN -> new Speech(topic, null, plain, key);
            case DRY -> new Speech(topic, null, dry, key, plain, Tone.PLAIN);
            case PLAYFUL -> new Speech(topic, null, playful, key, plain, Tone.JOKE);
        };
    }

    /**
     * A hello as someone comes into view. To another AI player a playful one teases a little, and only then: a person is
     * never teased; with anyone in earshot who asked for no teasing the plain hello is said.
     */
    private Speech greeting(SocialSnapshot.Contact contact, boolean companion, SocialProfile profile) {
        String plain = pick("Hi, " + contact.name() + ".", "Hello, " + contact.name() + "!", "Hey, " + contact.name() + ".")
                + (companion ? " Good to see another companion around." : "");
        Topic topic = companion ? Topic.COMPANION : Topic.GREETING;
        String key = "hello:" + contact.id();
        return switch (profile.effectiveExpression()) {
            case PLAIN -> new Speech(topic, contact.id(), plain, key);
            case DRY -> new Speech(topic, contact.id(), contact.name() + ". Hello.", key, plain, Tone.PLAIN);
            case PLAYFUL -> companion
                    ? new Speech(topic, contact.id(), "Hey, " + contact.name() + ". Still here? I thought you'd have the Nether sorted by now.",
                            key, plain, Tone.TEASE)
                    : new Speech(topic, contact.id(), "Hey, " + contact.name() + "! Always room for another square head around here.",
                            key, plain, Tone.JOKE);
        };
    }

    private String pick(String... choices) { return choices[Math.floorMod(variation, choices.length)]; }
}
