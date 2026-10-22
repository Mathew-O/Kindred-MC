package dev.kindred.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.StrategyAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The OpenAI-compatible strategist against a local fake server: no network, no keys, no cost. */
class OpenAiCompatibleStrategistTest {
    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private final Queue<int[]> statuses = new ArrayDeque<>();
    private final Queue<String> replies = new ArrayDeque<>();

    private OpenAiCompatibleStrategist start(StrategistProvider.Provider provider, String key) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = statuses.isEmpty() ? 200 : statuses.remove()[0];
            byte[] out = (replies.isEmpty() ? "{}" : replies.remove()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        var choice = new StrategistProvider.Choice(provider, key, "test-model", base);
        return new OpenAiCompatibleStrategist(choice, HttpClient.newBuilder().build());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static String completion(String content, String finish) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        var choice = new JsonObject();
        choice.add("message", message);
        choice.addProperty("finish_reason", finish);
        var choices = new com.google.gson.JsonArray();
        choices.add(choice);
        var usage = JsonParser.parseString("{\"prompt_tokens\":1900,\"completion_tokens\":90,\"prompt_tokens_details\":{\"cached_tokens\":1536}}");
        var body = new JsonObject();
        body.add("choices", choices);
        body.add("usage", usage);
        return body.toString();
    }

    private static final String ADVICE = "{\"stance\":\"SURFACE_AND_RECOVER\",\"reason\":\"Hungry below ground.\",\"note\":\"Carry food down.\","
            + "\"avoid\":[{\"x\":35,\"y\":32,\"z\":87},{\"x\":900,\"y\":0,\"z\":900}],\"focus\":\"FOOD\",\"habit\":\"MORE_FOOD\",\"retire\":\"NONE\"}";
    private static final List<StrategyAdvice.Place> REPORTED = List.of(new StrategyAdvice.Place(36, 32, 86));

