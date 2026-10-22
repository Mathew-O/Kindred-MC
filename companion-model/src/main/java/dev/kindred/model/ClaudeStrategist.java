package dev.kindred.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import dev.kindred.core.guide.Need;
import dev.kindred.core.guide.Objective;
import dev.kindred.core.guide.Want;
import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.StrategyAdvice;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Claude strategist (F-19b): one request per consultation through the official Anthropic SDK, its reply
 * constrained by structured output to the fields of {@link StrategistPrompt}, then validated in core. The API key is
 * read from the {@code ANTHROPIC_API_KEY} environment variable and is never stored or logged here.
 */
public final class ClaudeStrategist implements Strategist {
    /** Default model: fast and cheap enough to consult on every event that matters. */
    public static final String DEFAULT_MODEL = "claude-haiku-5-5";

    /** The reply as the model is constrained to write it. */
    public record Reply(
            @JsonPropertyDescription(StrategistPrompt.STANCE) StrategyAdvice.Stance stance,
            @JsonPropertyDescription(StrategistPrompt.REASON) String reason,
            @JsonPropertyDescription(StrategistPrompt.NOTE) String note,
            @JsonPropertyDescription(StrategistPrompt.AVOID) List<Spot> avoid,
            @JsonPropertyDescription(StrategistPrompt.FOCUS) PlanPreferences.Focus focus,
            @JsonPropertyDescription(StrategistPrompt.HABIT) PlanPreferences.Habit habit,
            @JsonPropertyDescription(StrategistPrompt.RETIRE) PlanPreferences.Habit retire,
            @JsonPropertyDescription(StrategistPrompt.QUEUE) List<FollowUp> queue,
            @JsonPropertyDescription(StrategistPrompt.APPROACH) StrategistPrompt.Way approach) { }

    public record Spot(int x, int y, int z) { }

    /** A follow-up for the player's queue (F-19e). */
    public record FollowUp(
            @JsonPropertyDescription(StrategistPrompt.QUEUE_NEED) Need need,
            @JsonPropertyDescription(StrategistPrompt.QUEUE_COUNT) int count,
            @JsonPropertyDescription(StrategistPrompt.QUEUE_FOR) Objective task,
            @JsonPropertyDescription(StrategistPrompt.QUEUE_WHY) String why) { }

    private final AnthropicClient client;
    private final String model;
    /** How hard the model thinks: null for its own default. */
    private final OutputConfig.Effort effort;
    private final boolean thinking;
    private final ExecutorService executor;

    ClaudeStrategist(AnthropicClient client, String model, OutputConfig.Effort effort, boolean thinking) {
        this.client = Objects.requireNonNull(client);
        this.model = Objects.requireNonNull(model);
        this.effort = effort;
        this.thinking = thinking;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "kindred-strategist");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * A strategist when {@code ANTHROPIC_API_KEY} is set, else nothing (the game plays on without one). By default
     * at low effort and without thinking first: measured on the same report, that answers in about two seconds every
     * time, against three to four at the model's own default, with the same advice.
     */
    public static Optional<ClaudeStrategist> fromEnvironment(String model) {
        String effort = System.getenv("KINDRED_STRATEGIST_EFFORT"), thinking = System.getenv("KINDRED_STRATEGIST_THINKING");
        return fromEnvironment(model, effort == null ? "low" : effort, thinking == null ? "off" : thinking);
    }

    /**
     * As {@link #fromEnvironment(String)}, with the effort ({@code low}, {@code medium}, {@code high}; blank for the
     * model's default) and whether the model thinks first ({@code off} turns it off) given.
     */
    public static Optional<ClaudeStrategist> fromEnvironment(String model, String effort, String thinking) {
        return withKey(System.getenv("ANTHROPIC_API_KEY"), model, effort, thinking);
    }

