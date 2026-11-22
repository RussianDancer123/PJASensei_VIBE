package pl.edu.pjasensei.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.AbstractStringAssert;
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
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
class DisplayNameApiTest {

    @Autowired
    RestTestClient http;

    @Autowired
    TestIdentityProvider identityProvider;

    @Autowired
    TestCognito cognito;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aStudentGetsTheLocalPartOfTheirStoredEmail() {
        var sub = newSub();
        saveStudent(sub, "anna.nowak@mail.com", "s" + sub);

        displayNameOf(token(sub, "opaque-" + sub, "17c")).isEqualTo("anna.nowak");
    }

    @Test
    void anInstructorGetsTheLocalPartOfTheirStoredEmail() {
        var sub = newSub();
        saveInstructor(sub, "jan.kowalski@mail.com");

        displayNameOf(token(sub, "opaque-" + sub, "17w")).isEqualTo("jan.kowalski");
    }

    @Test
    void aPersonWithBothRolesGetsOneDisplayName() {
        var sub = newSub();
        saveStudent(sub, "ewa.kowalska@mail.com", "s" + sub);
        saveInstructor(sub, "ewa.kowalska@mail.com");

        displayNameOf(token(sub, "opaque-" + sub, "17c", "17w")).isEqualTo("ewa.kowalska");
    }

    @Test
    void theStoredEmailWinsOverWhatTheIdentityProviderSaysNow() {
        var sub = newSub();
        var username = "opaque-" + sub;
        saveInstructor(sub, "old.name@mail.com");
        cognito.addUser(new Member(username, sub, "new.name@mail.com"));

        displayNameOf(token(sub, username, "17w")).isEqualTo("old.name");
    }

    @Test
    void anInstructorNotYetInTheDatabaseGetsItFromTheIdentityProvider() {
        var sub = newSub();
        var username = "opaque-" + sub;
        cognito.addUser(new Member(username, sub, "piotr.zielinski@mail.com"));

        displayNameOf(token(sub, username, "17w")).isEqualTo("piotr.zielinski");
    }

    @Test
    void theNameIsTheUsernameWhenTheIdentityProviderFails() {
        var sub = newSub();
        var username = "unknown-to-the-identity-provider-" + sub;

        displayNameOf(token(sub, username, "17w")).isEqualTo(username);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "no-at-sign", "@mail.com", "jan@", "two words@mail.com"})
    void theNameIsTheUsernameWhenThereIsNoUsableEmail(String email) {
        var sub = newSub();
        var username = "opaque-" + sub;
        cognito.addUser(new Member(username, sub, email));

        displayNameOf(token(sub, username, "17w")).isEqualTo(username);
    }

    @Test
    void aTokenWithoutAUsernameStillAnswers() {
        var sub = newSub();
        var token = identityProvider.token(claims -> claims.subject(sub).claim("username", null));

        http.get().uri("/api/me").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk();
    }

    @Test
    void theRestOfTheAnswerStays() {
        var sub = newSub();
        saveInstructor(sub, "jan.kowalski@mail.com");

        http.get().uri("/api/me").header("Authorization", "Bearer " + token(sub, "the-username", "17w")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sub").isEqualTo(sub)
                .jsonPath("$.username").isEqualTo("the-username")
                .jsonPath("$.roles").isEqualTo(List.of("INSTRUCTOR"));
    }

    @Test
    void asksForNoDatabaseRowAndAddsNone() {
        var sub = newSub();
        var username = "opaque-" + sub;
        cognito.addUser(new Member(username, sub, "piotr.zielinski@mail.com"));

        displayNameOf(token(sub, username, "17w")).isEqualTo("piotr.zielinski");

        var rows = jdbc.sql("""
                        SELECT (SELECT count(*) FROM students WHERE sub = :sub)
                             + (SELECT count(*) FROM instructors WHERE sub = :sub)
                        """)
                .param("sub", sub)
                .query(Long.class)
                .single();
        assertThat(rows).isZero();
    }

    private AbstractStringAssert<?> displayNameOf(String token) {
        var body = http.get().uri("/api/me").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        return assertThat((String) JsonPath.read(body, "$.displayName"));
    }

    private String token(String sub, String username, String... groups) {
        return identityProvider.token(claims -> claims
                .subject(sub)
                .claim("username", username)
                .claim("cognito:groups", List.of(groups)));
    }

    private void saveStudent(String sub, String email, String studentNumber) {
        jdbc.sql("INSERT INTO students (sub, email, student_number) VALUES (?, ?, ?)")
                .params(sub, email, studentNumber)
                .update();
    }

    private void saveInstructor(String sub, String email) {
        jdbc.sql("INSERT INTO instructors (sub, email) VALUES (?, ?)").params(sub, email).update();
    }

    private static String newSub() {
        return UUID.randomUUID().toString();
    }
}
