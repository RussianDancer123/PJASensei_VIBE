package pl.edu.pjasensei.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
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

    @Autowired
    TestCognito cognito;

    @Test
    void rolesFollowTheConfiguredSuffixes() {
        rolesOf(List.of("17x", "18y")).jsonPath("$.roles").isEqualTo(List.of("INSTRUCTOR", "STUDENT"));
    }

    @Test
    void theDefaultSuffixesNoLongerGiveARole() {
        rolesOf(List.of("17w", "17c")).jsonPath("$.roles").isEmpty();
    }

    @Test
    void refreshReadsTheClassGroupPairedByTheConfiguredSuffixes() {
        int number = cognito.newGroupNumber();
        cognito.setGroup(number + "y");
        var token = identityProvider.token(claims -> claims.claim("cognito:groups", List.of(number + "x")));

        http.post().uri("/api/refresh").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk();

        assertThat(cognito.requestedGroups()).contains(number + "y");
    }

    private RestTestClient.BodyContentSpec rolesOf(List<String> groups) {
        var token = identityProvider.token(claims -> claims.claim("cognito:groups", groups));

        return http.get().uri("/api/me").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody();
    }
}
