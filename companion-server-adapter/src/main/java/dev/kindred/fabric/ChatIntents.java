package dev.kindred.fabric;

import dev.kindred.core.match.MatchChoice;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.core.social.Intent;
import dev.kindred.core.social.IntentGrammar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: an AI player doing what a person asks in chat ("Echo, go for the Nether", "race me", "I'm in", "build a
 * cabin here"), exactly as the command would do it if that person typed it, and saying how it went: done, or why not, in
 * its own words. A race or duel with nobody named is asked back once; a person's own "I'm in" is what puts them in a
 * match, and an AI player named in one says yes or no by its own rule. The operator's switches and anything with
 * coordinates stay commands, and it says which.
 *
 * Technical: E-52's adapter. Reads a message with {@link IntentGrammar} (every reading passes {@link Intent#of}) and runs
 * the intent through the runtime method its command calls, with a command source that is the speaker's own, its output
 * captured: nothing moves on an ask the command would refuse, and the refusal is the command's message, made its own.
 * E-20b's come, wait, stop and toss go to the model when the voice is on, and are read here otherwise. Server thread only.
 */
final class ChatIntents {
    /** How long a question asked back waits for its answer: half a minute of game ticks. */
    static final long ASKED_BACK_TICKS = 600;

    private record Pending(Intent.Kind kind, long until) { }

    private final PlayerRuntime runtime;
    private final MinecraftServer server;
    private final Map<String, Pending> askedBack = new HashMap<>();

    ChatIntents(PlayerRuntime runtime, MinecraftServer server) {
        this.runtime = runtime;
        this.server = server;
    }

    /**
     * An ask in chat to the player {@code player}, other than E-20b's four: run as its command, and what the player says of
     * it; or the question asked back; or empty when the message is no such ask. An answer to a question asked back is read
     * once: naming nobody, the ask is dropped and the message is ordinary talk.
     */
    Optional<String> early(ServerPlayerEntity speaker, CompanionSocials.Participant player, String message) {
        String key = player.id() + ":" + speaker.getUuid();
        Pending pending = askedBack.remove(key);
        if (pending != null && server.getTicks() <= pending.until()) {
            var answered = IntentGrammar.answer(pending.kind(), message);
            if (answered.isPresent()) return Optional.of(run(answered.get(), speaker, player));
            KindredMod.LOGGER.info("{} asked {} back about a {}; the answer named nobody, so it is dropped", player.name(),
                    speaker.getName().getString(), pending.kind());
        }
        var reading = IntentGrammar.read(message);
        if (reading.isEmpty()) return Optional.empty();
        if (reading.get() instanceof IntentGrammar.Unclear unclear) {
            askedBack.put(key, new Pending(unclear.kind(), server.getTicks() + ASKED_BACK_TICKS));
            while (askedBack.size() > 256) askedBack.remove(askedBack.keySet().iterator().next());
            return Optional.of(unclear.question());
        }
        Intent intent = ((IntentGrammar.Ask) reading.get()).intent();
        if (e20b(intent.kind())) return Optional.empty();
        return Optional.of(run(intent, speaker, player));
    }

    /**
     * E-20b's come, wait, stop and toss in their plain forms, when no model reads them: said, then done through the same
     * path as a model's ask (after the player's look at the speaker), a refusal said after it.
     */
    Optional<CompanionSocials.Spoken> fallback(ServerPlayerEntity speaker, CompanionSocials.Participant player, String message) {
        var reading = IntentGrammar.read(message);
        if (reading.isEmpty() || !(reading.get() instanceof IntentGrammar.Ask ask) || !e20b(ask.intent().kind())) return Optional.empty();
        var intent = ask.intent();
        // Only one who may command it is told "Coming." and then has it done; anyone else is told no at once (E-20b's rule).
        if (!runtime.mayCommand(player.id(), speaker)) {
            runtime.chatAskedForFixtures(player.id(), intent.kind() + ": refused - not one who may command it");
            return Optional.of(new CompanionSocials.Spoken("Only an operator or whoever looks after me can ask me that.", null));
        }
        var model = switch (intent.kind()) {
            case COME -> dev.kindred.model.Strategist.Ask.COME;
            case WAIT -> dev.kindred.model.Strategist.Ask.WAIT;
            case STOP -> dev.kindred.model.Strategist.Ask.STOP;
            default -> dev.kindred.model.Strategist.Ask.TOSS;
        };
        String said = switch (intent.kind()) {
            case COME -> "Coming.";
            case WAIT -> "I'll wait.";
            case STOP -> "Stopping.";
            default -> "Here you go.";
        };
        String item = intent.kind() == Intent.Kind.TOSS ? intent.arg() : null;
        return Optional.of(new CompanionSocials.Spoken(said, refused -> runtime.askInChat(player.id(), speaker, model, item, refused)));
    }

    private static boolean e20b(Intent.Kind kind) {
        return kind == Intent.Kind.COME || kind == Intent.Kind.WAIT || kind == Intent.Kind.STOP || kind == Intent.Kind.TOSS;
    }

    /** The intent run as its command, as the speaker; what the player says of how it went. */
    String run(Intent intent, ServerPlayerEntity speaker, CompanionSocials.Participant player) {
        var heard = new ArrayList<String>();
        CommandOutput output = new CommandOutput() {
            @Override public void sendMessage(Text message) { heard.add(message.getString()); }
            @Override public boolean shouldReceiveFeedback() { return true; }
            @Override public boolean shouldTrackOutput() { return true; }
            @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        };
        ServerCommandSource as = speaker.getCommandSource().withOutput(output);
        UUID id = player.id();
        // From someone it is wary of, nothing of theirs goes in (E-49); their own joining, leaving and quiet are theirs.
        if (intent.who() != Intent.Who.SELF && runtime.waryOf(id, speaker.getUuid())) return said(intent, speaker, player, 0, PlayerRuntime.NOT_AFTER_LAST_TIME);
        String spoken = null;
        int done = switch (intent.kind()) {
            case GOAL -> runtime.startGoal(as, id, goal(intent.arg()));
            case MODE -> runtime.setPlaystyleMode(as, id, mode(intent.arg()));
            case TEAM -> team(as, id, intent.arg());
            case RACE, DUEL -> {
                var proposed = propose(intent, as, speaker, player);
                if (proposed.refusal() != null) spoken = proposed.refusal();
                yield proposed.done();
            }
            case JOIN -> join(as, speaker);
            case LEAVE -> leave(as, speaker);
            case BUILD -> runtime.startBuild(as, id, PlayerRuntime.generated(intent.arg()), speaker.getBlockPos(), 0, intent.arg());
            case RANCH -> runtime.startRanch(as, id, speaker.getBlockPos(), dev.kindred.core.survival.Ranch.Kind.valueOf(intent.arg().toUpperCase(java.util.Locale.ROOT)));
            case FARM -> runtime.startField(as, id, speaker.getBlockPos());
            case SKIN -> runtime.setSkin(as, id, intent.arg());
            case QUIET -> runtime.listenerPreference(as, "unasked", "fewer");
            case TALK -> runtime.listenerPreference(as, "unasked", "more");
            case OPERATOR -> {
                spoken = (speaker.hasPermissionLevel(2) ? "That one stays a command: " : "That's not mine to change; an operator can, with ")
                        + operatorCommand(intent.arg()) + ".";
                yield 0;
            }
            case PLACE -> {
                spoken = "Give me a place by command: /aiplayer " + (intent.arg().equals("farm") ? "field" : intent.arg()) + " ... <x y z>.";
                yield 0;
            }
            case COME, WAIT, STOP, TOSS -> 0; // Not here: E-20b's, through fallback.
        };
        String why = spoken != null ? spoken : heard.isEmpty() ? "That can't be done now." : heard.getLast();
        return said(intent, speaker, player, done, why);
    }

    /** {@code why} is the command's last message: its refusal, or what it reported doing. */
    private String said(Intent intent, ServerPlayerEntity speaker, CompanionSocials.Participant player, int done, String why) {
        KindredMod.LOGGER.info("{} asked in chat by {}: {}{}{}: {}", player.name(), speaker.getName().getString(), intent.kind(),
                intent.arg().isEmpty() ? "" : " " + intent.arg(), intent.names().isEmpty() ? "" : " " + intent.names(),
                done == 1 ? "done (" + why + ")" : "refused (" + why + ")");
        runtime.chatAskedForFixtures(player.id(), intent.kind() + (done == 1 ? ": done - " : ": refused - ") + why);
        return done == 1 ? doneWords(intent, player) : ownWords(player.name(), why);
    }

    /** What the player says once it has done what was asked. */
    private static String doneWords(Intent intent, CompanionSocials.Participant player) {
        return switch (intent.kind()) {
            case GOAL -> intent.arg().equals("none") ? "All right, no goal for now." : "On it: " + goal(intent.arg()).label().toLowerCase(java.util.Locale.ROOT) + ".";
            case MODE -> switch (intent.arg()) {
                case "relaxed" -> "All right, I'll take it easy.";
                case "speedrun" -> "Speedrunning, then.";
                default -> "I'll play it safe.";
            };
            case TEAM -> intent.arg().equals("together") ? "We'll work together." : "I'll go it alone.";
            case RACE -> "You're on. Say \"I'm in\" to join the race.";
            case DUEL -> "You're on. Say \"I'm in\" to join the duel.";
            case JOIN -> "You're in.";
            case LEAVE -> "You're out.";
            case BUILD -> "Building a " + intent.arg() + " here.";
            case RANCH -> "A " + intent.arg() + " ranch here it is.";
            case FARM -> "I'll plant a field here.";
            case SKIN -> "How do I look?";
            case QUIET -> "I'll keep it down.";
            case TALK -> "Happy to chat.";
            default -> "Done.";
        };
    }

    /** A command's refusal, said as the player's own: "Echo has no diamond." becomes "I have no diamond." */
    static String ownWords(String name, String text) {
        String t = text.replace("Native player unavailable or administrative access denied.", "Only an operator or whoever looks after me can ask me that.")
                .replaceAll("^Administrative access to ([A-Za-z0-9_]+) denied\\.$", "Only an operator or whoever looks after $1 can ask that.")
                .replace("this player's controller", "whoever looks after me").replace("change its ", "change my ")
                .replace("this player's", "my").replace("This player's", "My");
        if (name != null && !name.isEmpty()) {
            t = t.replace(name + "'s ", "my ");
            if (t.startsWith(name + " is ")) t = "I'm " + t.substring(name.length() + 4);
            else if (t.startsWith(name + " has ")) t = "I have " + t.substring(name.length() + 5);
            else if (t.startsWith(name + " ")) t = "I " + t.substring(name.length() + 1);
        }
        return t;
    }

    private static dev.kindred.core.survival.SurvivalGoal goal(String arg) {
        return switch (arg) {
            case "nether" -> dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL;
            case "end" -> dev.kindred.core.survival.SurvivalGoal.STRONGHOLD;
            case "dragon" -> dev.kindred.core.survival.SurvivalGoal.DRAGON;
            default -> dev.kindred.core.survival.SurvivalGoal.NONE;
        };
    }

    private static dev.kindred.core.progression.PlaystyleProfile mode(String arg) {
        return switch (arg) {
            case "relaxed" -> dev.kindred.core.progression.PlaystyleProfile.IDLE_OBSERVING;
            case "speedrun" -> dev.kindred.core.progression.PlaystyleProfile.SPEEDRUN_ANY_PERCENT;
            default -> dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL;
        };
    }

    /** Together or alone, through the collaboration toggle the panel uses, when it is not that already. */
    private int team(ServerCommandSource as, UUID id, String arg) {
        var wanted = arg.equals("together") ? dev.kindred.core.team.CollaborationMode.COOP_TEAM : dev.kindred.core.team.CollaborationMode.SOLO_INDEPENDENT;
        if (runtime.collaborationOf(id) == wanted) {
            // Already so: the toggle would undo it. Its access is still the command's: asked only of one who may set it.
            if (!runtime.mayAdjustFrom(as, id)) {
                as.sendError(Text.literal("Only an operator or this player's controller may change its settings."));
                return 0;
            }
            as.sendFeedback(() -> Text.literal("Collaboration for " + runtime.nameOf(id) + ": " + wanted + " already"), false);
            return 1;
        }
        return runtime.toggleCollaborationForBot(as, id);
    }

    private record Proposed(int done, String refusal) { }

    /**
     * A race or a duel proposed as the speaker: the player asked and any AI player named answer by their own rule first
     * ({@link MatchChoice}); one that says no is said, and nothing is proposed. Each person named joins by their own "I'm
     * in", as the registry has it.
     */
    private Proposed propose(Intent intent, ServerCommandSource as, ServerPlayerEntity speaker, CompanionSocials.Participant player) {
        var players = new ArrayList<UUID>();
        UUID self = runtime.nativeBodyOf(player.id()).map(ServerPlayerEntity::getUuid).orElse(null);
        if (self == null) return new Proposed(0, "I can't, not without being in the world.");
        players.add(self);
        for (String name : intent.names()) {
            UUID who = name.equals("me") ? speaker.getUuid() : runtime.playerNamed(name).orElse(null);
            if (who == null) return new Proposed(0, "I don't see anyone called " + name + " about.");
            if (!players.contains(who)) players.add(who);
        }
        if (intent.kind() == Intent.Kind.DUEL && players.size() != 2) return new Proposed(0, "A duel is two of us.");
        if (players.size() < 2 && intent.kind() == Intent.Kind.RACE && !players.contains(speaker.getUuid())) players.add(speaker.getUuid());
        Set<UUID> chose = new HashSet<>();
        for (UUID body : players) {
            UUID ai = runtime.aiPlayerFor(body);
            if (ai == null) continue;
            var choice = runtime.matchChoice(ai, players, intent.kind() == Intent.Kind.DUEL ? MatchChoice.Kind.DUEL : MatchChoice.Kind.RACE);
            KindredMod.LOGGER.info("{} on a {} with {}: {} ({})", runtime.nameOf(ai), intent.kind(), players.size() - 1, choice.accepts() ? "yes" : "no", choice.words());
            if (!choice.accepts()) return new Proposed(0, ai.equals(player.id()) ? choice.words() : runtime.nameOf(ai) + " says: " + choice.words());
            chose.add(ai);
        }
        int done = intent.kind() == Intent.Kind.DUEL ? runtime.proposeDuel(as, players.get(0), players.get(1), chose)
                : runtime.proposeSpeedrun(as, List.copyOf(players), chose);
        return new Proposed(done, null);
    }

    /** "I'm in": the newest match the speaker is invited to and has not joined, joined as them; started once everyone has. */
    private int join(ServerCommandSource as, ServerPlayerEntity speaker) {
        MatchRegistry.Match open = null;
        for (var match : runtime.matchRegistry().list()) {
            if (match.state() != MatchRegistry.State.LOBBY || !match.invited().contains(speaker.getUuid()) || match.joined().contains(speaker.getUuid())) continue;
            open = match;
        }
        if (open == null) {
            as.sendError(Text.literal("There's no match waiting for you to join."));
            return 0;
        }
        int joined = runtime.joinMatch(as, open.id());
        if (joined != 1) return 0;
        var now = runtime.matchRegistry().get(open.id()).orElse(null);
        if (now != null && now.joined().containsAll(now.invited())) runtime.startMatch(as, open.id());
        return 1;
    }

    /** "Count me out": the speaker's own match left, as a player in it stops it. */
    private int leave(ServerCommandSource as, ServerPlayerEntity speaker) {
        var match = runtime.matchRegistry().active(speaker.getUuid()).orElse(null);
        if (match == null) {
            as.sendError(Text.literal("You're not in a match."));
            return 0;
        }
        return runtime.stopMatch(as, match.id());
    }

    private static String operatorCommand(String arg) {
        return switch (arg) {
            case "edits" -> "/aiplayer edits on|off";
            case "dragonfight" -> "/aiplayer dragonfight on";
            case "bases" -> "/aiplayer bases on";
            case "create" -> "/aiplayer native create <name>";
            case "remove" -> "/aiplayer remove <player>";
            default -> "/aiplayer limit";
        };
    }
}
