package dev.kindred.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.StrategyAdvice;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In plain terms: asks for advice from OpenAI or any service that speaks the same language (Gemini, Groq,
 * Mistral, DeepSeek, xAI, OpenRouter, or a model running on your own computer with Ollama or LM Studio).
 * Optional, checked before use, and the key is never saved or logged.
 *
 * Technical: The strategist served over OpenAI's chat completions API (F-19d): OpenAI itself, and the
 * providers and local servers that speak the same API (Gemini, Groq, Mistral, DeepSeek, xAI, OpenRouter,
 * Ollama, LM Studio). The reply's shape is asked for with a strict JSON schema where the provider enforces
 * one, else with JSON mode and the fields described in the instructions; either way core validates it exactly
 * as it validates Claude's. Uses only the JDK's HTTP client and Gson, which the server already carries, so it
 * needs nothing bundled. A request the provider refuses for its schema is asked once more in JSON mode within
 * the same deadline (AI-05's one repair); nothing else retries.
 */
public final class OpenAiCompatibleStrategist implements Strategist {
    private final StrategistProvider.Choice choice;
    private final HttpClient http;
    private final ExecutorService executor;
    /** The chat lane's own thread, so a reply is never queued behind a planning request (AI-05's separate lanes). */
    private final ExecutorService voice;
    private final URI endpoint;

    /** A strategist for the chosen service. */
    public OpenAiCompatibleStrategist(StrategistProvider.Choice choice) {
        this(choice, HttpClient.newBuilder().connectTimeout(DEADLINE).proxy(ProxySelector.getDefault()).build());
    }

    OpenAiCompatibleStrategist(StrategistProvider.Choice choice, HttpClient http) {
        this.choice = Objects.requireNonNull(choice);
        this.http = Objects.requireNonNull(http);
        this.endpoint = URI.create(choice.baseUrl() + "/chat/completions");
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "kindred-strategist");
            thread.setDaemon(true);
            return thread;
        });
        this.voice = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "kindred-voice");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override public String provider() { return choice.provider().label(); }
    @Override public String model() { return choice.model(); }
    @Override public String pace() { return choice.provider().shape() == StrategistProvider.Shape.JSON_SCHEMA ? "JSON schema" : "JSON mode"; }

    /** Asks for advice in the background; the game never waits for the answer. */
    @Override
    public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
        Objects.requireNonNull(report);
        List<StrategyAdvice.Place> known = List.copyOf(reported);
        return CompletableFuture.supplyAsync(() -> call(report, known), executor);
    }

    private Outcome call(String report, List<StrategyAdvice.Place> reported) {
        long started = System.nanoTime();
        try {
            boolean schema = choice.provider().shape() == StrategistProvider.Shape.JSON_SCHEMA;
            HttpResponse<String> response = send(request(report, schema), started);
            if (schema && response.statusCode() == 400 && mentionsShape(response.body())) {
                response = send(request(report, false), started); // The one repair: the same question in JSON mode.
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (response.statusCode() == 429) return Outcome.failed("rate limited", millis);
            if (response.statusCode() / 100 != 2) return Outcome.failed("service error " + response.statusCode(), millis);
            return outcome(JsonParser.parseString(response.body()).getAsJsonObject(), reported, millis);
        } catch (java.net.http.HttpTimeoutException late) {
            return Outcome.failed("deadline passed", (System.nanoTime() - started) / 1_000_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.failed("interrupted", (System.nanoTime() - started) / 1_000_000);
        } catch (Exception other) {
            return Outcome.failed(other.getClass().getSimpleName() + ": " + String.valueOf(other.getMessage()), (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** Asks one question in the background, such as which of a few choices to make. */
    @Override
    public CompletableFuture<Answer> ask(Question question) {
        Objects.requireNonNull(question);
        return CompletableFuture.supplyAsync(() -> askCall(question), executor);
    }

    private Answer askCall(Question question) {
        long started = System.nanoTime();
        try {
            boolean schema = choice.provider().shape() == StrategistProvider.Shape.JSON_SCHEMA;
            HttpResponse<String> response = send(askRequest(question, schema), started);
            if (schema && response.statusCode() == 400 && mentionsShape(response.body())) {
                response = send(askRequest(question, false), started); // The one repair, as for advice.
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (response.statusCode() == 429) return Answer.failed("rate limited", millis);
            if (response.statusCode() / 100 != 2) return Answer.failed("service error " + response.statusCode(), millis);
            return answer(question, JsonParser.parseString(response.body()).getAsJsonObject(), millis);
        } catch (java.net.http.HttpTimeoutException late) {
            return Answer.failed("deadline passed", (System.nanoTime() - started) / 1_000_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Answer.failed("interrupted", (System.nanoTime() - started) / 1_000_000);
        } catch (Exception other) {
            return Answer.failed(other.getClass().getSimpleName() + ": " + String.valueOf(other.getMessage()), (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** A question's request: its own instructions, the question, and the reply's three fields. */
    String askRequest(Question question, boolean schema) {
        var body = new JsonObject();
        body.addProperty("model", choice.model());
        var messages = new JsonArray();
        String words = "Reply with one JSON object and nothing else, with exactly these fields: \"choice\" ("
                + (question.choices().isEmpty() ? "an empty string" : "one of " + String.join(", ", question.choices())) + "), \"text\" ("
                + (question.maxText() > 0 ? "the name asked for, at most " + question.maxText() + " characters" : "an empty string")
                + ") and \"reason\" (one sentence: why).";
        messages.add(message("system", schema ? question.system() : question.system() + "\n\n" + words));
        messages.add(message("user", question.text()));
        body.add("messages", messages);
        body.addProperty(choice.provider() == StrategistProvider.Provider.OPENAI ? "max_completion_tokens" : "max_tokens", ASK_MAX_OUTPUT_TOKENS);
        var format = new JsonObject();
        if (schema) {
            var properties = new JsonObject();
            var picked = stringField("Exactly one of the choices the question lists, as written; an empty string when it lists none.");
            if (!question.choices().isEmpty()) {
                var names = new JsonArray();
                for (String offered : question.choices()) names.add(offered);
                picked.add("enum", names);
            }
            properties.add("choice", picked);
            properties.add("text", stringField("The name the question asks for; an empty string when it asks for none."));
            properties.add("reason", stringField("One sentence: why."));
            var all = new JsonArray();
            for (String name : properties.keySet()) all.add(name);
            var shape = new JsonObject();
            shape.addProperty("type", "object");
            shape.addProperty("additionalProperties", false);
            shape.add("properties", properties);
            shape.add("required", all);
            format.addProperty("type", "json_schema");
            var spec = new JsonObject();
            spec.addProperty("name", "answer");
            spec.addProperty("strict", true);
            spec.add("schema", shape);
            format.add("json_schema", spec);
        } else {
            format.addProperty("type", "json_object");
        }
        body.add("response_format", format);
        return body.toString();
    }

    /** A question's completion, checked against what was asked. */
    static Answer answer(Question question, JsonObject completion, long millis) {
        long in = 0, cached = 0, out = 0;
        if (completion.has("usage") && completion.get("usage").isJsonObject()) {
            var usage = completion.getAsJsonObject("usage");
            in = number(usage, "prompt_tokens");
            out = number(usage, "completion_tokens");
            if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
                cached = number(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
            }
        }
        var choices = completion.has("choices") && completion.get("choices").isJsonArray() ? completion.getAsJsonArray("choices") : new JsonArray();
        if (choices.isEmpty()) return new Answer(Optional.empty(), Optional.empty(), "", in, cached, out, millis, Optional.of("no reply"));
        var first = choices.get(0).getAsJsonObject();
        String finish = text(first, "finish_reason");
        if (finish != null && !finish.equals("stop")) {
            return new Answer(Optional.empty(), Optional.empty(), "", in, cached, out, millis, Optional.of("stopped: " + finish));
        }
        var message = first.has("message") && first.get("message").isJsonObject() ? first.getAsJsonObject("message") : new JsonObject();
        String content = text(message, "content");
        if (content == null || content.isBlank()) return new Answer(Optional.empty(), Optional.empty(), "", in, cached, out, millis, Optional.of("no reply text"));
        JsonObject reply;
        try {
            reply = JsonParser.parseString(stripFence(content)).getAsJsonObject();
        } catch (RuntimeException unreadable) {
            return new Answer(Optional.empty(), Optional.empty(), "", in, cached, out, millis, Optional.of("unreadable reply"));
        }
        return Answer.checked(question, text(reply, "choice"), text(reply, "text"), text(reply, "reason"), in, cached, out, millis);
    }

    /**
     * Answers someone who spoke to the player, in the background on the chat lane: within its eight seconds from the
     * moment it was asked (time spent waiting for the lane counts), with one repair in JSON mode when the provider
     * refuses the schema.
     */
    @Override
    public CompletableFuture<Line> converse(Conversation conversation) {
        Objects.requireNonNull(conversation);
        long started = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> converseCall(conversation, started), voice);
    }

    private Line converseCall(Conversation conversation, long started) {
        try {
            boolean schema = choice.provider().shape() == StrategistProvider.Shape.JSON_SCHEMA;
            HttpResponse<String> response = send(converseRequest(conversation, schema), started, CHAT_DEADLINE);
            if (schema && response.statusCode() == 400 && mentionsShape(response.body())) {
                response = send(converseRequest(conversation, false), started, CHAT_DEADLINE); // The one repair, within the same deadline.
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (response.statusCode() == 429) return Line.failed("rate limited", millis);
            if (response.statusCode() / 100 != 2) return Line.failed("service error " + response.statusCode(), millis);
            return line(conversation, JsonParser.parseString(response.body()).getAsJsonObject(), millis);
        } catch (java.net.http.HttpTimeoutException late) {
            return Line.failed("deadline passed", (System.nanoTime() - started) / 1_000_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Line.failed("interrupted", (System.nanoTime() - started) / 1_000_000);
        } catch (Exception other) {
            return Line.failed(other.getClass().getSimpleName() + ": " + String.valueOf(other.getMessage()), (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** A conversation's request: the player's instructions and personality, what it knows and what was said, and the reply's two fields. */
    String converseRequest(Conversation conversation, boolean schema) {
        var body = new JsonObject();
        body.addProperty("model", choice.model());
        var messages = new JsonArray();
        messages.add(message("system", schema ? conversation.system() : conversation.system() + "\n\n" + ConversationPrompt.fieldsInWords()));
        messages.add(message("user", conversation.text()));
        body.add("messages", messages);
        body.addProperty(choice.provider() == StrategistProvider.Provider.OPENAI ? "max_completion_tokens" : "max_tokens", CHAT_MAX_OUTPUT_TOKENS);
        var format = new JsonObject();
        if (schema) {
            var properties = new JsonObject();
            properties.add("say", stringField(ConversationPrompt.SAY));
            properties.add("reason", stringField(ConversationPrompt.REASON));
            var all = new JsonArray();
            for (String name : properties.keySet()) all.add(name);
            var shape = new JsonObject();
            shape.addProperty("type", "object");
            shape.addProperty("additionalProperties", false);
            shape.add("properties", properties);
            shape.add("required", all);
            format.addProperty("type", "json_schema");
            var spec = new JsonObject();
            spec.addProperty("name", "reply");
            spec.addProperty("strict", true);
            spec.add("schema", shape);
            format.add("json_schema", spec);
        } else {
            format.addProperty("type", "json_object");
        }
        body.add("response_format", format);
        return body.toString();
    }

    /** A conversation's completion, checked before anything of it is said. */
    static Line line(Conversation conversation, JsonObject completion, long millis) {
        long in = 0, cached = 0, out = 0;
        if (completion.has("usage") && completion.get("usage").isJsonObject()) {
            var usage = completion.getAsJsonObject("usage");
            in = number(usage, "prompt_tokens");
            out = number(usage, "completion_tokens");
            if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
                cached = number(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
            }
        }
        var choices = completion.has("choices") && completion.get("choices").isJsonArray() ? completion.getAsJsonArray("choices") : new JsonArray();
        if (choices.isEmpty()) return Line.failed("no reply", in, cached, out, millis);
        var first = choices.get(0).getAsJsonObject();
        String finish = text(first, "finish_reason");
        if (finish != null && !finish.equals("stop")) return Line.failed("stopped: " + finish, in, cached, out, millis);
        var message = first.has("message") && first.get("message").isJsonObject() ? first.getAsJsonObject("message") : new JsonObject();
        String content = text(message, "content");
        if (content == null || content.isBlank()) return Line.failed("no reply text", in, cached, out, millis);
        JsonObject reply;
        try {
            reply = JsonParser.parseString(stripFence(content)).getAsJsonObject();
        } catch (RuntimeException unreadable) {
            return Line.failed("unreadable reply", in, cached, out, millis);
        }
        return Line.checked(conversation, text(reply, "say"), text(reply, "reason"), in, cached, out, millis);
    }

    /** Sends one request, giving up at the planning lane's deadline. */
    private HttpResponse<String> send(String body, long started) throws Exception {
        return send(body, started, DEADLINE);
    }

    /** Sends one request, giving up at {@code deadline} after {@code started}. */
    private HttpResponse<String> send(String body, long started, java.time.Duration deadline) throws Exception {
        long left = deadline.toMillis() - (System.nanoTime() - started) / 1_000_000;
        if (left <= 0) throw new java.net.http.HttpTimeoutException("deadline passed");
        var builder = HttpRequest.newBuilder(endpoint)
                .timeout(java.time.Duration.ofMillis(left))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (choice.apiKey() != null) builder.header("Authorization", "Bearer " + choice.apiKey());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static boolean mentionsShape(String body) {
        String b = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        return b.contains("response_format") || b.contains("json_schema") || b.contains("schema");
    }

    /** The request body: instructions, the report, and the reply's shape. */
    String request(String report, boolean schema) {
        var body = new JsonObject();
        body.addProperty("model", choice.model());
        var messages = new JsonArray();
        messages.add(message("system", schema ? StrategistPrompt.SYSTEM : StrategistPrompt.SYSTEM + "\n\n" + fieldsInWords()));
        messages.add(message("user", report));
        body.add("messages", messages);
        // OpenAI's own API names the cap differently from the others.
        body.addProperty(choice.provider() == StrategistProvider.Provider.OPENAI ? "max_completion_tokens" : "max_tokens", MAX_OUTPUT_TOKENS);
        var format = new JsonObject();
        if (schema) {
            format.addProperty("type", "json_schema");
            var spec = new JsonObject();
            spec.addProperty("name", "strategy_advice");
            spec.addProperty("strict", true);
            spec.add("schema", schema());
            format.add("json_schema", spec);
        } else {
            format.addProperty("type", "json_object");
        }
        body.add("response_format", format);
        return body.toString();
    }

    private static JsonObject message(String role, String content) {
        var m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** The reply's fields as a strict JSON schema: every field required, nothing else allowed. */
    static JsonObject schema() {
        var properties = new JsonObject();
        properties.add("stance", enumField(StrategistPrompt.STANCE, StrategyAdvice.Stance.values()));
        properties.add("reason", stringField(StrategistPrompt.REASON));
        properties.add("note", stringField(StrategistPrompt.NOTE));
        var spot = new JsonObject();
        spot.addProperty("type", "object");
        spot.addProperty("additionalProperties", false);
        var coords = new JsonObject();
        var required = new JsonArray();
        for (String axis : List.of("x", "y", "z")) {
            var number = new JsonObject();
            number.addProperty("type", "integer");
            coords.add(axis, number);
            required.add(axis);
        }
        spot.add("properties", coords);
        spot.add("required", required);
        var avoid = new JsonObject();
        avoid.addProperty("type", "array");
        avoid.addProperty("description", StrategistPrompt.AVOID);
        avoid.add("items", spot);
        properties.add("avoid", avoid);
        properties.add("focus", enumField(StrategistPrompt.FOCUS, PlanPreferences.Focus.values()));
        properties.add("habit", enumField(StrategistPrompt.HABIT, PlanPreferences.Habit.values()));
        properties.add("retire", enumField(StrategistPrompt.RETIRE, PlanPreferences.Habit.values()));
        var follow = new JsonObject();
        follow.addProperty("type", "object");
        follow.addProperty("additionalProperties", false);
        var fields = new JsonObject();
        fields.add("need", enumField(StrategistPrompt.QUEUE_NEED, dev.kindred.core.guide.Need.values()));
        var count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", StrategistPrompt.QUEUE_COUNT);
        fields.add("count", count);
        fields.add("task", enumField(StrategistPrompt.QUEUE_FOR, dev.kindred.core.guide.Objective.values()));
        fields.add("why", stringField(StrategistPrompt.QUEUE_WHY));
        var needed = new JsonArray();
        for (String name : fields.keySet()) needed.add(name);
        follow.add("properties", fields);
        follow.add("required", needed);
        var queue = new JsonObject();
        queue.addProperty("type", "array");
        queue.addProperty("description", StrategistPrompt.QUEUE);
        queue.add("items", follow);
        properties.add("queue", queue);
        properties.add("approach", enumField(StrategistPrompt.APPROACH, StrategistPrompt.Way.values()));
        var all = new JsonArray();
        for (String name : properties.keySet()) all.add(name);
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", properties);
        schema.add("required", all);
        return schema;
    }

    private static JsonObject stringField(String description) {
        var field = new JsonObject();
        field.addProperty("type", "string");
        field.addProperty("description", description);
        return field;
    }

    private static JsonObject enumField(String description, Enum<?>[] values) {
        var field = stringField(description);
        var names = new JsonArray();
        for (Enum<?> value : values) names.add(value.name());
        field.add("enum", names);
        return field;
    }

    /** For JSON mode, where no schema is enforced: the same fields, described. */
    private static String fieldsInWords() {
        return "Reply with one JSON object and nothing else, with exactly these fields: "
                + "\"stance\" (PRESS_ON, PLAY_SAFE or SURFACE_AND_RECOVER; " + StrategistPrompt.STANCE + "), "
                + "\"reason\" (" + StrategistPrompt.REASON + "), \"note\" (" + StrategistPrompt.NOTE + "), "
                + "\"avoid\" (a list of {\"x\":..,\"y\":..,\"z\":..}; " + StrategistPrompt.AVOID + "), "
                + "\"focus\" (NONE, FOOD or WOOD; " + StrategistPrompt.FOCUS + "), "
                + "\"habit\" (NONE, MORE_FOOD, SPARE_PICKAXE, NO_CAVES or SHELTER_EARLY; " + StrategistPrompt.HABIT + "), "
                + "\"retire\" (as habit; " + StrategistPrompt.RETIRE + "), "
                + "\"queue\" (a list of {\"need\":..,\"count\":..,\"task\":..,\"why\":..}; " + StrategistPrompt.QUEUE + " need: "
                + StrategistPrompt.QUEUE_NEED + " count: " + StrategistPrompt.QUEUE_COUNT + " task: " + StrategistPrompt.QUEUE_FOR + "), "
                + "\"approach\" (" + StrategistPrompt.APPROACH + ").";
    }

    /** The completion's reply, validated in core, and its token counts. */
    static Outcome outcome(JsonObject completion, List<StrategyAdvice.Place> reported, long millis) {
        long in = 0, cached = 0, out = 0;
        if (completion.has("usage") && completion.get("usage").isJsonObject()) {
            var usage = completion.getAsJsonObject("usage");
            in = number(usage, "prompt_tokens");
            out = number(usage, "completion_tokens");
            if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
                cached = number(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
            }
        }
        var choices = completion.has("choices") && completion.get("choices").isJsonArray() ? completion.getAsJsonArray("choices") : new JsonArray();
        if (choices.isEmpty()) return new Outcome(Optional.empty(), in, cached, out, millis, Optional.of("no reply"));
        var first = choices.get(0).getAsJsonObject();
        String finish = text(first, "finish_reason");
        if (finish != null && !finish.equals("stop")) return new Outcome(Optional.empty(), in, cached, out, millis, Optional.of("stopped: " + finish));
        var message = first.has("message") && first.get("message").isJsonObject() ? first.getAsJsonObject("message") : new JsonObject();
        String content = text(message, "content");
        if (content == null || content.isBlank()) return new Outcome(Optional.empty(), in, cached, out, millis, Optional.of("no reply text"));
        JsonObject reply;
        try {
            reply = JsonParser.parseString(stripFence(content)).getAsJsonObject();
        } catch (RuntimeException unreadable) {
            return new Outcome(Optional.empty(), in, cached, out, millis, Optional.of("unreadable reply"));
        }
        var places = new ArrayList<StrategyAdvice.Place>();
        if (reply.has("avoid") && reply.get("avoid").isJsonArray()) {
            for (JsonElement spot : reply.getAsJsonArray("avoid")) {
                if (!spot.isJsonObject()) continue;
                var o = spot.getAsJsonObject();
                if (o.has("x") && o.has("y") && o.has("z")) {
                    try {
                        places.add(new StrategyAdvice.Place(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
                    } catch (RuntimeException notANumber) { /* Dropped, as an unknown place would be. */ }
                }
            }
        }
        var queue = new ArrayList<dev.kindred.core.guide.Want>();
        if (reply.has("queue") && reply.get("queue").isJsonArray()) {
            for (JsonElement entry : reply.getAsJsonArray("queue")) {
                if (!entry.isJsonObject()) continue;
                var o = entry.getAsJsonObject();
                long count = number(o, "count");
                dev.kindred.core.guide.Want.parse(text(o, "need"), count, text(o, "task"), text(o, "why")).ifPresent(queue::add);
            }
        }
        var advice = StrategyAdvice.validated(text(reply, "stance"), places, text(reply, "note"), text(reply, "reason"), reported,
                text(reply, "focus"), text(reply, "habit"), text(reply, "retire"), queue, text(reply, "approach"));
        return new Outcome(advice, in, cached, out, millis, advice.isPresent() ? Optional.empty() : Optional.of("unusable reply"));
    }

    /** Some models wrap JSON mode's reply in a code fence. */
    private static String stripFence(String content) {
        String c = content.trim();
        if (c.startsWith("```")) {
            int start = c.indexOf('\n');
            int end = c.lastIndexOf("```");
            if (start > 0 && end > start) c = c.substring(start + 1, end).trim();
        }
        return c;
    }

    private static long number(JsonObject o, String name) {
        try {
            return o.has(name) && o.get(name).isJsonPrimitive() ? o.get(name).getAsLong() : 0;
        } catch (RuntimeException notANumber) {
            return 0;
        }
    }

    private static String text(JsonObject o, String name) {
        return o.has(name) && o.get(name).isJsonPrimitive() ? o.get(name).getAsString() : null;
    }

    /** Nothing to shut: each request stands alone. */
    @Override
    public void close() {
        executor.shutdownNow();
        voice.shutdownNow();
    }
}
