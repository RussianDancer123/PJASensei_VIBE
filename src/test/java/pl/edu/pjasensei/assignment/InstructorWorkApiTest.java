package pl.edu.pjasensei.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
@TestPropertySource(properties = "pjasensei.assignments.allowed-source-hosts=source.test")
class InstructorWorkApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    @Autowired
    TestGitea gitea;

    Classroom classroom;

    @BeforeEach
    void setUp() {
        classroom = new Classroom(http, identityProvider, cognito);
    }

    @Test
    void anInstructorListsOnlyTheirOwnAssignmentsNewestFirst() {
        var taught = classroom.newTaughtClassGroup(1);
        var rival = classroom.newTaughtClassGroup(1);
        var url = gitea.newSourceRepository().url();
        int first = classroom.send(taught.instructor(), "Sorting", url, taught.students());
        int second = classroom.send(taught.instructor(), "Graphs", url, taught.students());
        classroom.send(rival.instructor(), "Someone else's", url, rival.students());

        get(taught.instructor().token(), "/api/assignments")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.assignments.length()").isEqualTo(2)
                .jsonPath("$.assignments[0].id").isEqualTo(second)
                .jsonPath("$.assignments[0].title").isEqualTo("Graphs")
                .jsonPath("$.assignments[1].id").isEqualTo(first)
                .jsonPath("$.assignments[1].title").isEqualTo("Sorting");
    }

    @Test
    void anAssignmentShowsEachRecipientWithTheirSshAddress() {
        var taught = classroom.newTaughtClassGroup(2);
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), taught.students());

        var body = get(taught.instructor().token(), "/api/assignments/" + id)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.title").isEqualTo("Sorting")
                .jsonPath("$.recipients.length()").isEqualTo(2);
        for (var student : taught.students()) {
            var address = gitea.repository(Classroom.studentRepositoryName(id, student))
                    .path("ssh_url")
                    .asString();
            assertThat(address).isNotBlank();
            body.jsonPath("$.recipients[?(@.sub == '%s')].sshUrl".formatted(student.sub()))
                    .isEqualTo(address)
                    .jsonPath("$.recipients[?(@.sub == '%s')].studentNumber".formatted(student.sub()))
                    .isEqualTo(student.username());
        }
    }

    @Test
    void aRecipientWithoutAStudentRepositoryHasNoAddress() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = JsonPath.read(
                http.post().uri("/api/assignments")
                        .header("Authorization", "Bearer " + taught.instructor().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "title", "Sorting",
                                "sourceUrl", gitea.newSourceRepository().url(),
                                "recipients", List.of(student.sub())))
                        .exchange()
                        .expectStatus().isCreated()
                        .expectBody(String.class).returnResult().getResponseBody(),
                "$.id");

        get(taught.instructor().token(), "/api/assignments/" + id)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.recipients.length()").isEqualTo(1)
                .jsonPath("$.recipients[0].studentNumber").isEqualTo(student.username())
                .jsonPath("$.recipients[0].sshUrl").isEmpty()
                // Only who they are and the address: no pulled, submitted or done statuses.
                .jsonPath("$.recipients[0].length()").isEqualTo(3);
    }

    @Test
    void anInstructorCannotOpenTheAssignmentOfAnotherInstructor() {
        var taught = classroom.newTaughtClassGroup(1);
        var rival = classroom.newTaughtClassGroup(1);
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), taught.students());

        get(rival.instructor().token(), "/api/assignments/" + id).expectStatus().isNotFound();
        get(taught.instructor().token(), "/api/assignments/" + Integer.MAX_VALUE).expectStatus().isNotFound();
    }

    @Test
    void theseEndpointsAreForInstructorsTheDatabaseKnows() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));
        var unknownInstructor = identityProvider.token(claims -> claims.claim("cognito:groups", List.of("17w")));
        // Saved and with work, but the token no longer has an Instructor group in it.
        var noLongerAnInstructor = identityProvider.token(claims -> claims.subject(taught.instructor().sub()));

        for (var path : List.of("/api/assignments", "/api/assignments/" + id)) {
            get(student.token(), path).expectStatus().isForbidden();
            get(unknownInstructor, path).expectStatus().isForbidden();
            get(noLongerAnInstructor, path).expectStatus().isForbidden();
            http.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
    }

    private RestTestClient.ResponseSpec get(String token, String path) {
        return http.get().uri(path).header("Authorization", "Bearer " + token).exchange();
    }
}