    /** As {@link #fromEnvironment(String, String, String)} with the key given (from whichever setting held it). */
    static Optional<ClaudeStrategist> withKey(String key, String model, String effort, String thinking) {
        if (key == null || key.isBlank()) return Optional.empty();
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(DEADLINE)
                .maxRetries(0) // A failed consultation is not repeated; the next event asks afresh.
                .build();
        OutputConfig.Effort level = effort == null || effort.isBlank() ? null : OutputConfig.Effort.of(effort.trim().toLowerCase(java.util.Locale.ROOT));
        return Optional.of(new ClaudeStrategist(client, model == null || model.isBlank() ? DEFAULT_MODEL : model, level,
                !"off".equalsIgnoreCase(thinking == null ? "" : thinking.trim())));
    }

    @Override public String provider() { return "Anthropic"; }

    @Override public String model() { return model; }

    /** The effort and thinking settings, for the log. */
    @Override public String pace() { return "effort " + (effort == null ? "default" : effort.toString()) + ", thinking " + (thinking ? "on" : "off"); }

    @Override
    public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
        Objects.requireNonNull(report);
        List<StrategyAdvice.Place> known = List.copyOf(reported);
        return CompletableFuture.supplyAsync(() -> call(report, known), executor);
    }

    private Outcome call(String report, List<StrategyAdvice.Place> reported) {
        var builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_OUTPUT_TOKENS)
                // The instructions are the same every time: cached, they are read back faster and for a tenth of the price.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(StrategistPrompt.SYSTEM).cacheControl(CacheControlEphemeral.builder().build()).build()))
                .outputConfig(StructuredOutputConfig.<Reply>builder().format(Reply.class).effort(Optional.ofNullable(effort)).build())
                .addUserMessage(report);
        if (!thinking) builder.thinking(ThinkingConfigDisabled.builder().build());
        StructuredMessageCreateParams<Reply> params = builder.build();
        long started = System.nanoTime();
        try {
            var message = client.messages().create(params);
            long millis = (System.nanoTime() - started) / 1_000_000;
            var usage = message.usage();
            long cached = usage.cacheReadInputTokens().orElse(0L);
            long in = usage.inputTokens() + cached + usage.cacheCreationInputTokens().orElse(0L), out = usage.outputTokens();
            String stop = message.stopReason().map(Object::toString).orElse("none");
            if (!stop.equalsIgnoreCase("end_turn")) {
                return new Outcome(Optional.empty(), in, cached, out, millis, Optional.of("stopped: " + stop));
            }
            Optional<Reply> reply = message.content().stream().flatMap(block -> block.text().stream()).map(text -> text.text()).findFirst();
            if (reply.isEmpty()) return new Outcome(Optional.empty(), in, cached, out, millis, Optional.of("no reply text"));
            Reply r = reply.get();
            var places = r.avoid() == null ? List.<StrategyAdvice.Place>of()
                    : r.avoid().stream().filter(Objects::nonNull).map(s -> new StrategyAdvice.Place(s.x(), s.y(), s.z())).toList();
            var queue = r.queue() == null ? List.<Want>of() : r.queue().stream().filter(Objects::nonNull)
                    .flatMap(f -> Want.parse(name(f.need()), f.count(), name(f.task()), f.why()).stream()).toList();
            var advice = StrategyAdvice.validated(r.stance() == null ? null : r.stance().name(), places, r.note(), r.reason(), reported,
                    name(r.focus()), name(r.habit()), name(r.retire()), queue, name(r.approach()));
            return new Outcome(advice, in, cached, out, millis, advice.isPresent() ? Optional.empty() : Optional.of("unusable reply"));
        } catch (RateLimitException limited) {
            return Outcome.failed("rate limited", (System.nanoTime() - started) / 1_000_000);
        } catch (AnthropicServiceException service) {
            return Outcome.failed("service error " + service.statusCode(), (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException other) {
            return Outcome.failed(other.getClass().getSimpleName() + ": " + String.valueOf(other.getMessage()), (System.nanoTime() - started) / 1_000_000);
        }
    }

    private static String name(Enum<?> value) { return value == null ? null : value.name(); }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
