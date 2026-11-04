package pl.edu.pjasensei.tutor;

import org.springframework.http.HttpStatus;

/** A request that cannot go ahead, with a reason the Student can read. */
class TutorRefused extends RuntimeException {

    private final HttpStatus status;

    TutorRefused(HttpStatus status, String reason) {
        super(reason);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
