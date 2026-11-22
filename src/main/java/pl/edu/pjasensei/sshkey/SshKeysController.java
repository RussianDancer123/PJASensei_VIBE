package pl.edu.pjasensei.sshkey;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import pl.edu.pjasensei.gitea.GiteaClient;

/**
 * The SSH public keys of the caller's technical Gitea account, which make ordinary git work for them:
 * listed, added and removed. The same way in for Students and Instructors, so the known-user rule is the
 * only requirement.
 */
@RestController
class SshKeysController {

    private static final Logger log = LoggerFactory.getLogger(SshKeysController.class);
    private static final String INVALID_KEY = "That is not a valid SSH public key. Paste the contents of a .pub file.";

    private final GiteaClient gitea;

    SshKeysController(GiteaClient gitea) {
        this.gitea = gitea;
    }

    /** Only the caller's own keys, oldest first. Lists without making a technical account. */
    @GetMapping("/api/ssh-keys")
    List<Key> list(JwtAuthenticationToken authentication) {
        return gitea.publicKeys(authentication.getName()).stream()
                .map(key -> new Key(key.id(), key.fingerprint(), key.createdAt().toInstant()))
                .toList();
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

    /** Another person's key is not found either, so its id tells the caller nothing. */
    @DeleteMapping("/api/ssh-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, JwtAuthenticationToken authentication) {
        if (!gitea.deletePublicKey(authentication.getName(), id)) {
            throw new KeyRefused(HttpStatus.NOT_FOUND, "You have no such key.");
        }
    }

    @ExceptionHandler
    ProblemDetail refused(KeyRefused refused) {
        return ProblemDetail.forStatusAndDetail(refused.status, refused.getMessage());
    }

    @ExceptionHandler
    ProblemDetail giteaFailed(RestClientException e) {
        log.warn("Gitea failed during an SSH key request", e);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "Could not reach the SSH key store. Please try again later.");
    }

    private static final class KeyRefused extends RuntimeException {

        private final HttpStatus status;

        KeyRefused(HttpStatus status, String reason) {
            super(reason);
            this.status = status;
        }
    }

    record Request(String publicKey) {}

    record Key(long id, String fingerprint, Instant createdAt) {}
}
