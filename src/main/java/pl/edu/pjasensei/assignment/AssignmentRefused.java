package pl.edu.pjasensei.assignment;

import org.springframework.http.HttpStatus;

/** A request that cannot go ahead, with a reason the Instructor can read. */
class AssignmentRefused extends RuntimeException {

    private final HttpStatus status;

    AssignmentRefused(HttpStatus status, String reason) {
        super(reason);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
