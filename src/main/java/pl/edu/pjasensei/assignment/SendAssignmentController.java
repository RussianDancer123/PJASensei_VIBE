package pl.edu.pjasensei.assignment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import pl.edu.pjasensei.identity.TaughtClassGroups;

/** The two requests of sending an Assignment: see {@link SendAssignment}. */
@RestController
class SendAssignmentController {

    private static final Logger log = LoggerFactory.getLogger(SendAssignmentController.class);

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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void createRepositories(@PathVariable long id, JwtAuthenticationToken authentication) {
        sendAssignment.createRepositories(id, authentication.getName());
    }

    @ExceptionHandler
    ProblemDetail refused(AssignmentRefused refused) {
        return ProblemDetail.forStatusAndDetail(refused.status(), refused.getMessage());
    }

    @ExceptionHandler
    ProblemDetail giteaFailed(RestClientException e) {
        log.warn("Could not create StudentRepositories", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Could not create the StudentRepositories.");
    }

    record Created(long id) {}
}
