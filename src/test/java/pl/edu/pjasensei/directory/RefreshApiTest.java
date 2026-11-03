package pl.edu.pjasensei.directory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestCognito.Member;
import pl.edu.pjasensei.support.TestGitea;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
class RefreshApiTest {

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
    void addsAFirstTimeInstructorWithTheirEmailFromCognito() {
        int number = cognito.newGroupNumber();
        cognito.setGroup(number + "c");
        var sub = UUID.randomUUID().toString();
        var username = "instructor-" + sub;
        cognito.addUser(new Member(username, sub, "jan.kowalski@mail.com"));

        var token = instructorToken(sub, username, number + "w");

        refreshWith(token).expectStatus().isOk().expectBody().jsonPath("$.failures").isEmpty();

        assertThat(jdbc.sql("SELECT email FROM instructors WHERE sub = ?").param(sub).query(String.class).single())
                .isEqualTo("jan.kowalski@mail.com");
    }

    @Test
    void showsTheStudentsOfThePairedClassGroupWithTheirStudentNumbers() {
        int number = cognito.newGroupNumber();
        var first = newStudent();
        var second = newStudent();
        cognito.setGroup(number + "c", List.of(first, second));
        var token = instructorOf(number + "w");

        refreshWith(token).expectStatus().isOk();

        classGroupsWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.classGroups.length()").isEqualTo(1)
                .jsonPath("$.classGroups[0].name").isEqualTo(number + "c")
                .jsonPath("$.classGroups[0].students[?(@.sub == '%s')].studentNumber".formatted(first.sub()))
                .isEqualTo(List.of(first.username()))
                .jsonPath("$.classGroups[0].students[?(@.sub == '%s')].email".formatted(second.sub()))
                .isEqualTo(List.of(second.email()));
    }

    @Test
    void readsOnlyThePairedClassGroupAndShowsNoOther() {
        int taught = cognito.newGroupNumber();
        int other = cognito.newGroupNumber();
        var outsider = newStudent();
        cognito.setGroup(taught + "c");
        cognito.setGroup(other + "c", List.of(outsider));
        refreshWith(instructorOf(other + "w")).expectStatus().isOk();
        int timesOtherWasRead = Collections.frequency(cognito.requestedGroups(), other + "c");
        var token = instructorOf(taught + "w");

        refreshWith(token).expectStatus().isOk();

        assertThat(Collections.frequency(cognito.requestedGroups(), other + "c")).isEqualTo(timesOtherWasRead);
        assertThat(cognito.requestedGroups()).contains(taught + "c");
        classGroupsWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.classGroups[*].name").isEqualTo(List.of(taught + "c"))
                .jsonPath("$.classGroups[0].students").isEmpty();
    }

    @Test
    void readsEveryResultPage() {
        int number = cognito.newGroupNumber();
        var first = newStudent();
        var second = newStudent();
        var third = newStudent();
        cognito.setGroup(number + "c", List.of(first), List.of(second), List.of(third));
        var token = instructorOf(number + "w");

        refreshWith(token).expectStatus().isOk();

        assertThat(studentNumbersIn(token, 0))
                .containsExactlyInAnyOrder(first.username(), second.username(), third.username());
    }

    @Test
    void replacesTheMembershipOfARefreshedClassGroupOnly() {
        int number = cognito.newGroupNumber();
        int other = cognito.newGroupNumber();
        var leaver = newStudent();
        var stayer = newStudent();
        cognito.setGroup(number + "c", List.of(leaver, stayer));
        cognito.setGroup(other + "c", List.of(leaver));
        var token = instructorOf(number + "w");
        var otherToken = instructorOf(other + "w");
        refreshWith(token).expectStatus().isOk();
        refreshWith(otherToken).expectStatus().isOk();

        cognito.setGroup(number + "c", List.of(stayer));
        refreshWith(token).expectStatus().isOk();

        assertThat(studentNumbersIn(token, 0)).containsExactly(stayer.username());
        assertThat(studentNumbersIn(otherToken, 0)).containsExactly(leaver.username());
    }

