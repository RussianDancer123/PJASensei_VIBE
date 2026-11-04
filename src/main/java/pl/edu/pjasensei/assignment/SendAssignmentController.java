package pl.edu.pjasensei.assignment;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.edu.pjasensei.identity.TaughtClassGroups;

/** The two requests of sending an Assignment: see {@link SendAssignment}. */
@RestController
class SendAssignmentController {

    private final SendAssignment sendAssignment;
    private final TaughtClassGroups taughtClassGroups;

    SendAssignmentController(SendAssignment sendAssignment, TaughtClassGroups taughtClassGroups) {
        this.sendAssignment = sendAssignment;
        this.taughtClassGroups = taughtClassGroups;
    }

    @PostMapping("/api/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@RequestBody SendAssignment.Request request, JwtAuthenticationToken authentication) {
        return new Created(sendAssignment.create(
                request, authentication.getName(), taughtClassGroups.of(authentication.getToken())));
    }

    @PostMapping("/api/assignments/{id}/repositories")
    RepositoriesResponse createRepositories(@PathVariable long id, JwtAuthenticationToken authentication) {
        return new RepositoriesResponse(sendAssignment.createRepositories(id, authentication.getName()));
    }

    @ExceptionHandler
    ProblemDetail refused(AssignmentRefused refused) {
        return ProblemDetail.forStatusAndDetail(refused.status(), refused.getMessage());
    }

    record Created(long id) {}

    /** The Students left without a StudentRepository. */
    record RepositoriesResponse(List<AssignmentStore.Recipient> failures) {}
}
