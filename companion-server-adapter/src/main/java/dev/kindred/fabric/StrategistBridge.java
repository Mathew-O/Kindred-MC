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
        boolean loading, inFlight, asking, talking;
        long lastAt, tokensToday;
        int deaths = -1, stuck = -1, deathsRecorded, events = -1;
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
     * Ask one question for a player (E-44, E-16d), on the game thread: within the same rates and daily budgets as its
     * advice, one question at a time for each player, off the game thread, the answer handed back on it. Nothing, at
     * once, when the budgets or rates leave no room or a question of its is already out: the player then answers by
     * its own rule.
     */
    java.util.concurrent.CompletableFuture<Optional<Strategist.Answer>> ask(UUID id, String name, Strategist.Question question, MinecraftServer server) {
        State state = states.computeIfAbsent(id, key -> new State());
        var answered = new java.util.concurrent.CompletableFuture<Optional<Strategist.Answer>>();
        long reserved = Strategist.reservation(question);
        if (state.asking || !admit(System.currentTimeMillis(), state, reserved)) {
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
        if (state.talking || !admit(System.currentTimeMillis(), state, reserved)) {
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
        // The habits this player keeps in this world go to each goal it starts, before anything is asked.
        if (state.taught != campaign) {
            campaign.habits(state.context.habits());
            campaign.queue(state.context.taskQueue());
            state.queued = campaign.queue();
            state.taught = campaign;
        }
        // The queue as the goal settled it (follow-ups met, or their task behind the player) goes back into memory.
        if (!campaign.queue().equals(state.queued)) {
            state.queued = campaign.queue();
            state.context.queue(state.queued);
            store.save(id, state.context);
        }
        // Deaths go into this world's memory as they happen, whether or not a consultation follows.
        if (campaign.deaths() > state.deathsRecorded) {
            state.deathsRecorded = campaign.deaths();
            campaign.lastDeath().ifPresent(death -> {
                state.context.addDeath(server.getTicks(), death.pos().getX(), death.pos().getY(), death.pos().getZ(), death.cause(), death.stage().name());
                store.save(id, state.context);
            });
        }
        if (state.inFlight) return;
        long now = System.currentTimeMillis();
        // A spell with no progress repeats every few seconds while the player is stuck: one question about it now and then.
        boolean changed = state.lastAt == 0 || campaign.deaths() != state.deaths || campaign.stage() != state.stage
                || campaign.stuckSpells() != state.stuck && now - state.lastAt > pace.stuckGapMs
                || pace.onEvents && campaign.eventCount() != state.events
                || now - state.lastAt > pace.periodMs && !campaign.progress().equals(state.progress);
        if (!changed || now - state.lastAt < pace.minGapMs) return;
        String report = campaign.strategyReport(player, name, state.context.noteTexts())
                + (stored.isEmpty() ? "" : "Stored in its chests, as it last counted: " + stored + ".\n");
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
        strategist.close();
        store.close();
    }
}
