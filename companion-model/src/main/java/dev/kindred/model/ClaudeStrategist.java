package dev.kindred.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.StrategyAdvice;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The optional model strategist (F-19b): one Claude request per consultation, off the game thread, whose reply is
 * constrained by structured output to a stance from a fixed set, a few places and two short texts, then validated in
 * core. It never returns actions or code, and nothing in the game waits on it. The API key is read from the
 * {@code ANTHROPIC_API_KEY} environment variable and is never stored or logged here.
 */
public final class ClaudeStrategist implements AutoCloseable {
    /** Default model: fast and cheap enough to consult on every event that matters. */
    public static final String DEFAULT_MODEL = "claude-haiku-5-5";
    /** The planning lane's bounds (AI-05): a fifteen-second deadline and a thousand output tokens. */
    public static final Duration DEADLINE = Duration.ofSeconds(15);
    public static final int MAX_OUTPUT_TOKENS = 1000;

    /** The reply as the model is constrained to write it. */
    public record Reply(
            @JsonPropertyDescription("How careful the player should be for the next few minutes: PRESS_ON carries on with the route; "
                    + "PLAY_SAFE means no caving, holing up and backing off sooner; SURFACE_AND_RECOVER means getting out to the surface and healing first.")
            StrategyAdvice.Stance stance,
            @JsonPropertyDescription("One sentence: why, from the reported events.")
            String reason,
            @JsonPropertyDescription("A short note worth remembering in this world (a lesson, a place, a habit), or an empty string.")
            String note,
            @JsonPropertyDescription("Up to three places to keep away from for a while. Only places near deaths or danger zones in the report.")
            List<Spot> avoid,
            @JsonPropertyDescription("What to do first for the next few minutes: FOOD hunts and looks for animals until well stocked; "
                    + "WOOD gathers logs until there is wood to spare; NONE leaves the order to the route.")
            PlanPreferences.Focus focus,
            @JsonPropertyDescription("A lasting habit the note teaches, kept in this world from now on, or NONE: MORE_FOOD carries more food; "
                    + "SPARE_PICKAXE packs a second spare pickaxe for the work below; NO_CAVES digs its own way rather than explore caves; "
                    + "SHELTER_EARLY digs in at dusk. Only with a note saying why.")
            PlanPreferences.Habit habit,
            @JsonPropertyDescription("A habit kept in this world (listed in the report) that is no longer worth keeping, or NONE.")
            PlanPreferences.Habit retire) { }

    public record Spot(int x, int y, int z) { }

    /** What a consultation came to: advice if the reply was usable, the tokens it cost, and why it failed if it did. */
    public record Outcome(Optional<StrategyAdvice> advice, long inputTokens, long outputTokens, Optional<String> failure) {
        static Outcome failed(String why) { return new Outcome(Optional.empty(), 0, 0, Optional.of(why)); }
    }

    static final String SYSTEM = """
            You advise an autonomous Minecraft survival player that is earning a Nether portal from an empty inventory \
            with no cheats. Its own code plays every moment: movement, mining, crafting, fighting and recovery. You do \
            not control it. From what it reports, you choose how careful it should be and what it should see to first \
            for the next few minutes, and you keep its lessons for this world.

            Choose one stance:
            - PRESS_ON: things are going well; let it carry on.
            - PLAY_SAFE: it keeps dying or getting into fights it loses; it should stay out of caves and back off sooner.
            - SURFACE_AND_RECOVER: it is hurt, hungry or cornered below ground; it should get to the surface and heal first.

            Choose a focus: FOOD when it is low on food or carries little and animals may be about; WOOD when it keeps \
            stalling for want of logs, sticks, a table or fuel; otherwise NONE. A focus only reorders its ordinary \
            jobs; fighting, shelter and healing still come first.

            Your note is the player's memory of this world, and the report lists the notes it keeps. When the report \
            shows the same problem more than once (deaths in caves, nights, running out of food or of pickaxes), \
            write a note with the lesson and set the habit that acts on it, so the player keeps it in every later \
            life here. Set a habit only for a repeated problem, never one it already keeps, and retire a kept habit \
            only when the report shows it is getting in the way.

            You may name up to three places to keep away from, but only places close to deaths or danger zones in the \
            report. Never invent positions, resources or events. Keep the note to one practical lesson and the reason \
            to one sentence. Prefer PRESS_ON and no focus unless the report shows a real problem.""";

    private final AnthropicClient client;
    private final String model;
    private final ExecutorService executor;

    ClaudeStrategist(AnthropicClient client, String model) {
        this.client = Objects.requireNonNull(client);
        this.model = Objects.requireNonNull(model);
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "kindred-strategist");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A strategist when {@code ANTHROPIC_API_KEY} is set, else nothing (the game plays on without one). */
    public static Optional<ClaudeStrategist> fromEnvironment(String model) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) return Optional.empty();
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(DEADLINE)
                .maxRetries(0) // A failed consultation is not repeated; the next event asks afresh.
                .build();
        return Optional.of(new ClaudeStrategist(client, model == null || model.isBlank() ? DEFAULT_MODEL : model));
    }

    public String model() { return model; }

    /**
     * Tokens to reserve before asking about {@code report}: a conservative count of the input (three characters
     * a token, plus the reply schema) and the most the reply may use.
     */
    public static long reservation(String report) {
        return (SYSTEM.length() + report.length()) / 3 + 400 + MAX_OUTPUT_TOKENS;
    }

    /** Ask for advice on {@code report}; places in the reply must lie near {@code reported}. */
    public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
        Objects.requireNonNull(report);
        List<StrategyAdvice.Place> known = List.copyOf(reported);
        return CompletableFuture.supplyAsync(() -> call(report, known), executor);
    }

    private Outcome call(String report, List<StrategyAdvice.Place> reported) {
        StructuredMessageCreateParams<Reply> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .system(SYSTEM)
                .outputConfig(Reply.class)
                .addUserMessage(report)
                .build();
        try {
            var message = client.messages().create(params);
            long in = message.usage().inputTokens(), out = message.usage().outputTokens();
            String stop = message.stopReason().map(Object::toString).orElse("none");
            if (!stop.equalsIgnoreCase("end_turn")) {
                return new Outcome(Optional.empty(), in, out, Optional.of("stopped: " + stop));
            }
            Optional<Reply> reply = message.content().stream().flatMap(block -> block.text().stream()).map(text -> text.text()).findFirst();
            if (reply.isEmpty()) return new Outcome(Optional.empty(), in, out, Optional.of("no reply text"));
            Reply r = reply.get();
            var places = r.avoid() == null ? List.<StrategyAdvice.Place>of()
                    : r.avoid().stream().filter(Objects::nonNull).map(s -> new StrategyAdvice.Place(s.x(), s.y(), s.z())).toList();
            var advice = StrategyAdvice.validated(r.stance() == null ? null : r.stance().name(), places, r.note(), r.reason(), reported,
                    name(r.focus()), name(r.habit()), name(r.retire()));
            return new Outcome(advice, in, out, advice.isPresent() ? Optional.empty() : Optional.of("unusable reply"));
        } catch (RateLimitException limited) {
            return Outcome.failed("rate limited");
        } catch (AnthropicServiceException service) {
            return Outcome.failed("service error " + service.statusCode());
        } catch (RuntimeException other) {
            return Outcome.failed(other.getClass().getSimpleName() + ": " + String.valueOf(other.getMessage()));
        }
    }

    private static String name(Enum<?> value) { return value == null ? null : value.name(); }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
