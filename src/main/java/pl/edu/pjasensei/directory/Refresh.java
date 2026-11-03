package pl.edu.pjasensei.directory;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import pl.edu.pjasensei.gitea.GiteaClient;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Pulls people from the identity provider: the only thing that does. Everything that succeeds is
 * kept and everything that fails is listed in the result, so one bad person or ClassGroup costs
 * nothing else.
 *
 * <p>For now only an Instructor's button triggers this. It may later run nightly, around 01:00 or
 * 02:00, aligned with the updates of the university system.
 */
@Component
class Refresh {

    private static final Logger log = LoggerFactory.getLogger(Refresh.class);

    private static final Pattern USABLE_EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
    private static final String UNREADABLE = "Could not read from the identity provider.";
    private static final String NO_USABLE_EMAIL = "No usable email address.";
    private static final String NOT_SAVED = "Could not save to the database.";

    private final CognitoDirectory directory;
    private final DirectoryStore store;
    private final GiteaClient gitea;

    Refresh(CognitoDirectory directory, DirectoryStore store, GiteaClient gitea) {
        this.directory = directory;
        this.store = store;
        this.gitea = gitea;
    }

    List<Failure> run(Caller caller) {
        var failures = new ArrayList<Failure>();
        refreshInstructor(caller, failures);
        caller.taughtClassGroups().forEach(classGroup -> refreshClassGroup(classGroup, failures));
        return failures;
    }

    private void refreshInstructor(Caller caller, List<Failure> failures) {
        String email;
        try {
            email = directory.emailOf(caller.username()).orElse(null);
        } catch (SdkException e) {
            log.warn("Could not read Instructor {} from the identity provider", caller.username(), e);
            failures.add(new Failure(null, caller.username(), UNREADABLE));
            return;
        }
        if (!isUsable(email)) {
            failures.add(new Failure(null, caller.username(), NO_USABLE_EMAIL));
            return;
        }
        try {
            store.saveInstructor(caller.sub(), email);
        } catch (DataAccessException e) {
            log.warn("Could not save Instructor {}", caller.username(), e);
            failures.add(new Failure(null, caller.username(), NOT_SAVED));
            return;
        }
        ensureGiteaAccount(caller.sub(), null, caller.username(), failures);
    }

    private void refreshClassGroup(String classGroup, List<Failure> failures) {
        List<CognitoDirectory.Member> members;
        try {
            // Every page is read before anything changes, so a failure here leaves the old members.
            members = directory.membersOf(classGroup);
        } catch (SdkException e) {
            log.warn("Could not read ClassGroup {} from the identity provider", classGroup, e);
            failures.add(new Failure(classGroup, null, UNREADABLE));
            return;
        }
        var students = new ArrayList<CognitoDirectory.Member>();
        for (var member : members) {
            if (saveStudent(classGroup, member, failures)) {
                students.add(member);
            }
        }
        try {
            store.replaceMembers(classGroup, students.stream().map(CognitoDirectory.Member::sub).toList());
        } catch (DataAccessException e) {
            log.warn("Could not save the members of ClassGroup {}", classGroup, e);
            failures.add(new Failure(classGroup, null, NOT_SAVED));
            return;
        }
        students.forEach(student -> ensureGiteaAccount(student.sub(), classGroup, student.username(), failures));
    }

    /** A person stays in the database without an account: the next Refresh makes it. */
    private void ensureGiteaAccount(String sub, String classGroup, String person, List<Failure> failures) {
        try {
            gitea.ensureAccount(sub);
        } catch (RestClientException e) {
            log.warn("Could not make the Gitea account of {}", person, e);
            failures.add(new Failure(classGroup, person, "Could not create the Gitea account."));
        }
    }

    private boolean saveStudent(String classGroup, CognitoDirectory.Member member, List<Failure> failures) {
        if (!isUsable(member.email())) {
            failures.add(new Failure(classGroup, member.username(), NO_USABLE_EMAIL));
            return false;
        }
        if (member.sub() == null) {
            failures.add(new Failure(classGroup, member.username(), "The identity provider gave no sub."));
            return false;
        }
        try {
            store.saveStudent(member.sub(), member.email(), member.studentNumber());
            return true;
        } catch (DuplicateKeyException e) {
            failures.add(new Failure(
                    classGroup,
                    member.username(),
                    "Student Number %s belongs to someone else.".formatted(member.studentNumber())));
            return false;
        } catch (DataAccessException e) {
            log.warn("Could not save Student {}", member.username(), e);
            failures.add(new Failure(classGroup, member.username(), NOT_SAVED));
            return false;
        }
    }

    private static boolean isUsable(String email) {
        return email != null && USABLE_EMAIL.matcher(email).matches();
    }

    record Caller(String sub, String username, List<String> taughtClassGroups) {}

    /** What went wrong and where: a ClassGroup, a person, or a person within a ClassGroup. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Failure(String classGroup, String person, String reason) {}
}
