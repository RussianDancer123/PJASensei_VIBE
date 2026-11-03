package pl.edu.pjasensei.support;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

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

    private RestClient api() {
        return RestClient.builder()
                .baseUrl(container.apiUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + container.apiToken())
                .build();
    }
}
