-- The AI generates this and owns the messages, so the link is all that is kept of a TutorSession.
ALTER TABLE student_repositories ADD COLUMN session_id text;
