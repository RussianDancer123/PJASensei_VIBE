package pl.edu.pjasensei.identity;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestIdentityProvider;

/**
 * The rule covers every path under /api unless it is exempted, so this asks for one no controller
 * maps: a user the database knows gets past the rule and finds nothing there.
 */
@IntegrationTest
class KnownUserRuleApiTest {

    private static final String ANY_LATER_ENDPOINT = "/api/any-later-endpoint";

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    JdbcClient jdbc;

    @Test
    void refusesAValidTokenOfAUserMissingFromTheDatabase() {
        getWith(identityProvider.token()).expectStatus().isForbidden();
    }

    @Test
    void aMissingUserHoldingARoleIsStillRefused() {
        var token = identityProvider.token(claims -> claims.claim("cognito:groups", List.of("17w", "17c")));

        getWith(token).expectStatus().isForbidden();
    }

    @Test
    void letsAStudentInTheDatabasePass() {
        var sub = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO students (sub, email, student_number) VALUES (?, ?, ?)")
                .params(sub, sub + "@mail.com", sub)
                .update();

        getWith(identityProvider.token(claims -> claims.subject(sub))).expectStatus().isNotFound();
    }

    @Test
    void letsAnInstructorInTheDatabasePass() {
        var sub = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO instructors (sub, email) VALUES (?, ?)")
                .params(sub, sub + "@mail.com")
                .update();

        getWith(identityProvider.token(claims -> claims.subject(sub))).expectStatus().isNotFound();
    }

    @Test
    void aRequestWithoutATokenIsUnauthenticatedRatherThanForbidden() {
        http.get().uri(ANY_LATER_ENDPOINT).exchange().expectStatus().isUnauthorized();
    }

    private RestTestClient.ResponseSpec getWith(String token) {
        return http.get().uri(ANY_LATER_ENDPOINT).header("Authorization", "Bearer " + token).exchange();
    }
}
