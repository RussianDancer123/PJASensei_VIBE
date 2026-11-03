package pl.edu.pjasensei.gitea;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Spring's only way into Gitea, which nothing else may reach. */
@Component
@EnableConfigurationProperties(GiteaProperties.class)
public class GiteaClient {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient http;

    GiteaClient(RestClient.Builder builder, GiteaProperties properties) {
        this.http = builder
                .baseUrl(properties.url())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + properties.apiToken())
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
