package dev.kindred.core.knowledge;

import dev.kindred.core.wire.Delivery;
import dev.kindred.core.wire.Fact;
import dev.kindred.core.wire.Listener;
import dev.kindred.core.wire.Visibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * In plain terms: how what an AI player is told becomes what it knows. Each fact in a message that names a way two
 * things stand ("needs", "has spare", "saw at") becomes a fact in its web about the sender, kept as told (or as a claim
 * when a person only said it), resting on the message's events, and still only for those the fact was for: what its
 * faction told it stays the faction's, what someone told it privately of themselves stays theirs.
 *
 * Technical: F-32b's feed from F-34's {@link Delivery.Told}. A fact key equal to a {@link KnowledgeWeb.Verb} id
 * (any case, {@code -} or {@code _}) maps to that verb with the fact's value as its object; a key {@code <who>_at}
 * maps to {@code saw_at} about the sender's {@code <who>} ({@code runner_at} about "runner"); other keys are the
 * message's own business and are not kept. The relation's group is the sender's for that visibility, taken from the
 * receiver (who shares it, or the Wire would not have delivered it); a self fact's group is the sender's entity.
 */
public final class WebFeed {
    private WebFeed() { }

    /** The entity id of a player or person. */
    public static String entityOf(UUID id, boolean person) { return (person ? "person:" : "player:") + id; }

    /** What {@code receiver} learns from a told message into its own web at {@code tick}; the relations added. */
    public static List<KnowledgeWeb.Relation> told(KnowledgeWeb web, Delivery.Told told, Listener receiver, boolean senderIsPerson, long tick) {
        var out = new ArrayList<KnowledgeWeb.Relation>();
        var message = told.message();
        String subject = message.fromServer() ? "server" : entityOf(message.from(), senderIsPerson);
        for (Map.Entry<String, Fact> e : message.facts().entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT).replace('-', '_');
            Fact fact = e.getValue();
            if (fact.value().isEmpty()) continue;
            KnowledgeWeb.Verb verb = verbOf(key);
            String about = subject, object = fact.value();
            if (verb == null && key.endsWith("_at") && key.length() > 3) {
                verb = KnowledgeWeb.Verb.SAW_AT;
                about = subject + "/" + key.substring(0, key.length() - 3);
            }
            if (verb == null) continue;
            out.add(web.add(about, verb, object, "", told.howKnown(), message.eventIds(), tick, fact.visibility(),
                    group(fact.visibility(), receiver, subject, message.scope().key())));
        }
        return out;
    }

    private static KnowledgeWeb.Verb verbOf(String key) {
        for (var v : KnowledgeWeb.Verb.values()) if (v.id().equals(key)) return v;
        return null;
    }

    private static String group(Visibility v, Listener receiver, String sender, String scopeKey) {
        return switch (v) {
            case SELF -> sender;
            case FACTION -> receiver.faction();
            case TEAM -> receiver.teams().contains(scopeKey) ? scopeKey : receiver.teams().stream().sorted().findFirst().orElse("");
            case SIDE -> receiver.side();
            case MATCH -> receiver.match();
            case SETTLEMENT -> receiver.settlement();
            case PUBLIC, OPERATOR -> "";
        };
    }
}
