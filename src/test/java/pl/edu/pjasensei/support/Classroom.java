package pl.edu.pjasensei.support;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.edu.pjasensei.support.TestCognito.Member;

/** Gets people and Assignments ready through the HTTP API, for tests about what happens next. */
public final class Classroom {

    private final RestTestClient http;
    private final TestIdentityProvider identityProvider;
    private final TestCognito cognito;

    public Classroom(RestTestClient http, TestIdentityProvider identityProvider, TestCognito cognito) {
        this.http = http;
        this.identityProvider = identityProvider;
        this.cognito = cognito;
    }

    /** A new ClassGroup, saved with its Students by a Refresh of its new Instructor. */
    public TaughtClassGroup newTaughtClassGroup(int students) {
        int number = cognito.newGroupNumber();
        var members = Stream.generate(Classroom::newMember).limit(students).toList();
        cognito.setGroup(number + "c", members);

        var instructorSub = UUID.randomUUID().toString();
        var username = "instructor-" + instructorSub;
        cognito.addUser(new Member(username, instructorSub, username + "@mail.com"));
        var instructorToken = identityProvider.token(claims -> claims
                .subject(instructorSub)
                .claim("username", username)
                .claim("cognito:groups", List.of(number + "w")));
        http.post().uri("/api/refresh").header("Authorization", "Bearer " + instructorToken).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.failures").isEmpty();

        return new TaughtClassGroup(
                new Person(instructorSub, username, instructorToken),
                number + "c",
                members.stream()
                        .map(member -> new Person(member.sub(), member.username(), tokenWith(member.sub(), number + "c")))
                        .toList());
    }

    /** Sends an Assignment and makes the StudentRepositories of its recipients. */
    public int send(Person instructor, String title, String sourceUrl, List<Person> recipients) {
        int id = JsonPath.read(
                http.post().uri("/api/assignments")
                        .header("Authorization", "Bearer " + instructor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "title", title,
                                "sourceUrl", sourceUrl,
                                "recipients", recipients.stream().map(Person::sub).toList()))
                        .exchange()
                        .expectStatus().isCreated()
                        .expectBody(String.class).returnResult().getResponseBody(),
                "$.id");
        http.post().uri("/api/assignments/{id}/repositories", id)
                .header("Authorization", "Bearer " + instructor.token())
                .exchange()
                .expectStatus().isOk();
        return id;
    }

    /** A Student whose username is also their Student Number, as in s12345@mail.com. */
    private static Member newMember() {
        var studentNumber = "s" + UUID.randomUUID().toString().replace("-", "");
        return new Member(studentNumber, UUID.randomUUID().toString(), studentNumber + "@mail.com");
    }

    /** A valid token of someone with this one group in it. */
    public String tokenWith(String sub, String group) {
        return identityProvider.token(claims -> claims.subject(sub).claim("cognito:groups", List.of(group)));
    }

    /** The name of a StudentRepository in Gitea. A test Student's username is their Student Number. */
    public static String studentRepositoryName(int assignment, Person student) {
        return "assignment-%d-%s".formatted(assignment, student.username());
    }

    public record Person(String sub, String username, String token) {}

    public record TaughtClassGroup(Person instructor, String classGroup, List<Person> students) {}
}