    @Test
    void refusesAnyoneWithoutAnInstructorGroup() {
        refreshWith(identityProvider.token(claims -> claims.claim("cognito:groups", List.of("17c"))))
                .expectStatus().isForbidden();
        http.post().uri("/api/refresh").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void classGroupsAreForInstructorsTheDatabaseKnows() {
        classGroupsWith(identityProvider.token(claims -> claims.claim("cognito:groups", List.of("17w"))))
                .expectStatus().isForbidden();

        int number = cognito.newGroupNumber();
        var student = newStudent();
        cognito.setGroup(number + "c", List.of(student));
        refreshWith(instructorOf(number + "w")).expectStatus().isOk();
        classGroupsWith(identityProvider.token(claims -> claims
                        .subject(student.sub())
                        .claim("cognito:groups", List.of(number + "c"))))
                .expectStatus().isForbidden();
    }

    @Test
    void aClassGroupThatFailsPartWayKeepsItsPreviousMembersWhileOthersSucceed() {
        int failing = cognito.newGroupNumber();
        int working = cognito.newGroupNumber();
        var previous = newStudent();
        var joinedOnFirstPage = newStudent();
        var joinedOnSecondPage = newStudent();
        var other = newStudent();
        cognito.setGroup(failing + "c", List.of(previous));
        cognito.setGroup(working + "c", List.of());
        var token = instructorOf(failing + "w", working + "w");
        refreshWith(token).expectStatus().isOk();

        cognito.setGroup(failing + "c", List.of(joinedOnFirstPage), List.of(joinedOnSecondPage));
        cognito.failReading(failing + "c", 1);
        cognito.setGroup(working + "c", List.of(other));

        refreshWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.failures.length()").isEqualTo(1)
                .jsonPath("$.failures[0].classGroup").isEqualTo(failing + "c")
                .jsonPath("$.failures[0].person").doesNotExist()
                .jsonPath("$.failures[0].reason").isNotEmpty();

        assertThat(studentNumbersIn(token, 0)).containsExactly(previous.username());
        assertThat(studentNumbersIn(token, 1)).containsExactly(other.username());
    }

    @Test
    void aClassGroupTheIdentityProviderDoesNotHaveIsListed() {
        int number = cognito.newGroupNumber();
        var token = instructorOf(number + "w");

        refreshWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.failures[0].classGroup").isEqualTo(number + "c");

        classGroupsWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.classGroups[0].name").isEqualTo(number + "c")
                .jsonPath("$.classGroups[0].students").isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "no-at-sign", "@mail.com", "s12345@", "a@b@mail.com", "s 12345@mail.com", "s\t12345@mail.com", "s12345@mail.com\n"})
    void aPersonWithoutAUsableEmailIsSkippedAndListed(String email) {
        int number = cognito.newGroupNumber();
        var good = newStudent();
        var bad = new Member("bad-" + UUID.randomUUID(), UUID.randomUUID().toString(), email);
        cognito.setGroup(number + "c", List.of(bad, good));
        var token = instructorOf(number + "w");

        refreshWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.failures.length()").isEqualTo(1)
                .jsonPath("$.failures[0].classGroup").isEqualTo(number + "c")
                .jsonPath("$.failures[0].person").isEqualTo(bad.username())
                .jsonPath("$.failures[0].reason").isNotEmpty();

        assertThat(studentNumbersIn(token, 0)).containsExactly(good.username());
    }

    @Test
    void aStudentNumberAlreadyHeldBySomeoneElseIsListedWithoutLosingEitherPerson() {
        int number = cognito.newGroupNumber();
        var holder = newStudent();
        var clashing = new Member(
                "clash-" + UUID.randomUUID(), UUID.randomUUID().toString(), holder.username() + "@other.com");
        var other = newStudent();
        cognito.setGroup(number + "c", List.of(holder, clashing, other));
        var token = instructorOf(number + "w");

        refreshWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.failures.length()").isEqualTo(1)
                .jsonPath("$.failures[0].person").isEqualTo(clashing.username());

        assertThat(studentNumbersIn(token, 0)).containsExactlyInAnyOrder(holder.username(), other.username());
    }

    @Test
    void anInstructorWithoutAUsableEmailIsListedAndTheirClassGroupsAreStillRefreshed() {
        int number = cognito.newGroupNumber();
        var student = newStudent();
        cognito.setGroup(number + "c", List.of(student));
        var sub = UUID.randomUUID().toString();
        var username = "instructor-" + sub;
        cognito.addUser(new Member(username, sub, null));
        var token = instructorToken(sub, username, number + "w");

        refreshWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.failures.length()").isEqualTo(1)
                .jsonPath("$.failures[0].person").isEqualTo(username)
                .jsonPath("$.failures[0].classGroup").doesNotExist();

        // Not added, so the rule for people missing from the database still applies to them.
        classGroupsWith(token).expectStatus().isForbidden();
        assertThat(studentNumbersIn(instructorRefreshed(number + "w"), 0)).containsExactly(student.username());
    }

