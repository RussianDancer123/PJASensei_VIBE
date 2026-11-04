package pl.edu.pjasensei.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.Classroom;
import pl.edu.pjasensei.support.Classroom.Person;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestGitea;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
@TestPropertySource(properties = "pjasensei.assignments.allowed-source-hosts=source.test")
class StudentWorkApiTest {

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
    void aStudentListsOnlyAssignmentsWithTheirOwnStudentRepository() {
        var taught = classroom.newTaughtClassGroup(2);
        var student = taught.students().get(0);
        var other = taught.students().get(1);
        var url = gitea.newSourceRepository().url();
        int theirs = classroom.send(taught.instructor(), "Sorting", url, List.of(student));
        int alsoTheirs = classroom.send(taught.instructor(), "Graphs", url, List.of(student, other));
        classroom.send(taught.instructor(), "Someone else's", url, List.of(other));

        get(student.token(), "/api/student/assignments")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.assignments.length()").isEqualTo(2)
                .jsonPath("$.assignments[0].id").isEqualTo(alsoTheirs)
                .jsonPath("$.assignments[0].title").isEqualTo("Graphs")
                .jsonPath("$.assignments[1].id").isEqualTo(theirs)
                .jsonPath("$.assignments[1].title").isEqualTo("Sorting");
    }

    @Test
    void aRecipientWhoseStudentRepositoryIsNotMadeYetSeesNothing() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        http.post().uri("/api/assignments")
                .header("Authorization", "Bearer " + taught.instructor().token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "title", "Sorting",
                        "sourceUrl", gitea.newSourceRepository().url(),
                        "recipients", List.of(student.sub())))
                .exchange()
                .expectStatus().isCreated();

        get(student.token(), "/api/student/assignments")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.assignments").isEmpty();
    }

    @Test
    void thePreviewShowsTheTitleAndTheRawTextOfTheReadme() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var readme = "# Sortowanie\n\n*Zażółć* <b>gęślą</b> jaźń\n";
        var source = gitea.newSourceRepository();
        gitea.setFile(source, "README.md", readme);

        int id = classroom.send(taught.instructor(), "Sorting", source.url(), List.of(student));

        get(student.token(), "/api/student/assignments/" + id)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.title").isEqualTo("Sorting")
                .jsonPath("$.readme").isEqualTo(readme);
    }

    @Test
    void thePreviewFindsAReadmeWhateverItsCaseOrExtension() {
        for (var name : List.of("README", "readme.txt", "Readme.rst")) {
            var taught = classroom.newTaughtClassGroup(1);
            var student = taught.students().getFirst();
            var source = gitea.newSourceRepository();
            gitea.deleteFile(source, "README.md");
            gitea.addFile(source, name, "Read " + name);

            int id = classroom.send(taught.instructor(), "Sorting", source.url(), List.of(student));

            get(student.token(), "/api/student/assignments/" + id)
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.readme").isEqualTo("Read " + name);
        }
    }

    @Test
    void aMissingReadmeDoesNotBreakThePreview() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var source = gitea.newSourceRepository();
        gitea.deleteFile(source, "README.md");
        gitea.addFile(source, "README-not-really", "Not a README");

        int id = classroom.send(taught.instructor(), "Sorting", source.url(), List.of(student));

        get(student.token(), "/api/student/assignments/" + id)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Sorting")
                .jsonPath("$.readme").isEmpty();
    }

    @Test
    void aHugeReadmeIsNotLoadedIntoThePreview() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var source = gitea.newSourceRepository();
        gitea.setFile(source, "README.md", "x".repeat(1024 * 1024 + 1));

        int id = classroom.send(taught.instructor(), "Sorting", source.url(), List.of(student));

        get(student.token(), "/api/student/assignments/" + id)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Sorting")
                .jsonPath("$.readme").isEmpty();
    }

    @Test
    void getReturnsTheSshAddressOfTheirOwnStudentRepository() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));

        var address = gitea.repository("assignment-%d-%s".formatted(id, student.username()))
                .path("ssh_url")
                .asString();
        assertThat(address).isNotBlank();
        get(student.token(), "/api/student/assignments/%d/ssh-address".formatted(id))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.sshUrl").isEqualTo(address);
    }

    @Test
    void aStudentCannotReachTheAssignmentOfAnotherStudent() {
        var taught = classroom.newTaughtClassGroup(2);
        var student = taught.students().get(0);
        Person other = taught.students().get(1);
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));

        get(other.token(), "/api/student/assignments/" + id).expectStatus().isNotFound();
        get(other.token(), "/api/student/assignments/%d/ssh-address".formatted(id)).expectStatus().isNotFound();
        get(student.token(), "/api/student/assignments/" + Integer.MAX_VALUE).expectStatus().isNotFound();
    }

    @Test
    void theseEndpointsAreForStudentsTheDatabaseKnows() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));
        var unknownStudent = identityProvider.token(claims -> claims.claim("cognito:groups", List.of("17c")));
        // Saved and with work, but the token no longer has a ClassGroup in it.
        var noLongerAStudent = identityProvider.token(claims -> claims.subject(student.sub()));

        for (var path : List.of(
                "/api/student/assignments",
                "/api/student/assignments/" + id,
                "/api/student/assignments/%d/ssh-address".formatted(id))) {
            get(taught.instructor().token(), path).expectStatus().isForbidden();
            get(unknownStudent, path).expectStatus().isForbidden();
            get(noLongerAStudent, path).expectStatus().isForbidden();
            http.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
    }

    private RestTestClient.ResponseSpec get(String token, String path) {
        return http.get().uri(path).header("Authorization", "Bearer " + token).exchange();
    }
}
