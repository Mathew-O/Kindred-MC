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
 * Consults the optional model strategist for AI players with a standing goal (F-19b). Never per tick: only when
 * something changed that a player would stop and think about (the goal starting, a new stage, a death, and at most
 * every two minutes a spell with no progress) and otherwise at most every five minutes. Within the planning lane's
 * bounds (AI-05): one request in flight per player and one at a time in all, at most six a minute per player and
 * twelve in all, and daily token budgets per player and in all (50,000 and 100,000 unless the operator sets others),
 * reserved before each request for its largest possible size and settled to what it used; a failure with no usage
 * keeps its reservation. Requests go out off the game thread; the advice comes back to it and is applied only if the
 * same goal is still running. Each player's memory in this world (notes, deaths, advice, cost) lives in its context
 * file. Off unless {@code KINDRED_STRATEGIST=on} and {@code ANTHROPIC_API_KEY} are set; the game plays the same
 * without it.
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
        boolean loading, inFlight;
        long lastAt, tokensToday;
        int deaths = -1, stuck = -1, deathsRecorded, events = -1;
        dev.kindred.core.survival.PortalRunPlanner.Stage stage;
        String progress = "";
        PortalRunCampaign campaign, taught;
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

    /** Called on the game thread after a goal's step was chosen; cheap when nothing changed. */
    void tick(UUID id, String name, PortalRunCampaign campaign, ServerPlayerEntity player, MinecraftServer server) {
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
            state.taught = campaign;
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
        String report = campaign.strategyReport(player, name, state.context.noteTexts());
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
            long used = outcome.inputTokens() + outcome.outputTokens();
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
                KindredMod.LOGGER.info("{} strategist: {}{} ({} in, {} cached, {} out tokens, {} ms) - {}{}", name, advice.stance(),
                        advice.focus() == PlanPreferences.Focus.NONE ? "" : ", focus " + advice.focus(), outcome.inputTokens(),
                        outcome.cachedTokens(), outcome.outputTokens(), outcome.millis(), advice.reason(),
                        advice.avoid().isEmpty() ? "" : "; avoid " + advice.avoid());
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

    @Override
    public void close() {
        strategist.close();
        store.close();
    }
}
