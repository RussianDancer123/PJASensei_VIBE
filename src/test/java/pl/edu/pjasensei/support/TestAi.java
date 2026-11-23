package pl.edu.pjasensei.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the AI at the network level, keeping to its contract in the spec, so Spring's
 * production client is what gets exercised. One instance serves every test in the JVM and tests run
 * one at a time, so a test compares counts with those from before it and calls {@link #behaveNormally()}
 * when it has made this misbehave.
 */
public final class TestAi {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern MESSAGES = Pattern.compile("/ai/conversations/([^/]+)/messages");

    private final String url;
    private final Map<String, List<Object>> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionsCreated = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Duration sessionCreationDelay = Duration.ZERO;
    private volatile Integer failureStatus;
    private volatile boolean droppingConnections;
    private volatile boolean forgettingEverySession;
    private volatile String sessionCreationBody;
    private volatile String replyBody;
    private volatile String historyBody;
    private volatile JsonNode lastMessage;

    TestAi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                var thread = new Thread(runnable);
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/", this::handle);
            server.start();
            url = "http://localhost:%d/ai".formatted(server.getAddress().getPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return url;
    }

    public int sessionsCreated() {
        return sessionsCreated.get();
    }

    /** Every request that reached this so far, by any test. */
    public int requests() {
        return requests.get();
    }

    /** What the last message arrived as. */
    public JsonNode lastMessage() {
        return lastMessage;
    }

    /** As a restarted AI would be: every session so far is unknown from now on. */
    public void forgetSessions() {
        sessions.clear();
    }

    /** Every message and history request gets a 404, even on a session just made. */
    public void forgetEverySessionAtOnce() {
        forgettingEverySession = true;
    }

    public void delaySessionCreation(Duration delay) {
        sessionCreationDelay = delay;
    }

    public void failWith(int status) {
        failureStatus = status;
    }

    public void dropConnections() {
        droppingConnections = true;
    }

    /** A 200 to creating a session, with this as its body. */
    public void answerSessionCreationWith(String body) {
        sessionCreationBody = body;
    }

    /** A 200 to every message, with this as its body. */
    public void answerMessagesWith(String body) {
        replyBody = body;
    }

    /** A 200 to every history request, with this as its body. */
    public void answerHistoryWith(String body) {
        historyBody = body;
    }

    public void behaveNormally() {
        historyBody = null;
        sessionCreationDelay = Duration.ZERO;
        failureStatus = null;
        droppingConnections = false;
        forgettingEverySession = false;
        sessionCreationBody = null;
        replyBody = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            if (droppingConnections) {
                return;
            }
            if (failureStatus != null) {
                send(exchange, failureStatus, Map.of("message", "Failing on purpose"));
                return;
            }
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            var messages = MESSAGES.matcher(path);
            if (path.equals("/ai/conversations") && method.equals("POST")) {
                createSession(exchange);
            } else if (messages.matches() && method.equals("POST")) {
                sendMessage(exchange, messages.group(1));
            } else if (messages.matches() && method.equals("GET")) {
                history(exchange, messages.group(1));
            } else {
                send(exchange, 405, Map.of());
            }
        }
    }

    private void createSession(HttpExchange exchange) throws IOException {
        sessionsCreated.incrementAndGet();
        if (!JSON.readTree(exchange.getRequestBody().readAllBytes()).has("config")) {
            send(exchange, 422, Map.of("detail", "config is required"));
            return;
        }
        try {
            Thread.sleep(sessionCreationDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (sessionCreationBody != null) {
            sendRaw(exchange, sessionCreationBody);
            return;
        }
        var id = UUID.randomUUID().toString();
        sessions.put(id, new CopyOnWriteArrayList<>());
        send(exchange, 200, Map.of("conversation_id", id));
    }

    private void sendMessage(HttpExchange exchange, String id) throws IOException {
        JsonNode message = JSON.readTree(exchange.getRequestBody().readAllBytes());
        lastMessage = message;
        var history = forgettingEverySession ? null : sessions.get(id);
        if (history == null) {
            send(exchange, 404, Map.of("message", "Unknown session"));
            return;
        }
        if (!isCodeContext(message.path("code_context"))) {
            send(exchange, 422, Map.of("detail", "code_context is not of the right shape"));
            return;
        }
        if (replyBody != null) {
            sendRaw(exchange, replyBody);
            return;
        }
        var question = message.get("question").asString();
        var answer = new StringBuilder(question).reverse().toString();
        history.add(Map.of("role", "user", "content", question));
        history.add(Map.of("role", "assistant", "content", answer));
        send(exchange, 200, Map.of("answer", answer));
    }

    /** Optional, but when sent it holds the open file's name and code, and the names of other open files. */
    private static boolean isCodeContext(JsonNode context) {
        if (context.isMissingNode()) {
            return true;
        }
        var openFiles = context.path("open_files");
        return context.path("current_file_name").isString()
                && context.path("current_code").isString()
                && (openFiles.isMissingNode() || openFiles.isArray() && openFiles.valueStream().allMatch(JsonNode::isString));
    }

    private void history(HttpExchange exchange, String id) throws IOException {
        var history = forgettingEverySession ? null : sessions.get(id);
        if (history == null) {
            send(exchange, 404, Map.of("message", "Unknown session"));
            return;
        }
        if (historyBody != null) {
            sendRaw(exchange, historyBody);
            return;
        }
        send(exchange, 200, Map.of("messages", new ArrayList<>(history)));
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        var bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendRaw(HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
