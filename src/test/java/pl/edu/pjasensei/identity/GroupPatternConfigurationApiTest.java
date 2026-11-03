package pl.edu.pjasensei.identity;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
@TestPropertySource(properties = {
    "pjasensei.identity.instructor-group-pattern=teachers-[0-9]+",
    "pjasensei.identity.class-group-pattern=learners-[0-9]+"
})
class GroupPatternConfigurationApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Test
    void rolesFollowTheConfiguredPatterns() {
        var token = identityProvider.token(
                claims -> claims.claim("cognito:groups", List.of("teachers-1", "learners-2", "17w", "17c")));

        http.get().uri("/api/me").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.roles").isEqualTo(List.of("INSTRUCTOR", "STUDENT"));
    }

    @Test
    void theDefaultGroupsNoLongerGiveARole() {
        var token = identityProvider.token(claims -> claims.claim("cognito:groups", List.of("17w", "17c")));

        http.get().uri("/api/me").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.roles").isEmpty();
    }
}
