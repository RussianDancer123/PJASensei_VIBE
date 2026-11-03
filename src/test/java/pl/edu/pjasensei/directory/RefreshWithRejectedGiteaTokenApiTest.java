package pl.edu.pjasensei.directory;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestCognito.Member;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
@TestPropertySource(properties = "pjasensei.gitea.api-token=a-token-gitea-never-issued")
class RefreshWithRejectedGiteaTokenApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    @Test
    void peopleAreKeptAndListedWhenGiteaRefusesToMakeTheirAccounts() {
        int number = cognito.newGroupNumber();
        var studentNumber = "s" + UUID.randomUUID().toString().replace("-", "");
        var student = new Member(studentNumber, UUID.randomUUID().toString(), studentNumber + "@mail.com");
        cognito.setGroup(number + "c", List.of(student));
        var sub = UUID.randomUUID().toString();
        var username = "instructor-" + sub;
        cognito.addUser(new Member(username, sub, username + "@mail.com"));
        var token = identityProvider.token(claims -> claims
                .subject(sub)
                .claim("username", username)
                .claim("cognito:groups", List.of(number + "w")));

        http.post().uri("/api/refresh").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.failures.length()").isEqualTo(2)
                .jsonPath("$.failures[?(@.classGroup == '%dc')].person".formatted(number))
                .isEqualTo(List.of(studentNumber))
                .jsonPath("$.failures[?(!@.classGroup)].person").isEqualTo(List.of(username));

        http.get().uri("/api/class-groups").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.classGroups[0].students[0].studentNumber").isEqualTo(studentNumber);
    }
}
