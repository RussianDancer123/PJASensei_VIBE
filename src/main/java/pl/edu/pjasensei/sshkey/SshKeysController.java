package pl.edu.pjasensei.sshkey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import pl.edu.pjasensei.gitea.GiteaClient;

/**
 * Puts an SSH public key on the caller's technical Gitea account, so ordinary git works for them.
 * The same way in for Students and Instructors, so the known-user rule is the only requirement.
 */
@RestController
class SshKeysController {

    private static final Logger log = LoggerFactory.getLogger(SshKeysController.class);
    private static final String INVALID_KEY = "That is not a valid SSH public key. Paste the contents of a .pub file.";

    private final GiteaClient gitea;

    SshKeysController(GiteaClient gitea) {
        this.gitea = gitea;
    }

    @PostMapping("/api/ssh-keys")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@RequestBody Request request, JwtAuthenticationToken authentication) {
        if (request.publicKey() == null || request.publicKey().isBlank()) {
            throw new KeyRefused(HttpStatus.BAD_REQUEST, INVALID_KEY);
        }
        gitea.ensureAccount(authentication.getName());
        switch (gitea.addPublicKey(authentication.getName(), request.publicKey())) {
            case ADDED -> {}
            case INVALID -> throw new KeyRefused(HttpStatus.BAD_REQUEST, INVALID_KEY);
            case USED_BY_ANOTHER_ACCOUNT -> throw new KeyRefused(
                    HttpStatus.CONFLICT, "This key is already registered to someone else. Please use another one.");
        }
    }

    @ExceptionHandler
    ProblemDetail refused(KeyRefused refused) {
        return ProblemDetail.forStatusAndDetail(refused.status, refused.getMessage());
    }

    @ExceptionHandler
    ProblemDetail giteaFailed(RestClientException e) {
        log.warn("Could not register an SSH key", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Could not register the key.");
    }

    private static final class KeyRefused extends RuntimeException {

        private final HttpStatus status;

        KeyRefused(HttpStatus status, String reason) {
            super(reason);
            this.status = status;
        }
    }

    record Request(String publicKey) {}
}
