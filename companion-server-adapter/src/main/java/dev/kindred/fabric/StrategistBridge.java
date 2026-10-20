package dev.kindred.fabric;

import dev.kindred.core.survival.StrategyAdvice;
import dev.kindred.model.ClaudeStrategist;
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
 * something changed that a player would stop and think about (the goal starting, a new stage, a death, a spell with
 * no progress) and otherwise at most every five minutes. Within the planning lane's bounds (AI-05): one request in
 * flight per player and one at a time in all, at most six a minute per player and twelve in all, and daily token
 * budgets per player and in all (50,000 and 100,000 unless the operator sets others), reserved before each request
 * for its largest possible size and settled to what it used; a failure with no usage keeps its reservation. Requests
 * go out off the game thread; the advice comes back to it and is applied only if the same goal is still running.
 * Each player's memory in this world (notes, deaths, advice, cost) lives in its context file. Off unless
 * {@code KINDRED_STRATEGIST=on} and {@code ANTHROPIC_API_KEY} are set; the game plays the same without it.
 */
final class StrategistBridge implements AutoCloseable {
    static final long MIN_GAP_MS = 20_000, PERIOD_MS = 300_000;
    static final int PLAYER_PER_MINUTE = 6, GLOBAL_PER_MINUTE = 12;
    static final long DEFAULT_PLAYER_DAILY_TOKENS = 50_000, DEFAULT_DAILY_TOKENS = 100_000;

    private static final class State {
        ModelContextStore.PlayerContext context;
        boolean loading, inFlight;
        long lastAt, tokensToday;
        int deaths = -1, stuck = -1, deathsRecorded;
        dev.kindred.core.survival.PortalRunPlanner.Stage stage;
        String progress = "";
        PortalRunCampaign campaign;
        final ArrayDeque<Long> recent = new ArrayDeque<>();
    }

    private final ClaudeStrategist strategist;
    private final ModelContextStore store;
    private final long playerDailyTokens, dailyTokens;
    private final Map<UUID, State> states = new HashMap<>();
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private long dayStartMs = System.currentTimeMillis(), tokensToday;
    private boolean budgetLogged;

    private StrategistBridge(ClaudeStrategist strategist, ModelContextStore store, long playerDailyTokens, long dailyTokens) {
        this.strategist = strategist;
        this.store = store;
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

    /** A bridge when the strategist is switched on and a key is available, else nothing. */
    static Optional<StrategistBridge> fromEnvironment(Path worldKindredFolder) {
        if (!"on".equalsIgnoreCase(System.getenv("KINDRED_STRATEGIST"))) return Optional.empty();
        Optional<ClaudeStrategist> strategist;
        try {
            strategist = ClaudeStrategist.fromEnvironment(System.getenv("KINDRED_STRATEGIST_MODEL"));
        } catch (LinkageError missing) { // The release jar does not carry the SDK yet; development runs do.
            KindredMod.LOGGER.warn("KINDRED_STRATEGIST is on but the Anthropic SDK is not installed; playing without a strategist");
            return Optional.empty();
        }
        if (strategist.isEmpty()) {
            KindredMod.LOGGER.warn("KINDRED_STRATEGIST is on but ANTHROPIC_API_KEY is not set; playing without a strategist");
            return Optional.empty();
        }
        long playerDaily = tokensSetting("KINDRED_STRATEGIST_PLAYER_DAILY_TOKENS", DEFAULT_PLAYER_DAILY_TOKENS);
        long daily = tokensSetting("KINDRED_STRATEGIST_DAILY_TOKENS", DEFAULT_DAILY_TOKENS);
        KindredMod.LOGGER.info("Kindred strategist on: model {}, daily token budgets {} a player and {} in all",
                strategist.get().model(), playerDaily, daily);
        return Optional.of(new StrategistBridge(strategist.get(), new ModelContextStore(worldKindredFolder), playerDaily, daily));
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
        boolean changed = state.lastAt == 0 || campaign.deaths() != state.deaths || campaign.stuckSpells() != state.stuck
                || campaign.stage() != state.stage || now - state.lastAt > PERIOD_MS && !campaign.progress().equals(state.progress);
        if (!changed || now - state.lastAt < MIN_GAP_MS) return;
        String report = campaign.strategyReport(player, name, state.context.noteTexts());
        long reserved = ClaudeStrategist.reservation(report);
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
                asked.advise(advice, server.getTicks());
                state.context.addAdvice(server.getTicks(), advice.stance().name(), advice.reason());
                state.context.addNote(server.getTicks(), advice.note());
                KindredMod.LOGGER.info("{} strategist: {} ({} in, {} out tokens) - {}{}", name, advice.stance(), outcome.inputTokens(),
                        outcome.outputTokens(), advice.reason(), advice.avoid().isEmpty() ? "" : "; avoid " + advice.avoid());
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
