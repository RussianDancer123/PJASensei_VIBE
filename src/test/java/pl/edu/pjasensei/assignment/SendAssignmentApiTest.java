package pl.edu.pjasensei.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestCognito.Member;
import pl.edu.pjasensei.support.TestGitea;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
@TestPropertySource(properties = "pjasensei.assignments.allowed-source-hosts=source.test")
class SendAssignmentApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    @Autowired
    TestGitea gitea;

    @Autowired
    JdbcClient jdbc;

    @Test
    void eachRecipientGetsAPrivateCopyOfTheSourceWithACleanHistory() {
        var taught = newTaughtClassGroup(2);
        var source = gitea.newSourceRepository();

        var id = created(taught.instructor(), request(source.url(), taught.studentSubs()));
        repositoriesWith(taught.instructor().token(), id).expectStatus().isNoContent();

        for (var student : taught.students()) {
            var name = repositoryName(id, student);
            assertThat(gitea.repository(name).path("private").asBoolean()).isTrue();
            assertThat(gitea.fileExists(name, "first.txt")).isTrue();
            assertThat(gitea.commitCount(name)).isEqualTo(1);
        }
    }

    @Test
    void theStudentCanWriteAndTheSendingInstructorCanOnlyRead() {
        var taught = newTaughtClassGroup(2);
        var chosen = taught.students().get(0);
        var notChosen = taught.students().get(1);
        var id = sent(taught.instructor(), gitea.newSourceRepository().url(), List.of(chosen.sub()));

        var name = repositoryName(id, chosen);
        assertThat(gitea.collaborators(name)).containsExactlyInAnyOrder(chosen.sub(), taught.instructor().sub());
        assertThat(gitea.permission(name, chosen.sub())).isEqualTo("write");
        assertThat(gitea.permission(name, taught.instructor().sub())).isEqualTo("read");
        assertThat(gitea.permission(name, notChosen.sub())).isEqualTo("none");
        assertThat(gitea.repositoryExists(repositoryName(id, notChosen))).isFalse();
    }

    @Test
    void remembersTheSshAddressOfEachStudentRepository() {
        var taught = newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var id = sent(taught.instructor(), gitea.newSourceRepository().url(), taught.studentSubs());

        assertThat(sshAddressesOf(id)).containsExactly(
                gitea.repository(repositoryName(id, student)).path("ssh_url").asString());
    }

    @Test
    void theSnapshotIsPrivateAndOnlySpringCanReachIt() {
        var taught = newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var id = sent(taught.instructor(), gitea.newSourceRepository().url(), taught.studentSubs());

        var snapshot = snapshotOf(id);
        assertThat(gitea.repository(snapshot).path("private").asBoolean()).isTrue();
        assertThat(gitea.permission(snapshot, student.sub())).isEqualTo("none");
        assertThat(gitea.permission(snapshot, taught.instructor().sub())).isEqualTo("none");
    }

    @Test
    void changesToTheSourceAfterTheCopyReachNobody() {
        var taught = newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var source = gitea.newSourceRepository();
        var id = created(taught.instructor(), request(source.url(), taught.studentSubs()));

        gitea.addFile(source, "between-the-requests.txt", "too late");
        repositoriesWith(taught.instructor().token(), id).expectStatus().isNoContent();
        gitea.addFile(source, "after-the-requests.txt", "too late");

        var name = repositoryName(id, student);
        assertThat(gitea.fileExists(name, "first.txt")).isTrue();
        assertThat(gitea.fileExists(name, "between-the-requests.txt")).isFalse();
        assertThat(gitea.fileExists(name, "after-the-requests.txt")).isFalse();
    }

    @Test
    void acceptsStudentsFromDifferentTaughtClassGroupsAndRemovesDuplicates() {
        int first = cognito.newGroupNumber();
        int second = cognito.newGroupNumber();
        var inBoth = newStudent();
        var inSecond = newStudent();
        cognito.setGroup(first + "c", List.of(inBoth));
        cognito.setGroup(second + "c", List.of(inBoth, inSecond));
        var instructor = newInstructor(first + "w", second + "w");

        var id = sent(
                instructor,
                gitea.newSourceRepository().url(),
                List.of(inBoth.sub(), inSecond.sub(), inBoth.sub()));

        assertThat(sshAddressesOf(id)).hasSize(2);
        assertThat(gitea.repositoryExists(repositoryName(id, inBoth))).isTrue();
        assertThat(gitea.repositoryExists(repositoryName(id, inSecond))).isTrue();
    }

    @Test
    void refusesAStudentOutsideTheClassGroupsOfTheInstructor() {
        var taught = newTaughtClassGroup(1);
        var other = newTaughtClassGroup(1);
        var source = gitea.newSourceRepository();

        createWith(
                        taught.instructor().token(),
                        request(source.url(), List.of(taught.studentSubs().getFirst(), other.studentSubs().getFirst())))
                .expectStatus().isForbidden();

        assertThat(assignmentsFrom(source.url())).isZero();
    }

    @Test
    void refusesAStudentWhoHasLeftTheClassGroup() {
        var taught = newTaughtClassGroup(2);
        var leaver = taught.students().get(0);
        cognito.setGroup(taught.classGroup(), List.of(taught.students().get(1)));
        refreshWith(taught.instructor().token()).expectStatus().isOk();

        createWith(
                        taught.instructor().token(),
                        request(gitea.newSourceRepository().url(), List.of(leaver.sub())))
                .expectStatus().isForbidden();
    }

    @Test
    void refusesARecipientNobodyKnowsTheSameWayAsAnOutsider() {
        var taught = newTaughtClassGroup(1);

        createWith(
                        taught.instructor().token(),
                        request(gitea.newSourceRepository().url(), List.of(UUID.randomUUID().toString())))
                .expectStatus().isForbidden();
    }

    @Test
    void aFailedImportReturnsAnErrorAtOnceAndKeepsNoAssignment() {
        var taught = newTaughtClassGroup(1);
        var source = new TestGitea.SourceRepository("nobody", "missing-" + UUID.randomUUID());

        createWith(taught.instructor().token(), request(source.url(), taught.studentSubs()))
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.detail").isNotEmpty();

        assertThat(assignmentsFrom(source.url())).isZero();
    }

    @Test
    void refusesAMissingOrOverlongTitle() {
        var taught = newTaughtClassGroup(1);
        var url = gitea.newSourceRepository().url();

        for (var title : List.of("", "   ", "x".repeat(201))) {
            createWith(
                            taught.instructor().token(),
                            Map.of("title", title, "sourceUrl", url, "recipients", taught.studentSubs()))
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.detail").isNotEmpty();
        }
        createWith(taught.instructor().token(), Map.of("sourceUrl", url, "recipients", taught.studentSubs()))
                .expectStatus().isBadRequest();
        assertThat(assignmentsFrom(url)).isZero();
    }

    @Test
    void refusesAnAssignmentWithoutRecipients() {
        var taught = newTaughtClassGroup(1);
        var url = gitea.newSourceRepository().url();

        createWith(taught.instructor().token(), request(url, List.of())).expectStatus().isBadRequest();
        createWith(taught.instructor().token(), Map.of("title", "Sorting", "sourceUrl", url))
                .expectStatus().isBadRequest();
    }

    @Test
    void onlyAnInstructorTheDatabaseKnowsMaySend() {
        var taught = newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var url = gitea.newSourceRepository().url();
        var id = created(taught.instructor(), request(url, taught.studentSubs()));

        var studentToken = identityProvider.token(claims -> claims
                .subject(student.sub())
                .claim("cognito:groups", List.of(taught.classGroup())));
        var unknownInstructorToken = identityProvider.token(claims -> claims.claim("cognito:groups", List.of("17w")));
        for (var token : List.of(studentToken, unknownInstructorToken)) {
            createWith(token, request(url, taught.studentSubs())).expectStatus().isForbidden();
            repositoriesWith(token, id).expectStatus().isForbidden();
        }
        http.post().uri("/api/assignments").contentType(MediaType.APPLICATION_JSON).body(request(url, List.of()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void aStudentWithAnInstructorGroupButNoInstructorRowIsRefusedBeforeAnyCopy() {
        var taught = newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var token = identityProvider.token(claims -> claims
                .subject(student.sub())
                .claim("cognito:groups", List.of(taught.classGroup().replace("c", "w"))));
        var url = gitea.newSourceRepository().url();

        createWith(token, request(url, taught.studentSubs())).expectStatus().isForbidden();

        assertThat(assignmentsFrom(url)).isZero();
    }

    @Test
    void onlyTheSendingInstructorMayCreateTheRepositories() {
        var taught = newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var id = created(taught.instructor(), request(gitea.newSourceRepository().url(), taught.studentSubs()));
        // Teaches the same ClassGroup, and still did not send this Assignment.
        var colleague = newInstructor(taught.classGroup().replace("c", "w"));

        repositoriesWith(colleague.token(), id).expectStatus().isNotFound();
        repositoriesWith(colleague.token(), Integer.MAX_VALUE).expectStatus().isNotFound();

        assertThat(gitea.repositoryExists(repositoryName(id, student))).isFalse();
    }

    @Test
    void refreshLeavesAssignmentsAndStudentRepositoriesAloneEvenWhenAStudentHasLeft() {
        var taught = newTaughtClassGroup(2);
        var leaver = taught.students().get(0);
        var stayer = taught.students().get(1);
        var id = sent(taught.instructor(), gitea.newSourceRepository().url(), taught.studentSubs());
        var addresses = sshAddressesOf(id);

        cognito.setGroup(taught.classGroup(), List.of(stayer));
        refreshWith(taught.instructor().token())
                .expectStatus().isOk()
                .expectBody().jsonPath("$.failures").isEmpty();

        assertThat(jdbc.sql("SELECT student_sub FROM assignment_recipients WHERE assignment_id = ?")
                        .param(id)
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder(leaver.sub(), stayer.sub());
        assertThat(sshAddressesOf(id)).isEqualTo(addresses);
        assertThat(gitea.fileExists(repositoryName(id, leaver), "first.txt")).isTrue();
        assertThat(gitea.permission(repositoryName(id, leaver), leaver.sub())).isEqualTo("write");
    }

    private static String repositoryName(int assignment, Member student) {
        return "assignment-%d-%s".formatted(assignment, student.username());
    }

    private List<String> sshAddressesOf(int assignment) {
        return jdbc.sql("SELECT ssh_url FROM student_repositories WHERE assignment_id = ? ORDER BY ssh_url")
                .param(assignment)
                .query(String.class)
                .list();
    }

    private String snapshotOf(int assignment) {
        return jdbc.sql("SELECT snapshot_repository FROM assignments WHERE id = ?")
                .param(assignment)
                .query(String.class)
                .single();
    }

    private int assignmentsFrom(String sourceUrl) {
        return jdbc.sql("SELECT count(*) FROM assignments WHERE source_url = ?")
                .param(sourceUrl)
                .query(Integer.class)
                .single();
    }

    private int sent(Instructor instructor, String sourceUrl, List<String> recipients) {
        var id = created(instructor, request(sourceUrl, recipients));
        repositoriesWith(instructor.token(), id).expectStatus().isNoContent();
        return id;
    }

    private int created(Instructor instructor, Map<String, Object> request) {
        return JsonPath.read(
                createWith(instructor.token(), request)
                        .expectStatus().isCreated()
                        .expectBody(String.class).returnResult().getResponseBody(),
                "$.id");
    }

    private RestTestClient.ResponseSpec createWith(String token, Map<String, Object> request) {
        return http.post().uri("/api/assignments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
    }

    private RestTestClient.ResponseSpec repositoriesWith(String token, int assignment) {
        return http.post().uri("/api/assignments/{id}/repositories", assignment)
                .header("Authorization", "Bearer " + token)
                .exchange();
    }

    private RestTestClient.ResponseSpec refreshWith(String token) {
        return http.post().uri("/api/refresh").header("Authorization", "Bearer " + token).exchange();
    }

    private static Map<String, Object> request(String sourceUrl, List<String> recipients) {
        return Map.of("title", "Sorting", "sourceUrl", sourceUrl, "recipients", recipients);
    }

    /** A ClassGroup with its Students, and an Instructor of it who has run Refresh. */
    private TaughtClassGroup newTaughtClassGroup(int students) {
        int number = cognito.newGroupNumber();
        var members = Stream.generate(SendAssignmentApiTest::newStudent).limit(students).toList();
        cognito.setGroup(number + "c", members);
        return new TaughtClassGroup(number + "c", newInstructor(number + "w"), members);
    }

    private Instructor newInstructor(String... groups) {
        var sub = UUID.randomUUID().toString();
        var username = "instructor-" + sub;
        cognito.addUser(new Member(username, sub, username + "@mail.com"));
        var token = identityProvider.token(claims -> claims
                .subject(sub)
                .claim("username", username)
                .claim("cognito:groups", List.of(groups)));
        refreshWith(token).expectStatus().isOk().expectBody().jsonPath("$.failures").isEmpty();
        return new Instructor(token, sub);
    }

    /** A Student whose username is also their Student Number, as in s12345@mail.com. */
    private static Member newStudent() {
        var studentNumber = "s" + UUID.randomUUID().toString().replace("-", "");
        return new Member(studentNumber, UUID.randomUUID().toString(), studentNumber + "@mail.com");
    }

    private record Instructor(String token, String sub) {}

    private record TaughtClassGroup(String classGroup, Instructor instructor, List<Member> students) {

        List<String> studentSubs() {
            return students.stream().map(Member::sub).toList();
        }
    }
}
