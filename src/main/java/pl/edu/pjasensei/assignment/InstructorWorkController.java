package pl.edu.pjasensei.assignment;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * What an Instructor sees of their own Assignments. An Assignment of another Instructor is as unknown
 * to them as one that does not exist.
 */
@RestController
class InstructorWorkController {

    private final AssignmentStore store;

    InstructorWorkController(AssignmentStore store) {
        this.store = store;
    }

    @GetMapping("/api/assignments")
    Assignments list(JwtAuthenticationToken authentication) {
        return new Assignments(store.assignmentsSentBy(authentication.getName()));
    }

    @GetMapping("/api/assignments/{id}")
    OpenedAssignment open(@PathVariable long id, JwtAuthenticationToken authentication) {
        var title = store.titleOf(id, authentication.getName())
                .orElseThrow(() -> new AssignmentRefused(HttpStatus.NOT_FOUND, "You sent no such Assignment."));
        return new OpenedAssignment(id, title, store.recipientAddressesOf(id));
    }

    @ExceptionHandler
    ProblemDetail refused(AssignmentRefused refused) {
        return ProblemDetail.forStatusAndDetail(refused.status(), refused.getMessage());
    }

    record Assignments(List<AssignmentStore.AssignmentSummary> assignments) {}

    /** A recipient without a StudentRepository has a null {@code sshUrl}. */
    record OpenedAssignment(long id, String title, List<AssignmentStore.RecipientAddress> recipients) {}
}
