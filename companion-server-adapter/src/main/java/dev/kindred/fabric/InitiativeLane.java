package dev.kindred.fabric;

import dev.kindred.core.social.Initiative;
import dev.kindred.core.social.ListenerPreferences;
import dev.kindred.core.social.Expression;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * In plain terms: an AI player speaking first. It warns a person of a creeper or other monster behind them; offers food to
 * someone hungry when it has some to spare, and something it has spare to someone who said they need it (E-21a), and
 * hands either over only once they say "yes please"; asks those about for a
 * thing it needs, once an hour for each thing; and, idle with someone near, says a word now and then. All of it within each
 * person's attention budget ({@link Initiative}), which keeps quiet to someone fighting, building fast, waiting for a match
 * or away, and to anyone out of earshot. A player whose remarks are turned off says nothing first at all.
 *
 * Technical: E-53's adapter, on the server thread, a turn a second. Lines go through the social lane's own checks and
 * typing delay ({@link CompanionSocials#unasked}), and are put as the strictest of the hearer's preferences asks. A person
 * who speaks to the player within a minute of a line has answered it. The offer's toss is the toss method's, run when the
 * person accepts: nothing moves before. The runtime's side is a {@link Source}, so that a fixture can drive it on its own
 * clock.
 */
final class InitiativeLane {
    /** How long an offer waits for its yes. */
    static final long OFFER_MILLIS = 60_000;
    /** A person this hungry (of 20) may be offered food. */
    static final int HUNGRY = 10;
    /** Food the player keeps for itself before it has any to spare. */
    static final int KEEP_FOOD = 4;
    /** How near a monster behind a person must be to warn them. */
    static final double DANGER = 6;
    /** No movement for this long: away. */
    static final long AWAY_MILLIS = 300_000;
    /** A person near for this long while the player is idle may hear a word. */
    static final long IDLE_TOGETHER_MILLIS = 20_000;

    /** The runtime's side. */
    interface Source {
        /** Its friendship with {@code other} (E-24a), for tone and its own choices only (E-25a); 0 when none is kept. */
        default int friendship(UUID id, UUID other) { return 0; }
        /** The AI players who may speak now, in the social lane's terms. */
        List<CompanionSocials.Participant> players();

        /** Food it carries that it could spare (the item id and how many beyond what it keeps), or empty. */
        Optional<Map.Entry<String, Integer>> spareFood(UUID id);

        /** What it needs, in words without counts ("flint", "string"), from its own update to the team. */
        List<String> needs(UUID id);

        /** Whether it is idle: no task in hand. */
        boolean idle(UUID id);

        /** Whether a person is in a match's countdown. */
        boolean inCountdown(UUID person);

        /** The offer's toss, through the toss method, to {@code to}: 1 when started, else the refusal is in {@code refused}. */
        int offerToss(UUID id, ServerPlayerEntity to, String item, int count, java.util.function.Consumer<String> refused);

        /** How the player sounds under the server's cap, and what this person asked for. */
        Expression sounding(CompanionSocials.Participant player);

        ListenerPreferences preferences(UUID person);

        /** What a person has said they need, to this AI player, still a claim (E-50), newest first. */
        default List<String> claimedNeeds(UUID id, UUID person) { return List.of(); }

        /** What it could give (E-21a): half of its unpromised finds, never its tools or armour. */
        default List<dev.kindred.core.gift.GiftPolicy.Spare> spare(UUID id) { return List.of(); }

        /** Whether it is wary of this person (E-49): no offers to them. */
        default boolean wary(UUID id, UUID person) { return false; }
    }

    private record Offer(String item, int count, long until) { }

    /** What is seen of a person from one turn to the next: where they stood, their held block stack, and since when. */
    private static final class Track {
        Vec3d at;
        long movedAt;
        int held = -1;
        long placingUntil;
        long nearSince = Long.MIN_VALUE;
    }

    private static final Pattern YES = Pattern.compile("(?:yes|yeah|yep|sure|ok(?:ay)?|please)(?:,?\\s*(?:please|thanks|thank you))?");
    private static final Pattern NO = Pattern.compile("no(?:,?\\s*(?:thanks|thank you))?|nope|i'?m (?:fine|good|ok(?:ay)?)");

    private final Source source;
    private final CompanionSocials socials;
    private final LongSupplier clock;
    private final Map<UUID, Initiative> budgets = new HashMap<>();
    private final Map<String, Offer> offers = new LinkedHashMap<>();
    private final Map<UUID, Track> tracks = new LinkedHashMap<>();

    InitiativeLane(Source source, CompanionSocials socials, LongSupplier clock) {
        this.source = source;
        this.socials = socials;
        this.clock = clock;
    }

    Initiative budget(UUID id) { return budgets.computeIfAbsent(id, ignored -> new Initiative()); }

    /** A turn: for each AI player, what it might say first, to whom, the most pressing first, within each budget. */
    void tick() {
        long now = clock.getAsLong();
        offers.values().removeIf(offer -> now > offer.until());
        for (var player : source.players()) {
            // Its remarks turned off, or not sociable at all: nothing unasked, warnings included.
            if (!player.active() || player.body() == null || !player.profile().ambientEnabled() || player.profile().personality().sociability() == 0) continue;
            if (!(player.body().getWorld() instanceof net.minecraft.server.world.ServerWorld world)) continue;
            // Friends first (E-25a): where its word for one uses up what it may say, a friend has it.
            var people = new java.util.ArrayList<>(world.getPlayers());
            people.sort(java.util.Comparator.comparingInt((ServerPlayerEntity person) -> -source.friendship(player.id(), person.getUuid())));
            for (ServerPlayerEntity person : people) {
                if (socials.aiPlayerOf(person) != null) continue;
                Track track = track(person, now);
                boolean earshot = socials.canTalk(person, player);
                if (!earshot) {
                    track.nearSince = Long.MIN_VALUE;
                    continue;
                }
                if (track.nearSince == Long.MIN_VALUE) track.nearSince = now;
                var state = new Initiative.State(true, fighting(person), now < track.placingUntil, source.inCountdown(person.getUuid()),
                        now - track.movedAt >= AWAY_MILLIS);
                turn(player, person, state, track, now);
            }
        }
        while (tracks.size() > 256) tracks.remove(tracks.keySet().iterator().next());
    }

    private void turn(CompanionSocials.Participant player, ServerPlayerEntity person, Initiative.State state, Track track, long now) {
        Initiative budget = budget(player.id());
        ListenerPreferences prefs = source.preferences(person.getUuid());
        UUID who = person.getUuid();
        // A monster behind them: at once, fighting or not.
        var danger = behind(player, person);
        if (danger != null && budget.may(who, state, Initiative.Occasion.WARNING, who.toString(), prefs, now)) {
            say(player, person, "Behind you, " + person.getName().getString() + ": a " + danger + "!", Initiative.Occasion.WARNING, who.toString(), now);
            return;
        }
        boolean wary = source.wary(player.id(), who);
        var warmth = dev.kindred.core.social.Warmth.of(source.friendship(player.id(), who));
        // A friend, or a friendly one, it calls by name (E-25a).
        String to = warmth == dev.kindred.core.social.Warmth.WARM || warmth == dev.kindred.core.social.Warmth.KIND ? person.getName().getString() : "";
        // Hungry, with food to spare: an offer, held a minute for their yes.
        var spare = source.spareFood(player.id());
        if (!wary && person.getHungerManager().getFoodLevel() <= HUNGRY && spare.isPresent() && spare.get().getValue() > 0
                && budget.may(who, state, Initiative.Occasion.OFFER, who.toString(), prefs, now)) {
            String item = spare.get().getKey();
            String words = net.minecraft.util.Identifier.of(item).getPath().replace('_', ' ');
            offers.put(key(player.id(), who), new Offer(item, Math.min(spare.get().getValue(), 4), now + OFFER_MILLIS));
            say(player, person, (to.isEmpty() ? "Need food?" : "Need food, " + to + "?") + " I've got " + words + ".", Initiative.Occasion.OFFER, who.toString(), now);
            return;
        }
        // Something they said they need, that it has to spare (E-21a): an offer, held a minute for their yes; the toss only on
        // it, and the gift counted only once they pick it up (E-22a).
        if (!wary) {
            var gifts = source.spare(player.id());
            for (String need : source.claimedNeeds(player.id(), who)) {
                var gift = dev.kindred.core.gift.GiftPolicy.offerFor(need, gifts);
                if (gift.isEmpty()) continue;
                String giftKey = "gift:" + gift.get().item();
                if (!budget.may(who, state, Initiative.Occasion.OFFER, giftKey, prefs, now)) break;
                offers.put(key(player.id(), who), new Offer(gift.get().item(), gift.get().count(), now + OFFER_MILLIS));
                say(player, person, (to.isEmpty() ? "You said" : to + ", you said") + " you need " + need + "? I've got " + gift.get().count() + " " + gift.get().item().replace('_', ' ')
                        + " spare. Want some?", Initiative.Occasion.OFFER, giftKey, now);
                return;
            }
        }
        // Something it needs: asked once an hour for each thing.
        for (String need : source.needs(player.id())) {
            if (!budget.may(who, state, Initiative.Occasion.REQUEST, need, prefs, now)) continue;
            say(player, person, "Anyone got " + need + "?", Initiative.Occasion.REQUEST, need, now);
            return;
        }
        // Idle together a while: a word, as it sounds.
        // No small talk with someone it is cool with (E-25a).
        if (warmth.smallTalk() && source.idle(player.id()) && now - track.nearSince >= IDLE_TOGETHER_MILLIS
                && budget.may(who, state, Initiative.Occasion.BANTER, "", prefs, now)) {
            say(player, person, banter(source.sounding(player), prefs), Initiative.Occasion.BANTER, "", now);
        }
    }

    private void say(CompanionSocials.Participant player, ServerPlayerEntity person, String text, Initiative.Occasion occasion, String key, long now) {
        if (!socials.unasked(player, person, text, now)) return;
        budget(player.id()).said(person.getUuid(), occasion, key, source.preferences(person.getUuid()), now);
        KindredMod.LOGGER.info("{} to {} unasked ({}): {}", player.name(), person.getName().getString(), occasion, text);
    }

    /** A word while idle together, in the player's manner; plainly to one who asked for no jokes. */
    static String banter(Expression sounding, ListenerPreferences prefs) {
        boolean noJokes = prefs != null && prefs.noJokes();
        return switch (sounding == null ? Expression.PLAIN : sounding) {
            case PLAIN -> "Quiet out here.";
            case DRY -> "Quiet. Too quiet.";
            case PLAYFUL -> noJokes ? "Quiet out here." : "If it gets any quieter I'll start naming the sheep.";
            // E-18b: a rough one swears (softened at delivery for whoever did not say yes) or, to someone who said yes to
            // dark jokes, makes one.
            case ROUGH -> noJokes ? "Quiet out here." : prefs != null && prefs.dark()
                    ? "So quiet. Perfect time for something to jump out and kill us." : "Quiet as hell out here. Almost miss the creepers.";
        };
    }

    /**
     * Something said to the player by a person: an answer to its last unasked line, and, to an offer still waiting, their
     * yes (the toss, through the toss method) or their no. The reply to a yes or a no, or empty.
     */
    Optional<String> heard(ServerPlayerEntity speaker, CompanionSocials.Participant player, String message) {
        long now = clock.getAsLong();
        budget(player.id()).answered(speaker.getUuid(), now);
        Offer offer = offers.get(key(player.id(), speaker.getUuid()));
        if (offer == null) return Optional.empty();
        String m = dev.kindred.core.social.Claims.plain(message);
        if (NO.matcher(m).matches()) {
            offers.remove(key(player.id(), speaker.getUuid()));
            return Optional.of("No problem.");
        }
        if (!YES.matcher(m).matches()) return Optional.empty();
        offers.remove(key(player.id(), speaker.getUuid()));
        String[] refusal = {null};
        int done = source.offerToss(player.id(), speaker, offer.item(), offer.count(), why -> refusal[0] = why);
        KindredMod.LOGGER.info("{}'s offer of {} {} to {}: {}", player.name(), offer.count(), offer.item(), speaker.getName().getString(),
                done == 1 ? "taken" : "refused (" + refusal[0] + ")");
        return Optional.of(done == 1 ? "Here you go." : ChatIntents.ownWords(player.name(), refusal[0] == null ? "That can't be done now." : refusal[0]));
    }

    /** Whether an offer to this person is waiting for their answer. */
    boolean offering(UUID id, UUID person) {
        return offers.containsKey(key(id, person));
    }

    private Track track(ServerPlayerEntity person, long now) {
        Track t = tracks.computeIfAbsent(person.getUuid(), ignored -> new Track());
        Vec3d at = person.getPos();
        if (t.at == null || t.at.squaredDistanceTo(at) > 0.01) {
            t.at = at;
            t.movedAt = now;
        }
        var stack = person.getMainHandStack();
        int held = stack.getItem() instanceof BlockItem ? stack.getCount() : -1;
        if (held >= 0 && t.held >= 0 && t.held - held >= 2) t.placingUntil = now + 5_000; // Two or more placed in a second.
        t.held = held;
        return t;
    }

    /** In a fight: it struck, or was struck, in the last ten seconds. */
    static boolean fighting(ServerPlayerEntity person) {
        int age = person.age;
        return person.getLastAttackTime() > 0 && age - person.getLastAttackTime() < 200
                || person.getLastAttackedTime() > 0 && age - person.getLastAttackedTime() < 200;
    }

    /** A monster near a person and behind them, that the player can see: its name, or null. */
    private static String behind(CompanionSocials.Participant player, ServerPlayerEntity person) {
        var box = person.getBoundingBox().expand(DANGER);
        Vec3d look = person.getRotationVec(1).multiply(1, 0, 1);
        for (var mob : person.getWorld().getEntitiesByClass(HostileEntity.class, box, HostileEntity::isAlive)) {
            if (mob.squaredDistanceTo(person) > DANGER * DANGER || !SocialPerception.visible(player.body(), mob)) continue;
            Vec3d to = mob.getPos().subtract(person.getPos()).multiply(1, 0, 1);
            if (to.lengthSquared() < 1e-6 || look.lengthSquared() < 1e-6) continue;
            double cos = look.normalize().dotProduct(to.normalize());
            if (cos < -0.2) return mob.getType().getName().getString().toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }

    private static String key(UUID id, UUID person) { return id + ":" + person; }
}
