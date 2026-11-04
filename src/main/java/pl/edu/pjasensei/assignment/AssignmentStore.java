package pl.edu.pjasensei.assignment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Assignments, their recipients and their StudentRepositories. */
@Component
class AssignmentStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    AssignmentStore(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    boolean isInstructor(String sub) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM instructors WHERE sub = ?)")
                .param(sub)
                .query(Boolean.class)
                .single();
    }

    long save(String title, String sourceUrl, String snapshot, String instructorSub, Collection<String> recipients) {
        return transaction.execute(status -> {
            long id = jdbc.sql("""
                            INSERT INTO assignments (title, source_url, snapshot_repository, instructor_sub)
                            VALUES (?, ?, ?, ?)
                            RETURNING id
                            """)
                    .params(title, sourceUrl, snapshot, instructorSub)
                    .query(Long.class)
                    .single();
            for (String recipient : recipients) {
                jdbc.sql("INSERT INTO assignment_recipients (assignment_id, student_sub) VALUES (?, ?)")
                        .params(id, recipient)
                        .update();
            }
            return id;
        });
    }

    /** The snapshot of an Assignment, if this Instructor is the one who sent it. */
    Optional<String> snapshotOf(long assignment, String instructorSub) {
        return jdbc.sql("SELECT snapshot_repository FROM assignments WHERE id = ? AND instructor_sub = ?")
                .params(assignment, instructorSub)
                .query(String.class)
                .optional();
    }

    List<Recipient> recipientsWithoutRepository(long assignment) {
        return jdbc.sql("""
                        SELECT s.sub, s.student_number
                        FROM assignment_recipients r
                        JOIN students s ON s.sub = r.student_sub
                        LEFT JOIN student_repositories sr
                            ON sr.assignment_id = r.assignment_id AND sr.student_sub = r.student_sub
                        WHERE r.assignment_id = ? AND sr.student_sub IS NULL
                        ORDER BY s.student_number
                        """)
                .param(assignment)
                .query(Recipient.class)
                .list();
    }

    void saveRepository(long assignment, String studentSub, String repositoryName, String sshUrl) {
        jdbc.sql("""
                        INSERT INTO student_repositories (assignment_id, student_sub, repository_name, ssh_url)
                        VALUES (?, ?, ?, ?)
                        """)
                .params(assignment, studentSub, repositoryName, sshUrl)
                .update();
    }

    /** The newest first. A recipient whose StudentRepository was never made has nothing to start. */
    List<StudentAssignment> assignmentsWithRepositoryOf(String studentSub) {
        return jdbc.sql("""
                        SELECT a.id, a.title
                        FROM student_repositories sr JOIN assignments a ON a.id = sr.assignment_id
                        WHERE sr.student_sub = ?
                        ORDER BY a.id DESC
                        """)
                .param(studentSub)
                .query(StudentAssignment.class)
                .list();
    }

    Optional<Work> workOf(long assignment, String studentSub) {
        return jdbc.sql("""
                        SELECT a.title, sr.repository_name, sr.ssh_url
                        FROM student_repositories sr JOIN assignments a ON a.id = sr.assignment_id
                        WHERE sr.assignment_id = ? AND sr.student_sub = ?
                        """)
                .params(assignment, studentSub)
                .query(Work.class)
                .optional();
    }

    record Recipient(String sub, String studentNumber) {}

    record StudentAssignment(long id, String title) {}

    record Work(String title, String repositoryName, String sshUrl) {}
}
