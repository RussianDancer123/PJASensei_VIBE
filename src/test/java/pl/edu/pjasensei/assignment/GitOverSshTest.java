package pl.edu.pjasensei.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.Classroom;
import pl.edu.pjasensei.support.Classroom.Person;
import pl.edu.pjasensei.support.GitUser;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestGitea;
import pl.edu.pjasensei.support.TestIdentityProvider;

/** The real git and ssh programs, with keys registered and addresses given through Spring. */
@IntegrationTest
@TestPropertySource(properties = "pjasensei.assignments.allowed-source-hosts=source.test")
class GitOverSshTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    @Autowired
    TestGitea gitea;

    @TempDir
    Path computers;

    Classroom classroom;

    @BeforeEach
    void setUp() {
        classroom = new Classroom(http, identityProvider, cognito);
    }

    @Test
    void aStudentClonesAndPushesTheirOwnStudentRepositoryWithARegisteredKey() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));
        var computer = registeredComputerOf(student);

        var cloned = computer.gitClone(sshAddress(student, id), "work");
        assertThat(cloned.succeeded()).as(cloned.toString()).isTrue();
        assertThat(computers.resolve(student.sub()).resolve("work/first.txt")).exists();

        var pushed = computer.pushNewFile("work", "solution.txt");
        assertThat(pushed.succeeded()).as(pushed.toString()).isTrue();
        assertThat(gitea.fileExists(Classroom.studentRepositoryName(id, student), "solution.txt"))
                .isTrue();
    }

    @Test
    void aKeyThatWasNeverRegisteredReachesNothing() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));

        var cloned = computerOf(student).gitClone(sshAddress(student, id), "work");

        assertThat(cloned.succeeded()).as(cloned.toString()).isFalse();
        assertThat(cloned.output()).contains("Permission denied");
    }

    @Test
    void aStudentCannotReadOrPushToTheStudentRepositoryOfAnotherStudent() {
        var taught = classroom.newTaughtClassGroup(2);
        var owner = taught.students().get(0);
        var other = taught.students().get(1);
        var url = gitea.newSourceRepository().url();
        int ownersOnly = classroom.send(taught.instructor(), "Sorting", url, List.of(owner));
        int othersOwn = classroom.send(taught.instructor(), "Graphs", url, List.of(other));
        var ownersAddress = sshAddress(owner, ownersOnly);
        var othersComputer = registeredComputerOf(other);
        // The same key and connection do work for their own StudentRepository.
        var clonedOwn = othersComputer.gitClone(sshAddress(other, othersOwn), "their-own");
        assertThat(clonedOwn.succeeded()).as(clonedOwn.toString()).isTrue();
        var pushedOwn = othersComputer.pushNewFile("their-own", "written-by-the-other-student.txt");
        assertThat(pushedOwn.succeeded()).as(pushedOwn.toString()).isTrue();

        var cloned = othersComputer.gitClone(ownersAddress, "stolen");
        assertThat(cloned.succeeded()).as(cloned.toString()).isFalse();
        assertThat(cloned.output()).contains("is not authorized to read");

        var pushed = othersComputer.pushTo("their-own", ownersAddress);
        assertThat(pushed.succeeded()).as(pushed.toString()).isFalse();
        assertThat(pushed.output()).contains("is not authorized to write");
        var ownersRepository = Classroom.studentRepositoryName(ownersOnly, owner);
        assertThat(gitea.fileExists(ownersRepository, "written-by-the-other-student.txt")).isFalse();
    }

    @Test
    void anInstructorClonesButCannotPushToTheStudentRepositoriesOfTheirAssignment() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int id = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));
        var computer = registeredComputerOf(taught.instructor());
        var address = instructorSshAddress(taught.instructor(), id, student);

        var cloned = computer.gitClone(address, "review");
        assertThat(cloned.succeeded()).as(cloned.toString()).isTrue();
        assertThat(computers.resolve(taught.instructor().sub()).resolve("review/first.txt")).exists();

        var pushed = computer.pushNewFile("review", "instructor.txt");
        assertThat(pushed.succeeded()).as(pushed.toString()).isFalse();
        // A reader of the repository gets this from Gitea's hook, not the "not authorized" of a stranger.
        assertThat(pushed.output()).contains("permission denied for writing");
        assertThat(gitea.fileExists(Classroom.studentRepositoryName(id, student), "instructor.txt"))
                .isFalse();
    }

    @Test
    void anInstructorCannotCloneTheStudentRepositoriesOfAssignmentsOfAnotherInstructor() {
        var taught = classroom.newTaughtClassGroup(1);
        var rival = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        var url = gitea.newSourceRepository().url();
        int id = classroom.send(taught.instructor(), "Sorting", url, List.of(student));
        classroom.send(rival.instructor(), "Graphs", url, rival.students());
        var address = instructorSshAddress(taught.instructor(), id, student);
        var rivalsComputer = registeredComputerOf(rival.instructor());

        var cloned = rivalsComputer.gitClone(address, "stolen");

        assertThat(cloned.succeeded()).as(cloned.toString()).isFalse();
        assertThat(cloned.output()).contains("is not authorized to read");
    }

    private String instructorSshAddress(Person instructor, int assignment, Person student) {
        List<String> addresses = JsonPath.read(
                http.get().uri("/api/assignments/{id}", assignment)
                        .header("Authorization", "Bearer " + instructor.token())
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(String.class).returnResult().getResponseBody(),
                "$.recipients[?(@.sub == '%s')].sshUrl".formatted(student.sub()));
        return addresses.getFirst();
    }

    private String sshAddress(Person student, int assignment) {
        return JsonPath.read(
                http.get().uri("/api/student/assignments/{id}/ssh-address", assignment)
                        .header("Authorization", "Bearer " + student.token())
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(String.class).returnResult().getResponseBody(),
                "$.sshUrl");
    }

    private GitUser registeredComputerOf(Person person) {
        var computer = computerOf(person);
        http.post().uri("/api/ssh-keys")
                .header("Authorization", "Bearer " + person.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("publicKey", computer.publicKey()))
                .exchange()
                .expectStatus().isNoContent();
        return computer;
    }

    private GitUser computerOf(Person person) {
        try {
            return gitea.newGitUser(Files.createDirectory(computers.resolve(person.sub())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
