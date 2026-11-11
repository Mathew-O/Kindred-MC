package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: the watch page, started on a free port of this computer, shows the players to a request that carries
 * the operator's token and nothing to one without; what it serves is the copy the server's own thread makes once a
 * tick, and nothing asked of it changes anything. As a player plays, its card shows what it weighs, and its death is a
 * highlight. Without a port and a token the page does not start at all.
 *
 * Technical: E-48. The fixture's runtime is given a watch server on an ephemeral port. The requests are made with the
 * JDK's HTTP client on a thread of their own, never the server thread, and the steps wait for their answers: the
 * snapshot was taken on the server thread, each request was served on one of the page's threads, and a snapshot
 * published from any other thread is refused.
 */
public final class NativeWatchGameTests implements FabricGameTest {
    private static final String TOKEN = "fixture-watch-token";

    /** A request's answer: its status, its body and the headers a fixture looks at. */
    record Answer(int status, String body, String allow, String authenticate) { }

    private static Answer ask(HttpClient client, int port, String method, String path, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body(), response.headers().firstValue("Allow").orElse(""),
                response.headers().firstValue("WWW-Authenticate").orElse(""));
    }

    /** Every request the fixture makes, in order, from a thread of its own; and whether a publish from there was refused. */
    private static CompletableFuture<Map<String, Object>> askEverything(WatchServer watch) {
        var answers = new CompletableFuture<Map<String, Object>>();
        Thread asker = new Thread(() -> {
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var got = new LinkedHashMap<String, Object>();
                int port = watch.port();
                got.put("players", ask(client, port, "GET", "/api/players", TOKEN));
                got.put("byQuery", ask(client, port, "GET", "/api/players?token=" + TOKEN, null));
                got.put("noToken", ask(client, port, "GET", "/api/players", null));
                got.put("wrongToken", ask(client, port, "GET", "/api/players", "not-the-token"));
                got.put("page", ask(client, port, "GET", "/", TOKEN));
                got.put("pageNoToken", ask(client, port, "GET", "/", null));
                got.put("one", ask(client, port, "GET", "/api/players/Watched", TOKEN));
                got.put("stranger", ask(client, port, "GET", "/api/players/Nobody", TOKEN));
                got.put("events", ask(client, port, "GET", "/api/events", TOKEN));
                got.put("eval", ask(client, port, "GET", "/api/eval", TOKEN));
                got.put("post", ask(client, port, "POST", "/api/players", TOKEN));
                got.put("put", ask(client, port, "PUT", "/api/players/Watched", TOKEN));
                got.put("delete", ask(client, port, "DELETE", "/api/players/Watched", TOKEN));
                got.put("servedOn", watch.servedOnForTest());
                try {
                    watch.publish(watch.latest());
                    got.put("publishedOffThread", "accepted");
                } catch (IllegalStateException refused) {
                    got.put("publishedOffThread", "refused: " + refused.getMessage());
                }
                answers.complete(got);
            } catch (Exception failed) {
                answers.completeExceptionally(failed);
            }
        }, "watch-fixture-client");
        asker.setDaemon(true);
        asker.start();
        return answers;
    }

    private static Answer answer(Map<String, Object> got, String key) {
        return (Answer) got.get(key);
    }

    /**
     * The players, served from the snapshot taken on the server thread: 200 with the token (in the header or the query)
     * and the player in it; 401 without it or with a wrong one; 405 for anything but a read, and the player as it was.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "watchServed", tickLimit = 1200)
    public void thePlayersAreServedFromTheTicksSnapshotWithTheTokenAndNothingElse(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        var server = context.getWorld().getServer();
        WatchServer[] watch = new WatchServer[1];
        String[] before = new String[1];
        String[] serverThread = new String[1];
        List<CompletableFuture<Map<String, Object>>> asked = new java.util.ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Watched"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    try {
                        watch[0] = WatchServer.start("127.0.0.1", 0, TOKEN, server::isOnThread);
                    } catch (IOException failed) {
                        throw new GameTestException("The watch page did not start: " + failed);
                    }
                    f.runtime.watch(watch[0]);
                })
                .createAndAdd(() -> context.assertTrue(watch[0].latest() != null && watch[0].latest().players().size() == 1
                        && watch[0].latest().players().getFirst().name().equals("Watched"), "A snapshot of the player"))
                .createAndAdd(() -> {
                    serverThread[0] = Thread.currentThread().getName();
                    context.assertTrue(watch[0].latest().takenOn().equals(serverThread[0]),
                            "Taken on the server thread: " + watch[0].latest().takenOn() + " against " + serverThread[0]);
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    before[0] = bot.goal() + " " + bot.phase() + " " + bot.stopped() + " " + bot.playstyle() + " " + bot.collaboration();
                    asked.add(askEverything(watch[0]));
                })
                .createAndAdd(() -> context.assertTrue(asked.getFirst().isDone(), "Every request answered"))
                .createAndAdd(() -> {
                    Map<String, Object> got;
                    try {
                        got = asked.getFirst().join();
                    } catch (RuntimeException failed) {
                        throw new GameTestException("A request failed: " + failed);
                    }
                    KindredMod.LOGGER.info("Watch page: {}", got);
                    var players = answer(got, "players");
                    context.assertTrue(players.status() == 200 && players.body().contains("\"name\": \"Watched\"")
                            && players.body().contains("\"takenOn\": \"" + serverThread[0] + "\""), "200 with the token, the player in it: " + players);
                    context.assertTrue(answer(got, "byQuery").status() == 200, "200 with the token in the query: " + answer(got, "byQuery"));
                    context.assertTrue(answer(got, "noToken").status() == 401 && !answer(got, "noToken").body().contains("Watched")
                            && answer(got, "noToken").authenticate().startsWith("Bearer"), "401 without the token: " + answer(got, "noToken"));
                    context.assertTrue(answer(got, "wrongToken").status() == 401, "401 with a wrong token: " + answer(got, "wrongToken"));
                    context.assertTrue(answer(got, "page").status() == 200 && answer(got, "page").body().contains("<title>Kindred watch</title>"),
                            "The page with the token: " + answer(got, "page").status());
                    context.assertTrue(answer(got, "pageNoToken").status() == 401, "Not the page without it: " + answer(got, "pageNoToken").status());
                    context.assertTrue(answer(got, "one").status() == 200 && answer(got, "one").body().startsWith("{\"id\": "), "One player: " + answer(got, "one"));
                    context.assertTrue(answer(got, "stranger").status() == 404, "No stranger: " + answer(got, "stranger"));
                    context.assertTrue(answer(got, "events").status() == 200 && answer(got, "events").body().contains("\"highlights\": ["),
                            "The highlights: " + answer(got, "events"));
                    context.assertTrue(answer(got, "eval").status() == 200 && answer(got, "eval").body().equals("{\"eval\": null}"),
                            "No evaluation running: " + answer(got, "eval"));
                    for (String change : List.of("post", "put", "delete")) {
                        context.assertTrue(answer(got, change).status() == 405 && answer(got, change).allow().equals("GET"),
                                "Read-only, " + change + " refused: " + answer(got, change));
                    }
                    String servedOn = String.valueOf(got.get("servedOn"));
                    context.assertTrue(servedOn.startsWith("kindred-watch-") && !servedOn.equals(serverThread[0]), "Served off the server thread: " + servedOn);
                    context.assertTrue(String.valueOf(got.get("publishedOffThread")).startsWith("refused"),
                            "A snapshot published off the server thread is refused: " + got.get("publishedOffThread"));
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    String after = bot.goal() + " " + bot.phase() + " " + bot.stopped() + " " + bot.playstyle() + " " + bot.collaboration();
                    context.assertTrue(after.equals(before[0]), "Nothing changed: " + before[0] + " then " + after);
                    f.runtime.watch(null);
                    watch[0].close();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * What an AI player was told on the Wire is on its card (F-34c), as an onlooker may read it, since the page may be on
     * a stream: Ada tells her faction she needs iron; Bram's card says a faction message came from Ada, never the iron.
     * The K panel's state for Bram says the same.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "watchTold", tickLimit = 1200)
    public void whatItWasToldIsOnItsCardWithNothingNarrowerThanPublicInWords(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        var server = context.getWorld().getServer();
        WatchServer[] watch = new WatchServer[1];
        UUID[] ids = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(st -> st.connected()), "Both connected");
                    for (var st : statuses) {
                        var body = server.getPlayerManager().getPlayer(st.nativeId());
                        ids[body.getName().getString().equals("Ada") ? 0 : 1] = f.runtime.wire().idOf(body);
                    }
                    f.runtime.setFactionOf(id -> id.equals(ids[0]) || id.equals(ids[1]) ? "oak" : "");
                    var d = f.runtime.wire().send(dev.kindred.core.wire.Message.of("faction.need", ids[0], dev.kindred.core.wire.Scope.faction("oak"),
                            Map.of("needs", dev.kindred.core.wire.Fact.of("iron", dev.kindred.core.wire.Visibility.FACTION)), List.of()));
                    context.assertTrue(d.sent() && d.receivers().equals(List.of(ids[1])), "Told to Bram: " + d);
                    try {
                        watch[0] = WatchServer.start("127.0.0.1", 0, TOKEN, server::isOnThread);
                    } catch (IOException failed) {
                        throw new GameTestException("The watch page did not start: " + failed);
                    }
                    f.runtime.watch(watch[0]);
                })
                .createAndAdd(() -> {
                    var latest = watch[0].latest();
                    context.assertTrue(latest != null && latest.players().size() == 2, "A snapshot of both");
                    var bram = latest.players().stream().filter(p -> p.name().equals("Bram")).findFirst().orElseThrow();
                    var ada = latest.players().stream().filter(p -> p.name().equals("Ada")).findFirst().orElseThrow();
                    context.assertTrue(bram.told().equals(List.of("[faction] Ada: need (kept to its faction)")), "Bram's card: " + bram.told());
                    context.assertTrue(ada.told().isEmpty(), "Ada, the sender, was told nothing: " + ada.told());
                    context.assertTrue(latest.players().stream().flatMap(p -> p.told().stream()).noneMatch(line -> line.contains("iron")), "Never the iron");
                    // The K panel shows the same, in its state for whoever may control Bram (F-34c).
                    var panel = f.runtime.panelBots(Actor.console()).stream().filter(b -> b.name().equals("Bram")).findFirst().orElseThrow();
                    context.assertTrue(panel.told().equals(List.of("[faction] Ada: need (kept to its faction)")), "Bram in the panel: " + panel.told());
                    var json = KindredPanelService.botJson(panel, null).getAsJsonArray("told");
                    context.assertTrue(json.size() == 1 && json.get(0).getAsString().equals("[faction] Ada: need (kept to its faction)")
                            && !json.toString().contains("iron"), "The panel's state: " + json);
                    f.runtime.watch(null);
                    watch[0].close();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * As it plays, its card fills: what it is doing, what it weighed at its last decision with the scores, and its own
     * lines; its death is a highlight, as the game records it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "watchPlays", tickLimit = 1200)
    public void asItPlaysItsCardShowsWhatItWeighsAndItsDeathIsAHighlight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var server = context.getWorld().getServer();
        WatchServer[] watch = new WatchServer[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Watched"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    try {
                        watch[0] = WatchServer.start("127.0.0.1", 0, TOKEN, server::isOnThread);
                    } catch (IOException failed) {
                        throw new GameTestException("The watch page did not start: " + failed);
                    }
                    f.runtime.watch(watch[0]);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var latest = watch[0].latest();
                    var card = latest == null || latest.players().isEmpty() ? null : latest.players().getFirst();
                    context.assertTrue(card != null && !card.candidates().isEmpty() && !card.lines().isEmpty() && !card.doing().isEmpty()
                            && card.goal().equals(dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL.label()), "Its card filled: " + card);
                    KindredMod.LOGGER.info("Watch card: doing '{}', weighed {}, its last line '{}'", card.doing(), card.candidates(), card.lines().getLast());
                    var first = card.candidates().getFirst();
                    context.assertTrue(card.candidates().stream().allMatch(c -> c.score() <= first.score()), "Highest score first: " + card.candidates());
                })
                .createAndAdd(() -> f.player().kill())
                .createAndAdd(() -> {
                    var deaths = watch[0].latest().highlights().stream().filter(h -> h.kind().equals("death")).toList();
                    context.assertTrue(deaths.size() == 1 && deaths.getFirst().player().equals("Watched") && deaths.getFirst().text().contains("Watched"),
                            "Its death a highlight: " + watch[0].latest().highlights());
                    context.assertTrue(watch[0].latest().eventsJson().contains("\"kind\": \"death\""), "In /api/events: " + watch[0].latest().eventsJson());
                    f.runtime.watch(null);
                    watch[0].close();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Off unless a port and a token are both given; a port that is not a number is off too; on, it answers only here. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "watchOff", tickLimit = 200)
    public void itIsOffUnlessAPortAndATokenAreGivenAndAnswersOnlyHereByDefault(TestContext context) {
        var server = context.getWorld().getServer();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    if (System.getenv("KINDRED_WATCH_PORT") == null) {
                        context.assertTrue(WatchServer.fromEnvironment(server::isOnThread).isEmpty(), "Off by default");
                    }
                    context.assertTrue(WatchServer.fromSettings(null, TOKEN, null, server::isOnThread).isEmpty(), "Off with no port");
                    context.assertTrue(WatchServer.fromSettings("0", null, null, server::isOnThread).isEmpty(), "Off with no token");
                    context.assertTrue(WatchServer.fromSettings("0", "  ", null, server::isOnThread).isEmpty(), "Off with a blank token");
                    context.assertTrue(WatchServer.fromSettings("eighty", TOKEN, null, server::isOnThread).isEmpty(), "Off with a port that is no number");
                    var on = WatchServer.fromSettings("0", TOKEN, null, server::isOnThread);
                    context.assertTrue(on.isPresent(), "On with a port and a token");
                    try (var watch = on.get()) {
                        context.assertTrue(watch.address().getAddress().isLoopbackAddress(), "Bound to this computer alone: " + watch.address());
                    }
                })
                .completeIfSuccessful();
    }
}