    @Test
    void anInstructorTheIdentityProviderDoesNotKnowIsListed() {
        int number = cognito.newGroupNumber();
        cognito.setGroup(number + "c");
        var username = "unknown-" + UUID.randomUUID();
        var token = identityProvider.token(claims -> claims
                .claim("username", username)
                .claim("cognito:groups", List.of(number + "w")));

        refreshWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.failures[0].person").isEqualTo(username);
    }

    @Test
    void createsTheMissingTechnicalGiteaAccountsOfStudentsAndTheInstructor() {
        int number = cognito.newGroupNumber();
        var student = newStudent();
        cognito.setGroup(number + "c", List.of(student));
        var instructorSub = UUID.randomUUID().toString();
        var username = "instructor-" + instructorSub;
        cognito.addUser(new Member(username, instructorSub, username + "@mail.com"));
        var token = instructorToken(instructorSub, username, number + "w");

        refreshWith(token).expectStatus().isOk().expectBody().jsonPath("$.failures").isEmpty();

        assertThat(gitea.accountExists(student.sub())).isTrue();
        assertThat(gitea.accountExists(instructorSub)).isTrue();
    }

    @Test
    void refreshingAgainIsSafeAndRestoresAnAccountThatWentMissing() {
        int number = cognito.newGroupNumber();
        var kept = newStudent();
        var lost = newStudent();
        cognito.setGroup(number + "c", List.of(kept, lost));
        var token = instructorOf(number + "w");
        refreshWith(token).expectBody().jsonPath("$.failures").isEmpty();

        gitea.deleteAccount(lost.sub());
        refreshWith(token).expectStatus().isOk().expectBody().jsonPath("$.failures").isEmpty();

        assertThat(gitea.accountExists(kept.sub())).isTrue();
        assertThat(gitea.accountExists(lost.sub())).isTrue();
    }

    @Test
    void anAccountThatCannotBeCreatedIsListedAndThePersonStaysInTheirClassGroup() {
        int number = cognito.newGroupNumber();
        var refused = new Member("refused-" + UUID.randomUUID(), "not a name Gitea accepts", "s" + number + "@mail.com");
        var other = newStudent();
        cognito.setGroup(number + "c", List.of(refused, other));
        var token = instructorOf(number + "w");

        refreshWith(token).expectStatus().isOk().expectBody()
                .jsonPath("$.failures.length()").isEqualTo(1)
                .jsonPath("$.failures[0].classGroup").isEqualTo(number + "c")
                .jsonPath("$.failures[0].person").isEqualTo(refused.username());

        assertThat(studentNumbersIn(token, 0)).containsExactlyInAnyOrder("s" + number, other.username());
        assertThat(gitea.accountExists(other.sub())).isTrue();
    }

    private String instructorRefreshed(String... groups) {
        var token = instructorOf(groups);
        refreshWith(token).expectStatus().isOk();
        return token;
    }

    private String instructorOf(String... groups) {
        var sub = UUID.randomUUID().toString();
        var username = "instructor-" + sub;
        cognito.addUser(new Member(username, sub, username + "@mail.com"));
        return instructorToken(sub, username, groups);
    }

    private String instructorToken(String sub, String username, String... groups) {
        return identityProvider.token(claims -> claims
                .subject(sub)
                .claim("username", username)
                .claim("cognito:groups", List.of(groups)));
    }

    /** A Student whose username is also their Student Number, as in s12345@mail.com. */
    private static Member newStudent() {
        var studentNumber = "s" + UUID.randomUUID().toString().replace("-", "");
        return new Member(studentNumber, UUID.randomUUID().toString(), studentNumber + "@mail.com");
    }

    private RestTestClient.ResponseSpec classGroupsWith(String token) {
        return http.get().uri("/api/class-groups").header("Authorization", "Bearer " + token).exchange();
    }

    private List<String> studentNumbersIn(String token, int classGroup) {
        return JsonPath.read(
                classGroupsWith(token).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody(),
                "$.classGroups[%d].students[*].studentNumber".formatted(classGroup));
    }

    private RestTestClient.ResponseSpec refreshWith(String token) {
        return http.post().uri("/api/refresh").header("Authorization", "Bearer " + token).exchange();
    }
}
