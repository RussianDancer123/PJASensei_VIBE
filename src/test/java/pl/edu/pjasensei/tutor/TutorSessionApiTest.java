package pl.edu.pjasensei.tutor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.Classroom;
import pl.edu.pjasensei.support.Classroom.Person;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestAi;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestGitea;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
@TestPropertySource(properties = "pjasensei.assignments.allowed-source-hosts=source.test")
class TutorSessionApiTest {

    private static final int MEGABYTE = 1024 * 1024;

    @LocalServerPort
    int port;

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    @Autowired
    TestGitea gitea;

    @Autowired
    TestAi ai;

    Classroom classroom;

    @BeforeEach
    void setUp() {
        classroom = new Classroom(http, identityProvider, cognito);
    }

    @AfterEach
    void tearDown() {
        ai.behaveNormally();
    }

    @Test
    void aStudentSendsAMessageAndSeesItInTheHistoryWhenTheyReturn() {
        var work = newWork();

        send(work, "{\"content\":\"hello\"}")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reply").isEqualTo("olleh");

        history(work)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.messages.length()").isEqualTo(2)
                .jsonPath("$.messages[0].role").isEqualTo("student")
                .jsonPath("$.messages[0].content").isEqualTo("hello")
                .jsonPath("$.messages[1].role").isEqualTo("tutor")
                .jsonPath("$.messages[1].content").isEqualTo("olleh");
    }

    @Test
    void theSecondMessageContinuesTheSameTutorSession() {
        var work = newWork();
        int before = ai.sessionsCreated();

        send(work, "{\"content\":\"one\"}").expectStatus().isOk();
        send(work, "{\"content\":\"two\"}").expectStatus().isOk();

        assertThat(ai.sessionsCreated() - before).isEqualTo(1);
        history(work).expectBody().jsonPath("$.messages.length()").isEqualTo(4);
    }

    @Test
    void eachStudentRepositoryHasItsOwnTutorSession() {
        var first = newWork();
        var second = newWork();

        send(first, "{\"content\":\"mine\"}").expectStatus().isOk();

        history(second).expectStatus().isOk().expectBody().jsonPath("$.messages").isEmpty();
    }

    @Test
    void simultaneousFirstRequestsGiveOneTutorSession() throws Exception {
        var work = newWork();
        int before = ai.sessionsCreated();
        // Long enough for every request to find no session before the first one has made its own.
        ai.delaySessionCreation(Duration.ofMillis(500));
        int requests = 8;

        try (var threads = Executors.newFixedThreadPool(requests)) {
            var start = new CountDownLatch(requests);
            var sent = new ArrayList<Future<?>>();
            for (int i = 0; i < requests; i++) {
                int number = i;
                sent.add(threads.submit(() -> {
                    start.countDown();
                    start.await();
                    send(work, "{\"content\":\"message %d\"}".formatted(number)).expectStatus().isOk();
                    return null;
                }));
            }
            for (var result : sent) {
                result.get();
            }
        }

        assertThat(ai.sessionsCreated() - before).isEqualTo(1);
        history(work).expectBody().jsonPath("$.messages.length()").isEqualTo(requests * 2);
    }

    @Test
    void aStudentCannotUseTheTutorSessionOfAnotherStudent() {
        var taught = classroom.newTaughtClassGroup(2);
        var owner = taught.students().get(0);
        var other = taught.students().get(1);
        int assignment = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(owner));
        int requestsBefore = ai.requests();

        send(other.token(), assignment, "{\"content\":\"hello\"}").expectStatus().isNotFound();
        history(other.token(), assignment).expectStatus().isNotFound();

