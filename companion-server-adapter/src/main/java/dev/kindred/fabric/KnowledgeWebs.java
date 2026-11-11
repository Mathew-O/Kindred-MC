package dev.kindred.fabric;

import dev.kindred.core.knowledge.KnowledgeWeb;
import dev.kindred.core.knowledge.LegacyMemory;
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
 * fed as each event is written and rebuilt from the Record at start; the players' own webs hold what they were told,
 * and, read once a start after that rebuild, what each one's old memory files kept ({@link LegacyMemory}: its peer
 * ledger and its atlas), so nothing it knew before the web is lost.
 */
final class KnowledgeWebs implements KindredWire.Knowledge {
    private final Map<UUID, KnowledgeWeb> webs = new HashMap<>();
    /** What everyone may know, from the World Record (F-32b): claims and their ends, corrections. */
    private KnowledgeWeb world = new KnowledgeWeb();
    private boolean rebuilt;
    /** The AI players whose old files have been read since the rebuild: once each a start. */
    private final java.util.Set<UUID> oldRead = new java.util.HashSet<>();
    private long oldAdded;
    private final LongSupplier tick;

    KnowledgeWebs(LongSupplier tick) { this.tick = tick; }

    /** This AI player's web, made empty when it has none yet. */
    KnowledgeWeb of(UUID ai) { return webs.computeIfAbsent(ai, KnowledgeWeb::new); }

    Optional<KnowledgeWeb> existing(UUID ai) { return Optional.ofNullable(webs.get(ai)); }

    /** How many webs, and how many relations they hold in all (the month-long cost check). */
    int count() { return webs.size(); }

    long relations() {
        long n = 0;
        for (var w : webs.values()) n += w.size();
        return n;
    }

    /** The world's web. */
    KnowledgeWeb world() { return world; }

    /** Whether the world's web has been rebuilt from the Record since start. */
    boolean rebuilt() { return rebuilt; }

    /** The world's web rebuilt from the Record's events (read off the server thread), replacing what it held. */
    void rebuild(java.util.List<dev.kindred.core.record.WorldEvent> events) {
        world = dev.kindred.core.knowledge.RecordFeed.replay(events);
        webs.clear(); // What each was told, again, from the deliveries the Record kept.
        oldRead.clear(); // And their old files read again, into the webs rebuilt.
        for (var e : events) dev.kindred.core.knowledge.WebFeed.replay(this::of, e);
        rebuilt = true;
    }

    /**
     * Whether this AI player's old files are to be read now, marking them as read: true once a start, and only once the
     * webs have been rebuilt (a rebuild would empty what was read before it).
     */
    boolean readOldOnce(UUID ai) { return rebuilt && oldRead.add(ai); }

    /**
     * What this AI player's old files kept, read into its web (either may be absent): its peer ledger's facts for
     * {@code team}, its atlas's finds its own. Reading the same again adds nothing.
     */
    LegacyMemory.Read readOld(UUID ai, dev.kindred.core.social.PeerLedger.Snapshot peers, dev.kindred.core.atlas.Atlas.Snapshot atlas, String team) {
        var web = of(ai);
        var read = new LegacyMemory.Read(0, 0);
        if (peers != null && ai.equals(peers.self())) {
            var r = LegacyMemory.peers(web, peers, team);
            read = new LegacyMemory.Read(read.added() + r.added(), read.kept() + r.kept());
        }
        if (atlas != null) {
            var r = LegacyMemory.atlas(web, ai, atlas);
            read = new LegacyMemory.Read(read.added() + r.added(), read.kept() + r.kept());
        }
        oldAdded += read.added();
        return read;
    }

    /** How many of the players' old files' facts were added to their webs since start. */
    long oldAdded() { return oldAdded; }

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
