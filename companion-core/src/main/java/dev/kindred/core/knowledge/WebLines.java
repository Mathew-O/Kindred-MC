package dev.kindred.core.knowledge;

import dev.kindred.core.social.PeerLedger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In plain terms: what a player knows from its web, put as short lines its voice may use with one listener, and only
 * the facts that listener may know: an opponent gets only what is public, a teammate what their side shares, nobody a
 * fact meant for others. Something only said is marked as said.
 *
 * Technical: F-32's reader for the voice's situation ({@code ConversationAudience.Situation.peers}). The web filters
 * by the listener's viewer first; the voice's own tiers (E-20a) then apply as before, so a fact must pass both. Each
 * line is {@link PeerLedger.Reach#ANYONE} as the web has already decided who may know it, a claim marked so.
 */
public final class WebLines {
    private WebLines() { }

    /** At most {@code max} lines of what {@code listener} may know from {@code web} at {@code tick}, surest first. */
    public static List<PeerLedger.Told> forListener(KnowledgeWeb web, KnowledgeWeb.Viewer listener, long tick, int max) {
        var out = new ArrayList<PeerLedger.Told>();
        for (var r : web.query(listener, tick, x -> true)) {
            if (out.size() >= max) break;
            String name = web.entity(r.subject()).map(KnowledgeWeb.Entity::name).filter(n -> !n.isEmpty()).orElse(r.subject());
            String text = name + " " + r.verb().id().replace('_', ' ') + " " + r.object() + (r.value().isEmpty() ? "" : " (" + r.value() + ")")
                    + (r.claimed() ? ", as was said" : "");
            out.add(new PeerLedger.Told(about(r.subject()), name, text,
                    PeerLedger.Reach.ANYONE, r.claimed()));
        }
        return List.copyOf(out);
    }

    private static UUID about(String subject) {
        int colon = subject.indexOf(':');
        String rest = colon < 0 ? subject : subject.substring(colon + 1);
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        try {
            return UUID.fromString(rest);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
