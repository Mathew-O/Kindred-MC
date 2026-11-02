package dev.kindred.core.social;

import dev.kindred.core.ConversationAudience;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: the player's own answer, without any model, when someone asks what it knows of another player ("what
 * does Sol need?", "what is Sol doing?", "where is Sol?"): from what it knows, told only as far as the asker may be told,
 * with a claim said as what was said and not seen, and "I don't know" when it does not.
 *
 * Technical: E-50's fixed replies from the ledger. The same reach rules as the voice ({@link ConversationAudience#mayBeTold}):
 * a place to a controller alone; the team's business to its controllers and teammates; what someone said back to them;
 * nothing of others to an opponent.
 */
public final class PeerAnswers {
    private PeerAnswers() { }

    /** The answer to {@code question} from {@code ledger}, as this asker may be told it; empty when it knows nobody by that name. */
    public static Optional<String> answer(Claims.Question question, PeerLedger ledger, ConversationAudience.Relation relation, boolean controls,
                                          String asker, long now) {
        if (question == null || ledger == null) return Optional.empty();
        if (ConversationAudience.tier(relation, controls) == ConversationAudience.Tier.OPPONENT) return Optional.of("I'd rather not say.");
        var id = ledger.named(question.name());
        if (id.isEmpty()) return Optional.empty(); // Nobody it knows by that name: ordinary talk ("what is that doing?").
        UUID peer = id.get();
        String name = ledger.name(peer).orElse(capital(question.name()));
        return Optional.of(switch (question.ask()) {
            case NEEDS -> topic(ledger, peer, name, PeerLedger.Topic.NEEDS, relation, controls, asker, now)
                    .orElse("I don't know of anything " + name + " needs.");
            case DOING -> topic(ledger, peer, name, PeerLedger.Topic.DOING, relation, controls, asker, now)
                    .orElse("I don't know what " + name + " is doing.");
            case WHERE -> where(ledger, peer, name, relation, controls, asker, now);
        });
    }

    private static Optional<String> topic(PeerLedger ledger, UUID peer, String name, PeerLedger.Topic topic,
                                          ConversationAudience.Relation relation, boolean controls, String asker, long now) {
        var parts = new ArrayList<String>();
        var best = ledger.best(peer, topic, now);
        if (best.isPresent()) {
            var note = best.get();
            var told = new PeerLedger.Told(peer, name, "", note.source() == PeerLedger.Source.GAME ? PeerLedger.Reach.ANYONE : PeerLedger.Reach.SIDE, false);
            if (ConversationAudience.mayBeTold(told, relation, controls, asker)) {
                String from = note.source() == PeerLedger.Source.TEAM ? " (" + name + "'s own update, " + PeerLedger.ago(now - note.at()) + ")" : "";
                parts.add(switch (topic) {
                    case NEEDS -> name + " needs " + note.text() + from + ".";
                    case DOING -> name + " is " + note.text() + from + ".";
                    case SPARE -> name + " could spare " + note.text() + from + ".";
                });
            }
        }
        for (var claim : ledger.claims(peer, topic, now)) {
            var told = new PeerLedger.Told(peer, name, "", PeerLedger.Reach.SIDE, true);
            if (!ConversationAudience.mayBeTold(told, relation, controls, asker)) continue;
            parts.add(name + " said, " + PeerLedger.ago(now - claim.at()) + ", that they " + PeerLedger.claimWords(claim) + "; I haven't seen that myself.");
            break;
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", parts));
    }

    private static String where(PeerLedger ledger, UUID peer, String name, ConversationAudience.Relation relation, boolean controls,
                                String asker, long now) {
        var place = ledger.place(peer);
        if (place.isPresent() && ConversationAudience.mayBeTold(new PeerLedger.Told(peer, name, "", PeerLedger.Reach.CONTROLLER, false), relation, controls, asker)) {
            return name + " is at " + place.get().words() + ".";
        }
        var seen = ledger.seen(peer);
        if (seen.isPresent()) {
            String world = seen.get().world() == null ? "" : " in " + PeerLedger.worldWords(seen.get().world());
            return "I last saw " + name + " " + PeerLedger.ago(now - seen.get().at()) + world + "." + going(ledger, peer, name, relation, controls, asker, now);
        }
        String said = going(ledger, peer, name, relation, controls, asker, now);
        return said.isEmpty() ? "I haven't seen " + name + "." : said.strip();
    }

    private static String going(PeerLedger ledger, UUID peer, String name, ConversationAudience.Relation relation, boolean controls,
                                String asker, long now) {
        for (var claim : ledger.claims(peer, PeerLedger.Topic.DOING, now)) {
            if (!ConversationAudience.mayBeTold(new PeerLedger.Told(peer, name, "", PeerLedger.Reach.SIDE, true), relation, controls, asker)) break;
            return " " + name + " said, " + PeerLedger.ago(now - claim.at()) + ", that they " + PeerLedger.claimWords(claim) + "; I haven't seen that myself.";
        }
        return "";
    }

    private static String capital(String name) {
        return name == null || name.isEmpty() ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
