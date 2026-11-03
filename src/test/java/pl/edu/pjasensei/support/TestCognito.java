package pl.edu.pjasensei.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the Cognito API at the network level, speaking its real JSON protocol, so Spring's
 * production client is what gets exercised. One instance serves every test in the JVM, so a test
 * takes its own group numbers from {@link #newGroupNumber()}.
 */
public final class TestCognito {

    static final String REGION = "eu-central-1";
    static final String USER_POOL_ID = "eu-central-1_test";
    static final String ACCESS_KEY_ID = "test-access-key";
    static final String SECRET_ACCESS_KEY = "test-secret-key";

    private static final String TARGET_PREFIX = "AWSCognitoIdentityProviderService.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String endpoint;
    private final AtomicInteger groupNumbers = new AtomicInteger(1000);
    private final Map<String, Member> users = new ConcurrentHashMap<>();
    private final Map<String, List<List<Member>>> groups = new ConcurrentHashMap<>();
    private final Map<String, Integer> failingPages = new ConcurrentHashMap<>();
    private final List<String> requestedGroups = new CopyOnWriteArrayList<>();

    TestCognito() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            endpoint = "http://localhost:%d".formatted(server.getAddress().getPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String endpoint() {
        return endpoint;
    }

    /** A number no other test uses, so that a group of this test is its own. */
    public int newGroupNumber() {
        return groupNumbers.incrementAndGet();
    }

    /** A person whose attributes can be read by username. The email may be null. */
    public void addUser(Member user) {
        users.put(user.username(), user);
    }

    /** Sets what a group holds, one list per result page, replacing what it held before. */
    @SafeVarargs
    public final void setGroup(String group, List<Member>... pages) {
        groups.put(group, List.of(pages));
        failingPages.remove(group);
    }

    /** Reading the page at this zero-based index keeps failing, after the pages before it worked. */
    public void failReading(String group, int page) {
        failingPages.put(group, page);
    }

    /** Every group asked for so far, by any test. */
    public List<String> requestedGroups() {
        return List.copyOf(requestedGroups);
    }

    public record Member(String username, String sub, String email) {}

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY_ID)) {
                send(exchange, 403, error("NotAuthorizedException", "Not signed with the configured credentials"));
                return;
            }
            if (!USER_POOL_ID.equals(request.path("UserPoolId").asString(""))) {
                send(exchange, 400, error("ResourceNotFoundException", "Unknown user pool"));
                return;
            }
            String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
            switch (target == null ? "" : target.replace(TARGET_PREFIX, "")) {
                case "ListUsersInGroup" -> listUsersInGroup(exchange, request);
                case "AdminGetUser" -> adminGetUser(exchange, request);
                default -> send(exchange, 400, error("UnknownOperationException", "Unknown operation " + target));
            }
        }
    }

    private void listUsersInGroup(HttpExchange exchange, JsonNode request) throws IOException {
        String group = request.path("GroupName").asString("");
        requestedGroups.add(group);
        List<List<Member>> pages = groups.get(group);
        if (pages == null) {
            send(exchange, 400, error("ResourceNotFoundException", "Group not found"));
            return;
        }
        int page = request.hasNonNull("NextToken") ? Integer.parseInt(request.get("NextToken").asString()) : 0;
        if (failingPages.containsKey(group) && failingPages.get(group) == page) {
            send(exchange, 500, error("InternalErrorException", "Something went wrong"));
            return;
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("Users", (pages.isEmpty() ? List.<Member>of() : pages.get(page)).stream()
                .map(member -> Map.of("Username", member.username(), "Attributes", attributes(member)))
                .toList());
        if (page + 1 < pages.size()) {
            body.put("NextToken", String.valueOf(page + 1));
        }
        send(exchange, 200, body);
    }

    private void adminGetUser(HttpExchange exchange, JsonNode request) throws IOException {
        Member user = users.get(request.path("Username").asString(""));
        if (user == null) {
            send(exchange, 400, error("UserNotFoundException", "User does not exist"));
            return;
        }
        send(exchange, 200, Map.of("Username", user.username(), "UserAttributes", attributes(user)));
    }

    private static List<Map<String, String>> attributes(Member member) {
        var attributes = new ArrayList<Map<String, String>>();
        if (member.sub() != null) {
            attributes.add(Map.of("Name", "sub", "Value", member.sub()));
        }
        if (member.email() != null) {
            attributes.add(Map.of("Name", "email", "Value", member.email()));
        }
        return attributes;
    }

    private static Map<String, String> error(String type, String message) {
        return Map.of("__type", type, "message", message);
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.1");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
