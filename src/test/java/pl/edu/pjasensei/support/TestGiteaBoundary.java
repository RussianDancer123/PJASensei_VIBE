package pl.edu.pjasensei.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Sits between Spring and the real test Gitea, so a test can make Gitea fail while Spring's production
 * client is what gets exercised. It passes everything on until a test asks otherwise.
 *
 * <p>Rules and counts match a request written as {@code METHOD /path?query body}, so a test can pick
 * out one Student by a name in the body. Call {@link #reset()} after each test using this.
 */
public final class TestGiteaBoundary {

    private static final List<String> PASSED_ON_HEADERS = List.of("Authorization", "Content-Type", "Accept");

    private final Supplier<String> gitea;
    private final String url;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<Fault> faults = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    TestGiteaBoundary(Supplier<String> gitea) {
        this.gitea = gitea;
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
            url = "http://localhost:%d".formatted(server.getAddress().getPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return url;
    }

    /** The next matching requests get a 500, and Gitea never sees them. */
    public void failNext(int times, String request) {
        faults.add(new Fault(Pattern.compile(request), new AtomicInteger(times), false));
    }

    /**
     * The next matching requests reach Gitea and do their work, but Spring gets no answer, like a
     * timeout on an attempt that actually succeeded.
     */
    public void loseAnswerToNext(int times, String request) {
        faults.add(new Fault(Pattern.compile(request), new AtomicInteger(times), true));
    }

    /** How many matching requests Spring has made since the last {@link #reset()}. */
    public long requestsMatching(String request) {
        var pattern = Pattern.compile(request);
        return requests.stream().filter(seen -> pattern.matcher(seen).find()).count();
    }

    public void reset() {
        faults.clear();
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            var uri = exchange.getRequestURI();
            var target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            var request = "%s %s %s".formatted(
                    exchange.getRequestMethod(), target, new String(body, StandardCharsets.UTF_8));
            requests.add(request);

            var fault = faults.stream().filter(candidate -> candidate.appliesTo(request)).findFirst();
            if (fault.isPresent() && !fault.get().worksFirst()) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            var answer = passOn(exchange, target, body);
            if (fault.isPresent()) {
                return; // Closing without an answer: Spring sees the connection end.
            }
            answer.headers().firstValue("Content-Type")
                    .ifPresent(type -> exchange.getResponseHeaders().set("Content-Type", type));
            exchange.sendResponseHeaders(answer.statusCode(), answer.body().length == 0 ? -1 : answer.body().length);
            if (answer.body().length > 0) {
                exchange.getResponseBody().write(answer.body());
            }
        }
    }

    private HttpResponse<byte[]> passOn(HttpExchange exchange, String target, byte[] body) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(gitea.get() + target))
                .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
        for (String header : PASSED_ON_HEADERS) {
            var value = exchange.getRequestHeaders().getFirst(header);
            if (value != null) {
                request.header(header, value);
            }
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private record Fault(Pattern request, AtomicInteger timesLeft, boolean worksFirst) {

        boolean appliesTo(String seen) {
            return request.matcher(seen).find() && timesLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
        }
    }
}
