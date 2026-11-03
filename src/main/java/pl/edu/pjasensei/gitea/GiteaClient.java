package pl.edu.pjasensei.gitea;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Spring's only way into Gitea, which nothing else may reach. */
@Component
@EnableConfigurationProperties(GiteaProperties.class)
public class GiteaClient {

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
}
