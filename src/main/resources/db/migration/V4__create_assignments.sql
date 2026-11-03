-- The work of Instructors and Students. It refers to people, never to a ClassGroup, so a Refresh that
-- changes a membership leaves all of it alone.
CREATE TABLE assignments (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title               text NOT NULL,
    source_url          text NOT NULL,
    snapshot_repository text NOT NULL,
    instructor_sub      text NOT NULL REFERENCES instructors (sub)
);

CREATE TABLE assignment_recipients (
    assignment_id bigint NOT NULL REFERENCES assignments (id),
    student_sub   text   NOT NULL REFERENCES students (sub),
    PRIMARY KEY (assignment_id, student_sub)
);

CREATE TABLE student_repositories (
    assignment_id   bigint NOT NULL,
    student_sub     text   NOT NULL,
    repository_name text   NOT NULL UNIQUE,
    ssh_url         text   NOT NULL,
    PRIMARY KEY (assignment_id, student_sub),
    FOREIGN KEY (assignment_id, student_sub) REFERENCES assignment_recipients (assignment_id, student_sub)
);
