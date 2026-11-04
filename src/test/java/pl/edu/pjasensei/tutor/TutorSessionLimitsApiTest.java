package pl.edu.pjasensei.tutor;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.Classroom;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestGitea;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
@TestPropertySource(properties = {
    "pjasensei.assignments.allowed-source-hosts=source.test",
    "pjasensei.tutor.max-file-size=10B",
    "pjasensei.tutor.max-message-size=100B",
})
class TutorSessionLimitsApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    @Autowired
    TestGitea gitea;

    @Test
    void theLimitsComeFromConfiguration() {
        var classroom = new Classroom(http, identityProvider, cognito);
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int assignment = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));

        send(student.token(), assignment, withFile("x".repeat(10))).expectStatus().isOk();
        send(student.token(), assignment, withFile("x".repeat(11)))
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.detail").isEqualTo("A file may be at most 10B.");
        send(student.token(), assignment, "{\"content\":\"%s\"}".formatted("x".repeat(100)))
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.detail").isEqualTo("A message may be at most 100B.");
    }

    private RestTestClient.ResponseSpec send(String token, int assignment, String body) {
        return http.post().uri("/api/student/assignments/{id}/tutor-session/messages", assignment)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    private static String withFile(String content) {
        return "{\"content\":\"a\",\"files\":[{\"path\":\"b\",\"content\":\"%s\"}]}".formatted(content);
    }
}
