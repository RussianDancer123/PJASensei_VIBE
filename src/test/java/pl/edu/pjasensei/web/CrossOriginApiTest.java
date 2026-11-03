package pl.edu.pjasensei.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;

@IntegrationTest
@TestPropertySource(properties = "pjasensei.cors.allowed-origins=https://plugin.example")
class CrossOriginApiTest {

    @Autowired
    RestTestClient http;

    @Test
    void allowsAConfiguredOrigin() {
        preflightFrom("https://plugin.example", "/api/status")
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://plugin.example");
    }

    @Test
    void refusesAnyOtherOrigin() {
        preflightFrom("https://elsewhere.example", "/api/status")
                .expectStatus().isForbidden();
    }

    /** A preflight never carries a token, so it must be answered before authentication is asked for. */
    @Test
    void allowsAConfiguredOriginToSendATokenToAProtectedEndpoint() {
        preflightFrom("https://plugin.example", "/api/me")
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://plugin.example")
                .expectHeader().value("Access-Control-Allow-Headers", allowed -> allowed.equalsIgnoreCase("authorization"));
    }

    @Test
    void refusesAnyOtherOriginOnAProtectedEndpoint() {
        preflightFrom("https://elsewhere.example", "/api/me")
                .expectStatus().isForbidden();
    }

    /** Without the header a browser hides the 401 from the plugin, which then cannot tell why. */
    @Test
    void aConfiguredOriginCanReadARefusalOfAMissingToken() {
        http.get().uri("/api/me").header("Origin", "https://plugin.example").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://plugin.example");
    }

    private RestTestClient.ResponseSpec preflightFrom(String origin, String path) {
        return http.options().uri(path)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange();
    }
}
