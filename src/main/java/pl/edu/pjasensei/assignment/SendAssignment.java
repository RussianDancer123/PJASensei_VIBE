package pl.edu.pjasensei.assignment;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import pl.edu.pjasensei.directory.DirectoryStore;
import pl.edu.pjasensei.gitea.GiteaClient;

/**
 * Sending takes two requests, so the Instructor learns at once whether the copy from the
 * SourceRepository worked and then sees progress while StudentRepositories are made.
 */
@Component
@EnableConfigurationProperties(AssignmentProperties.class)
class SendAssignment {

    private static final Logger log = LoggerFactory.getLogger(SendAssignment.class);
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int ATTEMPTS_PER_REPOSITORY = 3;
    private static final Duration PAUSE_BETWEEN_ATTEMPTS = Duration.ofSeconds(1);

    private final AssignmentStore store;
    private final DirectoryStore directory;
    private final GiteaClient gitea;
    private final Set<String> allowedSourceHosts;

    SendAssignment(
            AssignmentStore store, DirectoryStore directory, GiteaClient gitea, AssignmentProperties properties) {
        this.store = store;
        this.directory = directory;
        this.gitea = gitea;
        this.allowedSourceHosts = properties.allowedSourceHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Copies the SourceRepository once into a snapshot, so later changes to it reach nobody, and
     * keeps the Assignment only if that worked.
     */
    long create(Request request, String instructorSub, List<String> taughtClassGroups) {
        if (request.title() == null || request.title().isBlank() || request.title().length() > MAX_TITLE_LENGTH) {
            throw new AssignmentRefused(
                    HttpStatus.BAD_REQUEST,
                    "A title is required, of at most %d characters.".formatted(MAX_TITLE_LENGTH));
        }
        if (!isAllowedSource(request.sourceUrl())) {
            throw new AssignmentRefused(
                    HttpStatus.BAD_REQUEST,
                    "The source must be an HTTPS URL of a repository on: " + String.join(", ", allowedSourceHosts));
        }
        if (request.recipients() == null || request.recipients().isEmpty()) {
            throw new AssignmentRefused(HttpStatus.BAD_REQUEST, "Choose at least one Student.");
        }
        var recipients = new LinkedHashSet<>(request.recipients());
        if (!taughtStudents(taughtClassGroups).containsAll(recipients)) {
            throw new AssignmentRefused(HttpStatus.FORBIDDEN, "You may only choose Students of ClassGroups you teach.");
        }
        // The known-user rule also accepts a Student row, and a failed Refresh may leave a person who
        // has an Instructor group in their token with only that.
        if (!store.isInstructor(instructorSub)) {
            throw new AssignmentRefused(HttpStatus.FORBIDDEN, "Run Refresh first, to add yourself as an Instructor.");
        }

        String snapshot;
        try {
            snapshot = gitea.importSource(request.sourceUrl());
        } catch (RestClientException e) {
            log.warn("Could not import {}", request.sourceUrl(), e);
            throw new AssignmentRefused(
                    HttpStatus.BAD_GATEWAY, "Could not copy the repository. Is the URL right, and is it public?");
        }
        try {
            gitea.prepareSnapshot(snapshot);
            return store.save(request.title(), request.sourceUrl(), snapshot, instructorSub, recipients);
        } catch (RestClientException e) {
            log.warn("Could not prepare snapshot {}", snapshot, e);
            discardSnapshot(snapshot);
            throw new AssignmentRefused(
                    HttpStatus.BAD_GATEWAY, "The repository was copied, but could not be prepared. Please try again.");
        } catch (DataAccessException e) {
            discardSnapshot(snapshot);
            throw e;
        }
    }

    /** No Assignment will use this snapshot. The Instructor's answer does not depend on removing it. */
    private void discardSnapshot(String snapshot) {
        try {
            gitea.deleteRepository(snapshot);
        } catch (RestClientException e) {
            log.warn("Could not remove unused snapshot {}", snapshot, e);
        }
    }

    /**
     * Makes the StudentRepositories one at a time, each a fresh start from the snapshot, so one that
     * fails costs the others nothing. Safe to repeat: only recipients without one are handled.
     *
     * @return the recipients still without a StudentRepository, because every attempt failed
     */
    List<AssignmentStore.Recipient> createRepositories(long assignment, String instructorSub) {
        String snapshot = store.snapshotOf(assignment, instructorSub)
                .orElseThrow(() -> new AssignmentRefused(HttpStatus.NOT_FOUND, "You sent no such Assignment."));
        var failures = new ArrayList<AssignmentStore.Recipient>();
        for (var recipient : store.recipientsWithoutRepository(assignment)) {
            if (!createRepository(assignment, snapshot, recipient, instructorSub)) {
                failures.add(recipient);
            }
        }
        return failures;
    }

    private boolean createRepository(
            long assignment, String snapshot, AssignmentStore.Recipient recipient, String instructorSub) {
        String name = "assignment-%d-%s".formatted(assignment, recipient.studentNumber());
        for (int attempt = 1; attempt <= ATTEMPTS_PER_REPOSITORY; attempt++) {
            try {
                // Every step is safe to repeat, so an attempt picks up whatever an earlier one left.
                gitea.ensureAccount(recipient.sub());
                gitea.ensureAccount(instructorSub);
                String sshUrl = gitea.createFromSnapshot(snapshot, name);
                gitea.grantWrite(name, recipient.sub());
                gitea.grantRead(name, instructorSub);
                store.saveRepository(assignment, recipient.sub(), name, sshUrl);
                return true;
            } catch (RestClientException | DataAccessException e) {
                log.warn("Attempt {} of {} at {} failed", attempt, ATTEMPTS_PER_REPOSITORY, name, e);
            }
            if (attempt < ATTEMPTS_PER_REPOSITORY) {
                pauseBeforeNextAttempt();
            }
        }
        return false;
    }

    /** Attempts in quick succession would all meet the same temporary problem. */
    private static void pauseBeforeNextAttempt() {
        try {
            Thread.sleep(PAUSE_BETWEEN_ATTEMPTS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Set<String> taughtStudents(List<String> taughtClassGroups) {
        return taughtClassGroups.stream()
                .flatMap(classGroup -> directory.studentsOf(classGroup).stream())
                .map(DirectoryStore.Student::sub)
                .collect(Collectors.toSet());
    }

    private boolean isAllowedSource(String url) {
        if (url == null) {
            return false;
        }
        try {
            var uri = new URI(url);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && allowedSourceHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getPort() == -1
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getRawPath().length() > 1;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    record Request(String title, String sourceUrl, List<String> recipients) {}
}
