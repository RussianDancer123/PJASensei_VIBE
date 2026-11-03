package pl.edu.pjasensei.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;

@IntegrationTest
class StatusApiTest {

    @Autowired
    RestTestClient http;

    @Test
    void reportsUpWithMigratedDatabaseAndReachableGitea() {
        http.get().uri("/api/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.database.status").isEqualTo("UP")
                .jsonPath("$.database.schemaVersion").isNotEmpty()
                .jsonPath("$.gitea.status").isEqualTo("UP");
    }
}
