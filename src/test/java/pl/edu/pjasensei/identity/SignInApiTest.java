package pl.edu.pjasensei.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
class SignInApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Test
    void showsWhoAValidTokenBelongsToEvenBeforeTheyAreInTheDatabase() {
        var token = identityProvider.token(claims -> claims.subject("the-sub").claim("username", "s12345"));

        me(token)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sub").isEqualTo("the-sub")
                .jsonPath("$.username").isEqualTo("s12345");
    }

    @Test
    void refusesARequestWithoutAToken() {
        http.get().uri("/api/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void refusesAnExpiredToken() {
        // Further back than the minute of clock skew that validation tolerates.
        var expired = Date.from(Instant.now().minus(Duration.ofMinutes(2)));

        me(identityProvider.token(claims -> claims.expirationTime(expired))).expectStatus().isUnauthorized();
    }

    @Test
    void refusesATokenThatNeverExpires() {
        me(identityProvider.token(claims -> claims.expirationTime(null))).expectStatus().isUnauthorized();
    }

    @Test
    void refusesATokenFromAnotherIssuer() {
        me(identityProvider.token(claims -> claims.issuer("https://elsewhere.invalid/pool")))
                .expectStatus().isUnauthorized();
    }

    @Test
    void refusesATokenForAnotherClient() {
        me(identityProvider.token(claims -> claims.claim("client_id", "another-client")))
                .expectStatus().isUnauthorized();
    }

    @Test
    void refusesATokenWithoutAClient() {
        me(identityProvider.token(claims -> claims.claim("client_id", null))).expectStatus().isUnauthorized();
    }

    @Test
    void refusesATokenSignedWithAnotherKey() {
        me(identityProvider.tokenSignedWithAnotherKey()).expectStatus().isUnauthorized();
    }

    private RestTestClient.ResponseSpec me(String token) {
        return http.get().uri("/api/me").header("Authorization", "Bearer " + token).exchange();
    }
}
