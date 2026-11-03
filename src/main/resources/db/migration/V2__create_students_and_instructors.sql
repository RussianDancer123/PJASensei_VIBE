-- The people the database knows. A user missing from both tables gets a 403 (see KnownUserRule).
CREATE TABLE students (
    sub            text PRIMARY KEY,
    email          text NOT NULL,
    student_number text NOT NULL UNIQUE
);

CREATE TABLE instructors (
    sub   text PRIMARY KEY,
    email text NOT NULL
);
