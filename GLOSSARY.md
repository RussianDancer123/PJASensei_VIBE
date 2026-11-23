# PJASensei

Coursework platform: an Instructor hands out a coding task to a group, and each Student works on their own copy of it from their IDE, with a Socratic AI tutor alongside.

## Language

### People

**Instructor**:
A university teacher who hands out Assignments to Students from the ClassGroups they teach.
_Avoid_: Teacher, tutor, ćwiczeniowiec

**Student**:
A person who receives Assignments through their ClassGroup and works on them. Identified by a university-issued Student Number.
_Avoid_: User, participant

**Student Number**:
The stable, university-issued identifier of a Student (the `s12345` in `s12345@mail.com`).
_Avoid_: Login, username, s-ka

**ClassGroup**:
A set of Students taught together, used to find and select Students. Membership comes from the identity provider, where Students of group `17c` are taught by Instructors of the paired group `17p`. A ClassGroup with no members is not shown to an Instructor. Work never belongs to a ClassGroup, so changing or removing one affects no Assignment.
_Avoid_: Group, class, team

### Work

**Assignment**:
A task an Instructor has handed out to individually chosen Students, based on a SourceRepository.
_Avoid_: Task, homework, zadanie, exercise

**SourceRepository**:
The Instructor's own repository, outside this platform, that an Assignment is copied from.
_Avoid_: Template repo, instructor repo, base repo

**StudentRepository**:
One Student's private copy of an Assignment's code, which they clone, work in and push to.
_Avoid_: Fork, task repo, student's assignment

**Finished mark**:
A checkbox on a StudentRepository that its Student ticks to say they are done, and may untick again. It changes nothing else: no access, no deadline, no grade. The Instructor sees it beside that Student in the Assignment, to know which work is ready to check.
_Avoid_: Submission, status, done, handed in

### Tutoring

**TutorSession**:
The Socratic AI conversation tied to one StudentRepository.
_Avoid_: Chat, conversation, AI session
