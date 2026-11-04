package pl.edu.pjasensei.tutor;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** The one TutorSession of each StudentRepository, of which Spring keeps only the link. */
@Component
class TutorSessions {

    private static final int LOCKS = 64;

    private final JdbcClient jdbc;
    private final AiClient ai;
    // Not a lock in the database: that would hold a connection for as long as the AI takes, and an AI
    // that hangs would then use them all up and stop Assignments too. One Spring runs (ADR 0001), so
    // locks here are enough, and sharing them only makes unrelated first uses wait for each other.
    private final ReentrantLock[] locks =
            Stream.generate(ReentrantLock::new).limit(LOCKS).toArray(ReentrantLock[]::new);

    TutorSessions(JdbcClient jdbc, AiClient ai) {
        this.jdbc = jdbc;
        this.ai = ai;
    }

    /** The link of this Student's StudentRepository for this Assignment, if they have one. */
    Optional<SessionLink> linkOf(long assignment, String studentSub) {
        return jdbc.sql("""
                        SELECT assignment_id, student_sub, session_id
                        FROM student_repositories
                        WHERE assignment_id = ? AND student_sub = ?
                        """)
                .params(assignment, studentSub)
                .query(SessionLink.class)
                .optional();
    }

    /**
     * Runs a request on the TutorSession of a link, making one on first use. A session the AI no longer
     * knows is replaced and the request retried once, after which its 404 stands.
     */
    <T> T withSession(SessionLink link, Function<String, T> request) {
        var session = link.sessionId() != null ? link.sessionId() : sessionReplacing(null, link);
        try {
            return request.apply(session);
        } catch (HttpClientErrorException.NotFound unknownSession) {
            return request.apply(sessionReplacing(session, link));
        }
    }

    /**
     * The session to use instead of this one, or instead of none. A simultaneous request waits for the
     * lock and then finds the session just made, rather than making its own.
     */
    private String sessionReplacing(String replaced, SessionLink link) {
        var lock = locks[Math.floorMod(Objects.hash(link.assignmentId(), link.studentSub()), LOCKS)];
        lock.lock();
        try {
            var stored = linkOf(link.assignmentId(), link.studentSub()).map(SessionLink::sessionId);
            if (stored.isPresent() && !stored.get().equals(replaced)) {
                return stored.get();
            }
            var session = ai.createSession();
            jdbc.sql("UPDATE student_repositories SET session_id = ? WHERE assignment_id = ? AND student_sub = ?")
                    .params(session, link.assignmentId(), link.studentSub())
                    .update();
            return session;
        } finally {
            lock.unlock();
        }
    }

    record SessionLink(long assignmentId, String studentSub, String sessionId) {}
}
