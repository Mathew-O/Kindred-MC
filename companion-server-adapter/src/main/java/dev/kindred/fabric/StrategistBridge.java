package dev.kindred.fabric;

import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.StrategyAdvice;
import dev.kindred.model.Strategist;
import dev.kindred.model.Strategists;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: asks the optional AI adviser how an AI player should go about its goal, now and then and
 * never every moment: when something worth stopping to think about happens, and otherwise every few minutes.
 * It keeps to set limits on how often and how much it asks, never holds up the game, and the game plays the
 * same without it.
 *
 * Technical: Consults the optional model strategist for AI players with a standing goal (F-19b). Never per
 * tick: only when something changed that a player would stop and think about (the goal starting, a new stage,
 * a death, and at most every two minutes a spell with no progress) and otherwise at most every five minutes.
 * Within the planning lane's bounds (AI-05): one request in flight per player and one at a time in all, at
 * most six a minute per player and twelve in all, and daily token budgets per player and in all (50,000 and
 * 100,000 unless the operator sets others), reserved before each request for its largest possible size and
 * settled to what it used; a failure with no usage keeps its reservation; input read back from the provider's
 * prompt cache counts for a tenth, as it is billed, since the instructions and the Minecraft guide (F-19e)
 * are the same on every request. Requests go out off the game thread; the advice comes back to it and is
 * applied only if the same goal is still running. Each player's memory in this world (notes, deaths, advice,
 * cost) lives in its context file. Off unless {@code KINDRED_STRATEGIST=on} and {@code ANTHROPIC_API_KEY} are
 * set; the game plays the same without it.
 */
final class StrategistBridge implements AutoCloseable {
    static final int PLAYER_PER_MINUTE = 6, GLOBAL_PER_MINUTE = 12;

    /**
     * How closely the strategist follows play ({@code KINDRED_STRATEGIST_PACE}). Steady asks on the big changes and
     * otherwise every few minutes, within the contract's default daily budgets. Real time asks as often as the
     * planning lane's rate allows (every ten seconds per player at most), also whenever the goal reports something new
     * (a fight, a heavy hit, nightfall), and its advice lapses after a minute of game time unless renewed; its default
     * budgets are sized for hours of continuous play (about two thousand tokens a question, most of them cached).
     */
    enum Pace {
        STEADY(20_000, 120_000, 300_000, false, PortalRunCampaign.ADVICE_TICKS, 50_000, 100_000),
        REALTIME(10_000, 30_000, 60_000, true, 1_200, 5_000_000, 10_000_000);

        final long minGapMs, stuckGapMs, periodMs, adviceTicks, playerDailyTokens, dailyTokens;
        final boolean onEvents;

        Pace(long minGapMs, long stuckGapMs, long periodMs, boolean onEvents, long adviceTicks, long playerDailyTokens, long dailyTokens) {
            this.minGapMs = minGapMs; this.stuckGapMs = stuckGapMs; this.periodMs = periodMs; this.onEvents = onEvents;
            this.adviceTicks = adviceTicks; this.playerDailyTokens = playerDailyTokens; this.dailyTokens = dailyTokens;
        }
    }

    private static final class State {
        ModelContextStore.PlayerContext context;
        boolean loading, inFlight, asking, talking, rescueInFlight;
        long lastAt, tokensToday, lessonsAt = Long.MIN_VALUE, rescueAt;
        /** The stuck spell last asked about, asked or turned away (F-29), and its round (F-29c): each round is asked once. */
        int rescueSpell, rescueRound;
        int deaths = -1, stuck = -1, deathsRecorded, events = -1, strandingsRecorded;
        dev.kindred.core.survival.PortalRunPlanner.Stage stage;
        String progress = "";
        PortalRunCampaign campaign, taught;
        dev.kindred.core.guide.TaskQueue queued;
        final ArrayDeque<Long> recent = new ArrayDeque<>();
    }

    private final Strategist strategist;
    private final ModelContextStore store;
    private final Pace pace;
    private final long playerDailyTokens, dailyTokens;
    private final Map<UUID, State> states = new HashMap<>();
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private long dayStartMs = System.currentTimeMillis(), tokensToday;
    private boolean budgetLogged;
    /** Whether its own lessons are drawn from its deaths (E-46): on unless the operator or a scenario turns them off. */
    private boolean lessons = true;
    /** Whether a stuck player asks for a way out (F-29), and whether it asks for advice: both on unless turned off. */
    private boolean rescue = true, advice = true;
    /** How far apart one player's rescues are, at the least: five minutes. */
    static final long RESCUE_GAP_MS = 300_000;

