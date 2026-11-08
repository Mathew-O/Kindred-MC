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
 * {@link KindredWire.Knowledge}. Server thread only; in memory until F-31's Record is on main, when the webs are rebuilt
 * from its events after a restart (F-32b).
 */
final class KnowledgeWebs implements KindredWire.Knowledge {
    private final Map<UUID, KnowledgeWeb> webs = new HashMap<>();
    private final LongSupplier tick;

    KnowledgeWebs(LongSupplier tick) { this.tick = tick; }

    /** This AI player's web, made empty when it has none yet. */
    KnowledgeWeb of(UUID ai) { return webs.computeIfAbsent(ai, KnowledgeWeb::new); }

    Optional<KnowledgeWeb> existing(UUID ai) { return Optional.ofNullable(webs.get(ai)); }

    @Override public void told(Delivery.Told told, Listener receiver, boolean senderIsPerson) {
        if (receiver == null || receiver.person()) return;
        WebFeed.told(of(receiver.id()), told, receiver, senderIsPerson, tick.getAsLong());
    }
}
