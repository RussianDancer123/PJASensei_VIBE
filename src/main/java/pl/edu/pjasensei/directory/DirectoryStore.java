package pl.edu.pjasensei.directory;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** The people and ClassGroup memberships Refresh keeps. It stores no work, so none is affected. */
@Component
public class DirectoryStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    DirectoryStore(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    void saveInstructor(String sub, String email) {
        jdbc.sql("""
                        INSERT INTO instructors (sub, email) VALUES (:sub, :email)
                        ON CONFLICT (sub) DO UPDATE SET email = EXCLUDED.email
                        """)
                .param("sub", sub)
                .param("email", email)
                .update();
    }

    boolean isStudentSaved(String sub) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM students WHERE sub = ?)")
                .param(sub)
                .query(Boolean.class)
                .single();
    }

    /** Another Instructor's Refresh may have just added this Student, who then stays as they are. */
    void addStudent(String sub, String email, String studentNumber) {
        jdbc.sql("""
                        INSERT INTO students (sub, email, student_number) VALUES (:sub, :email, :studentNumber)
                        ON CONFLICT (sub) DO NOTHING
                        """)
                .param("sub", sub)
                .param("email", email)
                .param("studentNumber", studentNumber)
                .update();
    }

    /**
     * All at once, so a reader sees the old members or the new ones. Another Instructor of the same
     * ClassGroup may be refreshing it at this moment, so replacements of one ClassGroup take turns:
     * otherwise both sets of members would survive.
     */
    void replaceMembers(String classGroup, Collection<String> studentSubs) {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param(classGroup).query(Object.class).single();
            jdbc.sql("DELETE FROM class_group_members WHERE class_group = ?").param(classGroup).update();
            for (String sub : studentSubs) {
                // Result pages may repeat a person if the group changes while it is being read.
                jdbc.sql("""
                                INSERT INTO class_group_members (class_group, student_sub) VALUES (?, ?)
                                ON CONFLICT DO NOTHING
                                """)
                        .params(classGroup, sub)
                        .update();
            }
        });
    }

    public List<Student> studentsOf(String classGroup) {
        return jdbc.sql("""
                        SELECT s.sub, s.email, s.student_number
                        FROM class_group_members m JOIN students s ON s.sub = m.student_sub
                        WHERE m.class_group = ?
                        ORDER BY s.student_number
                        """)
                .param(classGroup)
                .query(Student.class)
                .list();
    }

    public record Student(String sub, String email, String studentNumber) {}
}
