-- Who is in a ClassGroup right now. Only Refresh writes here, and no work refers to it, so changing
-- or removing a membership affects no Assignment.
CREATE TABLE class_group_members (
    class_group text NOT NULL,
    student_sub text NOT NULL REFERENCES students (sub),
    PRIMARY KEY (class_group, student_sub)
);
