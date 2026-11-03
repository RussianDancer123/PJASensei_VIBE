package pl.edu.pjasensei.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;

@IntegrationTest
@TestPropertySource(properties = "pjasensei.gitea.api-token=a-token-gitea-never-issued")
class StatusApiWithRejectedGiteaTokenTest {

    @Autowired
    RestTestClient http;

    @Test
    void reportsGiteaDownWhenItRefusesOurToken() {
        http.get().uri("/api/status").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo("DOWN")
                .jsonPath("$.database.status").isEqualTo("UP")
                .jsonPath("$.gitea.status").isEqualTo("DOWN");
    }
}
