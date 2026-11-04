package pl.edu.pjasensei.sshkey;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestCognito.Member;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
@TestPropertySource(properties = "pjasensei.gitea.api-token=a-token-gitea-never-issued")
class SshKeysWithRejectedGiteaTokenApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    @Test
    void saysSoWhenGiteaCannotTakeTheKey() {
        var sub = UUID.randomUUID().toString();
        var username = "instructor-" + sub;
        cognito.addUser(new Member(username, sub, username + "@mail.com"));
        var token = identityProvider.token(claims -> claims
                .subject(sub)
                .claim("username", username)
                .claim("cognito:groups", List.of(cognito.newGroupNumber() + "w")));
        // Saves the Instructor, though it cannot make their account.
        http.post().uri("/api/refresh").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk();

        http.post().uri("/api/ssh-keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("publicKey", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ8WkzR8bZ0tq8m5fXvJ2Y7f0m6u3hTqYwTqvC1oN2pL"))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.detail").isNotEmpty();
    }
}