    /**
     * What a rescue needs of the runtime (F-29): the player's fence and whether it still holds, where it stands, the
     * skills installed on this server, and the scene captured as a replay before a plan runs (F-27), when replays are on.
     */
    interface RescueHost {
        dev.kindred.core.ProposalFence fence(UUID id);

        boolean current(UUID id, dev.kindred.core.ProposalFence fence);

        net.minecraft.util.math.BlockPos where(UUID id);

        java.util.Set<String> installed();

        void capture(UUID id);
    }

    private RescueHost rescueHost;

    void rescueHost(RescueHost host) { rescueHost = host; }

    /** Rescue plans on or off (F-29); {@code KINDRED_RESCUE=off} or a scenario turns them off. */
    void rescue(boolean on) { rescue = on; }

    /** The strategist's advice on or off; off, a strategist is asked only for rescues (an evaluation's {@code advice=off}). */
    void advice(boolean on) { advice = on; }

    private StrategistBridge(Strategist strategist, ModelContextStore store, Pace pace, long playerDailyTokens, long dailyTokens) {
        this.strategist = strategist;
        this.store = store;
        this.pace = pace;
        this.playerDailyTokens = playerDailyTokens;
        this.dailyTokens = dailyTokens;
    }

    private static long tokensSetting(String name, long fallback) {
        try {
            String set = System.getenv(name);
            return set == null || set.isBlank() ? fallback : Math.max(0, Long.parseLong(set.trim()));
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    /**
     * A bridge when the strategist is switched on and a provider is set up (F-19d: Anthropic, OpenAI, Gemini, Groq,
     * Mistral, DeepSeek, xAI, OpenRouter or any OpenAI-compatible server), else nothing.
     */
    static Optional<StrategistBridge> fromEnvironment(Path worldKindredFolder) {
        if (!"on".equalsIgnoreCase(System.getenv("KINDRED_STRATEGIST"))) return Optional.empty();
        Optional<Strategist> strategist;
        try {
            var built = Strategists.fromEnvironment(System.getenv());
            if (built.problem().isPresent()) {
                KindredMod.LOGGER.warn("KINDRED_STRATEGIST is on but {}; playing without a strategist", built.problem().get());
                return Optional.empty();
            }
            strategist = built.strategist();
        } catch (LinkageError missing) { // The release jar does not carry the Anthropic SDK; development runs do.
            KindredMod.LOGGER.warn("KINDRED_STRATEGIST is on with Anthropic but its SDK is not installed; playing without a strategist "
                    + "(the other providers need nothing more)");
            return Optional.empty();
        }
        if (strategist.isEmpty()) {
            KindredMod.LOGGER.warn("KINDRED_STRATEGIST is on but no provider's API key is set; playing without a strategist");
            return Optional.empty();
        }
        Pace pace = "realtime".equalsIgnoreCase(String.valueOf(System.getenv("KINDRED_STRATEGIST_PACE")).trim()) ? Pace.REALTIME : Pace.STEADY;
        long playerDaily = tokensSetting("KINDRED_STRATEGIST_PLAYER_DAILY_TOKENS", pace.playerDailyTokens);
        long daily = tokensSetting("KINDRED_STRATEGIST_DAILY_TOKENS", pace.dailyTokens);
        KindredMod.LOGGER.info("Kindred strategist on: {} model {} ({}), pace {}, daily token budgets {} a player and {} in all",
                strategist.get().provider(), strategist.get().model(), strategist.get().pace(), pace.name().toLowerCase(java.util.Locale.ROOT), playerDaily, daily);
        return Optional.of(new StrategistBridge(strategist.get(), new ModelContextStore(worldKindredFolder), pace, playerDaily, daily));
    }

    /** A bridge to a strategist given here, for fixtures: the steady pace and its budgets. */
    static StrategistBridge of(Strategist strategist, Path worldKindredFolder) {
        return new StrategistBridge(strategist, new ModelContextStore(worldKindredFolder), Pace.STEADY, Pace.STEADY.playerDailyTokens,
                Pace.STEADY.dailyTokens);
    }

    /**
     * The player's memory with no strategist (E-46): the same notes, habits, deaths and follow-ups, kept in the same
     * files, and its own lessons drawn from them; nothing is ever asked of a model.
     */
    static StrategistBridge memoryOnly(Path worldKindredFolder) {
        return new StrategistBridge(null, new ModelContextStore(worldKindredFolder), Pace.STEADY, 0, 0);
    }

    /** Whether a model stands behind it, or it is only the player's memory. */
    boolean consults() { return strategist != null; }

    /** Its own lessons on or off (E-46). */
    void lessons(boolean on) { lessons = on; }

    /**
     * Ask one question for a player (E-44, E-16d), on the game thread: within the same rates and daily budgets as its
     * advice, one question at a time for each player, off the game thread, the answer handed back on it. Nothing, at
     * once, when the budgets or rates leave no room or a question of its is already out: the player then answers by
     * its own rule.
     */
    java.util.concurrent.CompletableFuture<Optional<Strategist.Answer>> ask(UUID id, String name, Strategist.Question question, MinecraftServer server) {
        State state = states.computeIfAbsent(id, key -> new State());
        var answered = new java.util.concurrent.CompletableFuture<Optional<Strategist.Answer>>();
        long reserved = Strategist.reservation(question);
        if (strategist == null || state.asking || !admit(System.currentTimeMillis(), state, reserved)) {
            answered.complete(Optional.empty());
            return answered;
        }
        state.asking = true;
        strategist.ask(question).whenComplete((answer, failure) -> server.execute(() -> {
            state.asking = false;
            if (failure != null || answer == null) { // The reservation stands: it may still have been billed.
                KindredMod.LOGGER.warn("{} strategist: question failed: {}", name, String.valueOf(failure));
                answered.complete(Optional.empty());
                return;
            }
            long used = answer.inputTokens() - answer.cachedTokens() + (answer.cachedTokens() + 9) / 10 + answer.outputTokens();
            if (used > 0) {
                tokensToday = Math.max(0, tokensToday + used - reserved);
                state.tokensToday = Math.max(0, state.tokensToday + used - reserved);
            }
            if (answer.failure().isPresent()) KindredMod.LOGGER.info("{} strategist: no answer ({})", name, answer.failure().get());
            answered.complete(answer.failure().isPresent() ? Optional.empty() : Optional.of(answer));
        }));
        return answered;
    }

    /**
     * Answer someone who spoke to a player (E-20a), on the game thread: within the same rates and daily budgets as its
     * advice and questions, one conversation at a time for each player (its own lane, apart from advice and questions),
     * off the game thread, the line handed back on it. Nothing, at once, when the budgets or rates leave no room or a
     * line of its is already being phrased: its fixed reply is then said. Each exchange is one line in the log.
     */
    java.util.concurrent.CompletableFuture<Optional<Strategist.Line>> converse(UUID id, String name, String listener,
                                                                                 Strategist.Conversation conversation, MinecraftServer server) {
        State state = states.computeIfAbsent(id, key -> new State());
        var answered = new java.util.concurrent.CompletableFuture<Optional<Strategist.Line>>();
        long reserved = Strategist.reservation(conversation);
        if (strategist == null || state.talking || !admit(System.currentTimeMillis(), state, reserved)) {
            answered.complete(Optional.empty());
            return answered;
        }
        if (state.context == null && !state.loading) { // Its memory of this world, for the counters and what it remembers.
            state.loading = true;
            store.load(id, name).whenComplete((context, failure) -> server.execute(() -> {
                state.loading = false;
                if (state.context == null) state.context = context != null ? context : new ModelContextStore.PlayerContext();
            }));
        }
        state.talking = true;
        strategist.converse(conversation).whenComplete((given, failure) -> server.execute(() -> {
            state.talking = false;
            // Checked here again, whoever served it: a provider that let a place or a command through is still refused.
            var line = given == null || given.say().isEmpty() ? given : Strategist.Line.checked(conversation, given.say().get(), given.ask().name(),
                    given.item().orElse(null), given.reason(), given.inputTokens(), given.cachedTokens(), given.outputTokens(), given.millis());
            if (failure != null || line == null) { // The reservation stands: it may still have been billed.
                KindredMod.LOGGER.warn("{} voice: no line for {}: {}", name, listener, String.valueOf(failure));
                answered.complete(Optional.empty());
                return;
            }
            long used = line.inputTokens() - line.cachedTokens() + (line.cachedTokens() + 9) / 10 + line.outputTokens();
            if (used > 0) {
                tokensToday = Math.max(0, tokensToday + used - reserved);
                state.tokensToday = Math.max(0, state.tokensToday + used - reserved);
            }
            if (state.context != null) {
                state.context.conversations++;
                state.context.inputTokens += line.inputTokens();
                state.context.outputTokens += line.outputTokens();
                store.save(id, state.context);
            }
            String cost = String.format(java.util.Locale.ROOT, "to %s, %,d in / %,d out, %.1f s", listener, line.inputTokens(), line.outputTokens(),
                    line.millis() / 1000.0);
            if (line.say().isPresent()) KindredMod.LOGGER.info("<{}> {} ({}{})", name, line.say().get(), cost,
                    line.ask() == Strategist.Ask.NONE ? "" : "; asked " + line.ask() + line.item().map(item -> " " + item).orElse(""));
            else KindredMod.LOGGER.info("{} voice: no line ({}); the fixed reply instead ({})", name, line.failure().orElse("refused"), cost);
            answered.complete(line.say().isPresent() ? Optional.of(line) : Optional.empty());
        }));
        return answered;
    }

    /** A player's latest Chronicle pages (F-33), set by the runtime; none until it is. */
    private java.util.function.Function<UUID, List<dev.kindred.core.chronicle.Chronicle.Summary>> chronicle = id -> List.of();

    void chronicle(java.util.function.Function<UUID, List<dev.kindred.core.chronicle.Chronicle.Summary>> pages) {
        chronicle = pages == null ? id -> List.of() : pages;
    }

    /** Whether a model writes the Chronicle's pages (F-33); without one they are the plain list. */
    boolean writesPages() { return strategist != null; }

    /**
     * One page of a player's Chronicle (F-33), off the game thread, within that player's and the server's daily budgets
     * and rates; the page handed back on the game thread, or nothing when there is no room or it failed (the plain list
     * is kept then).
     */
    java.util.concurrent.CompletableFuture<Optional<Strategist.Page>> page(UUID id, String name, String request, MinecraftServer server) {
        State state = states.computeIfAbsent(id, key -> new State());
        var paged = new java.util.concurrent.CompletableFuture<Optional<Strategist.Page>>();
        long reserved = (dev.kindred.model.ChroniclePrompt.SYSTEM.length() + request.length()) / 3 + 100 + Strategist.CHRONICLE_MAX_OUTPUT_TOKENS;
        if (strategist == null || !admit(System.currentTimeMillis(), state, reserved)) {
            paged.complete(Optional.empty());
            return paged;
        }
        strategist.chronicle(request).whenComplete((page, failure) -> server.execute(() -> {
            if (failure != null || page == null) {
                KindredMod.LOGGER.warn("{} chronicle: page failed: {}", name, String.valueOf(failure));
                paged.complete(Optional.empty());
                return;
            }
            long used = page.inputTokens() - page.cachedTokens() + (page.cachedTokens() + 9) / 10 + page.outputTokens();
            if (used > 0) {
                tokensToday = Math.max(0, tokensToday + used - reserved);
                state.tokensToday = Math.max(0, state.tokensToday + used - reserved);
            }
            if (page.failure().isPresent()) KindredMod.LOGGER.info("{} chronicle: no page ({})", name, page.failure().get());
            paged.complete(page.failure().isPresent() ? Optional.empty() : Optional.of(page));
        }));
        return paged;
    }

    /** The tokens a player's questions and talk have used today, as charged against its budget (F-26's run reads it). */
    long tokensToday(UUID id) {
        State state = states.get(id);
        return state == null ? 0 : state.tokensToday;
    }

    /**
     * What a player remembers of this world, as its context file holds it: its notes (each with the habit it carries)
     * and its deaths, newest last; nothing until the file is read.
     */
    java.util.List<String> remembered(UUID id) {
        State state = states.get(id);
        if (state == null || state.context == null) return java.util.List.of();
        var kept = new ArrayList<String>(state.context.noteTexts());
        for (var death : state.context.deaths) kept.add("you died at " + death.x() + ", " + death.y() + ", " + death.z() + ": " + death.cause());
        return kept.size() <= 8 ? kept : kept.subList(kept.size() - 8, kept.size());
    }

    /** Its notes as the report shows them, each with the habit it carries, for fixtures (E-46); nothing until its file is read. */
    java.util.List<String> notes(UUID id) {
        State state = states.get(id);
        return state == null || state.context == null ? java.util.List.of() : state.context.noteTexts();
    }

    /** Called on the game thread after a goal's step was chosen; cheap when nothing changed. */
    void tick(UUID id, String name, PortalRunCampaign campaign, ServerPlayerEntity player, MinecraftServer server) {
        tick(id, name, campaign, player, server, "");
    }

    /**
     * As {@link #tick(UUID, String, PortalRunCampaign, ServerPlayerEntity, MinecraftServer)}, with what it has stored in
     * its chests as it knows it ("3 diamonds and a saddle"), told with what it holds (E-42, E-43).
     */
    void tick(UUID id, String name, PortalRunCampaign campaign, ServerPlayerEntity player, MinecraftServer server, String stored) {
        State state = states.computeIfAbsent(id, key -> new State());
        if (state.campaign != campaign) { // A new goal: everything is new to it.
            state.campaign = campaign;
            state.lastAt = 0;
            state.deaths = -1;
            state.deathsRecorded = campaign.deaths();
        }
        if (state.context == null) {
            if (!state.loading) {
                state.loading = true;
                store.load(id, name).whenComplete((context, failure) -> server.execute(() -> {
                    state.loading = false;
                    state.context = context != null ? context : new ModelContextStore.PlayerContext();
                }));
            }
            return;
        }
        // The habits this player keeps in this world go to each goal it starts, before anything is asked, with any the goal
        // was handed before its memory was read (an operator's or a fixture's advice); and so do its follow-ups, those it
        // was handed after those it remembers, a need in both as it was handed.
        if (state.taught != campaign) {
            var kept = java.util.EnumSet.noneOf(PlanPreferences.Habit.class);
            kept.addAll(state.context.habits());
            kept.addAll(campaign.preferences().habits());
            campaign.habits(kept);
            var queued = new java.util.ArrayList<>(state.context.taskQueue().advised());
            for (var want : campaign.queue().advised()) {
                queued.removeIf(old -> old.need() == want.need());
                queued.add(want);
            }
            campaign.queue(new dev.kindred.core.guide.TaskQueue(queued));
            state.queued = campaign.queue();
            state.taught = campaign;
        }
        // The queue as the goal settled it (follow-ups met, or their task behind the player) goes back into memory.
        if (!campaign.queue().equals(state.queued)) {
            state.queued = campaign.queue();
            state.context.queue(state.queued);
            store.save(id, state.context);
        }
        // Deaths go into this world's memory as they happen, whether or not a consultation follows, with what its
        // lessons read of them (E-46); and so do its strandings.
        boolean fresh = false;
        if (campaign.deaths() > state.deathsRecorded) {
            // Every death since the last look, not only the latest: two in a row (one soon after a respawn) are two.
            for (var death : campaign.deathsAfter(state.deathsRecorded)) {
                var f = death.facts();
                state.context.addDeath(f == null
                        ? new ModelContextStore.DeathRecord(server.getTicks(), death.pos().getX(), death.pos().getY(), death.pos().getZ(), death.cause(), death.stage().name())
                        : new ModelContextStore.DeathRecord(server.getTicks(), death.pos().getX(), death.pos().getY(), death.pos().getZ(), death.cause(),
                                death.stage().name(), f.time(), f.night(), f.underSky(), f.overworld(), f.food(),
                                f.how().name().toLowerCase(java.util.Locale.ROOT), f.ownTunnel()));
            }
            state.deathsRecorded = campaign.deaths();
            fresh = true;
            store.save(id, state.context);
        }
        if (campaign.strandings() > state.strandingsRecorded) {
            state.strandingsRecorded = campaign.strandings();
            fresh = true;
            state.context.addEpisode(campaign.lastStranding(), "stranded");
            store.save(id, state.context);
        }
        if (lessons) learn(id, name, state, campaign, player, server, fresh);
        campaign.rescueOn(strategist != null && rescue && rescueHost != null);
        if (strategist != null && rescue && rescueHost != null) rescue(id, name, state, campaign, player, server);
        if (strategist == null || state.inFlight || !advice) return;
        long now = System.currentTimeMillis();
        // A spell with no progress repeats every few seconds while the player is stuck: one question about it now and then.
        boolean changed = state.lastAt == 0 || campaign.deaths() != state.deaths || campaign.stage() != state.stage
                || campaign.stuckSpells() != state.stuck && now - state.lastAt > pace.stuckGapMs
                || pace.onEvents && campaign.eventCount() != state.events
                || now - state.lastAt > pace.periodMs && !campaign.progress().equals(state.progress);
        if (!changed || now - state.lastAt < pace.minGapMs) return;
        var pages = chronicle.apply(id);
        String report = campaign.strategyReport(player, name, state.context.noteTexts(), pages)
                + (stored.isEmpty() ? "" : "Stored in its chests, as it last counted: " + stored + ".\n");
        if (!pages.isEmpty()) { // F-33's measure: the report as sent, against the same report with its whole list of events.
            int without = campaign.strategyReport(player, name, state.context.noteTexts()).length()
                    + (stored.isEmpty() ? 0 : ("Stored in its chests, as it last counted: " + stored + ".\n").length());
            KindredMod.LOGGER.info("{} strategist report: {} characters with its chronicle, {} without", name, report.length(), without);
        }
        long reserved = Strategist.reservation(report);
        if (!admit(now, state, reserved)) {
            state.lastAt = now; // Not again before the gap is over.
            return;
        }
        var reported = new ArrayList<>(campaign.reportedPlaces());
        for (var death : state.context.deaths) reported.add(new StrategyAdvice.Place(death.x(), death.y(), death.z()));
        state.inFlight = true;
        state.lastAt = now;
        state.deaths = campaign.deaths();
        state.stuck = campaign.stuckSpells();
        state.stage = campaign.stage();
        state.progress = campaign.progress();
        state.events = campaign.eventCount();
        PortalRunCampaign asked = campaign;
        strategist.advise(report, reported).whenComplete((outcome, failure) -> server.execute(() -> {
            state.inFlight = false;
            if (failure != null || outcome == null) { // The reservation stands: it may still have been billed.
                KindredMod.LOGGER.warn("{} strategist: consultation failed: {}", name, String.valueOf(failure));
                return;
            }
            long used = outcome.inputTokens() - outcome.cachedTokens() + (outcome.cachedTokens() + 9) / 10 + outcome.outputTokens();
            if (used > 0) { // Settle the reservation to what was used; without usage it stands.
                tokensToday = Math.max(0, tokensToday + used - reserved);
                state.tokensToday = Math.max(0, state.tokensToday + used - reserved);
            }
            state.context.consultations++;
            state.context.inputTokens += outcome.inputTokens();
            state.context.outputTokens += outcome.outputTokens();
            if (outcome.advice().isPresent() && state.campaign == asked) {
                StrategyAdvice advice = outcome.advice().get();
                long tick = server.getTicks();
                asked.advise(advice, tick, pace.adviceTicks);
                state.context.addAdvice(tick, advice.stance().name() + (advice.focus() == PlanPreferences.Focus.NONE ? "" : ", focus " + advice.focus()),
                        advice.reason());
                // The note is the lesson; a habit it carries is kept in this world from now on, and one retired is not.
                var before = state.context.habits();
                state.context.addNote(tick, advice.note(), advice.habit());
                state.context.retire(advice.retire());
                // A lesson's habit it retired is not taught again from what came before (E-46).
                if (state.context.lessons.remove(advice.retire().name()) != null) {
                    state.context.retiredLessons.put(advice.retire().name(), player.getServerWorld().getTime());
                }
                var after = state.context.habits();
                asked.habits(after);
                state.queued = asked.queue();
                state.context.queue(state.queued);
                KindredMod.LOGGER.info("{} strategist: {}{} ({} in, {} cached, {} out tokens, {} ms) - {}{}", name, advice.stance(),
                        advice.focus() == PlanPreferences.Focus.NONE ? "" : ", focus " + advice.focus(), outcome.inputTokens(),
                        outcome.cachedTokens(), outcome.outputTokens(), outcome.millis(), advice.reason(),
                        advice.avoid().isEmpty() ? "" : "; avoid " + advice.avoid());
                if (!advice.queue().isEmpty()) KindredMod.LOGGER.info("{} strategist: queued {}", name, advice.queue());
                if (!after.equals(before)) {
                    KindredMod.LOGGER.info("{} strategist: habits in this world now {} - {}", name, after, advice.note());
                }
            } else {
                KindredMod.LOGGER.info("{} strategist: no advice ({}){}", name, outcome.failure().orElse("goal changed meanwhile"),
                        state.campaign == asked ? "" : "; discarded");
            }
            store.save(id, state.context);
        }));
    }

    /**
     * Its own lessons (E-46): after each death or stranding recorded, and once a game minute for a lesson's habit gone
     * three days unneeded. Each is written as a note with its habit, as the strategist's are, and the habits go to the
     * running goal through the same path.
     */
    private void learn(UUID id, String name, State state, PortalRunCampaign campaign, ServerPlayerEntity player, MinecraftServer server, boolean fresh) {
        long now = player.getServerWorld().getTime();
        if (!fresh && now - state.lessonsAt < 1_200) return;
        state.lessonsAt = now;
        var context = state.context;
        var kept = context.habits();
        // A lesson's habit pushed out by newer ones is from its own play no longer: learned again, it is marked again.
        context.lessons.keySet().removeIf(habit -> kept.stream().noneMatch(k -> k.name().equals(habit)));
        var deaths = new ArrayList<dev.kindred.core.survival.Lessons.Death>();
        for (var d : context.deaths) {
            if (d.how() == null) continue; // Recorded before lessons: nothing of it to read.
            var how = switch (d.how()) {
                case "mob" -> dev.kindred.core.survival.Lessons.How.MOB;
                case "fall" -> dev.kindred.core.survival.Lessons.How.FALL;
                default -> dev.kindred.core.survival.Lessons.How.OTHER;
            };
            deaths.add(new dev.kindred.core.survival.Lessons.Death(d.time(), d.y(), d.night(), d.sky(), d.overworld(), d.food(), how, d.tunnel()));
        }
        var stranded = new ArrayList<dev.kindred.core.survival.Lessons.Stranded>();
        for (var e : context.episodes) if ("stranded".equals(e.kind())) stranded.add(new dev.kindred.core.survival.Lessons.Stranded(e.time()));
        var learned = new java.util.EnumMap<PlanPreferences.Habit, Long>(PlanPreferences.Habit.class);
        var retired = new java.util.EnumMap<PlanPreferences.Habit, Long>(PlanPreferences.Habit.class);
        for (var habit : PlanPreferences.Habit.values()) {
            if (context.lessons.containsKey(habit.name())) learned.put(habit, context.lessons.get(habit.name()));
            if (context.retiredLessons.containsKey(habit.name())) retired.put(habit, context.retiredLessons.get(habit.name()));
        }
        var taught = dev.kindred.core.survival.Lessons.review(deaths, stranded, kept, learned, retired, now);
        if (taught.isEmpty()) return;
        var before = java.util.EnumSet.noneOf(PlanPreferences.Habit.class);
        before.addAll(kept);
        long tick = server.getTicks();
        for (var lesson : taught) {
            if (lesson.retire()) {
                context.retire(lesson.habit());
                context.addNote(tick, lesson.note());
                context.lessons.remove(lesson.habit().name());
                context.retiredLessons.put(lesson.habit().name(), now);
            } else {
                context.addNote(tick, lesson.note(), lesson.habit());
                if (context.habits().contains(lesson.habit())) context.lessons.put(lesson.habit().name(), now);
                context.retiredLessons.remove(lesson.habit().name());
            }
            KindredMod.LOGGER.info("{} lesson: {} {} - {}", name, lesson.retire() ? "dropped" : "learned", lesson.habit(), lesson.note());
        }
        // The goal's habits change as the memory's did, and only so: one it was handed otherwise stays.
        var after = context.habits();
        var goal = java.util.EnumSet.noneOf(PlanPreferences.Habit.class);
        goal.addAll(campaign.preferences().habits());
        goal.removeIf(habit -> before.contains(habit) && !after.contains(habit));
        for (var habit : after) if (!before.contains(habit)) goal.add(habit);
        campaign.habits(goal);
        store.save(id, context);
    }

    /**
     * A way out asked for (F-29): once for each stuck spell, a minute with no progress (F-29b), five minutes apart at
     * the least, within the same rates and budgets as advice. The reply is parsed and bounded in core, then checked by
     * {@link dev.kindred.core.ProposalGate} against the skills installed and the player's fence; a plan that passes goes
     * to the goal, which carries it out a step at a time as ordinary tasks. Before it runs, the scene is captured as a
     * replay when replays are on (F-27). Nothing waits for the reply.
     */
    private void rescue(UUID id, String name, State state, PortalRunCampaign campaign, ServerPlayerEntity player, MinecraftServer server) {
        int spell = campaign.rescueWanted(), round = campaign.rescueRound();
        if (spell == 0 || round == 0 || spell == state.rescueSpell && round <= state.rescueRound || state.rescueInFlight || campaign.rescuing()) return;
        long now = System.currentTimeMillis();
        // Five minutes between one player's conversations; a spell's later rounds follow its first at once (F-29c).
        if (round == 1 && state.rescueAt != 0 && now - state.rescueAt < RESCUE_GAP_MS) return;
        state.rescueSpell = spell; // This round is asked about now or not at all.
        state.rescueRound = round;
        String report = campaign.rescueReport(player, name);
        long reserved = Strategist.rescueReservation(report);
        if (!admit(now, state, reserved)) {
            KindredMod.LOGGER.info("{} rescue: not asked (the rates or the day's budgets)", name);
            return;
        }
        var fence = rescueHost.fence(id);
        var from = player.getBlockPos().toImmutable();
        state.rescueInFlight = true;
        state.rescueAt = now;
        campaign.rescueAsked(true); // It holds still meanwhile, so the plan finds it as it was asked about.
        KindredMod.LOGGER.info("{} rescue: asked, stuck spell {} at {} ({} characters reported)", name, spell, from.toShortString(), report.length());
        PortalRunCampaign asked = campaign;
        strategist.rescue(report).whenComplete((reply, failure) -> server.execute(() -> {
            state.rescueInFlight = false;
            asked.rescueAsked(false);
            if (failure != null || reply == null) { // The reservation stands: it may still have been billed.
                KindredMod.LOGGER.warn("{} rescue: no plan ({})", name, String.valueOf(failure));
                asked.rescueUnanswered();
                return;
            }
            long used = reply.inputTokens() - reply.cachedTokens() + (reply.cachedTokens() + 9) / 10 + reply.outputTokens();
            if (used > 0) {
                tokensToday = Math.max(0, tokensToday + used - reserved);
                state.tokensToday = Math.max(0, state.tokensToday + used - reserved);
            }
            if (state.context != null) {
                state.context.inputTokens += reply.inputTokens();
                state.context.outputTokens += reply.outputTokens();
                store.save(id, state.context);
            }
            String cost = " (" + reply.inputTokens() + " in, " + reply.cachedTokens() + " cached, " + reply.outputTokens() + " out tokens, "
                    + reply.millis() + " ms)";
            if (reply.failure().isPresent()) {
                KindredMod.LOGGER.info("{} rescue: no plan ({}){}", name, reply.failure().get(), cost);
                asked.rescueUnanswered();
                return;
            }
            var parsed = dev.kindred.core.rescue.RescuePlan.parse(reply.steps(), reply.reason());
            if (parsed.plan().isEmpty()) {
                asked.rescueRefused(reply.steps(), String.join("; ", parsed.refusals()), name);
                return;
            }
            var plan = parsed.plan().get();
            var refusals = new java.util.ArrayList<>(new dev.kindred.core.ProposalGate(rescueHost.installed())
                    .reject(plan.skills(), true, rescueHost.current(id, fence), java.time.Instant.now().isAfter(fence.deadline())));
            // Every skill a step's executor may give out is installed, not only its first (F-29c).
            for (var step : plan.steps()) {
                for (String skill : dev.kindred.core.rescue.RescuePlan.uses(step.verb())) {
                    String unknown = "unknown skill " + skill;
                    if (!rescueHost.installed().contains(skill) && !refusals.contains(unknown)) refusals.add(unknown);
                }
            }
            var where = rescueHost.where(id);
            if (state.campaign != asked || asked.rescueWanted() != spell || asked.rescuing()) refusals.add("the stuck spell is over");
            else if (where == null || where.getSquaredDistance(from) > 2 * 2) refusals.add("it is no longer where it asked from");
            if (!refusals.isEmpty()) {
                asked.rescueRefused(reply.steps(), String.join("; ", refusals), name);
                return;
            }
            KindredMod.LOGGER.info("{} rescue: plan {} - {}{}", name, plan.text(), plan.reason(), cost);
            rescueHost.capture(id);
            asked.rescue(plan, spell, name);
        }));
    }

    /** Within the rates and the day's budgets with {@code reserved} tokens set aside; reserves them if so. */
    private boolean admit(long now, State state, long reserved) {
        if (now - dayStartMs > 86_400_000L) {
            dayStartMs = now;
            tokensToday = 0;
            budgetLogged = false;
            for (State each : states.values()) each.tokensToday = 0;
        }
        if (tokensToday + reserved > dailyTokens || state.tokensToday + reserved > playerDailyTokens) {
            if (!budgetLogged) KindredMod.LOGGER.info("Kindred strategist: a daily token budget is spent; no more consultations today");
            budgetLogged = true;
            return false;
        }
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000L) recent.removeFirst();
        while (!state.recent.isEmpty() && now - state.recent.peekFirst() > 60_000L) state.recent.removeFirst();
        if (recent.size() >= GLOBAL_PER_MINUTE || state.recent.size() >= PLAYER_PER_MINUTE) return false;
        recent.addLast(now);
        state.recent.addLast(now);
        tokensToday += reserved;
        state.tokensToday += reserved;
        return true;
    }

    /** Closes the connection to the adviser and stops the memory's saving. */
    @Override
    public void close() {
        if (strategist != null) strategist.close();
        store.close();
    }
}
