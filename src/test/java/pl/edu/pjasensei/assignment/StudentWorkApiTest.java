package pl.edu.pjasensei.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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
    void aFileOnlyNamedLikeAReadmeIsNotOne() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var source = gitea.newSourceRepository();
        gitea.deleteFile(source, "README.md");
        gitea.addFile(source, "README.png", "a picture");
        gitea.addFile(source, "README.txt", "The text");

        int id = classroom.send(taught.instructor(), "Sorting", source.url(), List.of(student));

        get(student.token(), "/api/student/assignments/" + id)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.readme").isEqualTo("The text");
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

        var address = gitea.repository(Classroom.studentRepositoryName(id, student))
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
                "/api/student/assignments?repository=git@h:o/n.git",
                "/api/student/assignments/" + id,
                "/api/student/assignments/%d/ssh-address".formatted(id))) {
            get(taught.instructor().token(), path).expectStatus().isForbidden();
            get(unknownStudent, path).expectStatus().isForbidden();
            get(noLongerAStudent, path).expectStatus().isForbidden();
            http.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void theRepositoryFilterMatchesBothFormsOfAGitRemoteAddress() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var url = gitea.newSourceRepository().url();
        classroom.send(taught.instructor(), "Other", url, List.of(student));
        int id = classroom.send(taught.instructor(), "Sorting", url, List.of(student));
        var path = ownerAndName(id, student);

        for (var remote : List.of(
                "ssh://git@gitea.test:2222/" + path + ".git",
                "ssh://git@gitea.test:2222/" + path,
                "git@gitea.test:" + path + ".git",
                "git@gitea.test:" + path,
                "ssh://someone@alias/" + path.toUpperCase() + ".GIT",
                "git@other-host:" + path.toUpperCase())) {
            assertOnly(student, id, remote);
        }
    }

    @Test
    void severalRepositoryValuesGiveTheAssignmentsMatchingAnyOfThem() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var url = gitea.newSourceRepository().url();
        int first = classroom.send(taught.instructor(), "Sorting", url, List.of(student));
        int second = classroom.send(taught.instructor(), "Graphs", url, List.of(student));
        classroom.send(taught.instructor(), "Unasked", url, List.of(student));

        withRepositories(student, "git@elsewhere.test:nobody/nothing.git",
                        "git@h:" + ownerAndName(first, student),
                        "git@h:" + ownerAndName(second, student))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.assignments.length()").isEqualTo(2)
                .jsonPath("$.assignments[0].id").isEqualTo(second)
                .jsonPath("$.assignments[1].id").isEqualTo(first);
    }

    @Test
    void anotherStudentsForeignOrUnparseableAddressGivesAnEmptyListNotAnError() {
        var taught = classroom.newTaughtClassGroup(2);
        var student = taught.students().get(0);
        var other = taught.students().get(1);
        var url = gitea.newSourceRepository().url();
        classroom.send(taught.instructor(), "Mine", url, List.of(student));
        int theirs = classroom.send(taught.instructor(), "Theirs", url, List.of(other));

        for (var remote : List.of(
                "git@gitea.test:" + ownerAndName(theirs, other),
                "git@github.com:someone/else.git",
                "not an address",
                "ssh://",
                "git@host:",
                "git@host:one/two/three",
                "https://",
                "")) {
            withRepositories(student, remote)
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.assignments").isEmpty();
        }
    }

    private void assertOnly(Person student, int id, String remote) {
        withRepositories(student, remote)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.assignments.length()").isEqualTo(1)
                .jsonPath("$.assignments[0].id").isEqualTo(id);
    }

    /** The {@code owner/name} of a StudentRepository, as Gitea says it. */
    private String ownerAndName(int assignment, Person student) {
        var address = gitea.repository(Classroom.studentRepositoryName(assignment, student))
                .path("ssh_url")
                .asString();
        var path = address.startsWith("ssh://")
                ? address.substring(address.indexOf('/', "ssh://".length()) + 1)
                : address.substring(address.indexOf(':') + 1);
        return path.replaceFirst("[.]git$", "");
    }

    /** Asks with a {@code repository} parameter per remote, as the plugin does. */
    private RestTestClient.ResponseSpec withRepositories(Person student, String... remotes) {
        return http.get()
                .uri(builder -> {
                    builder.path("/api/student/assignments");
                    var values = new HashMap<String, Object>();
                    for (int i = 0; i < remotes.length; i++) {
                        builder.queryParam("repository", "{remote" + i + "}");
                        values.put("remote" + i, remotes[i]);
                    }
                    return builder.build(values);
                })
                .header("Authorization", "Bearer " + student.token())
                .exchange();
    }

    @Test
    void aStudentSetsTheFinishedMarkReadsItBackClearsItAndSetsItAgain() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));
        var preview = "/api/student/assignments/" + id;

        get(student.token(), preview).expectStatus().isOk().expectBody().jsonPath("$.finished").isEqualTo(false);
        for (boolean finished : List.of(true, true, false, false, true)) {
            putFinished(student.token(), id, finished).expectStatus().isNoContent();
            get(student.token(), preview)
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.finished").isEqualTo(finished);
        }
    }

    @Test
    void aStudentCannotSetTheFinishedMarkOfAnotherStudentOrOfAnAssignmentTheyDidNotReceive() {
        var taught = classroom.newTaughtClassGroup(2);
        var student = taught.students().get(0);
        var other = taught.students().get(1);
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));

        putFinished(other.token(), id, true).expectStatus().isNotFound();
        putFinished(student.token(), Integer.MAX_VALUE, true).expectStatus().isNotFound();
        get(student.token(), "/api/student/assignments/" + id)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.finished").isEqualTo(false);
    }

    @Test
    void anInstructorAndAnUnknownPersonCannotSetTheFinishedMark() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));
        var unknownStudent = identityProvider.token(claims -> claims.claim("cognito:groups", List.of("17c")));

        putFinished(taught.instructor().token(), id, true).expectStatus().isForbidden();
        putFinished(unknownStudent, id, true).expectStatus().isForbidden();
        http.put().uri("/api/student/assignments/%d/finished".formatted(id))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("finished", true))
                .exchange().expectStatus().isUnauthorized();
        get(student.token(), "/api/student/assignments/" + id)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.finished").isEqualTo(false);
    }

    @Test
    void theFinishedMarkNeedsATrueOrFalse() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));

        for (Object body : List.of(Map.of(), Map.of("finished", "yes"))) {
            http.put().uri("/api/student/assignments/%d/finished".formatted(id))
                    .header("Authorization", "Bearer " + student.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange().expectStatus().isBadRequest();
        }
    }

    @Test
    void aRefreshAndAChangeOfClassGroupKeepTheFinishedMark() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));
        putFinished(student.token(), id, true).expectStatus().isNoContent();

        // The Student leaves the ClassGroup altogether.
        cognito.setGroup(taught.classGroup());
        http.post().uri("/api/refresh").header("Authorization", "Bearer " + taught.instructor().token())
                .exchange().expectStatus().isOk();

        get(student.token(), "/api/student/assignments/" + id)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.finished").isEqualTo(true);
        get(taught.instructor().token(), "/api/assignments/" + id)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.recipients[0].finished").isEqualTo(true);
    }

    private RestTestClient.ResponseSpec putFinished(String token, int assignment, boolean finished) {
        return http.put().uri("/api/student/assignments/%d/finished".formatted(assignment))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("finished", finished))
                .exchange();
    }

    private RestTestClient.ResponseSpec get(String token, String path) {
        return http.get().uri(path).header("Authorization", "Bearer " + token).exchange();
    }
}
