package dev.kindred.fabric;

import dev.kindred.core.SocialProfile;
import dev.kindred.core.social.Expression;
import dev.kindred.core.social.ListenerPreferences;
import dev.kindred.persistence.SqliteCompanionStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: how AI players sound on this server, and how each person has asked to be spoken to. The operator sets
 * the most expressive any player may be ({@code KINDRED_EXPRESSION_CAP}), and whether people who said nothing hear the
 * rough stuff ({@code KINDRED_ROUGH_AUDIENCE}); a person sets, for themselves only, fewer or more lines they did not ask
 * for, no teasing, no jokes, and yes or no to swearing, dark jokes and flirting, and can delete what they set. None of it
 * gives anyone control of a player.
 *
 * Technical: E-18a's server cap and E-19a's per-person preferences: the commands' logic, the reads at startup and the
 * writes through the metadata store, one at a time per person. The social lane ({@link CompanionSocials}) holds what is in
 * force and applies it to every line; a change holds from the next line, before its write completes. Server thread only.
 */
final class TalkSettings {
    /** The operator's cap on every player's preset: {@code plain}, {@code dry}, {@code playful} or {@code rough} (the default). */
    static final String CAP_VARIABLE = "KINDRED_EXPRESSION_CAP";
    /**
     * The operator's say for people who said nothing (E-18b): {@code on} and they hear swearing and dark jokes until they say
     * no; unset or anything else and they hear them only once they say yes. Flirting is never the server's to say.
     */
    static final String ROUGH_AUDIENCE_VARIABLE = "KINDRED_ROUGH_AUDIENCE";

    private final CompanionSocials socials;
    private final MinecraftServer server;
    private SqliteCompanionStore store;
    private final Set<UUID> saving = new HashSet<>();

    TalkSettings(CompanionSocials socials, MinecraftServer server) {
        this.socials = socials;
        this.server = server;
    }

    /**
     * The cap from the environment: rough when unset (what a person hears is still theirs to say yes to); plain, the
     * strictest, for a value it does not know.
     */
    static Expression cap(Map<String, String> environment) {
        String raw = environment.get(CAP_VARIABLE);
        if (raw == null || raw.isBlank()) return Expression.ROUGH;
        var parsed = Expression.parse(raw);
        if (parsed.isEmpty()) KindredMod.LOGGER.warn("{}={} is not plain, dry, playful or rough; every player sounds plain", CAP_VARIABLE, raw);
        return parsed.orElse(Expression.PLAIN);
    }

    /** Whether people who said nothing hear swearing and dark jokes: only when the operator set {@code on}. */
    static boolean roughAudience(Map<String, String> environment) {
        return "on".equalsIgnoreCase(String.valueOf(environment.get(ROUGH_AUDIENCE_VARIABLE)).strip());
    }

    /**
     * The metadata is open: read everyone's saved preferences. Until they are read the lane says nothing unasked and no
     * jokes; if they cannot be read, that holds until a restart.
     */
    void load(SqliteCompanionStore opened) {
        store = opened;
        saving.clear();
        socials.awaitPreferences();
        opened.listenerPreferences().whenComplete((saved, failure) -> server.execute(() -> {
            if (store != opened) return;
            if (failure != null) {
                KindredMod.LOGGER.error("People's preferences for how AI players speak to them could not be read; no jokes and nothing unasked until a restart", failure);
                return;
            }
            socials.loadPreferences(saved);
        }));
    }

    /** {@code /aiplayer prefs}: what this person asked for. */
    int show(ServerCommandSource source) {
        ServerPlayerEntity person = person(source);
        if (person == null) return reject(source, "Only a person has preferences for how AI players speak to them.");
        if (!socials.preferencesKnown()) return reject(source, "Kindred is still reading saved preferences.");
        source.sendFeedback(() -> Text.literal("AI players speak to you with " + describe(socials.preferences(person.getUuid())) + "."), false);
        return 1;
    }

    /**
     * {@code /aiplayer prefs unasked fewer|usual|more}, {@code teasing on|off}, {@code jokes on|off}: changed for this
     * person alone, by them alone.
     */
    int set(ServerCommandSource source, String setting, String value) {
        ServerPlayerEntity person = person(source);
        if (person == null) return reject(source, "Only a person sets how AI players speak to them, and only for themselves.");
        UUID id = person.getUuid();
        if (store == null || !socials.preferencesKnown()) return reject(source, "Kindred is still reading saved preferences.");
        if (saving.contains(id)) return reject(source, "Your previous change is still being saved.");
        ListenerPreferences before = socials.ownPreferences(id); // As they set it, the server's default not read in.
        ListenerPreferences after;
        switch (setting) {
            case "unasked" -> {
                var unasked = ListenerPreferences.Unasked.parse(value);
                if (unasked == null) return reject(source, "Unasked lines are fewer, usual or more.");
                after = before.withUnasked(unasked);
            }
            case "teasing" -> after = before.withNoTeasing(!on(value));
            case "jokes" -> after = before.withNoJokes(!on(value));
            case "swearing", "dark", "flirting" -> {
                var opt = ListenerPreferences.Opt.parse(value);
                if (opt == null) return reject(source, "Swearing, dark jokes and flirting are on, off or default.");
                after = setting.equals("swearing") ? before.withSwearing(opt) : setting.equals("dark") ? before.withDarkHumor(opt) : before.withFlirting(opt);
            }
            default -> { return reject(source, "Set unasked, teasing, jokes, swearing, dark or flirting."); }
        }
        socials.preferencesChanged(after);
        save(source, id, after, "Saved: AI players speak to you with " + describe(socials.preferences(id)) + ".");
        return 1;
    }

