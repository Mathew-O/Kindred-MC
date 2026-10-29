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

/**
 * In plain terms: checks the connection to the many optional AI services that share one common format,
 * against a stand-in server on this computer with no network, keys or cost: a well-formed reply becomes
 * checked advice with its cost counted, a reply format the service refuses is asked for once more in a
 * simpler form, failures are reported and not retried, chat replies are checked the same way and give up
 * after eight seconds, and anything outside the fixed choices is dropped.
 *
 * Technical: The OpenAI-compatible strategist against a local fake server: no network, no keys, no cost.
 */
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

    private static final Strategist.Question RAID = new Strategist.Question("You decide for a player in a match.",
            "You saw Steve, a runner, use a chest 20 blocks off. Raid it?", List.of("RAID", "LEAVE", "WATCH"), 0);

    @Test
    void aQuestionsAnswerIsOneOfTheChoicesOfferedWithItsReason() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            replies.add(completion("{\"choice\":\"leave\",\"text\":\"\",\"reason\":\"Steve is still close by.\"}", "stop"));
            var answer = strategist.ask(RAID).get(10, TimeUnit.SECONDS);
            assertEquals(java.util.Optional.of("LEAVE"), answer.choice(), "As offered, whatever its case: " + answer.failure());
            assertEquals("Steve is still close by.", answer.reason());
            assertTrue(answer.text().isEmpty());
            assertEquals(1900, answer.inputTokens());
            var body = JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
            assertEquals(Strategist.ASK_MAX_OUTPUT_TOKENS, body.get("max_completion_tokens").getAsInt());
            var schema = body.getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema");
            assertEquals("[\"RAID\",\"LEAVE\",\"WATCH\"]", schema.getAsJsonObject("properties").getAsJsonObject("choice").get("enum").toString());
            var messages = body.getAsJsonArray("messages");
            assertEquals("You decide for a player in a match.", messages.get(0).getAsJsonObject().get("content").getAsString(), "Its own instructions, not the guide");
            assertEquals(RAID.text(), messages.get(1).getAsJsonObject().get("content").getAsString());
        }
    }

    @Test
    void aChoiceNotOfferedOrANameTooLongIsNoAnswer() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            replies.add(completion("{\"choice\":\"BURN_IT\",\"text\":\"\",\"reason\":\"Why not.\"}", "stop"));
            var burned = strategist.ask(RAID).get(10, TimeUnit.SECONDS);
            assertTrue(burned.choice().isEmpty());
            assertTrue(burned.failure().orElse("").startsWith("not one of the choices"), burned.failure().toString());
            var name = new Strategist.Question("Name a player's sword.", "A diamond sword, enchanted.", List.of(), 32);
            replies.add(completion("{\"choice\":\"\",\"text\":\"The Unbelievably Long Name Of Doom Itself\",\"reason\":\"Grand.\"}", "stop"));
            assertEquals(java.util.Optional.of("no usable text"), strategist.ask(name).get(10, TimeUnit.SECONDS).failure());
            replies.add(completion("{\"choice\":\"\",\"text\":\" Emberfang \",\"reason\":\"It burns.\"}", "stop"));
            assertEquals(java.util.Optional.of("Emberfang"), strategist.ask(name).get(10, TimeUnit.SECONDS).text());
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

    private static final Strategist.Conversation TALK = new Strategist.Conversation("You are Echo, a player.",
            "What you know:\nWhat you are doing: gathering logs\n\n" + ConversationPrompt.SAID + "\n\"Steve: Echo, what are you doing?\"", false);

    @Test
    void aConversationAsksForTheTwoFieldsInAStrictSchemaWithTheChatLanesCap() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            replies.add(completion("{\"say\":\" Chopping logs for a table. \",\"reason\":\"I am gathering logs.\"}", "stop"));
            var line = strategist.converse(TALK).get(10, TimeUnit.SECONDS);
            assertEquals(java.util.Optional.of("Chopping logs for a table."), line.say(), "trimmed: " + line.failure());
            assertEquals("I am gathering logs.", line.reason());
            assertEquals(1900, line.inputTokens());
            assertEquals(1536, line.cachedTokens());
            assertEquals(90, line.outputTokens());
            var body = JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
            assertEquals(Strategist.CHAT_MAX_OUTPUT_TOKENS, body.get("max_completion_tokens").getAsInt());
            var format = body.getAsJsonObject("response_format");
            assertEquals("json_schema", format.get("type").getAsString());
            var spec = format.getAsJsonObject("json_schema");
            assertTrue(spec.get("strict").getAsBoolean());
            var shape = spec.getAsJsonObject("schema");
            assertFalse(shape.get("additionalProperties").getAsBoolean());
            assertEquals("[\"say\",\"ask\",\"item\",\"reason\"]", shape.getAsJsonArray("required").toString());
            assertEquals("[\"NONE\",\"COME\",\"WAIT\",\"STOP\",\"TOSS\"]",
                    shape.getAsJsonObject("properties").getAsJsonObject("ask").get("enum").toString(), "the asks, and only they");
            var messages = body.getAsJsonArray("messages");
            assertEquals(TALK.system(), messages.get(0).getAsJsonObject().get("content").getAsString(), "the player's own instructions, not the guide");
            assertEquals(TALK.text(), messages.get(1).getAsJsonObject().get("content").getAsString());
        }
    }

    @Test
    void anAskInTheReplyIsTakenOnlyFromOneWhoMayCommandThePlayer() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            var owner = new Strategist.Conversation("You are Echo.", "What you know: ...", true, true);
            replies.add(completion("{\"say\":\"Here, have some.\",\"ask\":\"TOSS\",\"item\":\"minecraft:bread\",\"reason\":\"asked for bread\"}", "stop"));
            var line = strategist.converse(owner).get(10, TimeUnit.SECONDS);
            assertEquals(Strategist.Ask.TOSS, line.ask(), "" + line.failure());
            assertEquals(java.util.Optional.of("minecraft:bread"), line.item());
            replies.add(completion("{\"say\":\"On my way.\",\"ask\":\"COME\",\"item\":\"\",\"reason\":\"asked to come\"}", "stop"));
            var refused = strategist.converse(TALK).get(10, TimeUnit.SECONDS);
            assertEquals(java.util.Optional.of("an ask from one who may not command it"), refused.failure(), "TALK's listener may not ask");
        }
    }

    @Test
    void aRefusedConversationReplyIsAFailureAndCostsWhatItUsed() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.OPENAI, "sk-test")) {
            replies.add(completion("{\"say\":\"I'm at 120, 64, -35.\",\"reason\":\"Asked where.\"}", "stop"));
            var placed = strategist.converse(TALK).get(10, TimeUnit.SECONDS);
            assertTrue(placed.say().isEmpty());
            assertEquals(java.util.Optional.of("a place this listener may not know"), placed.failure());
            assertEquals(1900, placed.inputTokens(), "a used request is still counted");
            replies.add(completion("{\"say\":\"/kill Steve\",\"reason\":\"\"}", "stop"));
            assertEquals(java.util.Optional.of("a command"), strategist.converse(TALK).get(10, TimeUnit.SECONDS).failure());
            replies.add(completion("{\"say\":\"Chopping\",\"reason\":\"\"}", "length"));
            assertEquals(java.util.Optional.of("stopped: length"), strategist.converse(TALK).get(10, TimeUnit.SECONDS).failure());
            statuses.add(new int[] {500});
            replies.add("{}");
            assertEquals(java.util.Optional.of("service error 500"), strategist.converse(TALK).get(10, TimeUnit.SECONDS).failure());
            assertEquals(4, bodies.size(), "no retries");
        }
    }

    @Test
    void aConversationSchemaTheProviderRefusesIsRepairedOnceInJsonMode() throws Exception {
        try (var strategist = start(StrategistProvider.Provider.GROQ, "gsk_test")) {
            statuses.add(new int[] {400});
            replies.add("{\"error\":{\"message\":\"response_format json_schema is not supported for this model\"}}");
            replies.add(completion("```json\n{\"say\":\"Chopping logs.\",\"reason\":\"Logs.\"}\n```", "stop"));
            var line = strategist.converse(TALK).get(10, TimeUnit.SECONDS);
            assertEquals(java.util.Optional.of("Chopping logs."), line.say(), "JSON mode, fenced reply read: " + line.failure());
            assertEquals(2, bodies.size(), "one repair, no more");
            var second = JsonParser.parseString(bodies.get(1)).getAsJsonObject();
            assertEquals("json_object", second.getAsJsonObject("response_format").get("type").getAsString());
            assertTrue(second.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("\"say\""),
                    "the fields described in the instructions instead");
            assertEquals(Strategist.CHAT_MAX_OUTPUT_TOKENS, second.get("max_tokens").getAsInt());
        }
    }

    @Test
    void aConversationGivesUpAtTheChatLanesEightSeconds() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(12)); // Slower than the deadline.
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try (var strategist = new OpenAiCompatibleStrategist(new StrategistProvider.Choice(StrategistProvider.Provider.OPENAI, "sk", "m", base),
                HttpClient.newBuilder().build())) {
            long started = System.nanoTime();
            var line = strategist.converse(TALK).get(20, TimeUnit.SECONDS);
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertEquals(java.util.Optional.of("deadline passed"), line.failure());
            assertTrue(millis >= 7_500 && millis < 10_000, "within the chat lane's deadline, not the planning lane's: " + millis);
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
