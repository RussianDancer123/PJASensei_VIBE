-- A plain flag the Student sets and clears. It changes no access and is read by nothing but the screens.
ALTER TABLE student_repositories ADD COLUMN finished boolean NOT NULL DEFAULT false;
