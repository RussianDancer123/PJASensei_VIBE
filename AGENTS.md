## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues (`RussianDancer123/PJASensei_VIBE`, via `gh`). See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Docker

Tests and the local stack need Docker. If the Docker daemon is not running, stop and ask the user to start Docker Desktop. Do not work around it: no skipping tests, no swapping in H2 or fakes, no continuing without having run them.
