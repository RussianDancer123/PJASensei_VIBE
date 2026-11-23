package pl.edu.pjasensei.assignment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestCognito.Member;
import pl.edu.pjasensei.support.TestIdentityProvider;

/** Runs with the default allowed hosts, unlike {@link SendAssignmentApiTest}. */
@IntegrationTest
class SourceUrlApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    private String token;
    private String student;

    @BeforeEach
    void instructorWithAStudent() {
        int number = cognito.newGroupNumber();
        var studentNumber = "s" + UUID.randomUUID().toString().replace("-", "");
        student = UUID.randomUUID().toString();
        cognito.setGroup(number + "c", List.of(new Member(studentNumber, student, studentNumber + "@mail.com")));
        var sub = UUID.randomUUID().toString();
        var username = "instructor-" + sub;
        cognito.addUser(new Member(username, sub, username + "@mail.com"));
        token = identityProvider.token(claims -> claims
                .subject(sub)
                .claim("username", username)
                .claim("cognito:groups", List.of(number + "p")));
        http.post().uri("/api/refresh").header("Authorization", "Bearer " + token).exchange().expectStatus().isOk();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://github.com/owner/repo",
        "git://github.com/owner/repo.git",
        "ssh://git@github.com/owner/repo.git",
        "file:///etc/passwd",
        "git@github.com:owner/repo.git",
        "https://gitlab.com/owner/repo",
        "https://gitea:3000/owner/repo",
        "https://localhost/owner/repo",
        "https://169.254.169.254/latest/meta-data",
        "https://github.com.example.org/owner/repo",
        "https://github.com@example.org/owner/repo",
        "https://user:secret@github.com/owner/repo",
        "https://github.com:8443/owner/repo",
        "https://github.com/owner/repo?x=1",
        "https://github.com/owner/repo#readme",
        "https://github.com/",
        "https://github.com",
        "https://source.test/owner/repo",
        "not a url",
        ""
    })
    void refusesAnythingButAnHttpsUrlOnAnAllowedHost(String url) {
        createWith(url).expectStatus().isBadRequest().expectBody().jsonPath("$.detail").isNotEmpty();
    }

    @Test
    void refusesAMissingUrl() {
        http.post().uri("/api/assignments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("title", "Sorting", "recipients", List.of(student)))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** The test Gitea may not reach GitHub, so a failed copy shows the URL itself was accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"https://github.com/owner/repo", "https://GitHub.com/owner/repo.git"})
    void githubIsAllowedByDefault(String url) {
        createWith(url).expectStatus().isEqualTo(502);
    }

    private RestTestClient.ResponseSpec createWith(String url) {
        return http.post().uri("/api/assignments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("title", "Sorting", "sourceUrl", url, "recipients", List.of(student)))
                .exchange();
    }
}
