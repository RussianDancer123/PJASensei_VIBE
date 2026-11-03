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
    "pjasensei.identity.instructor-group-suffix=x",
    "pjasensei.identity.class-group-suffix=y"
})
class GroupSuffixConfigurationApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Test
    void rolesFollowTheConfiguredSuffixes() {
        rolesOf(List.of("17x", "18y")).jsonPath("$.roles").isEqualTo(List.of("INSTRUCTOR", "STUDENT"));
    }

    @Test
    void theDefaultSuffixesNoLongerGiveARole() {
        rolesOf(List.of("17w", "17c")).jsonPath("$.roles").isEmpty();
    }

    private RestTestClient.BodyContentSpec rolesOf(List<String> groups) {
        var token = identityProvider.token(claims -> claims.claim("cognito:groups", groups));

        return http.get().uri("/api/me").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody();
    }
}
