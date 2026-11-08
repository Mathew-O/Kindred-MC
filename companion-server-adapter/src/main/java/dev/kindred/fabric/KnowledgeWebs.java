package dev.kindred.fabric;

import dev.kindred.core.knowledge.KnowledgeWeb;
import dev.kindred.core.knowledge.WebFeed;
import dev.kindred.core.wire.Delivery;
import dev.kindred.core.wire.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * In plain terms: what each AI player knows, one web each, filled from what it is told on the Wire. Asked on someone's
 * behalf, it answers only what that one may know.
 *
 * Technical: F-32's adapter registry: a {@link KnowledgeWeb} per AI player, created on first use, fed by
 * {@link KindredWire.Knowledge}. Server thread only. The world's web holds what the World Record tells everyone (F-32b),
 * fed as each event is written and rebuilt from the Record at start; the players' own webs hold what they were told.
 */
final class KnowledgeWebs implements KindredWire.Knowledge {
    private final Map<UUID, KnowledgeWeb> webs = new HashMap<>();
    /** What everyone may know, from the World Record (F-32b): claims and their ends, corrections. */
    private KnowledgeWeb world = new KnowledgeWeb();
    private boolean rebuilt;
    private final LongSupplier tick;

    KnowledgeWebs(LongSupplier tick) { this.tick = tick; }

    /** This AI player's web, made empty when it has none yet. */
    KnowledgeWeb of(UUID ai) { return webs.computeIfAbsent(ai, KnowledgeWeb::new); }

    Optional<KnowledgeWeb> existing(UUID ai) { return Optional.ofNullable(webs.get(ai)); }

    /** The world's web. */
    KnowledgeWeb world() { return world; }

    /** Whether the world's web has been rebuilt from the Record since start. */
    boolean rebuilt() { return rebuilt; }

    /** The world's web rebuilt from the Record's events (read off the server thread), replacing what it held. */
    void rebuild(java.util.List<dev.kindred.core.record.WorldEvent> events) {
        world = dev.kindred.core.knowledge.RecordFeed.replay(events);
        webs.clear(); // What each was told, again, from the deliveries the Record kept.
        for (var e : events) dev.kindred.core.knowledge.WebFeed.replay(this::of, e);
        rebuilt = true;
    }

    /** The kinds the webs are rebuilt from: the world's, and the deliveries. */
    static final java.util.Set<String> KINDS = union();

    private static java.util.Set<String> union() {
        var all = new java.util.HashSet<>(dev.kindred.core.knowledge.RecordFeed.KINDS);
        all.add(dev.kindred.core.knowledge.WebFeed.MESSAGE);
        return java.util.Set.copyOf(all);
    }

    /** An event the Record has just written, into the world's web. */
    void recorded(dev.kindred.core.record.WorldEvent event) {
        if (dev.kindred.core.knowledge.RecordFeed.KINDS.contains(event.kind())) dev.kindred.core.knowledge.RecordFeed.apply(world, event);
    }

    @Override public void told(Delivery.Told told, Listener receiver, boolean senderIsPerson) {
        if (receiver == null || receiver.person()) return;
        WebFeed.told(of(receiver.id()), told, receiver, senderIsPerson, tick.getAsLong());
    }
}
