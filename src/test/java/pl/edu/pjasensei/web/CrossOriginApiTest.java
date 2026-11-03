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
        preflightFrom("https://plugin.example")
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://plugin.example");
    }

    @Test
    void refusesAnyOtherOrigin() {
        preflightFrom("https://elsewhere.example")
                .expectStatus().isForbidden();
    }

    private RestTestClient.ResponseSpec preflightFrom(String origin) {
        return http.options().uri("/api/status")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .exchange();
    }
}