        assertThat(ai.requests()).isEqualTo(requestsBefore);
    }

    @Test
    void anInstructorCannotUseATutorSession() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int assignment = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));

        send(taught.instructor().token(), assignment, "{\"content\":\"hello\"}").expectStatus().isForbidden();
        history(taught.instructor().token(), assignment).expectStatus().isForbidden();
    }

    @Test
    void aStudentMissingFromTheDatabaseCannotUseATutorSession() {
        var work = newWork();
        var token = classroom.tokenWith("nobody-" + UUID.randomUUID(), "17c");

        send(token, work.assignment(), "{\"content\":\"hello\"}").expectStatus().isForbidden();
    }

    @Test
    void aMessageToAnUnknownTutorSessionGoesToANewOneInstead() {
        var work = newWork();
        send(work, "{\"content\":\"lost\"}").expectStatus().isOk();
        int before = ai.sessionsCreated();
        ai.forgetSessions();

        send(work, "{\"content\":\"hello\"}")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reply").isEqualTo("olleh");

        assertThat(ai.sessionsCreated() - before).isEqualTo(1);
        history(work).expectBody()
                .jsonPath("$.messages.length()").isEqualTo(2)
                .jsonPath("$.messages[0].content").isEqualTo("hello");
        assertThat(ai.sessionsCreated() - before).as("the new session is the stored one").isEqualTo(1);
    }

    @Test
    void theHistoryOfAnUnknownTutorSessionIsThatOfANewOne() {
        var work = newWork();
        send(work, "{\"content\":\"lost\"}").expectStatus().isOk();
        ai.forgetSessions();

        history(work).expectStatus().isOk().expectBody().jsonPath("$.messages").isEmpty();
    }

    @Test
    void anUnknownTutorSessionIsRetriedOnlyOnce() {
        var work = newWork();
        send(work, "{\"content\":\"first\"}").expectStatus().isOk();
        ai.forgetEverySessionAtOnce();
        int requestsBefore = ai.requests();

        send(work, "{\"content\":\"hello\"}").expectStatus().isEqualTo(502);

        // The message, a new session, and the message again.
        assertThat(ai.requests() - requestsBefore).isEqualTo(3);
    }

    @Test
    void simultaneousRequestsOnAnUnknownTutorSessionGiveOneNewOne() throws Exception {
        var work = newWork();
        send(work, "{\"content\":\"lost\"}").expectStatus().isOk();
        ai.forgetSessions();
        int before = ai.sessionsCreated();
        ai.delaySessionCreation(Duration.ofMillis(500));
        int requests = 4;

        try (var threads = Executors.newFixedThreadPool(requests)) {
            var start = new CountDownLatch(requests);
            var sent = new ArrayList<Future<?>>();
            for (int i = 0; i < requests; i++) {
                sent.add(threads.submit(() -> {
                    start.countDown();
                    start.await();
                    send(work, "{\"content\":\"hello\"}").expectStatus().isOk();
                    return null;
                }));
            }
            for (var result : sent) {
                result.get();
            }
        }

        assertThat(ai.sessionsCreated() - before).isEqualTo(1);
    }

    @Test
    void theAiGetsTheTextOfAMessageAsItsQuestionAndTheRestUnchangedButFiles() {
        var work = newWork();

        send(work, "{\"extra\":[1,2.5],\"content\":\"zażółć\",\"files\":[]}").expectStatus().isOk();

        var message = ai.lastMessage();
        assertThat(message.get("question").asString()).isEqualTo("zażółć");
        assertThat(message.has("content")).isFalse();
        assertThat(message.get("extra").toString()).isEqualTo("[1,2.5]");
        assertThat(message.has("files")).isFalse();
        assertThat(message.has("code_context")).isFalse();
    }

    @Test
    void theAnswerOfTheAiComesBackAsTheReplyWithItsOtherFields() {
        var work = newWork();
        ai.answerMessagesWith("{\"answer\":\"hi\",\"prompt_score\":7}");

        send(work, "{\"content\":\"hello\"}")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reply").isEqualTo("hi")
                .jsonPath("$.answer").doesNotExist()
                .jsonPath("$.prompt_score").isEqualTo(7);
    }

    @Test
    void theAiGetsTheFirstFileAsTheCodeAndTheOthersByName() {
        var work = newWork();

        send(work, """
                {"content":"look","files":[
                  {"path":"src/A.java","content":"class A {}"},
                  {"path":"src/B.java","content":"class B {}"},
                  {"path":"README.md","content":"# readme"}]}
                """).expectStatus().isOk();

        var message = ai.lastMessage();
        assertThat(message.has("files")).isFalse();
        assertThat(message.get("code_context").toString()).isEqualTo(
                "{\"current_file_name\":\"src/A.java\",\"current_code\":\"class A {}\","
                        + "\"open_files\":[\"src/B.java\",\"README.md\"]}");
    }

    @Test
    void aFileOfExactlyTwoMegabytesIsAccepted() {
        var work = newWork();

        send(work, messageWithFiles("x".repeat(2 * MEGABYTE))).expectStatus().isOk();
    }

    @Test
    void aFileOverTwoMegabytesIsRefusedAndNeverReachesTheAi() {
        var work = newWork();
        int requestsBefore = ai.requests();

        send(work, messageWithFiles("x".repeat(2 * MEGABYTE + 1)))
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.detail").isEqualTo("A file may be at most 2MB.");

        assertThat(ai.requests()).isEqualTo(requestsBefore);
    }

    @Test
    void theSizeOfAFileIsCountedInBytesNotCharacters() {
        var work = newWork();

        send(work, messageWithFiles("ą".repeat(MEGABYTE + 1))).expectStatus().isEqualTo(413);
    }

    @Test
    void aMessageOfExactlyTenMegabytesIsAccepted() {
        var work = newWork();
        var message = messageWithFiles("x".repeat(MEGABYTE), "x".repeat(MEGABYTE));

        send(work, paddedTo(10 * MEGABYTE, message)).expectStatus().isOk();
    }

    @Test
    void aMessageOverTenMegabytesIsRefusedAndNeverReachesTheAi() {
        var work = newWork();
        var message = messageWithFiles("x".repeat(MEGABYTE), "x".repeat(MEGABYTE));
        int requestsBefore = ai.requests();

        send(work, paddedTo(10 * MEGABYTE + 1, message))
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.detail").isEqualTo("A message may be at most 10MB.");

        assertThat(ai.requests()).isEqualTo(requestsBefore);
    }

    @Test
    void aMessageFarOverTheLimitStillGetsAClearAnswer() {
        var work = newWork();

        send(work, "x".repeat(40 * MEGABYTE))
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.detail").isEqualTo("A message may be at most 10MB.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "[]",
        "{}",
        "{\"content\":1}",
        "{\"content\":\"a\"} trailing",
        "{\"content\":\"a\",\"files\":null}",
        "{\"content\":\"a\",\"files\":{}}",
        "{\"content\":\"a\",\"files\":[\"b\"]}",
        "{\"content\":\"a\",\"files\":[{\"content\":\"b\"}]}",
        "{\"content\":\"a\",\"files\":[{\"path\":\"b\"}]}",
        "{\"content\":\"a\",\"files\":[{\"path\":\"b\",\"content\":1}]}",
    })
    void aMessageOfTheWrongShapeIsRefusedAndNeverReachesTheAi(String body) {
        var work = newWork();
        int requestsBefore = ai.requests();

        send(work, body).expectStatus().isBadRequest();

        assertThat(ai.requests()).isEqualTo(requestsBefore);
    }

    @Test
    void anAiThatAnswersWithAnErrorGivesAClearErrorAndAssignmentsStillWork() {
        var work = newWork();
        ai.failWith(503);

        send(work, "{\"content\":\"hello\"}")
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.detail")
                .isEqualTo("The tutor cannot answer right now. Your Assignments are not affected.");
        history(work).expectStatus().isEqualTo(502);

        assignmentsStillWorkFor(work);
    }

    @Test
    void anAiThatCannotBeReachedGivesAClearErrorAndAssignmentsStillWork() {
        var work = newWork();
        send(work, "{\"content\":\"hello\"}").expectStatus().isOk();
        ai.dropConnections();

        send(work, "{\"content\":\"hello\"}").expectStatus().isEqualTo(502);
        history(work).expectStatus().isEqualTo(502);

        assignmentsStillWorkFor(work);
    }

    @Test
    void anAiThatAnswersNonsenseGivesAnError() {
        var work = newWork();
        ai.answerSessionCreationWith("{\"other\":\"field\"}");
        send(work, "{\"content\":\"hello\"}").expectStatus().isEqualTo(502);
        ai.behaveNormally();
        ai.answerSessionCreationWith("not json");
        send(work, "{\"content\":\"hello\"}").expectStatus().isEqualTo(502);
        ai.behaveNormally();

        send(work, "{\"content\":\"made\"}").expectStatus().isOk();
        ai.answerMessagesWith("not json");
        send(work, "{\"content\":\"hello\"}").expectStatus().isEqualTo(502);
    }

    @Test
    void aTutorSessionThatCouldNotBeMadeIsMadeOnTheNextTry() {
        var work = newWork();
        ai.failWith(503);
        send(work, "{\"content\":\"hello\"}").expectStatus().isEqualTo(502);
        ai.behaveNormally();

        send(work, "{\"content\":\"hello\"}")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reply").isEqualTo("olleh");
    }

    @Test
    void anAiAnswerOfTheWrongShapeGivesAnError() {
        var work = newWork();
        send(work, "{\"content\":\"made\"}").expectStatus().isOk();

        ai.answerMessagesWith("{\"answer\":1}");
        send(work, "{\"content\":\"hello\"}").expectStatus().isEqualTo(502);

        ai.answerHistoryWith("{\"messages\":\"none\"}");
        history(work).expectStatus().isEqualTo(502);
    }

    @Test
    void anAiThatHangsOnNewTutorSessionsDoesNotHoldUpAssignments() throws Exception {
        // More Students than the database connections Spring has, by default ten.
        var taught = classroom.newTaughtClassGroup(12);
        int assignment = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), taught.students());
        int before = ai.sessionsCreated();
        ai.delaySessionCreation(Duration.ofSeconds(6));
        var messages = "/api/student/assignments/%d/tutor-session/messages".formatted(assignment);

        // Not RestTestClient, which keeps only five connections and would be waiting for itself here.
        try (var direct = HttpClient.newHttpClient();
                var threads = Executors.newFixedThreadPool(taught.students().size())) {
            for (var student : taught.students()) {
                threads.submit(() -> direct.send(request(student.token(), messages), BodyHandlers.discarding()));
            }
            var giveUp = Instant.now().plusSeconds(5);
            while (ai.sessionsCreated() - before < 5 && Instant.now().isBefore(giveUp)) {
                Thread.sleep(50);
            }
            Thread.sleep(300);

            var started = Instant.now();
            var assignments = direct.send(
                    request(taught.students().getFirst().token(), "/api/student/assignments"),
                    BodyHandlers.discarding());

            assertThat(assignments.statusCode()).isEqualTo(200);
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(3));
        }
    }

    private HttpRequest request(String token, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("Authorization", "Bearer " + token)
                .build();
    }

    private void assignmentsStillWorkFor(Work work) {
        http.get().uri("/api/student/assignments")
                .header("Authorization", "Bearer " + work.student().token())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.assignments[0].id").isEqualTo(work.assignment());
        http.get().uri("/api/student/assignments/{id}/ssh-address", work.assignment())
                .header("Authorization", "Bearer " + work.student().token())
                .exchange()
                .expectStatus().isOk();
    }

    private static String messageWithFiles(String... contents) {
        var files = Arrays.stream(contents)
                .map(content -> "{\"path\":\"a.txt\",\"content\":\"%s\"}".formatted(content))
                .collect(Collectors.joining(","));
        return "{\"content\":\"look\",\"files\":[%s]}".formatted(files);
    }

    /** Spaces are valid JSON, so this makes a message exactly this long without any file being too big. */
    private static String paddedTo(int bytes, String message) {
        return message + " ".repeat(bytes - message.getBytes(StandardCharsets.UTF_8).length);
    }

    private Work newWork() {
        var taught = classroom.newTaughtClassGroup(1);
        var student = taught.students().getFirst();
        int assignment = classroom.send(
                taught.instructor(), "Sorting", gitea.newSourceRepository().url(), List.of(student));
        return new Work(student, assignment);
    }

    private RestTestClient.ResponseSpec send(Work work, String body) {
        return send(work.student().token(), work.assignment(), body);
    }

    private RestTestClient.ResponseSpec send(String token, int assignment, String body) {
        return http.post().uri("/api/student/assignments/{id}/tutor-session/messages", assignment)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    private RestTestClient.ResponseSpec history(Work work) {
        return history(work.student().token(), work.assignment());
    }

    private RestTestClient.ResponseSpec history(String token, int assignment) {
        return http.get().uri("/api/student/assignments/{id}/tutor-session/messages", assignment)
                .header("Authorization", "Bearer " + token)
                .exchange();
    }

    private record Work(Person student, int assignment) {}
}
