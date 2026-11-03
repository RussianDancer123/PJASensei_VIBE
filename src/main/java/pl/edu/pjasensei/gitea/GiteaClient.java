package pl.edu.pjasensei.gitea;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Spring's only way into Gitea, which nothing else may reach. */
@Component
@EnableConfigurationProperties(GiteaProperties.class)
public class GiteaClient {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMPLATE_INSTRUCTIONS = ".gitea/template";

    private final RestClient http;
    private final RestClient importHttp;

    GiteaClient(RestClient.Builder builder, GiteaProperties properties) {
        builder.baseUrl(properties.url())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + properties.apiToken());
        this.http = builder.build();
        // Gitea answers an import only when it has copied the repository, which can take a while.
        this.importHttp = builder.clone()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(Duration.ofSeconds(5), properties.importTimeout())))
                .build();
    }

    /** Whether Gitea answers and accepts the token: this endpoint is refused without a valid one. */
    public boolean acceptsOurToken() {
        try {
            http.get().uri("/api/v1/user").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    /**
     * Makes sure a person has their technical account, safe to repeat and to run at the same time as
     * another call. It is named after the {@code sub}, the one identifier Students and Instructors
     * both have and that never changes, so a person with both roles has one account. Nobody signs in
     * with it: the password goes nowhere, and the address only fills a required field.
     *
     * @throws RestClientException when Gitea cannot or will not make the account
     */
    public void ensureAccount(String sub) {
        if (accountExists(sub)) {
            return;
        }
        try {
            http.post()
                    .uri("/api/v1/admin/users")
                    .body(Map.of(
                            "username", sub,
                            "email", sub + "@pjasensei.invalid",
                            "password", randomPassword(),
                            "must_change_password", false))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // Another call may have made it in between; otherwise Gitea refused for a real reason.
            if (!accountExists(sub)) {
                throw e;
            }
        }
    }

    /**
     * Copies a SourceRepository once into a private snapshot that StudentRepositories start from. A
     * template, because that is how Gitea makes a repository with the same files and a new history.
     *
     * @return the name of the snapshot
     * @throws RestClientException when Gitea cannot or will not copy it
     */
    public String importSnapshot(String sourceUrl) {
        var name = "snapshot-" + UUID.randomUUID();
        importHttp.post()
                .uri("/api/v1/repos/migrate")
                .body(Map.of("clone_addr", sourceUrl, "repo_name", name, "service", "git", "private", true))
                .retrieve()
                .toBodilessEntity();
        http.patch()
                .uri("/api/v1/repos/{owner}/{name}", serviceAccount(), name)
                .body(Map.of("template", true))
                .retrieve()
                .toBodilessEntity();
        removeTemplateInstructions(name);
        return name;
    }

    /**
     * Gitea would follow this file when making a repository from the snapshot, filling in the name of
     * each repository, so Students would start from different files.
     */
    private void removeTemplateInstructions(String snapshot) {
        File instructions;
        try {
            instructions = http.get()
                    .uri(
                            "/api/v1/repos/{owner}/{name}/contents/{path}",
                            serviceAccount(),
                            snapshot,
                            TEMPLATE_INSTRUCTIONS)
                    .retrieve()
                    .body(File.class);
        } catch (HttpClientErrorException.NotFound e) {
            return;
        }
        http.method(HttpMethod.DELETE)
                .uri(
                        "/api/v1/repos/{owner}/{name}/contents/{path}",
                        serviceAccount(),
                        snapshot,
                        TEMPLATE_INSTRUCTIONS)
                .body(Map.of("sha", instructions.sha()))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Makes a private repository from a snapshot, with a clean history. Spring's account owns it,
     * because an owner could do more than the write access a Student is meant to have.
     *
     * @return the address to clone it from over SSH
     * @throws RestClientException when Gitea cannot or will not make it
     */
    public String createFromSnapshot(String snapshot, String name) {
        var owner = serviceAccount();
        return http.post()
                .uri("/api/v1/repos/{owner}/{name}/generate", owner, snapshot)
                .body(Map.of("owner", owner, "name", name, "private", true, "git_content", true))
                .retrieve()
                .body(Repository.class)
                .sshUrl();
    }

    /** @throws RestClientException when Gitea cannot or will not give it */
    public void grantWrite(String repository, String account) {
        grant(repository, account, "write");
    }

    /** @throws RestClientException when Gitea cannot or will not give it */
    public void grantRead(String repository, String account) {
        grant(repository, account, "read");
    }

    private void grant(String repository, String account, String permission) {
        http.put()
                .uri(
                        "/api/v1/repos/{owner}/{name}/collaborators/{account}",
                        serviceAccount(),
                        repository,
                        account)
                .body(Map.of("permission", permission))
                .retrieve()
                .toBodilessEntity();
    }

    private String serviceAccount() {
        return http.get().uri("/api/v1/user").retrieve().body(Account.class).login();
    }

    private record Account(String login) {}

    private record File(String sha) {}

    private record Repository(@JsonProperty("ssh_url") String sshUrl) {}

    private boolean accountExists(String name) {
        try {
            http.get().uri("/api/v1/users/{name}", name).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    private static String randomPassword() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
