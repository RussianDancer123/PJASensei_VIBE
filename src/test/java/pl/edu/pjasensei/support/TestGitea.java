package pl.edu.pjasensei.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Lets a test see and change the real test Gitea from outside Spring. */
public final class TestGitea {

    private final GiteaContainer container;

    TestGitea(GiteaContainer container) {
        this.container = container;
    }

    public boolean accountExists(String name) {
        try {
            api().get().uri("/api/v1/users/{name}", name).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    public void deleteAccount(String name) {
        api().delete().uri("/api/v1/admin/users/{name}", name).retrieve().toBodilessEntity();
    }

    /** A new public SourceRepository with several commits, one of which adds {@code first.txt}. */
    public SourceRepository newSourceRepository() {
        var name = "source-" + UUID.randomUUID();
        api().post()
                .uri("/api/v1/user/repos")
                .body(Map.of("name", name, "auto_init", true, "readme", "Default"))
                .retrieve()
                .toBodilessEntity();
        var source = new SourceRepository(serviceAccount(), name);
        addFile(source, "first.txt", "first");
        return source;
    }

    public void addFile(SourceRepository source, String path, String content) {
        api().post()
                .uri("/api/v1/repos/{owner}/{name}/contents/{path}", source.owner(), source.name(), path)
                .body(Map.of(
                        "content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))))
                .retrieve()
                .toBodilessEntity();
    }

    /** A repository of Spring's own account, which holds every snapshot and StudentRepository. */
    public JsonNode repository(String name) {
        return api().get()
                .uri("/api/v1/repos/{owner}/{name}", serviceAccount(), name)
                .retrieve()
                .body(JsonNode.class);
    }

    public boolean repositoryExists(String name) {
        try {
            repository(name);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    public boolean fileExists(String repository, String path) {
        try {
            api().get()
                    .uri("/api/v1/repos/{owner}/{name}/contents/{path}", serviceAccount(), repository, path)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    public int commitCount(String repository) {
        return api().get()
                .uri("/api/v1/repos/{owner}/{name}/commits", serviceAccount(), repository)
                .retrieve()
                .body(JsonNode.class)
                .size();
    }

    /** What an account may do with a repository of Spring's account, such as {@code write}. */
    public String permission(String repository, String account) {
        return api().get()
                .uri(
                        "/api/v1/repos/{owner}/{name}/collaborators/{account}/permission",
                        serviceAccount(),
                        repository,
                        account)
                .retrieve()
                .body(JsonNode.class)
                .path("permission")
                .asString();
    }

    public List<String> collaborators(String repository) {
        var collaborators = api().get()
                .uri("/api/v1/repos/{owner}/{name}/collaborators", serviceAccount(), repository)
                .retrieve()
                .body(JsonNode.class);
        return collaborators.valueStream().map(user -> user.path("login").asString()).toList();
    }

    private String serviceAccount() {
        return api().get().uri("/api/v1/user").retrieve().body(JsonNode.class).path("login").asString();
    }

    private RestClient api() {
        return RestClient.builder()
                .baseUrl(container.apiUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + container.apiToken())
                .build();
    }

    /** A SourceRepository on the one HTTPS host the test Gitea may import from. */
    public record SourceRepository(String owner, String name) {

        public String url() {
            return "https://%s/%s/%s.git".formatted(SourceRepositoryHost.NAME, owner, name);
        }
    }
}