    @Test
    void aReplyInTheSchemaBecomesValidatedAdviceWithItsTokens() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            replies.add(completion(ADVICE, "stop"));
            var outcome = strategist.advise("Player hungry at Y 20.", REPORTED).get(10, TimeUnit.SECONDS);
            var advice = outcome.advice().orElseThrow(() -> new AssertionError(outcome.failure()));
            assertEquals(StrategyAdvice.Stance.SURFACE_AND_RECOVER, advice.stance());
            assertEquals(PlanPreferences.Focus.FOOD, advice.focus());
            assertEquals(PlanPreferences.Habit.MORE_FOOD, advice.habit());
            assertEquals(List.of(new StrategyAdvice.Place(35, 32, 87)), advice.avoid(), "the unreported place is dropped");
            assertEquals(1900, outcome.inputTokens());
            assertEquals(1536, outcome.cachedTokens());
            assertEquals(90, outcome.outputTokens());
            assertEquals("Bearer sk-test", auth.getFirst());
            var body = JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
            assertEquals("test-model", body.get("model").getAsString());
            assertEquals(1000, body.get("max_completion_tokens").getAsInt(), "OpenAI's own name for the cap");
            var format = body.getAsJsonObject("response_format");
            assertEquals("json_schema", format.get("type").getAsString());
            var schema = format.getAsJsonObject("json_schema");
            assertTrue(schema.get("strict").getAsBoolean());
            var required = schema.getAsJsonObject("schema").getAsJsonArray("required");
            for (String field : List.of("stance", "reason", "note", "avoid", "focus", "habit", "retire", "queue", "approach")) {
                assertTrue(required.toString().contains("\"" + field + "\""), field);
            }
            var messages = body.getAsJsonArray("messages");
            assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
            assertTrue(messages.get(0).getAsJsonObject().get("content").getAsString().contains("# Minecraft survival guide"), "the guide goes with the instructions");
            assertEquals("Player hungry at Y 20.", messages.get(1).getAsJsonObject().get("content").getAsString());
        }
    }

    @Test
    void aSchemaTheProviderRefusesIsAskedOnceMoreInJsonMode() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.GROQ, "gsk_test")) {
            statuses.add(new int[] {400});
            replies.add("{\"error\":{\"message\":\"response_format json_schema is not supported for this model\"}}");
            replies.add(completion("```json\n" + ADVICE + "\n```", "stop"));
            var outcome = strategist.advise("Report.", REPORTED).get(10, TimeUnit.SECONDS);
            assertTrue(outcome.advice().isPresent(), "JSON mode, fenced reply read: " + outcome.failure());
            assertEquals(2, bodies.size(), "one repair, no more");
            var second = JsonParser.parseString(bodies.get(1)).getAsJsonObject();
            assertEquals("json_object", second.getAsJsonObject("response_format").get("type").getAsString());
            assertTrue(second.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("\"stance\""),
                    "the fields described in the instructions instead");
            assertTrue(second.has("max_tokens"), "the others' name for the cap");
        }
    }

    @Test
    void failuresAreReportedNotRetried() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            statuses.add(new int[] {429});
            replies.add("{}");
            assertEquals("rate limited", strategist.advise("r", REPORTED).get(10, TimeUnit.SECONDS).failure().orElse(""));
            statuses.add(new int[] {500});
            replies.add("{}");
            assertEquals("service error 500", strategist.advise("r", REPORTED).get(10, TimeUnit.SECONDS).failure().orElse(""));
            replies.add(completion(ADVICE, "length"));
            assertEquals("stopped: length", strategist.advise("r", REPORTED).get(10, TimeUnit.SECONDS).failure().orElse(""));
            replies.add(completion("{\"stance\":\"ATTACK_EVERYTHING\"}", "stop"));
            var odd = strategist.advise("r", REPORTED).get(10, TimeUnit.SECONDS);
            assertEquals("unusable reply", odd.failure().orElse(""));
            assertEquals(1900, odd.inputTokens(), "a used request is still counted");
            replies.add(completion("not json", "stop"));
            assertEquals("unreadable reply", strategist.advise("r", REPORTED).get(10, TimeUnit.SECONDS).failure().orElse(""));
            assertEquals(5, bodies.size());
        }
    }

    @Test
    void aLocalServerNeedsNoKeyAndDeepSeekUsesJsonMode() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.COMPATIBLE, null)) {
            replies.add(completion(ADVICE, "stop"));
            assertTrue(strategist.advise("r", REPORTED).get(10, TimeUnit.SECONDS).advice().isPresent());
            assertNull(auth.getFirst(), "no Authorization header without a key");
        }
        stop();
        bodies.clear();
        try (var strategist = start(StrategistProvider.Provider.DEEPSEEK, "sk-d")) {
            replies.add(completion(ADVICE, "stop"));
            assertTrue(strategist.advise("r", REPORTED).get(10, TimeUnit.SECONDS).advice().isPresent());
            assertEquals("json_object", JsonParser.parseString(bodies.getFirst()).getAsJsonObject()
                    .getAsJsonObject("response_format").get("type").getAsString());
        }
    }

    @Test
    void followUpsInTheReplyAreParsedFromTheFixedSetsAndTheRestDropped() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            String reply = "{\"stance\":\"PRESS_ON\",\"reason\":\"Skeletons twice.\",\"note\":\"\",\"avoid\":[],\"focus\":\"NONE\","
                    + "\"habit\":\"NONE\",\"retire\":\"NONE\",\"queue\":[{\"need\":\"SHIELD\",\"count\":1,\"task\":\"NETHER_TRIP\",\"why\":\"arrows\"},"
                    + "{\"need\":\"ELYTRA\",\"count\":1,\"task\":\"END_FIGHT\",\"why\":\"\"},"
                    + "{\"need\":\"ARROWS\",\"count\":900,\"task\":\"END_FIGHT\",\"why\":\"misses\"}]}";
            replies.add(completion(reply, "stop"));
            var outcome = strategist.advise("Two deaths to skeletons.", REPORTED).get(10, TimeUnit.SECONDS);
            var queue = outcome.advice().orElseThrow(() -> new AssertionError(outcome.failure())).queue();
            assertEquals(List.of("SHIELD", "ARROWS"), queue.stream().map(w -> w.need().name()).toList(), "an elytra is no need the player can act on");
            assertEquals(64, queue.getLast().count(), "held to the cap");
        }
    }

    @Test
    void aWayOfThePlaybookIsTakenFromTheFixedSetAndNoneOrAnUnknownOneIsNone() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            String base = "{\"stance\":\"PRESS_ON\",\"reason\":\"Gold in the pack.\",\"note\":\"\",\"avoid\":[],\"focus\":\"NONE\","
                    + "\"habit\":\"NONE\",\"retire\":\"NONE\",\"queue\":[],\"approach\":";
            replies.add(completion(base + "\"BARTER\"}", "stop"));
            replies.add(completion(base + "\"NONE\"}", "stop"));
            replies.add(completion(base + "\"ELYTRA_SKIP\"}", "stop"));
            var advised = new java.util.ArrayList<java.util.Optional<dev.kindred.core.guide.Playbook.Approach>>();
            for (int i = 0; i < 3; i++) {
                var outcome = strategist.advise("Report.", REPORTED).get(10, TimeUnit.SECONDS);
                advised.add(outcome.advice().orElseThrow(() -> new AssertionError(outcome.failure())).advised());
            }
            assertEquals(List.of(java.util.Optional.of(dev.kindred.core.guide.Playbook.Approach.BARTER), java.util.Optional.empty(),
                    java.util.Optional.empty()), advised);
        }
    }

    @Test
    void theRepliesWaysAreThePlaybooksAndTheGuideNamesEach() {
        var ways = java.util.Arrays.stream(StrategistPrompt.Way.values()).map(Enum::name).toList();
        var approaches = java.util.Arrays.stream(dev.kindred.core.guide.Playbook.Approach.values()).map(Enum::name).toList();
        assertEquals("NONE", ways.getFirst());
        assertEquals(approaches, ways.subList(1, ways.size()), "the reply's set is the playbook's, and only it");
        for (String name : approaches) {
            assertTrue(StrategistPrompt.APPROACH.contains(name), name);
            assertTrue(StrategistPrompt.GUIDE.contains(name), name);
        }
    }

    @Test
    void theGuideCoversEveryNeedAndTaskTheQueueNames() {
        for (var need : dev.kindred.core.guide.Need.values()) {
            assertTrue(StrategistPrompt.GUIDE.contains(need.name()), need.name());
        }
        for (var task : dev.kindred.core.guide.Objective.values()) {
            assertTrue(StrategistPrompt.GUIDE.contains(task.name()), task.name());
        }
        // What the planner asks for the End fight is what the guide tells the model.
        for (var requirement : dev.kindred.core.guide.MinecraftGuide.requirements(dev.kindred.core.guide.Objective.END_FIGHT)) {
            assertTrue(StrategistPrompt.GUIDE.contains(requirement.count() + " ") , requirement.toString());
        }
        assertTrue(StrategistPrompt.SYSTEM.endsWith(StrategistPrompt.GUIDE));
    }
}
