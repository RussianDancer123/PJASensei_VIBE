package pl.edu.pjasensei.assignment;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import pl.edu.pjasensei.gitea.GiteaClient;

/**
 * What a Student sees of their work. Everything goes by their StudentRepository, so an Assignment
 * without one is as unknown to them as one that does not exist.
 */
@RestController
class StudentWorkController {

    private static final Logger log = LoggerFactory.getLogger(StudentWorkController.class);

    private final AssignmentStore store;
    private final GiteaClient gitea;

    StudentWorkController(AssignmentStore store, GiteaClient gitea) {
        this.store = store;
        this.gitea = gitea;
    }

    @GetMapping("/api/student/assignments")
    Assignments list(JwtAuthenticationToken authentication) {
        return new Assignments(store.assignmentsWithRepositoryOf(authentication.getName()));
    }

    @GetMapping("/api/student/assignments/{id}")
    Preview preview(@PathVariable long id, JwtAuthenticationToken authentication) {
        var repository = studentRepositoryOf(id, authentication);
        return new Preview(id, repository.assignmentTitle(), gitea.readme(repository.repositoryName()).orElse(null));
    }

    @GetMapping("/api/student/assignments/{id}/ssh-address")
    SshAddress sshAddress(@PathVariable long id, JwtAuthenticationToken authentication) {
        return new SshAddress(studentRepositoryOf(id, authentication).sshUrl());
    }

    private AssignmentStore.StudentRepository studentRepositoryOf(
            long assignment, JwtAuthenticationToken authentication) {
        return store.studentRepositoryOf(assignment, authentication.getName())
                .orElseThrow(() -> new AssignmentRefused(HttpStatus.NOT_FOUND, "You have no such Assignment."));
    }

    @ExceptionHandler
    ProblemDetail refused(AssignmentRefused refused) {
        return ProblemDetail.forStatusAndDetail(refused.status(), refused.getMessage());
    }

    @ExceptionHandler
    ProblemDetail giteaFailed(RestClientException e) {
        log.warn("Could not read a StudentRepository", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Could not read the README.");
    }

    record Assignments(List<AssignmentStore.AssignmentSummary> assignments) {}

    record Preview(long id, String title, String readme) {}

    record SshAddress(String sshUrl) {}
}
