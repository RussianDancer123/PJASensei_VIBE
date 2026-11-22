package pl.edu.pjasensei.directory;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The name shown for a person: the part of their email before the {@code @}. It never fails, so
 * that asking who someone is keeps working when the database or the identity provider does not.
 */
@Component
public class DisplayNames {

    private static final Logger log = LoggerFactory.getLogger(DisplayNames.class);

    private final DirectoryStore store;
    private final CognitoDirectory directory;

    DisplayNames(DirectoryStore store, CognitoDirectory directory) {
        this.store = store;
        this.directory = directory;
    }

    /**
     * Reads the stored email first, and the identity provider only for someone nobody has stored,
     * such as an Instructor before their first Refresh. Falls back to the {@code username} claim,
     * which may itself be null.
     */
    public String of(String sub, String username) {
        String email = storedEmail(sub).or(() -> emailFromIdentityProvider(username)).orElse(null);
        return Email.isUsable(email) ? Email.localPart(email) : username;
    }

    private Optional<String> storedEmail(String sub) {
        try {
            return store.emailOf(sub);
        } catch (RuntimeException e) {
            log.warn("Could not read the stored email of {}", sub, e);
            return Optional.empty();
        }
    }

    private Optional<String> emailFromIdentityProvider(String username) {
        if (username == null) {
            return Optional.empty();
        }
        try {
            return directory.emailOf(username);
        } catch (RuntimeException e) {
            log.warn("Could not read {} from the identity provider", username, e);
            return Optional.empty();
        }
    }
}