    /** {@code /aiplayer prefs forget}: this person's preferences deleted; the defaults hold again. */
    int forget(ServerCommandSource source) {
        ServerPlayerEntity person = person(source);
        if (person == null) return reject(source, "Only a person can delete their own preferences.");
        UUID id = person.getUuid();
        if (store == null || !socials.preferencesKnown()) return reject(source, "Kindred is still reading saved preferences.");
        if (saving.contains(id)) return reject(source, "Your previous change is still being saved.");
        socials.preferencesChanged(ListenerPreferences.defaults(id));
        saving.add(id);
        store.forgetListenerPreferences(id).whenComplete((deleted, failure) -> server.execute(() -> {
            saving.remove(id);
            if (failure != null) {
                KindredMod.LOGGER.error("A person's preferences could not be deleted", failure);
                source.sendError(Text.literal("Your preferences no longer apply, but they could not be deleted from the save; ask again."));
            } else source.sendFeedback(() -> Text.literal(Boolean.TRUE.equals(deleted)
                    ? "Deleted your preferences. AI players speak to you as usual." : "You had no preferences saved."), false);
        }));
        return 1;
    }

    private void save(ServerCommandSource source, UUID id, ListenerPreferences after, String said) {
        saving.add(id);
        store.saveListenerPreferences(after).whenComplete((saved, failure) -> server.execute(() -> {
            saving.remove(id);
            if (failure != null) {
                KindredMod.LOGGER.error("A person's preferences could not be saved", failure);
                source.sendError(Text.literal("This holds until the server restarts, but it could not be saved."));
            } else source.sendFeedback(() -> Text.literal(said), false);
        }));
    }

    /** What a person's preferences come to, in words. */
    static String describe(ListenerPreferences p) {
        String unasked = switch (p.unasked()) {
            case FEWER -> "fewer lines you did not ask for";
            case USUAL -> "the usual lines you did not ask for";
            case MORE -> "more lines you did not ask for";
        };
        String said = unasked + ", " + (p.noTeasing() ? "no teasing" : "teasing between AI players") + " and " + (p.noJokes() ? "no jokes" : "jokes");
        // The rough stuff (E-18b), named once any of it is heard: clean is the default and says nothing new.
        if (!p.swears() && !p.dark() && !p.flirts()) return said;
        return said + "; " + (p.swears() ? "swearing" : "no swearing") + ", " + (p.dark() ? "dark jokes" : "no dark jokes")
                + " and " + (p.flirts() ? "flirting" : "no flirting");
    }

    /** How a player sounds, in words: its preset or its humour, and what the cap makes of it. */
    static String sounds(String name, SocialProfile profile, Expression cap) {
        Expression own = profile.effectiveExpression();
        Expression heard = own.capped(cap);
        String why = profile.expression() != null ? "its preset" : "no preset, so by its humour (" + profile.personality().humor() + ")"
                + (own == Expression.ROUGH ? " and combativeness (" + profile.personality().combativeness() + ")" : "");
        String said = name + " sounds " + heard.id() + ": " + why + (heard != own ? "; " + own.id() + " is above this server's cap of " + cap.id() : "") + ".";
        // How it types and whether it flirts (E-18b), named when either is more than proper and plain.
        var style = profile.effectiveChatStyle();
        if (profile.chatStyle() == null && style == dev.kindred.core.social.ChatStyle.PROPER && !profile.flirty()) return said;
        return said + " It types " + style.id() + (profile.chatStyle() != null ? " (set)" : " (by its personality)")
                + (profile.flirty() ? ", and flirts with whoever says yes to it" : "") + ".";
    }

    private static boolean on(String value) {
        return "on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
    }

    /** The person giving the command; never an AI player, nor the console. */
    private ServerPlayerEntity person(ServerCommandSource source) {
        return source.getEntity() instanceof ServerPlayerEntity player && socials.aiPlayerOf(player) == null ? player : null;
    }

    private static int reject(ServerCommandSource source, String message) {
        source.sendError(Text.literal(message));
        return 0;
    }
}
