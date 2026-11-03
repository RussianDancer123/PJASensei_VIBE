package pl.edu.pjasensei.identity;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
class RolesApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Test
    void anInstructorGroupGivesInstructor() {
        rolesOf(List.of("17w")).jsonPath("$.roles").isEqualTo(List.of("INSTRUCTOR"));
    }

    @Test
    void aClassGroupGivesStudent() {
        rolesOf(List.of("17c")).jsonPath("$.roles").isEqualTo(List.of("STUDENT"));
    }

    @Test
    void oneOfEachGivesBothRoles() {
        rolesOf(List.of("18c", "17w")).jsonPath("$.roles").isEqualTo(List.of("INSTRUCTOR", "STUDENT"));
    }

    @Test
    void otherGroupsAreIgnored() {
        rolesOf(List.of("eu-central-1_abc123_Google", "17", "w", "17x", "x17w", "17wc", "17W"))
                .jsonPath("$.roles").isEmpty();
    }

    @Test
    void otherGroupsDoNotAffectTheOnesThatMatch() {
        rolesOf(List.of("eu-central-1_abc123_Google", "17c")).jsonPath("$.roles").isEqualTo(List.of("STUDENT"));
    }

    @Test
    void aTokenWithoutGroupsGivesNoRoles() {
        rolesWith(identityProvider.token()).jsonPath("$.roles").isEmpty();
    }

    private RestTestClient.BodyContentSpec rolesOf(List<String> groups) {
        return rolesWith(identityProvider.token(claims -> claims.claim("cognito:groups", groups)));
    }

    private RestTestClient.BodyContentSpec rolesWith(String token) {
        return http.get().uri("/api/me").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody();
    }
}
