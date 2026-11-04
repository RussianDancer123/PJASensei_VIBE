package pl.edu.pjasensei.sshkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.Classroom;
import pl.edu.pjasensei.support.IntegrationTest;
import pl.edu.pjasensei.support.TestCognito;
import pl.edu.pjasensei.support.TestGitea;
import pl.edu.pjasensei.support.TestIdentityProvider;

@IntegrationTest
class SshKeysApiTest {

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
    void aStudentCanRegisterSeveralKeys() {
        var student = classroom.newTaughtClassGroup(1).students().getFirst();
        var keyOfFirstComputer = newPublicKey();
        var keyOfSecondComputer = newPublicKey();

        register(student.token(), keyOfFirstComputer).expectStatus().isNoContent();
        register(student.token(), keyOfSecondComputer).expectStatus().isNoContent();

        assertThat(gitea.publicKeys(student.sub())).containsExactlyInAnyOrder(keyOfFirstComputer, keyOfSecondComputer);
    }

    @Test
    void anInstructorCanRegisterAKey() {
        var instructor = classroom.newTaughtClassGroup(1).instructor();
        var key = newPublicKey();

        register(instructor.token(), key).expectStatus().isNoContent();

        assertThat(gitea.publicKeys(instructor.sub())).containsExactly(key);
    }

    @Test
    void makesAMissingTechnicalAccount() {
        var student = classroom.newTaughtClassGroup(1).students().getFirst();
        gitea.deleteAccount(student.sub());
        var key = newPublicKey();

        register(student.token(), key).expectStatus().isNoContent();

        assertThat(gitea.publicKeys(student.sub())).containsExactly(key);
    }

    @Test
    void registeringTheSameKeyAgainChangesNothingEvenWithAnotherComment() {
        var student = classroom.newTaughtClassGroup(1).students().getFirst();
        var key = newPublicKey();

        register(student.token(), key).expectStatus().isNoContent();
        register(student.token(), key).expectStatus().isNoContent();
        register(student.token(), key + "-renamed").expectStatus().isNoContent();

        assertThat(gitea.publicKeys(student.sub())).containsExactly(key);
    }

    @Test
    void refusesInvalidKeysAndAddsNothing() {
        var student = classroom.newTaughtClassGroup(1).students().getFirst();
        var valid = newPublicKey();
        var invalid = List.of(
                "",
                "   ",
                "not a key",
                "ssh-ed25519",
                "ssh-ed25519 AAAAnotbase64!!!",
                "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----",
                valid.substring(0, valid.length() / 2));

        for (var key : invalid) {
            register(student.token(), key)
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.detail").isNotEmpty();
        }
        http.post().uri("/api/ssh-keys")
                .header("Authorization", "Bearer " + student.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(gitea.publicKeys(student.sub())).isEmpty();
    }

    @Test
    void acceptsAKeyOnlyInTheFormOfAPubFile() {
        var student = classroom.newTaughtClassGroup(1).students().getFirst();
        var parts = newPublicKey().split(" ");
        var wrapped = parts[0] + " " + parts[1].substring(0, 20) + "\n" + parts[1].substring(20);
        var ssh2 = "---- BEGIN SSH2 PUBLIC KEY ----\n" + parts[1] + "\n---- END SSH2 PUBLIC KEY ----";

        for (var key : List.of(parts[1], wrapped, ssh2)) {
            register(student.token(), key)
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.detail").isNotEmpty();
        }

        assertThat(gitea.publicKeys(student.sub())).isEmpty();
    }

    @Test
    void refusesAKeyRegisteredToSomeoneElse() {
        var students = classroom.newTaughtClassGroup(2).students();
        var owner = students.get(0);
        var other = students.get(1);
        var key = newPublicKey();
        register(owner.token(), key).expectStatus().isNoContent();

        register(other.token(), key)
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isNotEmpty();

        assertThat(gitea.publicKeys(owner.sub())).containsExactly(key);
        assertThat(gitea.publicKeys(other.sub())).isEmpty();
    }

    @Test
    void refusesAUserMissingFromTheDatabaseAndAnyoneWithoutAToken() {
        var stranger = UUID.randomUUID().toString();
        var token = classroom.tokenWith(stranger, cognito.newGroupNumber() + "c");

        register(token, newPublicKey()).expectStatus().isForbidden();

        assertThat(gitea.accountExists(stranger)).isFalse();
        http.post().uri("/api/ssh-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("publicKey", newPublicKey()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private RestTestClient.ResponseSpec register(String token, String publicKey) {
        return http.post().uri("/api/ssh-keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("publicKey", publicKey))
                .exchange();
    }

    private String newPublicKey() {
        try {
            return gitea.newGitUser(Files.createTempDirectory(computers, "computer")).publicKey();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
