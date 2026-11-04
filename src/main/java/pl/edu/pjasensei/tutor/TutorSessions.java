package pl.edu.pjasensei.tutor;

import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

/** The one TutorSession of each StudentRepository, of which Spring keeps only the link. */
@Component
class TutorSessions {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final AiClient ai;

    TutorSessions(JdbcClient jdbc, TransactionTemplate transaction, AiClient ai) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.ai = ai;
    }

    /** The StudentRepository of this Student for this Assignment, if they have one. */
    Optional<StudentRepository> studentRepositoryOf(long assignment, String studentSub) {
        return jdbc.sql("""
                        SELECT assignment_id, student_sub, session_id
                        FROM student_repositories
                        WHERE assignment_id = ? AND student_sub = ?
                        """)
                .params(assignment, studentSub)
                .query(StudentRepository.class)
                .optional();
    }

    /**
     * Runs a request on the TutorSession of a StudentRepository, making one on first use. A session the
     * AI no longer knows is replaced and the request retried once, after which its 404 stands.
     */
    <T> T call(StudentRepository repository, Function<String, T> request) {
        var session = repository.sessionId() != null ? repository.sessionId() : sessionReplacing(null, repository);
        try {
            return request.apply(session);
        } catch (HttpClientErrorException.NotFound unknownSession) {
            return request.apply(sessionReplacing(session, repository));
        }
    }

    /**
     * The session to use instead of this one, or instead of none. The row stays locked while the AI makes
     * it, so a simultaneous request waits here and then finds that session rather than making its own.
     */
    private String sessionReplacing(String replaced, StudentRepository repository) {
        return transaction.execute(status -> {
            var stored = jdbc.sql("""
                            SELECT session_id
                            FROM student_repositories
                            WHERE assignment_id = ? AND student_sub = ?
                            FOR UPDATE
                            """)
                    .params(repository.assignmentId(), repository.studentSub())
                    .query(String.class)
                    .optional();
            if (stored.isPresent() && !stored.get().equals(replaced)) {
                return stored.get();
            }
            var session = ai.createSession();
            jdbc.sql("UPDATE student_repositories SET session_id = ? WHERE assignment_id = ? AND student_sub = ?")
                    .params(session, repository.assignmentId(), repository.studentSub())
                    .update();
            return session;
        });
    }

    record StudentRepository(long assignmentId, String studentSub, String sessionId) {}
}
