package dev.kindred.fabric;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.kindred.core.watch.WatchSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * In plain terms: an optional web page that shows what each AI player is doing and weighing, for an operator or a
 * stream to watch. It is off unless the server's operator turns it on, it answers only on this computer unless told
 * otherwise, every request needs the operator's token, and nothing on it can change anything in the game.
 *
 * Technical: E-48. The JDK's own HTTP server ({@code com.sun.net.httpserver}), no new dependency, on its own two daemon
 * threads. Once a tick the runtime publishes an immutable {@link WatchSnapshot} on the server thread; the handlers serve
 * only that, and are asserted to be off the server thread. Read-only (D9): every endpoint is a GET; anything else is
 * 405. A request without the token (the {@code Authorization: Bearer} header, or {@code token} in the query) is 401.
 */
final class WatchServer implements AutoCloseable {
    /** Server-sent event streams open at once, at most; each holds a thread. */
    static final int MAX_STREAMS = 4;
    /** How long one event stream lasts before the page asks again. */
    static final long STREAM_MILLIS = 600_000;

    private final HttpServer http;
    private final ExecutorService threads;
    private final byte[] token;
    private final BooleanSupplier onServerThread;
    private final AtomicInteger streams = new AtomicInteger();
    private volatile WatchSnapshot latest;
    /** For fixtures: the name of the last thread a request was served on. */
    private volatile String servedOn = "";

    private WatchServer(HttpServer http, ExecutorService threads, String token, BooleanSupplier onServerThread) {
        this.http = http;
        this.threads = threads;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.onServerThread = onServerThread;
    }

    /**
     * The watch page when {@code KINDRED_WATCH_PORT} is set and {@code KINDRED_WATCH_TOKEN} too, bound to
     * {@code KINDRED_WATCH_BIND} or else 127.0.0.1; otherwise none, and the log says why when it was asked for.
     */
    static Optional<WatchServer> fromEnvironment(BooleanSupplier onServerThread) {
        return fromSettings(System.getenv("KINDRED_WATCH_PORT"), System.getenv("KINDRED_WATCH_TOKEN"), System.getenv("KINDRED_WATCH_BIND"), onServerThread);
    }

    /** As {@link #fromEnvironment}, from the three settings as given: off with no port, and off with no token. */
    static Optional<WatchServer> fromSettings(String port, String token, String bind, BooleanSupplier onServerThread) {
        if (port == null || port.isBlank()) return Optional.empty();
        if (token == null || token.isBlank()) {
            KindredMod.LOGGER.warn("KINDRED_WATCH_PORT is set but KINDRED_WATCH_TOKEN is not; the watch page is off");
            return Optional.empty();
        }
        try {
            return Optional.of(start(bind == null || bind.isBlank() ? "127.0.0.1" : bind.trim(), Integer.parseInt(port.trim()), token.trim(), onServerThread));
        } catch (NumberFormatException unreadable) {
            KindredMod.LOGGER.warn("KINDRED_WATCH_PORT is not a port number ('{}'); the watch page is off", port);
        } catch (IOException | LinkageError failed) {
            KindredMod.LOGGER.warn("The watch page could not start on {}: {}", port, failed.toString());
        }
        return Optional.empty();
    }

    /** A watch server on {@code bind}:{@code port} (0 for any free port), serving with {@code token}. */
    static WatchServer start(String bind, int port, String token, BooleanSupplier onServerThread) throws IOException {
        var http = HttpServer.create(new InetSocketAddress(bind, port), 16);
        AtomicInteger made = new AtomicInteger();
        ExecutorService threads = Executors.newFixedThreadPool(2 + MAX_STREAMS, task -> {
            Thread thread = new Thread(task, "kindred-watch-" + made.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        var server = new WatchServer(http, threads, token, onServerThread);
        http.setExecutor(threads);
        http.createContext("/", server::handle);
        http.start();
        KindredMod.LOGGER.info("Kindred watch page on http://{}:{}/ (read-only; every request needs the token)", bind, http.getAddress().getPort());
        return server;
    }

    int port() { return http.getAddress().getPort(); }

    InetSocketAddress address() { return http.getAddress(); }

    /** The latest snapshot, published on the server thread once a tick. Refused from any other thread. */
    void publish(WatchSnapshot snapshot) {
        if (!onServerThread.getAsBoolean()) throw new IllegalStateException("A watch snapshot is taken on the server thread only");
        latest = snapshot;
    }

    WatchSnapshot latest() { return latest; }

    String servedOnForTest() { return servedOn; }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // The handlers read the published snapshot and nothing else: no world, entry or store off the server thread.
            if (onServerThread.getAsBoolean()) throw new IllegalStateException("The watch page is served off the server thread only");
            servedOn = Thread.currentThread().getName();
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Allow", "GET");
                send(exchange, 405, "text/plain", "Read-only: GET only.");
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"kindred-watch\"");
                send(exchange, 401, "text/plain", "The watch token is missing or wrong.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            WatchSnapshot snapshot = latest;
            if (path.equals("/") || path.equals("/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", page());
            } else if (snapshot == null) {
                send(exchange, 503, "text/plain", "No snapshot yet.");
            } else if (path.equals("/api/players")) {
                send(exchange, 200, "application/json", snapshot.playersJson());
            } else if (path.startsWith("/api/players/")) {
                var one = snapshot.playerJson(URLDecoder.decode(path.substring("/api/players/".length()), StandardCharsets.UTF_8));
                if (one.isPresent()) send(exchange, 200, "application/json", one.get());
                else send(exchange, 404, "text/plain", "No such player.");
            } else if (path.equals("/api/events")) {
                send(exchange, 200, "application/json", snapshot.eventsJson());
            } else if (path.equals("/api/eval")) {
                send(exchange, 200, "application/json", snapshot.evalJson());
            } else if (path.equals("/api/stream")) {
                stream(exchange);
            } else {
                send(exchange, 404, "text/plain", "Not found.");
            }
        }
    }

    /** The players as server-sent events, once a second, for at most {@link #STREAM_MILLIS}. */
    private void stream(HttpExchange exchange) throws IOException {
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet();
            send(exchange, 503, "text/plain", "Too many streams open.");
            return;
        }
        try {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            long until = System.currentTimeMillis() + STREAM_MILLIS;
            while (System.currentTimeMillis() < until) {
                WatchSnapshot snapshot = latest;
                if (snapshot != null) {
                    out.write(("data: " + snapshot.playersJson() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException gone) {
            // The page went away.
        } finally {
            streams.decrementAndGet();
        }
    }

    /** The token in the {@code Authorization: Bearer} header or in the query, compared in constant time. */
    private boolean authorized(HttpExchange exchange) {
        String given = null;
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) given = header.substring("Bearer ".length()).trim();
        String query = exchange.getRequestURI().getRawQuery();
        if (given == null && query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("token=")) given = URLDecoder.decode(pair.substring("token=".length()), StandardCharsets.UTF_8);
            }
        }
        return given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }

    private static String pageText;

    private static synchronized String page() throws IOException {
        if (pageText == null) {
            try (InputStream in = WatchSnapshot.class.getResourceAsStream("watch.html")) {
                if (in == null) throw new IOException("watch.html is missing beside WatchSnapshot");
                pageText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return pageText;
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        http.stop(0);
        threads.shutdownNow();
    }
}
